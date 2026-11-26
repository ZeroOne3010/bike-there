package dev.zeroone.bikethere;

import org.junit.Test;

import static org.junit.Assert.*;

public class GpsStatusTest {
    @Test
    public void satellitesAloneDoNotMakeTrackingReady() {
        GpsStatus status = new GpsStatus(true, -1, 19, 8);
        assertEquals("Searching for GPS", status.label(Tracker.State.READY, 10_000, false));
        assertEquals("Searching for GPS", status.label(Tracker.State.READY, 10_000, true));
    }

    @Test
    public void oldFixDoesNotKeepTrackingReady() {
        GpsStatus status = new GpsStatus(true, 1_000, 19, 8);
        assertEquals("Ready to auto-start", status.label(Tracker.State.READY, 2_000, false));
        assertEquals("Starting ride", status.label(Tracker.State.READY, 2_000, true));
        assertEquals("GPS signal lost", status.label(Tracker.State.RIDING, 20_000, false));
    }
    @Test
    public void standbyCanLockWithoutChangingRecordingState() {
        var gps = new GpsStatus(true, 10_000, 13, 8);
        assertEquals("Tracking off", gps.label(Tracker.State.DISARMED, 12_000, false));
        assertEquals("GPS locked", gps.signalLabel(12_000));
        assertEquals("Searching for GPS", gps.signalLabel(16_000));
        assertEquals("GPS off", GpsStatus.OFF.signalLabel(12_000));
    }

    @Test
    public void manualHandoffRequiresAFreshAccurateSample() {
        long age = GpsStatus.LOCK_AGE_MS;
        assertTrue(GpsStatus.usableSample(10_000, 12_000, age, 8));
        assertFalse(GpsStatus.usableSample(10_000, 16_000, age, 8));
        assertFalse(GpsStatus.usableSample(13_000, 12_000, age, 8));
        assertFalse(GpsStatus.usableSample(10_000, 12_000, age, 50));
    }
}
