package dev.zeroone.bikethere;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.List;

final class RouteMap extends SlippyMap {
    private final List<Ride.Point> route;
    private final double[] x, y;
    private final Path routeLine = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long time;
    private double left, right, top, bottom;
    private boolean fitted;

    RouteMap(Context context, List<Ride.Point> route, boolean maps, RideColors colours) {
        super(context, colours, maps);
        this.route = route;
        time = route.get(0).time();
        x = new double[route.size()];
        y = new double[route.size()];
        double startX = mapX(route.get(0).lon());
        left = right = startX;
        top = bottom = mapY(route.get(0).lat());
        for (int i = 0; i < route.size(); i++) {
            double normalX = mapX(route.get(i).lon());
            x[i] = normalX - Math.rint(normalX - startX);
            y[i] = mapY(route.get(i).lat());
            left = Math.min(left, x[i]);
            right = Math.max(right, x[i]);
            top = Math.min(top, y[i]);
            bottom = Math.max(bottom, y[i]);
        }
        setContentDescription("Ride map. Pan or pinch to explore the recorded route.");
    }

    void seek(long time) {
        this.time = time;
        invalidate();
    }

    void fit() {
        double padding = 28 * density;
        double world = Math.min(Math.max(1, getWidth() - 2 * padding) / Math.max(1e-9, right - left),
                Math.max(1, getHeight() - 2 * padding) / Math.max(1e-9, bottom - top));
        double position = Math.min(18, Math.log(world / worldAt(0)) / Math.log(2));
        setCamera((left + right) / 2, (top + bottom) / 2, position);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width > 0 && height > 0 && !fitted) {
            fitted = true;
            fit();
        } else {
            onCameraChanged();
        }
    }

    @Override
    void onCameraChanged() {
        routeLine.reset();
        float previousX = 0;
        for (int i = 0; i < route.size(); i++) {
            float px = screenX(x[i]);
            float py = screenY(y[i]);
            if (i == 0 || route.get(i).segmentStart() || Math.abs(px - previousX) > world() / 2) {
                routeLine.moveTo(px, py);
            } else {
                routeLine.lineTo(px, py);
            }
            previousX = px;
        }
    }

    @Override
    void drawContent(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(8 * density);
        paint.setColor(colours.dark() ? RideColors.TRACK : Color.WHITE);
        canvas.drawPath(routeLine, paint);
        paint.setColor(colours.accent());
        paint.setStrokeWidth(4.5f * density);
        canvas.drawPath(routeLine, paint);

        int index = RideProfile.floorIndex(route, time);
        double longitude = x[index];
        double latitude = y[index];
        if (index + 1 < route.size() && !route.get(index + 1).segmentStart()) {
            double progress = (double) (time - route.get(index).time())
                    / (route.get(index + 1).time() - route.get(index).time());
            longitude += (x[index + 1] - longitude) * progress;
            latitude += (y[index + 1] - latitude) * progress;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colours.dark() ? RideColors.TRACK : Color.WHITE);
        canvas.drawCircle(screenX(longitude), screenY(latitude), 9 * density, paint);
        paint.setColor(colours.accent());
        canvas.drawCircle(screenX(longitude), screenY(latitude), 6 * density, paint);
    }
}
