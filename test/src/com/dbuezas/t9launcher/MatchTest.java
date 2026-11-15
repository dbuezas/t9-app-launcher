package com.dbuezas.t9launcher;

import java.util.Random;

/** Matching tests: run with scripts/test.sh. Exits with 1 on any failure. */
public class MatchTest {
    static int fails, ver;

    static App app(String label) {
        return App.of("x/.Y", 0, null, label, 0);
    }

    static App setting(String label, String cls) {
        return App.of("!com.android.settings/com.android.settings." + cls, 0, null, label, 0);
    }

    static void fail(String s) {
        fails++;
        if (fails <= 20) System.out.println("FAIL " + s);
    }

    /** The shown text, with the highlighted letters in upper case and the rest in lower case. */
    static String shown(App a) {
        String text = a.alias == null ? a.label : a.label + " " + a.alias;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            b.append(a.hl[i] ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** want: the tier (-1 = no match). wantHl: the highlight, or null to only check that something is blue. */
    static void check(App a, String keys, int want, String wantHl) {
        char[] q = keys.toCharArray();
        a.tier = a.match(q, q.length);
        a.highlight(q, q.length, ++ver);
        String hl = shown(a);
        boolean any = false;
        for (boolean b : a.hl) any |= b;
        if (a.tier != want || (wantHl != null && !hl.equals(wantHl)) || (want >= 0 && !any)) {
            fail(a.label + " / " + keys + " -> tier " + a.tier + " (want " + want + "), shown " + hl);
        }
    }

    static void examples() {
        // The README examples.
        check(app("Hearts"), "432", 0, "HEArts");
        check(app("Google Maps"), "6277", 1, "google MAPS");
        check(app("Google Maps"), "4627", 2, "Google MAPs");
        check(app("Google Play Music"), "476", 2, "Google Play Music");
        check(app("Exclude"), "2583", 3, "exCLUDe");
        check(app("Radio 100"), "299", 1, "radio 100");
        check(app("YouTube"), "8823", 1, "youTUBE");
        check(app("PDFViewer"), "8439", 1, "pdfVIEWer");
        check(app("Fußball"), "38772", 0, null);
        check(app("Øresund"), "67378", 0, null);
        check(setting("Devices", "Settings$BluetoothSettingsActivity"), "2583", 1, null);
        // Start of the name, before the word starts.
        check(app("Google Maps"), "466", 0, "GOOgle maps");
        // Special letters and forms.
        check(app("Łódź"), "5639", 0, "ŁÓDŹ");
        check(app("Æble"), "23253", 0, null);
        check(app("ﬁnance"), "34626", 0, null);
        check(app("ＡＢＣ App"), "22227", 0, null);
        check(app("Résumé Builder"), "737", 0, null);
        check(app("Résumé Builder"), "7863", 3, null);
        check(app("𤋮 App"), "277", 0, null); // a sign without letters is skipped
        // Capitals inside a name: an all-caps run splits before a camelCase word.
        check(app("HPSmart"), "76278", 1, "hpSMART");
        check(app("PDFViewer"), "733", 0, "PDFviewer");
        // Apostrophes stay inside the word.
        check(app("Today's News"), "8632", 0, null);
        // Settings: the class name words count only when one of them starts with the keys.
        check(setting("Devices", "Settings$BluetoothSettingsActivity"), "3384", 0, null);
        check(setting("Alarms", "Settings$CalendarActivity"), "225", 1, "alarms CALendar");
        check(setting("Alarms", "Settings$CalendarActivity"), "2536", -1, null);
        // No match.
        check(app("Google Maps"), "999", -1, null);
    }

    // ---------------------------------------------------------------- reference matcher

    // The README rules written the slow, simple way, on the same index (codes, word starts, split).
    static boolean at(App a, int s, char[] q, int n, int end) {
        if (s + n > end) return false;
        for (int i = 0; i < n; i++) if (a.codes[s + i] != q[i]) return false;
        return true;
    }

    static boolean chain(App a, char[] q, int n, int qi, int w) {
        if (qi == n) return true;
        if (w >= a.splitWord) return false;
        int s = a.ws[w], e = w + 1 < a.ws.length ? a.ws[w + 1] : a.codes.length;
        for (int l = 0; s + l < e && qi + l < n && a.codes[s + l] == q[qi + l]; l++)
            if (chain(a, q, n, qi + l + 1, w + 1)) return true;
        return false;
    }

    static int ref(App a, char[] q, int n) {
        if (n == 0) return 0;
        if (a.ws.length == 0) return -1;
        if (a.splitWord > 0 && at(a, 0, q, n, a.splitCode)) return 0;
        for (int i = 1; i < a.ws.length; i++) {
            int s = a.ws[i];
            if (at(a, s, q, n, s < a.splitCode ? a.splitCode : a.codes.length)) return 1;
        }
        if (a.splitWord == 0 && at(a, 0, q, n, a.codes.length)) return 1;
        if (chain(a, q, n, 0, 0)) return 2;
        if (n >= 3) for (int w = 0; w < a.splitWord; w++) {
            int s = a.ws[w], e = w + 1 < a.ws.length ? a.ws[w + 1] : a.codes.length;
            for (int k = s; k + n <= e; k++) if (k > 0 && at(a, k, q, n, e)) return 3;
        }
        return -1;
    }

    static void fuzz() {
        Random r = new Random(1);
        String alpha = "abcdefgABCDG01 -.é'ß";
        for (int it = 0; it < 300000; it++) {
            int len = r.nextInt(it % 50 == 0 ? 300 : 14);
            StringBuilder lb = new StringBuilder();
            for (int i = 0; i < len; i++) lb.append(alpha.charAt(r.nextInt(alpha.length())));
            String label = lb.toString();
            App a = r.nextBoolean() ? app(label)
                    : setting(label, "Settings$" + (r.nextBoolean() ? "AbcDefActivity" : "BAd2CafeSettingsActivity"));
            int n = 1 + r.nextInt(r.nextInt(10) == 0 ? 64 : 6);
            char[] q = new char[64];
            for (int i = 0; i < n; i++) q[i] = (char) ('2' + r.nextInt(r.nextInt(3) == 0 ? 2 : 8));
            String keys = new String(q, 0, n);
            try {
                int t = a.match(q, n), want = ref(a, q, n);
                if (t != want) fail("'" + label + "' / " + keys + " -> tier " + t + ", reference " + want);
                a.tier = t;
                a.highlight(q, n, ++ver);
                int blue = 0;
                for (boolean b : a.hl) if (b) blue++;
                // Every key lights exactly one letter (a folded letter like ß may light one for two keys).
                if (t >= 0 && (blue == 0 || blue > n)) fail("'" + label + "' / " + keys + " -> " + blue + " blue letters");
            } catch (RuntimeException e) {
                fail("'" + label + "' / " + keys + " -> " + e);
            }
        }
    }

    public static void main(String[] args) {
        examples();
        fuzz();
        System.out.println(fails == 0 ? "All matching tests passed." : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
