package com.irathinker.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** IraThinker design system: a deep-space navy theme with the icon's
 *  blue/orange accents, rounded cards, pills and stat blocks. */
public final class Ui {

    public static final int BG_TOP = Color.rgb(6, 14, 32);
    public static final int BG_BOT = Color.rgb(2, 5, 12);
    public static final int CARD = Color.rgb(13, 26, 48);
    public static final int CARD_ALT = Color.rgb(17, 34, 62);
    public static final int STROKE = Color.rgb(38, 62, 102);
    public static final int TXT = Color.rgb(235, 241, 250);
    public static final int DIM = Color.rgb(148, 168, 196);
    public static final int FAINT = Color.rgb(104, 124, 156);
    public static final int GREEN = Color.rgb(38, 208, 124);
    public static final int RED = Color.rgb(255, 94, 98);
    public static final int ORANGE = Color.rgb(255, 159, 46);
    public static final int BLUE = Color.rgb(64, 156, 255);

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Edge-to-edge is enforced on modern Android: pad a screen root by the
     *  status/navigation bar insets so content never slides underneath them. */
    public static void applySystemBarPadding(final android.view.View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop();
        final int r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int tl, tt, tr, tb;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                tl = bars.left; tt = bars.top; tr = bars.right; tb = bars.bottom;
            } else {
                tl = insets.getSystemWindowInsetLeft();
                tt = insets.getSystemWindowInsetTop();
                tr = insets.getSystemWindowInsetRight();
                tb = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(l + tl, t + tt, r + tr, b + tb);
            return insets;
        });
        v.requestApplyInsets();
    }

    /** Explicit back affordance - every sub-screen starts with one. */
    public static android.widget.Button backButton(Context c, Runnable onBack) {
        android.widget.Button b = new android.widget.Button(c);
        b.setText("← BACK");
        b.setTextColor(TXT);
        b.setTextSize(12.5f);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setBackground(cardBg(CARD_ALT, 10, c));
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, 0, dp(c, 12));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onBack.run());
        return b;
    }

    /** Vertical gradient for screen roots. */
    public static GradientDrawable screenBg() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOT});
        return g;
    }

    /** Rounded card background. */
    public static GradientDrawable cardBg(int fill, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 1), STROKE);
        return g;
    }

    public static GradientDrawable pillBg(int fill, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 40));
        return g;
    }

    public static GradientDrawable accentBtnBg(Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(28, 96, 196), Color.rgb(16, 62, 138)});
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, 1), Color.rgb(64, 140, 230));
        return g;
    }

    public static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    public static TextView mono(Context c, String text, float sp, int color, boolean bold) {
        TextView t = tv(c, text, sp, color, bold);
        t.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        return t;
    }

    /** Small rounded pill label, e.g. a setup badge or a status chip. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = tv(c, text, 11, fg, true);
        t.setBackground(pillBg(bg, c));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** A card container with standard padding and margins. */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(cardBg(CARD, 16, c));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(c, 12));
        l.setLayoutParams(lp);
        return l;
    }

    /** Section title inside a card. */
    public static TextView sectionTitle(Context c, String text) {
        TextView t = tv(c, text.toUpperCase(), 12, BLUE, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, 0, 0, dp(c, 8));
        return t;
    }

    /** One column of a stat row: big value over a small caption. */
    public static LinearLayout stat(Context c, String value, String caption, int valueColor) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.addView(mono(c, value, 16, valueColor, true));
        TextView cap = tv(c, caption, 10.5f, FAINT, false);
        cap.setGravity(Gravity.CENTER);
        l.addView(cap);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        return l;
    }

    /** Horizontal bar showing a percentage (for odds). */
    public static LinearLayout bar(Context c, String label, double pct, int color) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = tv(c, label, 12.5f, DIM, false);
        lab.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 96), -2));
        row.addView(lab);
        LinearLayout track = new LinearLayout(c);
        track.setBackground(pillBg(Color.rgb(24, 40, 68), c));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(c, 10), 1);
        track.setLayoutParams(tlp);
        LinearLayout fill = new LinearLayout(c);
        fill.setBackground(pillBg(color, c));
        int w = (int) Math.max(2, pct);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, w));
        track.addView(fill);
        LinearLayout spacer = new LinearLayout(c);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, -1, (float) Math.max(0.1, 100 - pct)));
        track.addView(spacer);
        row.addView(track);
        TextView val = mono(c, String.format("%.0f%%", pct), 12.5f, TXT, true);
        val.setPadding(dp(c, 8), 0, 0, 0);
        row.addView(val);
        row.setPadding(0, dp(c, 3), 0, dp(c, 3));
        return row;
    }
}
