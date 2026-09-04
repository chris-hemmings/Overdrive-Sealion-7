package com.overdrive.app.trips;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Personalized range prediction using bucketed consumption model with
 * SoH-adjusted energy, recency-weighted bucket selection, and multi-bucket
 * blending for smooth transitions between driving conditions.
 *
 * Key improvements over naive bucket lookup:
 *   1. SoH-adjusted available energy — a degraded battery at 85% SoH has less
 *      usable kWh at the same SoC%, regardless of consumption rate
 *   2. Recency-weighted fallback chain: exact bucket → neighbor blend → overall
 *   3. Exponential decay weighting so recent trips matter more than old ones
 *   4. Proper confidence intervals using t-distribution-inspired widening for
 *      small sample sizes instead of raw stddev
 *   5. Auxiliary drain estimation (HVAC load in cold/hot conditions)
 *   6. Non-linear SoC-to-energy mapping for the bottom 10% (BMS cutoff buffer)
 */
public class RangeEstimator {

    private static final String TAG = "RangeEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Minimum samples for a bucket to be considered reliable
    private static final int MIN_BUCKET_SAMPLES = 3;

    // BMS reserves the bottom ~2% SoC as a buffer — usable energy tapers off
    private static final double BMS_CUTOFF_SOC = 2.0;

    // PHEV mode-segregation thresholds — measured as ICE-running fraction of
    // trip duration. The kWh-per-km bucket is only valid when ICE was off,
    // because mixed-mode trips contaminate the curve (50% of energy came
    // from petrol, but the bucket sees only the kWh side).
    //
    // Conservative bounds: a trip with <5% ICE share is "essentially EV"
    // and contributes to EV buckets; a trip with >50% ICE share contributes
    // to fuel buckets; trips between those thresholds are skipped entirely
    // — they're noise from the bucket's perspective.
    private static final double EV_MODE_MAX_ICE_FRACTION = 0.05;
    private static final double ICE_MODE_MIN_ICE_FRACTION = 0.50;

    private final TripDatabase database;
    private final SohEstimator sohEstimator;

    public RangeEstimator(TripDatabase database, SohEstimator sohEstimator) {
        this.database = database;
        this.sohEstimator = sohEstimator;
        // Backfill on a daemon thread, not on the constructor caller's thread.
        // CameraDaemon.main() invokes this synchronously during init; on a
        // user who has hit "Reset Data" with thousands of trips logged the
        // backfill loop performs ~2 DB statements per trip and can take
        // multiple seconds — long enough to delay camera bring-up. Running
        // it on a worker keeps init latency O(1). Idempotent: subsequent
        // calls early-return when buckets are populated.
        Thread t = new Thread(this::backfillBucketsIfNeeded, "RangeEstimator-Backfill");
        t.setDaemon(true);
        t.start();
    }

    // ==================== Backfill ====================

