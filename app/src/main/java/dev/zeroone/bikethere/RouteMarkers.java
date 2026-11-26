package dev.zeroone.bikethere;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

final class RouteMarkers {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private final float density;

    RouteMarkers(float density) {
        this.density = density;
    }

    void drawStart(Canvas canvas, float x, float y) {
        draw(canvas, x, y, x, y - 17 * density, true);
    }

    void drawEnds(Canvas canvas, float startX, float startY, float endX, float endY) {
        boolean close = Math.hypot(startX - endX, startY - endY) < 29 * density;
        float offset = close ? (startX <= endX ? 15 : -15) * density : 0;
        draw(canvas, startX, startY, startX - offset, startY - 17 * density, true);
        draw(canvas, endX, endY, endX + offset, endY - 17 * density, false);
    }

    private void draw(Canvas canvas, float x, float y, float badgeX, float badgeY, boolean start) {
        float radius = 11 * density;
        paint.setColor(RideColors.TRACK);
        paint.setStrokeWidth(4 * density);
        canvas.drawLine(x, y, badgeX, badgeY, paint);
        paint.setColor(RideColors.ON_TRACK);
        paint.setStrokeWidth(1.5f * density);
        canvas.drawLine(x, y, badgeX, badgeY, paint);
        canvas.drawCircle(x, y, 3 * density, paint);

        canvas.drawRoundRect(badgeX - radius, badgeY - radius, badgeX + radius, badgeY + radius,
                2 * density, 2 * density, paint);
        paint.setColor(start ? RideColors.SIGNAL : RideColors.TRACK);
        canvas.drawRoundRect(badgeX - radius + density, badgeY - radius + density,
                badgeX + radius - density, badgeY + radius - density, density, density, paint);
        if (start) {
            paint.setColor(RideColors.TRACK);
            triangle.reset();
            triangle.moveTo(badgeX - 3.5f * density, badgeY - 5.5f * density);
            triangle.lineTo(badgeX + 5 * density, badgeY);
            triangle.lineTo(badgeX - 3.5f * density, badgeY + 5.5f * density);
            triangle.close();
            canvas.drawPath(triangle, paint);
        } else {
            drawFlag(canvas, badgeX, badgeY);
        }
    }

    private void drawFlag(Canvas canvas, float x, float y) {
        paint.setColor(RideColors.ON_TRACK);
        float square = 2.5f * density;
        float left = x - 3 * density;
        float top = y - 5 * density;
        canvas.drawRect(left - density, top, left, y + 6 * density, paint);
        canvas.drawRect(left, top, left + 4 * square, top + 3 * square, paint);
        paint.setColor(RideColors.TRACK);
        for (int row = 0; row < 3; row++) {
            for (int col = row % 2; col < 4; col += 2) {
                float l = left + col * square;
                float t = top + row * square;
                canvas.drawRect(l, t, l + square, t + square, paint);
            }
        }
    }
}
