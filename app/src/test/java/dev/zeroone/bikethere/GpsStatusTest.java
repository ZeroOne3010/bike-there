package dev.zeroone.bikethere;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
