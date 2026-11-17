package com.qixing.hud.phone;

import com.qixing.hud.common.Geo;

import java.util.ArrayList;
import java.util.List;

/** A computed route: polyline with cumulative distances plus the maneuvers along it. */
final class Route {
    static final class Step {
        String type = "", modifier = "", name = "";
        int exit;
        double lat, lon;
        double along;      // distance from route start to this maneuver, metres
        String code = "";  // arrow code for the glasses
        boolean silent;    // straight-on name changes we do not announce
    }

    static final class Match {
        int seg;
        double along;
        double off;
    }

    int id;
    double[] lat, lon, cum;
    final List<Step> steps = new ArrayList<>();
    double totalDist, totalDur;
    String destName = "";
    double destLat, destLon;
    String profile = ""; // "bike", "foot" or "car"
    String raw = "";     // the routing server's response, kept so the ride can continue offline

    int size() {
        return lat.length;
    }

    void buildCum() {
        int n = lat.length;
        cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Geo.dist(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        totalDist = n > 0 ? cum[n - 1] : 0;
    }

    /** Within this many metres of the route counts as back on it. */
    static final double REJOIN = 30;

    /** Projects a point onto the route. hint < 0 searches the whole route. */
    Match project(double la, double lo, int hint) {
        if (hint < 0) {
            // First fix: on a round trip the start and the end are both nearby. Take the earliest
            // part of the route that is about as close as the best match, not the end of it.
            return projectRange(la, lo, 0, size() - 1, 15);
        }
        Match m = projectRange(la, lo, Math.max(0, hint - 15), Math.min(size() - 1, hint + 400), 0);
        if (m.off > REJOIN) {
            // Not on the stretch we expected: the rider may have come back to the route somewhere
            // else (a shortcut, a detour that rejoins further on, or turning back). Look at the
            // whole route, and move there only when the rider is clearly on it: on a loop another
            // leg can pass close by, and a rider who is simply off route must not jump onto it.
            Match g = projectRange(la, lo, 0, size() - 1, 0);
            if (g.off <= REJOIN && g.off < m.off - 10) m = g;
            else if (m.off > 80 && g.off < m.off) m = g;
        }
        return m;
    }

    /** slack > 0: return the earliest segment within slack metres of the closest one. */
    private Match projectRange(double la, double lo, int from, int to, double slack) {
        Match best = new Match();
        best.off = Double.MAX_VALUE;
        if (size() < 2) {
            best.off = size() == 1 ? Geo.dist(la, lo, lat[0], lon[0]) : Double.MAX_VALUE;
            return best;
        }
        int n = Math.max(0, to - from);
        double[] off = new double[n], along = new double[n];
        double[] a = new double[2], b = new double[2];
        for (int i = from; i < to; i++) {
            Geo.toXY(la, lo, lat[i], lon[i], a);
            Geo.toXY(la, lo, lat[i + 1], lon[i + 1], b);
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 <= 1e-9 ? 0 : -(a[0] * dx + a[1] * dy) / len2;
            if (t < 0) t = 0;
            else if (t > 1) t = 1;
            double qx = a[0] + t * dx, qy = a[1] + t * dy;
            off[i - from] = Math.sqrt(qx * qx + qy * qy);
            along[i - from] = cum[i] + t * (cum[i + 1] - cum[i]);
            if (off[i - from] < best.off) {
                best.off = off[i - from];
                best.seg = i;
                best.along = along[i - from];
            }
        }
        if (slack > 0) {
            for (int k = 0; k < n; k++) {
                if (off[k] <= best.off + slack) {
                    best.seg = from + k;
                    best.along = along[k];
                    best.off = off[k];
                    break;
                }
            }
        }
        return best;
    }

    /** Point and bearing at a distance along the route. out = {lat, lon, bearing}. */
    void pointAt(double d, double[] out) {
        int n = size();
        if (d <= 0 || n < 2) {
            out[0] = lat[0];
            out[1] = lon[0];
            out[2] = n < 2 ? 0 : Geo.bearing(lat[0], lon[0], lat[1], lon[1]);
            return;
        }
        if (d >= totalDist) {
            out[0] = lat[n - 1];
            out[1] = lon[n - 1];
            out[2] = Geo.bearing(lat[n - 2], lon[n - 2], lat[n - 1], lon[n - 1]);
            return;
        }
        int i = 0;
        while (i < n - 2 && cum[i + 1] < d) i++;
        double segLen = cum[i + 1] - cum[i];
        double t = segLen <= 0 ? 0 : (d - cum[i]) / segLen;
        out[0] = lat[i] + t * (lat[i + 1] - lat[i]);
        out[1] = lon[i] + t * (lon[i + 1] - lon[i]);
        out[2] = Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1]);
    }

    double segBearing(int seg) {
        if (size() < 2) return 0;
        int i = Math.max(0, Math.min(seg, size() - 2));
        return Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1]);
    }
}
