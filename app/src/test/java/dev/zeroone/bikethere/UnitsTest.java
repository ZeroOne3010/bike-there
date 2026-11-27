package dev.zeroone.bikethere;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class UnitsTest {
    @Test
    public void devicePreferenceUsesExplicitMeasurementSystem() {
        Locale us = Locale.forLanguageTag("en-US-u-ms-ussystem");
        Locale uk = Locale.forLanguageTag("en-GB-u-ms-uksystem");
        assertEquals(Units.METRIC, Units.resolve("system", Locale.US));
        assertEquals(Units.IMPERIAL, Units.resolve("system", us));
        assertEquals(Units.UK, Units.resolve("system", uk));
        assertEquals(Units.METRIC, Units.resolve("metric", us));
        assertEquals(Units.IMPERIAL, Units.resolve("imperial", Locale.UK));
    }

    @Test
    public void conversionsMatchTheirDisplayUnits() {
        assertEquals(1, Units.IMPERIAL.distance(1609.344), 0.000001);
        assertEquals(1, Units.IMPERIAL.speed(1609.344 / 3600), 0.000001);
        assertEquals(1, Units.IMPERIAL.height(0.3048), 0.000001);
        assertEquals(3.6, Units.METRIC.speed(1), 0.000001);
    }

    @Test
    public void coordinatesShowHemisphereAndCarryRoundedMinutesIntoDegrees() {
        assertEquals("N 60° 12.345′, E 25° 32.111′",
                Coordinates.label(60 + 12.345 / 60, 25 + 32.111 / 60));
        assertEquals("S 61° 00.000′, W 180° 00.000′",
                Coordinates.label(-60.999999, -179.999999));
    }
}
