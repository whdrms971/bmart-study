package com.baropick.delivery.common;

public final class Geo {
    private static final double R = 6_371_008.8;
    private Geo() {}

    /** 두 좌표 사이 대권 거리(m). 도로 거리 아님. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static boolean validLat(Double v) { return v != null && Double.isFinite(v) && v >= -90 && v <= 90; }
    public static boolean validLng(Double v) { return v != null && Double.isFinite(v) && v >= -180 && v <= 180; }
}
