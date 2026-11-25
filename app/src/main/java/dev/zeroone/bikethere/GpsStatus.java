package dev.zeroone.bikethere;

record GpsStatus(boolean enabled, long lastFix, int seen, int used) {
    static final GpsStatus OFF = new GpsStatus(false, -1, -1, -1);

    boolean hasFix(long now) {
        return enabled && lastFix >= 0 && now - lastFix <= Tracker.MAX_GAP_MS;
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
