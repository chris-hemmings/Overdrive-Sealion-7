package com.overdrive.app.automation.condition;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Minimal sunrise/sunset calculator (NOAA solar-position approximation) used to drive
 * the "sunrise" / "sunset" / day-vs-night automation signals. Pure math — no network,
 * no external library — keyed off the vehicle's current GPS latitude/longitude.
 *
 * <p>Accuracy is ~±1-2 minutes, which is far finer than the 1-minute automation tick,
 * so it's more than enough to fire "at sunset, close the windows" reliably. Returns
 * minutes-since-local-midnight for sunrise and sunset on a given date at a given
 * lat/lon; callers compare those against the current minute-of-day.
 *
 * <p>Polar edge cases (sun never rises / never sets at high latitudes on some dates)
 * are reported via {@link SunTimes#alwaysUp} / {@link SunTimes#alwaysDown} so the
 * caller can still resolve a sensible day/night phase instead of a bogus time.
 */
public final class SolarCalculator {

    private SolarCalculator() {}

    /** Result of a sunrise/sunset computation. Times are minutes since local midnight. */
    public static final class SunTimes {
        public final int sunriseMinute;
        public final int sunsetMinute;
        public final boolean alwaysUp;    // polar day — sun never sets on this date
        public final boolean alwaysDown;  // polar night — sun never rises on this date

        SunTimes(int sunrise, int sunset, boolean up, boolean down) {
            this.sunriseMinute = sunrise;
            this.sunsetMinute = sunset;
            this.alwaysUp = up;
            this.alwaysDown = down;
        }
    }

    private static final double ZENITH = 90.833; // official sunrise/sunset (with refraction)

    /**
     * Compute sunrise/sunset for {@code date} at {@code lat}/{@code lon}, expressed in
     * minutes since midnight in {@code zone} (the device's local zone). Returns null
     * only if the inputs are non-finite.
     */
    public static SunTimes compute(LocalDate date, double lat, double lon, ZoneId zone) {
        if (!isFinite(lat) || !isFinite(lon) || date == null || zone == null) return null;

        int dayOfYear = date.getDayOfYear();

        // Local mean solar noon offsets for rising (hour=6) and setting (hour=18).
        double sunrise = solarEvent(dayOfYear, lat, lon, true);
        double sunset = solarEvent(dayOfYear, lat, lon, false);

        boolean alwaysUp = false, alwaysDown = false;
        if (Double.isNaN(sunrise) || Double.isNaN(sunset)) {
            // Sun doesn't cross the horizon on this date at this latitude. Decide which:
            // use the sun's noon altitude sign as the day/night verdict.
            double noonAltitude = noonSunAltitude(dayOfYear, lat);
            if (noonAltitude > 0) { alwaysUp = true; }
            else { alwaysDown = true; }
            return new SunTimes(0, 24 * 60 - 1, alwaysUp, alwaysDown);
        }

        // Convert UTC hours → local minutes-of-day using the zone's offset for this date.
        int offsetMinutes = zone.getRules()
                .getOffset(date.atTime(12, 0)).getTotalSeconds() / 60;
        int sunriseLocal = wrapMinute((int) Math.round(sunrise * 60) + offsetMinutes);
        int sunsetLocal = wrapMinute((int) Math.round(sunset * 60) + offsetMinutes);
        return new SunTimes(sunriseLocal, sunsetLocal, false, false);
    }

    /**
     * Core NOAA sunrise-equation solve. Returns the event time in UTC HOURS (0-24), or
     * NaN if the sun never reaches the zenith angle on this day (polar case).
     * {@code rising} selects the rising vs setting branch.
     */
    private static double solarEvent(int dayOfYear, double lat, double lon, boolean rising) {
        double lngHour = lon / 15.0;
        double t = dayOfYear + ((rising ? 6.0 : 18.0) - lngHour) / 24.0;

        // Sun's mean anomaly.
        double M = (0.9856 * t) - 3.289;
        // Sun's true longitude.
        double L = M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634;
        L = mod(L, 360);
        // Right ascension, aligned to L's quadrant.
        double RA = atanDeg(0.91764 * tanDeg(L));
        RA = mod(RA, 360);
        RA += (Math.floor(L / 90) * 90) - (Math.floor(RA / 90) * 90);
        RA /= 15.0;
        // Sun's declination.
        double sinDec = 0.39782 * sinDeg(L);
        double cosDec = Math.cos(Math.asin(sinDec));
        // Local hour angle.
        double cosH = (cosDeg(ZENITH) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat));
        if (cosH > 1 || cosH < -1) return Double.NaN; // no sunrise/sunset this day
        double H = rising ? (360 - acosDeg(cosH)) : acosDeg(cosH);
        H /= 15.0;
        // Local mean time of the event, then to UTC.
        double T = H + RA - (0.06571 * t) - 6.622;
        return mod(T - lngHour, 24);
    }

    /** Sun's altitude (degrees) at solar noon — sign gives polar day/night verdict. */
    private static double noonSunAltitude(int dayOfYear, double lat) {
        // Declination approximation (Cooper's equation).
        double decl = 23.44 * sinDeg((360.0 / 365.0) * (dayOfYear - 81));
        return 90 - Math.abs(lat - decl);
    }

    private static int wrapMinute(int m) {
        m %= (24 * 60);
        if (m < 0) m += 24 * 60;
        return m;
    }

    private static boolean isFinite(double d) { return !Double.isNaN(d) && !Double.isInfinite(d); }

    // Degree-based trig helpers (the sunrise equation is expressed in degrees).
    private static double sinDeg(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cosDeg(double d) { return Math.cos(Math.toRadians(d)); }
    private static double tanDeg(double d) { return Math.tan(Math.toRadians(d)); }
    private static double atanDeg(double x) { return Math.toDegrees(Math.atan(x)); }
    private static double acosDeg(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double mod(double a, double b) { double r = a % b; return r < 0 ? r + b : r; }
}
