package com.irathinker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Semicircular sentiment dial for the Market Meter: five coloured
 *  zones from -100 (left, red) to +100 (right, green) and a needle.
 *  Angles follow the Android canvas convention: 180 degrees is the
 *  left end, 270 the top, 360 the right end. */
public class MeterView extends View {

    private int score = 0;
    private boolean hasScore = false;
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MeterView(Context c) {
        super(c);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        needle.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setScore(int s, boolean has) {
        score = s;
        hasScore = has;
        invalidate();
    }

    private void zone(Canvas cv, RectF oval, int from, int to, int color) {
        arc.setColor(color);
        cv.drawArc(oval, 180 + (from + 100) * 0.9f, (to - from) * 0.9f, false, arc);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float w = getWidth(), h = getHeight();
        float stroke = Ui.dp(getContext(), 11);
        arc.setStrokeWidth(stroke);
        float r = Math.min(w / 2f, h) - stroke - Ui.dp(getContext(), 3);
        float cx = w / 2f, cy = h - Ui.dp(getContext(), 10);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        zone(cv, oval, -100, -40, Ui.RED);
        zone(cv, oval, -40, -15, Ui.ORANGE);
        zone(cv, oval, -15, 15, Ui.STROKE);
        zone(cv, oval, 15, 40, Color.rgb(26, 140, 80));
        zone(cv, oval, 40, 100, Ui.GREEN);
        if (!hasScore) return;
        double ang = Math.toRadians(180 + (score + 100) * 0.9);
        float nx = cx + (float) Math.cos(ang) * r * 0.8f;
        float ny = cy + (float) Math.sin(ang) * r * 0.8f;
        needle.setStyle(Paint.Style.STROKE);
        needle.setColor(Ui.TXT);
        needle.setStrokeWidth(Ui.dp(getContext(), 3));
        cv.drawLine(cx, cy, nx, ny, needle);
        needle.setStyle(Paint.Style.FILL);
        cv.drawCircle(cx, cy, Ui.dp(getContext(), 5), needle);
    }
}
