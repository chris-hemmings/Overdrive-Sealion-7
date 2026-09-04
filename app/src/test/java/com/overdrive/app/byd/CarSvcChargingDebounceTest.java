package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarSvcChargingDebounceTest {

    @Test
    public void firstNotChargingDoesNotOpenASession() {
        CarSvcChargingDebounce debounce = new CarSvcChargingDebounce(60_000L);
        assertFalse(debounce.apply(false, 1_000L));
        assertFalse(debounce.apply(false, 2_000L));
    }

    @Test
    public void trueThenFalseHoldsForUnlockBlipThenDrops() {
        CarSvcChargingDebounce debounce = new CarSvcChargingDebounce(60_000L);
        assertTrue(debounce.apply(true, 1_000L));
        assertTrue(debounce.apply(false, 2_000L));
        assertTrue(debounce.apply(false, 30_000L));
        assertFalse(debounce.apply(false, 62_000L));
        assertFalse(debounce.apply(false, 63_000L));
    }

    @Test
    public void trueDuringHoldRearms() {
        CarSvcChargingDebounce debounce = new CarSvcChargingDebounce(60_000L);
        assertTrue(debounce.apply(true, 1_000L));
        assertTrue(debounce.apply(false, 2_000L));
        assertTrue(debounce.apply(true, 20_000L));
        assertTrue(debounce.apply(false, 21_000L));
        assertTrue(debounce.apply(false, 80_000L));
        assertFalse(debounce.apply(false, 82_000L));
    }
}
