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
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends ComponentActivity {
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
    private int page;
    private int historyLimit = 40;
    private String selectedRide;
    private String exportingRide;
    private boolean openToArm;
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
            } else {
                page = 0;
                render(false);
                findMapLocation(false);
            }
        }
    };

    private final BroadcastReceiver statusChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
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
        if (TrackerService.state() == Tracker.State.DISARMED) {
            store.recoverInterruptedRides();
        }
        if (saved != null) {
            page = saved.getInt("page");
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
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TrackerService.UPDATE);
        filter.addAction(TrackerService.LIVE_UPDATE);
        ContextCompat.registerReceiver(this, statusChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        render(true);
        uiHandler.post(liveTick);
        if (page == 0 && selectedRide == null) {
            findMapLocation(false);
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (openToArm) {
            openToArm = false;
            if (TrackerService.state() == Tracker.State.DISARMED && hasLocation() && hasNotifications()) {
                arm(TrackerService.ARM);
            }
        }
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
        out.putInt("limit", historyLimit);
        out.putString("ride", selectedRide);
        out.putString("export", exportingRide);
        out.putString("arm", pendingArm);
        out.putLong("playback", playbackOffset);
    }

    private void render(boolean keepScroll) {
        back.setEnabled(selectedRide != null || page != 0);
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
        content.setPadding(ui.dp(19), ui.dp(12), ui.dp(19), ui.dp(26));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (selectedRide != null) {
            rideDetails();
        } else {
            switch (page) {
                case 1 -> placesPage();
                case 2 -> settingsPage();
                default -> historyPage();
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
        header.setPadding(ui.dp(19), ui.dp(5), ui.dp(18), ui.dp(5));
        if (selectedRide != null) {
            header.addView(ui.secondary("← Rides", this::closeRide));
        } else {
            String title = switch (page) {
                case 1 -> "Places";
                case 2 -> "Settings";
                default -> "BIKE THERE";
            };
            TextView heading = ui.title(title, page == 0 ? 40 : 35, colours.ink());
            ViewCompat.setAccessibilityHeading(heading, true);
            header.addView(heading, RideUi.weight());
            if (page != 0 && TrackerService.state() != Tracker.State.DISARMED) {
                header.addView(ui.button("Tracker", colours.violet(), colours.violetInk(),
                        null, () -> {
                            page = 0;
                            render(false);
                            findMapLocation(false);
                        }));
            }
        }
        return header;
    }

    private LinearLayout navigation() {
        LinearLayout bar = ui.row();
        bar.setPadding(ui.dp(16), ui.dp(7), ui.dp(16), ui.dp(7));
        String[] tabs = {"Rides", "Places", "Settings"};
        int[] icons = {R.drawable.tab_rides, R.drawable.tab_places, R.drawable.tab_settings};
        for (int i = 0; i < tabs.length; i++) {
            int tab = i;
            boolean current = tab == page;
            int foreground = current ? colours.violetInk() : colours.muted();
            Button item = ui.button(tabs[i], current ? colours.violet() : Color.TRANSPARENT,
                    foreground, null, () -> {
                        page = tab;
                        render(false);
                        if (tab == 0) {
                            findMapLocation(false);
                        }
                    });
            var icon = getDrawable(icons[i]).mutate();
            icon.setTint(foreground);
            icon.setBounds(0, 0, ui.dp(20), ui.dp(20));
            item.setCompoundDrawables(null, icon, null, null);
            item.setCompoundDrawablePadding(ui.dp(3));
            item.setTextSize(12);
            item.setSelected(current);
            LinearLayout.LayoutParams size = RideUi.weight();
            size.leftMargin = ui.dp(3);
            size.rightMargin = ui.dp(3);
            bar.addView(item, size);
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
            }, () -> findMapLocation(true));
        }
        if (livePanel.getParent() instanceof android.view.ViewGroup parent) {
            parent.removeView(livePanel);
        }
        long now = SystemClock.elapsedRealtime();
        livePanel.showArea(roughLocation);
        livePanel.update(TrackerService.live(now), now);
        return livePanel;
    }

    private void updateLivePanel() {
        if (livePanel != null && page == 0 && selectedRide == null) {
            long now = SystemClock.elapsedRealtime();
            livePanel.update(TrackerService.live(now), now);
        }
    }

    private void findMapLocation(boolean prompt) {
        if (!MapLocation.permitted(this)) {
            if (prompt) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, MAP_PERMISSION);
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
        add(trackingPanel(), 20);
        List<RideStore.Summary> history = store.history();
        YearMonth month = YearMonth.now();
        Year year = Year.now();
        double monthMeters = 0;
        double yearMeters = 0;
        for (RideStore.Summary ride : history) {
            var date = Instant.ofEpochMilli(ride.start()).atZone(ZoneId.of(ride.zone()));
            if (YearMonth.from(date).equals(month)) {
                monthMeters += ride.distance();
            }
            if (Year.from(date).equals(year)) {
                yearMeters += ride.distance();
            }
        }
        LinearLayout totals = ui.row();
        totals.setBaselineAligned(false);
        totals.addView(distanceTotal("THIS MONTH", monthMeters), RideUi.weight());
        View divider = new View(this);
        divider.setBackgroundColor(colours.line());
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(ui.dp(1), ui.dp(55));
        dividerSize.setMargins(ui.dp(10), 0, ui.dp(15), 0);
        totals.addView(divider, dividerSize);
        totals.addView(distanceTotal("THIS YEAR", yearMeters), RideUi.weight());
        add(totals, 18);
        add(ui.hairline(), 17);
        add(section("Ride history"), 9);
        if (history.isEmpty()) {
            add(ui.text("No rides yet", 15, false, colours.muted()), 12);
        }
        for (RideStore.Summary ride : history.subList(0, Math.min(historyLimit, history.size()))) {
            String details = date(ride) + "  ·  " + km(ride.distance());
            if (ride.weather() != null) {
                details += "\n" + ride.weather().label();
            }
            content.addView(listEntry(ride.title(), details, () -> {
                selectedRide = ride.id();
                playbackOffset = 0;
                render(false);
            }));
            content.addView(ui.hairline());
        }
        if (history.size() > historyLimit) {
            add(ui.secondary("Show older rides", () -> {
                historyLimit += 40;
                render(true);
            }), 10);
        }
    }

    private View distanceTotal(String label, double metres) {
        LinearLayout column = ui.column();
        TextView caption = ui.text(label, 11, true, colours.muted());
        caption.setLetterSpacing(.1f);
        column.addView(caption);
        LinearLayout amount = ui.row();
        amount.setGravity(Gravity.BOTTOM);
        TextView value = ui.title(String.format(Locale.getDefault(), "%.1f", metres / 1000), 36, colours.ink());
        value.setMaxLines(1);
        value.setAutoSizeTextTypeUniformWithConfiguration(18, 36, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        amount.addView(value, new LinearLayout.LayoutParams(-2, ui.dp(42)));
        TextView units = ui.text("km", 14, true, colours.muted());
        units.setPadding(ui.dp(4), 0, 0, ui.dp(4));
        amount.addView(units);
        column.addView(amount);
        return column;
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

    private void openPlaceEditor(long id) {
        startActivity(new Intent(this, PlaceEditorActivity.class).putExtra(PlaceEditorActivity.PLACE, id));
    }

    private View listEntry(String title, String detail, Runnable open) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(4), ui.dp(13), ui.dp(5), ui.dp(13));
        row.setMinimumHeight(ui.dp(58));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x268d719e), null,
                ui.shape(Color.WHITE, 10, null)));
        LinearLayout labels = ui.column();
        labels.addView(ui.text(title, 18, true, colours.ink()));
        TextView subtitle = ui.text(detail, 13, false, colours.muted());
        subtitle.setPadding(0, ui.dp(5), 0, 0);
        labels.addView(subtitle);
        row.addView(labels, RideUi.weight());
        TextView arrow = ui.text("›", 28, false, colours.accent());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        row.setOnClickListener(view -> open.run());
        row.setFocusable(true);
        return row;
    }

    private void settingsPage() {
        add(section("Appearance"), 6);
        int current = appearanceIndex();
        String[] labels = {"System", "Light", "Dark"};
        LinearLayout modes = ui.row();
        for (int i = 0; i < labels.length; i++) {
            int mode = i;
            Button button = current == i
                    ? ui.button(labels[i], colours.violet(), colours.violetInk(), null, () -> {})
                    : ui.secondary(labels[i], () -> changeAppearance(mode));
            button.setSelected(current == i);
            LinearLayout.LayoutParams size = RideUi.weight();
            size.rightMargin = i < labels.length - 1 ? ui.dp(7) : 0;
            modes.addView(button, size);
        }
        add(modes, 20);
        add(ui.hairline(), 16);
        add(section("Tracking"), 5);
        toggle("Enable auto-start when opening Bike There", "openToArm", true);
        int timeout = preferences.getInt(TrackerService.SET_TIMEOUT, TrackerService.DEFAULT_TIMEOUT);
        content.addView(listEntry("Auto-start timeout", getString(R.string.timeout_minutes, timeout), () -> editTimeout(timeout)));
        add(ui.hairline(), 16);
        add(section("Maps & weather"), 5);
        toggle("OpenStreetMap tiles", "maps", true);
        add(ui.text("Ride playback and saved circles work offline.", 13, false, colours.muted()), 10);
        toggle("Weather from Open-Meteo", "weather", false);
        add(ui.text("Weather lookup sends the approximate ride start and date to Open-Meteo.", 13, false, colours.muted()), 10);
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
        TextView title = ui.title(label, 29, colours.ink());
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
        add(ui.title(ride.title(), 37, colours.ink()), 8);
        add(ui.text(date(ride), 14, false, colours.muted()), 12);
        LinearLayout summary = ui.column();
        summary.setPadding(ui.dp(15), ui.dp(13), ui.dp(15), ui.dp(13));
        summary.setBackground(ui.shape(colours.violet(), 15, null));
        summary.addView(ui.title(km(ride.distance()), 34, colours.violetInk()));
        TextView duration = ui.text(getString(R.string.ride_duration,
                duration(ride.moving()), duration(ride.elapsed())), 14, true, colours.violetInk());
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
        map.setBackground(ui.shape(colours.panel(), 16, colours.line()));
        map.setClipToOutline(true);
        content.addView(map, new LinearLayout.LayoutParams(-1, ui.dp(300)));

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
        TextView elevationLabel = ui.title("", 22, colours.dark() ? colours.violet() : colours.accent());
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
        startForegroundService(new Intent(this, TrackerService.class).setAction(action));
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
