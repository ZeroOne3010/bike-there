package dev.zeroone.bikethere;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class TrackerService extends Service implements LocationListener {
    static final String UPDATE = "dev.zeroone.bikethere.STATUS";
    static final String LIVE_UPDATE = "dev.zeroone.bikethere.LIVE_STATUS";
    static final String ARM = "arm";
    static final String START_FIX = "startFix";
    static final String START = "start";
    static final String FINISH = "finish";
    static final String DISARM = "disarm";
    static final String PLACES = "places";
    static final String SET_TIMEOUT = "timeout";
    static final int DEFAULT_TIMEOUT = 20;
    private static final int NOTIFICATION_ID = 12;
    private static final String CHANNEL = "ride-tracking";

    record Live(Tracker.State state, boolean manualPending, GpsStatus gps, Location fix,
                Tracker.LiveStats stats, List<Ride.Point> route) {
        private static final Live OFF = new Live(Tracker.State.DISARMED, false, GpsStatus.OFF, null,
                Tracker.LiveStats.EMPTY, java.util.Collections.emptyList());
    }

    private static TrackerService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Tracker tracker = new Tracker();
    private RideStore store;
    private LocationManager location;
    private List<Tracker.Place> places;
    private boolean listening;
    private int timeout;
    private long nextCheckpoint;
    private boolean gpsEnabled;
    private boolean gnssRegistered;
    private long lastFixAt = -1;
    private Location lastLocation;
    private int satellitesSeen = -1;
    private int satellitesUsed = -1;
    private String notificationLabel;

    static Tracker.State state() {
        return active == null ? Tracker.State.DISARMED : active.tracker.state();
    }

    static Location fix() {
        return active == null || active.lastLocation == null ? null : new Location(active.lastLocation);
    }

    static Live live(long now) {
        if (active == null) {
            return Live.OFF;
        }
        Tracker tracker = active.tracker;
        return new Live(tracker.state(), tracker.manualPending(), active.gpsStatus(),
                active.lastLocation, tracker.liveStats(now), tracker.liveRoute());
    }

    private GpsStatus gpsStatus() {
        return new GpsStatus(gpsEnabled, lastFixAt, satellitesSeen, satellitesUsed);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new RideStore(this);
        store.recoverInterruptedRides();
        places = store.places();
        location = getSystemService(LocationManager.class);
        timeout = preferences(this).getInt(SET_TIMEOUT, DEFAULT_TIMEOUT);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Ride tracking",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when Bike There is ready, riding or paused");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static android.content.SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("bike-there", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (DISARM.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ARM.equals(action) || START.equals(action)) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return START_NOT_STICKY;
            }
            tracker.arm(SystemClock.elapsedRealtime());
            if (START.equals(action)) {
                tracker.startManually();
            }
            if (listening) {
                updateSignal();
            } else {
                beginListening();
            }
            if (START.equals(action) && listening && gpsEnabled && tracker.manualPending()) {
                Location warm = lastLocation != null ? lastLocation
                        : androidx.core.content.IntentCompat.getParcelableExtra(intent, START_FIX, Location.class);
                long now = SystemClock.elapsedRealtime();
                if (GpsStandby.usable(warm, now, GpsStatus.LOCK_AGE_MS)) {
                    Location start = new Location(warm);
                    start.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    start.setTime(System.currentTimeMillis());
                    start.removeSpeed();
                    onLocationChanged(start);
                }
            }
        } else if (FINISH.equals(action) && listening) {
            save(tracker.finish(SystemClock.elapsedRealtime(), places));
            publish();
        } else if (PLACES.equals(action) && listening) {
            places = store.places();
        } else if (SET_TIMEOUT.equals(action) && listening) {
            timeout = preferences(this).getInt(SET_TIMEOUT, DEFAULT_TIMEOUT);
        }
        if (!listening) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void beginListening() {
        active = this;
        gpsEnabled = location.isProviderEnabled(LocationManager.GPS_PROVIDER);
        startForeground(NOTIFICATION_ID, notification());
        try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, this, Looper.getMainLooper());
            listening = true;
        } catch (IllegalArgumentException | SecurityException e) {
            shutdown();
            return;
        }
        try {
            gnssRegistered = location.registerGnssStatusCallback(satellites, handler);
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Some devices do not report satellite status. GPS recording can still work.
        }
        handler.post(idleCheck);
        publish();
    }

    private final GnssStatus.Callback satellites = new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            if (!listening || !gpsEnabled) {
                return;
            }
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) {
                    used++;
                }
            }
            if (satellitesSeen != status.getSatelliteCount() || satellitesUsed != used) {
                satellitesSeen = status.getSatelliteCount();
                satellitesUsed = used;
                sendLiveUpdate();
            }
        }
    };

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (tracker.idleExpired(SystemClock.elapsedRealtime(), TimeUnit.MINUTES.toMillis(timeout))) {
                shutdown();
            } else {
                updateSignal();
                handler.postDelayed(this, 2_000L);
            }
        }
    };

    @Override
    public void onLocationChanged(Location fix) {
        if (!listening) {
            return;
        }
        long elapsed = GpsStandby.elapsed(fix);
        if (!GpsStandby.usable(fix, SystemClock.elapsedRealtime(), 10_000) || elapsed <= lastFixAt) {
            return;
        }
        Tracker.State before = tracker.state();
        lastLocation = new Location(fix);
        lastFixAt = elapsed;
        float speed = fix.hasSpeed() && (!fix.hasSpeedAccuracy() || fix.getSpeedAccuracyMetersPerSecond() <= 1.5f)
                ? fix.getSpeed() : Float.NaN;
        double elevation = fix.hasAltitude()
                && (!fix.hasVerticalAccuracy() || fix.getVerticalAccuracyMeters() <= 25)
                ? fix.getAltitude() : Double.NaN;
        Ride completed = tracker.accept(new Tracker.Fix(fix.getLatitude(), fix.getLongitude(),
                fix.getTime(), elapsed, fix.getAccuracy(), speed, elevation), places, ZoneId.systemDefault());
        save(completed);
        if (tracker.state() == Tracker.State.RIDING && elapsed >= nextCheckpoint) {
            Ride snapshot = tracker.snapshot();
            nextCheckpoint = elapsed + 6_000;
            if (snapshot != null) {
                store.saveRide(snapshot, true);
            }
        }
        if (before != tracker.state() || completed != null) {
            publish();
        } else {
            updateSignal();
            sendLiveUpdate();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        gpsEnabled = false;
        lastFixAt = -1;
        satellitesSeen = satellitesUsed = -1;
        updateSignal();
    }

    @Override
    public void onProviderEnabled(String provider) {
        gpsEnabled = true;
        updateSignal();
    }

    private void save(Ride ride) {
        if (ride != null) {
            store.saveRide(ride, false);
            if (preferences(this).getBoolean("weather", false)) {
                Context app = getApplicationContext();
                Weather.load(app, ride.id(), ride.points().get(0),
                        () -> app.sendBroadcast(new Intent(UPDATE).setPackage(app.getPackageName())));
            }
        }
    }

    private void shutdown() {
        save(tracker.disarm());
        stopUpdates();
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendUpdate();
        stopSelf();
    }

    private void stopUpdates() {
        handler.removeCallbacks(idleCheck);
        if (listening) {
            location.removeUpdates(this);
            listening = false;
        }
        if (gnssRegistered) {
            location.unregisterGnssStatusCallback(satellites);
            gnssRegistered = false;
        }
        if (active == this) {
            active = null;
        }
    }

    private void publish() {
        updateSignal();
        sendUpdate();
    }

    private void updateSignal() {
        String label = gpsStatus().label(tracker.state(), SystemClock.elapsedRealtime(), tracker.manualPending());
        if (listening && !label.equals(notificationLabel)) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
            sendLiveUpdate();
        }
    }

    private void sendLiveUpdate() {
        sendBroadcast(new Intent(LIVE_UPDATE).setPackage(getPackageName()));
    }

    private void sendUpdate() {
        sendBroadcast(new Intent(UPDATE).setPackage(getPackageName()));
    }

    private Notification notification() {
        Intent screen = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("notification", true);
        PendingIntent open = PendingIntent.getActivity(this, 1, screen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent disarm = PendingIntent.getService(this, 2,
                new Intent(this, TrackerService.class).setAction(DISARM), PendingIntent.FLAG_IMMUTABLE);
        Tracker.State state = tracker.state();
        boolean riding = state == Tracker.State.RIDING || state == Tracker.State.PAUSED;
        notificationLabel = gpsStatus().label(state, SystemClock.elapsedRealtime(), tracker.manualPending());
        String description = !gpsEnabled ? "Turn on the phone’s location"
                : riding ? "Tracking your ride"
                : tracker.manualPending() ? "Starts at the first usable GPS fix" : "Starts when you cycle";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bike There · " + notificationLabel)
                .setContentText(description)
                .setSmallIcon(R.drawable.ic_tracking)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, riding ? "Finish & disarm" : "Disarm", disarm).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        if (listening) {
            save(tracker.disarm());
        }
        stopUpdates();
        store.close();
        sendUpdate();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
