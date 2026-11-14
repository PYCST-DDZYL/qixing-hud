package com.qixing.hud.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.qixing.hud.common.L;
import com.qixing.hud.common.Proto;

import java.io.Closeable;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Serves the glasses over two transports at once: TCP (phone hotspot / same Wi-Fi, announced by
 * UDP broadcast beacons) and Bluetooth RFCOMM (works with the glasses' existing pairing).
 */
final class GlassesLink {
    private static final String TAG = "QixingLink";

    /**
     * One connected pair of glasses. Writes happen on the client's own sender thread, never on the
     * caller's: a stalled socket (weak Wi-Fi, half-dead Bluetooth) must not freeze navigation.
     * Only the newest state is kept; routes and voice clips queue in order.
     */
    final class Client {
        final Closeable sock;
        final OutputStream out;
        final String kind;
        private final Object lock = new Object();
        private final java.util.ArrayDeque<byte[]> queue = new java.util.ArrayDeque<>();
        private byte[] latestState;
        private volatile boolean closed;
        private volatile long writingSince; // 0 when idle

        Client(Closeable sock, OutputStream out, String kind) {
            this.sock = sock;
            this.out = out;
            this.kind = kind;
            thread("tx-" + kind, this::senderLoop);
        }

        void enqueue(byte[] b) {
            boolean dead;
            synchronized (lock) {
                // A client this far behind is effectively gone. Dropping it lets the glasses
                // reconnect and get the current route and state replayed, instead of losing a route.
                dead = queue.size() >= 16;
                if (!dead) {
                    queue.addLast(b);
                    lock.notifyAll();
                }
            }
            if (dead) close();
        }

        void setState(byte[] b) {
            synchronized (lock) {
                latestState = b;
                lock.notifyAll();
            }
        }

        /** True if a single write has been stuck long enough that the link is effectively dead. */
        boolean stalled() {
            long w = writingSince;
            return w != 0 && System.currentTimeMillis() - w > 10000;
        }

        private void senderLoop() {
            try {
                while (!closed) {
                    byte[] b;
                    synchronized (lock) {
                        while (!closed && queue.isEmpty() && latestState == null) lock.wait(2000);
                        if (closed) break;
                        if (!queue.isEmpty()) {
                            b = queue.pollFirst();
                        } else {
                            b = latestState;
                            latestState = null;
                        }
                    }
                    writingSince = System.currentTimeMillis();
                    out.write(b);
                    out.flush();
                    writingSince = 0;
                }
            } catch (Exception ignored) {
            }
            close();
        }

        void close() {
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            try {
                sock.close();
            } catch (Exception ignored) {
            }
            if (clients.remove(this)) Log.i(TAG, "glasses disconnected (" + kind + ")");
        }
    }

    final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile String routeLine, stateLine;
    private ServerSocket tcpServer;
    private BluetoothServerSocket btServer;
    volatile String btError = "";

    void start() {
        running = true;
        thread("tcp-accept", this::tcpLoop);
        thread("bt-accept", this::btLoop);
        thread("beacon", this::beaconLoop);
    }

    void stop() {
        running = false;
        closeQuietly(tcpServer);
        closeQuietly(btServer);
        for (Client c : clients) c.close();
    }

    int count(String kind) {
        int n = 0;
        for (Client c : clients) if (c.kind.equals(kind)) n++;
        return n;
    }

    void sendRoute(String line) {
        routeLine = line;
        byte[] b = bytes(line);
        for (Client c : clients) c.enqueue(b);
    }

    void sendState(String line) {
        stateLine = line;
        byte[] b = bytes(line);
        for (Client c : clients) {
            if (c.stalled()) c.close();
            else c.setState(b);
        }
    }

    /** One-off message (voice clip, ping): delivered in order, not replayed to later clients. */
    void sendOnce(String line) {
        byte[] b = bytes(line);
        for (Client c : clients) c.enqueue(b);
    }

    private static byte[] bytes(String line) {
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private void onClient(Closeable sock, OutputStream out, java.io.InputStream in, String kind) {
        if (clients.size() >= 4) { // one pair of glasses needs one; refuse connection floods
            closeQuietly(sock);
            return;
        }
        Client c = new Client(sock, out, kind);
        clients.add(c);
        Log.i(TAG, "glasses connected (" + kind + ")");
        String r = routeLine, s = stateLine;
        c.enqueue(bytes("{\"type\":\"hello\",\"v\":2}"));
        if (r != null) c.enqueue(bytes(r));
        if (s != null) c.setState(bytes(s));
        // Drain whatever the glasses send so we notice when the socket dies.
        thread("rx-" + kind, () -> {
            // Read and discard without buffering lines: anything on the same Wi-Fi can connect, and
            // an endless "line" must not be able to run the phone out of memory.
            byte[] sink = new byte[1024];
            try {
                while (running && in.read(sink) >= 0) {
                    // the glasses only send a hello
                }
            } catch (Exception ignored) {
            }
            c.close();
        });
    }

    private void tcpLoop() {
        while (running) {
            try {
                tcpServer = new ServerSocket();
                tcpServer.setReuseAddress(true);
                tcpServer.bind(new java.net.InetSocketAddress(Proto.TCP_PORT));
                while (running) {
                    Socket s = tcpServer.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    onClient(s, s.getOutputStream(), s.getInputStream(), "wifi");
                }
            } catch (Exception e) {
                Log.w(TAG, "tcp server: " + e);
            }
            closeQuietly(tcpServer);
            sleep(3000);
        }
    }

    private void btLoop() {
        while (running) {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) {
                    btError = L.t("手机没有蓝牙", "no Bluetooth on this phone");
                    return;
                }
                if (!ad.isEnabled()) {
                    btError = L.t("蓝牙未打开", "Bluetooth is off");
                    sleep(4000);
                    continue;
                }
                btServer = ad.listenUsingInsecureRfcommWithServiceRecord(Proto.BT_NAME, Proto.BT_UUID);
                btError = "";
                while (running) {
                    BluetoothSocket s = btServer.accept();
                    onClient(s, s.getOutputStream(), s.getInputStream(), "bt");
                }
            } catch (SecurityException e) {
                btError = L.t("没有蓝牙权限", "Bluetooth permission missing");
                sleep(5000);
            } catch (Exception e) {
                if (running) Log.w(TAG, "bt server: " + e);
                sleep(3000);
            }
            closeQuietly(btServer);
        }
    }

    private void beaconLoop() {
        byte[] msg = (Proto.BEACON_PREFIX + Proto.TCP_PORT).getBytes(StandardCharsets.UTF_8);
        DatagramSocket ds = null;
        while (running) {
            try {
                if (ds == null) {
                    ds = new DatagramSocket();
                    ds.setBroadcast(true);
                }
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress bc = ia.getBroadcast();
                        if (bc == null) continue;
                        try {
                            ds.send(new DatagramPacket(msg, msg.length, bc, Proto.BEACON_PORT));
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                closeQuietly(ds);
                ds = null;
            }
            sleep(2000);
        }
        closeQuietly(ds);
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
