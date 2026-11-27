package dev.zeroone.bikethere;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RideProfileTest {
    @Test
    public void legacyRidesEstimateSpeedWithoutInventingElevationOrCrossingPauses() {
        RideProfile profile = new RideProfile(List.of(
                new Ride.Point(60, 24, 1000, true, Double.NaN, Double.NaN),
                new Ride.Point(60.0001, 24, 3000, false, Double.NaN, Double.NaN),
                new Ride.Point(60.005, 24, 16_000, true, Double.NaN, Double.NaN),
                new Ride.Point(60.0051, 24, 18_000, false, Double.NaN, Double.NaN)));
        assertEquals(5.56, profile.valueAt(RideProfile.Metric.SPEED, 2000), 0.01);
        assertTrue(Double.isNaN(profile.valueAt(RideProfile.Metric.SPEED, 8000)));
        assertEquals(5.56, profile.valueAt(RideProfile.Metric.SPEED, 17_000), 0.01);
        assertTrue(Double.isNaN(profile.valueAt(RideProfile.Metric.ELEVATION, 17_000)));
    }

    @Test
    public void prefersRecordedSpeedAndFiltersAnElevationSpike() {
        RideProfile profile = new RideProfile(List.of(
                new Ride.Point(60, 24, 1000, true, 12, 3),
                new Ride.Point(60.0001, 24, 3000, false, 90, 5),
                new Ride.Point(60.0002, 24, 5000, false, 14, 7)));
        assertEquals(4, profile.valueAt(RideProfile.Metric.SPEED, 2000), 0.001);
        assertEquals(14, profile.valueAt(RideProfile.Metric.ELEVATION, 3000), 0.001);
    }
}
