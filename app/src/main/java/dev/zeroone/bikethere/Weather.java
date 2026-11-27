package dev.zeroone.bikethere;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Weather {
    record Conditions(double temperature, double wind, int direction) {
        String label(Units units) {
            String[] bearings = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            String bearing = bearings[Math.floorMod((int) Math.round(direction / 45.0), 8)];
            return units.weather(temperature, wind, bearing);
        }
    }

    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();

    static void load(Context context, String id, Ride.Point point, Runnable callback) {
        Context app = context.getApplicationContext();
        REQUESTS.execute(() -> {
            try {
                Conditions conditions = fetch(point);
                if (conditions != null) {
                    try (RideStore store = new RideStore(app)) {
                        store.saveWeather(id, conditions);
                    }
                }
            } catch (Exception e) {
                Log.w("BikeThere", "Weather lookup failed (" + e.getClass().getSimpleName() + ")");
            } finally {
                callback.run();
            }
        });
    }

    private static Conditions fetch(Ride.Point point) throws Exception {
        Instant time = Instant.ofEpochMilli(point.time());
        String date = time.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        String fields = "temperature_2m,wind_speed_10m,wind_direction_10m";
        // Weather resolution is coarser than GPS. Send only the starting area.
        String address = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                        + "&hourly=%s&wind_speed_unit=ms&timezone=GMT&timeformat=unixtime&start_date=%s&end_date=%s",
                point.lat(), point.lon(), fields, date, date);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = input.readLine()) != null) {
                body.append(line);
            }
            JSONObject hourly = new JSONObject(body.toString()).getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            long hour = time.truncatedTo(ChronoUnit.HOURS).getEpochSecond();
            for (int i = 0; i < times.length(); i++) {
                if (times.getLong(i) == hour) {
                    return new Conditions(hourly.getJSONArray("temperature_2m").getDouble(i),
                            hourly.getJSONArray("wind_speed_10m").getDouble(i),
                            hourly.getJSONArray("wind_direction_10m").getInt(i));
                }
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }
}
