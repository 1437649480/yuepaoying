package com.zcshou.gogogo.route;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.zcshou.gogogo.database.DataBaseRoute;

/**
 * 路线存储封装：增删改查 + 序列化 + GPX 导入导出。
 */
public class RouteStore {

    /** 一条已保存的路线 */
    public static class RouteItem {
        public long id;
        public String name;
        public List<RoutePoint> points = new ArrayList<>();
        public long timestamp;   // 创建时间
        public long updateTime;  // 最近修改时间

        public double lengthMeters() {
            double total = 0;
            for (int i = 1; i < points.size(); i++) {
                total += com.zcshou.gogogo.utils.GeoUtils.distance(
                        points.get(i - 1).latitude, points.get(i - 1).longitude,
                        points.get(i).latitude, points.get(i).longitude);
            }
            return total;
        }
    }

    /* ============================ 序列化 ============================ */

    public static String pointsToJson(List<RoutePoint> points) {
        JSONArray array = new JSONArray();
        if (points != null) {
            for (RoutePoint p : points) {
                try {
                    JSONArray item = new JSONArray();
                    item.put(0, p.longitude);
                    item.put(1, p.latitude);
                    array.put(item);
                } catch (Exception e) {
                    // 单个点写入失败时跳过
                }
            }
        }
        return array.toString();
    }

    public static List<RoutePoint> jsonToPoints(String json) {
        List<RoutePoint> points = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return points;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONArray item = array.getJSONArray(i);
                points.add(new RoutePoint(item.getDouble(0), item.getDouble(1)));
            }
        } catch (Exception e) {
            // 数据损坏时返回已解析出的部分
        }
        return points;
    }

    /* ============================ 查询 ============================ */

    public static List<RouteItem> loadAll(SQLiteDatabase db) {
        List<RouteItem> list = new ArrayList<>();
        if (db == null || !db.isOpen()) {
            return list;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(DataBaseRoute.TABLE_NAME, null,
                    DataBaseRoute.DB_COLUMN_ID + " > ?", new String[]{"0"},
                    null, null, DataBaseRoute.DB_COLUMN_UPDATE + " DESC", null);
            while (cursor.moveToNext()) {
                RouteItem item = new RouteItem();
                item.id = cursor.getLong(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_ID));
                item.name = cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_NAME));
                item.points = jsonToPoints(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_POINTS)));
                try {
                    item.timestamp = Long.parseLong(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_TIMESTAMP)));
                } catch (Exception e) {
                    item.timestamp = 0;
                }
                try {
                    item.updateTime = Long.parseLong(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_UPDATE)));
                } catch (Exception e) {
                    item.updateTime = item.timestamp;
                }
                list.add(item);
            }
        } catch (Exception e) {
            // 表不存在等异常时返回空列表
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return list;
    }

    public static RouteItem getById(SQLiteDatabase db, long id) {
        if (db == null || !db.isOpen()) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = db.query(DataBaseRoute.TABLE_NAME, null,
                    DataBaseRoute.DB_COLUMN_ID + " = ?", new String[]{String.valueOf(id)},
                    null, null, null, null);
            if (cursor.moveToFirst()) {
                RouteItem item = new RouteItem();
                item.id = id;
                item.name = cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_NAME));
                item.points = jsonToPoints(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_POINTS)));
                try {
                    item.timestamp = Long.parseLong(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_TIMESTAMP)));
                } catch (Exception e) {
                    item.timestamp = 0;
                }
                try {
                    item.updateTime = Long.parseLong(cursor.getString(cursor.getColumnIndexOrThrow(DataBaseRoute.DB_COLUMN_UPDATE)));
                } catch (Exception e) {
                    item.updateTime = item.timestamp;
                }
                return item;
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /* ============================ 写入 ============================ */

    /** 新增路线，返回新记录 id（-1 表示失败） */
    public static long insert(SQLiteDatabase db, String name, List<RoutePoint> points) {
        if (db == null || !db.isOpen()) {
            return -1;
        }
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put(DataBaseRoute.DB_COLUMN_NAME, name);
        values.put(DataBaseRoute.DB_COLUMN_POINTS, pointsToJson(points));
        values.put(DataBaseRoute.DB_COLUMN_TIMESTAMP, String.valueOf(now));
        values.put(DataBaseRoute.DB_COLUMN_UPDATE, String.valueOf(now));
        return db.insert(DataBaseRoute.TABLE_NAME, null, values);
    }

    public static boolean update(SQLiteDatabase db, long id, String name, List<RoutePoint> points) {
        if (db == null || !db.isOpen()) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put(DataBaseRoute.DB_COLUMN_NAME, name);
        values.put(DataBaseRoute.DB_COLUMN_POINTS, pointsToJson(points));
        values.put(DataBaseRoute.DB_COLUMN_UPDATE, String.valueOf(System.currentTimeMillis()));
        return db.update(DataBaseRoute.TABLE_NAME, values,
                DataBaseRoute.DB_COLUMN_ID + " = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public static boolean rename(SQLiteDatabase db, long id, String name) {
        if (db == null || !db.isOpen()) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put(DataBaseRoute.DB_COLUMN_NAME, name);
        values.put(DataBaseRoute.DB_COLUMN_UPDATE, String.valueOf(System.currentTimeMillis()));
        return db.update(DataBaseRoute.TABLE_NAME, values,
                DataBaseRoute.DB_COLUMN_ID + " = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public static boolean delete(SQLiteDatabase db, long id) {
        if (db == null || !db.isOpen()) {
            return false;
        }
        return db.delete(DataBaseRoute.TABLE_NAME,
                DataBaseRoute.DB_COLUMN_ID + " = ?", new String[]{String.valueOf(id)}) > 0;
    }

    /* ============================ GPX ============================ */

    /** 导出为 GPX 1.1 文本 */
    public static String toGpx(String name, List<RoutePoint> points) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"GoGoGo\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("  <trk>\n");
        sb.append("    <name>").append(escapeXml(name)).append("</name>\n");
        sb.append("    <trkseg>\n");
        for (RoutePoint p : points) {
            sb.append("      <trkpt lat=\"").append(p.latitude)
                    .append("\" lon=\"").append(p.longitude).append("\" />\n");
        }
        sb.append("    </trkseg>\n");
        sb.append("  </trk>\n");
        sb.append("</gpx>\n");
        return sb.toString();
    }

    /**
     * 从 GPX 中解析第一条轨迹。
     * 同时兼容 trkpt / rtept / wpt 三种节点，提升第三方文件兼容性。
     */
    public static List<RoutePoint> fromGpx(InputStream in) {
        List<RoutePoint> points = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(in, "UTF-8");
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag)) {
                        String lat = parser.getAttributeValue(null, "lat");
                        String lon = parser.getAttributeValue(null, "lon");
                        if (lat != null && lon != null) {
                            points.add(new RoutePoint(Double.parseDouble(lon), Double.parseDouble(lat)));
                        }
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            // 解析失败返回已解析部分
        }
        return points;
    }

    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 生成包含若干点的 JSONObject（备份 / 分享用） */
    public static String toJson(String name, List<RoutePoint> points) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("name", name);
            obj.put("points", new JSONArray(pointsToJson(points)));
        } catch (Exception e) {
            // ignore
        }
        return obj.toString();
    }
}
