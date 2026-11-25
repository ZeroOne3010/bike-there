package dev.zeroone.bikethere;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

record Ride(String id, String title, String zone, List<Point> points) {
    record Point(double lat, double lon, long time, boolean segmentStart) {}

    long started() {
        return points.get(0).time();
    }

    long elapsedMillis() {
        return points.get(points.size() - 1).time() - started();
    }

    long movingMillis() {
        long total = 0;
        for (int i = 1; i < points.size(); i++) {
            if (!points.get(i).segmentStart()) {
                total += points.get(i).time() - points.get(i - 1).time();
            }
        }
        return total;
    }

    double distanceMeters() {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            if (!b.segmentStart()) {
                total += meters(a.lat(), a.lon(), b.lat(), b.lon());
            }
        }
        return total;
    }

    static double meters(double firstLat, double firstLon, double lastLat, double lastLon) {
        double dLat = Math.toRadians(lastLat - firstLat);
        double dLon = Math.toRadians(lastLon - firstLon);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(firstLat)) * Math.cos(Math.toRadians(lastLat))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(a, 1)));
    }

    static String title(String from, String to, long start, ZoneId zone) {
        int hour = Instant.ofEpochMilli(start).atZone(zone).getHour();
        String time = hour < 5 ? "Night" : hour < 12 ? "Morning" : hour < 18 ? "Afternoon" : "Evening";
        if (from == null && to == null) {
            return time + " ride";
        }
        return (from == null ? "Unknown start" : from) + " → "
                + (to == null ? "Unknown finish" : to) + " · " + time;
    }
}
