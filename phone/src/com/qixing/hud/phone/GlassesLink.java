package com.qixing.hud.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import com.qixing.hud.common.Proto;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
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

    final class Client {
        final Closeable sock;
        final OutputStream out;
        final String kind;

        Client(Closeable sock, OutputStream out, String kind) {
            this.sock = sock;
            this.out = out;
            this.kind = kind;
        }

        synchronized boolean write(byte[] b) {
            try {
                out.write(b);
                out.flush();
                return true;
            } catch (Exception e) {
                close();
                return false;
            }
        }

        void close() {
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
        broadcast(line);
    }

    void sendState(String line) {
        stateLine = line;
        broadcast(line);
    }

    private void broadcast(String line) {
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) c.write(b);
    }

    private void onClient(Closeable sock, OutputStream out, java.io.InputStream in, String kind) {
        Client c = new Client(sock, out, kind);
        clients.add(c);
        Log.i(TAG, "glasses connected (" + kind + ")");
        String r = routeLine, s = stateLine;
        c.write("{\"type\":\"hello\",\"v\":1}\n".getBytes(StandardCharsets.UTF_8));
        if (r != null) c.write((r + "\n").getBytes(StandardCharsets.UTF_8));
        if (s != null) c.write((s + "\n").getBytes(StandardCharsets.UTF_8));
        // Drain whatever the glasses send so we notice when the socket dies.
        thread("rx-" + kind, () -> {
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                while (running && br.readLine() != null) {
                    // pings only
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
                    btError = "手机没有蓝牙";
                    return;
                }
                if (!ad.isEnabled()) {
                    btError = "蓝牙未打开";
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
                btError = "没有蓝牙权限";
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
