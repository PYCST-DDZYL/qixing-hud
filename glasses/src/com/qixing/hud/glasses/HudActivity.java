package com.qixing.hud.glasses;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class HudActivity extends Activity implements HudService.Listener {
    static volatile boolean visible;
    private HudView view;
    private boolean keepOn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        view = new HudView(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(view);
        startForegroundService(new Intent(this, HudService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        HudService.listener = this;
        onHudUpdate();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    public void onHudUpdate() {
        runOnUiThread(() -> {
            HudService s = HudService.I;
            boolean nav = false;
            if (s != null) {
                synchronized (s.st) {
                    nav = s.st.connected && s.st.nav;
                }
            }
            if (nav != keepOn) {
                keepOn = nav;
                if (nav) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            view.invalidate();
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                view.toggleZoom();
                return true;
            case KeyEvent.KEYCODE_BACK:
                moveTaskToBack(true);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
