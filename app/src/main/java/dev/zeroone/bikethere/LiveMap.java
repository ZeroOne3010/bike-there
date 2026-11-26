package dev.zeroone.bikethere;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.location.Location;

import java.util.Collections;
import java.util.List;

@SuppressLint("ViewConstructor")
final class LiveMap extends SlippyMap {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RouteMarkers markers;
    private final Path track = new Path();
    private List<Ride.Point> route = Collections.emptyList();
    private Location fix;
    private Location initialArea;
    private boolean approximate;
    private boolean hasView;
    private int followingInteractions;

    LiveMap(Context context, RideColors colours, boolean tiles) {
        super(context, colours, tiles);
        markers = new RouteMarkers(density);
        showTiles(false);
        setContentDescription("Live ride map. Pan or pinch to explore.");
    }

    boolean hasPosition() {
        return fix != null || initialArea != null;
    }

    boolean showsApproximatePosition() {
        return fix != null && approximate;
    }

    void area(Location location) {
        if (location == null) {
            return;
        }
        initialArea = location;
        if (fix == null) {
            position(location, true, false);
        }
    }

    void update(Location latest, boolean exact, List<Ride.Point> points) {
        if (route != points) {
            route = points;
            rebuildTrack();
            invalidate();
        }
        if (latest != null) {
            position(latest, !exact, false);
        } else if (initialArea != null) {
            position(initialArea, true, false);
        }
    }

    void recenter() {
        followingInteractions = userCameraChanges();
        if (fix != null) {
            position(fix, approximate, true);
        }
    }

    private void position(Location location, boolean crude, boolean force) {
        boolean changed = fix != location || approximate != crude;
        boolean levelChanged = hasView && approximate != crude;
        fix = location;
        approximate = crude;
        if (changed || force) {
            showTiles(true);
            if (force || !hasView || userCameraChanges() == followingInteractions) {
                double desired = force || !hasView || levelChanged
                        ? crude ? MapLocation.cityZoom(location) : 16.5
                        : zoom;
                setCamera(mapX(location.getLongitude()), mapY(location.getLatitude()), desired);
            } else {
                invalidate();
            }
            hasView = true;
        }
    }

    @Override void onCameraChanged() {
        rebuildTrack();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rebuildTrack();
    }

    private void rebuildTrack() {
        track.reset();
        float previous = 0;
        for (int i = 0; i < route.size(); i++) {
            Ride.Point point = route.get(i);
            float x = screenX(mapX(point.lon()));
            float y = screenY(mapY(point.lat()));
            if (i == 0 || point.segmentStart() || Math.abs(x - previous) > world() / 2) {
                track.moveTo(x, y);
            } else {
                track.lineTo(x, y);
            }
            previous = x;
        }
    }

    @Override void drawContent(Canvas canvas) {
        int accent = colours.dark() ? RideColors.SIGNAL : colours.accent();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(8 * density);
        paint.setColor(colours.panel());
        canvas.drawPath(track, paint);
        paint.setStrokeWidth(4 * density);
        paint.setColor(accent);
        canvas.drawPath(track, paint);

        if (fix == null) {
            drawStart(canvas);
            return;
        }
        float x = screenX(mapX(fix.getLongitude()));
        float y = screenY(mapY(fix.getLatitude()));
        paint.setStyle(Paint.Style.FILL);
        if (approximate) {
            double scale = world() / (40_030_174 * Math.max(.08, Math.cos(Math.toRadians(fix.getLatitude()))));
            float radius = (float) Math.min(160 * density, Math.max(16 * density, fix.getAccuracy() * scale));
            paint.setColor((accent & 0x00ffffff) | 0x44000000);
            canvas.drawCircle(x, y, radius, paint);
        }
        paint.setColor(colours.dark() ? RideColors.TRACK : Color.WHITE);
        canvas.drawCircle(x, y, 9 * density, paint);
        paint.setColor(accent);
        canvas.drawCircle(x, y, 5.5f * density, paint);
        drawStart(canvas);
    }

    private void drawStart(Canvas canvas) {
        if (!route.isEmpty()) {
            Ride.Point start = route.get(0);
            markers.drawStart(canvas, screenX(mapX(start.lon())), screenY(mapY(start.lat())));
        }
    }
}
