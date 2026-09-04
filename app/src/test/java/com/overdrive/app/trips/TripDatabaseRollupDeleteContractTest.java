package com.overdrive.app.trips;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Pins that deleting a trip rebuilds weekly/monthly rollups from remaining
 * rows (COUNT/SUM/AVG) instead of leaving additive totals stale.
 */
public class TripDatabaseRollupDeleteContractTest {

    @Test
    public void deletePathsRecomputeRollupsFromRemainingTrips() throws IOException {
        String src = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripDatabase.java");

        assertTrue(src.contains("public synchronized void recomputeWeeklyRollup(int year, int week)"));
        assertTrue(src.contains("public synchronized void recomputeMonthlyRollup(int year, int month)"));
        assertTrue(src.contains("SELECT COUNT(*) AS trip_count"));
        assertTrue(src.contains("COALESCE(SUM(distance_km), 0)"));
        assertTrue(src.contains("COALESCE(SUM(duration_seconds), 0)"));
        assertTrue(src.contains("COALESCE(AVG(efficiency_soc_per_km), 0)"));

        String weekly = methodBody(src, "public synchronized void recomputeWeeklyRollup");
        assertTrue(weekly.contains("queryTripAggregate"));
        assertTrue(weekly.contains("DELETE FROM weekly_rollups"));
        assertTrue(weekly.contains("tripCount <= 0"));
        assertFalse(weekly.contains("runningAvg("));
        assertFalse(weekly.contains("oldCount - 1"));

        String monthly = methodBody(src, "public synchronized void recomputeMonthlyRollup");
        assertTrue(monthly.contains("queryTripAggregate"));
        assertTrue(monthly.contains("DELETE FROM monthly_rollups"));
        assertTrue(monthly.contains("tripCount <= 0"));
        assertFalse(monthly.contains("runningAvg("));

        String deleteTrip = methodBody(src, "public synchronized boolean deleteTrip");
        assertTrue(deleteTrip.contains("SELECT start_time FROM trips WHERE id=?"));
        int selectAt = deleteTrip.indexOf("SELECT start_time FROM trips WHERE id=?");
        int deleteAt = deleteTrip.indexOf("DELETE FROM trips WHERE id=?");
        assertTrue(selectAt >= 0 && deleteAt > selectAt);
        assertTrue(deleteTrip.contains("recomputeRollupsForStartTimes"));

        String deleteByPath = methodBody(src, "public synchronized int deleteByTelemetryPath");
        assertTrue(deleteByPath.contains("SELECT start_time FROM trips WHERE telemetry_file_path=?"));
        int pathSelectAt = deleteByPath.indexOf(
                "SELECT start_time FROM trips WHERE telemetry_file_path=?");
        int pathDeleteAt = deleteByPath.indexOf(
                "DELETE FROM trips WHERE telemetry_file_path=?");
        assertTrue(pathSelectAt >= 0 && pathDeleteAt > pathSelectAt);
        assertTrue(deleteByPath.contains("recomputeRollupsForStartTimes"));

        String reconcile = methodBody(src,
                "public int deleteRowsWithMissingFiles(java.util.function.Predicate");
        assertTrue(reconcile.contains("SELECT id, telemetry_file_path, start_time FROM trips"));
        assertTrue(reconcile.contains("recomputeRollupsForStartTimes"));

        String helper = methodBody(src, "private void recomputeRollupsForStartTimes");
        assertTrue(helper.contains("recomputeWeeklyRollup"));
        assertTrue(helper.contains("recomputeMonthlyRollup"));
        assertTrue(helper.contains("newTripPeriodCalendar"));
        assertTrue(helper.contains("WEEK_OF_YEAR"));

        String cal = methodBody(src, "private static Calendar newTripPeriodCalendar");
        assertTrue(cal.contains("setMinimalDaysInFirstWeek(4)"));
        assertTrue(cal.contains("Calendar.MONDAY"));
    }

    @Test
    public void periodSummaryAggregatesTripsInTheSelectedDayWindow() throws IOException {
        String db = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripDatabase.java");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripApiHandler.java");
        String js = readRepositoryFile(
                "app/src/main/assets/web/shared/trips.js");

        assertTrue(db.contains("public synchronized WeeklyRollup getPeriodSummary(int days)"));
        String period = methodBody(db, "public synchronized WeeklyRollup getPeriodSummary");
        assertTrue(period.contains("86400000L"));
        assertTrue(period.contains("queryTripAggregate"));

        String summary = methodBody(api, "private JSONObject handleGetSummary");
        assertTrue(summary.contains("getPeriodSummary"));
        assertFalse(summary.contains("getRecentWeeklyRollups"));

        String update = methodBody(js, "updatePeriodSummary() {");
        int emptyCheck = update.indexOf("!trips || trips.length === 0");
        int emptyReturn = update.indexOf("return;", emptyCheck);
        int zeroTrips = update.indexOf("setEl('summaryTrips', 0)");
        assertTrue(emptyCheck >= 0);
        assertTrue(emptyReturn > emptyCheck);
        assertTrue(zeroTrips > emptyCheck && zeroTrips < emptyReturn);
        String emptyBranch = update.substring(emptyCheck, emptyReturn);
        assertFalse(emptyBranch.contains("rangeFromMs"));

        assertTrue(js.contains("this.trips && this.trips.length > 0")
                || js.contains("this.trips.length > 0"));
        assertTrue(js.contains("&& this._lastSummaryPayload)"));
    }

    private static String methodBody(String src, String signaturePrefix) {
        int start = src.indexOf(signaturePrefix);
        assertTrue("missing " + signaturePrefix, start >= 0);
        int brace = src.indexOf('{', start);
        assertTrue(brace > start);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(start, i + 1);
            }
        }
        throw new AssertionError("unclosed method: " + signaturePrefix);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
