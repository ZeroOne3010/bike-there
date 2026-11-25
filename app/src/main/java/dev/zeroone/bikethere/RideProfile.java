package dev.zeroone.bikethere;

import java.util.Arrays;
import java.util.List;

final class RideProfile {
    enum Metric { ELEVATION, SPEED }

    private final List<Ride.Point> route;
    private final double[] elevation;
    private final double[] speed;

    RideProfile(List<Ride.Point> route) {
        this.route = route;
        elevation = new double[route.size()];
        speed = new double[route.size()];
        double[] window = new double[5];
        for (int i = 0; i < route.size(); i++) {
            elevation[i] = filteredElevation(i, window);
            speed[i] = speed(i) * 3.6;
        }
    }

    int size() {
        return route.size();
    }

    long start() {
        return time(0);
    }

    long end() {
        return time(route.size() - 1);
    }

    long time(int index) {
        return route.get(index).time();
    }

    boolean segmentStart(int index) {
        return route.get(index).segmentStart();
    }

    double value(Metric metric, int index) {
        return (metric == Metric.SPEED ? speed : elevation)[index];
    }

    double valueAt(Metric metric, long time) {
        int index = floorIndex(route, time);
        double value = value(metric, index);
        if (time <= time(index) || index == size() - 1) {
            return value;
        }
        double next = value(metric, index + 1);
        if (segmentStart(index + 1) || !Double.isFinite(value) || !Double.isFinite(next)) {
            return Double.NaN;
        }
        double progress = (double) (time - time(index)) / (time(index + 1) - time(index));
        return value + (next - value) * progress;
    }

    static int floorIndex(List<Ride.Point> route, long time) {
        int lo = 0;
        int hi = route.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (route.get(mid).time() <= time) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private double filteredElevation(int index, double[] window) {
        Ride.Point current = route.get(index);
        if (!Double.isFinite(current.elevationMetres())) {
            return Double.NaN;
        }
        int count = 0;
        window[count++] = current.elevationMetres();
        for (int j = index - 1; j >= Math.max(0, index - 2); j--) {
            if (route.get(j + 1).segmentStart() || !Double.isFinite(route.get(j).elevationMetres())) {
                break;
            }
            window[count++] = route.get(j).elevationMetres();
        }
        for (int j = index + 1; j <= Math.min(route.size() - 1, index + 2); j++) {
            if (route.get(j).segmentStart() || !Double.isFinite(route.get(j).elevationMetres())) {
                break;
            }
            window[count++] = route.get(j).elevationMetres();
        }
        Arrays.sort(window, 0, count);
        return (window[(count - 1) / 2] + window[count / 2]) / 2;
    }

    private double speed(int index) {
        Ride.Point current = route.get(index);
        if (Double.isFinite(current.speedMetresPerSecond()) && current.speedMetresPerSecond() >= 0) {
            return current.speedMetresPerSecond();
        }
        Ride.Point previous = index > 0 && !current.segmentStart() ? route.get(index - 1) : current;
        Ride.Point next = index + 1 < route.size() && !route.get(index + 1).segmentStart()
                ? route.get(index + 1) : current;
        long duration = next.time() - previous.time();
        if (duration <= 0) {
            return Double.NaN;
        }
        double distance = Ride.meters(previous.lat(), previous.lon(), current.lat(), current.lon())
                + Ride.meters(current.lat(), current.lon(), next.lat(), next.lon());
        return 1000 * distance / duration;
    }
}
