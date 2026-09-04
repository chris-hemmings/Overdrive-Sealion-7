package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarSvcTelemetryParseTest {

    @Test
    public void parseInt32LockValue() {
        String line = "event count:3, lastEvent:Property:0x21404627,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [2],int64Values: [],bytes: [],string: ";
        assertEquals(2, CarSvcTelemetry.INSTANCE.parseValueLine(line).intValue());
    }

    @Test
    public void parseFloatWhenInt32Empty() {
        String line = "event count:2, lastEvent:Property:0x21604601,status: 2,"
                + "timestamp:1,zone:0x0,floatValues: [0.0],int32Values: [],int64Values: [],bytes: [],string: ";
        assertEquals(0.0f, CarSvcTelemetry.INSTANCE.parseValueLine(line).floatValue(), 0.001f);
    }

    @Test
    public void parseSocAndRange() {
        String dump =
                "event count:3, lastEvent:Property:0x21404622,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [67],int64Values: [],bytes: [],string: \n"
                + "event count:5, lastEvent:Property:0x21404401,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [323],int64Values: [],bytes: [],string: \n";
        assertEquals(67, CarSvcTelemetry.INSTANCE.findInText(dump, 0x21404622).intValue());
        assertEquals(323, CarSvcTelemetry.INSTANCE.findInText(dump, 0x21404401).intValue());
    }

    @Test
    public void missingLastEventIsNull() {
        String dump = "Property:0x21406006, Property name:SPEED_VALUE, access:0x1\n";
        assertNull(CarSvcTelemetry.INSTANCE.findInText(dump, 0x21406006));
    }

    @Test
    public void parseChargingGunStaterBinary() {
        String connected =
                "event count:1, lastEvent:Property:0x21403407,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [1],int64Values: [],bytes: [],string: \n";
        String unplugged =
                "event count:1, lastEvent:Property:0x21403407,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [0],int64Values: [],bytes: [],string: \n";
        assertEquals(1, CarSvcTelemetry.INSTANCE.findInText(connected, 0x21403407).intValue());
        assertEquals(0, CarSvcTelemetry.INSTANCE.findInText(unplugged, 0x21403407).intValue());
    }

    @Test
    public void searchKeyMatchesLiveLastEventPrefix() {
        assertEquals(
                "lastEvent:Property:0x21604601,",
                CarSvcTelemetry.INSTANCE.buildSearchKey(0x21604601));
    }

    @Test
    public void parseGearIgnoresUnrelatedInt32Values() {
        String dump =
                "event count:2, lastEvent:Property:0x21405069,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [2],int64Values: [],bytes: [],string: \n"
                + "event count:2, lastEvent:Property:0x21406f13,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [3],int64Values: [],bytes: [],string: \n"
                + "event count:2, lastEvent:Property:0x21406000,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [1],int64Values: [],bytes: [],string: \n"
                + "Property:0x21403a0a, Property name:GEAR_R, access:0x1\n";
        assertEquals(-1, CarSvcTelemetry.INSTANCE.parseGearFromText(dump));
    }

    @Test
    public void remainingBatteryPowerIsFirstSocSource() {
        CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
        String remainOnly =
                "event count:9, lastEvent:Property:0x21604420,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [62.8],int32Values: [],int64Values: [],bytes: [],string: \n";
        assertEquals(62.8, CarSvcTelemetry.INSTANCE.parseSocFromText(remainOnly), 0.01);

        String both =
                remainOnly
                + "event count:3, lastEvent:Property:0x21404622,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [67],int64Values: [],bytes: [],string: \n";
        assertEquals(62.8, CarSvcTelemetry.INSTANCE.parseSocFromText(both), 0.01);
    }

    @Test
    public void socValuerUsedWhenRemainingBatteryPowerBlank() {
        CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
        String dump =
                "Property:0x21604420, Property name:REMAINING_BATTERY_POWER_R, access:0x1\n"
                + "event count:3, lastEvent:Property:0x21404622,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [67],int64Values: [],bytes: [],string: \n";
        assertEquals(67.0, CarSvcTelemetry.INSTANCE.parseSocFromText(dump), 0.01);
    }

    @Test
    public void blankCarServiceSocIsNan() {
        CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
        String dump =
                "Property:0x21604420, Property name:REMAINING_BATTERY_POWER_R, access:0x1\n"
                + "Property:0x21404622, Property name:SOC_VALUER, access:0x1\n";
        assertTrue(Double.isNaN(CarSvcTelemetry.INSTANCE.parseSocFromText(dump)));
        assertTrue(Double.isNaN(CarSvcTelemetry.INSTANCE.parseSocFromText(null)));
    }

    @Test
    public void missingLastEventHoldsLastKnownSoc() {
        CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
        String live =
                "event count:3, lastEvent:Property:0x21404622,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [67],int64Values: [],bytes: [],string: \n";
        String missing = "Property:0x21404622, Property name:SOC_VALUER, access:0x1\n";
        String garbage =
                "event count:1, lastEvent:Property:0x21404622,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [150],int64Values: [],bytes: [],string: \n";
        assertEquals(67, CarSvcTelemetry.INSTANCE.stickyInt(live, 0x21404622, 0, 100));
        assertEquals(67, CarSvcTelemetry.INSTANCE.stickyInt(missing, 0x21404622, 0, 100));
        assertEquals(67, CarSvcTelemetry.INSTANCE.stickyInt(null, 0x21404622, 0, 100));
        assertEquals(67, CarSvcTelemetry.INSTANCE.stickyInt(garbage, 0x21404622, 0, 100));
        CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
        assertEquals(-1, CarSvcTelemetry.INSTANCE.stickyInt(missing, 0x21404622, 0, 100));
    }

    @Test
    public void lastKnownSurvivesReloadFromDisk() throws Exception {
        java.io.File store = java.io.File.createTempFile("carsvc_last_known", ".json");
        store.deleteOnExit();
        CarSvcTelemetry.lastKnownStoreOverride = store;
        try {
            CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
            assertEquals(456, CarSvcTelemetry.INSTANCE.holdInt(0x2160801d, 456, true));
            assertEquals(12.4f, CarSvcTelemetry.INSTANCE.holdFloat(0x2140461e, 12.4f, true), 0.01f);
            CarSvcTelemetry.INSTANCE.reloadLastKnownForTest();
            assertEquals(456, CarSvcTelemetry.INSTANCE.holdInt(0x2160801d, null, false));
            assertEquals(12.4f, CarSvcTelemetry.INSTANCE.holdFloat(0x2140461e, null, false), 0.01f);
        } finally {
            CarSvcTelemetry.lastKnownStoreOverride = null;
            CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
            store.delete();
        }
    }

    @Test
    public void statisticTotalMileageIsPreferredAndCached() throws Exception {
        java.io.File store = java.io.File.createTempFile("carsvc_last_known", ".json");
        store.deleteOnExit();
        CarSvcTelemetry.lastKnownStoreOverride = store;
        try {
            CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
            String named =
                    "Property:0x21401000, Property name:STATISTIC_TOTAL_MILEAGE, access:0x1\n"
                    + "event count:4, lastEvent:Property:0x21401000,status: 0,"
                    + "timestamp:1,zone:0x0,floatValues: [],int32Values: [18432],int64Values: [],bytes: [],string: \n"
                    + "event count:2, lastEvent:Property:0x21604409,status: 0,"
                    + "timestamp:1,zone:0x0,floatValues: [12.0],int32Values: [],int64Values: [],bytes: [],string: \n";
            assertEquals(0x21401000,
                    CarSvcTelemetry.INSTANCE.findPropertyIdByName(named, "STATISTIC_TOTAL_MILEAGE").intValue());
            assertEquals(18432, CarSvcTelemetry.INSTANCE.parseTotalMileageFromText(named));
            assertEquals(18432, CarSvcTelemetry.INSTANCE.holdInt(0x21401000, 18432, true));
            String json = new String(java.nio.file.Files.readAllBytes(store.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"odometerKm\":18432"));
            CarSvcTelemetry.INSTANCE.reloadLastKnownForTest();
            String parked =
                    "Property:0x21401000, Property name:STATISTIC_TOTAL_MILEAGE, access:0x1\n";
            assertEquals(-1, CarSvcTelemetry.INSTANCE.parseTotalMileageFromText(parked));
            assertEquals(18432, CarSvcTelemetry.INSTANCE.holdInt(0x21401000, null, false));
        } finally {
            CarSvcTelemetry.lastKnownStoreOverride = null;
            CarSvcTelemetry.INSTANCE.clearLastKnownForTest();
            store.delete();
        }
    }

    @Test
    public void parseGearReadsShiftModeAndGearR() {
        String shiftD =
                "event count:1, lastEvent:Property:0x21406407,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [4],int64Values: [],bytes: [],string: \n";
        String gearRPark =
                "event count:1, lastEvent:Property:0x21403a0a,status: 0,"
                + "timestamp:1,zone:0x0,floatValues: [],int32Values: [1],int64Values: [],bytes: [],string: \n";
        assertEquals(4, CarSvcTelemetry.INSTANCE.parseGearFromText(shiftD));
        assertEquals(1, CarSvcTelemetry.INSTANCE.parseGearFromText(gearRPark));
    }
}
