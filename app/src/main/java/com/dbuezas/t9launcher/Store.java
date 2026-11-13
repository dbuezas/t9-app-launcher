package com.dbuezas.t9launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ChangedPackages;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-wide app list, usage stats and icon cache.
 *
 * State lives on the main thread; file IO and PackageManager calls run on one
 * background thread. The app list is saved to disk with a "package change
 * sequence number", so a cold start shows results at once and only rescans
 * PackageManager when something really changed.
 */
final class Store {
    interface Listener {
        void appsChanged();
        void iconLoaded();
    }

    static final int ICON_DP = 56;

    /** Settings activities that need arguments (an app, a channel...) or are not pages. */
    private static final String[] SETTINGS_SKIP = {
            "Picker", "Pick", "Choose", "Licen", "Shortcut", "PrintJob", "Editor", "Panel", "Trampoline",
            "Enroll", "Confirm", "Dialog", "AppInfo", "InstalledApp", "Detail", "Channel", "AppNotification",
            "AppLocale", "Suggestion", "Setup", "Wizard", "Test", "Dpp", "Handler", "Redaction", "Credential",
            "Supervision", "Retail", "Warranty", "Onboarding", "Launchpad", "SearchResult", "AddApp", "AdminAdd",
    };
    private static final int ICON_LRU = 48;     // icons kept while the panel is open
    private static final int ICON_KEEP = 8;     // icons kept while it is closed
    private static Store instance;

    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler bg;
    private final File listFile, usageFile, iconDir;
    private final HashMap<String, double[]> usage = new HashMap<>();
    private final LinkedHashMap<App, Boolean> lru = new LinkedHashMap<>(ICON_LRU, 0.75f, true);
    private boolean scanQueued;
    /** Header of the saved list (state + package sequence number). Background thread. */
    private volatile String savedHeader;

    /**
     * Called on every open. Never blocks: one cheap check on the background thread
     * ("did any package change?"), and a rescan only if something did.
     */
    void onOpen() {
        bg.post(() -> {
            String h = savedHeader;
            if (!base(h).equals(stateHeader(-1)) || packagesChanged(h)) scanNow();
        });
    }

    /** Read on the background thread too: only ever replaced, never mutated. */
    volatile App[] apps = new App[0];
    Listener listener;
    final int iconPx;

