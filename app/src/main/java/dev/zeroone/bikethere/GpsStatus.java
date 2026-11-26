package dev.zeroone.bikethere;

record GpsStatus(boolean enabled, long lastFix, int seen, int used) {
    static final long LOCK_AGE_MS = 5_000;
    static final GpsStatus OFF = new GpsStatus(false, -1, -1, -1);

    boolean hasFix(long now) {
        return enabled && lastFix > 0 && lastFix <= now && now - lastFix <= LOCK_AGE_MS;
    }

    static boolean usableSample(long sample, long now, long maxAge, float accuracy) {
        return sample > 0 && sample <= now && now - sample <= maxAge
                && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= Tracker.MAX_ACCURACY;
    }

    String signalLabel(long now) {
        if (!enabled) return "GPS off";
        return hasFix(now) ? "GPS locked" : "Searching for GPS";
    }

    String label(Tracker.State state, long now, boolean manualPending) {
        if (state == Tracker.State.DISARMED) {
            return "Tracking off";
        }
        if (!enabled) {
            return "Location off";
        }
        if (!hasFix(now)) {
            return state == Tracker.State.READY ? "Searching for GPS" : "GPS signal lost";
        }
        return switch (state) {
            case READY -> manualPending ? "Starting ride" : "Ready to auto-start";
            case RIDING -> "Recording ride";
            case PAUSED -> "Auto-paused";
            case DISARMED -> throw new IllegalStateException();
        };
    }
}
