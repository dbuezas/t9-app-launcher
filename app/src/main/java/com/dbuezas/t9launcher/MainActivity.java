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
        look = lookOf(getResources().getConfiguration());
        buildView(needsSetup());
        placeWindow();
        setFinishOnTouchOutside(true);
    }

    /** Dark mode and display size: the view's colors and sizes are fixed when it is made. */
    private int look;

    private static int lookOf(android.content.res.Configuration c) {
        return c.densityDpi * 4 + (c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
    }

    private void buildView(boolean banner) {
        view = new DialerView(this, store);
        view.banner = banner;
        store.listener = view;
        setContentView(view);
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
        int l = lookOf(c);
        if (l != look) {
            // Dark mode or display size changed while open: rebuild with the new colors and sizes.
            look = l;
            store.checkDensity();
            buildView(view.banner);
        }
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
        if (!isChangingConfigurations() && store != null) {
            store.onHidden();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (store != null && store.listener == view) store.listener = null;
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return view.onHardKey(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    void launch(App a, Rect bounds) {
        if (a.cn.getPackageName().equals(getPackageName()) && a.user.equals(android.os.Process.myUserHandle())) {
            finish(); // this panel itself: it is already open
            return;
        }
        if (a.archived()) {
            showInfo(a, bounds); // App info has the restore button
            return;
        }
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

    // ---------------------------------------------------------------- long-press menu

    private static final String PLAY = "com.android.vending";

    // No Uninstall, Archive or Restore: App info has them, and their permissions made Play Protect call the app harmful.
    private static final int M_STORE = 0, M_INFO = 1, M_PIN = 2, M_SHARE = 3;
    private static final String[] M_LABEL = {"Play Store", "App info", "Add to home screen", "Share"};
    /** An invisible view over the pressed result: Android's popup menu opens next to it. */
    private android.view.View anchor;

    /** Long-press: Android's popup menu next to the app. cell is in view coordinates. False if it has no items. */
    boolean showMenu(App a, Rect cell, Rect bounds) {
        int[] items = new int[M_LABEL.length];
        int n = menuFor(a, items);
        if (n == 0) return false;
        if (anchor == null) {
            anchor = new android.view.View(this);
            addContentView(anchor, new android.widget.FrameLayout.LayoutParams(1, 1));
        }
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) anchor.getLayoutParams();
        lp.width = cell.width();
        lp.height = cell.height();
        lp.leftMargin = cell.left;
        lp.topMargin = cell.top;
        anchor.setLayoutParams(lp);
        // Light or dark like the phone (the panel's own theme is always dark).
        android.content.Context themed = new android.view.ContextThemeWrapper(this, android.os.Build.VERSION.SDK_INT >= 29
                ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_DeviceDefault);
        android.widget.PopupMenu pm = new android.widget.PopupMenu(themed, anchor);
        for (int k = 0; k < n; k++) {
            pm.getMenu().add(0, items[k], k, M_LABEL[items[k]]);
        }
        pm.setOnMenuItemClickListener(item -> {
            menuAction(a, item.getItemId(), bounds);
            return true;
        });
        pm.setOnDismissListener(m -> view.menuClosed());
        anchor.post(pm::show); // after the anchor moved
        return true;
    }

    /** The link that Share sends for the app of the open menu. */
    private String shareUrl;

    /** The store page the app came from. This app itself is not on a store: its GitHub page, unless a store installed it. */
    private static String shareUrl(String pkg, String installer, boolean self) {
        if (installer != null && (installer.contains("fdroid") || installer.equals("com.looker.droidify"))) {
            return "https://f-droid.org/packages/" + pkg;
        }
        if (self && !PLAY.equals(installer)) return "https://github.com/dbuezas/t9-app-launcher";
        return "https://play.google.com/store/apps/details?id=" + pkg;
    }

    /** Fills out[] with the menu items for this app and returns how many. */
    private int menuFor(App a, int[] out) {
        int n = 0;
        if (a.setting) {
            out[n++] = M_PIN; // a settings page can sit on the home screen too
            return n;
        }
        String pkg = a.cn.getPackageName();
        boolean me = a.user.equals(android.os.Process.myUserHandle());
        boolean self = me && pkg.equals(getPackageName());
        String installer = null;
        if (me && android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                installer = getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            } catch (Exception ignored) {
            }
        }
        boolean fromPlay = PLAY.equals(installer);
        shareUrl = shareUrl(pkg, installer, self);
        if (!self || fromPlay) out[n++] = M_STORE;
        out[n++] = M_SHARE;
        out[n++] = M_INFO;
        if (me && !a.archived()) out[n++] = M_PIN;
        return n;
    }

    private void menuAction(App a, int item, Rect bounds) {
        String pkg = a.cn.getPackageName();
        try {
            switch (item) {
                case M_STORE:
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + pkg))
                                .setPackage(PLAY));
                    } catch (android.content.ActivityNotFoundException e) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                    }
                    break;
                case M_SHARE:
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, a.label)
                            .putExtra(Intent.EXTRA_TEXT, a.label + "\n" + shareUrl), null));
                    break;
                case M_INFO:
                    store.showAppInfo(a, bounds);
                    break;
                case M_PIN:
                    pin(a);
                    break;
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Not possible: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Asks the home screen to add a shortcut that opens this app or settings page. */
    private void pin(App a) {
        android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) throw new IllegalStateException("the home screen does not allow it");
        Intent open = new Intent(Intent.ACTION_MAIN).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!a.setting) open.addCategory(Intent.CATEGORY_LAUNCHER);
        android.content.pm.ShortcutInfo.Builder b = new android.content.pm.ShortcutInfo.Builder(this, "open:" + a.key)
                .setShortLabel(a.label.isEmpty() && a.alias != null ? a.alias : a.label)
                .setIntent(open);
        if (a.icon != null) b.setIcon(android.graphics.drawable.Icon.createWithBitmap(a.icon));
        sm.requestPinShortcut(b.build(), null);
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
