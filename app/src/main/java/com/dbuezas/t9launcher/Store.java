package com.dbuezas.t9launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-wide app list, usage stats and icon cache.
 * Everything on the main thread except file IO and PackageManager calls.
 * The app list is saved to disk, so a cold start shows results before the
 * (slower) PackageManager scan finishes.
 */
final class Store {
    interface Listener {
        void appsChanged();
        void iconLoaded();
    }

    private static final int ICON_LRU = 80;
    private static Store instance;

    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler scanThread, iconThread;
    private final File listFile, usageFile, iconDir;
    private final HashMap<String, double[]> usage = new HashMap<>();
    private final LinkedHashMap<App, Boolean> lru = new LinkedHashMap<App, Boolean>(ICON_LRU, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<App, Boolean> e) {
            if (size() <= ICON_LRU) return false;
            e.getKey().icon = null;
            return true;
        }
    };
    private String locale;
    private boolean scanQueued;

    App[] apps = new App[0];
    Listener listener;
    int iconPx;

    static Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        else instance.checkLocale();
        return instance;
    }

    private Store(Context c) {
        ctx = c;
        la = c.getSystemService(LauncherApps.class);
        um = c.getSystemService(UserManager.class);
        listFile = new File(c.getFilesDir(), "apps.tsv");
        usageFile = new File(c.getFilesDir(), "usage.tsv");
        iconDir = new File(c.getCacheDir(), "icons");
        iconPx = Math.round(48 * c.getResources().getDisplayMetrics().density);
        scanThread = thread("scan");
        iconThread = thread("icons");
        locale = Locale.getDefault().toString();

        readUsage();
        readList();
        scan();
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { scan(); }
            @Override public void onPackageAdded(String p, UserHandle u) { scan(); }
            @Override public void onPackageChanged(String p, UserHandle u) { scan(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { scan(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { scan(); }
        }, main);
    }

    private static Handler thread(String name) {
        HandlerThread t = new HandlerThread(name, Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        return new Handler(t.getLooper());
    }

    private void checkLocale() {
        String l = Locale.getDefault().toString();
        if (!l.equals(locale)) {
            locale = l;
            scan();
        }
    }

    // ---------------------------------------------------------------- app list

    private void readList() {
        if (!listFile.exists()) return;
        ArrayList<App> out = new ArrayList<>();
        HashMap<Long, UserHandle> users = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(listFile), 32768)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", 4);
                if (f.length < 4) continue;
                ComponentName cn = ComponentName.unflattenFromString(f[0]);
                long serial = Long.parseLong(f[1]);
                UserHandle u = users.get(serial);
                if (u == null) {
                    u = um.getUserForSerialNumber(serial);
                    if (u == null) continue;
                    users.put(serial, u);
                }
                if (cn != null) out.add(withUsage(new App(cn, serial, u, f[3], Long.parseLong(f[2]))));
            }
        } catch (Exception ignored) {
        }
        apps = out.toArray(new App[0]);
    }

    private App withUsage(App a) {
        a.usage = usage.get(a.key);
        return a;
    }

    /** Rescan PackageManager in the background (coalesced). */
    void scan() {
        if (scanQueued) return;
        scanQueued = true;
        scanThread.postDelayed(() -> {
            main.post(() -> scanQueued = false);
            String self = ctx.getPackageName();
            UserHandle me = Process.myUserHandle();
            ArrayList<String[]> rows = new ArrayList<>();
            ArrayList<UserHandle> rowUsers = new ArrayList<>();
            for (UserHandle u : um.getUserProfiles()) {
                List<LauncherActivityInfo> list;
                try {
                    list = la.getActivityList(null, u);
                } catch (Exception e) {
                    continue; // e.g. work profile paused
                }
                long serial = um.getSerialNumberForUser(u);
                for (LauncherActivityInfo i : list) {
                    ComponentName cn = i.getComponentName();
                    if (cn.getPackageName().equals(self) && u.equals(me)) continue;
                    String label = String.valueOf(i.getLabel()).replace('\t', ' ').replace('\n', ' ').trim();
                    long stamp = new File(i.getApplicationInfo().sourceDir).lastModified();
                    rows.add(new String[]{cn.flattenToShortString(), Long.toString(serial), Long.toString(stamp), label});
                    rowUsers.add(u);
                }
            }
            StringBuilder sb = new StringBuilder(rows.size() * 64);
            for (String[] r : rows) sb.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]).append('\t').append(r[3]).append('\n');
            String text = sb.toString();
            if (!text.equals(readText(listFile))) writeText(listFile, text);
            main.post(() -> applyScan(rows, rowUsers));
        }, 50);
    }

    private void applyScan(ArrayList<String[]> rows, ArrayList<UserHandle> users) {
        HashMap<String, App> old = new HashMap<>();
        for (App a : apps) old.put(a.key + "|" + a.stamp + "|" + a.label, a);
        App[] next = new App[rows.size()];
        boolean changed = rows.size() != apps.length;
        HashSet<String> iconNames = new HashSet<>();
        for (int i = 0; i < next.length; i++) {
            String[] r = rows.get(i);
            App a = old.get(r[0] + "#" + r[1] + "|" + r[2] + "|" + r[3]);
            if (a == null) {
                a = withUsage(new App(ComponentName.unflattenFromString(r[0]), Long.parseLong(r[1]), users.get(i), r[3], Long.parseLong(r[2])));
                changed = true;
            }
            next[i] = a;
            iconNames.add(iconName(a));
        }
        if (changed) {
            apps = next;
            if (listener != null) listener.appsChanged();
        }
        iconThread.post(() -> {
            File[] files = iconDir.listFiles();
            if (files != null) for (File f : files) if (!iconNames.contains(f.getName())) f.delete();
        });
    }

    // ---------------------------------------------------------------- usage

    private void readUsage() {
        String t = readText(usageFile);
        if (t == null) return;
        for (String line : t.split("\n")) {
            String[] f = line.split("\t");
            if (f.length == 3) {
                try {
                    usage.put(f[0], new double[]{Double.parseDouble(f[1]), Double.parseDouble(f[2])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** Last time the app was opened from here (epoch seconds), 0 = never. */
    static double rank(App a, double now) {
        double[] u = a.usage;
        return u == null ? 0 : u[1];
    }

    void recordLaunch(App a) {
        double now = System.currentTimeMillis() / 1000.0;
        double[] u = usage.get(a.key);
        if (u == null) usage.put(a.key, u = new double[2]);
        u[0] += 1; // launch count, kept for later use
        u[1] = now;
        a.usage = u;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, double[]> e : usage.entrySet()) {
            double[] v = e.getValue();
            sb.append(e.getKey()).append('\t').append(v[0]).append('\t').append(v[1]).append('\n');
        }
        String text = sb.toString();
        scanThread.post(() -> writeText(usageFile, text));
    }

    void startApp(App a, android.graphics.Rect bounds, android.os.Bundle opts) {
        la.startMainActivity(a.cn, a.user, bounds, opts);
    }

    void showAppInfo(App a, android.graphics.Rect bounds) {
        la.startAppDetailsActivity(a.cn, a.user, bounds, null);
    }

    // ---------------------------------------------------------------- icons

    private String iconName(App a) {
        return Integer.toHexString(a.key.hashCode()) + "_" + Long.toHexString(a.stamp) + "_" + iconPx + ".png";
    }

    /** Non-blocking. Calls listener.iconLoaded() when ready. */
    void requestIcon(App a) {
        if (a.icon != null) {
            lru.get(a);
            return;
        }
        if (a.iconLoading) return;
        a.iconLoading = true;
        int px = iconPx;
        File f = new File(iconDir, iconName(a));
        iconThread.post(() -> {
            Bitmap b = null;
            if (f.exists()) b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) {
                b = render(a, px);
                if (b != null) save(b, f);
            }
            Bitmap result = b;
            main.post(() -> {
                a.iconLoading = false;
                if (result == null) return;
                a.icon = result;
                lru.put(a, Boolean.TRUE);
                if (listener != null) listener.iconLoaded();
            });
        });
    }

    private Bitmap render(App a, int px) {
        try {
            LauncherActivityInfo info = la.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn), a.user);
            if (info == null) return null;
            Drawable d = info.getIcon(ctx.getResources().getDisplayMetrics().densityDpi);
            if (!a.user.equals(Process.myUserHandle())) d = ctx.getPackageManager().getUserBadgedIcon(d, a.user);
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(b));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void save(Bitmap b, File f) {
        iconDir.mkdirs();
        File tmp = new File(iconDir, f.getName() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        tmp.renameTo(f);
    }

    // ---------------------------------------------------------------- files

    private static String readText(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(text);
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}
