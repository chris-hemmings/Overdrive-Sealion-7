package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class SentryScreenWalkLogTest {

    @Before
    public void reset() {
        SentryScreenWalkLog.resetForTest();
    }

    @Test
    public void screenShouldWakeOnApproachOrLoiterNotRecording() {
        assertFalse(SentryScreenWalkLog.shouldScreenOn(MotionPipelineV2.THREAT_NONE));
        assertFalse(SentryScreenWalkLog.shouldScreenOn(MotionPipelineV2.THREAT_LOW));
        assertTrue(SentryScreenWalkLog.shouldScreenOn(MotionPipelineV2.THREAT_MEDIUM));
        assertTrue(SentryScreenWalkLog.shouldScreenOn(MotionPipelineV2.THREAT_HIGH));
    }

    @Test
    public void oneWakePerApproachUntilThreatClears() {
        long t0 = 1_000_000L;
        assertTrue(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_MEDIUM, t0));
        assertFalse(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_MEDIUM, t0 + 500));
        assertFalse(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_HIGH, t0 + 1_000));
        assertFalse(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_NONE, t0 + 1_100));
        assertFalse(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_NONE, t0 + 3_100));
        assertTrue(SentryScreenWalkLog.consumeNewApproach(
                MotionPipelineV2.THREAT_MEDIUM, t0 + 3_101));
    }
}
