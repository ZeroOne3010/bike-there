package dev.zeroone.bikethere;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class TrackBackdrop extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final Path route = new Path();
    private int alpha = 255;

    TrackBackdrop() {
        route.moveTo(302, -16);
        route.cubicTo(305, 53, 375, 50, 338, 90);
        route.cubicTo(320, 112, 293, 84, 276, 111);
        route.cubicTo(262, 133, 314, 139, 303, 163);
        route.cubicTo(292, 188, 251, 186, 251, 225);
        route.cubicTo(253, 249, 278, 264, 306, 268);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / 360f, bounds.height() / 274f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.8f);
        for (int i = 0; i < 16; i++) {
            float delta = 10 * i;
            colour(i % 4 == 0 ? 0xff675885 : 0xff453957);
            contour.reset();
            contour.moveTo(140 + delta, -30);
            contour.cubicTo(107 + delta, 43, 237 + delta, 32, 228 + delta, 92);
            contour.cubicTo(218 + delta, 156, 144 + delta, 164, 160 + delta, 216);
            contour.cubicTo(175 + delta, 264, 226 + delta, 244, 206 + delta, 302);
            canvas.drawPath(contour, paint);
        }
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(9);
        colour(RideColors.TRACK);
        canvas.drawPath(route, paint);
        colour(0xffb198e7);
        paint.setStrokeWidth(3.6f);
        canvas.drawPath(route, paint);
        paint.setStyle(Paint.Style.FILL);
        colour(RideColors.TRACK);
        canvas.drawCircle(276, 111, 9, paint);
        colour(RideColors.SIGNAL);
        canvas.drawCircle(276, 111, 5.5f, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private void colour(int colour) {
        paint.setColor(colour);
        paint.setAlpha(alpha);
    }
}
