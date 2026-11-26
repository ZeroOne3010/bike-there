package dev.zeroone.bikethere;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class SlippyMap extends View {
    static final double MIN_ZOOM = 0;
    static final double MAX_ZOOM = 19;
    private static final String COPYRIGHT = "© OpenStreetMap contributors";
    private static final ExecutorService REQUESTS = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };
    private final RectF rect = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ColorMatrixColorFilter nightTiles = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -.20f, -.33f, -.07f, 0, 187,
            -.20f, -.33f, -.07f, 0, 187,
            -.20f, -.33f, -.07f, 0, 178,
            0, 0, 0, 1, 0
    }));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> failures = new HashMap<>();
    private final File cache;
    private final boolean tilesEnabled;
    private boolean showTiles = true;
    private int userCameraChanges;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scale;
    private boolean moving;
    final float density;
    final RideColors colours;
    double centreX = .5, centreY = .5, zoom = MIN_ZOOM;

    SlippyMap(Context context, RideColors colours, boolean tilesEnabled) {
        super(context);
        this.colours = colours;
        this.tilesEnabled = tilesEnabled;
        density = getResources().getDisplayMetrics().density;
        cache = new File(context.getCacheDir(), "osm");
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onScroll(MotionEvent start, MotionEvent event, float dx, float dy) {
                if (!scale.isInProgress() && event.getPointerCount() == 1) {
                    userCameraChanges++;
                    setCamera(centreX + dx / world(), centreY + dy / world(), zoom);
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                onTap(event.getX(), event.getY());
                performClick();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                zoomAt(1, event.getX(), event.getY());
                return true;
            }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector event) {
                zoomAt(Math.log(event.getScaleFactor()) / Math.log(2), event.getFocusX(), event.getFocusY());
                return true;
            }
        });
        scale.setQuickScaleEnabled(false);
    }

    void setCamera(double x, double y, double zoom) {
        centreX = x - Math.floor(x);
        centreY = Math.max(0, Math.min(1, y));
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        onCameraChanged();
        invalidate();
    }

    void zoomBy(double amount) {
        zoomAt(amount, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoomAt(double amount, float x, float y) {
        userCameraChanges++;
        double target = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + amount));
        double delta = (1 - Math.pow(2, zoom - target)) / world();
        setCamera(centreX + (x - getWidth() / 2.0) * delta, centreY + (y - getHeight() / 2.0) * delta, target);
    }

    int userCameraChanges() {
        return userCameraChanges;
    }

    void showTiles(boolean show) {
        if (showTiles != show) {
            showTiles = show;
            invalidate();
        }
    }

    void onCameraChanged() {}
    void onTap(float x, float y) {}
    void drawContent(Canvas canvas) {}

    double world() {
        return worldAt(zoom);
    }

    double worldAt(double zoom) {
        return 256 * density * Math.pow(2, zoom);
    }

    float screenX(double x) {
        double delta = x - centreX;
        return (float) ((delta - Math.rint(delta)) * world() + getWidth() / 2.0);
    }

    float screenY(double y) {
        return (float) ((y - centreY) * world() + getHeight() / 2.0);
    }

    double longitudeAt(float x) {
        double unit = (x - getWidth() / 2.0) / world() + centreX;
        return (unit - Math.floor(unit)) * 360 - 180;
    }

    double latitudeAt(float y) {
        double unit = (y - getHeight() / 2.0) / world() + centreY;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * Math.max(0, Math.min(1, unit))))));
    }

    static double mapX(double longitude) {
        return (longitude + 180) / 360;
    }

    static double mapY(double latitude) {
        double radians = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, latitude)));
        return (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            moving = true;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scale.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            moving = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(colours.panel());
        drawGrid(canvas);
        if (tilesEnabled && showTiles) {
            drawTiles(canvas);
        }
        drawContent(canvas);
        if (tilesEnabled && showTiles) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(10 * density);
            float width = paint.measureText(COPYRIGHT);
            paint.setColor(colours.panel());
            canvas.drawRect(getWidth() - width - 9 * density, getHeight() - 17 * density,
                    getWidth(), getHeight(), paint);
            paint.setColor(colours.ink());
            canvas.drawText(COPYRIGHT, getWidth() - width - 4 * density, getHeight() - 5 * density, paint);
        }
    }

    private void drawGrid(Canvas canvas) {
        paint.setColor(colours.line());
        paint.setStrokeWidth(density);
        float step = 32 * density;
        for (float x = (float) ((-centreX * world()) % step); x < getWidth(); x += step) {
            canvas.drawLine(x, 0, x, getHeight(), paint);
        }
        for (float y = (float) ((-centreY * world()) % step); y < getHeight(); y += step) {
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
    }

    private void drawTiles(Canvas canvas) {
        int tileZoom = (int) Math.round(zoom);
        int count = 1 << tileZoom;
        double world = world();
        double tileSize = world / count;
        double originX = centreX * world - getWidth() / 2.0;
        double originY = centreY * world - getHeight() / 2.0;
        int firstX = (int) Math.floor(originX / tileSize);
        int lastX = (int) Math.floor((originX + getWidth() - 1) / tileSize);
        int firstY = Math.max(0, (int) Math.floor(originY / tileSize));
        int lastY = Math.min(count - 1, (int) Math.floor((originY + getHeight() - 1) / tileSize));
        paint.setColor(Color.WHITE);
        paint.setColorFilter(colours.dark() ? nightTiles : null);
        for (int x = firstX; x <= lastX; x++) {
            for (int y = firstY; y <= lastY; y++) {
                int tileX = Math.floorMod(x, count);
                String key = tileZoom + "/" + tileX + "/" + y;
                Bitmap bitmap = MEMORY.get(key);
                if (bitmap == null) {
                    if (!moving) {
                        fetch(key, tileZoom, tileX, y);
                    }
                } else {
                    float left = (float) (x * tileSize - originX);
                    float top = (float) (y * tileSize - originY);
                    rect.set(left, top, left + (float) tileSize + 1, top + (float) tileSize + 1);
                    canvas.drawBitmap(bitmap, null, rect, paint);
                }
            }
        }
        paint.setColorFilter(null);
    }

    private void fetch(String key, int zoom, int x, int y) {
        Long retryAt = failures.get(key);
        if ((retryAt != null && SystemClock.uptimeMillis() < retryAt) || !pending.add(key)) {
            return;
        }
        REQUESTS.execute(() -> {
            Bitmap tile = MapTiles.load(zoom, x, y, cache);
            if (tile != null) {
                MEMORY.put(key, tile);
            }
            main.post(() -> {
                pending.remove(key);
                if (tile == null) {
                    failures.put(key, SystemClock.uptimeMillis() + 30_000);
                }
                if (isAttachedToWindow()) {
                    invalidate();
                }
            });
        });
    }
}
