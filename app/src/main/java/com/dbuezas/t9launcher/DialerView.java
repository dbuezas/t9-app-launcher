package com.dbuezas.t9launcher;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * The whole UI in one View: a horizontally scrolling row of results above a
 * 3x3 T9 keypad (C, 2..9). No layouts, no adapters, no allocations per frame.
 */
@SuppressLint("ViewConstructor")
final class DialerView extends View implements Store.Listener {
    private static final String[] DIGITS = {"C", "12", "3", "4", "5", "6", "7", "8", "90"};
    private static final String[] LETTERS = {"CLEAR", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};
    private static final long LONG_PRESS_MS = 450;
    private static final int RESULTS_DP = 118, KEY_DP = 68, MAX_W_DP = 320, SIDE_DP = 24;

    // Theme colors: follow the system light/dark mode.
    private final int bg, text, dim, accent, press;

    /** The window is only as big as the panel: less GPU memory than a full-screen window. */
    static int panelHeight(float dp, boolean banner) {
        return Math.round(((banner ? BANNER_DP : 0) + RESULTS_DP + 3 * KEY_DP) * dp);
    }

    private static final int BANNER_DP = 40;
    private static final String BANNER_TEXT = "Open me by swiping from a corner  \u203A";
    /** "Set up the assistant gesture" bar on top, while the app is not the assistant. */
    boolean banner;

    static int panelWidth(float dp, int screenW) {
        return (int) Math.min(screenW - 2 * SIDE_DP * dp, MAX_W_DP * dp);
    }

    private static final Comparator<App> ORDER = (a, b) -> {
        // Most recently opened (from this app) first, then match quality.
        if (a.rank != b.rank) return a.rank > b.rank ? -1 : 1;
        if (a.tier != b.tier) return a.tier - b.tier;
        if (a.setting != b.setting) return a.setting ? 1 : -1; // apps before settings
        return a.sortKey.compareTo(b.sortKey);
    };

    private final MainActivity act;
    private final Store store;
    private final float dp;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint labelPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeL = new Paint(), fadeR = new Paint();
    private float fadeW;
    /** Everything outside the rounded card: cleared last, so scrolled icons never show in the corners. */
    private final android.graphics.Path outside = new android.graphics.Path();
    private final RectF tmpF = new RectF();
    private final Rect tmp = new Rect();
    private final OverScroller scroller;
    private final int touchSlop;

    // Layout (px).
    private float panelL, panelR, panelT, resultsB, keyH, keyW, cellW, iconSize, labelY, lineH, digitCapH;

    // Search state.
    private final char[] query = new char[64];
    private int qlen;
    private int version;
    private final ArrayList<App> results = new ArrayList<>();

    // Touch state.
    private static final int T_NONE = 0, T_KEY = 1, T_RESULT = 2, T_DRAG = 3, T_OUTSIDE = 4;
    private int touch = T_NONE, pressedKey = -1, pressedResult = -1;
    private float downX, downY, lastX, scrollX;
    private VelocityTracker velocity;
    private final Runnable longPress = this::onLongPress;

    DialerView(MainActivity act, Store store) {
        super(act);
        this.act = act;
        this.store = store;
        dp = getResources().getDisplayMetrics().density;
        scroller = new OverScroller(act);
        touchSlop = ViewConfiguration.get(act).getScaledTouchSlop();

        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF202226 : 0xFFF6F7F9;
        text = night ? 0xFFE8EAED : 0xFF202124;
        dim = night ? 0xFF9AA0A6 : 0xFF6B7078;
        accent = night ? 0xFF7FB2FF : 0xFF1A6FE0;
        press = night ? 0x1FFFFFFF : 0x14000000;

        bgPaint.setColor(bg);
        digitPaint.setColor(accent);
        digitPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        digitPaint.setTextSize(24 * dp);
        digitPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setColor(dim);
        letterPaint.setTextSize(16 * dp);
        letterPaint.setLetterSpacing(0.05f);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(14 * dp);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        linePaint.setStrokeWidth(3 * dp);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        clearPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        setHapticFeedbackEnabled(true);
        search();
    }

    // ------------------------------------------------------------------ data

    @Override
    public void appsChanged() {
        search();
    }

    @Override
    public void iconLoaded() {
        invalidate();
    }

    void reset() {
        qlen = 0;
        search();
    }

