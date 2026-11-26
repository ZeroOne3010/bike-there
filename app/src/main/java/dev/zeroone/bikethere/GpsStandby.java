package dev.zeroone.bikethere;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

final class GpsStandby implements LocationListener {
    private final LocationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private boolean listening;
    private boolean gnssRegistered;
    private boolean enabled;
    private Location fix;
    private int seen = -1;
    private int used = -1;

    GpsStandby(Context context, Runnable changed) {
        manager = context.getSystemService(LocationManager.class);
        this.changed = changed;
    }

    @SuppressLint("MissingPermission") // The activity starts standby only with precise location permission.
    void start() {
        if (listening) return;
        enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, handler.getLooper());
            listening = true;
        } catch (IllegalArgumentException | SecurityException ignored) {
            enabled = false;
            return;
        }
        try {
            gnssRegistered = manager.registerGnssStatusCallback(satellites, handler);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Fixes still arrive on devices that do not provide satellite reports.
        }
    }

    void stop() {
        if (listening) {
            listening = false;
            manager.removeUpdates(this);
        }
        if (gnssRegistered) {
            manager.unregisterGnssStatusCallback(satellites);
            gnssRegistered = false;
        }
        enabled = false;
        fix = null;
        seen = used = -1;
    }

    Location fix() {
        return fix;
    }

    GpsStatus status() {
        return new GpsStatus(enabled, fix == null ? -1 : elapsed(fix), seen, used);
    }

    static long elapsed(Location fix) {
        return fix.getElapsedRealtimeNanos() / 1_000_000;
    }

    static boolean usable(Location fix, long now, long maxAge) {
        if (fix == null || !LocationManager.GPS_PROVIDER.equals(fix.getProvider()) || !fix.hasAccuracy()) {
            return false;
        }
        return GpsStatus.usableSample(elapsed(fix), now, maxAge, fix.getAccuracy());
    }

    @Override public void onLocationChanged(Location location) {
        if (listening && enabled && usable(location, android.os.SystemClock.elapsedRealtime(), 10_000)
                && (fix == null || elapsed(location) > elapsed(fix))) {
            fix = new Location(location);
            changed.run();
        }
    }

    @Override public void onProviderEnabled(String provider) {
        if (listening) {
            enabled = true;
            changed.run();
        }
    }

    @Override public void onProviderDisabled(String provider) {
        enabled = false;
        fix = null;
        seen = used = -1;
        if (listening) changed.run();
    }

    private final GnssStatus.Callback satellites = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            if (!listening || !enabled) return;
            seen = status.getSatelliteCount();
            used = 0;
            for (int i = 0; i < seen; i++) {
                if (status.usedInFix(i)) used++;
            }
            changed.run();
        }
    };
}
