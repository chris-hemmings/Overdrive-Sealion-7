package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TripDetectorMotionTest {

    private TripDetector detector;

    @Before
    public void setUp() {
        detector = new TripDetector();
    }

    @After
    public void tearDown() {
        if (detector != null) detector.shutdown();
    }

    @Test
    public void threeMovingTicksStartATripWhileGearIsPark() {
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        assertEquals(TripDetector.State.IDLE, detector.getState());
        detector.onMotionSample(1, 40.0, true, true, false);
        assertEquals(TripDetector.State.ACTIVE, detector.getState());
    }

    @Test
    public void brokenStartStreakDoesNotOpenATrip() {
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 0.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        assertEquals(TripDetector.State.IDLE, detector.getState());
    }

    @Test
    public void twoStoppedTicksParkWhenGearStaysPark() {
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 0.0, true, true, false);
        assertEquals(TripDetector.State.ACTIVE, detector.getState());
        detector.onMotionSample(1, 0.0, true, true, false);
        assertEquals(TripDetector.State.PARK_PENDING, detector.getState());
    }

    @Test
    public void startGradeMotionCancelsParkDebounce() {
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 40.0, true, true, false);
        detector.onMotionSample(1, 0.0, true, true, false);
        detector.onMotionSample(1, 0.0, true, true, false);
        assertEquals(TripDetector.State.PARK_PENDING, detector.getState());
        detector.onMotionSample(1, 8.0, true, true, false);
        assertEquals(TripDetector.State.ACTIVE, detector.getState());
    }

    @Test
    public void redLightInDriveDoesNotPark() {
        detector.onMotionSample(4, 40.0, true, true, false);
        detector.onMotionSample(4, 40.0, true, true, false);
        detector.onMotionSample(4, 40.0, true, true, false);
        detector.onMotionSample(4, 0.0, true, true, false);
        detector.onMotionSample(4, 0.0, true, true, false);
        detector.onMotionSample(4, 0.0, true, true, false);
        assertEquals(TripDetector.State.ACTIVE, detector.getState());
    }
}