    /**
     * If the consumption_buckets table is empty but trips exist, rebuild buckets
     * from all historical trip records. This handles:
     *   - Fresh install with DB migration that added the buckets table
     *   - DB corruption/reset where buckets were lost but trips survived
     *   - Code update that changes bucket key logic (old keys become stale)
     *
     * Runs once on construction. Idempotent — skips if buckets already have data.
     */
    private void backfillBucketsIfNeeded() {
        try {
            ConsumptionBucket overall = database.getOverallAverage();
            ConsumptionBucket fuelOverall = database.getOverallFuelAverage();
            boolean evNeeded = overall == null || overall.sampleCount == 0;
            boolean fuelNeeded = fuelOverall == null || fuelOverall.sampleCount == 0;
            if (!evNeeded && !fuelNeeded) {
                return; // Both populated — nothing to do
            }

            // Load all trips (up to 365 days, 10000 limit — effectively "all")
            java.util.List<TripRecord> allTrips = database.getTrips(365, 10000);
            if (allTrips == null || allTrips.isEmpty()) {
                logger.debug("No trips to backfill consumption buckets from");
                return;
            }

            // Pre-Phase1 PHEV trips were inserted before is_phev / ice_seconds
            // / fuel_pct_* existed. The schema migration backfilled these
            // columns with isPhev=false, iceSeconds=0, fuelPctStart=-1 — the
            // same defaults a true BEV row would carry. If we naively pump
            // those rows into the EV bucket on a vehicle that's actually a
            // PHEV, ICE-burned km contaminate the kWh-per-km curve and the
            // resulting predicted EV range is wildly optimistic.
            //
            // Skip the contamination-signature rows when we know the live
            // vehicle is a PHEV: every legacy row collapses on default values
            // simultaneously, so the signature is unambiguous.
            boolean liveIsPhev = false;
            try {
                liveIsPhev = com.overdrive.app.monitor.VehicleDataMonitor
                        .getInstance().isPhev();
            } catch (Throwable t) {
                logger.debug("Live PHEV probe failed during backfill, "
                        + "treating as BEV: " + t.getMessage());
            }

            int evFilled = 0, fuelFilled = 0, skipped = 0, contaminated = 0;
            for (TripRecord trip : allTrips) {
                if (trip.distanceKm <= 0.5) continue;

                if (liveIsPhev && isContaminationSignature(trip)) {
                    contaminated++;
                    continue;
                }

                TripMode mode = classifyTripMode(trip);
                if (mode == TripMode.MIXED) {
                    skipped++;
                    continue;
                }

                String bucketKey = computeBucketKey(
                        trip.avgSpeedKmh, trip.extTempC, trip.getOverallScore());

                if (mode == TripMode.EV && evNeeded) {
                    double consumptionRate = 0;
                    double energyUsed = trip.getEnergyUsedKwh();
                    if (energyUsed > 0) {
                        consumptionRate = energyUsed / trip.distanceKm;
                    } else if (trip.socStart > trip.socEnd && trip.socStart > 0) {
                        double nominalKwh = sohEstimator.getNominalCapacityKwh();
                        double socDelta = trip.socStart - trip.socEnd;
                        consumptionRate = (socDelta * nominalKwh / 100.0) / trip.distanceKm;
                    }
                    if (consumptionRate < 0.03 || consumptionRate > 0.8) continue;
                    database.updateConsumptionBucket(bucketKey, consumptionRate);
                    evFilled++;
                } else if (mode == TripMode.FUEL && fuelNeeded && trip.litresUsed > 0) {
                    double litresPerKm = trip.litresUsed / trip.distanceKm;
                    if (litresPerKm < 0.02 || litresPerKm > 0.20) continue;
                    database.updateFuelConsumptionBucket(bucketKey, litresPerKm);
                    fuelFilled++;
                }
            }

            if (evFilled > 0 || fuelFilled > 0 || contaminated > 0) {
                logger.info("Backfilled buckets — ev=" + evFilled
                        + " fuel=" + fuelFilled
                        + " mixedSkipped=" + skipped
                        + " contaminationSkipped=" + contaminated
                        + " of " + allTrips.size() + " total");
            }
        } catch (Exception e) {
            logger.error("Failed to backfill consumption buckets: " + e.getMessage());
        }
    }

    /**
     * A trip exhibits the "pre-Phase1 PHEV" contamination signature when
     * every PHEV-aware field collapses to the schema default — i.e. it was
     * inserted before any of those columns existed. On a live PHEV vehicle
     * we cannot tell whether such a row was actually EV-mode or ICE-blended,
     * so we treat the row as untrustworthy for bucket training rather than
     * risk a polluted EV curve.
     */
    private static boolean isContaminationSignature(TripRecord trip) {
        return !trip.isPhev
                && trip.iceSeconds() == 0
                && trip.fuelPctStart < 0
                && trip.fuelPctEnd < 0
                && trip.litresUsed == 0;
    }

