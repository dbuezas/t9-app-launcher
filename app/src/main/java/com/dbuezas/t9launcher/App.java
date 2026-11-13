package com.dbuezas.t9launcher;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.os.UserHandle;

import java.text.Normalizer;

/** One launchable activity (or settings page) plus its precomputed T9 index. */
final class App {
    private static final String T9 = "22233344455566677778889999";
    /** chain() failure memo: bit w of FAIL[qi] = "q[qi..] can't start at word w". Main thread only. */
    private static final long[] FAIL = new long[65];

    final String id;           // "pkg/.Activity"; "!pkg/.Activity" for a settings page
    final String key;          // id + "#" + userSerial, used for usage stats and icon cache
    final ComponentName cn;
    final boolean setting;     // a page of the Settings app, not a launcher app
    final long serial;
    final UserHandle user;
    final String label;
    /** Settings only: the class-name words ("Bluetooth"), shown when only they matched. */
    final String alias;
    final int aliasStart;      // index of alias in the indexed text, -1 if none
    final String sortKey;
    final long stamp;          // APK modification time; invalidates the cached icon

    /** T9 digit of every indexed char, all words joined. '?' = letter that has no T9 key. */
    final char[] codes;
    /** Start index (in codes) of every word. */
    final int[] ws;
    /** For every code, the index of its char in label (for highlighting). */
    final int[] pos;

    double[] usage;            // {score, epochSeconds}, shared with Store.usage
    Bitmap icon;
    boolean iconLoading;

    // Per-search scratch.
    int tier;
    double rank;

    // Draw cache.
    /** Two-line layouts of the label and of the alias, cached per width. */
    final Lines labelLines = new Lines(), aliasLines = new Lines();

    static final class Lines {
        float width;           // width the split was made for
        int end1, start2;      // line 1 = s[0, end1); line 2 = s[start2..], ellipsized into line2
        String line2;
    }
    final boolean[] hl;
    int hlVersion = -1;

    /** From a saved/scanned row. Returns null for a malformed id. */
    static App of(String id, long serial, UserHandle user, String label, long stamp) {
        boolean setting = id.startsWith("!");
        ComponentName cn = ComponentName.unflattenFromString(setting ? id.substring(1) : id);
        return cn == null ? null : new App(id, cn, setting, serial, user, label, stamp);
    }

