package com.zcshou.gogogo.route;

/**
 * 路线途经点。
 *
 * 存储口径统一为 WGS-84；百度地图显示前需经 MapUtils.wgs2bd09 转换。
 */
public class RoutePoint {

    /** 经度 */
    public double longitude;
    /** 纬度 */
    public double latitude;

    public RoutePoint() {
    }

    public RoutePoint(double longitude, double latitude) {
        this.longitude = longitude;
        this.latitude = latitude;
    }

    public RoutePoint copy() {
        return new RoutePoint(this.longitude, this.latitude);
    }

    /** 转为 [经度, 纬度] 数组 */
    public double[] toArray() {
        return new double[]{longitude, latitude};
    }

    @Override
    public String toString() {
        return "[" + longitude + "," + latitude + "]";
    }
}
