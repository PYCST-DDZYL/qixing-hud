package com.qixing.hud.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Typeface;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.qixing.hud.common.Geo;
import com.qixing.hud.common.L;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText query;
    private ListView list;
    private BaseAdapter adapter;
    private Location nearLoc;
    private final List<Search.Place> places = new ArrayList<>();
    private TextView statusText, info, navBig, navSmall, stopBtn, settingsBtn;
    private GradientDrawable statusDot;
    private LinearLayout searchRow, settingsPanel;
    private View navSpacer, settingsSpacer;
    private boolean settingsOpen, simMode, wasNav;
    private CheckBox voice;
    private LinearLayout navPanel;
    private Route pending;
    private Search.Place pendingPlace;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private boolean serviceStarted;
    private boolean testHandled;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // adb test hooks: --es lang en|zh|auto picks the language, --ez offline true fails all requests.
        String testLang = getIntent().getStringExtra("lang");
        if (testLang != null) getSharedPreferences("p", MODE_PRIVATE).edit().putString("lang", testLang).commit();
        Http.offline = getIntent().getBooleanExtra("offline", false);
        L.apply(getSharedPreferences("p", MODE_PRIVATE).getString("lang", "auto"));
        buildUi();
        requestPerms();
        ui.post(refresh);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    // Colours shared by the whole screen: dark surfaces with the HUD's green as the accent.
    private static final int BG = 0xFF0D1012, SURFACE = 0xFF171C20, LINE = 0xFF2B343A,
            GREEN = 0xFF3DDC84, ON_GREEN = 0xFF06210F, TEXT = 0xFFF2F5F6, TEXT2 = 0xFF9AA5AA,
            AMBER = 0xFFFFC857, RED = 0xFFFF6B6B, GREY = 0xFF5A656B;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A rounded button: filled when stroke is 0, outlined otherwise. */
    private TextView button(String label, int fill, int textColor, int stroke, int minHeightDp, View.OnClickListener l) {
        TextView b = text(15, textColor, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable g = shape(fill, 14);
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(minHeightDp));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
    }

    private LinearLayout card(int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(color, 18));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.topMargin = dp(topDp);
        return p;
    }

    /** "● GPS   fixed, accuracy 5 m": a coloured dot, a fixed-width label and the value. */
    private LinearLayout statusRow(GradientDrawable dot, String label, TextView value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GREY);
        View d = new View(this);
        d.setBackground(dot);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9));
        dl.topMargin = dp(6);
        dl.rightMargin = dp(10);
        r.addView(d, dl);
        TextView lab = text(14, TEXT2, false);
        lab.setText(label);
        r.addView(lab, new LinearLayout.LayoutParams(dp(L.en ? 62 : 44), -2));
        r.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        return r;
    }

    private CheckBox option(String label, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(14);
        c.setButtonTintList(ColorStateList.valueOf(GREEN));
        c.setChecked(on);
        c.setOnCheckedChangeListener(l);
        return c;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        SharedPreferences prefs = getSharedPreferences("p", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(8));
        root.setBackgroundColor(BG);
        // Keep focus off the search box so the keyboard does not cover the results on launch.
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        // ---- header: title and the settings button
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(26, TEXT, true);
        title.setText(L.t("骑行导航", "Bike Nav HUD"));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        settingsBtn = button(settingsOpen ? L.t("完成", "Done") : L.t("设置", "Settings"), 0, GREEN, LINE, 36, v -> {
            settingsOpen = !settingsOpen;
            settingsBtn.setText(settingsOpen ? L.t("完成", "Done") : L.t("设置", "Settings"));
            setButtons();
        });
        top.addView(settingsBtn);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // ---- one status line: glasses and GPS
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setBackground(shape(SURFACE, 14));
        st.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusDot = new GradientDrawable();
        statusDot.setShape(GradientDrawable.OVAL);
        statusDot.setColor(GREY);
        View dot = new View(this);
        dot.setBackground(statusDot);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.rightMargin = dp(10);
        st.addView(dot, dl);
        statusText = text(14, TEXT, false);
        st.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(st, lp(-1, -2, 0, 12));

        // ---- while riding: the next turn and one big Stop button
        navPanel = new LinearLayout(this);
        navPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ng = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1F8A4C, 0xFF0F5132});
        ng.setCornerRadius(dp(18));
        navPanel.setBackground(ng);
        navPanel.setPadding(dp(18), dp(18), dp(18), dp(18));
        navBig = text(34, Color.WHITE, true);
        navSmall = text(16, 0xFFD8F5E3, false);
        navSmall.setLineSpacing(dp(3), 1f);
        navPanel.addView(navBig);
        navPanel.addView(navSmall, lp(-1, -2, 0, 6));
        TextView glassesNote = text(13, 0xFFA8E6C1, false);
        glassesNote.setText(L.t("眼镜上同步显示转向", "Turns are shown on your glasses"));
        navPanel.addView(glassesNote, lp(-1, -2, 0, 12));
        root.addView(navPanel, lp(-1, -2, 0, 12));
        stopBtn = button(L.t("结束导航", "Stop navigation"), 0, RED, RED, 56, v -> {
            if (NavService.I != null) NavService.I.stopNav();
        });
        root.addView(stopBtn, lp(-1, -2, 0, 14));
        View navSpace = new View(this);
        navSpacer = navSpace;
        root.addView(navSpace, lp(-1, 0, 1, 0));

        // ---- not riding: search and places; tapping a place starts the ride
        searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable sg = shape(SURFACE, 16);
        sg.setStroke(dp(1), LINE);
        searchRow.setBackground(sg);
        searchRow.setPadding(dp(14), dp(4), dp(4), dp(4));
        query = new EditText(this);
        query.setHint(L.t("要去哪里？", "Where to?"));
        query.setSingleLine(true);
        query.setTextSize(17);
        query.setTextColor(TEXT);
        query.setHintTextColor(GREY);
        query.setBackground(null);
        query.setPadding(0, dp(10), dp(8), dp(10));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        searchRow.addView(button(L.t("搜索", "Search"), GREEN, ON_GREEN, 0, 44, v -> doSearch()));
        root.addView(searchRow, lp(-1, -2, 0, 12));

        info = text(14, TEXT2, false);
        info.setLineSpacing(dp(2), 1f);
        info.setPadding(dp(4), 0, dp(4), 0);
        root.addView(info, lp(-1, -2, 0, 10));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return places.size();
            }

            @Override
            public Object getItem(int pos) {
                return places.get(pos);
            }

            @Override
            public long getItemId(int pos) {
                return pos;
            }

            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                return placeRow(places.get(pos));
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> pick(places.get(pos)));
        root.addView(list, lp(-1, 0, 1, 6));

        // ---- settings (hidden until the Settings button is tapped)
        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setBackground(shape(SURFACE, 18));
        settingsPanel.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout langRow = new LinearLayout(this);
        langRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView langLabel = text(15, TEXT, false);
        langLabel.setText(L.t("语言", "Language"));
        langRow.addView(langLabel, new LinearLayout.LayoutParams(0, -2, 1));
        // Shows the language you would switch to.
        langRow.addView(button(L.en ? "中文" : "English", 0, GREEN, LINE, 36, v -> {
            String to = L.en ? "zh" : "en";
            prefs.edit().putString("lang", to).apply();
            L.apply(to);
            if (NavService.I != null) NavService.I.setLang(to);
            buildUi(); // same activity, new texts; navigation is kept
        }));
        settingsPanel.addView(langRow, lp(-1, -2, 0, 4));
        voice = option(L.t("语音播报", "Voice prompts"), prefs.getBoolean("voice", true), (c, on) -> {
            prefs.edit().putBoolean("voice", on).apply();
            if (NavService.I != null) NavService.I.setVoice(on);
        });
        settingsPanel.addView(voice, lp(-1, -2, 0, 8));
        settingsPanel.addView(option(L.t("语音在眼镜播放（关掉就用手机播放）", "Voice on the glasses (off: on the phone)"),
                prefs.getBoolean("voiceGlasses", true), (c, on) -> {
                    prefs.edit().putBoolean("voiceGlasses", on).apply();
                    if (NavService.I != null) NavService.I.setVoiceOnGlasses(on);
                }));
        settingsPanel.addView(option(L.t("眼镜省电：离路口远时可以熄屏", "Glasses power saving: screen may sleep between turns"),
                prefs.getBoolean("eco", false), (c, on) -> {
                    prefs.edit().putBoolean("eco", on).apply();
                    if (NavService.I != null) NavService.I.setEcoScreen(on);
                }));
        settingsPanel.addView(option(L.t("模拟模式：点地点后在眼镜上模拟骑一遍（在家预览用）",
                "Simulation: tapping a place plays the ride on the glasses (to preview at home)"), simMode, (c, on) -> {
            simMode = on;
            setButtons();
        }));
        TextView quit = text(15, RED, false);
        quit.setText(L.t("退出骑行导航", "Quit"));
        quit.setPadding(0, dp(14), 0, dp(10));
        quit.setOnClickListener(v -> {
            if (NavService.I != null) NavService.I.stopNav();
            ui.postDelayed(() -> {
                stopService(new Intent(this, NavService.class));
                finishAndRemoveTask();
            }, 300);
        });
        settingsPanel.addView(quit);
        TextView credit = text(11, GREY, false);
        credit.setText(L.t("完全免费，不需要账号或密钥。\n地图数据 © OpenStreetMap 贡献者 · 路线 OSRM（FOSSGIS）· 搜索 Photon / Nominatim",
                "Free, no account or API key.\nMap data © OpenStreetMap contributors · Routing: OSRM (FOSSGIS) · Search: Photon / Nominatim"));
        settingsPanel.addView(credit, lp(-1, -2, 0, 4));
        root.addView(settingsPanel, lp(-1, -2, 0, 12));
        View setSpace = new View(this);
        settingsSpacer = setSpace;
        root.addView(setSpace, lp(-1, 0, 1, 0));

        // OpenStreetMap asks for visible credit; one quiet line under everything.
        TextView osm = text(10, GREY, false);
        osm.setText("© OpenStreetMap");
        osm.setGravity(Gravity.CENTER);
        root.addView(osm, lp(-1, -2, 0, 4));

        setContentView(root);
        showRecents();
        setButtons();
        refreshStatus();
    }

    /** One search result or recent place: name and address on the left, distance on the right. */
    private View placeRow(Search.Place p) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = shape(p.resume ? 0xFF12301F : SURFACE, 14);
        if (p.resume) g.setStroke(dp(1), GREEN);
        c.setBackground(g);
        c.setPadding(dp(14), dp(11), dp(14), dp(11));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(16, p.resume ? GREEN : TEXT, true);
        name.setText((p.resume ? L.t("↻ 继续上次导航：", "↻ Continue last ride: ") : "") + p.name);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(name);
        if (!TextUtils.isEmpty(p.addr)) {
            TextView addr = text(13, TEXT2, false);
            addr.setText(p.addr);
            addr.setMaxLines(2);
            addr.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(addr, lp(-1, -2, 0, 2));
        }
        c.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (nearLoc != null) {
            TextView d = text(14, GREEN, true);
            d.setText(Geo.fmtDist(Geo.dist(nearLoc.getLatitude(), nearLoc.getLongitude(), p.lat, p.lon)));
            d.setPadding(dp(10), 0, 0, 0);
            c.addView(d);
        }
        if (!p.resume) {
            // Star: add to / remove from the saved places, which stay at the top of the list.
            TextView star = text(24, p.fav ? AMBER : GREY, false);
            star.setText(p.fav ? "★" : "☆");
            star.setGravity(Gravity.CENTER);
            star.setPadding(dp(12), dp(4), dp(2), dp(4));
            star.setFocusable(false);
            star.setContentDescription(p.fav ? L.t("取消收藏", "Remove from saved places") : L.t("收藏", "Save this place"));
            star.setOnClickListener(v -> toggleFav(p));
            c.addView(star);
        }
        // The list has no dividers; this wrapper leaves a gap under each row.
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(0, 0, 0, dp(8));
        wrap.addView(c);
        return wrap;
    }

    /** Three screens, one at a time: settings, the ride, or search and places. */
    private void setButtons() {
        NavService s = NavService.I;
        boolean nav = s != null && s.navigating;
        boolean set = settingsOpen;
        settingsPanel.setVisibility(set ? View.VISIBLE : View.GONE);
        settingsSpacer.setVisibility(set ? View.VISIBLE : View.GONE);
        boolean ride = nav && !set;
        navPanel.setVisibility(ride ? View.VISIBLE : View.GONE);
        stopBtn.setVisibility(ride ? View.VISIBLE : View.GONE);
        navSpacer.setVisibility(ride ? View.VISIBLE : View.GONE);
        boolean find = !nav && !set;
        searchRow.setVisibility(find ? View.VISIBLE : View.GONE);
        info.setVisibility(find ? View.VISIBLE : View.GONE);
        list.setVisibility(find ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        NavService s = NavService.I;
        if (s == null) {
            statusDot.setColor(GREY);
            statusText.setText(serviceStarted ? L.t("正在启动…", "Starting…")
                    : L.t("需要定位权限才能导航，请在弹窗中允许", "Location permission is needed. Please allow it in the prompt."));
            return;
        }
        int w = s.link.count("wifi"), bt = s.link.count("bt");
        boolean glasses = w + bt > 0;
        Location l = s.last;
        boolean gps = s.gpsGood() && l != null;
        String g = glasses ? L.t("眼镜已连接", "Glasses connected")
                : L.t("眼镜未连接 · 打开手机蓝牙即可", "Glasses not connected · turn on Bluetooth");
        if (!glasses && !s.link.btError.isEmpty()) g += L.t("（蓝牙：", " (Bluetooth: ") + s.link.btError + L.t("）", ")");
        String p = gps ? L.t("GPS ", "GPS ") + Math.round(l.getAccuracy()) + L.t(" 米", " m")
                : L.t("GPS 定位中…", "GPS locating…");
        statusText.setText(g + "  ·  " + p + (simMode ? L.t("  ·  模拟模式", "  ·  simulation") : ""));
        statusDot.setColor(glasses && gps ? GREEN : glasses || gps ? AMBER : GREY);
        if (s.navigating && s.route != null) {
            navBig.setText(s.arrived ? L.t("已到达目的地", "You have arrived") : Geo.fmtDist(s.uiMdist) + "  " + s.uiAction);
            String road = s.uiRoad;
            String msg = s.uiMsg;
            navSmall.setText((TextUtils.isEmpty(road) ? "" : road + "\n")
                    + L.t("剩余 ", "Remaining ") + Geo.fmtDist(s.uiRem) + L.t(" · 预计 ", " · ETA ")
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.uiEta)) + L.t(" 到达", "")
                    + (s.simulating ? L.t(" · 模拟中", " · simulating") : "")
                    + (TextUtils.isEmpty(msg) ? "" : "\n" + msg));
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            // The list is often built before the first fix arrives: add the distances once it does.
            if (nearLoc == null && NavService.I != null && NavService.I.last != null && !places.isEmpty()) {
                nearLoc = NavService.I.last;
                adapter.notifyDataSetChanged();
            }
            refreshStatus();
            boolean nav = NavService.I != null && NavService.I.navigating;
            if (wasNav && !nav && query.getText().toString().trim().isEmpty()) showRecents(); // back from a ride
            wasNav = nav;
            setButtons();
            maybeRunTest();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ permissions & service

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
            need.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) startSvc();
        else requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startSvc();
        } else {
            info.setText(L.t("没有定位权限无法导航。请到 设置 → 应用 → 骑行导航 → 权限 里允许“位置信息”。",
                    "Navigation needs the location permission. Allow it in Settings → Apps → this app → Permissions."));
        }
    }

    private void startSvc() {
        if (serviceStarted) return;
        serviceStarted = true;
        startForegroundService(new Intent(this, NavService.class));
        askBattery();
    }

    private void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            SharedPreferences p = getSharedPreferences("p", MODE_PRIVATE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName()) && !p.getBoolean("askedBattery", false)) {
                p.edit().putBoolean("askedBattery", true).apply();
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ search & routing

    private static String why(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void doSearch() {
        final String q = query.getText().toString().trim();
        hideKeyboard();
        if (q.isEmpty()) {
            showRecents();
            return;
        }
        info.setText(L.t("正在搜索…", "Searching…"));
        NavService s = NavService.I;
        final Location near = s == null ? null : s.last;
        bg.execute(() -> {
            try {
                List<Search.Place> r = Search.search(q,
                        near == null ? 0 : near.getLatitude(), near == null ? 0 : near.getLongitude(), near != null);
                ui.post(() -> {
                    showPlaces(r, near);
                    info.setText(r.isEmpty() ? L.t("没有找到，换个说法试试（英文地名更容易搜到）", "Nothing found. Try different words.")
                            : L.t("点一个地点就出发，眼镜会同步显示", "Tap a place to go; your glasses follow along"));
                });
            } catch (Exception e) {
                ui.post(() -> info.setText(L.t("搜索失败：请检查手机网络（", "Search failed. Check the network (") + why(e) + L.t("）", ")")));
            }
        });
    }

    private void showPlaces(List<Search.Place> r, Location near) {
        List<Search.Place> favs = loadFavs();
        for (Search.Place p : r) {
            p.fav = false;
            if (p.resume) continue;
            for (Search.Place f : favs) if (samePlace(f, p)) p.fav = true;
        }
        places.clear();
        places.addAll(r);
        nearLoc = near;
        adapter.notifyDataSetChanged();
    }

    private void showRecents() {
        List<Search.Place> r = new ArrayList<>();
        NavService s = NavService.I;
        SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);
        Search.Place resume = null;
        try {
            // A ride that never ended (the app was closed or killed): offer to continue it.
            String rs = sp.getString("resume", null);
            if (rs != null && (s == null || !s.navigating)) {
                org.json.JSONObject o = new org.json.JSONObject(rs);
                if (System.currentTimeMillis() - o.optLong("t") < 6 * 3600 * 1000L) {
                    resume = Search.Place.fromJson(o);
                    resume.addr = L.t("上次导航没有结束，点这里从当前位置继续", "The last ride did not finish. Tap to continue from here.");
                    resume.resume = true;
                    r.add(resume);
                }
            }
        } catch (Exception ignored) {
        }
        // Saved places first, then recent ones that are not saved already.
        List<Search.Place> favs = loadFavs();
        r.addAll(favs);
        try {
            JSONArray a = new JSONArray(sp.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Search.Place p = Search.Place.fromJson(a.getJSONObject(i));
                boolean saved = false;
                for (Search.Place f : favs) if (samePlace(f, p)) saved = true;
                if (!saved) r.add(p);
            }
        } catch (Exception ignored) {
        }
        showPlaces(r, s == null ? null : s.last);
        if (info == null) return;
        if (resume != null) info.setText(L.t("上次导航没有结束。点第一行“继续上次导航”可以接着骑", "The last ride did not finish. Tap the first row to continue it."));
        else if (r.isEmpty()) info.setText(L.t("输入要去的地方，点“搜索”", "Type where you want to go and tap Search"));
        else if (favs.isEmpty()) info.setText(L.t("点一个地点就出发 · 点 ☆ 收藏为常去地点", "Tap a place to go · tap ☆ to save it"));
        else info.setText(L.t("点一个地点就出发 · 点 ★ 删除常去地点", "Tap a place to go · tap ★ to remove a saved place"));
    }

    /**
     * Same saved place: same name and practically the same spot. Distance alone is not enough:
     * shops in one building share an address and sit metres apart.
     */
    private static boolean samePlace(Search.Place a, Search.Place b) {
        return Geo.dist(a.lat, a.lon, b.lat, b.lon) <= 5 && TextUtils.equals(a.name, b.name);
    }

    /** Saved ("favourite") places, newest first, at most 30. */
    private List<Search.Place> loadFavs() {
        List<Search.Place> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(getSharedPreferences("p", MODE_PRIVATE).getString("fav", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Search.Place p = Search.Place.fromJson(a.getJSONObject(i));
                p.fav = true;
                r.add(p);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    private void toggleFav(Search.Place p) {
        try {
            List<Search.Place> favs = loadFavs();
            JSONArray a = new JSONArray();
            boolean removed = false;
            for (Search.Place f : favs) {
                if (samePlace(f, p)) removed = true;
                else a.put(f.toJson());
            }
            if (!removed) {
                JSONArray b = new JSONArray();
                b.put(p.toJson());
                for (int i = 0; i < a.length() && b.length() < 30; i++) b.put(a.get(i));
                a = b;
            }
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("fav", a.toString()).apply();
            Toast.makeText(this, removed ? L.t("已从常去地点移除：", "Removed from saved places: ") + p.name
                    : L.t("已收藏为常去地点：", "Saved: ") + p.name, Toast.LENGTH_SHORT).show();
            if (query.getText().toString().trim().isEmpty()) showRecents();
            else showPlaces(new ArrayList<>(places), nearLoc);
        } catch (Exception ignored) {
        }
    }

    private void saveRecent(Search.Place p) {
        try {
            SharedPreferences sp = getSharedPreferences("p", MODE_PRIVATE);
            JSONArray old = new JSONArray(sp.getString("recent", "[]"));
            JSONArray a = new JSONArray();
            a.put(p.toJson());
            for (int i = 0; i < old.length() && a.length() < 10; i++) {
                Search.Place o = Search.Place.fromJson(old.getJSONObject(i));
                if (Geo.dist(o.lat, o.lon, p.lat, p.lon) > 30) a.put(o.toJson());
            }
            sp.edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void pick(Search.Place p) {
        NavService s = NavService.I;
        Location l = s == null ? null : s.last;
        if (l == null) {
            Toast.makeText(this, L.t("还没有定位到你的位置，请到室外稍等几秒", "No position yet. Go outdoors and wait a few seconds."), Toast.LENGTH_LONG).show();
            return;
        }
        if (!s.hasFreshFix()) {
            // The only position we have is an old or very rough one: a route from there would be wrong.
            Toast.makeText(this, L.t("当前位置还不准（是之前的旧位置），请到室外等 GPS 定位后再试",
                    "The current position is old or too rough. Wait outdoors for a GPS fix and try again."), Toast.LENGTH_LONG).show();
            return;
        }
        info.setText(L.t("正在规划去 ", "Planning a route to ") + p.name + L.t(" 的骑行路线…", " …"));
        pending = null;
        setButtons();
        bg.execute(() -> {
            try {
                Route r = Router.fetch(l.getLatitude(), l.getLongitude(), p.lat, p.lon, p.name);
                ui.post(() -> go(r, p, ""));
            } catch (Exception e) {
                // No network: an unfinished ride can still go on with the route saved when it started.
                Route saved = p.resume ? NavService.loadSavedRoute(this, p.name, p.lat, p.lon) : null;
                if (saved != null) {
                    ui.post(() -> go(saved, p, L.t("\n没有网络：使用上次保存的路线。骑错路时要有网才能重新规划",
                            "\nNo network: using the saved route. Rerouting needs a connection.")));
                } else {
                    ui.post(() -> info.setText(L.t("路线规划失败：请检查手机网络（", "Routing failed. Check the network (") + why(e) + L.t("）", ")")));
                }
            }
        });
    }

    /** Starts the ride as soon as a route is in; the glasses pick it up at once. */
    private void go(Route r, Search.Place p, String note) {
        pending = r;
        pendingPlace = p;
        start(simMode);
        info.setText("");
        String warn = "car".equals(r.profile)
                ? L.t("骑行路线服务暂时不可用，这是驾车路线，可能经过禁止自行车的道路（如高速）",
                "The cycling route service is unavailable. This is a driving route and may use roads closed to bicycles.") : "";
        String msg = (warn + note).trim();
        if (!msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void start(boolean sim) {
        NavService s = NavService.I;
        if (s == null || pending == null) return;
        s.simDetour = false;
        s.simSpeed = 5.0;
        s.startNav(pending, sim);
        if (pendingPlace != null) saveRecent(pendingPlace);
    }

    /** adb test hook: --es sim_from "lat,lon" --es sim_to "lat,lon" runs a simulated ride. */
    private void maybeRunTest() {
        if (testHandled || NavService.I == null) return;
        Intent it = getIntent();
        String from = it.getStringExtra("sim_from"), to = it.getStringExtra("sim_to");
        String navTo = it.getStringExtra("nav_to");
        if (navTo != null) {
            // --es nav_to "lat,lon": real (GPS-driven) navigation from the current fix, for tests
            // with an injected test location provider. Waits until a fix exists.
            if (NavService.I.last == null) return;
            testHandled = true;
            try {
                String[] c = navTo.split(",");
                Search.Place p = new Search.Place();
                p.lat = Double.parseDouble(c[0]);
                p.lon = Double.parseDouble(c[1]);
                p.name = it.getStringExtra("sim_name") == null ? L.t("测试终点", "Test destination") : it.getStringExtra("sim_name");
                p.addr = "";
                Location l = NavService.I.last;
                bg.execute(() -> {
                    try {
                        Route r = Router.fetch(l.getLatitude(), l.getLongitude(), p.lat, p.lon, p.name);
                        ui.post(() -> {
                            pending = r;
                            pendingPlace = p;
                            start(false);
                        });
                    } catch (Exception e) {
                        ui.post(() -> info.setText("测试路线失败：" + why(e)));
                    }
                });
            } catch (Exception e) {
                info.setText("测试参数错误");
            }
            return;
        }
        testHandled = true;
        String search = it.getStringExtra("search"); // --es search "text": runs a search without the keyboard
        if (search != null) {
            query.setText(search);
            doSearch();
            return;
        }
        if (from == null || to == null) return;
        try {
            String[] a = from.split(","), c = to.split(",");
            final double fl = Double.parseDouble(a[0]), fo = Double.parseDouble(a[1]);
            final double tl = Double.parseDouble(c[0]), to2 = Double.parseDouble(c[1]);
            final String name = it.getStringExtra("sim_name") == null ? L.t("测试终点", "Test destination") : it.getStringExtra("sim_name");
            bg.execute(() -> {
                try {
                    Route r = Router.fetch(fl, fo, tl, to2, name);
                    ui.post(() -> {
                        pending = r;
                        NavService.I.simDetour = it.getBooleanExtra("sim_detour", false);
                        NavService.I.simSpeed = Math.max(1, Math.min(40, it.getFloatExtra("sim_speed", 5f)));
                        NavService.I.startNav(r, true);
                    });
                } catch (Exception e) {
                    ui.post(() -> info.setText("测试路线失败：" + e.getMessage()));
                }
            });
        } catch (Exception e) {
            info.setText("测试参数错误");
        }
    }
}