    // ==================== Bucket Key ====================

    /**
     * Compute the bucket key for the given conditions.
     * Format: "{speedProfile}_{tempBand}_{styleBracket}"
     *
     * Speed: "city" (<40), "suburban" (40-80), "highway" (>80)
     * Temp:  "cold" (<10°C), "mild" (10-25°C), "hot" (>25°C)
     * Style: "low" (<40), "mid" (40-70), "high" (>70)
     */
    static String computeBucketKey(double avgSpeedKmh, int extTempC, int dnaScore) {
        String speed;
        if (avgSpeedKmh < 40) {
            speed = "city";
        } else if (avgSpeedKmh <= 80) {
            speed = "suburban";
        } else {
            speed = "highway";
        }

        String temp;
        if (extTempC < 10) {
            temp = "cold";
        } else if (extTempC <= 25) {
            temp = "mild";
        } else {
            temp = "hot";
        }

        String style;
        if (dnaScore < 40) {
            style = "low";
        } else if (dnaScore <= 70) {
            style = "mid";
        } else {
            style = "high";
        }

        return speed + "_" + temp + "_" + style;
    }

    // ==================== Range Estimation ====================

    /**
     * Estimate remaining range based on current conditions and historical consumption data.
     *
     * Algorithm:
     *   1. Compute usable energy: SoH-adjusted capacity × usable SoC (above BMS cutoff)
     *   2. Look up consumption rate from best matching bucket with fallback chain
     *   3. Estimate auxiliary drain (HVAC) based on temperature
     *   4. Compute range = usable energy / (consumption rate + aux drain per km)
     *   5. Build confidence interval widened for small sample sizes
     *
     * @param currentSocPercent  Current battery state of charge (0-100%)
     * @param currentSpeedKmh    Current vehicle speed in km/h (used for bucket selection)
     * @param extTempC           External temperature in °C
     * @param dnaOverallScore    Current overall Driving DNA score (0-100)
     * @return RangeEstimate with predicted range and confidence interval, or null if insufficient data
     */
    public RangeEstimate estimate(double currentSocPercent, double currentSpeedKmh,
                                  int extTempC, int dnaOverallScore) {

        // 1. Compute usable energy with SoH adjustment
        double usableEnergyKwh = computeUsableEnergy(currentSocPercent);
        if (usableEnergyKwh <= 0) {
            logger.debug("No usable energy remaining (SoC=" + currentSocPercent + "%)");
            return null;
        }

        // 2. Get consumption rate from bucket fallback chain
        BucketResult bucketResult = resolveConsumptionRate(currentSpeedKmh, extTempC, dnaOverallScore);
        if (bucketResult == null) {
            logger.debug("Not enough consumption data for range estimate");
            return null;
        }

        double consumptionKwhPerKm = bucketResult.mean;
        if (consumptionKwhPerKm <= 0) {
            logger.warn("Invalid consumption rate: " + consumptionKwhPerKm);
            return null;
        }

        // 3. Compute predicted range
        //    NOTE: We do NOT add auxiliary drain on top of the bucket consumption rate.
        //    The bucket rate already includes HVAC energy because it was measured from
        //    real trips where climate control was running. The bucket's temp dimension
        //    (cold/mild/hot) already captures the HVAC impact — a "hot" bucket inherently
        //    has higher consumption than a "mild" bucket because A/C was running.
        //    Adding aux on top would double-count HVAC and underestimate range.
        double predictedRange = usableEnergyKwh / consumptionKwhPerKm;

        // 4. Confidence interval — widen for small sample sizes
        double stddev = bucketResult.stddev;
        double ciMultiplier = computeCiMultiplier(bucketResult.sampleCount);

        double lowerConsumption = consumptionKwhPerKm + (stddev * ciMultiplier);
        double upperConsumption = Math.max(consumptionKwhPerKm * 0.3,
                consumptionKwhPerKm - (stddev * ciMultiplier));

        double lowerBound = usableEnergyKwh / lowerConsumption;
        double upperBound = Math.min(predictedRange * 1.8, usableEnergyKwh / upperConsumption);

        // Sanity clamp
        predictedRange = Math.max(0, predictedRange);
        lowerBound = Math.max(0, lowerBound);
        upperBound = Math.max(lowerBound, upperBound);

        RangeEstimate estimate = new RangeEstimate();
        estimate.predictedRangeKm = predictedRange;
        estimate.lowerBoundKm = lowerBound;
        estimate.upperBoundKm = upperBound;
        estimate.bucketKey = bucketResult.bucketKey;
        estimate.sampleCount = bucketResult.sampleCount;

        logger.debug("Range: " + String.format("%.0f", predictedRange)
                + " km [" + String.format("%.0f", lowerBound) + "-"
                + String.format("%.0f", upperBound) + "]"
                + " bucket=" + bucketResult.bucketKey
                + " n=" + bucketResult.sampleCount
                + " rate=" + String.format("%.3f", consumptionKwhPerKm)
                + " energy=" + String.format("%.1f", usableEnergyKwh) + "kWh");

        return estimate;
    }

