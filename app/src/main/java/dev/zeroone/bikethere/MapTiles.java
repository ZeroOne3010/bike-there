package dev.zeroone.bikethere;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

final class MapTiles {
    private static final long FALLBACK_EXPIRY = TimeUnit.DAYS.toMillis(7);
    private static final long RETENTION = TimeUnit.DAYS.toMillis(60);

    static Bitmap load(int zoom, int x, int y, File cache) {
        cache.mkdirs();
        long now = System.currentTimeMillis();
        File file = new File(cache, zoom + "-" + x + "-" + y + ".png");
        Bitmap cached = BitmapFactory.decodeFile(file.getPath());
        if (cached != null && file.lastModified() > now) {
            return cached;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://tile.openstreetmap.org/"
                    + zoom + "/" + x + "/" + y + ".png").openConnection();
            connection.setRequestProperty("User-Agent", "BikeThere/0.2 (+https://github.com/ZeroOne3010/bike-there)");
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

}