    private void search() {
        App[] apps = store.apps;
        results.clear();
        // The list may change under a finger: never launch a stale index.
        removeCallbacks(longPress);
        pressedResult = -1;
        if (touch == T_RESULT) touch = T_NONE;
        for (App a : apps) {
            // Settings pages stay out of the empty list until they were used once.
            if (qlen == 0 && a.setting && a.usage == null) continue;
            int t = a.match(query, qlen);
            a.tier = t;
            if (t < 0) continue;
            a.rank = Store.rank(a);
            results.add(a);
        }
        Collections.sort(results, ORDER);
        version++;
        scroller.forceFinished(true);
        scrollX = 0;
        invalidate();
    }

    private void type(int key) {
        if (key == 0) {
            if (qlen == 0) {
                // Nothing to clear: just scroll back to the start.
                scroller.forceFinished(true);
                scrollX = 0;
                invalidate();
                return;
            }
            qlen = 0;
        } else {
            if (qlen == query.length) return;
            query[qlen++] = (char) ('1' + key);
        }
        search();
    }

    /** Hardware keyboard: letters and digits type, Del removes, Enter launches. */
    boolean onHardKey(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_DEL) {
            if (qlen > 0) {
                qlen--;
                search();
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (!results.isEmpty()) launch(0);
            return true;
        }
        char d = App.digitFor((char) e.getUnicodeChar());
        if (d >= '2' && d <= '9') {
            type(d - '1');
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // The window is exactly the panel; fill it.
        float panelW = w;
        panelL = 0;
        panelR = w;
        panelT = banner ? BANNER_DP * dp : 0;
        resultsB = panelT + RESULTS_DP * dp;
        keyW = panelW / 3;
        keyH = (h - resultsB) / 3;
        float r = 28 * dp;
        outside.reset();
        outside.addRect(0, 0, w, h, android.graphics.Path.Direction.CW);
        android.graphics.Path card = new android.graphics.Path();
        card.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
        outside.op(card, android.graphics.Path.Op.DIFFERENCE);
        // Edge fades: the app cut by the scroll edge melts into the card.
        fadeW = 32 * dp;
        int clear = bg & 0x00FFFFFF;
        fadeL.setShader(new android.graphics.LinearGradient(0, 0, fadeW, 0, bg, clear, android.graphics.Shader.TileMode.CLAMP));
        fadeR.setShader(new android.graphics.LinearGradient(w - fadeW, 0, w, 0, clear, bg, android.graphics.Shader.TileMode.CLAMP));
        cellW = panelW / 3.5f; // the half app shows the row scrolls
        iconSize = store.iconPx; // drawn 1:1, no scaling
        labelY = panelT + 12 * dp + iconSize + 22 * dp;
        lineH = 18 * dp;
        digitPaint.getTextBounds("C", 0, 1, tmp);
        digitCapH = tmp.height();
        clampScroll();
    }

    private float maxScroll() {
        return Math.max(0, results.size() * cellW - (panelR - panelL));
    }

    private void clampScroll() {
        scrollX = Math.max(0, Math.min(scrollX, maxScroll()));
    }

    private int keyAt(float x, float y) {
        if (x < panelL || x >= panelR || y < resultsB) return -1;
        int col = Math.min(2, (int) ((x - panelL) / keyW));
        int row = Math.min(2, (int) ((y - resultsB) / keyH));
        return row * 3 + col;
    }

    private int resultAt(float x, float y) {
        if (x < panelL || x >= panelR || y < panelT || y >= resultsB) return -1;
        int i = (int) ((x - panelL + scrollX) / cellW);
        return i < results.size() ? i : -1;
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas c) {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.getCurrX();
            clampScroll();
            postInvalidateOnAnimation();
        }
        float r = 28 * dp;
        tmpF.set(panelL, 0, panelR, getHeight());
        c.drawRoundRect(tmpF, r, r, bgPaint);

        if (banner) {
            float by = panelT * 0.5f + labelPaint.getTextSize() * 0.36f + 4 * dp;
            labelPaint.setColor(accent);
            c.drawText(BANNER_TEXT, panelL + 24 * dp, by, labelPaint);
            labelPaint.setColor(dim);
            c.drawText("\u2715", panelR - 34 * dp, by, labelPaint);
        }
        drawResults(c);

        // Keypad.
        for (int k = 0; k < 9; k++) {
            float x = panelL + (k % 3) * keyW, y = resultsB + (k / 3) * keyH;
            float cx = x + keyW / 2;
            if (k == pressedKey) {
                linePaint.setColor(press);
                float pr = Math.min(keyW, keyH) / 2 - 2 * dp;
                c.drawCircle(cx, y + keyH / 2, pr, linePaint);
            }
            c.drawText(DIGITS[k], cx, y + keyH * 0.44f, digitPaint);
            c.drawText(LETTERS[k], cx, y + keyH * 0.78f, letterPaint);
        }
        c.drawPath(outside, clearPaint);
    }

