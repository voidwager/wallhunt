package dev.voidwager.wallhunt;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/** Colours and small view builders shared by both screens. */
final class Ui {
    static final int BG = 0xFF15131C, CARD = 0xFF211E2B, INK = 0xFFECE8F2, DIM = 0xFF9A93A8, ACCENT = 0xFFE0A458;

    private Ui() {}

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(primary ? BG : INK);
        b.setBackground(round(c, primary ? ACCENT : CARD, 12));
        b.setStateListAnimator(null);
        return b;
    }

    static GradientDrawable round(Context c, int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, r));
        return g;
    }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
