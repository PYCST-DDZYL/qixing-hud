package com.qixing.hud.common;

public final class Geo {
    public static final double R = 6371008.8;

    private Geo() {}

    public static double dist(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing in degrees, 0..360, clockwise from north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360) % 360;
    }

    /** Local planar metres (east, north) of (lat,lon) relative to (lat0,lon0). */
    public static void toXY(double lat0, double lon0, double lat, double lon, double[] out) {
        out[0] = Math.toRadians(lon - lon0) * R * Math.cos(Math.toRadians(lat0));
        out[1] = Math.toRadians(lat - lat0) * R;
    }

    public static String fmtDist(double m) {
        if (m < 0) m = 0;
        if (m < 1000) {
            long v = m < 100 ? Math.round(m) : Math.round(m / 10.0) * 10;
            return v + (L.en ? " m" : " 米");
        }
        return String.format(java.util.Locale.US, m < 10000 ? "%.1f" : "%.0f", m / 1000.0) + (L.en ? " km" : " 公里");
    }
}
