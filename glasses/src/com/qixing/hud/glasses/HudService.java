package com.qixing.hud.glasses;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.qixing.hud.common.Proto;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a connection to the phone (TCP over hotspot/Wi-Fi or Bluetooth RFCOMM, whichever answers
 * first), pops the HUD up when navigation starts, and serves the phone APK over HTTP.
 */
public class HudService extends Service {
    private static final String TAG = "QixingHud";
    static volatile HudService I;

    interface Listener {
        void onHudUpdate();
    }

    static volatile Listener listener;

    final HudState st = new HudState();

    private static final class Conn {
        final Closeable sock;
        final InputStream in;
        final OutputStream out;
        final String kind;

        Conn(Closeable s, InputStream in, OutputStream out, String kind) {
            this.sock = s;
            this.in = in;
            this.out = out;
            this.kind = kind;
        }

        void close() {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private final AtomicReference<Conn> active = new AtomicReference<>();
    private volatile long lastRx;
    private volatile boolean running;
    private static final int MAX_LINE = 2 * 1024 * 1024;
    private final VoicePlayer voice = new VoicePlayer();
    private final Map<String, Long> beacons = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock mlock;
    private PowerManager pm;
    volatile String localIp = "";

    // wake / pop-up bookkeeping (main thread)
    private boolean prevNav, prevArr;
    private String lastWakeKey = "";

    @Override
    public void onCreate() {
        super.onCreate();
        I = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("hud", "骑行导航HUD", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "hud")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("骑行导航HUD")
                .setContentText("等待手机")
                .setOngoing(true)
                .build();
        startForeground(1, n);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            mlock = wm.createMulticastLock("qixing");
            mlock.setReferenceCounted(false);
            mlock.acquire();
        } catch (Exception ignored) {
        }
        running = true;
        thread("tcp", this::tcpLoop);
        thread("bt", this::btLoop);
        thread("beacon", this::beaconLoop);
        thread("watchdog", this::watchdog);
        thread("http", this::httpLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Conn c = active.get();
        if (c != null) c.close();
        if (mlock != null && mlock.isHeld()) mlock.release();
        voice.shutdown();
        I = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ connection

    private void runConn(Conn c) {
        synchronized (st) {
            st.connected = true;
            st.kind = c.kind;
        }
        lastRx = System.currentTimeMillis();
        Log.i(TAG, "connected via " + c.kind);
        if ("bt".equals(c.kind)) saveBtAddr(c);
        notifyUpdate(null);
        try {
            c.out.write("{\"type\":\"hello\",\"dev\":\"glasses\"}\n".getBytes(StandardCharsets.UTF_8));
            c.out.flush();
            InputStream in = new java.io.BufferedInputStream(c.in, 65536);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(4096);
            boolean skipping = false;
            int b;
            while (running && (b = in.read()) != -1) {
                lastRx = System.currentTimeMillis();
                if (b != '\n') {
                    // Lines over 2 MB cannot be real phone data: drop them instead of risking memory.
                    if (skipping) continue;
                    if (buf.size() >= MAX_LINE) {
                        skipping = true;
                        buf.reset();
                        continue;
                    }
                    buf.write(b);
                    continue;
                }
                if (skipping) {
                    skipping = false;
                    Log.w(TAG, "dropped oversized line");
                    continue;
                }
                String line = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                buf.reset();
                if (line.length() > 65536) buf = new java.io.ByteArrayOutputStream(4096); // release big buffer
                if (line.startsWith("{\"type\":\"say\"")) {
                    voice.play(line); // voice clips are not HUD state
                    continue;
                }
                String type = st.apply(line);
                if (type != null) notifyUpdate(type);
            }
        } catch (Throwable e) {
            Log.i(TAG, "link closed: " + e);
        } finally {
            c.close();
            active.compareAndSet(c, null);
            synchronized (st) {
                st.connected = false;
                st.kind = "";
            }
            notifyUpdate(null);
        }
    }

    private String btAddrSaved = "";

    private void saveBtAddr(Conn c) {
        try {
            String a = ((BluetoothSocket) c.sock).getRemoteDevice().getAddress();
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("bt", a).apply();
            btAddrSaved = a;
        } catch (Exception ignored) {
        }
    }

    private List<String> tcpCandidates() {
        Set<String> c = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : beacons.entrySet()) {
            if (now - e.getValue() < 15000) c.add(e.getKey());
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int gw = wm.getDhcpInfo().gateway;
            if (gw != 0) {
                c.add((gw & 0xff) + "." + ((gw >> 8) & 0xff) + "." + ((gw >> 16) & 0xff) + "." + ((gw >> 24) & 0xff));
            }
        } catch (Exception ignored) {
        }
        c.add("127.0.0.1");
        return new ArrayList<>(c);
    }

    private void tcpLoop() {
        while (running) {
            if (active.get() != null) {
                sleep(1000);
                continue;
            }
            localIp = findLocalIp();
            for (String ip : tcpCandidates()) {
                if (active.get() != null || !running) break;
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(ip, Proto.TCP_PORT), 1500);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(12000);
                    Conn c = new Conn(s, s.getInputStream(), s.getOutputStream(), "wifi");
                    if (active.compareAndSet(null, c)) runConn(c);
                    else c.close();
                } catch (Exception e) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            sleep(2000);
        }
    }

