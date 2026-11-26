package dev.zeroone.bikethere;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends ComponentActivity {
    private static final int TRACKER = 0;
    private static final int PLACES = 1;
    private static final int SETTINGS = 2;
    private static final int STATS = 3;
    private static final int LOG = 4;
    private static final String STATS_DISTANCE = "statsDistance";

    private static final int LOCATION_PERMISSION = 10;
    private static final int NOTIFICATION_PERMISSION = 11;
    private static final int EXPORT = 12;
    private static final int MAP_PERMISSION = 13;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private RideStore store;
    private SharedPreferences preferences;
    private LinearLayout content;
    private RideUi ui;
    private RideColors colours;
    private long playbackOffset;
    private ScrollView scroll;
    private int page = TRACKER;
    private YearMonth statsMonth;
    private int historyLimit = 40;
    private String selectedRide;
    private String exportingRide;
    private boolean openToArm;
    private boolean resumed;
    private GpsStandby standby;
    private String pendingArm;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private CancellationSignal mapRequest;
    private Location roughLocation;
    private LiveTrackerPanel livePanel;
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            updateLivePanel();
            uiHandler.postDelayed(this, 1_000L);
        }
    };

    private final OnBackPressedCallback back = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (selectedRide != null) {
                closeRide();
            } else if (page == PLACES) {
                closePlaces();
            } else {
                page = TRACKER;
                render(false);
                findMapLocation(false);
            }
        }
    };

    private final BroadcastReceiver statusChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            syncStandby();
            if (TrackerService.LIVE_UPDATE.equals(intent.getAction())) {
                updateLivePanel();
            } else {
                render(true);
            }
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        setTheme(RideUi.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(saved);
        ui = new RideUi(this);
        colours = ui.colours;
        getOnBackPressedDispatcher().addCallback(this, back);
        store = new RideStore(this);
        preferences = TrackerService.preferences(this);
        standby = new GpsStandby(this, this::updateLivePanel);
        if (TrackerService.state() == Tracker.State.DISARMED) {
            store.recoverInterruptedRides();
        }
        if (saved != null) {
            page = saved.getInt("page", TRACKER);
            String month = saved.getString("statsMonth");
            statsMonth = month == null ? null : YearMonth.parse(month);
            historyLimit = saved.getInt("limit", 40);
            selectedRide = saved.getString("ride");
            exportingRide = saved.getString("export");
            pendingArm = saved.getString("arm");
            playbackOffset = saved.getLong("playback");
        } else {
            openToArm = !getIntent().getBooleanExtra("notification", false)
                    && preferences.getBoolean("openToArm", true);
        }
        render(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("notification", false)) {
            selectedRide = null;
            page = TRACKER;
            render(false);
            findMapLocation(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TrackerService.UPDATE);
        filter.addAction(TrackerService.LIVE_UPDATE);
        ContextCompat.registerReceiver(this, statusChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        render(true);
        uiHandler.post(liveTick);
        if (page == TRACKER && selectedRide == null) {
            findMapLocation(false);
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        resumed = true;
        syncStandby();
        if (pendingArm == null && !hasLocation() && !preferences.getBoolean("gpsPermissionAsked", false)) {
            preferences.edit().putBoolean("gpsPermissionAsked", true).apply();
            requestGpsPermission();
        }
        if (openToArm && hasLocation()) {
            openToArm = false;
            if (TrackerService.state() == Tracker.State.DISARMED && hasLocation() && hasNotifications()) {
                arm(TrackerService.ARM);
            }
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        standby.stop();
        super.onPause();
    }

    private void syncStandby() {
        if (resumed && hasLocation() && TrackerService.state() == Tracker.State.DISARMED) {
            standby.start();
        } else {
            standby.stop();
        }
    }

    private void requestGpsPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, MAP_PERMISSION);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(statusChanges);
        uiHandler.removeCallbacks(liveTick);
        if (mapRequest != null) {
            mapRequest.cancel();
            mapRequest = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
        if (statsMonth != null) {
            out.putString("statsMonth", statsMonth.toString());
        }
        out.putInt("limit", historyLimit);
        out.putString("ride", selectedRide);
        out.putString("export", exportingRide);
        out.putString("arm", pendingArm);
        out.putLong("playback", playbackOffset);
    }

    private void render(boolean keepScroll) {
        back.setEnabled(selectedRide != null || page != TRACKER);
        int scrollPosition = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        if (keepScroll && selectedRide != null && content != null) {
            refreshWeatherLabel();
            return;
        }
        LinearLayout root = ui.column();
        root.setBackgroundColor(colours.paper());
        ui.edgeToEdge(this, root);
        root.addView(header());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(23));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (selectedRide != null) {
            rideDetails();
        } else {
            switch (page) {
                case PLACES -> placesPage();
                case SETTINGS -> settingsPage();
                case STATS -> statsPage();
                case LOG -> historyPage();
                default -> add(trackingPanel(), 0);
            }
            root.addView(ui.hairline());
            root.addView(navigation());
        }
        setContentView(root);
        ScrollView newScroll = scroll;
        newScroll.post(() -> newScroll.scrollTo(0, scrollPosition));
    }

    private LinearLayout header() {
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(16), ui.dp(7), ui.dp(16), ui.dp(7));
        header.setMinimumHeight(ui.dp(55));
        if (selectedRide != null) {
            header.addView(ui.secondary("← Ride log", this::closeRide));
        } else {
            if (page == PLACES) {
                Button settings = ui.secondary("‹", this::closePlaces);
                settings.setContentDescription("Back to settings");
                header.addView(settings);
            }
            String title = switch (page) {
                case PLACES -> "Places";
                case SETTINGS -> "Settings";
                case STATS -> "Stats";
                case LOG -> "Ride log";
                default -> "BIKE THERE";
            };
            TextView heading = page == TRACKER ? ui.title(title, 34, colours.ink())
                    : ui.text(title, 24, true, colours.ink());
            ViewCompat.setAccessibilityHeading(heading, true);
            LinearLayout.LayoutParams headingSize = RideUi.weight();
            if (page == PLACES) headingSize.leftMargin = ui.dp(10);
            header.addView(heading, headingSize);
            if (page != TRACKER && TrackerService.state() != Tracker.State.DISARMED) {
                header.addView(ui.secondary("Tracker", () -> {
                            page = TRACKER;
                            render(false);
                            findMapLocation(false);
                        }));
            }
        }
        return header;
    }

    private LinearLayout navigation() {
        LinearLayout bar = ui.row();
        bar.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(3));
        bar.setBaselineAligned(false);
        String[] tabs = {"Tracker", "Ride log", "Stats", "Settings"};
        int[] pages = {TRACKER, LOG, STATS, SETTINGS};
        int[] icons = {R.drawable.tab_rides, R.drawable.tab_log, R.drawable.tab_stats, R.drawable.tab_settings};
        for (int i = 0; i < tabs.length; i++) {
            int tab = pages[i];
            boolean current = tab == page || (tab == SETTINGS && page == PLACES);
            int foreground = current ? colours.ink() : colours.muted();
            Button item = ui.button(tabs[i], current ? colours.panel() : Color.TRANSPARENT,
                    foreground, null, () -> {
                        page = tab;
                        render(false);
                        if (tab == TRACKER) {
                            findMapLocation(false);
                        }
                    });
            var icon = getDrawable(icons[i]).mutate();
            icon.setTint(foreground);
            icon.setBounds(0, 0, ui.dp(18), ui.dp(18));
            item.setCompoundDrawables(null, icon, null, null);
            item.setCompoundDrawablePadding(ui.dp(3));
            item.setTextSize(12);
            item.setPadding(ui.dp(3), ui.dp(7), ui.dp(3), ui.dp(6));
            item.setSelected(current);

            LinearLayout slot = ui.column();
            View indicator = new View(this);
            indicator.setBackgroundColor(current ? colours.accent() : Color.TRANSPARENT);
            slot.addView(indicator, new LinearLayout.LayoutParams(-1, ui.dp(2)));
            slot.addView(item, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams size = RideUi.weight();
            size.leftMargin = ui.dp(2);
            size.rightMargin = ui.dp(2);
            bar.addView(slot, size);
        }
        return bar;
    }

    private void refreshWeatherLabel() {
        TextView label = content.findViewWithTag("rideWeather");
        RideStore.Summary ride = entry(selectedRide);
        if (label != null && ride != null && ride.weather() != null) {
            label.setText(getString(R.string.ride_weather, ride.weather().label()));
        }
    }

    private LiveTrackerPanel trackingPanel() {
        if (livePanel == null) {
            livePanel = new LiveTrackerPanel(this, ui, preferences.getBoolean("maps", true), action -> {
                if (TrackerService.ARM.equals(action) || TrackerService.START.equals(action)) {
                    arm(action);
                } else {
                    send(action);
                }
            }, () -> {
                if (hasLocation()) findMapLocation(true);
                else requestGpsPermission();
            });
        }
        if (livePanel.getParent() instanceof android.view.ViewGroup parent) {
            parent.removeView(livePanel);
        }
        long now = SystemClock.elapsedRealtime();
        livePanel.showArea(roughLocation);
        livePanel.update(live(now), now, hasLocation());
        return livePanel;
    }

    private void updateLivePanel() {
        if (livePanel != null && page == TRACKER && selectedRide == null) {
            long now = SystemClock.elapsedRealtime();
            livePanel.update(live(now), now, hasLocation());
        }
    }

    private TrackerService.Live live(long now) {
        TrackerService.Live current = TrackerService.live(now);
        if (current.state() != Tracker.State.DISARMED) return current;
        return new TrackerService.Live(current.state(), false, standby.status(), standby.fix(),
                current.stats(), current.route());
    }

    private void findMapLocation(boolean prompt) {
        if (!MapLocation.permitted(this)) {
            if (prompt) {
                requestGpsPermission();
            }
            return;
        }
        Location cached = MapLocation.cached(this);
        if (cached != null) {
            showMapLocation(cached);
        }
        if (mapRequest != null) {
            if (!prompt) {
                return;
            }
            mapRequest.cancel();
        }
        CancellationSignal request = new CancellationSignal();
        mapRequest = request;
        MapLocation.request(this, request, fix -> {
            if (mapRequest == request && !request.isCanceled()) {
                showMapLocation(fix);
            }
        });
    }

    private void showMapLocation(Location location) {
        roughLocation = location;
        if (livePanel != null) {
            livePanel.showArea(location);
            updateLivePanel();
        }
    }

    private void historyPage() {
        List<RideStore.Summary> history = store.history();
        content.addView(ui.hairline());
        if (history.isEmpty()) {
            TextView empty = ui.text("No rides yet", 14, false, colours.muted());
            empty.setPadding(0, ui.dp(12), 0, 0);
            add(empty, 12);
        }
        for (RideStore.Summary ride : history.subList(0, Math.min(historyLimit, history.size()))) {
            content.addView(logEntry(ride));
            content.addView(ui.hairline());
        }
        if (history.size() > historyLimit) {
            Button more = ui.secondary("Show older rides", () -> {
                historyLimit += 40;
                render(true);
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
            size.topMargin = ui.dp(10);
            more.setLayoutParams(size);
            add(more, 10);
        }
    }

    private View logEntry(RideStore.Summary ride) {
        var started = Instant.ofEpochMilli(ride.start()).atZone(ZoneId.of(ride.zone()));
        LinearLayout row = actionRow(() -> {
            selectedRide = ride.id();
            playbackOffset = 0;
            render(false);
        });
        row.setGravity(Gravity.TOP);
        LinearLayout stamp = ui.column();
        stamp.addView(ui.code(started.format(DateTimeFormatter.ofPattern("dd")), 17, true, colours.ink()));
        TextView month = ui.code(started.format(DateTimeFormatter.ofPattern("MMM uuuu")), 10, false,
                colours.muted());
        month.setPadding(0, ui.dp(4), 0, 0);
        stamp.addView(month);
        row.addView(stamp, new LinearLayout.LayoutParams(ui.dp(76), -2));

        LinearLayout labels = ui.column();
        labels.addView(ui.text(ride.title(), 15, true, colours.ink()));
        String detail = started.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT));
        if (ride.weather() != null) {
            detail += " · " + ride.weather().label();
        }
        TextView note = ui.text(detail, 12, false, colours.muted());
        note.setPadding(0, ui.dp(4), 0, 0);
        labels.addView(note);
        row.addView(labels, RideUi.weight());
        TextView distance = ui.title(km(ride.distance()), 19, colours.ink());
        distance.setPadding(ui.dp(8), 0, 0, 0);
        row.addView(distance);
        return row;
    }

    private void statsPage() {
        RideCalendar calendar = new RideCalendar(YearMonth.now(), store.history());
        YearMonth displayed = calendar.visibleMonth(statsMonth);
        boolean distance = preferences.getBoolean(STATS_DISTANCE, false);
        add(new StatsPanel(this, ui, calendar, displayed, distance, month -> {
            statsMonth = calendar.visibleMonth(month);
            render(true);
        }, kilometres -> {
            preferences.edit().putBoolean(STATS_DISTANCE, kilometres).apply();
            render(true);
        }), 0);
    }

    private void placesPage() {
        add(ui.text("Stop inside a saved circle to finish and name a ride.", 15, false, colours.muted()), 13);
        add(ui.primary("+  Add a place", () -> openPlaceEditor(0)), 19);
        List<Tracker.Place> places = store.places();
        if (places.isEmpty()) {
            add(ui.text("No saved places", 15, false, colours.muted()), 12);
        }
        for (Tracker.Place place : places) {
            content.addView(listEntry(place.name(), getString(R.string.place_radius, place.radius()),
                    () -> openPlaceEditor(place.id())));
            content.addView(ui.hairline());
        }
    }

    private void closePlaces() {
        page = SETTINGS;
        render(false);
    }

    private void openPlaceEditor(long id) {
        startActivity(new Intent(this, PlaceEditorActivity.class).putExtra(PlaceEditorActivity.PLACE, id));
    }

    private View listEntry(String title, String detail, Runnable open) {
        LinearLayout row = actionRow(open);
        LinearLayout labels = ui.column();
        labels.addView(ui.text(title, 15, true, colours.ink()));
        TextView subtitle = ui.text(detail, 13, false, colours.muted());
        subtitle.setPadding(0, ui.dp(5), 0, 0);
        labels.addView(subtitle);
        row.addView(labels, RideUi.weight());
        TextView arrow = ui.text("›", 28, false, colours.accent());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        return row;
    }

    private LinearLayout actionRow(Runnable open) {
        LinearLayout row = ui.row();
        row.setPadding(0, ui.dp(11), ui.dp(3), ui.dp(11));
        row.setMinimumHeight(ui.dp(52));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(colours.dark() ? 0x2cffffff : 0x25232620),
                null, ui.shape(Color.WHITE, 0, null)));
        row.setOnClickListener(view -> open.run());
        row.setFocusable(true);
        return row;
    }

    private void settingsPage() {
        LinearLayout version = ui.row();
        version.setPadding(0, ui.dp(2), 0, ui.dp(8));
        version.addView(ui.text("App version", 14, false, colours.ink()), RideUi.weight());
        version.addView(ui.code(installedVersion(), 13, false, colours.muted()));
        add(version, 0);
        add(ui.hairline(), 15);
        add(section("Appearance"), 6);
        int current = appearanceIndex();
        String[] labels = {"System", "Light", "Dark"};
        LinearLayout modes = ui.row();
        for (int i = 0; i < labels.length; i++) {
            int mode = i;
            Button button = current == i
                    ? ui.button(labels[i], colours.ink(), colours.paper(), null, () -> {})
                    : ui.secondary(labels[i], () -> changeAppearance(mode));
            button.setSelected(current == i);
            LinearLayout.LayoutParams size = RideUi.weight();
            size.rightMargin = i < labels.length - 1 ? ui.dp(7) : 0;
            modes.addView(button, size);
        }
        add(modes, 15);
        add(ui.hairline(), 13);
        add(section("Tracking"), 5);
        toggle("Enable auto-start when opening Bike There", "openToArm", true);
        int timeout = preferences.getInt(TrackerService.SET_TIMEOUT, TrackerService.DEFAULT_TIMEOUT);
        content.addView(listEntry("Auto-start timeout", getString(R.string.timeout_minutes, timeout), () -> editTimeout(timeout)));
        content.addView(listEntry("Places", "Manage saved arrival circles", () -> {
            page = PLACES;
            render(false);
        }));
        add(ui.hairline(), 13);
        add(section("Maps & weather"), 5);
        toggle("OpenStreetMap tiles", "maps", true);
        add(ui.text("Ride playback and saved circles work offline.", 13, false, colours.muted()), 10);
        toggle("Weather from Open-Meteo", "weather", false);
        add(ui.text("Weather lookup sends the approximate ride start and date to Open-Meteo.", 13, false, colours.muted()), 10);
    }

    private String installedVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "Unavailable" : version;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "Unavailable";
        }
    }

    private int appearanceIndex() {
        return switch (preferences.getString(RideUi.APPEARANCE, "system")) {
            case "light" -> 1;
            case "dark" -> 2;
            default -> 0;
        };
    }

    private void changeAppearance(int mode) {
        String[] choices = {"system", "light", "dark"};
        preferences.edit().putString(RideUi.APPEARANCE, choices[mode]).apply();
        recreate();
    }

    private TextView section(String label) {
        TextView title = ui.text(label, 19, true, colours.ink());
        ViewCompat.setAccessibilityHeading(title, true);
        return title;
    }

    private void editTimeout(int timeout) {
        String[] labels = {"5 minutes", "10 minutes", "20 minutes", "30 minutes", "60 minutes"};
        int[] choices = {5, 10, 20, 30, 60};
        int selected = java.util.Arrays.binarySearch(choices, timeout);
        new AlertDialog.Builder(this).setTitle("Disarm after waiting")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferences.edit().putInt(TrackerService.SET_TIMEOUT, choices[which]).apply();
                    if (TrackerService.state() != Tracker.State.DISARMED) {
                        send(TrackerService.SET_TIMEOUT);
                    }
                    dialog.dismiss();
                    render(true);
                }).setNegativeButton("Cancel", null).show();
    }

    private void toggle(String label, String key, boolean initial) {
        Switch control = new Switch(this);
        control.setMinHeight(ui.dp(48));
        control.setTextColor(colours.ink());
        control.setText(label);
        control.setChecked(preferences.getBoolean(key, initial));
        control.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            if ("maps".equals(key)) {
                livePanel = null;
            }
        });
        add(control, 3);
    }

    private void rideDetails() {
        RideStore.Summary ride = entry(selectedRide);
        if (ride == null) {
            return;
        }
        List<Ride.Point> route = store.route(ride.id());
        add(ui.text(ride.title(), 25, true, colours.ink()), 7);
        add(ui.text(date(ride), 14, false, colours.muted()), 12);
        LinearLayout summary = ui.column();
        summary.setPadding(ui.dp(11), ui.dp(8), ui.dp(11), ui.dp(9));
        summary.setBackground(ui.shape(colours.panel(), 0, colours.line()));
        summary.addView(ui.title(km(ride.distance()), 36, colours.ink()));
        TextView duration = ui.text(getString(R.string.ride_duration,
                duration(ride.moving()), duration(ride.elapsed())), 13, false, colours.muted());
        duration.setPadding(0, ui.dp(5), 0, 0);
        summary.addView(duration);
        add(summary, 14);

        TextView conditions = ui.text(weatherText(ride), 14, false, colours.muted());
        conditions.setTag("rideWeather");
        add(conditions, 12);
        if (ride.weather() == null && preferences.getBoolean("weather", false) && !route.isEmpty()) {
            add(ui.secondary("Look up weather", () -> {
                conditions.setText(R.string.weather_loading);
                Weather.load(this, ride.id(), route.get(0), () -> runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        conditions.setText(weatherText(entry(ride.id())));
                    }
                }));
            }), 12);
        }
        if (route.isEmpty()) {
            return;
        }
        showPlayback(ride, route);
        add(ui.primary("Export GPX", () -> export(ride)), 12);
    }

    private String weatherText(RideStore.Summary ride) {
        return ride != null && ride.weather() != null
                ? getString(R.string.ride_weather, ride.weather().label()) : getString(R.string.weather_unavailable);
    }

    private void showPlayback(RideStore.Summary ride, List<Ride.Point> route) {
        RouteMap map = new RouteMap(this, route, preferences.getBoolean("maps", true), colours);
        LinearLayout mapHeader = ui.row();
        mapHeader.addView(section("Route"), RideUi.weight());
        mapHeader.addView(ui.secondary("Fit route", map::fit));
        add(mapHeader, 7);
        map.setBackground(ui.shape(colours.panel(), 1, colours.line()));
        map.setClipToOutline(true);
        content.addView(map, new LinearLayout.LayoutParams(-1, ui.dp(300)));
        TextView legend = ui.text("▶ Start    ▦ Finish", 12, true, colours.muted());
        legend.setContentDescription("Triangle marks the start. Checkered flag marks the finish.");
        legend.setPadding(0, ui.dp(7), 0, 0);
        add(legend, 0);

        LinearLayout timeline = ui.row();
        timeline.setPadding(0, ui.dp(13), 0, 0);
        timeline.addView(ui.text("PLAYBACK", 11, true, colours.muted()), RideUi.weight());
        TextView position = ui.text("", 15, true, colours.ink());
        timeline.addView(position);
        content.addView(timeline);

        SeekBar slider = new SeekBar(this);
        slider.setContentDescription("Time along the ride");
        slider.setMax(2000);
        slider.setProgressTintList(ColorStateList.valueOf(colours.accent()));
        slider.setThumbTintList(ColorStateList.valueOf(colours.accent()));
        content.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(47)));
        RideProfile data = new RideProfile(route);

        TextView speedLabel = ui.title("", 22, colours.accent());
        TextView elevationLabel = ui.title("", 22, colours.ink());
        ProfileChart speedChart = new ProfileChart(this, data, RideProfile.Metric.SPEED, colours);
        ProfileChart altitudeChart = new ProfileChart(this, data, RideProfile.Metric.ELEVATION, colours);
        java.util.function.LongConsumer scrubTo = epoch -> {
            long offset = epoch - data.start();
            slider.setProgress((int) Math.round(2000.0 * offset / Math.max(1, ride.elapsed())));
            updatePlayback(map, data, offset, position, slider, speedLabel, elevationLabel);
            speedChart.seek(epoch);
            altitudeChart.seek(epoch);
        };
        speedChart.setOnScrub(scrubTo);
        altitudeChart.setOnScrub(scrubTo);
        addProfile("Speed", speedLabel, speedChart, "No speed samples for this ride.");
        if (route.stream().noneMatch(point -> Double.isFinite(point.speedMetresPerSecond())) && speedChart.hasData()) {
            add(ui.text("Estimated from the recorded route.", 13, false, colours.muted()), 15);
        }
        addProfile("Elevation", elevationLabel, altitudeChart, "No GPS elevation was recorded for this ride.");
        if (altitudeChart.hasData()) {
            add(ui.text("GPS elevation, smoothed over nearby samples.", 13, false, colours.muted()), 15);
        }

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                if (fromUser) {
                    long offset = ride.elapsed() * progress / 2000;
                    scrubTo.accept(ride.start() + offset);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        long restored = Math.min(playbackOffset, ride.elapsed());
        scrubTo.accept(ride.start() + restored);
    }

    private void addProfile(String title, TextView value, ProfileChart chart, String empty) {
        LinearLayout header = ui.row();
        header.addView(section(title), RideUi.weight());
        if (chart.hasData()) {
            header.addView(value);
        }
        add(header, 4);
        if (chart.hasData()) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, ui.dp(122));
            size.bottomMargin = ui.dp(10);
            content.addView(chart, size);
        } else {
            add(ui.text(empty, 14, false, colours.muted()), 18);
        }
    }

    private void updatePlayback(RouteMap map, RideProfile data, long offset, TextView position, SeekBar slider,
                                TextView speed, TextView elevation) {
        playbackOffset = offset;
        long time = data.start() + offset;
        map.seek(time);
        String displayTime = getString(R.string.ride_playback_time, duration(offset), duration(data.end() - data.start()));
        position.setText(displayTime);
        ViewCompat.setStateDescription(slider, displayTime);
        speed.setText(metric(data.valueAt(RideProfile.Metric.SPEED, time), R.string.profile_speed));
        elevation.setText(metric(data.valueAt(RideProfile.Metric.ELEVATION, time), R.string.profile_elevation));
    }

    private String metric(double value, int units) {
        if (!Double.isFinite(value)) {
            return getString(R.string.profile_missing);
        }
        return getString(units, value);
    }

    private RideStore.Summary entry(String id) {
        for (RideStore.Summary ride : store.history()) {
            if (ride.id().equals(id)) {
                return ride;
            }
        }
        return null;
    }

    private void closeRide() {
        selectedRide = null;
        page = LOG;
        render(false);
    }

    private void arm(String action) {
        if (TrackerService.state() != Tracker.State.DISARMED) {
            send(action);
            return;
        }
        pendingArm = action;
        if (!hasLocation()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        if (!hasNotifications()) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            } else {
                showPermissionSettings("Enable notifications so the tracking status is visible.");
            }
            return;
        }
        pendingArm = null;
        findMapLocation(false);
        Intent start = new Intent(this, TrackerService.class).setAction(action);
        if (TrackerService.START.equals(action)) {
            start.putExtra(TrackerService.START_FIX, standby.fix());
        }
        startForegroundService(start);
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotifications() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        var channel = manager.getNotificationChannel("ride-tracking");
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    private void send(String action) {
        startService(new Intent(this, TrackerService.class).setAction(action));
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MAP_PERMISSION) {
            syncStandby();
            updateLivePanel();
            findMapLocation(false);
            return;
        }
        if (pendingArm == null) {
            return;
        }
        if ((request == LOCATION_PERMISSION && hasLocation())
                || (request == NOTIFICATION_PERMISSION && hasNotifications())) {
            arm(pendingArm);
        } else {
            pendingArm = null;
            showPermissionSettings(request == LOCATION_PERMISSION
                    ? "Bike There needs precise location to record a ride."
                    : "Bike There needs notifications to show when tracking is armed.");
        }
    }

    private void showPermissionSettings(String message) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null)
                .setPositiveButton("App settings", (dialog, which) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
                .show();
    }

    private void export(RideStore.Summary ride) {
        exportingRide = ride.id();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/gpx+xml").putExtra(Intent.EXTRA_TITLE,
                        "Bike There " + Instant.ofEpochMilli(ride.start()).atZone(ZoneId.of(ride.zone())).toLocalDate()
                                + ".gpx");
        startActivityForResult(intent, EXPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT) {
            return;
        }
        String id = exportingRide;
        exportingRide = null;
        if (result != RESULT_OK || data == null || data.getData() == null || id == null) {
            return;
        }
        RideStore.Summary ride = entry(id);
        if (ride == null) {
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (output == null) {
                throw new java.io.IOException("Could not open destination");
            }
            var serializer = android.util.Xml.newSerializer();
            serializer.setOutput(output, StandardCharsets.UTF_8.name());
            serializer.startDocument("UTF-8", true);
            String namespace = "http://www.topografix.com/GPX/1/1";
            serializer.setPrefix("", namespace);
            serializer.startTag(namespace, "gpx").attribute(null, "version", "1.1")
                    .attribute(null, "creator", "Bike There");
            serializer.startTag(namespace, "trk");
            serializer.startTag(namespace, "name").text(ride.title()).endTag(namespace, "name");
            boolean inSegment = false;
            for (Ride.Point point : store.route(id)) {
                if (point.segmentStart() || !inSegment) {
                    if (inSegment) {
                        serializer.endTag(namespace, "trkseg");
                    }
                    serializer.startTag(namespace, "trkseg");
                    inSegment = true;
                }
                serializer.startTag(namespace, "trkpt").attribute(null, "lat", Double.toString(point.lat()))
                        .attribute(null, "lon", Double.toString(point.lon()));
                if (Double.isFinite(point.elevationMetres())) {
                    serializer.startTag(namespace, "ele").text(Double.toString(point.elevationMetres()))
                            .endTag(namespace, "ele");
                }
                serializer.startTag(namespace, "time").text(Instant.ofEpochMilli(point.time()).toString())
                        .endTag(namespace, "time");
                serializer.endTag(namespace, "trkpt");
            }
            if (inSegment) {
                serializer.endTag(namespace, "trkseg");
            }
            serializer.endTag(namespace, "trk").endTag(namespace, "gpx").endDocument();
            toast("GPX saved");
        } catch (java.io.IOException e) {
            toast("Could not write the GPX file");
        }
    }

    private void add(View view, int bottomMargin) {
        LinearLayout.LayoutParams size = view.getLayoutParams() instanceof LinearLayout.LayoutParams existing
                ? existing : new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = ui.dp(bottomMargin);
        content.addView(view, size);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String km(double metres) {
        return String.format(Locale.getDefault(), "%.1f km", metres / 1000);
    }

    private static String date(RideStore.Summary ride) {
        return DATE.format(Instant.ofEpochMilli(ride.start()).atZone(ZoneId.of(ride.zone())));
    }

    static String duration(long millis) {
        long seconds = millis / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60,
                    seconds % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