    static Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        else instance.checkLocale();
        return instance;
    }

    private String locale = Locale.getDefault().toString();

    /** Labels are translated: rescan when the language changed while the process lived. */
    private void checkLocale() {
        String l = Locale.getDefault().toString();
        if (!l.equals(locale)) {
            locale = l;
            scan();
        }
    }

    private Store(Context c) {
        ctx = c;
        la = c.getSystemService(LauncherApps.class);
        um = c.getSystemService(UserManager.class);
        listFile = new File(c.getFilesDir(), "apps.tsv");
        usageFile = new File(c.getFilesDir(), "usage.tsv");
        iconDir = new File(c.getCacheDir(), "icons");
        iconPx = Math.round(ICON_DP * c.getResources().getDisplayMetrics().density);
        HandlerThread t = new HandlerThread("bg", Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        bg = new Handler(t.getLooper());

        readUsage();
        savedHeader = readList();
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { scan(); }
            @Override public void onPackageAdded(String p, UserHandle u) { scan(); }
            @Override public void onPackageChanged(String p, UserHandle u) { scan(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { scan(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { scan(); }
        }, main);
    }

    // ---------------------------------------------------------------- change detection

    /**
     * Everything that invalidates the saved list, except the package sequence number:
     * boot count (sequence numbers restart at boot), locale (labels), profiles.
     * With seq >= 0 the sequence number is appended.
     */
    private String stateHeader(int seq) {
        StringBuilder sb = new StringBuilder("#")
                .append(Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1))
                .append('|').append(Locale.getDefault())
                .append('|');
        for (UserHandle u : um.getUserProfiles()) sb.append(um.getSerialNumberForUser(u)).append(',');
        if (seq >= 0) sb.append('|').append(seq);
        return sb.toString();
    }

    private static int seqOf(String header) {
        int i = header.lastIndexOf('|');
        try {
            return Integer.parseInt(header.substring(i + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Header without its sequence number, to compare with stateHeader(-1). */
    private static String base(String header) {
        int i = header.lastIndexOf('|');
        return i < 0 ? header : header.substring(0, i);
    }

    private boolean packagesChanged(String header) {
        int seq = seqOf(header);
        return seq < 0 || ctx.getPackageManager().getChangedPackages(seq) != null;
    }

    private int currentSeq() {
        ChangedPackages cp = ctx.getPackageManager().getChangedPackages(0);
        return cp == null ? 0 : cp.getSequenceNumber();
    }

    // ---------------------------------------------------------------- app list

    /** Loads the saved list (main thread, a few ms). Returns its header, "" if none. */
    private String readList() {
        String text = readText(listFile);
        if (text == null) return "";
        ArrayList<App> out = new ArrayList<>();
        HashMap<Long, UserHandle> users = new HashMap<>();
        String header = "";
        int start = 0, len = text.length();
        while (start < len) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = len;
            String line = text.substring(start, end);
            start = end + 1;
            if (line.startsWith("#")) {
                header = line;
                continue;
            }
            String[] f = line.split("\t", 4);
            if (f.length < 4) continue;
            try {
                long serial = Long.parseLong(f[1]);
                UserHandle u = users.get(serial);
                if (u == null) {
                    u = um.getUserForSerialNumber(serial);
                    if (u == null) continue;
                    users.put(serial, u);
                }
                App a = App.of(f[0], serial, u, f[3], Long.parseLong(f[2]));
                if (a != null) out.add(withUsage(a));
            } catch (NumberFormatException ignored) {
            }
        }
        apps = out.toArray(new App[0]);
        return header;
    }

    private App withUsage(App a) {
        a.usage = usage.get(a.key);
        return a;
    }

    /** Rescan PackageManager in the background (coalesced). Main thread only. */
    void scan() {
        if (scanQueued) return;
        scanQueued = true;
        bg.postDelayed(() -> {
            main.post(() -> scanQueued = false);
            scanNow();
        }, 100);
    }

    /** Background thread. */
    private void scanNow() {
        int seq = currentSeq(); // before the scan: a change during the scan triggers the next one
        String self = ctx.getPackageName();
        UserHandle me = Process.myUserHandle();
        App[] before = apps;
        ArrayList<String[]> rows = new ArrayList<>();
        ArrayList<UserHandle> rowUsers = new ArrayList<>();
        for (UserHandle u : um.getUserProfiles()) {
            long serial = um.getSerialNumberForUser(u);
            List<LauncherActivityInfo> list = null;
            if (!um.isQuietModeEnabled(u)) {
                try {
                    list = la.getActivityList(null, u);
                } catch (Exception ignored) {
                }
            }
            if (list == null) {
                // Paused work profile: keep its apps, tapping one asks to unpause it.
                for (App a : before) {
                    if (a.serial != serial || a.setting) continue;
                    rows.add(new String[]{a.id, Long.toString(serial), Long.toString(a.stamp), a.label});
                    rowUsers.add(u);
                }
                continue;
            }
            for (LauncherActivityInfo i : list) {
                ComponentName cn = i.getComponentName();
                if (u.equals(me) && cn.getPackageName().equals(self)) continue;
                String label = String.valueOf(i.getLabel()).replace('\t', ' ').replace('\n', ' ').trim();
                long stamp = new File(i.getApplicationInfo().sourceDir).lastModified();
                rows.add(new String[]{cn.flattenToShortString(), Long.toString(serial), Long.toString(stamp), label});
                rowUsers.add(u);
            }
        }
        scanSettings(rows, rowUsers, me);
        String header = stateHeader(seq);
        StringBuilder sb = new StringBuilder(rows.size() * 64).append(header).append('\n');
        for (String[] r : rows) sb.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]).append('\t').append(r[3]).append('\n');
        String text = sb.toString();
        if (!text.equals(readText(listFile))) writeText(listFile, text);
        savedHeader = header;
        main.post(() -> applyScan(rows, rowUsers));
    }

    /**
     * Settings pages, read from the phone's Settings app: every exported page with its own
     * (translated) title. Pages that need arguments are skipped; duplicate titles are dropped.
     */
    private void scanSettings(ArrayList<String[]> rows, ArrayList<UserHandle> rowUsers, UserHandle me) {
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        try {
            android.content.pm.ResolveInfo sr = pm.resolveActivity(new Intent(android.provider.Settings.ACTION_SETTINGS), 0);
            if (sr == null) return;
            android.content.pm.ApplicationInfo app = sr.activityInfo.applicationInfo;
            android.content.pm.PackageInfo pi = pm.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) return;
            String appLabel = String.valueOf(pm.getApplicationLabel(app));
            String serial = Long.toString(um.getSerialNumberForUser(me));
            String stamp = Long.toString(new File(app.sourceDir).lastModified());
            HashSet<String> seen = new HashSet<>();
            outer:
            for (android.content.pm.ActivityInfo ai : pi.activities) {
                if (!ai.exported || !ai.enabled || ai.permission != null || ai.labelRes == 0) continue;
                for (String skip : SETTINGS_SKIP) if (ai.name.contains(skip)) continue outer;
                String label = String.valueOf(ai.loadLabel(pm)).replace('\t', ' ').replace('\n', ' ').trim();
                if (label.isEmpty() || label.equals(appLabel) || !seen.add(label)) continue;
                rows.add(new String[]{"!" + new ComponentName(app.packageName, ai.name).flattenToShortString(), serial, stamp, label});
                rowUsers.add(me);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyScan(ArrayList<String[]> rows, ArrayList<UserHandle> users) {
        HashMap<String, App> old = new HashMap<>();
        for (App a : apps) old.put(a.key + "|" + a.stamp + "|" + a.label, a);
        App[] next = new App[rows.size()];
        boolean changed = rows.size() != apps.length;
        HashSet<String> iconNames = new HashSet<>();
        HashSet<String> keys = new HashSet<>();
        for (int i = 0; i < next.length; i++) {
            String[] r = rows.get(i);
            App a = old.get(r[0] + "#" + r[1] + "|" + r[2] + "|" + r[3]);
            if (a == null) {
                a = App.of(r[0], Long.parseLong(r[1]), users.get(i), r[3], Long.parseLong(r[2]));
                if (a == null) continue;
                withUsage(a);
                changed = true;
            }
            next[i] = a;
            iconNames.add(iconName(a));
            keys.add(a.key);
        }
        if (changed) {
            int n = 0;
            for (App a : next) if (a != null) next[n++] = a;
            if (n < next.length) next = java.util.Arrays.copyOf(next, n);
            apps = next;
            lru.keySet().retainAll(java.util.Arrays.asList(next));
            if (listener != null) listener.appsChanged();
        }
        // Forget usage of removed apps; delete their icons.
        if (usage.keySet().retainAll(keys)) saveUsage();
        bg.post(() -> {
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
    static double rank(App a) {
        double[] u = a.usage;
        return u == null ? 0 : u[1];
    }

    void recordLaunch(App a) {
        double[] u = usage.get(a.key);
        if (u == null) usage.put(a.key, u = new double[2]);
        u[0] += 1; // launch count, kept for later use
        u[1] = System.currentTimeMillis() / 1000.0;
        a.usage = u;
        saveUsage();
    }

    private void saveUsage() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, double[]> e : usage.entrySet()) {
            double[] v = e.getValue();
            sb.append(e.getKey()).append('\t').append(v[0]).append('\t').append(v[1]).append('\n');
        }
        String text = sb.toString();
        bg.post(() -> writeText(usageFile, text));
    }

    void startApp(android.app.Activity from, App a, Rect bounds, Bundle opts) {
        if (a.setting) from.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(a.cn), opts);
        else la.startMainActivity(a.cn, a.user, bounds, opts);
    }

    void showAppInfo(App a, Rect bounds) {
        if (!a.setting) la.startAppDetailsActivity(a.cn, a.user, bounds, null);
    }

    // ---------------------------------------------------------------- icons

    private String iconName(App a) {
        return Integer.toHexString(a.key.hashCode()) + "_" + Long.toHexString(a.stamp) + "_" + iconPx + ".png";
    }

    /** Non-blocking. Calls listener.iconLoaded() when ready. Main thread only. */
    void requestIcon(App a) {
        if (a.icon != null) {
            lru.get(a); // mark as recently used
            return;
        }
        if (a.iconLoading) return;
        a.iconLoading = true;
        int px = iconPx;
        File f = new File(iconDir, iconName(a));
        bg.post(() -> {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
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
                trimIcons(ICON_LRU);
                if (listener != null) listener.iconLoaded();
            });
        });
    }

    /** Drop all but the most recently used icons (they reload from disk in ~1 ms). */
    void trimIcons(int keep) {
        Iterator<App> it = lru.keySet().iterator();
        for (int n = lru.size(); n > keep && it.hasNext(); n--) {
            it.next().icon = null;
            it.remove();
        }
    }

    void onHidden() {
        trimIcons(ICON_KEEP);
    }

    private Bitmap render(App a, int px) {
        try {
            Drawable d;
            if (a.setting) {
                // Settings page: the Settings app icon.
                d = ctx.getPackageManager().getApplicationIcon(a.cn.getPackageName());
            } else {
                LauncherActivityInfo info = la.resolveActivity(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn), a.user);
                if (info == null) return null;
                d = info.getIcon(ctx.getResources().getDisplayMetrics().densityDpi);
            }
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
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }
}
