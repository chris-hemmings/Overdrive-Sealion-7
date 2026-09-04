package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Calendar;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.BeforeClass;
import org.junit.Test;

public class SocHistoryDatabaseTimeOfUseTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    /** Calendar.FRIDAY = 6 → bit 5. Same as the HTML data-daybit for Friday. */
    private static final int FRIDAY_BIT = 1 << (Calendar.FRIDAY - 1);
    private static final int EVERY_DAY = 127;
    private static final int WEEKDAYS = 62;

    @Test
    public void evChargeWindowCoversFriday0354Local() {
        assertEquals(32, FRIDAY_BIT);
        assertTrue(SocHistoryDatabase.timeTariffCovers(
                EVERY_DAY, 0, 359, FRIDAY_BIT, 3 * 60 + 54));
        assertFalse(SocHistoryDatabase.timeTariffCovers(
                WEEKDAYS, 16 * 60, 19 * 60 + 59, FRIDAY_BIT, 3 * 60 + 54));
    }

    @Test
    public void overnightWindowWrapsMidnight() {
        int start = 22 * 60;
        int end = 6 * 60;
        assertTrue(SocHistoryDatabase.timeTariffCovers(
                EVERY_DAY, start, end, FRIDAY_BIT, 23 * 60));
        assertTrue(SocHistoryDatabase.timeTariffCovers(
                EVERY_DAY, start, end, FRIDAY_BIT, 3 * 60 + 54));
        assertFalse(SocHistoryDatabase.timeTariffCovers(
                EVERY_DAY, start, end, FRIDAY_BIT, 12 * 60));
    }

    @Test
    public void dayMaskRejectsWrongWeekday() {
        int sundayBit = 1 << (Calendar.SUNDAY - 1);
        assertFalse(SocHistoryDatabase.timeTariffCovers(
                WEEKDAYS, 0, 359, sundayBit, 3 * 60));
        assertTrue(SocHistoryDatabase.timeTariffCovers(
                WEEKDAYS, 0, 359, FRIDAY_BIT, 3 * 60));
    }

    @Test
    public void parameterizedBitandCannotPrepareOnH2() throws Exception {
        Class.forName("org.h2.Driver");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tou-bitand-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE time_tariffs ("
                    + "id IDENTITY PRIMARY KEY, day_mask INTEGER NOT NULL,"
                    + "ac_rate REAL DEFAULT -1);");
            statement.execute("INSERT INTO time_tariffs (day_mask, ac_rate) VALUES (127, 0.04);");
            try {
                connection.prepareStatement(
                        "SELECT ac_rate FROM time_tariffs WHERE BITAND(day_mask, ?) <> 0");
                fail("H2 should reject BITAND with an untyped parameter");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("BITAND")
                                || expected.getMessage().contains("parameter")
                                || expected.getMessage().contains("data type"));
            }
            try (PreparedStatement ok = connection.prepareStatement(
                    "SELECT ac_rate FROM time_tariffs WHERE BITAND(day_mask, CAST(? AS INTEGER)) <> 0")) {
                ok.setInt(1, FRIDAY_BIT);
                assertTrue(ok.executeQuery().next());
            }
        }
    }

    @Test
    public void resolveUsesEvWindowInsteadOfGlobalAtFriday0354() throws Exception {
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(null);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:tou-resolve-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE time_tariffs (id IDENTITY PRIMARY KEY,"
                    + "label VARCHAR(64) DEFAULT '',day_mask INTEGER NOT NULL,"
                    + "start_minute INTEGER NOT NULL,end_minute INTEGER NOT NULL,"
                    + "ac_rate REAL DEFAULT -1,dc_rate REAL DEFAULT -1,"
                    + "priority INTEGER DEFAULT 0,enabled INTEGER DEFAULT 1);");
            statement.execute("CREATE TABLE time_tariff_config "
                    + "(id INTEGER PRIMARY KEY,enabled INTEGER DEFAULT 0);");
            statement.execute("INSERT INTO time_tariff_config (id, enabled) VALUES (1, 1);");
            statement.execute("INSERT INTO time_tariffs"
                    + " (label, day_mask, start_minute, end_minute, ac_rate, dc_rate, priority, enabled)"
                    + " VALUES ('Peak', 62, 960, 1199, 0.48895, 0, 0, 1),"
                    + " ('Shoulder', 127, 600, 839, 0.12958, 0, 0, 1),"
                    + " ('EV Charge Window', 127, 0, 359, 0.0449, 0, 0, 1);");

            set(database, "connection", connection);

            SocHistoryDatabase.TimeOfUseMatch match = database.resolveTimeOfUseMatchAt(
                    false, FRIDAY_BIT, 3 * 60 + 54);
            assertNotNull(match);
            assertEquals("EV Charge Window", match.label);
            assertEquals(0.0449, match.rate, 0.0001);

            assertNull(database.resolveTimeOfUseMatchAt(
                    false, FRIDAY_BIT, 20 * 60));
        }
    }

    @Test
    public void matchingQueryDoesNotBindBitandParameter() throws Exception {
        String source = readSource("app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
        assertFalse("H2 2.2 cannot prepare BITAND(day_mask, ?)",
                source.contains("BITAND(day_mask, ?)"));
        assertTrue(source.contains("timeTariffCovers("));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String readSource(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve("Overdrive-release-feature-fast_cam_capture")
                    .resolve(relativePath);
            if (Files.exists(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
