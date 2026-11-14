package com.qixing.hud.phone;

import com.qixing.hud.common.L;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Free routing through public OSRM servers. Bicycle first, then walking, then car as last resort. */
final class Router {
    /** Profile ids: "bike", "foot", "car". */
    private static final String[][] SERVERS = {
            {"bike", "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"},
            {"foot", "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"},
            {"car", "https://router.project-osrm.org/route/v1/driving/"},
    };
    private static int nextId = 1;

    private Router() {}

    static String profileName(String id) {
        if ("foot".equals(id)) return L.t("步行", "Walking");
        if ("car".equals(id)) return L.t("驾车", "Driving");
        return L.t("骑行", "Cycling");
    }

    static Route fetch(double fromLat, double fromLon, double toLat, double toLon, String destName)
            throws IOException {
        IOException last = null;
        for (String[] s : SERVERS) {
            String url = s[1] + String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat)
                    + "?overview=full&geometries=polyline&steps=true&alternatives=false";
            try {
                return finish(Http.get(url), s[0], destName, toLat, toLon);
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                last = new IOException(e.getMessage());
            }
        }
        throw last != null ? last : new IOException(L.t("没有可用的路线服务", "no routing service available"));
    }

    /** Rebuilds a route from a saved server response (used to continue a ride without network). */
    static Route fromSaved(String raw, String profile, String destName, double toLat, double toLon)
            throws Exception {
        return finish(raw, profile, destName, toLat, toLon);
    }

    private static Route finish(String raw, String profile, String destName, double toLat, double toLon)
            throws Exception {
        Route r = parse(raw);
        r.raw = raw;
        r.profile = profile;
        r.destName = destName == null ? "" : destName;
        r.destLat = toLat;
        r.destLon = toLon;
        synchronized (Router.class) {
            r.id = nextId++;
        }
        return r;
    }

    static Route parse(String body) throws Exception {
        JSONObject j = new JSONObject(body);
        if (!"Ok".equals(j.optString("code"))) {
            throw new IOException(L.t("路线服务返回：", "routing service says: ") + j.optString("code") + " " + j.optString("message"));
        }
        JSONObject r = j.getJSONArray("routes").getJSONObject(0);
        Route route = new Route();
        List<double[]> pts = decode(r.getString("geometry"));
        int n = pts.size();
        if (n < 2) throw new IOException(L.t("路线为空", "empty route"));
        route.lat = new double[n];
        route.lon = new double[n];
        for (int i = 0; i < n; i++) {
            route.lat[i] = pts.get(i)[0];
            route.lon[i] = pts.get(i)[1];
        }
        route.buildCum();
        route.totalDur = r.optDouble("duration", 0);

        JSONArray legs = r.getJSONArray("legs");
        // Where each maneuver sits along the route comes from summing OSRM's step distances, not
        // from snapping its location onto the line: a route that passes the same corner twice
        // (loops, out-and-back) would otherwise get turns out of order. Scaled to our geometry.
        double stepSum = 0;
        for (int li = 0; li < legs.length(); li++) {
            JSONArray steps = legs.getJSONObject(li).getJSONArray("steps");
            for (int i = 0; i < steps.length(); i++) stepSum += steps.getJSONObject(i).optDouble("distance", 0);
        }
        double scale = stepSum > 0 ? route.totalDist / stepSum : 1;
        double acc = 0;
        int hint = 0;
        for (int li = 0; li < legs.length(); li++) {
            JSONArray steps = legs.getJSONObject(li).getJSONArray("steps");
            for (int i = 0; i < steps.length(); i++) {
                JSONObject st = steps.getJSONObject(i);
                JSONObject man = st.getJSONObject("maneuver");
                JSONArray loc = man.getJSONArray("location");
                Route.Step s = new Route.Step();
                s.type = man.optString("type", "");
                s.modifier = man.optString("modifier", "");
                s.exit = man.optInt("exit", 0);
                s.name = st.optString("name", "");
                if (s.name.isEmpty()) s.name = st.optString("ref", "");
                s.lon = loc.getDouble(0);
                s.lat = loc.getDouble(1);
                if (stepSum > 0) {
                    s.along = Math.min(route.totalDist, acc * scale);
                } else {
                    Route.Match m = route.project(s.lat, s.lon, hint);
                    hint = m.seg;
                    s.along = m.along;
                }
                acc += st.optDouble("distance", 0);
                s.code = Instr.code(s.type, s.modifier);
                s.silent = Instr.silent(s);
                route.steps.add(s);
            }
        }
        return route;
    }

    static List<double[]> decode(String enc) {
        List<double[]> out = new ArrayList<>();
        int index = 0, lat = 0, lng = 0;
        while (index < enc.length()) {
            int b, shift = 0, result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            shift = 0;
            result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            out.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return out;
    }
}
