package com.qixing.hud.phone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class Http {
    // Generic client name; the free OSM services ask for an identifying User-Agent.
    static final String UA = "QixingHUD/1.0 (free bike navigation for AR glasses)";

    private Http() {}

    /** Test hook: behave as if there were no network. */
    static volatile boolean offline;

    static String get(String url) throws IOException {
        if (offline) throw new IOException("offline (test)");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language",
                com.qixing.hud.common.L.en ? "en-US,en;q=0.9" : "zh-CN,zh;q=0.9,en;q=0.8");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
