package dev.zeroone.bikethere;

import android.content.Context;
import android.content.res.ColorStateList;
import android.location.Location;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

final class LiveTrackerPanel extends LinearLayout {
    private final RideUi ui;
    private final RideColors colours;
    private final LiveMap map;
    private final ProgressBar searching;
    private final View signalLamp;
    private final TextView status;
    private final TextView satellites;
    private final TextView locate;
    private final View recenter;
    private final TextView approximate;
    private final TextView elapsed;
    private final TextView average;
    private final TextView maximum;
    private final TextView help;
    private final LinearLayout actions;
    private final Consumer<String> dispatch;
    private Tracker.State controlsFor;
    private boolean controlsManual;

    LiveTrackerPanel(Context context, RideUi ui, boolean showMaps, Consumer<String> dispatch, Runnable findMe) {
        super(context);
        this.ui = ui;
        this.colours = ui.colours;
        this.dispatch = dispatch;
        setOrientation(VERTICAL);

        LinearLayout signal = ui.row();
        signal.setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7));
        signal.setMinimumHeight(ui.dp(41));
        signal.setBackgroundColor(RideColors.TRACK);
        searching = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        searching.setIndeterminateTintList(ColorStateList.valueOf(RideColors.SIGNAL));
        searching.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams spin = new LayoutParams(ui.dp(15), ui.dp(15));
        spin.rightMargin = ui.dp(8);
        signal.addView(searching, spin);
        signalLamp = new View(context);
        signalLamp.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams lamp = new LayoutParams(ui.dp(7), ui.dp(7));
        lamp.setMargins(ui.dp(4), 0, ui.dp(12), 0);
        signal.addView(signalLamp, lamp);
        status = ui.text("", 13, true, RideColors.ON_TRACK);
        androidx.core.view.ViewCompat.setAccessibilityHeading(status, true);
        signal.addView(status, RideUi.weight());
        satellites = ui.code("", 10, false, RideColors.TRACK_MUTED);
        satellites.setPadding(ui.dp(8), 0, 0, 0);
        signal.addView(satellites, new LayoutParams(0, -2, 1));
        satellites.setGravity(Gravity.END);
        addView(signal);

        FrameLayout hero = new FrameLayout(context);
        hero.setBackground(ui.shape(colours.panel(), 0, colours.line()));
        hero.setPadding(ui.dp(1), 0, ui.dp(1), ui.dp(1));
        map = new LiveMap(context, colours, showMaps);
        hero.addView(map, new FrameLayout.LayoutParams(-1, -1));

        locate = ui.button("Find my location", RideColors.TRACK, RideColors.ON_TRACK, null, findMe);
        hero.addView(locate, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        recenter = ui.button("Centre map", RideColors.TRACK, RideColors.ON_TRACK, null, map::recenter);
        FrameLayout.LayoutParams centreSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        centreSize.rightMargin = ui.dp(9);
        centreSize.bottomMargin = ui.dp(21);
        hero.addView(recenter, centreSize);
        approximate = ui.text("Approximate location", 12, true, RideColors.ON_TRACK);
        approximate.setBackground(ui.shape(RideColors.TRACK, 2, null));
        approximate.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        FrameLayout.LayoutParams approximateSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        approximateSize.leftMargin = ui.dp(9);
        approximateSize.bottomMargin = ui.dp(21);
        hero.addView(approximate, approximateSize);
        addView(hero, new LayoutParams(-1, ui.dp(263)));

        LinearLayout instruments = ui.row();
        instruments.setBaselineAligned(false);
        instruments.setPadding(0, ui.dp(10), 0, ui.dp(8));
        elapsed = addInstrument(instruments, "ELAPSED", 42, 1.55f);
        average = addInstrument(instruments, "AVG KM/H", 30, 1);
        maximum = addInstrument(instruments, "MAX KM/H", 30, 1);
        addView(instruments);
        addView(ui.hairline());

        help = ui.text("", 14, false, colours.muted());
        help.setPadding(0, ui.dp(9), 0, 0);
        actions = ui.row();
        actions.setPadding(0, ui.dp(10), 0, 0);
        addView(actions);
        addView(help);
    }

    void showArea(Location rough) {
        map.area(rough);
    }

    void update(TrackerService.Live live, long now, boolean precise) {
        Tracker.State state = live.state();
        GpsStatus gps = live.gps();
        boolean active = state != Tracker.State.DISARMED;
        boolean hasFix = gps.hasFix(now);
        boolean looking = gps.enabled() && !hasFix;
        map.update(live.fix(), hasFix, live.route());
        locate.setVisibility(!precise || (!map.hasPosition() && !active) ? VISIBLE : GONE);
        setText(locate, precise ? "Find my location" : "Enable precise GPS");
        recenter.setVisibility(map.hasPosition() ? VISIBLE : GONE);
        approximate.setVisibility(map.showsApproximatePosition() ? VISIBLE : GONE);
        searching.setVisibility(looking ? VISIBLE : GONE);
        signalLamp.setVisibility(looking ? GONE : VISIBLE);
        signalLamp.setBackgroundColor(hasFix ? RideColors.SIGNAL : RideColors.TRACK_MUTED);
        setText(status, gps.label(state, now, live.manualPending()));
        String signal = precise ? gps.signalLabel(now) : "GPS permission needed";
        if (gps.enabled() && gps.seen() >= 0) {
            signal += String.format(Locale.getDefault(), "\n%d seen · %d used", gps.seen(), gps.used());
        }
        setText(satellites, signal);
        setText(elapsed, MainActivity.duration(live.stats().elapsedMillis()));
        setText(average, speed(live.stats().averageSpeedMps()));
        setText(maximum, speed(live.stats().topSpeedMps()));
        setText(help, detail(live, hasFix));
        if (state != controlsFor || live.manualPending() != controlsManual) {
            controlsFor = state;
            controlsManual = live.manualPending();
            setupActions(state, controlsManual);
        }
    }

    private String detail(TrackerService.Live live, boolean hasFix) {
        if (live.state() == Tracker.State.DISARMED) {
            return "Auto-start waits for cycling. Manual start uses the GPS lock or waits for a fix.";
        }
        if (!live.gps().enabled()) {
            return "Turn on the phone’s location to track the ride.";
        }
        if (!hasFix) {
            return live.state() == Tracker.State.READY
                    ? live.manualPending() ? "The ride will start at the first usable fix."
                    : "The tracker will watch for cycling once it has a usable fix."
                    : "Waiting for a usable fix to continue recording the route.";
        }
        return switch (live.state()) {
            case READY -> live.manualPending() ? "Starting at the next usable fix." : "Start cycling to record automatically.";
            case RIDING -> "Average speed uses moving time.";
            case PAUSED -> "Move to resume, or stop at a saved place to finish.";
            case DISARMED -> "";
        };
    }

    private void setupActions(Tracker.State state, boolean manualPending) {
        actions.removeAllViews();
        switch (state) {
            case DISARMED -> {
                actions.addView(ui.primary("Auto-start", () -> dispatch.accept(TrackerService.ARM)), RideUi.weight());
                addSecondary("Manual start", TrackerService.START);
            }
            case READY -> {
                if (manualPending) {
                    actions.addView(ui.secondary("Disarm", () -> dispatch.accept(TrackerService.DISARM)));
                } else {
                    actions.addView(ui.primary("Manual start", () -> dispatch.accept(TrackerService.START)), RideUi.weight());
                    addSecondary("Disarm", TrackerService.DISARM);
                }
            }
            case RIDING, PAUSED -> {
                actions.addView(ui.primary("Finish ride", () -> dispatch.accept(TrackerService.FINISH)), RideUi.weight());
                addSecondary("Finish & disarm", TrackerService.DISARM);
            }
        }
    }

    private void addSecondary(String title, String action) {
        LayoutParams size = new LayoutParams(-2, -2);
        size.leftMargin = ui.dp(7);
        actions.addView(ui.secondary(title, () -> dispatch.accept(action)), size);
    }

    private TextView addInstrument(LinearLayout container, String name, int numberSize, float weight) {
        boolean first = container.getChildCount() == 0;
        if (!first) {
            View divider = new View(getContext());
            divider.setBackgroundColor(colours.line());
            container.addView(divider, new LayoutParams(ui.dp(1), ui.dp(57)));
        }
        LinearLayout instrument = ui.column();
        instrument.setPadding(first ? 0 : ui.dp(10), 0, ui.dp(5), 0);
        TextView label = ui.code(name, 10, true, colours.muted());
        instrument.addView(label);
        TextView value = ui.title("", numberSize, colours.ink());
        value.setMaxLines(1);
        value.setGravity(Gravity.BOTTOM | Gravity.START);
        value.setAutoSizeTextTypeUniformWithConfiguration(16, numberSize, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        instrument.addView(value, new LayoutParams(-1, ui.dp(46)));
        container.addView(instrument, new LayoutParams(0, -2, weight));
        return value;
    }

    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) {
            view.setText(value);
        }
    }

    private static String speed(double mps) {
        return Double.isFinite(mps) ? String.format(Locale.getDefault(), "%.1f", mps * 3.6) : "—";
    }
}
