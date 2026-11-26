package dev.zeroone.bikethere;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.LongConsumer;

@SuppressLint("ViewConstructor")
final class ProfileChart extends View {
    private final RideProfile profile;
    private final RideProfile.Metric metric;
    private final RideColors colours;
    private final int trace;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();
    private final float pad;
    private LongConsumer scrub;
    private double low = Double.POSITIVE_INFINITY;
    private double high = Double.NEGATIVE_INFINITY;
    private String lowLabel;
    private String highLabel;
    private long time;

    ProfileChart(Context context, RideProfile profile, RideProfile.Metric metric,
                 RideColors colours) {
        super(context);
        this.profile = profile;
        this.metric = metric;
        this.colours = colours;
        trace = metric == RideProfile.Metric.SPEED ? colours.accent()
                : colours.ink();
        pad = getResources().getDisplayMetrics().density;
        time = profile.start();
        setContentDescription(metric == RideProfile.Metric.SPEED ? "Speed profile" : "Elevation profile");
        for (int i = 0; i < profile.size(); i++) {
            double value = profile.value(metric, i);
            if (Double.isFinite(value)) {
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
        }
        if (hasData()) {
            low = metric == RideProfile.Metric.SPEED ? 0 : 5 * Math.floor((low - 2.5) / 5);
            high = 5 * Math.ceil((high + 2.5) / 5);
            lowLabel = Long.toString(Math.round(low));
            highLabel = Long.toString(Math.round(high));
        }
    }

    void setOnScrub(LongConsumer listener) {
        scrub = listener;
    }

    boolean hasData() {
        return Double.isFinite(low);
    }

    void seek(long time) {
        this.time = time;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        line.reset();
        area.reset();
        boolean open = false;
        float lastX = 0;
        for (int i = 0; i < profile.size(); i++) {
            double value = profile.value(metric, i);
            if (profile.segmentStart(i) || !Double.isFinite(value)) {
                if (open) {
                    area.lineTo(lastX, floor());
                    area.close();
                    open = false;
                }
            }
            if (Double.isFinite(value)) {
                float x = x(profile.time(i));
                float y = y(value);
                if (!open) {
                    line.moveTo(x, y);
                    area.moveTo(x, floor());
                    area.lineTo(x, y);
                } else {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
                open = true;
                lastX = x;
            }
        }
        if (open) {
            area.lineTo(lastX, floor());
            area.close();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasData()) {
            return;
        }
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(10 * pad);
        paint.setColor(colours.muted());
        canvas.drawText(highLabel, 0, 15 * pad, paint);
        canvas.drawText(lowLabel, 0, floor(), paint);
        paint.setColor(colours.line());
        paint.setStrokeWidth(pad);
        for (int i = 0; i < 3; i++) {
            float y = 12 * pad + i * (floor() - 12 * pad) / 2;
            canvas.drawLine(33 * pad, y, getWidth() - pad * 6, y, paint);
        }
        paint.setColor((trace & 0x00ffffff) | 0x24000000);
        canvas.drawPath(area, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2.2f * pad);
        paint.setColor(trace);
        canvas.drawPath(line, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colours.muted());
        paint.setStrokeWidth(pad);
        float x = x(time);
        canvas.drawLine(x, 8 * pad, x, floor() + 4 * pad, paint);
        double value = profile.valueAt(metric, time);
        if (Double.isFinite(value)) {
            paint.setColor(colours.paper());
            canvas.drawCircle(x, y(value), 5.5f * pad, paint);
            paint.setColor(trace);
            canvas.drawCircle(x, y(value), 3.5f * pad, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (scrub == null || !hasData()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> getParent().requestDisallowInterceptTouchEvent(false);
            default -> { }
        }
        if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            double fraction = Math.max(0, Math.min(1, (event.getX() - 33 * pad) / graphWidth()));
            scrub.accept(profile.start() + Math.round(fraction * (profile.end() - profile.start())));
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private float graphWidth() {
        return Math.max(1, getWidth() - 39 * pad);
    }

    private float x(long time) {
        return 33 * pad + graphWidth() * (time - profile.start()) / Math.max(1L, profile.end() - profile.start());
    }

    private float y(double value) {
        return (float) (floor() - (floor() - 12 * pad) * (value - low) / (high - low));
    }

    private float floor() {
        return getHeight() - 10 * pad;
    }
}
