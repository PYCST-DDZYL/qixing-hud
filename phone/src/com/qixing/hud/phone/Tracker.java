package com.qixing.hud.phone;

import com.qixing.hud.common.Geo;

/**
 * Turns raw GPS fixes into the position shown to the rider, the way car navigation does.
 * <ul>
 * <li>On the route: the position is put on the route line ("snapped") and the heading follows the
 * road, so the arrow on the glasses sits on the line instead of wobbling 3-10 m around it.</li>
 * <li>The distance along the route is smoothed: predicted from the GPS speed, then pulled part of
 * the way towards each new fix. Countdowns to the next turn no longer jump back and forth.</li>
 * <li>Off the route: the raw fix and the GPS heading, unchanged.</li>
 * </ul>
 * Pure Java so it can be tested on a computer with real routes.
 */
final class Tracker {
    /** Within this distance of the route the rider is shown on the line. */
    static final double SNAP = 25;
    /** A fix this far from the prediction is taken as it is (a U-turn, a jump, a new route). */
    static final double RESET = 30;

    double along = -1;   // smoothed distance along the route, metres
    double lat, lon;     // position to show
    double brg;          // heading to show, degrees
    boolean snapped;

    private Route route;
    private long lastNs;
    private final double[] a = new double[3], b = new double[3];

    void reset() {
        route = null;
        along = -1;
    }

    /**
     * @param m      the fix projected onto the route
     * @param spd    ground speed from GPS, m/s
     * @param gpsBrg GPS heading in degrees, or NaN when there is none
     * @param nowNs  monotonic time of this fix in nanoseconds
     */
    void update(Route r, Route.Match m, double la, double lo, double spd, double gpsBrg, long nowNs) {
        if (r != route) {
            route = r;
            along = -1;
        }
        double routeBrg = bearingAround(r, m.along);
        // Riding the route the wrong way (turned back): predict backwards and keep the GPS heading.
        boolean backwards = !Double.isNaN(gpsBrg) && spd > 2 && angle(gpsBrg, routeBrg) > 120;
        snapped = m.off <= SNAP;
        if (!snapped || along < 0) {
            along = m.along;
        } else if (nowNs > lastNs) {
            double dt = Math.min(3, (nowNs - lastNs) / 1e9);
            double pred = along + (backwards ? -spd : spd) * dt;
            double diff = m.along - pred;
            double prev = along;
            if (Math.abs(diff) > RESET) along = m.along;
            else along = pred + (spd < 0.8 ? 0.25 : 0.5) * diff; // standing still: GPS noise, not movement
            // Riding forward, the distance to the next turn only counts down: a small step back is
            // GPS noise. A real turn-around shows up as "backwards" or as a jump beyond RESET.
            if (!backwards && along < prev && prev - along < 10) along = prev;
        }
        along = Math.max(0, Math.min(r.totalDist, along));
        if (nowNs > 0) lastNs = nowNs; // 0: the same fix processed again, no time has passed
        if (snapped) {
            r.pointAt(along, a);
            lat = a[0];
            lon = a[1];
            brg = backwards ? gpsBrg : bearingAround(r, along);
        } else {
            lat = la;
            lon = lo;
            brg = !Double.isNaN(gpsBrg) && spd > 1.5 ? gpsBrg : routeBrg;
        }
    }

    /** Direction of the road around a point, from 8 m before to 8 m after: steady on curves. */
    private double bearingAround(Route r, double d) {
        r.pointAt(d - 8, a);
        r.pointAt(d + 8, b);
        if (Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9) return a[2];
        return Geo.bearing(a[0], a[1], b[0], b[1]);
    }

    private static double angle(double x, double y) {
        double d = Math.abs(((x - y) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }
}
