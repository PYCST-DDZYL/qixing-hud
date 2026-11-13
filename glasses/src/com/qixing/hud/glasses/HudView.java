package com.qixing.hud.glasses;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import com.qixing.hud.common.Geo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 480x640 monochrome-green HUD: turn arrow, distance, road, heading-up route map, speed/ETA. */
final class HudView extends View {
    private static final int G = Color.rgb(0, 255, 80);
    private static final int GD = Color.rgb(0, 120, 40);
    private static final int GDD = Color.rgb(0, 60, 20);

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final double[] xy = new double[2];
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private boolean zoomIn;

    HudView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        txt.setColor(G);
    }

    void toggleZoom() {
        zoomIn = !zoomIn;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float sx = getWidth() / 480f, sy = getHeight() / 640f;
        int sc = c.save();
        try {
            c.scale(sx, sy);
            HudService svc = HudService.I;
            if (svc == null) {
                drawCentered(c, "正在启动…", 320, 36, G, true);
            } else {
                // Copy under the lock, draw without it, so drawing never blocks the receiver thread.
                HudState s = svc.st.snap();
                if (!s.connected) drawWaiting(c, svc.localIp);
                else if (s.nav && s.arr) drawArrived(c, s);
                else if (s.nav) drawNav(c, s);
                else drawIdle(c, s);
            }
        } catch (Throwable t) {
            // Never let one bad frame close the HUD; the next frame gets fresh data.
            android.util.Log.w("QixingHud", "draw: " + t);
        } finally {
            c.restoreToCount(sc);
        }
        postInvalidateDelayed(1000);
    }

    // ------------------------------------------------------------------ screens

    private void drawWaiting(Canvas c, String ip) {
        drawCentered(c, "骑行导航", 120, 50, G, true);
        drawCentered(c, "等待连接手机", 190, 34, G, false);
        float y = 270;
        String[] lines = {
                "1. 手机打开「骑行导航」",
                "2. 手机开热点，眼镜连上热点",
                "   或手机打开蓝牙（已配对即可）",
        };
        for (String l : lines) {
            drawLeft(c, l, 30, y, 26, GD, false);
            y += 42;
        }
        y += 20;
        drawLeft(c, "手机端下载：", 30, y, 24, GD, false);
        y += 38;
        if (ip != null && !ip.isEmpty()) {
            drawLeft(c, "浏览器打开", 30, y, 24, GD, false);
            y += 40;
            drawLeft(c, "http://" + ip + ":47802", 30, y, 28, G, true);
        } else {
            drawLeft(c, "眼镜先连上 Wi-Fi 或热点后显示地址", 30, y, 22, GD, false);
        }
        drawClock(c);
    }

    private void drawIdle(Canvas c, HudState s) {
        drawStatus(c, s);
        drawCentered(c, "骑行导航", 170, 50, G, true);
        drawCentered(c, "已连接手机", 230, 32, G, false);
        drawCentered(c, "在手机上搜索目的地并开始导航", 280, 24, GD, false);
        if (s.hasPos) {
            String spd = String.valueOf(Math.round(s.spd * 3.6));
            drawCentered(c, spd, 450, 110, G, true);
            drawCentered(c, "km/h", 500, 28, GD, false);
        }
        drawCentered(c, hm.format(new Date()), 600, 34, GD, false);
    }

    private void drawArrived(Canvas c, HudState s) {
        drawStatus(c, s);
        drawManeuver(c, "arrive", 0, new RectF(160, 90, 320, 250));
        drawCentered(c, "已到达目的地", 340, 44, G, true);
        String d = s.dest.isEmpty() ? s.routeDest : s.dest;
        drawCentered(c, ellipsize(d, 440, 30), 400, 30, GD, false);
        drawCentered(c, hm.format(new Date()), 600, 34, GD, false);
    }

    private void drawNav(Canvas c, HudState s) {
        drawStatus(c, s);

        // Turn arrow.
        drawManeuver(c, s.man, s.exit, new RectF(14, 40, 174, 200));

        // Distance to the turn.
        String[] du = distParts(s.mdist);
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextSize(s.mdist >= 10000 ? 72 : 92);
        txt.setColor(G);
        float x = 190;
        c.drawText(du[0], x, 125, txt);
        float w = txt.measureText(du[0]);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setTextSize(38);
        c.drawText(du[1], x + w + 8, 125, txt);

        // Action and road.
        String act = s.mdist > 2000 && "straight".equals(s.man) ? "沿当前道路直行" : s.act;
        drawLeft(c, ellipsize(act, 280, 40), x, 185, 40, G, true);
        drawLeft(c, ellipsize(s.road, 452, 32), 16, 248, 32, G, false);

        // Map.
        drawMap(c, s, new RectF(0, 268, 480, 568));

        // Message overlay (rerouting etc.).
        if (!s.msg.isEmpty()) {
            fill.setColor(Color.BLACK);
            c.drawRect(0, 272, 480, 318, fill);
            drawCentered(c, ellipsize(s.msg, 460, 28), 306, 28, G, true);
        }

        // Bottom bar.
        fill.setColor(GDD);
        c.drawRect(0, 572, 480, 574, fill);
        String spd = String.valueOf(Math.round(s.spd * 3.6));
        txt.setTypeface(Typeface.DEFAULT_BOLD);
        txt.setTextSize(46);
        txt.setColor(G);
        c.drawText(spd, 16, 625, txt);
        float sw = txt.measureText(spd);
        txt.setTypeface(Typeface.DEFAULT);
        txt.setTextSize(20);
        c.drawText("km/h", 16 + sw + 6, 625, txt);
        txt.setTextSize(28);
        txt.setTypeface(Typeface.DEFAULT);
        String rem = Geo.fmtDist(s.rem).replace(" ", "");
        c.drawText(rem, 180, 622, txt);
        String eta = s.eta > 0 ? hm.format(new Date(s.eta)) + "到" : "";
        float ew = txt.measureText(eta);
        c.drawText(eta, 470 - ew, 622, txt);
    }

    // ------------------------------------------------------------------ parts

    private void drawStatus(Canvas c, HudState s) {
        txt.setTypeface(Typeface.DEFAULT);
        txt.setTextSize(20);
        txt.setColor(GD);
        String k = "bt".equals(s.kind) ? "蓝牙" : "热点";
        String left = "● " + k + (s.sim ? " · 模拟" : "") + (s.gps ? "" : " · GPS信号弱");
        c.drawText(left, 14, 26, txt);
        String t = hm.format(new Date());
        c.drawText(t, 466 - txt.measureText(t), 26, txt);
    }

    private void drawClock(Canvas c) {
        txt.setTypeface(Typeface.DEFAULT);
        txt.setTextSize(22);
        txt.setColor(GD);
        String t = hm.format(new Date());
        c.drawText(t, 466 - txt.measureText(t), 30, txt);
    }

    private void drawMap(Canvas c, HudState s, RectF area) {
        int n = s.rlat.length;
        if (n < 2) return;
        boolean fresh = s.srid == s.rid; // a new route can arrive a moment before the matching state
        c.save();
        c.clipRect(area);
        float cx = area.centerX(), cy = area.top + area.height() * 0.78f;
        double mpp = zoomIn ? 0.55 : (s.mdist < 250 ? 0.8 : 1.6); // metres per pixel
        double b = Math.toRadians(s.brg);
        double cb = Math.cos(b), sb = Math.sin(b);
        double maxR = Math.max(area.width(), area.height()) * mpp * 1.3;

        // Traveled part (dim) ends at my position; the rest (bright) starts there.
        int split = fresh ? Math.max(0, Math.min(n - 1, s.seg + 1)) : 0;
        drawPolyline(c, s, 0, split, s.lat, s.lon, cx, cy, mpp, cb, sb, maxR, GDD, 10, -1, cx, cy);
        drawPolyline(c, s, split, n, s.lat, s.lon, cx, cy, mpp, cb, sb, maxR, G, 12, 1, cx, cy);

        // Upcoming maneuver points only.
        stroke.setColor(G);
        stroke.setStrokeWidth(4);
        for (int i = 0; i < s.slat.length; i++) {
            if (fresh && i < s.salong.length && s.salong[i] < s.along - 2) continue;
            Geo.toXY(s.lat, s.lon, s.slat[i], s.slon[i], xy);
            if (Math.hypot(xy[0], xy[1]) > maxR) continue;
            float px = (float) (cx + (xy[0] * cb - xy[1] * sb) / mpp);
            float py = (float) (cy - (xy[0] * sb + xy[1] * cb) / mpp);
            boolean isNext = Math.abs(s.slat[i] - s.mlat) < 1e-6 && Math.abs(s.slon[i] - s.mlon) < 1e-6;
            if (isNext) {
                fill.setColor(G);
                c.drawCircle(px, py, 11, fill);
            } else {
                c.drawCircle(px, py, 7, stroke);
            }
        }

        // Me.
        path.reset();
        path.moveTo(cx, cy - 22);
        path.lineTo(cx + 15, cy + 14);
        path.lineTo(cx, cy + 6);
        path.lineTo(cx - 15, cy + 14);
        path.close();
        fill.setColor(Color.BLACK);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeWidth(8);
        c.drawPath(path, stroke);
        fill.setColor(G);
        c.drawPath(path, fill);

        // Scale hint.
        txt.setTypeface(Typeface.DEFAULT);
        txt.setTextSize(18);
        txt.setColor(GDD);
        c.drawText(Math.round(100 / mpp) + "px=100m", area.left + 8, area.bottom - 8, txt);
        c.restore();
    }

    /** joinMe: 1 = start the line at my position, -1 = end it at my position, 0 = neither. */
    private void drawPolyline(Canvas c, HudState s, int from, int to, double la0, double lo0, float cx, float cy,
                              double mpp, double cb, double sb, double maxR, int color, float width,
                              int joinMe, float mx, float my) {
        path.reset();
        boolean started = false;
        if (joinMe == 1) {
            path.moveTo(mx, my);
            started = true;
        }
        for (int i = from; i < to; i++) {
            Geo.toXY(la0, lo0, s.rlat[i], s.rlon[i], xy);
            double r = Math.hypot(xy[0], xy[1]);
            if (r > maxR * 3) {
                started = false;
                continue;
            }
            float px = (float) (cx + (xy[0] * cb - xy[1] * sb) / mpp);
            float py = (float) (cy - (xy[0] * sb + xy[1] * cb) / mpp);
            if (!started) {
                path.moveTo(px, py);
                started = true;
            } else {
                path.lineTo(px, py);
            }
        }
        if (joinMe == -1 && started) path.lineTo(mx, my);
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        c.drawPath(path, stroke);
    }

    /** Big turn arrow inside r. */
    private void drawManeuver(Canvas c, String code, int exit, RectF r) {
        stroke.setColor(G);
        fill.setColor(G);
        float cx = r.centerX();
        float bottom = r.bottom - 6;
        float L = r.height() * 0.40f;
        float sw = r.width() * 0.13f;
        stroke.setStrokeWidth(sw);
        path.reset();
        if (code == null) code = "";
        switch (code) {
            case "arrive": {
                // flag
                float px = cx - 30;
                stroke.setStrokeWidth(sw * 0.6f);
                c.drawLine(px, r.top + 10, px, bottom, stroke);
                path.moveTo(px, r.top + 12);
                path.lineTo(px + 80, r.top + 40);
                path.lineTo(px, r.top + 70);
                path.close();
                c.drawPath(path, fill);
                return;
            }
            case "uturn": {
                float rx = cx + 22, lx = cx - 30, top = r.top + 30;
                path.moveTo(rx, bottom);
                path.lineTo(rx, top + 26);
                path.arcTo(new RectF(lx, top, rx, top + 52), 0, -180, false);
                path.lineTo(lx, r.centerY() + 10);
                c.drawPath(path, stroke);
                head(c, lx, r.centerY() + 10, 180, sw * 1.7f);
                return;
            }
            case "roundabout": {
                float rr = r.width() * 0.2f, ccy = r.top + rr + 22;
                stroke.setStrokeWidth(sw * 0.7f);
                c.drawCircle(cx, ccy, rr, stroke);
                stroke.setStrokeWidth(sw);
                c.drawLine(cx, bottom, cx, ccy + rr, stroke);
                float ex = cx + rr * 0.7f, ey = ccy - rr * 0.7f;
                c.drawLine(ex, ey, ex + 26, ey - 26, stroke);
                head(c, ex + 26, ey - 26, 45, sw * 1.6f);
                if (exit > 0) {
                    txt.setTypeface(Typeface.DEFAULT_BOLD);
                    txt.setTextSize(rr * 1.1f);
                    txt.setColor(G);
                    String e = String.valueOf(exit);
                    c.drawText(e, cx - txt.measureText(e) / 2, ccy + rr * 0.4f, txt);
                }
                return;
            }
            default:
                break;
        }
        float a;
        switch (code) {
            case "left": a = -90; break;
            case "right": a = 90; break;
            case "sharp-left": a = -135; break;
            case "sharp-right": a = 135; break;
            case "slight-left": a = -42; break;
            case "slight-right": a = 42; break;
            default: a = 0; break;
        }
        float ox = cx - a / 90f * 26f;
        float mid = r.top + r.height() * 0.52f;
        double ar = Math.toRadians(a);
        float ex = (float) (ox + L * Math.sin(ar));
        float ey = (float) (mid - L * Math.cos(ar));
        if (a == 0) ey = r.top + 34;
        path.moveTo(ox, bottom);
        path.lineTo(ox, mid);
        path.lineTo(ex, ey);
        c.drawPath(path, stroke);
        head(c, ex, ey, a, sw * 1.8f);
    }

    /** Filled arrow head with its tip beyond (x,y) in direction a (degrees, 0 = up). */
    private void head(Canvas c, float x, float y, float a, float size) {
        double ar = Math.toRadians(a);
        float dx = (float) Math.sin(ar), dy = (float) -Math.cos(ar);
        float px = -dy, py = dx;
        Path p = new Path();
        p.moveTo(x + dx * size, y + dy * size);
        p.lineTo(x + px * size, y + py * size);
        p.lineTo(x - px * size, y - py * size);
        p.close();
        c.drawPath(p, fill);
    }

    private String[] distParts(double m) {
        if (m < 1000) {
            long v = m < 100 ? Math.round(m) : Math.round(m / 10.0) * 10;
            return new String[]{String.valueOf(v), "米"};
        }
        return new String[]{String.format(Locale.US, m < 10000 ? "%.1f" : "%.0f", m / 1000.0), "公里"};
    }

    private String ellipsize(String s, float maxW, float size) {
        if (s == null) return "";
        txt.setTextSize(size);
        if (s.length() > 200) s = s.substring(0, 200);
        if (txt.measureText(s) <= maxW) return s;
        String e = "…";
        // One pass (breakText), not a char-by-char loop: long text must never stall the UI thread.
        int n = txt.breakText(s, true, Math.max(0, maxW - txt.measureText(e)), null);
        if (n > 0 && n < s.length() && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, Math.max(0, n)) + e;
    }

    private void drawCentered(Canvas c, String s, float y, float size, int color, boolean bold) {
        txt.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        txt.setTextSize(size);
        txt.setColor(color);
        c.drawText(s, 240 - txt.measureText(s) / 2, y, txt);
    }

    private void drawLeft(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        txt.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        txt.setTextSize(size);
        txt.setColor(color);
        c.drawText(s, x, y, txt);
    }
}
