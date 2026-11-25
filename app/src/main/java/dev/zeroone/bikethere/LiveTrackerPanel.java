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
    private final TextView status;
    private final TextView satellites;
    private final View locate;
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

        FrameLayout hero = new FrameLayout(context);
        hero.setBackground(ui.shape(colours.panel(), 20, colours.line()));
        hero.setClipToOutline(true);
        map = new LiveMap(context, colours, showMaps);
        hero.addView(map, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout signal = ui.row();
        signal.setPadding(ui.dp(10), ui.dp(7), ui.dp(12), ui.dp(7));
        signal.setBackground(ui.shape(RideColors.TRACK, 12, null));
        searching = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        searching.setIndeterminateTintList(ColorStateList.valueOf(RideColors.SIGNAL));
        searching.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams spin = new LayoutParams(ui.dp(18), ui.dp(18));
        spin.rightMargin = ui.dp(8);
        signal.addView(searching, spin);
        LinearLayout notes = ui.column();
        status = ui.text("", 14, true, RideColors.ON_TRACK);
        satellites = ui.text("", 12, false, RideColors.ON_TRACK);
        satellites.setPadding(0, ui.dp(3), 0, 0);
        notes.addView(status);
        notes.addView(satellites);
        signal.addView(notes);
        FrameLayout.LayoutParams signalSize = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        signalSize.setMargins(ui.dp(10), ui.dp(10), ui.dp(10), 0);
        hero.addView(signal, signalSize);

        locate = ui.button("Find my location", RideColors.TRACK, RideColors.ON_TRACK, null, findMe);
        hero.addView(locate, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        recenter = ui.button("Centre map", RideColors.TRACK, RideColors.ON_TRACK, null, map::recenter);
        FrameLayout.LayoutParams centreSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        centreSize.rightMargin = ui.dp(9);
        centreSize.bottomMargin = ui.dp(21);
        hero.addView(recenter, centreSize);
        approximate = ui.text("Approximate location", 12, true, RideColors.ON_TRACK);
        approximate.setBackground(ui.shape(RideColors.TRACK, 10, null));
        approximate.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        FrameLayout.LayoutParams approximateSize = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        approximateSize.leftMargin = ui.dp(9);
        approximateSize.bottomMargin = ui.dp(21);
        hero.addView(approximate, approximateSize);
        addView(hero, new LayoutParams(-1, ui.dp(296)));

        LinearLayout instruments = ui.row();
        instruments.setBaselineAligned(false);
        instruments.setPadding(ui.dp(6), ui.dp(14), ui.dp(6), ui.dp(13));
        elapsed = addInstrument(instruments, "ELAPSED");
        average = addInstrument(instruments, "AVG KM/H");
        maximum = addInstrument(instruments, "MAX KM/H");
        addView(instruments);
        addView(ui.hairline());

        help = ui.text("", 14, false, colours.muted());
        help.setPadding(0, ui.dp(11), 0, ui.dp(9));
        addView(help);
        actions = ui.row();
        addView(actions);
    }

    void showArea(Location rough) {
        map.area(rough);
    }

    void update(TrackerService.Live live, long now) {
        Tracker.State state = live.state();
        GpsStatus gps = live.gps();
        boolean active = state != Tracker.State.DISARMED;
        boolean hasFix = gps.hasFix(now);
        boolean looking = active && gps.enabled() && !hasFix;
        map.update(live.fix(), hasFix, live.route());
        locate.setVisibility(!map.hasPosition() && !active ? VISIBLE : GONE);
        recenter.setVisibility(map.hasPosition() ? VISIBLE : GONE);
        approximate.setVisibility(map.showsApproximatePosition() ? VISIBLE : GONE);
        searching.setVisibility(looking ? VISIBLE : GONE);
        setText(status, gps.label(state, now, live.manualPending()));
        satellites.setVisibility(active && gps.enabled() ? VISIBLE : GONE);
        if (active && gps.enabled()) {
            setText(satellites, gps.seen() >= 0 ? String.format(Locale.getDefault(),
                    "%d satellites seen · %d used", gps.seen(), gps.used()) : "Waiting for satellite data");
        }
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
            return "Auto-start waits for cycling. Manual start begins as soon as GPS is ready.";
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
                actions.addView(ui.primary("Auto-start", () -> dispatch.accept(TrackerService.ARM)));
                addSecondary("Manual start", TrackerService.START);
            }
            case READY -> {
                if (manualPending) {
                    actions.addView(ui.secondary("Disarm", () -> dispatch.accept(TrackerService.DISARM)));
                } else {
                    actions.addView(ui.primary("Manual start", () -> dispatch.accept(TrackerService.START)));
                    addSecondary("Disarm", TrackerService.DISARM);
                }
            }
            case RIDING, PAUSED -> {
                actions.addView(ui.primary("Finish ride", () -> dispatch.accept(TrackerService.FINISH)));
                addSecondary("Finish & disarm", TrackerService.DISARM);
            }
        }
    }

    private void addSecondary(String title, String action) {
        LayoutParams size = new LayoutParams(-2, -2);
        size.leftMargin = ui.dp(7);
        actions.addView(ui.secondary(title, () -> dispatch.accept(action)), size);
    }

    private TextView addInstrument(LinearLayout container, String name) {
        LinearLayout instrument = ui.column();
        TextView label = ui.text(name, 11, true, colours.muted());
        label.setLetterSpacing(.07f);
        instrument.addView(label);
        TextView value = ui.title("", 29, colours.ink());
        value.setPadding(0, ui.dp(3), 0, 0);
        instrument.addView(value);
        container.addView(instrument, RideUi.weight());
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
