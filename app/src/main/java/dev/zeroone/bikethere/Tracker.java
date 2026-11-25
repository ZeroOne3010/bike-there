package dev.zeroone.bikethere;

import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Tracker {
    enum State { DISARMED, READY, RIDING, PAUSED }

    record Place(long id, String name, double lat, double lon, int radius) {}
    record Fix(double lat, double lon, long time, long elapsed, float accuracy, float speed) {}
    private record Candidate(Fix fix, boolean moving) {}

    private static final long BUFFER_MS = 25_000;
    private static final long START_CONFIRM_MS = 4_000;
    private static final long PAUSE_MS = 12_000;
    private static final long ARRIVAL_MS = 30_000;
    private static final long MAX_GAP_MS = 15_000;
    private static final float MAX_ACCURACY = 35;
    private static final float START_SPEED = 2.5f;
    private static final float CONTINUE_SPEED = 1.6f;

    private final ArrayDeque<Candidate> buffer = new ArrayDeque<>();
    private final ArrayList<Ride.Point> points = new ArrayList<>();
    private State state = State.DISARMED;
    private Fix last;
    private long idleSince;
    private long firstElapsed;
    private long stoppedSince = -1;
    private long arrivalSince;
    private long startMoving = -1;
    private int movingFixes;
    private boolean manualStart;
    private Place arrival;
    private Fix arrivalFix;
    private int arrivalAt;
    private String origin;
    private String rideId;
    private ZoneId zone;

    State state() {
        return state;
    }

    void arm(long elapsed) {
        if (state == State.DISARMED) {
            state = State.READY;
            idleSince = elapsed;
        }
    }

    boolean idleExpired(long elapsed, long limit) {
        return state == State.READY && elapsed - idleSince >= limit;
    }

    void startManually() {
        if (state == State.READY) {
            manualStart = true;
        }
    }

    Ride disarm() {
        Ride result = completed(arrival);
        reset();
        state = State.DISARMED;
        return result;
    }

    Ride finish(long elapsed, List<Place> places) {
        Place destination = last == null ? null : nearest(last, places);
        Ride ride = completed(destination);
        reset();
        state = State.READY;
        idleSince = elapsed;
        return ride;
    }

    Ride snapshot() {
        return completed(null);
    }

    Ride accept(Fix fix, List<Place> places, ZoneId localZone) {
        if (state == State.DISARMED || !Float.isFinite(fix.accuracy()) || fix.accuracy() <= 0
                || fix.accuracy() > MAX_ACCURACY || (last != null && fix.elapsed() <= last.elapsed())) {
            return null;
        }
        boolean gap = last != null && fix.elapsed() - last.elapsed() > MAX_GAP_MS;
        boolean moving = speed(fix, gap ? null : last)
                >= (state == State.RIDING ? CONTINUE_SPEED : START_SPEED);
        last = fix;
        if (state == State.READY) {
            if (gap) {
                startMoving = -1;
                movingFixes = 0;
            }
            handleReady(fix, moving, places, localZone);
            return null;
        }
        if (gap) {
            clearStop();
            state = State.PAUSED;
        }
        if (moving) {
            append(fix, state == State.PAUSED);
            clearStop();
            state = State.RIDING;
            return null;
        }
        if (stoppedSince < 0) {
            stoppedSince = fix.elapsed();
        }
        Place place = nearest(fix, places);
        if (place == null) {
            arrival = null;
            arrivalFix = null;
        } else if (arrival == null || arrival.id() != place.id()) {
            arrival = place;
            arrivalSince = fix.elapsed();
            arrivalAt = points.size();
            arrivalFix = fix;
        }
        if (arrival != null && fix.elapsed() - arrivalSince >= ARRIVAL_MS) {
            if (arrivalAt == points.size()) {
                Ride.Point tail = points.get(points.size() - 1);
                double separation = Ride.meters(tail.lat(), tail.lon(), arrivalFix.lat(), arrivalFix.lon());
                if (arrivalFix.elapsed() - stoppedSince <= PAUSE_MS && separation <= 25) {
                    append(arrivalFix, false);
                }
            }
            return finish(fix.elapsed(), places);
        }
        if (fix.elapsed() - stoppedSince >= PAUSE_MS) {
            state = State.PAUSED;
        }
        return null;
    }

    static Place nearest(Fix fix, List<Place> places) {
        Place result = null;
        double closest = Double.POSITIVE_INFINITY;
        for (Place place : places) {
            double distance = Ride.meters(fix.lat(), fix.lon(), place.lat(), place.lon());
            if (distance <= place.radius() && distance < closest) {
                closest = distance;
                result = place;
            }
        }
        return result;
    }

    private void handleReady(Fix fix, boolean moving, List<Place> places, ZoneId localZone) {
        buffer.addLast(new Candidate(fix, moving));
        while (fix.elapsed() - buffer.getFirst().fix().elapsed() > BUFFER_MS) {
            buffer.removeFirst();
        }
        if (moving) {
            if (startMoving < 0) {
                startMoving = fix.elapsed();
            }
            movingFixes++;
        } else {
            startMoving = -1;
            movingFixes = 0;
        }
        if (!manualStart && (movingFixes < 3 || fix.elapsed() - startMoving < START_CONFIRM_MS)) {
            return;
        }
        rideId = UUID.randomUUID().toString();
        zone = localZone;
        Place bestOrigin = null;
        for (Candidate candidate : buffer) {
            if (candidate.fix().elapsed() > startMoving && !manualStart) {
                break;
            }
            Place found = nearest(candidate.fix(), places);
            if (found != null) {
                bestOrigin = found;
            }
        }
        origin = bestOrigin == null ? null : bestOrigin.name();
        if (manualStart) {
            append(fix, true);
        } else {
            for (Candidate candidate : buffer) {
                if (candidate.moving() && candidate.fix().elapsed() >= startMoving) {
                    append(candidate.fix(), points.isEmpty());
                }
            }
        }
        buffer.clear();
        state = State.RIDING;
    }

    private void append(Fix fix, boolean segmentStart) {
        // NMEA time and the phone clock can jump. Anchor track timestamps to the first fix.
        long time = points.isEmpty() ? fix.time()
                : points.get(0).time() + fix.elapsed() - firstElapsed;
        if (points.isEmpty()) {
            firstElapsed = fix.elapsed();
        }
        points.add(new Ride.Point(fix.lat(), fix.lon(), time, segmentStart));
    }

    private Ride completed(Place destination) {
        if (points.size() < 2) {
            return null;
        }
        Ride ride = new Ride(rideId,
                Ride.title(origin, destination == null ? null : destination.name(),
                        points.get(0).time(), zone), zone.getId(), new ArrayList<>(points));
        return ride.distanceMeters() >= (manualStart ? 5 : 15) ? ride : null;
    }

    private static float speed(Fix fix, Fix previous) {
        if (Float.isFinite(fix.speed()) && fix.speed() >= 0) {
            return fix.speed();
        }
        if (previous == null) {
            return 0;
        }
        double distance = Ride.meters(previous.lat(), previous.lon(), fix.lat(), fix.lon());
        return (float) (Math.max(0, distance - Math.max(previous.accuracy(), fix.accuracy()))
                * 1000 / (fix.elapsed() - previous.elapsed()));
    }

    private void clearStop() {
        stoppedSince = -1;
        arrival = null;
        arrivalFix = null;
    }

    private void reset() {
        clearStop();
        buffer.clear();
        points.clear();
        last = null;
        origin = null;
        rideId = null;
        startMoving = -1;
        movingFixes = 0;
        manualStart = false;
    }
}
