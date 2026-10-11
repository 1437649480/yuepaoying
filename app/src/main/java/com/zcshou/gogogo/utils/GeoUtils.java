package com.zcshou.gogogo.utils;

/**
 * 地理计算工具。
 *
 * 说明：本项目内部统一以 WGS-84 存储坐标（与 2.1.0 之后的口径一致），
 * 仅在百度地图上显示时转换为 BD-09。
 */
public class GeoUtils {

    private static final double EARTH_RADIUS = 6371008.8; // 地球平均半径，单位：米

    /**
     * 计算两点间的球面距离（Haversine），单位：米
     */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS * c;
    }

    /**
     * 计算两点间的方位角（正北为 0，顺时针），单位：度
     */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double dLngRad = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLngRad) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad)
                - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLngRad);
        double brg = Math.toDegrees(Math.atan2(y, x));
        return (brg + 360) % 360;
    }

    /**
     * 将距离（米）换算为纬度偏移量
     */
    public static double metersToLatDegrees(double meters) {
        return meters / 110574.0;
    }

    /**
     * 将距离（米）换算为经度偏移量（随纬度变化）
     */
    public static double metersToLngDegrees(double meters, double latitude) {
        double cos = Math.cos(Math.abs(latitude) * Math.PI / 180);
        if (cos < 1e-6) {
            cos = 1e-6;
        }
        return meters / (111320.0 * cos);
    }

    /**
     * 计算一条路线的总长度，单位：米
     */
    public static double pathLength(double[][] path) {
        double total = 0;
        if (path == null || path.length < 2) {
            return 0;
        }
        for (int i = 1; i < path.length; i++) {
            total += distance(path[i - 1][1], path[i - 1][0], path[i][1], path[i][0]);
        }
        return total;
    }

    /**
     * 格式化距离：小于 1000 米显示米，否则显示千米
     */
    public static String formatDistance(double meters) {
        if (meters < 1000) {
            return String.format("%.0f m", meters);
        }
        return String.format("%.2f km", meters / 1000);
    }

    /**
     * 格式化时间（秒）为 mm:ss 或 h:mm:ss
     */
    public static String formatDuration(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) {
            return "--:--";
        }
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }
}
