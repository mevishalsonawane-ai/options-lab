package com.irathinker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Intraday sparkline with the opening-range band, entry/stop/target
 *  levels and a soft area fill. Drawn on Canvas - no chart library. */
public class SparkView extends View {

    private double[] closes = new double[0];
    private double orHigh = Double.NaN, orLow = Double.NaN;
    private double entry = Double.NaN, stop = Double.NaN, target = Double.NaN;
    private boolean up = true;

    public SparkView(Context c) { super(c); }

    public void setData(double[] closes, double orHigh, double orLow,
                        double entry, double stop, double target, boolean up) {
        this.closes = closes == null ? new double[0] : closes;
        this.orHigh = orHigh; this.orLow = orLow;
        this.entry = entry; this.stop = stop; this.target = target;
        this.up = up;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        int w = getWidth(), h = getHeight();
        if (closes.length < 2 || w == 0 || h == 0) return;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : closes) { min = Math.min(min, v); max = Math.max(max, v); }
        for (double v : new double[]{orHigh, orLow, entry, stop, target}) {
            if (!Double.isNaN(v)) { min = Math.min(min, v); max = Math.max(max, v); }
        }
        if (max <= min) return;
        double pad = (max - min) * 0.08;
        min -= pad; max += pad;
        final double fmin = min, fmax = max;
        java.util.function.DoubleUnaryOperator y = v -> h - (v - fmin) / (fmax - fmin) * h;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // opening range band
        if (!Double.isNaN(orHigh) && !Double.isNaN(orLow)) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(28, 64, 156, 255));
            cv.drawRect(0, (float) y.applyAsDouble(orHigh), w, (float) y.applyAsDouble(orLow), p);
            dashed(cv, y.applyAsDouble(orHigh), w, Color.argb(140, 64, 156, 255), p);
            dashed(cv, y.applyAsDouble(orLow), w, Color.argb(140, 64, 156, 255), p);
        }
        // stop / target
        if (!Double.isNaN(target)) dashed(cv, y.applyAsDouble(target), w, Ui.GREEN, p);
        if (!Double.isNaN(stop)) dashed(cv, y.applyAsDouble(stop), w, Ui.RED, p);

        // area + line
        Path line = new Path();
        float xstep = (float) w / (closes.length - 1);
        for (int i = 0; i < closes.length; i++) {
            float x = i * xstep, yy = (float) y.applyAsDouble(closes[i]);
            if (i == 0) line.moveTo(x, yy); else line.lineTo(x, yy);
        }
        Path area = new Path(line);
        area.lineTo(w, h); area.lineTo(0, h); area.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(30, 255, 159, 46));
        cv.drawPath(area, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 2));
        p.setColor(Ui.ORANGE);
        cv.drawPath(line, p);

        // last point
        float lx = w, ly = (float) y.applyAsDouble(closes[closes.length - 1]);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.ORANGE);
        cv.drawCircle(lx - Ui.dp(getContext(), 3), ly, Ui.dp(getContext(), 4), p);
    }

    private void dashed(Canvas cv, double y, int w, int color, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1));
        p.setColor(color);
        p.setPathEffect(new DashPathEffect(new float[]{8, 8}, 0));
        cv.drawLine(0, (float) y, w, (float) y, p);
        p.setPathEffect(null);
    }
}