    private void drawResults(Canvas c) {
        int n = results.size();
        c.save();
        c.clipRect(panelL, panelT, panelR, resultsB);
        if (n == 0) {
            labelPaint.setColor(dim);
            String s = qlen == 0 ? "Loading apps…" : "No match";
            c.drawText(s, (panelL + panelR - labelPaint.measureText(s)) / 2, panelT + 60 * dp, labelPaint);
        }
        int first = Math.max(0, (int) (scrollX / cellW));
        int last = Math.min(n - 1, (int) ((scrollX + panelR - panelL) / cellW));
        float textMax = cellW - 8 * dp;
        for (int i = first; i <= last; i++) {
            App a = results.get(i);
            float x = panelL + i * cellW - scrollX;
            if (i == pressedResult) {
                linePaint.setColor(press);
                tmpF.set(x + 4 * dp, panelT + 4 * dp, x + cellW - 4 * dp, resultsB - 10 * dp);
                c.drawRoundRect(tmpF, 20 * dp, 20 * dp, linePaint);
            }
            float ix = x + (cellW - iconSize) / 2, iy = panelT + 12 * dp;
            store.requestIcon(a);
            if (a.icon != null) {
                c.drawBitmap(a.icon, Math.round(ix), Math.round(iy), iconPaint);
            }
            drawLabel(c, a, x, textMax);
        }
        if (scrollX > 0) c.drawRect(panelL, panelT, panelL + fadeW, resultsB, fadeL);
        if (scrollX < maxScroll()) c.drawRect(panelR - fadeW, panelT, panelR, resultsB, fadeR);
        c.restore();
        // Scroll bar: a faint track and a thumb showing the visible part of the row.
        float total = n * cellW, view = panelR - panelL;
        if (total > view) {
            // Centered between the second label line and the top of the key digits.
            float labelBottom = labelY + lineH + labelPaint.descent();
            float digitTop = resultsB + keyH * 0.44f - digitCapH;
            float sy = (labelBottom + digitTop) / 2, inset = 12 * dp, track = view - 2 * inset;
            linePaint.setColor((dim & 0x00FFFFFF) | 0x33000000);
            c.drawLine(panelL + inset, sy, panelR - inset, sy, linePaint);
            float bw = Math.max(20 * dp, track * view / total);
            float bx = panelL + inset + scrollX / maxScroll() * (track - bw);
            linePaint.setColor(dim);
            c.drawLine(bx, sy, bx + bw, sy, linePaint);
        }
    }

    /**
     * Label in up to two centered lines: broken between words when that fits, else at any
     * character (no space wasted); the second line is ellipsized. Matched letters are drawn in the accent color.
     */
    private void drawLabel(Canvas c, App a, float x, float max) {
        a.highlight(query, qlen, version);
        // A settings page matched only by its class-name words shows those words ("Bluetooth").
        boolean useAlias = a.aliasStart > 0 && qlen > 0
                && !anyHl(a, 0, a.label.length()) && anyHl(a, a.aliasStart, a.hl.length);
        String l = useAlias ? a.alias : a.label;
        int off = useAlias ? a.aliasStart : 0;
        App.Lines ln = useAlias ? a.aliasLines : a.labelLines;
        if (ln.width != max) {
            int len = l.length();
            int k = labelPaint.breakText(l, true, max, null);
            if (k <= 0 || k >= len) {
                ln.end1 = len;
                ln.line2 = null;
            } else {
                // Prefer a word break (at a space or after a hyphen) when the rest then fits on
                // line 2; otherwise break at any character, which fits the most text.
                for (int i = k; i > 0; i--) {
                    boolean space = l.charAt(i) == ' ', hyphen = l.charAt(i - 1) == '-';
                    if (!space && !hyphen) continue;
                    int s2 = i;
                    while (s2 < len && l.charAt(s2) == ' ') s2++;
                    if (labelPaint.measureText(l, s2, len) <= max) k = i;
                    break;
                }
                int e1 = k;
                while (e1 > 0 && l.charAt(e1 - 1) == ' ') e1--;
                int s2 = k;
                while (s2 < len && l.charAt(s2) == ' ') s2++;
                ln.end1 = e1;
                ln.start2 = s2;
                ln.line2 = s2 >= len ? null
                        : TextUtils.ellipsize(l.substring(s2), labelPaint, max, TextUtils.TruncateAt.END).toString();
            }
            ln.width = max;
        }
        float cx = x + cellW / 2;
        drawLine(c, a, l, ln.end1, off, false, cx, labelY);
        String s2 = ln.line2;
        if (s2 != null) drawLine(c, a, s2, s2.length(), off + ln.start2, s2.length() != l.length() - ln.start2, cx, labelY + lineH);
    }

