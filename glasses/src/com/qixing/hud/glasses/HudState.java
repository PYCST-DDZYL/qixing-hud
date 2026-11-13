package com.qixing.hud.glasses;

import org.json.JSONArray;
import org.json.JSONObject;

/** Latest data received from the phone. Guard reads and writes with synchronized(this). */
final class HudState implements Cloneable {
    boolean connected;
    String kind = "";

    int rid = -1;
    double[] rlat = new double[0], rlon = new double[0];
    double[] slat = new double[0], slon = new double[0], salong = new double[0];
    String routeDest = "";

    boolean nav, sim, gps, arr, hasPos;
    double lat, lon, acc, spd, brg, along, mdist, mlat, mlon, rem;
    int seg, exit, srid;
    long eta;
    String man = "", act = "", road = "", msg = "", dest = "";
    long stateAt;

    /** Shallow copy for drawing; arrays are replaced, never modified, so sharing them is safe. */
    synchronized HudState snap() {
        try {
            return (HudState) clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    private static String str(JSONObject j, String k) {
        String s = j.optString(k, "");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Returns the message type that was applied, or null. */
    synchronized String apply(String line) {
        try {
            JSONObject j = new JSONObject(line);
            String type = j.optString("type");
            if ("route".equals(type)) {
                rid = j.optInt("rid", -1);
                routeDest = str(j, "dest");
                JSONArray p = j.optJSONArray("pts");
                int n = p == null ? 0 : p.length();
                double[] la = new double[n], lo = new double[n];
                for (int i = 0; i < n; i++) {
                    JSONArray q = p.getJSONArray(i);
                    la[i] = q.getDouble(0);
                    lo[i] = q.getDouble(1);
                }
                rlat = la;
                rlon = lo;
                JSONArray s = j.optJSONArray("steps");
                int m = s == null ? 0 : s.length();
                double[] sa = new double[m], so = new double[m], sd = new double[m];
                for (int i = 0; i < m; i++) {
                    JSONArray q = s.getJSONArray(i);
                    sa[i] = q.getDouble(0);
                    so[i] = q.getDouble(1);
                    sd[i] = q.optDouble(2, Double.MAX_VALUE);
                }
                slat = sa;
                slon = so;
                salong = sd;
                return type;
            }
            if ("state".equals(type)) {
                nav = j.optBoolean("nav");
                sim = j.optBoolean("sim");
                gps = j.optBoolean("gps");
                hasPos = j.has("lat");
                lat = j.optDouble("lat", 0);
                lon = j.optDouble("lon", 0);
                acc = j.optDouble("acc", 0);
                spd = j.optDouble("spd", 0);
                brg = j.optDouble("brg", 0);
                srid = j.optInt("rid", -1);
                seg = j.optInt("seg", 0);
                along = j.optDouble("along", 0);
                man = str(j, "man");
                exit = j.optInt("exit", 0);
                act = str(j, "act");
                road = str(j, "road");
                mdist = j.optDouble("mdist", 0);
                mlat = j.optDouble("mlat", 0);
                mlon = j.optDouble("mlon", 0);
                rem = j.optDouble("rem", 0);
                eta = j.optLong("eta", 0);
                dest = str(j, "dest");
                msg = str(j, "msg");
                arr = j.optBoolean("arr");
                stateAt = System.currentTimeMillis();
                return type;
            }
            return type;
        } catch (Exception e) {
            return null;
        }
    }
}
