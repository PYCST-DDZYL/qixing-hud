package com.qixing.hud.phone;

import com.qixing.hud.common.Geo;
import com.qixing.hud.common.L;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Free place search: Photon (komoot) first, OSM Nominatim as fallback. No keys. */
final class Search {
    static final class Place {
        String name, addr;
        double lat, lon;
        boolean resume; // "continue last ride" entry; not saved

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("addr", addr);
                o.put("lat", lat);
                o.put("lon", lon);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Place fromJson(JSONObject o) {
            Place p = new Place();
            p.name = o.optString("name");
            p.addr = o.optString("addr");
            p.lat = o.optDouble("lat");
            p.lon = o.optDouble("lon");
            return p;
        }
    }

    private static final Pattern COORD =
            Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,，\\s]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    private Search() {}

    static List<Place> search(String q, double nearLat, double nearLon, boolean haveNear) throws Exception {
        List<Place> out = new ArrayList<>();
        Matcher m = COORD.matcher(q);
        if (m.matches()) {
            Place p = new Place();
            p.lat = Double.parseDouble(m.group(1));
            p.lon = Double.parseDouble(m.group(2));
            p.name = L.t("坐标 ", "Point ") + m.group(1) + "," + m.group(2);
            p.addr = "";
            out.add(p);
            return out;
        }
        Exception err = null;
        try {
            String url = "https://photon.komoot.io/api/?limit=10&q=" + Http.enc(q)
                    + (haveNear ? String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLon) : "");
            JSONArray f = new JSONObject(Http.get(url)).getJSONArray("features");
            for (int i = 0; i < f.length(); i++) {
                JSONObject o = f.getJSONObject(i);
                JSONArray c = o.getJSONObject("geometry").getJSONArray("coordinates");
                JSONObject pr = o.getJSONObject("properties");
                Place p = new Place();
                p.lon = c.getDouble(0);
                p.lat = c.getDouble(1);
                String street = join(" ", pr.optString("housenumber"), pr.optString("street"));
                p.name = pr.optString("name", "");
                if (p.name.isEmpty()) p.name = street.isEmpty() ? pr.optString("city", L.t("未命名地点", "Unnamed place")) : street;
                p.addr = join(", ", p.name.equals(street) ? "" : street, pr.optString("city"),
                        pr.optString("state"), pr.optString("country"));
                add(out, p);
            }
        } catch (Exception e) {
            err = e;
        }
        if (out.size() < 3) {
            try {
                String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=" + Http.enc(q)
                        + (haveNear ? String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f&bounded=0",
                        nearLon - 0.6, nearLat + 0.6, nearLon + 0.6, nearLat - 0.6) : "");
                JSONArray a = new JSONArray(Http.get(url));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Place p = new Place();
                    p.lat = o.getDouble("lat");
                    p.lon = o.getDouble("lon");
                    String disp = o.optString("display_name", "");
                    p.name = o.optString("name", "");
                    if (p.name.isEmpty()) {
                        int k = disp.indexOf(',');
                        p.name = k > 0 ? disp.substring(0, k) : disp;
                    }
                    p.addr = disp;
                    add(out, p);
                }
            } catch (Exception e) {
                if (err == null) err = e;
            }
        }
        if (out.isEmpty() && err != null) throw err;
        if (haveNear) {
            final double la = nearLat, lo = nearLon;
            out.sort((x, y) -> Double.compare(Geo.dist(la, lo, x.lat, x.lon), Geo.dist(la, lo, y.lat, y.lon)));
        }
        return out;
    }

    private static void add(List<Place> out, Place p) {
        for (Place o : out) {
            if (Geo.dist(o.lat, o.lon, p.lat, p.lon) < 40 && o.name.equalsIgnoreCase(p.name)) return;
        }
        out.add(p);
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }
}