    private static boolean anyHl(App a, int from, int to) {
        for (int i = from; i < to; i++) if (a.hl[i]) return true;
        return false;
    }

    /** s[0, end) centered at cx; char i of s is label char off + i. */
    private void drawLine(Canvas c, App a, String s, int end, int off, boolean ellipsized, float cx, float y) {
        float tx = cx - labelPaint.measureText(s, 0, end) / 2;
        int i = 0;
        while (i < end) {
            boolean h = isHl(a, i, off, ellipsized, end);
            int j = i + 1;
            while (j < end && isHl(a, j, off, ellipsized, end) == h) j++;
            labelPaint.setColor(h ? accent : text);
            c.drawText(s, i, j, tx, y, labelPaint);
            tx += labelPaint.measureText(s, i, j);
            i = j;
        }
    }

    private static boolean isHl(App a, int i, int off, boolean ellipsized, int end) {
        if (ellipsized && i == end - 1) return false; // the "…"
        int li = off + i;
        return li < a.hl.length && a.hl[li];
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                float x = e.getX(i), y = e.getY(i);
                int k = keyAt(x, y);
                if (k >= 0) {
                    // Keys fire on touch down: fastest feel, and two thumbs can type at once.
                    removeCallbacks(longPress);
                    pressedResult = -1;
                    pressedKey = k;
                    touch = T_KEY;
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    type(k);
                    return true;
                }
                if (action != MotionEvent.ACTION_DOWN) return true;
                downX = lastX = x;
                downY = y;
                if (y < panelT) {
                    touch = T_OUTSIDE;
                } else if (y < resultsB) {
                    scroller.forceFinished(true);
                    pressedResult = resultAt(x, y);
                    touch = T_RESULT;
                    if (pressedResult >= 0) postDelayed(longPress, LONG_PRESS_MS);
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (touch == T_RESULT && Math.abs(e.getX() - downX) > touchSlop) {
                    touch = T_DRAG;
                    pressedResult = -1;
                    removeCallbacks(longPress);
                    lastX = e.getX();
                }
                if (touch == T_DRAG) {
                    scrollX += lastX - e.getX();
                    lastX = e.getX();
                    clampScroll();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                pressedKey = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (touch == T_RESULT && pressedResult >= 0) launch(pressedResult);
                else if (touch == T_DRAG) {
                    velocity.computeCurrentVelocity(1000);
                    scroller.fling((int) scrollX, 0, (int) -velocity.getXVelocity(), 0,
                            0, (int) maxScroll(), 0, 0);
                } else if (touch == T_OUTSIDE && e.getY() < panelT) {
                    if (e.getX() > panelR - 56 * dp) act.dismissSetup();
                    else act.openSetup();
                }
                endTouch();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                endTouch();
                return true;
        }
        return true;
    }

    private void endTouch() {
        touch = T_NONE;
        pressedKey = -1;
        pressedResult = -1;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        invalidate();
    }

    private void onLongPress() {
        if (touch != T_RESULT || pressedResult < 0) return;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int i = pressedResult;
        touch = T_NONE;
        pressedResult = -1;
        invalidate();
        act.showInfo(results.get(i), bounds(i));
    }

    private Rect bounds(int i) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int x = (int) (panelL + i * cellW - scrollX) + loc[0], y = (int) panelT + loc[1];
        tmp.set(x, y, x + (int) cellW, (int) resultsB + loc[1]);
        return tmp;
    }

    private void launch(int i) {
        App a = results.get(i);
        act.launch(a, bounds(i));
    }
}
