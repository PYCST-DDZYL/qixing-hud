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
    String profile = "";

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

    /** Projects a point onto the route. hint < 0 searches the whole route. */
    Match project(double la, double lo, int hint) {
        Match m = projectRange(la, lo, hint < 0 ? 0 : Math.max(0, hint - 15),
                hint < 0 ? size() - 1 : Math.min(size() - 1, hint + 400));
        if (hint >= 0 && m.off > 80) {
            Match g = projectRange(la, lo, 0, size() - 1);
            if (g.off < m.off) m = g;
        }
        return m;
    }

    private Match projectRange(double la, double lo, int from, int to) {
        Match best = new Match();
        best.off = Double.MAX_VALUE;
        double[] a = new double[2], b = new double[2];
        if (size() < 2) {
            best.off = size() == 1 ? Geo.dist(la, lo, lat[0], lon[0]) : Double.MAX_VALUE;
            return best;
        }
        for (int i = from; i < to; i++) {
            Geo.toXY(la, lo, lat[i], lon[i], a);
            Geo.toXY(la, lo, lat[i + 1], lon[i + 1], b);
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 <= 1e-9 ? 0 : -(a[0] * dx + a[1] * dy) / len2;
            if (t < 0) t = 0;
            else if (t > 1) t = 1;
            double qx = a[0] + t * dx, qy = a[1] + t * dy;
            double d = Math.sqrt(qx * qx + qy * qy);
            if (d < best.off) {
                best.off = d;
                best.seg = i;
                best.along = cum[i] + t * (cum[i + 1] - cum[i]);
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
