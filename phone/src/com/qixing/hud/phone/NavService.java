package com.qixing.hud.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.qixing.hud.common.Geo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns GPS, the navigation engine, voice prompts and the link to the glasses. */
public class NavService extends Service {
    private static final String TAG = "QixingNav";
    private static final String CH = "nav";
    static volatile NavService I;

    private HandlerThread th;
    private Handler h;
    private LocationManager lm;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private PowerManager.WakeLock cpuLock;
    final GlassesLink link = new GlassesLink();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    // State read by the UI (written on the nav thread).
    volatile Location last;
    volatile long lastGpsFixAt;
    volatile Route route;
    volatile boolean navigating, simulating, arrived, rerouting;
    volatile String uiAction = "", uiRoad = "", uiMsg = "";
    volatile double uiMdist, uiRem;
    volatile long uiEta;
    volatile boolean voiceOn = true;

    // Engine state (nav thread only).
    private int hintSeg = -1;
    private int offCount;
    private long lastRerouteAt;
    private long arrivedAt;
    private final Set<String> spoken = new HashSet<>();
    private double simAlong, simSpeed = 5.0;
    private int simTicks;
    volatile boolean simDetour;
    private long lastStateAt;
    private final double[] tmp = new double[3];
    private double speedAvg;

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { onLocation(loc, true); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };
    private final LocationListener netListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { onLocation(loc, false); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        voiceOn = getSharedPreferences("p", MODE_PRIVATE).getBoolean("voice", true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "骑行导航", NotificationManager.IMPORTANCE_LOW));
        Notification n = notif("骑行导航运行中", "眼镜可通过热点或蓝牙连接");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }
        th = new HandlerThread("nav");
        th.start();
        h = new Handler(th.getLooper());
        link.start();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        startGps();
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
                ttsReady = true;
            }
        });
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qixing:nav");
        h.post(heartbeat);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A background restart could not regain location access anyway; the user reopens the app.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        I = null;
        try {
            lm.removeUpdates(gpsListener);
            lm.removeUpdates(netListener);
        } catch (Exception ignored) {
        }
        link.stop();
        if (tts != null) tts.shutdown();
        if (cpuLock.isHeld()) cpuLock.release();
        th.quitSafely();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notif(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotif(String title, String text) {
        getSystemService(NotificationManager.class).notify(1, notif(title, text));
    }

    void startGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, th.getLooper());
        } catch (Exception e) {
            Log.w(TAG, "gps: " + e);
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 0, netListener, th.getLooper());
            }
        } catch (Exception e) {
            Log.w(TAG, "network location: " + e);
        }
        try {
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) last = l;
        } catch (Exception ignored) {
        }
    }

    boolean gpsGood() {
        return SystemClock.elapsedRealtime() - lastGpsFixAt < 5000;
    }

    void setVoice(boolean on) {
        voiceOn = on;
        getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("voice", on).apply();
    }

    // ---------------------------------------------------------------- navigation control

    void startNav(Route r, boolean sim) {
        h.post(() -> {
            route = r;
            navigating = true;
            simulating = sim;
            arrived = false;
            rerouting = false;
            hintSeg = -1;
            offCount = 0;
            spoken.clear();
            uiMsg = "";
            sendRoute(r);
            if (!cpuLock.isHeld()) cpuLock.acquire(6 * 60 * 60 * 1000L);
            speak("开始" + (sim ? "模拟" : "") + "导航，全程" + Geo.fmtDist(r.totalDist)
                    + "，大约" + Math.max(1, Math.round(r.totalDur / 60)) + "分钟");
            updateNotif("导航中：" + r.destName, "全程 " + Geo.fmtDist(r.totalDist));
            if (sim) {
                simAlong = 0;
                simTicks = 0;
                h.removeCallbacks(simTick);
                h.post(simTick);
            } else if (last != null) {
                onLocation(last, false);
            }
        });
    }

    void stopNav() {
        h.post(() -> {
            boolean was = navigating;
            navigating = false;
            simulating = false;
            route = null;
            arrived = false;
            rerouting = false;
            h.removeCallbacks(simTick);
            if (cpuLock.isHeld()) cpuLock.release();
            link.sendRoute("{\"type\":\"route\",\"rid\":-1,\"pts\":[]}");
            sendIdle();
            if (was) speak("导航已结束");
            updateNotif("骑行导航运行中", "眼镜可通过热点或蓝牙连接");
        });
    }

    private final Runnable simTick = new Runnable() {
        @Override
        public void run() {
            Route r = route;
            if (!simulating || r == null) return;
            simAlong += simSpeed;
            r.pointAt(simAlong, tmp);
            Location l = new Location("sim");
            l.setLatitude(tmp[0]);
            l.setLongitude(tmp[1]);
            l.setBearing((float) tmp[2]);
            l.setSpeed((float) simSpeed);
            l.setAccuracy(4f);
            l.setTime(System.currentTimeMillis());
            simTicks++;
            if (simDetour && simTicks > 25 && simTicks <= 40) {
                // Test hook: ride ~100 m off the route for a while to exercise rerouting.
                l.setLatitude(tmp[0] + 0.0009);
            }
            processNav(l, true);
            // Keep ticking at the end point so the arrival screen times out like a real ride.
            if (simAlong > r.totalDist) simAlong = r.totalDist;
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.elapsedRealtime() - lastStateAt > 1500) {
                // No fresh fix: refresh the glasses (GPS-weak flag, clock) without re-judging position.
                if (navigating && route != null && last != null && !simulating) processNav(last, false);
                else if (!navigating) sendIdle();
            }
            h.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- engine

    private void onLocation(Location loc, boolean gps) {
        if (gps) lastGpsFixAt = SystemClock.elapsedRealtime();
        else if (gpsGood()) return; // GPS is better whenever we have it
        last = loc;
        if (simulating) return;
        if (navigating && route != null) processNav(loc, true);
        else sendIdle();
    }

    private void processNav(Location loc, boolean fresh) {
        // Runs on the location thread: an exception here would close the app mid-ride.
        try {
            processNavInner(loc, fresh);
        } catch (Throwable t) {
            Log.w(TAG, "nav: " + t);
        }
    }

    private void processNavInner(Location loc, boolean fresh) {
        Route r = route;
        if (r == null) return;
        double la = loc.getLatitude(), lo = loc.getLongitude();
        double acc = loc.hasAccuracy() ? loc.getAccuracy() : 30;
        double spd = loc.hasSpeed() ? loc.getSpeed() : 0;
        if (!fresh) spd = 0; // stale fix: we do not know that we are moving
        else speedAvg = speedAvg * 0.9 + spd * 0.1;

        Route.Match m = r.project(la, lo, hintSeg);
        hintSeg = m.seg;
        double along = m.along;
        double rem = Math.max(0, r.totalDist - along);
        double toDest = Geo.dist(la, lo, r.destLat, r.destLon);

        // Off-route -> reroute.
        if (fresh && (!simulating || simDetour) && !arrived) {
            double limit = Math.max(35, acc * 1.3);
            if (m.off > limit && acc < 60) offCount++;
            else offCount = 0;
            if (offCount >= 3 && !rerouting && SystemClock.elapsedRealtime() - lastRerouteAt > 12000) {
                reroute(la, lo);
            }
        }

        // Arrival.
        if (fresh && !arrived && (rem < 20 || toDest < 20 || (rem < 40 && m.off < 40 && toDest < 45))) {
            arrived = true;
            arrivedAt = SystemClock.elapsedRealtime();
            speak("已到达目的地附近，本次导航结束");
            uiMsg = "已到达";
        }
        if (arrived && SystemClock.elapsedRealtime() - arrivedAt > 25000) {
            stopNavNow();
            return;
        }

        // Next real maneuver.
        Route.Step next = null;
        int nextIdx = -1;
        for (int i = 1; i < r.steps.size(); i++) {
            Route.Step s = r.steps.get(i);
            if (s.along > along + 3 && (!s.silent || "arrive".equals(s.type))) {
                next = s;
                nextIdx = i;
                break;
            }
        }
        double mdist = next == null ? rem : next.along - along;
        String act, road, code;
        int exit = 0;
        double mlat = r.destLat, mlon = r.destLon;
        if (next == null) {
            act = "到达目的地";
            road = r.destName;
            code = "arrive";
        } else {
            act = Instr.action(next);
            road = Instr.road(next);
            code = next.code;
            exit = next.exit;
            mlat = next.lat;
            mlon = next.lon;
            if ("arrive".equals(next.type)) road = r.destName;
        }
        if (arrived) {
            act = "已到达";
            road = r.destName;
            code = "arrive";
            mdist = 0;
        }

        // Voice prompts.
        if (fresh && next != null && !arrived) {
            String key = r.id + ":" + nextIdx;
            String say = act + ("arrive".equals(next.type) ? "" : (road.isEmpty() ? "" : "，" + road));
            if (mdist <= 260 && mdist > 90 && spoken.add(key + ":far")) {
                speak("前方" + roundSay(mdist) + "，" + say);
            } else if (mdist <= 60 && mdist > 8 && spoken.add(key + ":near")) {
                spoken.add(key + ":far");
                speak("前方" + roundSay(mdist) + "，" + act);
            }
        }

        // ETA: blend measured speed with the router's estimate.
        double remDur = r.totalDist > 0 ? r.totalDur * rem / r.totalDist : 0;
        if (speedAvg > 2.0) remDur = rem / speedAvg;
        long eta = System.currentTimeMillis() + (long) (remDur * 1000);

        double brg = (loc.hasBearing() && spd > 1.5) ? loc.getBearing() : r.segBearing(m.seg);

        uiAction = act;
        uiRoad = road;
        uiMdist = mdist;
        uiRem = rem;
        uiEta = eta;
        if (!rerouting && !arrived) uiMsg = "";

        try {
            JSONObject j = new JSONObject();
            j.put("type", "state");
            j.put("nav", true);
            j.put("sim", simulating);
            j.put("gps", simulating || gpsGood());
            j.put("lat", la);
            j.put("lon", lo);
            j.put("acc", Math.round(acc));
            j.put("spd", round1(spd));
            j.put("brg", Math.round(brg));
            j.put("rid", r.id);
            j.put("seg", m.seg);
            j.put("along", Math.round(along));
            j.put("man", code);
            j.put("exit", exit);
            j.put("act", act);
            j.put("road", road);
            j.put("mdist", Math.round(mdist));
            j.put("mlat", mlat);
            j.put("mlon", mlon);
            j.put("rem", Math.round(rem));
            j.put("eta", eta);
            j.put("dest", r.destName);
            j.put("msg", uiMsg);
            j.put("arr", arrived);
            link.sendState(j.toString());
            lastStateAt = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.w(TAG, "state: " + e);
        }
    }

    private void stopNavNow() {
        navigating = false;
        simulating = false;
        route = null;
        arrived = false;
        h.removeCallbacks(simTick);
        if (cpuLock.isHeld()) cpuLock.release();
        link.sendRoute("{\"type\":\"route\",\"rid\":-1,\"pts\":[]}");
        sendIdle();
        updateNotif("骑行导航运行中", "眼镜可通过热点或蓝牙连接");
    }

    private void reroute(double la, double lo) {
        Route r = route;
        if (r == null) return;
        rerouting = true;
        lastRerouteAt = SystemClock.elapsedRealtime();
        uiMsg = "已偏离路线，正在重新规划…";
        speak("已偏离路线，正在重新规划");
        final double dLat = r.destLat, dLon = r.destLon;
        final String dName = r.destName;
        net.execute(() -> {
            try {
                Route nr = Router.fetch(la, lo, dLat, dLon, dName);
                h.post(() -> {
                    rerouting = false;
                    if (!navigating) return;
                    route = nr;
                    hintSeg = -1;
                    offCount = 0;
                    uiMsg = "";
                    if (simulating) {
                        simAlong = 0;
                        simDetour = false;
                    }
                    sendRoute(nr);
                    speak("已重新规划路线");
                });
            } catch (Exception e) {
                h.post(() -> {
                    rerouting = false;
                    uiMsg = "重新规划失败，请检查手机网络";
                });
            }
        });
    }

    private void sendRoute(Route r) {
        try {
            StringBuilder sb = new StringBuilder(r.size() * 24 + 128);
            sb.append("{\"type\":\"route\",\"rid\":").append(r.id)
                    .append(",\"total\":").append(Math.round(r.totalDist))
                    .append(",\"dest\":").append(JSONObject.quote(r.destName))
                    .append(",\"pts\":[");
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('[').append(String.format(Locale.US, "%.6f,%.6f", r.lat[i], r.lon[i])).append(']');
            }
            sb.append("],\"steps\":");
            JSONArray st = new JSONArray();
            for (Route.Step s : r.steps) {
                if (s.silent || "depart".equals(s.type)) continue;
                JSONArray a = new JSONArray();
                a.put(s.lat);
                a.put(s.lon);
                a.put(Math.round(s.along));
                st.put(a);
            }
            sb.append(st.toString()).append('}');
            link.sendRoute(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "route: " + e);
        }
    }

    private void sendIdle() {
        try {
            Location l = last;
            JSONObject j = new JSONObject();
            j.put("type", "state");
            j.put("nav", false);
            j.put("gps", gpsGood());
            if (l != null) {
                j.put("lat", l.getLatitude());
                j.put("lon", l.getLongitude());
                j.put("acc", Math.round(l.hasAccuracy() ? l.getAccuracy() : 0));
                j.put("spd", round1(l.hasSpeed() ? l.getSpeed() : 0));
                j.put("brg", Math.round(l.hasBearing() ? l.getBearing() : 0));
            }
            link.sendState(j.toString());
            lastStateAt = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.w(TAG, "idle: " + e);
        }
    }

    private void speak(String s) {
        Log.i(TAG, "say: " + s);
        if (!voiceOn || !ttsReady) return;
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "q" + SystemClock.elapsedRealtime());
    }

    private static String roundSay(double m) {
        if (m >= 1000) return String.format(Locale.US, "%.1f公里", m / 1000);
        long v = m >= 100 ? Math.round(m / 50.0) * 50 : Math.max(10, Math.round(m / 10.0) * 10);
        return v + "米";
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
