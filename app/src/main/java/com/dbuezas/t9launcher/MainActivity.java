package com.dbuezas.t9launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;

public final class MainActivity extends Activity {
    private Store store;
    private DialerView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = Store.get(this);
        store.onOpen();
        view = new DialerView(this, store);
        view.banner = needsSetup();
        store.listener = view;
        setContentView(view);
        placeWindow();
        setFinishOnTouchOutside(true);
    }

    /** A floating card, centered, just above the navigation bar. */
    private void placeWindow() {
        float dp = getResources().getDisplayMetrics().density;
        int screenW, nav;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics m = getWindowManager().getCurrentWindowMetrics();
            screenW = m.getBounds().width();
            nav = m.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars()).bottom;
        } else {
            screenW = getResources().getDisplayMetrics().widthPixels;
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            nav = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        }
        android.view.Window w = getWindow();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lp.width = DialerView.panelWidth(dp, screenW);
        lp.height = DialerView.panelHeight(dp, view.banner);
        lp.y = nav + Math.round(52 * dp);
        w.setAttributes(lp);
    }

    // ---------------------------------------------------------------- gesture setup

    private java.io.File dismissedFile() {
        return new java.io.File(getFilesDir(), "setup-dismissed");
    }

    /** True while this app is not the assistant and the hint was not dismissed. */
    private boolean needsSetup() {
        if (dismissedFile().exists()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = getSystemService(android.app.role.RoleManager.class);
            return rm != null && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        }
        String a = android.provider.Settings.Secure.getString(getContentResolver(), "assistant");
        return a == null || !a.startsWith(getPackageName() + "/");
    }

    /** Opens the "Default digital assistant app" screen. */
    void openSetup() {
        Intent[] tries = {
                new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                android.widget.Toast.makeText(this,
                        "Pick \u201CT9 App Launcher\u201D",
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
                return;
            } catch (Exception ignored) {
            }
        }
    }

    void dismissSetup() {
        try {
            dismissedFile().createNewFile();
        } catch (java.io.IOException ignored) {
        }
        view.banner = false;
        placeWindow();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        placeWindow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        store.onOpen();
        view.reset();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Never linger in the background: the next gesture starts clean.
        if (!isChangingConfigurations()) {
            store.onHidden();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (store.listener == view) store.listener = null;
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return view.onHardKey(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    void launch(App a, Rect bounds) {
        try {
            Bundle opts = ActivityOptions.makeScaleUpAnimation(view, bounds.left, bounds.top - viewTop(),
                    bounds.width(), bounds.height()).toBundle();
            store.startApp(this, a, bounds, opts);
            store.recordLaunch(a);
        } catch (Exception e) {
            store.scan(); // app was removed or disabled
            return;
        }
        finish();
    }

    void showInfo(App a, Rect bounds) {
        try {
            store.showAppInfo(a, bounds);
        } catch (Exception ignored) {
        }
    }

    private int viewTop() {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        return loc[1];
    }
}
