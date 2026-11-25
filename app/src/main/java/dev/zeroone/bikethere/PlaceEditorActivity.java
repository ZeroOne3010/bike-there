package dev.zeroone.bikethere;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.view.ViewCompat;

import java.util.Locale;

public final class PlaceEditorActivity extends ComponentActivity {
    static final String PLACE = "place";
    private static final int MIN_RADIUS = 20, MAX_RADIUS = 500;
    private RideStore store;
    private RideUi ui;
    private RideColors colours;
    private EditText name;
    private TextView coordinateHint;
    private PlaceMap map;
    private double lat = Double.NaN, lon = Double.NaN;
    private int radius = 60;
    private int selection;
    private long placeId;
    private CancellationSignal locating;
    private CancellationSignal roughMapRequest;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable locationTimeout = () -> {
        cancelLocation();
        updatePositionHint();
        toast("Couldn’t get a location. You can still choose the centre on the map.");
    };

    private final androidx.activity.result.ActivityResultLauncher<String[]> permission = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), granted -> locate(false));

    @Override
    public void onCreate(Bundle saved) {
        setTheme(RideUi.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(saved);
        ui = new RideUi(this);
        colours = ui.colours;
        store = new RideStore(this);
        placeId = getIntent().getLongExtra(PLACE, 0);
        var places = store.places();
        Tracker.Place existing = places.stream().filter(place -> place.id() == placeId).findFirst().orElse(null);
        String initialName = "";
        if (existing != null) {
            lat = existing.lat();
            lon = existing.lon();
            radius = existing.radius();
            initialName = existing.name();
        }
        if (saved != null) {
            lat = saved.getDouble("lat", Double.NaN);
            lon = saved.getDouble("lon", Double.NaN);
            radius = saved.getInt("radius", 60);
            initialName = saved.getString("name", "");
        }

        LinearLayout root = ui.column();
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(colours.paper());
        ui.edgeToEdge(this, root);
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(20), ui.dp(7), ui.dp(16), ui.dp(9));
        header.addView(ui.title(existing == null ? "New place" : "Edit place", 38, colours.ink()), RideUi.weight());
        header.addView(ui.secondary("Cancel", this::finish));
        root.addView(header);
        root.addView(ui.hairline());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout form = ui.column();
        form.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(12));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        form.addView(ui.text("Place name", 13, true, colours.muted()));
        name = new EditText(this);
        name.setSingleLine();
        name.setTextSize(19);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint("Home, daycare…");
        name.setContentDescription("Place name");
        name.setText(initialName);
        name.setTextColor(colours.ink());
        name.setHintTextColor(colours.muted());
        name.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        name.setBackground(ui.shape(colours.panel(), 12, colours.line()));
        LinearLayout.LayoutParams nameSize = new LinearLayout.LayoutParams(-1, ui.dp(48));
        nameSize.setMargins(0, ui.dp(6), 0, ui.dp(15));
        form.addView(name, nameSize);

        form.addView(ui.text("Tap the map to choose the centre.", 14, false, colours.muted()));
        map = new PlaceMap(this, colours, TrackerService.preferences(this).getBoolean("maps", true), radius,
                (latitude, longitude) -> {
                    lat = latitude;
                    lon = longitude;
                    selection++;
                    updatePositionHint();
                });
        map.setBackground(ui.shape(colours.panel(), 16, colours.line()));
        map.setClipToOutline(true);
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-1, ui.dp(312));
        mapSize.setMargins(0, ui.dp(8), 0, ui.dp(6));
        form.addView(map, mapSize);
        if (Double.isFinite(lat)) {
            map.select(lat, lon, true);
            map.post(() -> map.setRadius(radius));
        } else if (!places.isEmpty()) {
            Tracker.Place nearby = places.get(0);
            map.setCamera(SlippyMap.mapX(nearby.lon()), SlippyMap.mapY(nearby.lat()), 15);
        }
        if (saved != null) {
            map.setCamera(saved.getDouble("mapX", map.centreX), saved.getDouble("mapY", map.centreY),
                    saved.getDouble("zoom", map.zoom));
        }
        coordinateHint = ui.text("", 12, false, colours.muted());
        updatePositionHint();
        form.addView(coordinateHint);

        LinearLayout actions = ui.row();
        actions.addView(ui.secondary("My location", () -> locate(true)), RideUi.weight());
        var zoomOut = ui.secondary("−", () -> map.zoomBy(-1));
        zoomOut.setContentDescription("Zoom out");
        addSpaced(actions, zoomOut);
        var zoomIn = ui.secondary("+", () -> map.zoomBy(1));
        zoomIn.setContentDescription("Zoom in");
        addSpaced(actions, zoomIn);
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, -2);
        actionSize.setMargins(0, ui.dp(6), 0, ui.dp(15));
        form.addView(actions, actionSize);

        LinearLayout radiusRow = ui.row();
        radiusRow.addView(ui.title("Arrival radius", 24, colours.ink()), RideUi.weight());
        TextView size = ui.title(radiusLabel(), 25, colours.accent());
        radiusRow.addView(size);
        form.addView(radiusRow);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription("Arrival radius, 20 to 500 metres");
        slider.setMax(MAX_RADIUS - MIN_RADIUS);
        slider.setProgress(radius - MIN_RADIUS);
        slider.setProgressTintList(ColorStateList.valueOf(colours.accent()));
        slider.setThumbTintList(ColorStateList.valueOf(colours.accent()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                radius = MIN_RADIUS + progress;
                size.setText(radiusLabel());
                map.setRadius(radius);
                ViewCompat.setStateDescription(seek, radiusLabel());
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        ViewCompat.setStateDescription(slider, radiusLabel());
        form.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(48)));
        if (!TrackerService.preferences(this).getBoolean("maps", true)) {
            form.addView(ui.text("Map tiles are off in Settings.", 13, false, colours.muted()));
        }

        root.addView(ui.hairline());
        LinearLayout footer = ui.row();
        footer.setPadding(ui.dp(20), ui.dp(9), ui.dp(20), ui.dp(10));
        if (existing != null) {
            footer.addView(ui.secondary("Delete", () -> confirmDelete(existing)));
        }
        LinearLayout.LayoutParams saveSize = RideUi.weight();
        if (existing != null) {
            saveSize.leftMargin = ui.dp(8);
        }
        footer.addView(ui.primary("Save place", this::save), saveSize);
        root.addView(footer);
        setContentView(root);
        root.requestFocus();

        if (existing == null && saved == null) {
            centreRoughly();
            if (hasPreciseLocation()) {
                locate(false);
            }
        }
    }

    private void addSpaced(LinearLayout row, View view) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(48), -2);
        size.leftMargin = ui.dp(6);
        row.addView(view, size);
    }

    private String radiusLabel() {
        return getString(R.string.radius_metres, radius);
    }

    private void updatePositionHint() {
        if (coordinateHint == null) {
            return;
        }
        coordinateHint.setText(Double.isFinite(lat) ? getString(R.string.map_coordinates,
                String.format(Locale.getDefault(), "%.5f", lat), String.format(Locale.getDefault(), "%.5f", lon))
                : getString(R.string.map_no_centre));
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void centreRoughly() {
        if (!MapLocation.permitted(this)) {
            return;
        }
        Location cached = MapLocation.cached(this);
        if (cached != null) {
            centreMap(cached);
        }
        int camera = map.userCameraChanges();
        roughMapRequest = new CancellationSignal();
        MapLocation.request(this, roughMapRequest, fix -> {
            if (!Double.isFinite(lat) && map.userCameraChanges() == camera) {
                centreMap(fix);
            }
        });
    }

    private void centreMap(Location location) {
        map.setCamera(SlippyMap.mapX(location.getLongitude()), SlippyMap.mapY(location.getLatitude()),
                MapLocation.cityZoom(location));
    }

    private void locate(boolean prompt) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (prompt) {
                permission.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION});
            }
            return;
        }
        LocationManager manager = getSystemService(LocationManager.class);
        Location recent = TrackerService.fix();
        if (nearbyFix(recent)) {
            map.select(recent.getLatitude(), recent.getLongitude(), true);
            map.setRadius(radius);
            return;
        }
        String provider = hasPreciseLocation() && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        try {
            if (!manager.isProviderEnabled(provider)) {
                toast("Turn on location, or choose the centre on the map.");
                return;
            }
            recent = manager.getLastKnownLocation(provider);
            if (nearbyFix(recent)) {
                map.select(recent.getLatitude(), recent.getLongitude(), true);
                map.setRadius(radius);
                return;
            }
            cancelLocation();
            locating = new CancellationSignal();
            CancellationSignal request = locating;
            int currentSelection = selection;
            int currentCamera = map.userCameraChanges();
            coordinateHint.setText(R.string.map_locating);
            handler.postDelayed(locationTimeout, 14_000);
            LocationManagerCompat.getCurrentLocation(manager, provider, request, ContextCompat.getMainExecutor(this), fix -> {
                if (locating != request) {
                    return;
                }
                cancelLocation();
                boolean unchanged = selection == currentSelection && map.userCameraChanges() == currentCamera;
                if (nearbyFix(fix) && unchanged) {
                    map.select(fix.getLatitude(), fix.getLongitude(), true);
                    map.setRadius(radius);
                } else if (fix != null && unchanged) {
                    centreMap(fix);
                    coordinateHint.setText(R.string.map_approximate);
                } else {
                    updatePositionHint();
                    if (fix == null && unchanged) {
                        toast("Couldn’t get a location. Tap the map to set the centre.");
                    }
                }
            });
        } catch (IllegalArgumentException | SecurityException e) {
            cancelLocation();
            updatePositionHint();
            toast("Choose the centre on the map.");
        }
    }

    private static boolean nearbyFix(Location fix) {
        return fix != null && fix.hasAccuracy() && fix.getAccuracy() <= 120
                && SystemClock.elapsedRealtime() - fix.getElapsedRealtimeNanos() / 1_000_000 <= 120_000;
    }

    private void cancelLocation() {
        handler.removeCallbacks(locationTimeout);
        if (locating != null) {
            CancellationSignal current = locating;
            locating = null;
            current.cancel();
        }
    }

    private void save() {
        String title = name.getText().toString().trim();
        if (title.isEmpty()) {
            name.setError("Enter a name");
        } else if (!Double.isFinite(lat)) {
            toast("Tap the map to choose an arrival centre.");
        } else {
            store.savePlace(placeId, title, lat, lon, radius);
            closeWithChanges();
        }
    }

    private void confirmDelete(Tracker.Place place) {
        new AlertDialog.Builder(this).setMessage("Delete " + place.name() + " from saved places?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, choice) -> {
                    store.deletePlace(place.id());
                    closeWithChanges();
                }).show();
    }

    private void closeWithChanges() {
        if (TrackerService.state() != Tracker.State.DISARMED) {
            startService(new Intent(this, TrackerService.class).setAction(TrackerService.PLACES));
        }
        finish();
    }

    private void toast(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        if (roughMapRequest != null) {
            roughMapRequest.cancel();
            roughMapRequest = null;
        }
        cancelLocation();
        updatePositionHint();
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
        out.putDouble("lat", lat);
        out.putDouble("lon", lon);
        out.putInt("radius", radius);
        out.putString("name", name.getText().toString());
        out.putDouble("mapX", map.centreX);
        out.putDouble("mapY", map.centreY);
        out.putDouble("zoom", map.zoom);
    }
}
