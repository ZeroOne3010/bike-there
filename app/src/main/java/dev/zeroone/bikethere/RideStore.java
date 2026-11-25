package dev.zeroone.bikethere;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class RideStore extends SQLiteOpenHelper {
    record Summary(String id, String title, String zone, long start, long elapsed, long moving,
                   double distance, Weather.Conditions weather) {}

    RideStore(Context context) {
        super(context.getApplicationContext(), "rides.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE places (_id INTEGER PRIMARY KEY, name TEXT NOT NULL, "
                + "lat REAL NOT NULL, lon REAL NOT NULL, radius INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE rides (id TEXT PRIMARY KEY, title TEXT NOT NULL, zone TEXT NOT NULL, "
                + "start INTEGER NOT NULL, elapsed INTEGER NOT NULL, moving INTEGER NOT NULL, "
                + "distance REAL NOT NULL, draft INTEGER NOT NULL, temperature REAL, wind REAL, direction INTEGER)");
        db.execSQL("CREATE TABLE points (ride_id TEXT NOT NULL REFERENCES rides(id) ON DELETE CASCADE, "
                + "position INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, time INTEGER NOT NULL, "
                + "segment INTEGER NOT NULL, elevation REAL, speed REAL, PRIMARY KEY (ride_id, position)) WITHOUT ROWID");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from == 1 && to == 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN elevation REAL");
            db.execSQL("ALTER TABLE points ADD COLUMN speed REAL");
        } else {
            throw new IllegalStateException("Missing migration from " + from + " to " + to);
        }
    }

    List<Tracker.Place> places() {
        List<Tracker.Place> places = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT _id, name, lat, lon, radius FROM places ORDER BY name COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) {
                places.add(new Tracker.Place(cursor.getLong(0), cursor.getString(1),
                        cursor.getDouble(2), cursor.getDouble(3), cursor.getInt(4)));
            }
        }
        return places;
    }

    void savePlace(long id, String name, double lat, double lon, int radius) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("lat", lat);
        values.put("lon", lon);
        values.put("radius", radius);
        SQLiteDatabase db = getWritableDatabase();
        if (id == 0) {
            db.insertOrThrow("places", null, values);
        } else {
            db.update("places", values, "_id = ?", new String[] {Long.toString(id)});
        }
    }

    void deletePlace(long id) {
        getWritableDatabase().delete("places", "_id = ?", new String[] {Long.toString(id)});
    }

    void saveRide(Ride ride, boolean draft) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("id", ride.id());
            values.put("title", ride.title());
            values.put("zone", ride.zone());
            values.put("start", ride.started());
            values.put("elapsed", ride.elapsedMillis());
            values.put("moving", ride.movingMillis());
            values.put("distance", ride.distanceMeters());
            values.put("draft", draft ? 1 : 0);
            long inserted = db.insertWithOnConflict("rides", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            int position = 0;
            if (inserted == -1) {
                db.update("rides", values, "id = ?", new String[] {ride.id()});
                try (Cursor cursor = db.rawQuery(
                        "SELECT COUNT(*) FROM points WHERE ride_id = ?", new String[] {ride.id()})) {
                    if (cursor.moveToFirst()) {
                        position = cursor.getInt(0);
                    }
                }
            }
            for (; position < ride.points().size(); position++) {
                Ride.Point point = ride.points().get(position);
                ContentValues row = new ContentValues();
                row.put("ride_id", ride.id());
                row.put("position", position);
                row.put("lat", point.lat());
                row.put("lon", point.lon());
                row.put("time", point.time());
                row.put("segment", point.segmentStart() ? 1 : 0);
                if (Double.isFinite(point.elevationMetres())) {
                    row.put("elevation", point.elevationMetres());
                }
                if (Double.isFinite(point.speedMetresPerSecond())) {
                    row.put("speed", point.speedMetresPerSecond());
                }
                db.insertOrThrow("points", null, row);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void recoverInterruptedRides() {
        getWritableDatabase().execSQL("UPDATE rides SET draft = 0, title = title || ' (interrupted)' WHERE draft = 1");
    }

    List<Summary> history() {
        List<Summary> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id, title, zone, start, elapsed, moving, distance, temperature, wind, direction "
                        + "FROM rides WHERE draft = 0 ORDER BY start DESC", null)) {
            while (cursor.moveToNext()) {
                Weather.Conditions weather = cursor.isNull(7) ? null
                        : new Weather.Conditions(cursor.getDouble(7), cursor.getDouble(8), cursor.getInt(9));
                result.add(new Summary(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getLong(4), cursor.getLong(5), cursor.getDouble(6), weather));
            }
        }
        return result;
    }

    List<Ride.Point> route(String id) {
        List<Ride.Point> points = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT lat, lon, time, segment, elevation, speed FROM points WHERE ride_id = ? ORDER BY position",
                new String[] {id})) {
            while (cursor.moveToNext()) {
                points.add(new Ride.Point(cursor.getDouble(0), cursor.getDouble(1),
                        cursor.getLong(2), cursor.getInt(3) != 0,
                        cursor.isNull(4) ? Double.NaN : cursor.getDouble(4),
                        cursor.isNull(5) ? Double.NaN : cursor.getDouble(5)));
            }
        }
        return points;
    }

    void saveWeather(String id, Weather.Conditions weather) {
        ContentValues row = new ContentValues();
        row.put("temperature", weather.temperature());
        row.put("wind", weather.wind());
        row.put("direction", weather.direction());
        getWritableDatabase().update("rides", row, "id = ?", new String[] {id});
    }
}
