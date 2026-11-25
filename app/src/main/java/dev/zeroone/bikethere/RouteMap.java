package dev.zeroone.bikethere;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AtomicFile;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class RouteMap extends View {
    private record Location(double x, double y) {}
    private record Tile(int z, int x, int y, int screenX, int screenY) {}
    private record Image(Tile tile, Bitmap bitmap) {}

    private static final int TILE_PX = 256;
    private static final long FALLBACK_EXPIRY = TimeUnit.DAYS.toMillis(7);
    private static final long RETENTION = TimeUnit.DAYS.toMillis(60);
    private static final ExecutorService MAP_REQUESTS = Executors.newFixedThreadPool(3);
    private static final String COPYRIGHT = "© OpenStreetMap contributors";
    private final List<Ride.Point> route;
    private final List<Location> projected;
    private final List<Image> tiles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF tileBounds = new RectF();
    private final boolean maps;
    private final float density;
    private double originX;
    private double originY;
    private double world;
    private long time;
    private int generation;

    RouteMap(Context context, List<Ride.Point> route, boolean maps) {
        super(context);
        this.route = route;
        this.maps = maps;
        density = context.getResources().getDisplayMetrics().density;
        time = route.get(0).time();
        setContentDescription("Recorded ride route");
        projected = new ArrayList<>(route.size());
        double startX = (route.get(0).lon() + 180) / 360;
        for (Ride.Point point : route) {
            double lat = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, point.lat())));
            double x = (point.lon() + 180) / 360;
            x -= Math.rint(x - startX);
            double y = (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2;
            projected.add(new Location(x, y));
        }
    }

    void seek(long time) {
        this.time = time;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width < 1 || height < 1) {
            return;
        }
        double left = projected.get(0).x();
        double top = projected.get(0).y();
        double right = left;
        double bottom = top;
        for (Location point : projected) {
            left = Math.min(left, point.x());
            right = Math.max(right, point.x());
            top = Math.min(top, point.y());
            bottom = Math.max(bottom, point.y());
        }
        double padding = 26 * density;
        double tileSize = TILE_PX * density;
        double scale = Math.min(Math.max(1, width - 2 * padding) / Math.max(1e-9, right - left),
                Math.max(1, height - 2 * padding) / Math.max(1e-9, bottom - top));
        int zoom = Math.max(0, Math.min(18, (int) Math.floor(Math.log(scale / tileSize) / Math.log(2))));
        world = (1 << zoom) * tileSize;
        originX = (left + right) * world / 2 - width / 2.0;
        originY = (top + bottom) * world / 2 - height / 2.0;
        path.reset();
        for (int i = 0; i < projected.size(); i++) {
            Location point = projected.get(i);
            if (route.get(i).segmentStart() || i == 0) {
                path.moveTo(screenX(point), screenY(point));
            } else {
                path.lineTo(screenX(point), screenY(point));
            }
        }
        tiles.clear();
        int request = ++generation;
        if (maps) {
            loadVisibleTiles(width, height, zoom, tileSize, request);
        }
    }

    private void loadVisibleTiles(int width, int height, int zoom, double tileSize, int request) {
        int rowCount = 1 << zoom;
        int firstX = (int) Math.floor(originX / tileSize);
        int lastX = (int) Math.floor((originX + width - 1) / tileSize);
        int firstY = Math.max(0, (int) Math.floor(originY / tileSize));
        int lastY = Math.min(rowCount - 1, (int) Math.floor((originY + height - 1) / tileSize));
        File cache = new File(getContext().getCacheDir(), "osm");
        for (int x = firstX; x <= lastX; x++) {
            for (int y = firstY; y <= lastY; y++) {
                Tile tile = new Tile(zoom, Math.floorMod(x, rowCount), y,
                        (int) Math.round(x * tileSize - originX), (int) Math.round(y * tileSize - originY));
                MAP_REQUESTS.execute(() -> {
                    Bitmap bitmap = loadTile(tile, cache);
                    if (bitmap != null) {
                        post(() -> {
                            if (generation == request && isAttachedToWindow()) {
                                tiles.add(new Image(tile, bitmap));
                                invalidate();
                            }
                        });
                    }
                });
            }
        }
    }

    private static Bitmap loadTile(Tile tile, File cache) {
        cache.mkdirs();
        long now = System.currentTimeMillis();
        File file = new File(cache, tile.z() + "-" + tile.x() + "-" + tile.y() + ".png");
        Bitmap cached = BitmapFactory.decodeFile(file.getPath());
        if (cached != null && file.lastModified() > now) {
            return cached;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://tile.openstreetmap.org/"
                    + tile.z() + "/" + tile.x() + "/" + tile.y() + ".png").openConnection();
            connection.setRequestProperty("User-Agent", "BikeThere/0.1 (+https://github.com/ZeroOne3010/bike-there)");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return cached;
            }
            int size = connection.getContentLength();
            if (size > 1_000_000) {
                return cached;
            }
            Bitmap image;
            try (InputStream stream = connection.getInputStream()) {
                image = BitmapFactory.decodeStream(stream);
            }
            if (image != null) {
                storeTile(image, file, connection, now);
                File[] expired = cache.listFiles(entry -> entry.getName().endsWith(".png")
                        && entry.lastModified() < now - RETENTION);
                if (expired != null) {
                    for (File old : expired) {
                        old.delete();
                    }
                }
                return image;
            }
        } catch (java.io.IOException ignored) {
            // A cached tile or the route line remains available offline.
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return cached;
    }

    private static synchronized void storeTile(Bitmap image, File file, HttpURLConnection connection, long now)
            throws java.io.IOException {
        AtomicFile destination = new AtomicFile(file);
        FileOutputStream out = destination.startWrite();
        try {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new java.io.IOException("Could not encode tile");
            }
            destination.finishWrite(out);
        } catch (java.io.IOException e) {
            destination.failWrite(out);
            throw e;
        }
        long expiry = connection.getExpiration();
        String controls = connection.getHeaderField("Cache-Control");
        if (controls != null) {
            for (String control : controls.split(",")) {
                String directive = control.trim();
                if (directive.startsWith("max-age=")) {
                    try {
                        expiry = now + TimeUnit.SECONDS.toMillis(Long.parseLong(directive.substring(8)));
                    } catch (NumberFormatException ignored) {
                        // Fall back to the Expires header or the default lifetime.
                    }
                }
            }
        }
        file.setLastModified(expiry == 0 ? now + FALLBACK_EXPIRY : expiry);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xffe7eee5);
        float size = TILE_PX * density;
        for (Image image : tiles) {
            Tile tile = image.tile();
            tileBounds.set(tile.screenX(), tile.screenY(),
                    tile.screenX() + size + 1, tile.screenY() + size + 1);
            canvas.drawBitmap(image.bitmap(), null, tileBounds, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(8 * density);
        canvas.drawPath(path, paint);
        paint.setColor(MainActivity.LEAF);
        paint.setStrokeWidth(5 * density);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        Location marker = pointAtTime();
        canvas.drawCircle(screenX(marker), screenY(marker), 8 * density, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(screenX(marker), screenY(marker), 3 * density, paint);
        if (maps) {
            paint.setTextSize(10 * density);
            float width = paint.measureText(COPYRIGHT);
            canvas.drawRect(getWidth() - width - 8 * density, getHeight() - 16 * density,
                    getWidth(), getHeight(), paint);
            paint.setColor(MainActivity.INK);
            canvas.drawText(COPYRIGHT, getWidth() - width - 4 * density,
                    getHeight() - 4 * density, paint);
        }
    }

    private Location pointAtTime() {
        int lo = 0;
        int hi = route.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (route.get(mid).time() <= time) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        Location a = projected.get(lo);
        if (lo == projected.size() - 1 || route.get(lo + 1).segmentStart()) {
            return a;
        }
        Location b = projected.get(lo + 1);
        double fraction = (double) (time - route.get(lo).time())
                / (route.get(lo + 1).time() - route.get(lo).time());
        return new Location(a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y()));
    }

    private float screenX(Location location) {
        return (float) (location.x() * world - originX);
    }

    private float screenY(Location location) {
        return (float) (location.y() * world - originY);
    }
}
