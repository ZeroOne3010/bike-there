package dev.zeroone.bikethere;

import java.util.Locale;

enum Units {
    METRIC("Metric", "km", "km/h", "m", "Kilometres"),
    IMPERIAL("Imperial", "mi", "mph", "ft", "Miles"),
    UK("UK", "mi", "mph", "ft", "Miles");

    static final String PREFERENCE = "units";
    private static final double METRES_PER_MILE = 1609.344;
    private static final double METRES_PER_FOOT = 0.3048;

    final String label;
    final String distanceUnit;
    final String speedUnit;
    final String heightUnit;
    final String distanceName;

    Units(String label, String distanceUnit, String speedUnit, String heightUnit, String distanceName) {
        this.label = label;
        this.distanceUnit = distanceUnit;
        this.speedUnit = speedUnit;
        this.heightUnit = heightUnit;
        this.distanceName = distanceName;
    }

    static Units resolve(String preference, Locale locale) {
        return switch (preference) {
            case "metric" -> METRIC;
            case "imperial" -> IMPERIAL;
            default -> {
                String system = locale.getUnicodeLocaleType("ms");
                if ("ussystem".equals(system)) yield IMPERIAL;
                if ("uksystem".equals(system)) yield UK;
                yield METRIC;
            }
        };
    }

    double distance(double metres) {
        return metres / (this == METRIC ? 1000 : METRES_PER_MILE);
    }

    double speed(double metresPerSecond) {
        return distance(metresPerSecond * 3600);
    }

    double height(double metres) {
        return this == METRIC ? metres : metres / METRES_PER_FOOT;
    }

    String distanceLabel(double metres) {
        return String.format(Locale.getDefault(), "%.1f %s", distance(metres), distanceUnit);
    }

    String heightLabel(double metres) {
        return String.format(Locale.getDefault(), "%.0f %s", height(metres), heightUnit);
    }

    String weather(double celsius, double windMetresPerSecond, String bearing) {
        double temperature = this == IMPERIAL ? celsius * 1.8 + 32 : celsius;
        double wind = this == METRIC ? windMetresPerSecond : speed(windMetresPerSecond);
        return String.format(Locale.getDefault(), "%.0f °%s  ·  %s wind %.1f %s",
                temperature, this == IMPERIAL ? "F" : "C", bearing, wind, this == METRIC ? "m/s" : speedUnit);
    }
}
