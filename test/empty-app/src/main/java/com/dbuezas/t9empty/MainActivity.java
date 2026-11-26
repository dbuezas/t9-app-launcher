package com.dbuezas.t9empty;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

/** An empty card in the same window as T9 App Launcher's panel: the least that window can cost. */
public final class MainActivity extends Activity {
    // Panel size of T9 App Launcher, without the setup banner (see DialerView).
    private static final int RESULTS_DP = 118, KEY_DP = 68, MAX_W_DP = 320, SIDE_DP = 24;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new Card(this));
        placeWindow();
        setFinishOnTouchOutside(true);
    }

    private void placeWindow() {
        float dp = getResources().getDisplayMetrics().density;
        android.view.WindowMetrics m = getWindowManager().getCurrentWindowMetrics();
        int screenW = m.getBounds().width();
        int nav = m.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars()).bottom;
        android.view.Window w = getWindow();
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        lp.width = (int) Math.min(screenW - 2 * SIDE_DP * dp, MAX_W_DP * dp);
        lp.height = Math.round((RESULTS_DP + 3 * KEY_DP) * dp);
        lp.y = nav + Math.round(52 * dp);
        w.setAttributes(lp);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    private static final class Card extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r;

        Card(Context c) {
            super(c);
            boolean night = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            bg.setColor(night ? 0xFF202226 : 0xFFF6F7F9);
            r = 28 * getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, bg);
        }
    }
}