    private void btLoop() {
        btAddrSaved = getSharedPreferences("p", MODE_PRIVATE).getString("bt", "");
        while (running) {
            if (active.get() != null) {
                sleep(1000);
                continue;
            }
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null || !ad.isEnabled()) {
                    sleep(5000);
                    continue;
                }
                List<BluetoothDevice> devs = new ArrayList<>(ad.getBondedDevices());
                // Try the phone that worked last time first.
                Collections.sort(devs, (a, b) -> Boolean.compare(!a.getAddress().equals(btAddrSaved),
                        !b.getAddress().equals(btAddrSaved)));
                for (BluetoothDevice d : devs) {
                    if (active.get() != null || !running) break;
                    BluetoothSocket s = null;
                    try {
                        s = d.createInsecureRfcommSocketToServiceRecord(Proto.BT_UUID);
                        s.connect();
                        Conn c = new Conn(s, s.getInputStream(), s.getOutputStream(), "bt");
                        if (active.compareAndSet(null, c)) runConn(c);
                        else c.close();
                    } catch (Exception e) {
                        if (s != null) {
                            try {
                                s.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "bt permission missing");
            } catch (Exception e) {
                Log.w(TAG, "bt: " + e);
            }
            sleep(3000);
        }
    }

    private void beaconLoop() {
        while (running) {
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(Proto.BEACON_PORT));
                byte[] buf = new byte[256];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (s.startsWith(Proto.BEACON_PREFIX)) {
                        beacons.put(p.getAddress().getHostAddress(), System.currentTimeMillis());
                    }
                }
            } catch (Exception e) {
                sleep(3000);
            }
        }
    }

    private void watchdog() {
        long lastWifiCheck = 0;
        while (running) {
            Conn c = active.get();
            long now = System.currentTimeMillis();
            if (c != null && now - lastRx > 9000) {
                Log.i(TAG, "no data from phone, reconnecting");
                c.close();
            }
            // The glasses switch Wi-Fi off to save power; we need it to reach the phone's hotspot.
            if (c == null && now - lastWifiCheck > 20000) {
                lastWifiCheck = now;
                keepWifiOn();
            }
            sleep(1000);
        }
    }

    @SuppressWarnings("deprecation")
    private void keepWifiOn() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (!wm.isWifiEnabled()) {
                boolean ok = wm.setWifiEnabled(true); // allowed because targetSdk is 28
                Log.i(TAG, "wifi was off, enabling: " + ok);
            }
        } catch (Exception e) {
            Log.w(TAG, "wifi: " + e);
        }
    }

    // ------------------------------------------------------------------ phone APK download

    private void httpLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(Proto.HTTP_PORT));
                while (running) {
                    Socket s = ss.accept();
                    thread("http-client", () -> serve(s));
                }
            } catch (Exception e) {
                sleep(3000);
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(10000);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String req = br.readLine();
            String l;
            while ((l = br.readLine()) != null && !l.isEmpty()) {
                // skip headers
            }
            OutputStream out = s.getOutputStream();
            String path = req == null ? "/" : req.split(" ").length > 1 ? req.split(" ")[1] : "/";
            if (path.startsWith("/qixing-phone.apk")) {
                byte[] apk;
                try (InputStream in = getAssets().open("phone.apk")) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int r;
                    while ((r = in.read(b)) > 0) bo.write(b, 0, r);
                    apk = bo.toByteArray();
                }
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                        + "Content-Disposition: attachment; filename=\"qixing-phone.apk\"\r\n"
                        + "Content-Length: " + apk.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(apk);
            } else {
                String html = "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<title>骑行导航 手机端</title></head>"
                        + "<body style='font-family:sans-serif;background:#111;color:#eee;padding:24px'>"
                        + "<h2 style='color:#5e7'>骑行导航 · 手机端</h2>"
                        + "<p>完全免费，不需要账号或密钥。</p>"
                        + "<p><a style='display:inline-block;background:#2a6;color:#fff;padding:16px 24px;"
                        + "border-radius:10px;text-decoration:none;font-size:20px' href='/qixing-phone.apk'>下载安装手机端</a></p>"
                        + "<p>下载后点开安装。若提示被拦截，请临时关闭三星“自动拦截器”，装完再打开。</p>"
                        + "</body></html>";
                byte[] b = html.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(b);
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    static String findLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ------------------------------------------------------------------ pop-up and wake

    private void notifyUpdate(String type) {
        main.post(() -> {
            boolean nav, arr;
            String key;
            double md;
            synchronized (st) {
                nav = st.nav;
                arr = st.arr;
                md = st.mdist;
                key = st.srid + ":" + st.mlat + ":" + st.mlon;
            }
            if (nav && !prevNav) {
                wake();
                showHud();
            }
            if (nav && md < WAKE_DIST && !key.equals(lastWakeKey)) {
                lastWakeKey = key;
                wake();
                if (!HudActivity.visible) showHud();
            }
            if (arr && !prevArr) wake();
            prevNav = nav;
            prevArr = arr;
            Listener l = listener;
            if (l != null) l.onHudUpdate();
        });
    }

    /** Light the screen this far before a turn (about 35 s at 25 km/h). */
    static final double WAKE_DIST = 250;
    /** In power-saving mode the screen may sleep while the next turn is farther than this. */
    static final double ECO_DIST = 400;

    private void showHud() {
        try {
            Intent i = new Intent(this, HudActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "cannot open HUD: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private void wake() {
        try {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "qixing:wake");
            wl.acquire(10000);
        } catch (Exception ignored) {
        }
    }

    private void thread(String name, Runnable r) {
        // A worker that dies from an unexpected error would kill the whole process; restart it instead.
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    r.run();
                    return;
                } catch (Throwable e) {
                    Log.w(TAG, name + " restarted after: " + e);
                    sleep(2000);
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
