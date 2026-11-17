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
import com.qixing.hud.common.L;

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
    private final Tracker tracker = new Tracker();
    private int offCount;
    private long lastRerouteAt;
    private long arrivedAt;
    private final Set<String> spoken = new HashSet<>();
    private double simAlong;
    volatile double simSpeed = 5.0; // m/s; the adb test hook may raise it
    private int simTicks;
    volatile boolean simDetour;
    private long lastStateAt;
    private final double[] tmp = new double[3];
    private double speedAvg;
    private boolean offAnnounced, rerouteFailSpoken;
    private long msgUntil;
    private String notifTitle;
    private long notifAt;

    // Settings (written from the UI).
    volatile boolean voiceOnGlasses = true; // play prompts on the glasses' speaker when connected
    volatile boolean ecoScreen;             // glasses screen only lights up near turns
    private android.media.AudioManager audio;
    private android.media.AudioFocusRequest focusReq;
    private int clipSeq;

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
        android.content.SharedPreferences prefs = getSharedPreferences("p", MODE_PRIVATE);
        L.apply(prefs.getString("lang", "auto"));
        voiceOn = prefs.getBoolean("voice", true);
        voiceOnGlasses = prefs.getBoolean("voiceGlasses", true);
        ecoScreen = prefs.getBoolean("eco", false);
        audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        focusReq = new android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(navAttrs())
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "骑行导航", NotificationManager.IMPORTANCE_LOW));
        Notification n = notif(idleTitle(), idleText());
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
                applyTtsLang();
                tts.setAudioAttributes(navAttrs());
                tts.setOnUtteranceProgressListener(utterances);
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
            offAnnounced = false;
            rerouteFailSpoken = false;
            msgUntil = 0;
            notifTitle = null;
            spoken.clear();
            uiMsg = "";
            sendRoute(r);
            if (!sim) saveResume(r);
            if (!cpuLock.isHeld()) cpuLock.acquire(6 * 60 * 60 * 1000L);
            long mins = Math.max(1, Math.round(r.totalDur / 60));
            boolean car = "car".equals(r.profile);
            speak(L.en
                    ? (sim ? "Simulated navigation started. " : "Navigation started. ") + sayDist(r.totalDist)
                    + ", about " + mins + (mins == 1 ? " minute." : " minutes.")
                    + (car ? " Warning: the cycling route service is unavailable. This is a driving route. Watch for roads closed to bicycles." : "")
                    : "开始" + (sim ? "模拟" : "") + "导航，全程" + Geo.fmtDist(r.totalDist) + "，大约" + mins + "分钟"
                    + (car ? "。注意，骑行路线服务不可用，这是驾车路线，请留意禁止自行车的道路" : ""));
            updateNotif(L.t("导航中：", "Navigating: ") + r.destName, L.t("全程 ", "Total ") + Geo.fmtDist(r.totalDist));
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
            clearResume();
            navigating = false;
            simulating = false;
            route = null;
            arrived = false;
            rerouting = false;
            h.removeCallbacks(simTick);
            if (cpuLock.isHeld()) cpuLock.release();
            link.sendRoute("{\"type\":\"route\",\"rid\":-1,\"pts\":[]}");
            sendIdle();
            if (was) speak(L.t("导航已结束", "Navigation ended."));
            updateNotif(idleTitle(), idleText());
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
            if (simDetour && simAlong > 600 && simAlong <= 600 + simSpeed * 15) {
                // Test hook: ride 120 m to the right of the route for 15 s to exercise rerouting.
                double b = Math.toRadians(tmp[2] + 90);
                l.setLatitude(tmp[0] + 120 * Math.cos(b) / 111320.0);
                l.setLongitude(tmp[1] + 120 * Math.sin(b) / (111320.0 * Math.cos(Math.toRadians(tmp[0]))));
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
                // Nothing to report (e.g. no fix yet): still keep the link alive, or the glasses'
                // 9-second watchdog would drop and redial it over and over.
                if (SystemClock.elapsedRealtime() - lastStateAt > 2500) link.sendOnce("{\"type\":\"ping\"}");
            }
            h.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- engine

    private int rejected; // consecutive fixes thrown away (nav thread)

    /** How old a fix is, in seconds. */
    static double ageSec(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1e9;
    }

    /** A position recent and precise enough to start a route from. */
    boolean hasFreshFix() {
        Location l = last;
        return l != null && ageSec(l) < 30 && (!l.hasAccuracy() || l.getAccuracy() <= 100);
    }

    private void onLocation(Location loc, boolean gps) {
        if (!gps && gpsGood()) return; // GPS is better whenever we have it
        if (navigating && !simulating) {
            // While riding, a bad fix is worse than no fix: it would move the rider on the map,
            // and could even look like an arrival. Skip very imprecise fixes and impossible jumps
            // (but never more than a few in a row, so a real jump is accepted in the end).
            Location p = prevFix;
            boolean vague = loc.hasAccuracy() && loc.getAccuracy() > 80;
            boolean jump = false;
            if (p != null) {
                double dt = (loc.getElapsedRealtimeNanos() - p.getElapsedRealtimeNanos()) / 1e9;
                jump = dt > 0 && dt < 5 && p.distanceTo(loc) / dt > 35; // over 125 km/h on a bike
            }
            if ((vague || jump) && rejected < 5) {
                rejected++;
                Log.i(TAG, "fix skipped: " + (vague ? "accuracy " + Math.round(loc.getAccuracy()) + " m" : "jump"));
                return;
            }
        }
        rejected = 0;
        if (gps) lastGpsFixAt = SystemClock.elapsedRealtime();
        loc = withMotion(loc);
        last = loc;
        if (simulating) return;
        if (navigating && route != null) processNav(loc, true);
        else sendIdle();
    }

    private Location prevFix;

    /**
     * Fills in speed and heading from the previous fix when the provider gives none (network fixes,
     * some GPS chips). Movement smaller than the GPS noise counts as standing still.
     */
    private Location withMotion(Location loc) {
        Location p = prevFix;
        prevFix = loc;
        if (p == null || (loc.hasSpeed() && loc.hasBearing())) return loc;
        double dt = (loc.getElapsedRealtimeNanos() - p.getElapsedRealtimeNanos()) / 1e9;
        if (dt < 0.5 || dt > 10) return loc;
        Location l = new Location(loc);
        double d = p.distanceTo(loc);
        double noise = Math.max(3, (loc.hasAccuracy() ? loc.getAccuracy() : 10) * 0.5);
        boolean moving = d > noise;
        if (!loc.hasSpeed()) l.setSpeed(moving ? (float) Math.min(d / dt, 30) : 0f);
        if (!loc.hasBearing() && moving) l.setBearing(p.bearingTo(loc));
        return l;
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
        // Shown position: on the route line, with a smoothed distance along it (see Tracker).
        tracker.update(r, m, la, lo, spd, loc.hasBearing() ? loc.getBearing() : Double.NaN,
                fresh ? SystemClock.elapsedRealtimeNanos() : 0);
        double along = tracker.along;
        double rem = Math.max(0, r.totalDist - along);
        double toDest = Geo.dist(la, lo, r.destLat, r.destLon);

        // Off-route -> reroute.
        if (fresh && (!simulating || simDetour) && !arrived) {
            double limit = Math.max(35, acc * 1.3);
            if (m.off > limit && acc < 60) {
                offCount++;
            } else if (m.off <= limit) {
                offCount = 0;
                offAnnounced = false; // back on the route: a later detour may be announced again
            }
            if (offCount >= 3 && !rerouting && SystemClock.elapsedRealtime() - lastRerouteAt > 12000) {
                reroute(la, lo);
            }
        }

        // Arrival.
        // "Near the destination" only counts once most of the route is behind us: on a round trip
        // the destination is also right next to the start.
        boolean nearEnd = rem < Math.max(300, r.totalDist * 0.1);
        if (fresh && !arrived && (rem < 20 || (toDest < 20 && nearEnd) || (rem < 40 && m.off < 40 && toDest < 45))) {
            arrived = true;
            arrivedAt = SystemClock.elapsedRealtime();
            speak(L.t("已到达目的地附近，本次导航结束", "You have arrived. Navigation ended."));
            uiMsg = L.t("已到达", "Arrived");
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
            act = L.t("到达目的地", "Arrive");
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
            act = L.t("已到达", "Arrived");
            road = r.destName;
            code = "arrive";
            mdist = 0;
        }

        // Voice prompts.
        if (fresh && next != null && !arrived) {
            String key = r.id + ":" + nextIdx;
            String say = act + ("arrive".equals(next.type) ? "" : (road.isEmpty() ? "" : L.t("，", " ") + road));
            // A second turn right after this one would otherwise be announced too late to act on
            // ("前方10米…"): mention it now, the way car navigation says "随后右转".
            String then = "";
            int afterIdx = -1;
            for (int i = nextIdx + 1; i < r.steps.size(); i++) {
                Route.Step s = r.steps.get(i);
                if (s.silent && !"arrive".equals(s.type)) continue;
                if (s.along - next.along < 50) {
                    afterIdx = i;
                    then = L.t("，随后", ", then ") + lc(Instr.action(s));
                }
                break;
            }
            if (mdist <= 260 && mdist > 90 && spoken.add(key + ":far")) {
                speak(ahead(mdist) + lc(say) + then);
            } else if (mdist <= 60 && mdist > 8 && spoken.add(key + ":near")) {
                spoken.add(key + ":far");
                speak(ahead(mdist) + lc(act) + then);
            }
            if (afterIdx >= 0 && spoken.contains(key + ":near")) {
                // Already announced as "随后…": no separate, too-late prompt for it.
                spoken.add(r.id + ":" + afterIdx + ":far");
                spoken.add(r.id + ":" + afterIdx + ":near");
            }
        }

        // ETA: blend measured speed with the router's estimate.
        double remDur = r.totalDist > 0 ? r.totalDur * rem / r.totalDist : 0;
        if (speedAvg > 2.0) remDur = rem / speedAvg;
        long eta = System.currentTimeMillis() + (long) (remDur * 1000);

        double brg = tracker.brg;

        uiAction = act;
        uiRoad = road;
        uiMdist = mdist;
        uiRem = rem;
        uiEta = eta;
        if (!rerouting && !arrived && SystemClock.elapsedRealtime() > msgUntil) uiMsg = "";
        if (fresh) updateNavNotif(r, mdist, act, road, rem);

        try {
            JSONObject j = new JSONObject();
            j.put("type", "state");
            j.put("nav", true);
            j.put("sim", simulating);
            j.put("gps", simulating || gpsGood());
            j.put("lat", tracker.lat);
            j.put("lon", tracker.lon);
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
            j.put("eco", ecoScreen);
            j.put("lang", L.en ? "en" : "zh");
            link.sendState(j.toString());
            lastStateAt = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            Log.w(TAG, "state: " + e);
        }
    }

    /** Next turn on the phone's notification / lock screen; throttled so it does not flicker. */
    private void updateNavNotif(Route r, double mdist, String act, String road, double rem) {
        String title = (arrived ? L.t("已到达", "Arrived") : Geo.fmtDist(Math.round(mdist / 10.0) * 10) + "  " + act);
        String text = (road.isEmpty() ? "" : road + " · ") + L.t("剩余 ", "Remaining ") + Geo.fmtDist(rem);
        long now = SystemClock.elapsedRealtime();
        if (title.equals(notifTitle) || now - notifAt < 3000) return;
        notifTitle = title;
        notifAt = now;
        try {
            updateNotif(title, text);
        } catch (Exception ignored) {
        }
    }

    private void stopNavNow() {
        clearResume();
        navigating = false;
        simulating = false;
        route = null;
        arrived = false;
        h.removeCallbacks(simTick);
        if (cpuLock.isHeld()) cpuLock.release();
        link.sendRoute("{\"type\":\"route\",\"rid\":-1,\"pts\":[]}");
        sendIdle();
        updateNotif(idleTitle(), idleText());
    }

    private void reroute(double la, double lo) {
        Route r = route;
        if (r == null) return;
        rerouting = true;
        lastRerouteAt = SystemClock.elapsedRealtime();
        uiMsg = L.t("已偏离路线，正在重新规划…", "Off route. Rerouting…");
        // Retries happen every 12 s while off route; announce only the first one.
        if (!offAnnounced) speak(L.t("已偏离路线，正在重新规划", "Off route. Rerouting."));
        offAnnounced = true;
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
                    if (!simulating) saveResume(nr);
                    speak(L.t("已重新规划路线", "Route updated.") + ("car".equals(nr.profile)
                            ? L.t("。注意，骑行路线服务不可用，这是驾车路线", " Warning: the cycling route service is unavailable. This is a driving route.") : ""));
                    rerouteFailSpoken = false;
                });
            } catch (Exception e) {
                h.post(() -> {
                    rerouting = false;
                    if (!navigating) return;
                    uiMsg = L.t("重新规划失败，请检查手机网络", "Rerouting failed. Check the network.");
                    msgUntil = SystemClock.elapsedRealtime() + 8000;
                    if (!rerouteFailSpoken) speak(L.t("重新规划失败，请检查手机网络", "Rerouting failed. Check the phone's network."));
                    rerouteFailSpoken = true;
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
            j.put("lang", L.en ? "en" : "zh");
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

    private static android.media.AudioAttributes navAttrs() {
        return new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /**
     * Remembers the destination of a real ride until it ends, so that if the system kills the app
     * mid-ride the user can pick the ride up again with one tap.
     */
    private void saveResume(Route r) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", r.destName);
            o.put("lat", r.destLat);
            o.put("lon", r.destLon);
            o.put("t", System.currentTimeMillis());
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("resume", o.toString()).apply();
        } catch (Exception ignored) {
        }
        // Keep the route itself too, so the ride can be continued even with no network.
        final String profile = r.profile, raw = r.raw;
        final java.io.File f = savedRouteFile(this);
        net.execute(() -> {
            try {
                if (raw == null || raw.isEmpty()) return;
                java.nio.file.Files.write(f.toPath(),
                        (profile + "\n" + raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "save route: " + e);
            }
        });
    }

    private void clearResume() {
        getSharedPreferences("p", MODE_PRIVATE).edit().remove("resume").apply();
        final java.io.File f = savedRouteFile(this);
        try {
            net.execute(() -> {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            });
        } catch (Exception ignored) {
        }
    }

    static java.io.File savedRouteFile(android.content.Context c) {
        return new java.io.File(c.getFilesDir(), "last-route.txt");
    }

    /** The route saved by the unfinished ride, or null. Network-free; call off the main thread. */
    static Route loadSavedRoute(android.content.Context c, String destName, double lat, double lon) {
        try {
            java.io.File f = savedRouteFile(c);
            if (!f.exists() || f.length() > 8 * 1024 * 1024) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            int nl = s.indexOf('\n');
            if (nl <= 0) return null;
            Route r = Router.fromSaved(s.substring(nl + 1), s.substring(0, nl), destName, lat, lon);
            // Only if it really leads to the same place.
            return Geo.dist(r.lat[r.size() - 1], r.lon[r.size() - 1], lat, lon) < 500 ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Voice follows the app language; falls back to the phone's default voice if it is missing. */
    private void applyTtsLang() {
        try {
            int r = tts.setLanguage(L.en ? Locale.US : Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
        } catch (Exception ignored) {
        }
    }

    void setLang(String setting) {
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("lang", setting).apply();
        L.apply(setting);
        if (ttsReady) applyTtsLang();
        h.post(() -> {
            notifTitle = null;
            if (!navigating) {
                updateNotif(idleTitle(), idleText());
                sendIdle();
            }
        });
    }

    void setVoiceOnGlasses(boolean on) {
        voiceOnGlasses = on;
        getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("voiceGlasses", on).apply();
    }

    void setEcoScreen(boolean on) {
        ecoScreen = on;
        getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("eco", on).apply();
    }

    /**
     * Speaks on the glasses' speaker when they are connected and that option is on (the phone is
     * usually in a pocket while riding); otherwise on the phone, briefly ducking any music.
     */
    private void speak(String s) {
        Log.i(TAG, "say: " + s);
        if (!voiceOn || !ttsReady) return;
        try {
            if (voiceOnGlasses && !link.clients.isEmpty()) {
                java.io.File f = new java.io.File(getCacheDir(), "say" + (clipSeq++ % 4) + ".wav");
                Bundle p = new Bundle();
                int r = tts.synthesizeToFile(s, p, f, "g:" + f.getAbsolutePath());
                if (r == TextToSpeech.SUCCESS) return;
            }
            tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "q" + SystemClock.elapsedRealtime());
        } catch (Exception e) {
            Log.w(TAG, "speak: " + e);
        }
    }

    private final android.speech.tts.UtteranceProgressListener utterances =
            new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    if (id != null && id.startsWith("q")) {
                        try {
                            audio.requestAudioFocus(focusReq);
                        } catch (Exception ignored) {
                        }
                    }
                }

                @Override
                public void onDone(String id) {
                    if (id == null) return;
                    if (id.startsWith("q")) {
                        releaseFocus();
                    } else if (id.startsWith("g:")) {
                        sendClip(new java.io.File(id.substring(2)));
                    }
                }

                @Override
                public void onError(String id) {
                    if (id != null && id.startsWith("q")) releaseFocus();
                }
            };

    private void releaseFocus() {
        try {
            audio.abandonAudioFocusRequest(focusReq);
        } catch (Exception ignored) {
        }
    }

    /** Sends a synthesized prompt to the glasses as 16-bit mono PCM (downsampled to keep it small). */
    private void sendClip(java.io.File f) {
        try {
            byte[] wav = java.nio.file.Files.readAllBytes(f.toPath());
            Wav.Pcm pcm = Wav.parse(wav);
            if (pcm == null) return;
            pcm = Wav.shrink(pcm, 12000);
            String b64 = android.util.Base64.encodeToString(pcm.data, android.util.Base64.NO_WRAP);
            link.sendOnce("{\"type\":\"say\",\"rate\":" + pcm.rate + ",\"pcm\":\"" + b64 + "\"}");
        } catch (Throwable t) {
            Log.w(TAG, "clip: " + t);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String idleTitle() {
        return L.t("骑行导航运行中", "Bike Nav is running");
    }

    private static String idleText() {
        return L.t("眼镜可通过热点或蓝牙连接", "Glasses connect by hotspot or Bluetooth");
    }

    /** "前方250米，" / "In 250 meters, ". */
    private static String ahead(double m) {
        return L.en ? "In " + roundSay(m) + ", " : "前方" + roundSay(m) + "，";
    }

    /** Spoken total distance. */
    private static String sayDist(double m) {
        return m >= 1000 ? String.format(Locale.US, "%.1f kilometers", m / 1000) : Math.round(m / 10.0) * 10 + " meters";
    }

    /** English reads better mid-sentence in lower case ("In 50 meters, turn left"). */
    private static String lc(String s) {
        if (!L.en || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String roundSay(double m) {
        if (m >= 1000) return String.format(Locale.US, "%.1f", m / 1000) + L.t("公里", " kilometers");
        long v = m >= 100 ? Math.round(m / 50.0) * 50 : Math.max(10, Math.round(m / 10.0) * 10);
        return v + L.t("米", " meters");
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