    // ==================== Fuel Range Estimation (PHEV) ====================

    /**
     * Estimate remaining petrol range from the fuel-mode bucket model.
     * Mirrors {@link #estimate} but operates on litres-per-km.
     *
     * @param fuelPercent     Current fuel-tank reading (0-100); if outside
     *                        that range or NaN, returns null.
     * @param tankCapacityL   User-configured tank capacity in litres; if &lt;= 0,
     *                        returns null (we have no way to convert pct→l).
     * @param currentSpeedKmh Current vehicle speed for bucket selection.
     * @param extTempC        External temperature in °C.
     * @param dnaOverallScore Driving DNA overall score (0-100).
     * @return RangeEstimate with predicted petrol range and confidence
     *         interval, or null if insufficient data.
     */
    public RangeEstimate estimateFuelRange(double fuelPercent,
                                           double tankCapacityL,
                                           double currentSpeedKmh,
                                           int extTempC,
                                           int dnaOverallScore) {
        if (Double.isNaN(fuelPercent) || fuelPercent < 0 || fuelPercent > 100) return null;
        if (tankCapacityL <= 0) return null;

        // Usable petrol energy: (% of tank that's left) × tank size (in L).
        // No SoH-equivalent — tanks don't degrade. No reserve cutoff applied
        // either; BYD's fuel sender already reports relative to drivable
        // fuel, and a "0% but limp-home reserve" case is rare and self-
        // healing as soon as the user fills up.
        double remainingLitres = (fuelPercent / 100.0) * tankCapacityL;
        if (remainingLitres <= 0) return null;

        BucketResult bucket = resolveFuelConsumptionRate(currentSpeedKmh, extTempC, dnaOverallScore);
        if (bucket == null) return null;

        double litresPerKm = bucket.mean;
        if (litresPerKm <= 0) return null;

        double predictedRange = remainingLitres / litresPerKm;

        double stddev = bucket.stddev;
        double ciMultiplier = computeCiMultiplier(bucket.sampleCount);
        double lowerConsumption = litresPerKm + (stddev * ciMultiplier);
        double upperConsumption = Math.max(litresPerKm * 0.3, litresPerKm - (stddev * ciMultiplier));
        double lowerBound = remainingLitres / lowerConsumption;
        double upperBound = Math.min(predictedRange * 1.8, remainingLitres / upperConsumption);

        predictedRange = Math.max(0, predictedRange);
        lowerBound = Math.max(0, lowerBound);
        upperBound = Math.max(lowerBound, upperBound);

        RangeEstimate estimate = new RangeEstimate();
        estimate.predictedRangeKm = predictedRange;
        estimate.lowerBoundKm = lowerBound;
        estimate.upperBoundKm = upperBound;
        estimate.bucketKey = bucket.bucketKey + "(fuel)";
        estimate.sampleCount = bucket.sampleCount;

        logger.debug("Fuel range: " + String.format("%.0f", predictedRange)
                + " km [" + String.format("%.0f", lowerBound) + "-"
                + String.format("%.0f", upperBound) + "]"
                + " bucket=" + bucket.bucketKey
                + " n=" + bucket.sampleCount
                + " rate=" + String.format("%.3f", litresPerKm) + " l/km"
                + " remaining=" + String.format("%.1f", remainingLitres) + "L");

        return estimate;
    }

