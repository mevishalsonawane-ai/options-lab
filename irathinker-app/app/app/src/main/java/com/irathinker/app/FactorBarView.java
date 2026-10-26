package com.irathinker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** One diverging factor bar for the Market Meter: a centre-zero
 *  track; the fill runs right (green) for a bullish factor score and
 *  left (red) for a bearish one. NaN draws the bare track. */
public class FactorBarView extends View {

    private double score = Double.NaN;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public FactorBarView(Context c) {
        super(c);
    }

    public void setScore(double s) {
        score = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        float w = getWidth(), h = getHeight();
        float mid = w / 2f, cy = h / 2f;
        float tick = Ui.dp(getContext(), 4);
        p.setColor(Ui.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 2));
        cv.drawLine(0, cy, w, cy, p);
        cv.drawLine(mid, cy - tick, mid, cy + tick, p);
        if (Double.isNaN(score)) return;
        double s = Math.max(-1, Math.min(1, score));
        p.setColor(s >= 0 ? Ui.GREEN : Ui.RED);
        p.setStrokeWidth(Ui.dp(getContext(), 6));
        p.setStrokeCap(Paint.Cap.ROUND);
        cv.drawLine(mid, cy, mid + (float) (s * (w / 2f - 2)), cy, p);
    }
}
