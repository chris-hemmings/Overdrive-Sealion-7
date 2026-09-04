package com.overdrive.app.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ManualClipWindowTest {

    @Test
    public void createsLastThirtySecondsWindow() {
        ManualClipWindow window = ManualClipWindow.create(30, 0);

        assertEquals(30, window.getBeforeSeconds());
        assertEquals(0, window.getAfterSeconds());
    }

    @Test
    public void createsFifteenSecondsBeforeAndAfterWindow() {
        ManualClipWindow window = ManualClipWindow.create(15, 15);

        assertEquals(15, window.getBeforeSeconds());
        assertEquals(15, window.getAfterSeconds());
    }

    @Test
    public void createsLastSixtySecondsWindow() {
        ManualClipWindow window = ManualClipWindow.create(60, 0);

        assertEquals(60, window.getBeforeSeconds());
        assertEquals(0, window.getAfterSeconds());
    }

    @Test
    public void createsThirtySecondsBeforeAndAfterWindow() {
        ManualClipWindow window = ManualClipWindow.create(30, 30);

        assertEquals(30, window.getBeforeSeconds());
        assertEquals(30, window.getAfterSeconds());
    }

    @Test
    public void rejectsNegativeBeforeSeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(-1, 1));
    }

    @Test
    public void rejectsNegativeAfterSeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(1, -1));
    }

    @Test
    public void rejectsBeforeSecondsAboveMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(ManualClipWindow.MAX_SECONDS + 1, 0));
    }

    @Test
    public void rejectsAfterSecondsAboveMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(0, ManualClipWindow.MAX_SECONDS + 1));
    }

    @Test
    public void rejectsEmptyWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(0, 0));
    }

    @Test
    public void rejectsTotalDurationAboveMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualClipWindow.create(31, 30));
    }
}
