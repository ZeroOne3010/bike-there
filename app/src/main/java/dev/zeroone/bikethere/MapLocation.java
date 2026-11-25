package dev.zeroone.bikethere;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import java.util.function.Consumer;

final class MapLocation {
    private static final long MAX_CACHE_AGE_MS = 6 * 60 * 60 * 1000L;

    static boolean permitted(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    static Location cached(Context context) {
        if (!permitted(context)) {
            return null;
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        long now = SystemClock.elapsedRealtime();
        Location latest = null;
        for (String provider : manager.getProviders(true)) {
            if (LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                continue;
            }
            try {
                Location fix = manager.getLastKnownLocation(provider);
                if (usable(fix) && now - fix.getElapsedRealtimeNanos() / 1_000_000 <= MAX_CACHE_AGE_MS
                        && (latest == null || fix.getElapsedRealtimeNanos() > latest.getElapsedRealtimeNanos())) {
                    latest = fix;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Coarse permission can limit which providers offer cached locations.
            }
        }
        return latest;
    }

    @SuppressLint("MissingPermission")
    static void request(Context context, CancellationSignal cancellation, Consumer<Location> callback) {
        if (!permitted(context)) {
            return;
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, "fused"}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    LocationManagerCompat.getCurrentLocation(manager, provider, cancellation,
                            ContextCompat.getMainExecutor(context), fix -> {
                                if (usable(fix)) {
                                    callback.accept(fix);
                                }
                            });
                    return;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // A provider may be unavailable or have been disabled while starting.
            }
        }
    }

    static double cityZoom(Location location) {
        return Math.max(3, Math.min(14, Math.log(58_000 / Math.max(1, location.getAccuracy()))
                / Math.log(2) + 7));
    }

    private static boolean usable(Location fix) {
        return fix != null && fix.hasAccuracy() && Float.isFinite(fix.getAccuracy()) && fix.getAccuracy() > 0;
    }
}
