package dev.zeroone.bikethere;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.util.function.BiConsumer;

final class PlaceMap extends SlippyMap {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BiConsumer<Double, Double> onSelection;
    private double lat = Double.NaN;
    private double lon = Double.NaN;
    private int radius;

    PlaceMap(Context context, RideColors colours, boolean tiles, int radius, BiConsumer<Double, Double> onSelection) {
        super(context, colours, tiles);
        this.radius = radius;
        this.onSelection = onSelection;
        setContentDescription("Map. Pan or pinch to navigate. Tap to set the centre of the saved place.");
    }

    void select(double latitude, double longitude, boolean centre) {
        lat = latitude;
        lon = longitude;
        if (centre) {
            setCamera(mapX(lon), mapY(lat), 16.5);
        }
        onSelection.accept(lat, lon);
        invalidate();
    }

    void setRadius(int radius) {
        this.radius = radius;
        if (Double.isFinite(lat) && getHeight() > 0) {
            float projectedRadius = circleRadius();
            double fit = Math.min(getWidth(), getHeight()) * .35;
            if (projectedRadius > fit) {
                setCamera(mapX(lon), mapY(lat), zoom - Math.log(projectedRadius / fit) / Math.log(2));
            }
        }
        invalidate();
    }

    @Override
    void onTap(float x, float y) {
        select(latitudeAt(y), longitudeAt(x), false);
    }

    @Override
    void drawContent(Canvas canvas) {
        if (!Double.isFinite(lat)) {
            return;
        }
        float x = screenX(mapX(lon));
        float y = screenY(mapY(lat));
        float radius = circleRadius();
        int mark = colours.accent();
        ring.setStyle(Paint.Style.FILL);
        ring.setColor((mark & 0x00ffffff) | 0x37000000);
        canvas.drawCircle(x, y, radius, ring);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * density);
        ring.setColor(mark);
        canvas.drawCircle(x, y, radius, ring);
        ring.setStyle(Paint.Style.FILL);
        ring.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 8 * density, ring);
        ring.setColor(mark);
        canvas.drawCircle(x, y, 5 * density, ring);
    }

    private float circleRadius() {
        double cosine = Math.cos(Math.toRadians(Math.max(-85, Math.min(85, lat))));
        return (float) (world() * radius / (40_030_174 * cosine));
    }
}