    /**
     * Extra search words for a settings page, from its class name:
     * "Settings$BluetoothSettingsActivity" -> "Bluetooth" (Pixel labels that page "Devices").
     */
    private static String alias(ComponentName cn) {
        String s = cn.getClassName();
        s = s.substring(Math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1);
        if (s.endsWith("Activity")) s = s.substring(0, s.length() - 8);
        if (s.endsWith("Settings")) s = s.substring(0, s.length() - 8);
        // "ZenModeVoice" -> "Zen Mode Voice", "VPNSetup" -> "VPN Setup"
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(s.charAt(i - 1))
                    || i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1)))) b.append(' ');
            b.append(c);
        }
        return b.toString().trim();
    }

    private App(String id, ComponentName cn, boolean setting, long serial, UserHandle user, String label, long stamp) {
        this.id = id;
        this.cn = cn;
        this.setting = setting;
        this.serial = serial;
        this.user = user;
        this.label = label;
        this.stamp = stamp;
        this.key = id + "#" + serial;
        this.sortKey = label.toLowerCase();
        String al = setting ? alias(cn) : "";
        this.alias = al.isEmpty() ? null : al;
        this.aliasStart = this.alias == null ? -1 : label.length() + 1;

        // Indexed text: the label, then (for settings) the class-name words.
        String text = this.alias == null ? label : label + " " + this.alias;
        this.hl = new boolean[text.length()];
        int n = text.length();
        char[] c = new char[n];
        int[] p = new int[n];
        int[] w = new int[n + 1];
        int len = 0, words = 0;
        int prev = 0; // 0 boundary, 1 lower, 2 upper, 3 digit
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch >= 128 && Character.isLetter(ch)) {
                // Strip accents: é -> e, ñ -> n.
                String d = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD);
                if (d.length() > 0) ch = d.charAt(0);
            }
            int type;
            if (Character.isDigit(ch)) type = 3;
            else if (Character.isLetter(ch)) type = Character.isUpperCase(ch) ? 2 : 1;
            else {
                prev = 0;
                continue;
            }
            // New word at: start, after separator, letter<->digit, camelCase hump.
            if (prev == 0 || (type == 3) != (prev == 3) || (type == 2 && prev == 1)) w[words++] = len;
            char lc = Character.toLowerCase(ch);
            char code;
            if (lc >= 'a' && lc <= 'z') code = T9.charAt(lc - 'a');
            else if (type == 3) {
                int dv = Character.digit(ch, 10);
                code = dv >= 0 ? (char) ('0' + dv) : '?';
            } else code = '?';
            c[len] = code;
            p[len] = i;
            len++;
            prev = type;
        }
        codes = java.util.Arrays.copyOf(c, len);
        pos = java.util.Arrays.copyOf(p, len);
        ws = java.util.Arrays.copyOf(w, words);
    }

    static char digitFor(char ch) {
        char lc = Character.toLowerCase(ch);
        if (lc >= 'a' && lc <= 'z') return T9.charAt(lc - 'a');
        if (ch >= '0' && ch <= '9') return ch;
        return 0;
    }

    private boolean at(int s, char[] q, int n) {
        if (s + n > codes.length) return false;
        for (int i = 0; i < n; i++) if (codes[s + i] != q[i]) return false;
        return true;
    }

    /**
     * Match quality, lower is better; -1 = no match.
     * 0 name starts with query, 1 a word starts with query (may run into the next words),
     * 2 query is a chain of word prefixes ("gm" -> Google Maps), 3 substring (3+ digits).
     */
    int match(char[] q, int n) {
        if (n == 0) return 0;
        if (ws.length == 0) return -1;
        if (at(0, q, n)) return 0;
        for (int i = 1; i < ws.length; i++) if (at(ws[i], q, n)) return 1;
        if (chain0(q, n, null)) return 2;
        if (n >= 3) for (int s = 1; s + n <= codes.length; s++) if (at(s, q, n)) return 3;
        return -1;
    }

    private int wordEnd(int w) {
        return w + 1 < ws.length ? ws[w + 1] : codes.length;
    }

    private boolean chain0(char[] q, int n, boolean[] mark) {
        java.util.Arrays.fill(FAIL, 0, n + 1, 0);
        return chain(q, n, 0, 0, mark);
    }

    /**
     * Can q[qi..] be covered by prefixes of words w, w+1.. (words may be skipped)?
     * Memoized, so it stays O(query * words * wordLength) even for pathological names.
     */
    private boolean chain(char[] q, int n, int qi, int w, boolean[] mark) {
        if (qi == n) return true;
        if (w < 64 && (FAIL[qi] & (1L << w)) != 0) return false;
        for (int wi = w; wi < ws.length; wi++) {
            int s = ws[wi], e = wordEnd(wi);
            for (int l = 0; s + l < e && qi + l < n && codes[s + l] == q[qi + l]; l++) {
                if (chain(q, n, qi + l + 1, wi + 1, mark)) {
                    if (mark != null) for (int k = 0; k <= l; k++) if (pos[s + k] < mark.length) mark[pos[s + k]] = true;
                    return true;
                }
            }
        }
        if (w < 64) FAIL[qi] |= 1L << w;
        return false;
    }

    /** Fill hl[] with the label chars that the query matched. */
    void highlight(char[] q, int n, int version) {
        if (hlVersion == version) return;
        hlVersion = version;
        java.util.Arrays.fill(hl, false);
        if (n == 0 || tier < 0) return;
        int start = -1;
        if (tier == 0) start = 0;
        else if (tier == 1) {
            for (int i = 1; i < ws.length; i++) if (at(ws[i], q, n)) { start = ws[i]; break; }
        } else if (tier == 2) {
            chain0(q, n, hl);
            return;
        } else {
            for (int s = 1; s + n <= codes.length; s++) if (at(s, q, n)) { start = s; break; }
        }
        if (start >= 0) for (int k = 0; k < n; k++) if (pos[start + k] < hl.length) hl[pos[start + k]] = true;
    }
}
