package com.qixing.hud.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
    private ArrayAdapter<String> adapter;
    private final List<Search.Place> places = new ArrayList<>();
    private TextView status, info, navBig, navSmall;
    private Button startBtn, simBtn, stopBtn;
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        return btn;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(16, 18, 20));
        // Keep focus off the search box so the keyboard does not cover the results on launch.
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(22, Color.rgb(80, 230, 120), true);
        title.setText(L.t("骑行导航", "Bike Nav HUD"));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // Shows the language you would switch to.
        top.addView(button(L.en ? "中文" : "English", v -> {
            String to = L.en ? "zh" : "en";
            getSharedPreferences("p", MODE_PRIVATE).edit().putString("lang", to).apply();
            L.apply(to);
            if (NavService.I != null) NavService.I.setLang(to);
            buildUi(); // same activity, new texts; the planned route and navigation are kept
        }));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = text(13, Color.rgb(150, 160, 160), false);
        sub.setText(L.t("完全免费 · 不需要任何账号或密钥 · 眼镜显示转向", "Free · no account or API key · turn-by-turn on your glasses"));
        root.addView(sub);

        status = text(14, Color.rgb(220, 220, 220), false);
        status.setPadding(0, dp(8), 0, dp(6));
        root.addView(status);

        navPanel = new LinearLayout(this);
        navPanel.setOrientation(LinearLayout.VERTICAL);
        navPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        navPanel.setBackgroundColor(Color.rgb(20, 60, 36));
        navBig = text(26, Color.WHITE, true);
        navSmall = text(15, Color.rgb(210, 240, 220), false);
        navPanel.addView(navBig);
        navPanel.addView(navSmall);
        navPanel.setVisibility(View.GONE);
        root.addView(navPanel, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setHint(L.t("搜索目的地（地名、地址或 纬度,经度）", "Search a place, address or lat,lon"));
        query.setSingleLine(true);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(Color.rgb(120, 130, 130));
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        row.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button(L.t("搜索", "Search"), v -> doSearch()));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(Color.rgb(230, 230, 230));
                t.setTextSize(15);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> pick(places.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        info = text(15, Color.rgb(255, 220, 120), false);
        info.setPadding(0, dp(6), 0, dp(4));
        root.addView(info);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button(L.t("开始导航", "Start"), v -> start(false));
        simBtn = button(L.t("模拟骑行", "Simulate"), v -> start(true));
        stopBtn = button(L.t("结束导航", "Stop"), v -> {
            if (NavService.I != null) NavService.I.stopNav();
        });
        btns.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1));
        btns.addView(simBtn, new LinearLayout.LayoutParams(0, -2, 1));
        btns.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(btns, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        voice = new CheckBox(this);
        voice.setText(L.t("语音播报", "Voice prompts"));
        voice.setTextColor(Color.rgb(220, 220, 220));
        voice.setChecked(getSharedPreferences("p", MODE_PRIVATE).getBoolean("voice", true));
        voice.setOnCheckedChangeListener((c, on) -> {
            getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("voice", on).apply();
            if (NavService.I != null) NavService.I.setVoice(on);
        });
        bottom.addView(voice, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(button(L.t("退出", "Quit"), v -> {
            if (NavService.I != null) NavService.I.stopNav();
            ui.postDelayed(() -> {
                stopService(new Intent(this, NavService.class));
                finishAndRemoveTask();
            }, 300);
        }));
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        SharedPreferences prefs = getSharedPreferences("p", MODE_PRIVATE);
        CheckBox onGlasses = new CheckBox(this);
        onGlasses.setText(L.t("语音在眼镜播放", "Voice on glasses"));
        onGlasses.setTextColor(Color.rgb(220, 220, 220));
        onGlasses.setChecked(prefs.getBoolean("voiceGlasses", true));
        onGlasses.setOnCheckedChangeListener((c, on) -> {
            prefs.edit().putBoolean("voiceGlasses", on).apply();
            if (NavService.I != null) NavService.I.setVoiceOnGlasses(on);
        });
        CheckBox eco = new CheckBox(this);
        eco.setText(L.t("眼镜省电(路口前亮屏)", "Glasses power saving"));
        eco.setTextColor(Color.rgb(220, 220, 220));
        eco.setChecked(prefs.getBoolean("eco", false));
        eco.setOnCheckedChangeListener((c, on) -> {
            prefs.edit().putBoolean("eco", on).apply();
            if (NavService.I != null) NavService.I.setEcoScreen(on);
        });
        opts.addView(onGlasses, new LinearLayout.LayoutParams(0, -2, 1));
        opts.addView(eco, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(opts, new LinearLayout.LayoutParams(-1, -2));

        TextView credit = text(11, Color.rgb(110, 120, 120), false);
        credit.setText(L.t("地图数据 © OpenStreetMap 贡献者 · 路线 OSRM（FOSSGIS 免费服务）· 搜索 Photon / Nominatim",
                "Map data © OpenStreetMap contributors · Routing: OSRM (FOSSGIS) · Search: Photon / Nominatim"));
        root.addView(credit);

        setContentView(root);
        showRecents();
        setButtons();
    }

    private void setButtons() {
        NavService s = NavService.I;
        boolean nav = s != null && s.navigating;
        startBtn.setEnabled(!nav && pending != null);
        simBtn.setEnabled(!nav && pending != null);
        stopBtn.setEnabled(nav);
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            NavService s = NavService.I;
            StringBuilder sb = new StringBuilder();
            if (s == null) {
                sb.append(serviceStarted ? L.t("正在启动…", "Starting…")
                        : L.t("需要定位权限才能导航，请在弹窗中允许", "Location permission is needed. Please allow it in the prompt."));
            } else {
                Location l = s.last;
                if (s.gpsGood() && l != null) {
                    sb.append(L.t("GPS：已定位，精度约 ", "GPS: fixed, accuracy about ")).append(Math.round(l.getAccuracy())).append(L.t(" 米", " m"));
                    if (l.hasSpeed()) sb.append(" · ").append(Math.round(l.getSpeed() * 3.6)).append(" km/h");
                } else if (l != null) {
                    sb.append(L.t("GPS：正在定位…（已有大致位置，到室外会更准）", "GPS: locating… (rough position only; go outdoors for a better fix)"));
                } else {
                    sb.append(L.t("GPS：正在定位…请到室外开阔处", "GPS: locating… please go outdoors"));
                }
                int w = s.link.count("wifi"), bt = s.link.count("bt");
                sb.append(L.t("\n眼镜：", "\nGlasses: "));
                if (w + bt == 0) {
                    sb.append(L.t("未连接（手机开热点并让眼镜连上，或打开蓝牙）", "not connected (turn on the hotspot and join it from the glasses, or turn on Bluetooth)"));
                    if (!s.link.btError.isEmpty()) sb.append(L.t(" · 蓝牙：", " · Bluetooth: ")).append(s.link.btError);
                } else {
                    sb.append(L.t("已连接", "connected"));
                    if (w > 0) sb.append(L.t(" · 热点/WiFi", " · hotspot/Wi-Fi"));
                    if (bt > 0) sb.append(L.t(" · 蓝牙", " · Bluetooth"));
                }
                if (s.navigating && s.route != null) {
                    navPanel.setVisibility(View.VISIBLE);
                    String act = s.uiAction;
                    navBig.setText(s.arrived ? L.t("已到达目的地", "You have arrived") : Geo.fmtDist(s.uiMdist) + "  " + act);
                    String road = s.uiRoad;
                    String msg = s.uiMsg;
                    navSmall.setText((TextUtils.isEmpty(road) ? "" : road + "\n")
                            + L.t("剩余 ", "Remaining ") + Geo.fmtDist(s.uiRem) + L.t(" · 预计 ", " · ETA ")
                            + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.uiEta)) + L.t(" 到达", "")
                            + (s.simulating ? L.t(" · 模拟中", " · simulating") : "")
                            + (TextUtils.isEmpty(msg) ? "" : "\n" + msg));
                } else {
                    navPanel.setVisibility(View.GONE);
                }
            }
            status.setText(sb);
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
                            : L.t("点一个结果来规划骑行路线", "Tap a result to plan a cycling route"));
                });
            } catch (Exception e) {
                ui.post(() -> info.setText(L.t("搜索失败：请检查手机网络（", "Search failed. Check the network (") + why(e) + L.t("）", ")")));
            }
        });
    }

    private void showPlaces(List<Search.Place> r, Location near) {
        places.clear();
        places.addAll(r);
        adapter.clear();
        for (Search.Place p : r) {
            String d = near == null ? "" : "  ·  " + Geo.fmtDist(Geo.dist(near.getLatitude(), near.getLongitude(), p.lat, p.lon));
            adapter.add((p.resume ? L.t("↻ 继续上次导航：", "↻ Continue last ride: ") : "") + p.name + d + (TextUtils.isEmpty(p.addr) ? "" : "\n" + p.addr));
        }
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
        try {
            JSONArray a = new JSONArray(sp.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) r.add(Search.Place.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        showPlaces(r, s == null ? null : s.last);
        if (info == null) return;
        if (resume != null) info.setText(L.t("上次导航没有结束。点第一行“继续上次导航”可以接着骑", "The last ride did not finish. Tap the first row to continue it."));
        else if (r.isEmpty()) info.setText(L.t("输入目的地后点“搜索”", "Type a destination and tap Search"));
        else info.setText(L.t("最近去过的地方（点一下规划路线）", "Recent places (tap to plan a route)"));
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
        info.setText(L.t("正在规划骑行路线：", "Planning a cycling route to ") + p.name + " …");
        pending = null;
        setButtons();
        bg.execute(() -> {
            try {
                Route r = Router.fetch(l.getLatitude(), l.getLongitude(), p.lat, p.lon, p.name);
                ui.post(() -> showPlanned(r, p, ""));
            } catch (Exception e) {
                // No network: an unfinished ride can still go on with the route saved when it started.
                Route saved = p.resume ? NavService.loadSavedRoute(this, p.name, p.lat, p.lon) : null;
                if (saved != null) {
                    ui.post(() -> showPlanned(saved, p, L.t("\n没有网络：使用上次保存的路线。骑错路时要有网才能重新规划",
                            "\nNo network: using the saved route. Rerouting needs a connection.")));
                } else {
                    ui.post(() -> info.setText(L.t("路线规划失败：请检查手机网络（", "Routing failed. Check the network (") + why(e) + L.t("）", ")")));
                }
            }
        });
    }

    private void showPlanned(Route r, Search.Place p, String note) {
        pending = r;
        pendingPlace = p;
        String warn = "car".equals(r.profile)
                ? L.t("\n⚠ 骑行路线服务暂时不可用，这是驾车路线，可能经过禁止自行车的道路（如高速）",
                "\n⚠ The cycling route service is unavailable. This is a driving route and may use roads closed to bicycles.") : "";
        long mins = Math.max(1, Math.round(r.totalDur / 60));
        info.setText(L.en
                ? Router.profileName(r.profile) + " route: " + Geo.fmtDist(r.totalDist) + ", about " + mins + " min → " + p.name
                + warn + note + "\nTap Start to go, or Simulate to preview it on the glasses"
                : Router.profileName(r.profile) + "路线：全程 " + Geo.fmtDist(r.totalDist) + "，约 " + mins + " 分钟 → " + p.name
                + warn + note + "\n点“开始导航”出发，或“模拟骑行”先在眼镜上预览");
        setButtons();
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
