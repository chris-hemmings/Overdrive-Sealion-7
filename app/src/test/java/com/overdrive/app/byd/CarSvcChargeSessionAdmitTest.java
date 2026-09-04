package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarSvcChargeSessionAdmitTest {

    @Test
    public void gunConnectedAdmitsImmediatelyEvenAtZeroKw() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertTrue(admit.admit(true, true, true, 0f, 1_000L));
    }

    @Test
    public void strongKwWithoutGunAdmitsImmediately() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertTrue(admit.admit(true, true, false, 7.0f, 1_000L));
    }

    @Test
    public void weakKwWithoutGunWaitsForSustain() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertFalse(admit.admit(true, true, false, 1.4f, 1_000L));
        assertFalse(admit.admit(true, true, false, 1.4f, 30_000L));
        assertTrue(admit.admit(
                true, true, false, 1.4f, 1_000L + CarSvcChargeSessionAdmit.SUSTAIN_MS));
    }

    @Test
    public void zeroKwWithoutGunNeverAdmits() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertFalse(admit.admit(true, true, false, 0f, 1_000L));
        assertFalse(admit.admit(
                true, true, false, 0f, 1_000L + CarSvcChargeSessionAdmit.SUSTAIN_MS * 4));
        assertFalse(admit.admit(true, true, false, -1f, 200_000L));
    }

    @Test
    public void regenWhileDrivingDoesNotAdmitEvenAtHighKw() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertFalse(admit.admit(true, false, false, 40f, 1_000L));
        assertFalse(admit.admit(
                true, false, false, 40f, 1_000L + CarSvcChargeSessionAdmit.SUSTAIN_MS));
    }

    @Test
    public void gunDropHoldsWhileWeakInflowContinues() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertTrue(admit.admit(true, true, true, 1.4f, 1_000L));
        assertTrue(admit.admit(true, true, false, 1.4f, 2_000L));
    }

    @Test
    public void gunDropReleasesWhenInflowStops() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertTrue(admit.admit(true, true, true, 7.0f, 1_000L));
        assertFalse(admit.admit(true, true, false, 0f, 2_000L));
    }

    @Test
    public void weakKwClockResetsIfInflowDips() {
        CarSvcChargeSessionAdmit admit = new CarSvcChargeSessionAdmit();
        assertFalse(admit.admit(true, true, false, 1.4f, 1_000L));
        assertFalse(admit.admit(true, true, false, 0.2f, 20_000L));
        assertFalse(admit.admit(true, true, false, 1.4f, 40_000L));
        assertFalse(admit.admit(
                true, true, false, 1.4f, 40_000L + CarSvcChargeSessionAdmit.SUSTAIN_MS - 1));
        assertTrue(admit.admit(
                true, true, false, 1.4f, 40_000L + CarSvcChargeSessionAdmit.SUSTAIN_MS));
    }
}
