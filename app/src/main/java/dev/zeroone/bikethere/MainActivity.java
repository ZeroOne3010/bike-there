package dev.zeroone.bikethere;

import android.Manifest;
import android.app.Activity;
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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

public final class MainActivity extends Activity {
    static final int PAPER = 0xfff4f5ef;
    static final int INK = 0xff192820;
    static final int MUTED = 0xff455a4e;
    static final int LEAF = 0xff21644e;
    static final int TINT = 0xffdeece1;
    private static final int LOCATION_PERMISSION = 10;
    private static final int NOTIFICATION_PERMISSION = 11;
    private static final int EXPORT = 12;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private RideStore store;
    private SharedPreferences preferences;
    private LinearLayout content;
    private View status;
    private ScrollView scroll;
    private int page;
    private int historyLimit = 40;
    private String selectedRide;
    private String exportingRide;
    private boolean openToArm;
    private String pendingArm;

    private final BroadcastReceiver statusChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render(true);
        }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusChanges, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusChanges, filter);
        }
        render(true);
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
    }

    private void render(boolean keepScroll) {
        int scrollPosition = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        if (keepScroll && selectedRide != null && content != null) {
            refreshTrackerStatus();
            TextView weatherLabel = content.findViewWithTag("rideWeather");
            if (weatherLabel != null) {
                for (RideStore.Summary entry : store.history()) {
                    if (entry.id().equals(selectedRide) && entry.weather() != null) {
                        weatherLabel.setText(entry.weather().label() + " · Open-Meteo");
                        break;
                    }
                }
            }
            return;
        }
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(5), dp(20), dp(5));
        TextView heading = text("Bike There", 27, true, INK);
        heading.setLetterSpacing(-0.045f);
        header.addView(heading, weight());
        if (selectedRide != null) {
            header.addView(button("History", false, this::closeRide));
        }
        root.addView(header);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(20));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = null;
        refreshTrackerStatus();
        if (selectedRide != null) {
            rideDetails();
        } else {
            switch (page) {
                case 1 -> placesPage();
                case 2 -> settingsPage();
                default -> historyPage();
            }
            LinearLayout navigation = row();
            navigation.setPadding(dp(16), dp(4), dp(16), dp(8));
            String[] tabs = {"Rides", "Places", "Settings"};
            for (int i = 0; i < tabs.length; i++) {
                int tab = i;
                Button item = button(tabs[i], page == tab, () -> {
                    page = tab;
                    render(false);
                });
                item.setSelected(page == tab);
                navigation.addView(item, weight());
            }
            root.addView(navigation);
        }
        setContentView(root);
        scroll.post(() -> scroll.scrollTo(0, scrollPosition));
    }

    private void refreshTrackerStatus() {
        if (status != null) {
            content.removeView(status);
        }
        Tracker.State state = TrackerService.state();
        LinearLayout card = card(state == Tracker.State.DISARMED ? Color.WHITE : TINT);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        String label = switch (state) {
            case DISARMED -> "Tracking off";
            case READY -> "Ready";
            case RIDING -> "Riding";
            case PAUSED -> "Paused";
        };
        top.addView(text(label, 20, true, INK), weight());
        top.addView(text(state == Tracker.State.DISARMED ? "○" : "●", 22, true, LEAF));
        card.addView(top);
        String detail = switch (state) {
            case DISARMED -> "Arm tracking before you set off.";
            case READY -> "Waiting for you to start cycling.";
            case RIDING -> "Recording your route.";
            case PAUSED -> "Waiting for you to move or confirm an arrival.";
        };
        if (TrackerService.detail() != null) {
            detail = TrackerService.detail();
        }
        card.addView(text(detail, 14, false, MUTED));
        LinearLayout actions = row();
        switch (state) {
            case DISARMED -> {
                actions.addView(button("Arm", true, () -> arm(TrackerService.ARM)));
                actions.addView(button("Start ride", false, () -> arm(TrackerService.START)));
            }
            case READY -> {
                actions.addView(button("Start ride", true, () -> arm(TrackerService.START)));
                actions.addView(button("Disarm", false, () -> send(TrackerService.DISARM)));
            }
            case RIDING, PAUSED -> {
                actions.addView(button("Finish ride", true, () -> send(TrackerService.FINISH)));
                actions.addView(button("Finish & disarm", false, () -> send(TrackerService.DISARM)));
            }
        }
        card.addView(actions);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(16);
        content.addView(card, 0, size);
        status = card;
    }

    private void historyPage() {
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
        LinearLayout totals = row();
        LinearLayout monthCard = card(Color.WHITE);
        monthCard.addView(text("THIS MONTH", 12, true, MUTED));
        monthCard.addView(text(km(monthMeters), 24, true, LEAF));
        LinearLayout yearCard = card(Color.WHITE);
        yearCard.addView(text("THIS YEAR", 12, true, MUTED));
        yearCard.addView(text(km(yearMeters), 24, true, LEAF));
        LinearLayout.LayoutParams left = weight();
        left.rightMargin = dp(8);
        totals.addView(monthCard, left);
        totals.addView(yearCard, weight());
        add(totals);
        add(text("Ride history", 22, true, INK));
        if (history.isEmpty()) {
            add(text("Finished rides will appear here.", 15, false, MUTED));
        }
        for (RideStore.Summary ride : history.subList(0, Math.min(historyLimit, history.size()))) {
            LinearLayout entry = card(Color.WHITE);
            entry.addView(text(ride.title(), 17, true, INK));
            entry.addView(text(date(ride) + "  ·  " + km(ride.distance()), 14, false, MUTED));
            if (ride.weather() != null) {
                entry.addView(text(ride.weather().label(), 14, false, MUTED));
            }
            entry.setOnClickListener(view -> {
                selectedRide = ride.id();
                render(false);
            });
            entry.setFocusable(true);
            add(entry);
        }
        if (history.size() > historyLimit) {
            add(button("Show older rides", false, () -> {
                historyLimit += 40;
                render(true);
            }));
        }
    }

    private void placesPage() {
        add(text("Saved places", 22, true, INK));
        add(text("A sustained stop within a saved place ends the current ride.", 15, false, MUTED));
        add(button("Add a place", true, () -> placeEditor(null)));
        for (Tracker.Place place : store.places()) {
            LinearLayout entry = card(Color.WHITE);
            entry.addView(text(place.name(), 17, true, INK));
            entry.addView(text(place.radius() + " m radius", 14, false, MUTED));
            entry.setOnClickListener(view -> placeEditor(place));
            entry.setFocusable(true);
            add(entry);
        }
    }

    private void placeEditor(Tracker.Place place) {
        LinearLayout form = column();
        form.setPadding(dp(22), dp(4), dp(22), 0);
        EditText name = field(form, "Name (e.g. Daycare)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int coordinate = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        EditText lat = field(form, "Latitude", coordinate);
        EditText lon = field(form, "Longitude", coordinate);
        EditText radius = field(form, "Radius in metres", InputType.TYPE_CLASS_NUMBER);
        if (place != null) {
            name.setText(place.name());
        }
        radius.setText(Integer.toString(place == null ? 60 : place.radius()));
        if (place != null) {
            lat.setText(Double.toString(place.lat()));
            lon.setText(Double.toString(place.lon()));
        }
        Runnable usePosition = () -> {
            Location fix = TrackerService.fix();
            if (fix == null || SystemClock.elapsedRealtime() - fix.getElapsedRealtimeNanos() / 1_000_000 > 30_000) {
                toast("Arm tracking and wait for GPS, or enter coordinates.");
            } else {
                lat.setText(String.format(Locale.US, "%.6f", fix.getLatitude()));
                lon.setText(String.format(Locale.US, "%.6f", fix.getLongitude()));
            }
        };
        form.addView(button("Use current location", false, usePosition));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(place == null ? "Add saved place" : "Edit " + place.name())
                .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null);
        if (place != null) {
            builder.setNeutralButton("Delete", (dialog, which) -> new AlertDialog.Builder(this)
                    .setMessage("Delete " + place.name() + " from saved places?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (confirm, option) -> {
                        store.deletePlace(place.id());
                        placesChanged();
                    }).show());
        }
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) {
                name.setError("Enter a name");
                return;
            }
            try {
                double latitude = decimal(lat);
                double longitude = decimal(lon);
                int metres = Integer.parseInt(radius.getText().toString().trim());
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || Math.abs(latitude) > 90 || Math.abs(longitude) > 180
                        || metres < 20 || metres > 500) {
                    throw new IllegalArgumentException();
                }
                store.savePlace(place == null ? 0 : place.id(), title, latitude, longitude, metres);
                placesChanged();
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                toast("Enter valid coordinates and a radius between 20 and 500 m.");
            }
        });
    }

    private void placesChanged() {
        if (TrackerService.state() != Tracker.State.DISARMED) {
            send(TrackerService.PLACES);
        }
        render(true);
    }

    private void settingsPage() {
        add(text("Tracking", 22, true, INK));
        toggle("Arm when opening Bike There", "openToArm", true);
        int timeout = preferences.getInt(TrackerService.SET_TIMEOUT, TrackerService.DEFAULT_TIMEOUT);
        add(button("Ready timeout · " + timeout + " min", false, () -> editTimeout(timeout)));
        add(text("When Ready expires, GPS and the status notification turn off.", 14, false, MUTED));
        add(text("Ride details", 22, true, INK));
        toggle("Weather from Open-Meteo", "weather", false);
        add(text("Weather lookup sends the approximate start of the ride and its date to Open-Meteo.",
                14, false, MUTED));
        toggle("OpenStreetMap background", "maps", true);
        add(text("Opening a ride downloads visible map tiles. Your route playback also works offline.",
                14, false, MUTED));
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
        control.setMinHeight(dp(48));
        control.setTextColor(INK);
        control.setText(label);
        control.setChecked(preferences.getBoolean(key, initial));
        control.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean(key, checked).apply());
        add(control);
    }

    private void rideDetails() {
        RideStore.Summary ride = entry(selectedRide);
        if (ride == null) {
            return;
        }
        List<Ride.Point> route = store.route(ride.id());
        add(text(ride.title(), 24, true, INK));
        add(text(date(ride), 14, false, MUTED));
        add(text(km(ride.distance()) + "  ·  " + duration(ride.moving()) + " moving  ·  "
                + duration(ride.elapsed()) + " elapsed", 15, true, LEAF));
        TextView conditions = text(ride.weather() == null ? "Weather unavailable"
                : ride.weather().label() + " · Open-Meteo", 15, false, MUTED);
        conditions.setTag("rideWeather");
        add(conditions);
        if (ride.weather() == null && preferences.getBoolean("weather", false) && !route.isEmpty()) {
            add(button("Look up weather", false, () -> {
                conditions.setText("Looking up weather…");
                Weather.load(this, ride.id(), route.get(0), () -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    RideStore.Summary updated = entry(ride.id());
                    conditions.setText(updated != null && updated.weather() != null
                            ? updated.weather().label() + " · Open-Meteo" : "Weather unavailable");
                }));
            }));
        }
        if (!route.isEmpty()) {
            RouteMap map = new RouteMap(this, route, preferences.getBoolean("maps", true));
            content.addView(map, new LinearLayout.LayoutParams(-1, dp(320)));
            TextView position = text("0:00 / " + duration(ride.elapsed()), 14, false, MUTED);
            SeekBar scrub = new SeekBar(this);
            scrub.setContentDescription("Position along ride");
            scrub.setMax(1000);
            scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    long offset = ride.elapsed() * progress / 1000;
                    map.seek(ride.start() + offset);
                    position.setText(duration(offset) + " / " + duration(ride.elapsed()));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            add(scrub);
            add(position);
            add(button("Export GPX", true, () -> export(ride)));
        }
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

    @Override
    public void onBackPressed() {
        if (selectedRide != null) {
            closeRide();
        } else if (page != 0) {
            page = 0;
            render(false);
        } else {
            super.onBackPressed();
        }
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

    private void add(View view) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(10);
        content.addView(view, size);
    }

    private EditText field(LinearLayout form, String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine();
        form.addView(field, -1, -2);
        return field;
    }

    private static double decimal(EditText field) {
        return Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
    }

    private Button button(String label, boolean primary, Runnable click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? LEAF : TINT));
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private LinearLayout card(int color) {
        LinearLayout layout = column();
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(14));
        layout.setBackground(background);
        layout.setPadding(dp(14), dp(10), dp(14), dp(10));
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private static String duration(long millis) {
        long seconds = millis / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60,
                    seconds % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