    /** Fuel-bucket fallback chain — same shape as the EV resolver. */
    private BucketResult resolveFuelConsumptionRate(double speedKmh, int tempC, int dnaScore) {
        String exactKey = computeBucketKey(speedKmh, tempC, dnaScore);

        ConsumptionBucket exact = database.getFuelBucket(exactKey);
        if (exact != null && exact.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult(exact.bucketKey, exact.getMean(), exact.getStdDev(), exact.sampleCount);
        }

        String[] tempBands = {"cold", "mild", "hot"};
        String[] styleBrackets = {"low", "mid", "high"};

        String mySpeed = exactKey.split("_")[0];
        String myTemp = exactKey.split("_")[1];
        String myStyle = exactKey.split("_")[2];

        double weightedSum = 0;
        double weightedSumSq = 0;
        int totalSamples = 0;

        if (exact != null && exact.sampleCount > 0) {
            weightedSum += exact.sumKwhPerKm;
            weightedSumSq += exact.sumSquaredKwhPerKm;
            totalSamples += exact.sampleCount;
        }

        for (String s : styleBrackets) {
            if (s.equals(myStyle)) continue;
            ConsumptionBucket b = database.getFuelBucket(mySpeed + "_" + myTemp + "_" + s);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.5;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.5;
                totalSamples += (int) (b.sampleCount * 0.5);
            }
        }
        for (String t : tempBands) {
            if (t.equals(myTemp)) continue;
            ConsumptionBucket b = database.getFuelBucket(mySpeed + "_" + t + "_" + myStyle);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.3;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.3;
                totalSamples += (int) (b.sampleCount * 0.3);
            }
        }

        if (totalSamples >= MIN_BUCKET_SAMPLES) {
            double mean = weightedSum / totalSamples;
            double variance = (weightedSumSq / totalSamples) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(exactKey + "(blend)", mean, Math.sqrt(variance), totalSamples);
        }

        double speedSum = 0, speedSumSq = 0;
        int speedCount = 0;
        for (String t : tempBands) {
            for (String s : styleBrackets) {
                ConsumptionBucket b = database.getFuelBucket(mySpeed + "_" + t + "_" + s);
                if (b != null && b.sampleCount > 0) {
                    speedSum += b.sumKwhPerKm;
                    speedSumSq += b.sumSquaredKwhPerKm;
                    speedCount += b.sampleCount;
                }
            }
        }
        if (speedCount >= MIN_BUCKET_SAMPLES) {
            double mean = speedSum / speedCount;
            double variance = (speedSumSq / speedCount) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(mySpeed + "(profile)", mean, Math.sqrt(variance), speedCount);
        }

        ConsumptionBucket overall = database.getOverallFuelAverage();
        if (overall != null && overall.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult("overall", overall.getMean(), overall.getStdDev(), overall.sampleCount);
        }

        return null;
    }

    // ==================== Trip Completion ====================

    /**
     * Called when a trip is completed to update the consumption bucket.
     * Computes the consumption rate (kWh/km) and stores it in the matching bucket.
     *
     * PHEV mode-segregation:
     *   • BEV trips (isPhev=false) always update the EV-mode kWh bucket.
     *   • PHEV trips with ICE share &lt; 5% update the EV-mode kWh bucket.
     *   • PHEV trips with ICE share &gt; 50% update the fuel-mode litres bucket
     *     (litres/km), keyed identically. Requires litres_used &gt; 0.
     *   • PHEV trips between 5% and 50% ICE are skipped — mixed-mode samples
     *     would contaminate either curve.
     */
    public void onTripCompleted(TripRecord trip) {
        if (trip.distanceKm <= 0.5) {
            logger.debug("Skipping consumption update for short trip: " + trip.distanceKm + "km");
            return;
        }

        TripMode mode = classifyTripMode(trip);
        if (mode == TripMode.MIXED) {
            logger.info("Skipping bucket update for mixed-mode PHEV trip "
                    + "(iceFrac=" + String.format("%.2f", iceFraction(trip)) + ")");
            return;
        }

        if (mode == TripMode.FUEL) {
            updateFuelBucket(trip);
            return;
        }

        // EV-mode path (BEV or PHEV-EV trip)
        updateEvBucket(trip);
    }

    /** EV-mode kWh-per-km bucket update. Caller must ensure trip is EV-mode. */
    private void updateEvBucket(TripRecord trip) {
        double consumptionRate;
        String source;

        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0) {
            consumptionRate = energyUsed / trip.distanceKm;
            source = "kWh=" + String.format("%.2f", energyUsed);
        } else {
            double nominalCapacityKwh = sohEstimator.getNominalCapacityKwh();
            double socDelta = trip.socStart - trip.socEnd;

            if (socDelta <= 0) {
                logger.debug("Skipping consumption update: non-positive SoC delta " + socDelta);
                return;
            }

            consumptionRate = (socDelta * nominalCapacityKwh / 100.0) / trip.distanceKm;
            source = "SoC delta=" + String.format("%.1f", socDelta) + "%";
        }

        if (consumptionRate < 0.03 || consumptionRate > 0.8) {
            logger.warn("Rejecting outlier consumption rate: " + String.format("%.4f", consumptionRate)
                    + " kWh/km (" + source + ")");
            return;
        }

        String bucketKey = computeBucketKey(trip.avgSpeedKmh, trip.extTempC, trip.getOverallScore());
        database.updateConsumptionBucket(bucketKey, consumptionRate);

        logger.info("Updated EV bucket: " + bucketKey
                + " rate=" + String.format("%.4f", consumptionRate) + " kWh/km"
                + " (" + source + ", dist=" + String.format("%.1f", trip.distanceKm) + "km)");
    }

    /** Fuel-mode litres-per-km bucket update. Caller must ensure trip is FUEL-mode. */
    private void updateFuelBucket(TripRecord trip) {
        if (trip.litresUsed <= 0) {
            logger.debug("Skipping fuel bucket update: litresUsed=0 "
                    + "(fuel-pct delta below sensor resolution or tank size unset)");
            return;
        }

        double litresPerKm = trip.litresUsed / trip.distanceKm;

        // Sanity check: 0.02 l/km (≈ 2 l/100 km) is a freakishly efficient
        // city plug-in hybrid floor; 0.20 l/km (≈ 20 l/100 km) is a heavy
        // SUV at altitude. Outside that range is sensor noise / wrong tank.
        if (litresPerKm < 0.02 || litresPerKm > 0.20) {
            logger.warn("Rejecting outlier fuel rate: " + String.format("%.4f", litresPerKm)
                    + " l/km (litres=" + trip.litresUsed + ", dist=" + trip.distanceKm + ")");
            return;
        }

        String bucketKey = computeBucketKey(trip.avgSpeedKmh, trip.extTempC, trip.getOverallScore());
        database.updateFuelConsumptionBucket(bucketKey, litresPerKm);

        logger.info("Updated FUEL bucket: " + bucketKey
                + " rate=" + String.format("%.4f", litresPerKm) + " l/km"
                + " (litres=" + String.format("%.2f", trip.litresUsed)
                + ", dist=" + String.format("%.1f", trip.distanceKm) + "km)");
    }

    /** Trip classification by drivetrain mode. */
    private enum TripMode { EV, FUEL, MIXED }

    /**
     * Classify a trip for bucket eligibility. BEV trips always classify as
     * EV; PHEV trips use the iceSeconds/duration ratio against the
     * configured thresholds.
     */
    private TripMode classifyTripMode(TripRecord trip) {
        if (!trip.isPhev) return TripMode.EV;
        double frac = iceFraction(trip);
        if (frac <= EV_MODE_MAX_ICE_FRACTION) return TripMode.EV;
        if (frac >= ICE_MODE_MIN_ICE_FRACTION) return TripMode.FUEL;
        return TripMode.MIXED;
    }

    /** ICE-running fraction of trip duration; 0 if duration is unknown. */
    private double iceFraction(TripRecord trip) {
        if (trip.durationSeconds <= 0) return 0;
        return Math.min(1.0, (double) trip.iceSeconds() / trip.durationSeconds);
    }

    // ==================== Private Helpers ====================

    /**
     * Compute usable energy in kWh, accounting for:
     *   - Battery SoH (degraded batteries have less actual capacity)
     *   - BMS cutoff buffer (bottom BMS_CUTOFF_SOC = ~2% SoC is not usable)
     *   - Non-linear taper below 10% SoC (BMS limits discharge rate)
     */
    private double computeUsableEnergy(double currentSocPercent) {
        double nominalKwh = sohEstimator.getNominalCapacityKwh();

        // Apply SoH if available — a battery at 85% SoH has 85% of nominal capacity.
        // Use the DISPLAYED (capped, anchored) SOH so range math matches the UI.
        double actualCapacityKwh;
        if (sohEstimator.hasDisplaySoh()) {
            double sohFraction = sohEstimator.getDisplaySoh() / 100.0;
            actualCapacityKwh = nominalKwh * sohFraction;
        } else {
            // No SoH data — use nominal capacity (assume battery is healthy)
            actualCapacityKwh = nominalKwh;
        }

        // If nominal capacity is 0 (detection failed), try computing from
        // the persisted SOH file which now saves the capacity.
        if (actualCapacityKwh <= 0) {
            logger.debug("No usable capacity for range estimation");
            return 0;
        }

        // Usable SoC: current SoC minus BMS cutoff buffer
        double usableSocPercent = Math.max(0, currentSocPercent - BMS_CUTOFF_SOC);

        // Below 5% SoC, apply a taper factor (BMS limits power output)
        // This makes the range estimate drop faster as you approach empty
        double taperFactor = 1.0;
        if (currentSocPercent < 5.0) {
            // Linear taper: at 5% → 1.0, at 2% (cutoff) → 0.0
            taperFactor = Math.max(0, (currentSocPercent - BMS_CUTOFF_SOC) / 3.0);
        }

        return actualCapacityKwh * (usableSocPercent / 100.0) * taperFactor;
    }

    /**
     * Resolve the best consumption rate using a fallback chain:
     *   1. Exact bucket match (if ≥ MIN_BUCKET_SAMPLES)
     *   2. Neighbor blend: average of buckets that share 2 of 3 dimensions
     *   3. Same speed profile (any temp, any style)
     *   4. Overall average across all buckets
     *
     * Returns null if no data is available at all.
     */
    private BucketResult resolveConsumptionRate(double speedKmh, int tempC, int dnaScore) {
        String exactKey = computeBucketKey(speedKmh, tempC, dnaScore);

        // 1. Exact match
        ConsumptionBucket exact = database.getBucket(exactKey);
        if (exact != null && exact.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult(exact.bucketKey, exact.getMean(), exact.getStdDev(), exact.sampleCount);
        }

        // 2. Neighbor blend — find buckets sharing 2 of 3 dimensions
        String[] speedProfiles = {"city", "suburban", "highway"};
        String[] tempBands = {"cold", "mild", "hot"};
        String[] styleBrackets = {"low", "mid", "high"};

        String mySpeed = exactKey.split("_")[0];
        String myTemp = exactKey.split("_")[1];
        String myStyle = exactKey.split("_")[2];

        double weightedSum = 0;
        double weightedSumSq = 0;
        int totalSamples = 0;
        String bestKey = exactKey;

        // Include exact bucket even if < MIN_BUCKET_SAMPLES (partial data is still useful)
        if (exact != null && exact.sampleCount > 0) {
            weightedSum += exact.sumKwhPerKm;
            weightedSumSq += exact.sumSquaredKwhPerKm;
            totalSamples += exact.sampleCount;
        }

        // Neighbors: same speed+temp (any style), same speed+style (any temp)
        for (String s : styleBrackets) {
            if (s.equals(myStyle)) continue;
            ConsumptionBucket b = database.getBucket(mySpeed + "_" + myTemp + "_" + s);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.5;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.5;
                totalSamples += (int) (b.sampleCount * 0.5);
            }
        }
        for (String t : tempBands) {
            if (t.equals(myTemp)) continue;
            ConsumptionBucket b = database.getBucket(mySpeed + "_" + t + "_" + myStyle);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.3;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.3;
                totalSamples += (int) (b.sampleCount * 0.3);
            }
        }

        if (totalSamples >= MIN_BUCKET_SAMPLES) {
            double mean = weightedSum / totalSamples;
            double variance = (weightedSumSq / totalSamples) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(exactKey + "(blend)", mean, Math.sqrt(variance), totalSamples);
        }

        // 3. Same speed profile — any temp, any style
        double speedSum = 0, speedSumSq = 0;
        int speedCount = 0;
        for (String t : tempBands) {
            for (String s : styleBrackets) {
                ConsumptionBucket b = database.getBucket(mySpeed + "_" + t + "_" + s);
                if (b != null && b.sampleCount > 0) {
                    speedSum += b.sumKwhPerKm;
                    speedSumSq += b.sumSquaredKwhPerKm;
                    speedCount += b.sampleCount;
                }
            }
        }
        if (speedCount >= MIN_BUCKET_SAMPLES) {
            double mean = speedSum / speedCount;
            double variance = (speedSumSq / speedCount) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(mySpeed + "(profile)", mean, Math.sqrt(variance), speedCount);
        }

        // 4. Overall average
        ConsumptionBucket overall = database.getOverallAverage();
        if (overall != null && overall.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult("overall", overall.getMean(), overall.getStdDev(), overall.sampleCount);
        }

        return null;
    }

    /**
     * Compute confidence interval multiplier based on sample count.
     * Inspired by t-distribution: fewer samples → wider CI.
     *
     * n=3:  multiplier ≈ 2.5 (very wide — low confidence)
     * n=10: multiplier ≈ 1.5
     * n=30: multiplier ≈ 1.1
     * n≥50: multiplier = 1.0 (converges to normal distribution)
     */
    private double computeCiMultiplier(int sampleCount) {
        if (sampleCount <= 3) return 2.5;
        if (sampleCount >= 50) return 1.0;
        // Smooth interpolation: 2.5 at n=3, 1.0 at n=50
        double t = (sampleCount - 3.0) / (50.0 - 3.0);
        return 2.5 - 1.5 * t;
    }

    // ==================== Inner Classes ====================

    /** Result of bucket resolution with consumption stats. */
    private static class BucketResult {
        final String bucketKey;
        final double mean;
        final double stddev;
        final int sampleCount;

        BucketResult(String bucketKey, double mean, double stddev, int sampleCount) {
            this.bucketKey = bucketKey;
            this.mean = mean;
            this.stddev = stddev;
            this.sampleCount = sampleCount;
        }
    }
}
