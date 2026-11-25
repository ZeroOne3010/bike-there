package dev.zeroone.bikethere;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.*;

public class TrackerTest {
    private static final long TIME = Instant.parse("2026-11-25T05:30:00Z").toEpochMilli();
    private static final ZoneId HELSINKI = ZoneId.of("Europe/Helsinki");
    private static final Tracker.Place HOME = new Tracker.Place(1, "Home", 60, 24, 55);
    private static final Tracker.Place DAYCARE = new Tracker.Place(2, "Daycare", 60.003, 24, 55);
    private static final Tracker.Place PRESCHOOL = new Tracker.Place(3, "Preschool", 60, 24.01, 55);
    private static final List<Tracker.Place> PLACES = List.of(HOME, DAYCARE, PRESCHOOL);
    private final Tracker tracker = new Tracker();

    @Test
    public void waitingDoesNotStartRideAndConfirmationBackdatesStart() {
        tracker.arm(0);
        for (int t = 0; t <= 120; t += 2) {
            fix(t, 60, 24, 0);
        }
        assertEquals(Tracker.State.READY, tracker.state());
        assertNull(tracker.snapshot());
        fix(122, 60.00003, 24, 3);
        fix(124, 60.00012, 24, 4);
        fix(126, 60.00023, 24, 4);
        assertEquals(Tracker.State.RIDING, tracker.state());
        Ride snapshot = tracker.snapshot();
        assertEquals(TIME + 122_000, snapshot.started());
        assertEquals(30, snapshot.points().get(0).elevationMetres(), 0.001);
        assertEquals(3, snapshot.points().get(0).speedMetresPerSecond(), 0.001);
    }

    @Test
    public void arrivalTrimsStationaryTailAndPassThroughKeepsNextLegTogether() {
        tracker.arm(0);
        fix(0, 60, 24, 0);
        fix(2, 60.00005, 24, 3);
        fix(4, 60.00015, 24, 5);
        fix(6, 60.00025, 24, 5);
        for (int t = 8; t <= 44; t += 2) {
            fix(t, 60.00025 + (t - 6) * 0.00007, 24, 5);
        }
        Ride first = stopForThirtySeconds(46, 60.003, 24);
        assertEquals("Home → Daycare · Morning", first.title());
        assertEquals(TIME + 46_000, first.started() + first.elapsedMillis());
        assertEquals(Tracker.State.READY, tracker.state());

        fix(78, 60.003, 24, 0);
        for (int t = 80; t <= 140; t += 2) {
            fix(t, 60.003 - (t - 78) * 0.003 / 62, 24, 5);
        }
        assertEquals(Tracker.State.RIDING, tracker.state());
        for (int t = 142; t <= 222; t += 2) {
            assertNull(fix(t, 60, 24 + (t - 140) * 0.01 / 82, 7));
        }
        Ride second = stopForThirtySeconds(224, 60, 24.01);
        assertEquals("Daycare → Preschool · Morning", second.title());
        assertEquals(Tracker.State.READY, tracker.state());

        fix(256, 60, 24.01, 0);
        for (int t = 258; t <= 338; t += 2) {
            fix(t, 60, 24.01 - (t - 256) * 0.01 / 82, 7);
        }
        Ride third = stopForThirtySeconds(340, 60, 24);
        assertEquals("Preschool → Home · Morning", third.title());
        assertEquals(Tracker.State.READY, tracker.state());
    }

    @Test
    public void stopOutsideSavedPlacePausesAndMovementResumesSameRide() {
        tracker.arm(0);
        fix(0, 60.01, 24, 3);
        fix(2, 60.0101, 24, 4);
        fix(4, 60.0102, 24, 4);
        assertNull(stopForThirtySeconds(6, 60.0102, 24));
        assertEquals(Tracker.State.PAUSED, tracker.state());
        fix(38, 60.0105, 24, 5);
        fix(40, 60.0106, 24, 5);
        Ride ride = tracker.disarm();
        assertEquals(6_000, ride.movingMillis());
        assertEquals(40_000, ride.elapsedMillis());
        assertEquals(Tracker.State.DISARMED, tracker.state());
    }

    @Test
    public void readyTimeoutIsResetAfterFinishing() {
        tracker.arm(0);
        assertTrue(tracker.idleExpired(60_000, 60_000));
        tracker.finish(60_000, PLACES);
        assertFalse(tracker.idleExpired(65_000, 60_000));
        assertTrue(tracker.idleExpired(120_000, 60_000));
    }

    @Test
    public void liveStatsUseMovingTimeAndResetOnFinish() {
        tracker.arm(0);
        tracker.startManually();
        assertTrue(tracker.manualPending());
        fix(2, 61, 24, 0);
        assertFalse(tracker.manualPending());
        fix(4, 61.0001, 24, 5);
        fix(6, 61.0002, 24, 7);
        stopForThirtySeconds(8, 61.0002, 24);
        Tracker.LiveStats stats = tracker.liveStats(38_000);
        assertEquals(36_000, stats.elapsedMillis());
        assertEquals(4_000, stats.movingMillis());
        assertEquals(Ride.meters(61, 24, 61.0002, 24) / 4, stats.averageSpeedMps(), .001);
        assertEquals(7, stats.topSpeedMps(), .001);
        assertEquals(3, tracker.liveRoute().size());
        assertNotNull(tracker.finish(38_000, PLACES));
        assertEquals(Tracker.LiveStats.EMPTY, tracker.liveStats(39_000));
        assertTrue(tracker.liveRoute().isEmpty());
    }

    private Ride stopForThirtySeconds(int start, double lat, double lon) {
        for (int t = start; t < start + 30; t += 2) {
            assertNull(fix(t, lat, lon, 0));
        }
        return fix(start + 30, lat, lon, 0);
    }

    private Ride fix(int seconds, double lat, double lon, float speed) {
        return tracker.accept(new Tracker.Fix(lat, lon, TIME + seconds * 1000L,
                seconds * 1000L, 4, speed, 30), PLACES, HELSINKI);
    }
}
