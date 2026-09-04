package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TripMotionGateTest {

    @Test
    public void fusePrefersTheFasterLiveChannel() {
        assertEquals(40.0, TripMotionGate.fuse(0, 40.0), 0.01);
        assertEquals(40.0, TripMotionGate.fuse(40.0, 0), 0.01);
        assertEquals(12.0, TripMotionGate.fuse(Double.NaN, 12.0), 0.01);
        assertEquals(8.0, TripMotionGate.fuse(8.0, Double.NaN), 0.01);
        assertTrue(Double.isNaN(TripMotionGate.fuse(Double.NaN, Double.NaN)));
    }

    @Test
    public void cachedOrStaleGpsIsNotUsable() {
        assertFalse(TripMotionGate.gpsSpeedUsable(true, true, 100));
        assertFalse(TripMotionGate.gpsSpeedUsable(true, false, 6_000));
        assertFalse(TripMotionGate.gpsSpeedUsable(false, false, 100));
        assertTrue(TripMotionGate.gpsSpeedUsable(true, false, 400));
    }

    @Test
    public void startNeedsAccOnAndRealMotion() {
        assertFalse(TripMotionGate.mayStartFromMotion(
                true, true, true, false, 0.0));
        assertFalse(TripMotionGate.mayStartFromMotion(
                true, true, true, false, 7.9));
        assertTrue(TripMotionGate.mayStartFromMotion(
                true, true, true, false, 8.0));
        assertFalse(TripMotionGate.mayStartFromMotion(
                true, false, true, false, 40.0));
        assertFalse(TripMotionGate.mayStartFromMotion(
                true, true, false, false, 40.0));
        assertFalse(TripMotionGate.mayStartFromMotion(
                true, true, true, true, 40.0));
        assertFalse(TripMotionGate.mayStartFromMotion(
                false, true, true, false, 40.0));
    }

    @Test
    public void stopFromMotionOnlyWhenGearIsParkOrUnknown() {
        assertTrue(TripMotionGate.mayStopFromMotion(1, 0.0));
        assertTrue(TripMotionGate.mayStopFromMotion(-1, Double.NaN));
        assertFalse(TripMotionGate.mayStopFromMotion(4, 0.0));
        assertFalse(TripMotionGate.mayStopFromMotion(1, 8.0));
    }

    @Test
    public void parkDebounceCancelsOnlyOnStartGradeMotion() {
        assertFalse(TripMotionGate.mayCancelParkDebounce(2.5));
        assertTrue(TripMotionGate.mayCancelParkDebounce(8.0));
    }
}
