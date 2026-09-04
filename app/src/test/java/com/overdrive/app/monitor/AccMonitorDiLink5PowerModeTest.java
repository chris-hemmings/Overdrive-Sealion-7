package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AccMonitorDiLink5PowerModeTest {

    @Test
    public void preStartUpIsParkedEvenWhenHeadUnitIsAwake() {
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current: 1=PowerMode Pre StartUp"));
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current 1=PowerMode Pre StartUp"));
        String liveKeepAliveBlob =
                "\tIndex 15 [Power Mute State] type is Enum\n"
                + "\t\tIt could be callbackable\n"
                + "\t\tAll items {0=PowerMode Off, 1=PowerMode Pre StartUp, "
                + "2=PowerMode StartUp, 3=PowerMode Degraded, "
                + "4=PowerMode Standby, 5=PowerMode Str, "
                + "6=PowerMode Reflash, 7=PowerMode Remote Fota, "
                + "8=PowerMode Sleep, 9=PowerMode Str Suspending, "
                + "10=PowerMode DisPlay on, 11=PowerMode Half Hour Mode, "
                + "12=PowerMode Tod}\n"
                + "\t\tcurrent 1=PowerMode Pre StartUp\n";
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(liveKeepAliveBlob));
        assertEquals(0, DiLink5PowerMode.classifyBodyworkPowerLevel(liveKeepAliveBlob));
    }

    @Test
    public void startUpAndDisplayOnAreInUse() {
        assertEquals(Boolean.TRUE, DiLink5PowerMode.classifyCurrentLine(
                "current: 2=PowerMode StartUp"));
        assertEquals(Boolean.TRUE, DiLink5PowerMode.classifyCurrentLine(
                "current: 10=PowerMode DisPlay on"));
        assertEquals(Boolean.TRUE, DiLink5PowerMode.classifyCurrentLine(
                "current: 3=PowerMode Degraded"));
    }

    @Test
    public void standbyIsParked() {
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current: 4=PowerMode Standby"));
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current 4=PowerMode Standby"));
    }

    @Test
    public void dumpsysStandbyIsNotDisplayOnFromEnumList() {
        String blob =
                "Index 15 [Power Mute State] type is Enum\n"
                + "\tAll items {0=PowerMode Off, 1=PowerMode Pre StartUp, "
                + "10=PowerMode DisPlay on, 4=PowerMode Standby}\n"
                + "\tcurrent 4=PowerMode Standby\n";
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(blob));
        assertEquals(0, DiLink5PowerMode.classifyBodyworkPowerLevel(blob));
    }

    @Test
    public void sleepAndOffAreParked() {
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current: 8=PowerMode Sleep"));
        assertEquals(Boolean.FALSE, DiLink5PowerMode.classifyCurrentLine(
                "current: 0=PowerMode Off"));
    }

    @Test
    public void dumpsysEnumListMustNotOverrideCurrentDisplayOn() {
        String blob =
                "Index 15 [Power Mute State] type is Enum\n"
                + "\tIt could be callbackable\n"
                + "\tAll items {0=PowerMode Off, 1=PowerMode Pre StartUp, "
                + "2=PowerMode StartUp, 4=PowerMode Standby, "
                + "8=PowerMode Sleep, 10=PowerMode DisPlay on}\n"
                + "\tcurrent 10=PowerMode DisPlay on\n"
                + "Index 16 [Cp Alt Active] type is Bool\n"
                + "\tcurrent false\n";
        assertEquals(Boolean.TRUE, DiLink5PowerMode.classifyCurrentLine(blob));
        assertEquals(2, DiLink5PowerMode.classifyBodyworkPowerLevel(blob));
        assertEquals(Integer.valueOf(10), DiLink5PowerMode.extractCurrentMode(blob));
    }

    @Test
    public void unknownOrEmptyIsNull() {
        assertNull(DiLink5PowerMode.classifyCurrentLine(""));
        assertNull(DiLink5PowerMode.classifyCurrentLine(null));
        assertNull(DiLink5PowerMode.classifyCurrentLine("current: unknown"));
    }

    @Test
    public void bodyworkPowerLevelMapsParkedAndReady() {
        assertEquals(0, DiLink5PowerMode.classifyBodyworkPowerLevel(
                "current: 1=PowerMode Pre StartUp"));
        assertEquals(2, DiLink5PowerMode.classifyBodyworkPowerLevel(
                "current: 2=PowerMode StartUp"));
        assertEquals(2, DiLink5PowerMode.classifyBodyworkPowerLevel(
                "current: 10=PowerMode DisPlay on"));
        assertEquals(0, DiLink5PowerMode.classifyBodyworkPowerLevel(
                "current: 4=PowerMode Standby"));
        assertEquals(0, DiLink5PowerMode.classifyBodyworkPowerLevel(
                "current: 8=PowerMode Sleep"));
        assertEquals(-1, DiLink5PowerMode.classifyBodyworkPowerLevel(""));
    }
}
