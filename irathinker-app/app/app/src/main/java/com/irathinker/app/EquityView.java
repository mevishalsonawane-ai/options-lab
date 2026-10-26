package com.irathinker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/** Cumulative %-equity curve for the Strategy Lab, with a zero line
 *  and a faint drawdown shade under the running peak. */
public class EquityView extends View {

    private double[] eq = new double[0];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ddFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EquityView(Context c) { super(c); init(); }
    public EquityView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3f);
        zero.setColor(0x55FFFFFF);
        zero.setStrokeWidth(1.5f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x228BC7FF);
        ddFill.setStyle(Paint.Style.FILL);
        ddFill.setColor(0x33FF7A8A);
    }

    public void setEquity(double[] e) {
        eq = e == null ? new double[0] : e;
        invalidate();
    }

    private static float yOf(double v, double min, double max, int h) {
        return (float) (h - (v - min) / (max - min) * h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (eq.length < 2) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0xFF8EA6C8);
            t.setTextSize(30f);
            cv.drawText("Not enough trades to draw a curve", 8, h / 2f, t);
            return;
        }
        double min = 0, max = 0;
        for (double v : eq) { min = Math.min(min, v); max = Math.max(max, v); }
        if (max - min < 1e-9) max = min + 1;
        double pad = (max - min) * 0.08;
        max += pad; min -= pad;
        final double fMin = min, fMax = max;
        float zeroY = yOf(0, fMin, fMax, h);
        cv.drawLine(0, zeroY, w, zeroY, zero);

        // drawdown shade: area between curve and running peak
        Path dd = new Path();
        double peak = eq[0];
        boolean started = false;
        for (int i = 0; i < eq.length; i++) {
            peak = Math.max(peak, eq[i]);
            float x = (float) i / (eq.length - 1) * w;
            if (!started) { dd.moveTo(x, yOf(peak, fMin, fMax, h)); started = true; }
            else dd.lineTo(x, yOf(peak, fMin, fMax, h));
        }
        for (int i = eq.length - 1; i >= 0; i--) {
            float x = (float) i / (eq.length - 1) * w;
            dd.lineTo(x, yOf(eq[i], fMin, fMax, h));
        }
        dd.close();
        cv.drawPath(dd, ddFill);

        // area fill + line
        Path area = new Path();
        area.moveTo(0, zeroY);
        for (int i = 0; i < eq.length; i++) {
            float x = (float) i / (eq.length - 1) * w;
            area.lineTo(x, yOf(eq[i], fMin, fMax, h));
        }
        area.lineTo(w, zeroY);
        area.close();
        cv.drawPath(area, fill);
        line.setColor(eq[eq.length - 1] >= 0 ? 0xFF2BD98A : 0xFFFF5C5C);
        Path lp = new Path();
        for (int i = 0; i < eq.length; i++) {
            float x = (float) i / (eq.length - 1) * w;
            if (i == 0) lp.moveTo(x, yOf(eq[i], fMin, fMax, h)); else lp.lineTo(x, yOf(eq[i], fMin, fMax, h));
        }
        cv.drawPath(lp, line);
    }
}
