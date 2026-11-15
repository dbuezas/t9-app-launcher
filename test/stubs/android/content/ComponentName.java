package android.content;
public class ComponentName {
    final String p, c;
    public ComponentName(String p, String c) { this.p = p; this.c = c; }
    public static ComponentName unflattenFromString(String s) { int i = s.indexOf('/'); return i < 0 ? null : new ComponentName(s.substring(0, i), s.substring(i + 1).startsWith(".") ? s.substring(0, i) + s.substring(i + 1) : s.substring(i + 1)); }
    public String getClassName() { return c; }
    public String flattenToShortString() { return p + "/" + c; }
}
