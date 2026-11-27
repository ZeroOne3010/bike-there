package dev.zeroone.bikethere;

import java.util.Locale;

final class Coordinates {
    private Coordinates() {}

    static String label(double latitude, double longitude) {
        return degreesMinutes(latitude, "N", "S") + ", " + degreesMinutes(longitude, "E", "W");
    }

    private static String degreesMinutes(double value, String positive, String negative) {
        long thousandths = Math.round(Math.abs(value) * 60_000);
        return String.format(Locale.ROOT, "%s %d° %02d.%03d′", value < 0 ? negative : positive,
                thousandths / 60_000, thousandths / 1000 % 60, thousandths % 1000);
    }
}
