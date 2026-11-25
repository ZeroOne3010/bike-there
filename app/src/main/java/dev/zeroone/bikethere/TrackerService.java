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
    static final String ARM = "arm";
    static final String START = "start";
    static final String FINISH = "finish";
    static final String DISARM = "disarm";
    static final String PLACES = "places";
    static final String SET_TIMEOUT = "timeout";
    static final int DEFAULT_TIMEOUT = 20;
    private static final int NOTIFICATION_ID = 12;
    private static final String CHANNEL = "ride-tracking";

    private static Tracker.State visibleState = Tracker.State.DISARMED;
    private static Location lastLocation;
    private static String statusDetail;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Tracker tracker = new Tracker();
    private RideStore store;
    private LocationManager location;
    private List<Tracker.Place> places;
    private boolean listening;
    private int timeout;
    private long nextCheckpoint;

    static Tracker.State state() {
        return visibleState;
    }

    static Location fix() {
        return lastLocation == null ? null : new Location(lastLocation);
    }

    static String detail() {
        return statusDetail;
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
            beginListening();
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
        if (listening) {
            return;
        }
        visibleState = tracker.state();
        startForeground(NOTIFICATION_ID, notification());
        try {
            statusDetail = location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? "Waiting for GPS" : "Turn on the phone’s location to record";
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, this, Looper.getMainLooper());
            listening = true;
            handler.post(idleCheck);
            publish();
        } catch (IllegalArgumentException | SecurityException e) {
            shutdown();
        }
    }

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (tracker.idleExpired(SystemClock.elapsedRealtime(), TimeUnit.MINUTES.toMillis(timeout))) {
                shutdown();
            } else {
                handler.postDelayed(this, 5_000L);
            }
        }
    };

    @Override
    public void onLocationChanged(Location fix) {
        if (!listening) {
            return;
        }
        long elapsed = fix.getElapsedRealtimeNanos() / 1_000_000;
        if (SystemClock.elapsedRealtime() - elapsed > 10_000 || !fix.hasAccuracy()
                || fix.getAccuracy() > 35) {
            return;
        }
        boolean firstFix = lastLocation == null;
        lastLocation = new Location(fix);
        statusDetail = null;
        float speed = fix.hasSpeed() && (!fix.hasSpeedAccuracy() || fix.getSpeedAccuracyMetersPerSecond() <= 1.5f)
                ? fix.getSpeed() : Float.NaN;
        Ride completed = tracker.accept(new Tracker.Fix(fix.getLatitude(), fix.getLongitude(),
                fix.getTime(), elapsed, fix.getAccuracy(), speed), places, ZoneId.systemDefault());
        save(completed);
        if (tracker.state() == Tracker.State.RIDING && elapsed >= nextCheckpoint) {
            Ride snapshot = tracker.snapshot();
            nextCheckpoint = elapsed + 6_000;
            if (snapshot != null) {
                store.saveRide(snapshot, true);
            }
        }
        if (firstFix || visibleState != tracker.state() || completed != null) {
            publish();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        lastLocation = null;
        statusDetail = "Turn on the phone’s location to record";
        publish();
    }

    @Override
    public void onProviderEnabled(String provider) {
        statusDetail = "Waiting for GPS";
        publish();
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
        visibleState = Tracker.State.DISARMED;
        lastLocation = null;
        statusDetail = null;
        handler.removeCallbacks(idleCheck);
        if (listening) {
            location.removeUpdates(this);
            listening = false;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendUpdate();
        stopSelf();
    }

    private void publish() {
        visibleState = tracker.state();
        if (listening) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        sendUpdate();
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
        boolean riding = visibleState == Tracker.State.RIDING || visibleState == Tracker.State.PAUSED;
        String label = switch (visibleState) {
            case RIDING -> "Riding";
            case PAUSED -> "Paused";
            default -> "Ready";
        };
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bike There · " + label)
                .setContentText(statusDetail != null ? statusDetail
                        : riding ? "Tracking your ride" : "Waiting for you to start cycling")
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
        handler.removeCallbacks(idleCheck);
        if (listening) {
            save(tracker.disarm());
            location.removeUpdates(this);
        }
        visibleState = Tracker.State.DISARMED;
        lastLocation = null;
        statusDetail = null;
        store.close();
        sendUpdate();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
