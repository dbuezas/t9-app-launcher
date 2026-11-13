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
    private static final String[] DIGITS = {"C", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final String[] LETTERS = {"clear", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    private static final long LONG_PRESS_MS = 450;
    private static final int RESULTS_DP = 116, KEY_DP = 68, MAX_W_DP = 320, SIDE_DP = 24;

    // Theme colors: follow the system light/dark mode.
    private final int bg, text, dim, accent, press;

    /** The window is only as big as the panel: less GPU memory than a full-screen window. */
    static int panelHeight(float dp) {
        return Math.round((RESULTS_DP + 3 * KEY_DP) * dp);
    }

    static int panelWidth(float dp, int screenW) {
        return (int) Math.min(screenW - 2 * SIDE_DP * dp, MAX_W_DP * dp);
    }

    private static final Comparator<App> ORDER = (a, b) -> {
        // Most recently opened (from this app) first, then match quality.
        if (a.rank != b.rank) return a.rank > b.rank ? -1 : 1;
        if (a.tier != b.tier) return a.tier - b.tier;
        return a.sortKey.compareTo(b.sortKey);
    };

    private final MainActivity act;
    private final Store store;
    private final float dp;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF tmpF = new RectF();
    private final Rect tmp = new Rect();
    private final OverScroller scroller;
    private final int touchSlop;

    // Layout (px).
    private float panelL, panelR, panelT, resultsB, keyH, keyW, cellW, iconSize, labelY;

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
        letterPaint.setTextSize(14 * dp);
        letterPaint.setLetterSpacing(0.08f);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(14 * dp);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        linePaint.setStrokeWidth(3 * dp);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
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
        double now = System.currentTimeMillis() / 1000.0;
        results.clear();
        for (App a : apps) {
            int t = a.match(query, qlen);
            a.tier = t;
            if (t < 0) continue;
            a.rank = Store.rank(a, now);
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
                act.finish();
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
        panelT = 0;
        resultsB = RESULTS_DP * dp;
        keyW = panelW / 3;
        keyH = (h - resultsB) / 3;
        cellW = panelW / 3.5f; // the half app shows the row scrolls
        iconSize = 56 * dp;
        labelY = panelT + 12 * dp + iconSize + 24 * dp;
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
        tmpF.set(panelL, panelT, panelR, getHeight());
        c.drawRoundRect(tmpF, r, r, bgPaint);

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
            c.drawText(DIGITS[k], cx, y + keyH * 0.48f, digitPaint);
            c.drawText(LETTERS[k], cx, y + keyH * 0.80f, letterPaint);
        }
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
                tmpF.set(ix, iy, ix + iconSize, iy + iconSize);
                c.drawBitmap(a.icon, null, tmpF, iconPaint);
            }
            drawLabel(c, a, x, textMax);
        }
        c.restore();
        // Scroll hint: a thin bar showing the visible part of the row.
        float total = n * cellW, view = panelR - panelL;
        if (total > view) {
            float bw = view * view / total, bx = panelL + scrollX / total * view;
            linePaint.setColor(dim);
            float sy = resultsB - 6 * dp, inset = 24 * dp, track = view - 2 * inset;
            bx = panelL + inset + scrollX / total * track;
            bw = track * view / total;
            c.drawLine(bx, sy, bx + bw, sy, linePaint);
        }
    }

    /** Label centered in its cell, ellipsized, with the matched letters in blue. */
    private void drawLabel(Canvas c, App a, float x, float max) {
        if (a.shown == null || a.shownFor != max) {
            a.shown = TextUtils.ellipsize(a.label, new android.text.TextPaint(labelPaint), max, TextUtils.TruncateAt.END).toString();
            a.shownFor = max;
        }
        String s = a.shown;
        a.highlight(query, qlen, version);
        float tx = x + (cellW - labelPaint.measureText(s)) / 2;
        boolean ellipsized = s.length() != a.label.length();
        int len = s.length(), i = 0;
        while (i < len) {
            boolean h = !(ellipsized && i == len - 1) && i < a.hl.length && a.hl[i];
            int j = i + 1;
            while (j < len && (!(ellipsized && j == len - 1) && j < a.hl.length && a.hl[j]) == h) j++;
            labelPaint.setColor(h ? accent : text);
            c.drawText(s, i, j, tx, labelY, labelPaint);
            tx += labelPaint.measureText(s, i, j);
            i = j;
        }
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
                } else if (touch == T_OUTSIDE && e.getY() < panelT) act.finish();
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
        App a = results.get(pressedResult);
        touch = T_NONE;
        pressedResult = -1;
        invalidate();
        act.showInfo(a, bounds(results.indexOf(a)));
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
