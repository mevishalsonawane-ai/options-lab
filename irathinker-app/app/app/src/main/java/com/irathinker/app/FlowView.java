package com.irathinker.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** The living data-flow diagram, built to the approved mockup:
 *  glass source tiles with glyphs at the top, glowing ribbon streams
 *  converging into the tick-ringed IraThinker Engine core, and one
 *  glass Results Hub panel at the bottom - all carrying the app's
 *  real live counts, animated while visible. */
public class FlowView extends View {

    private static final int CYAN = 0xFF35C4FF;
    private static final int BLUE = 0xFF2E7CF6;
    private static final int ORANGE = 0xFFFF9F2E;
    private static final int NODE_FILL = 0xD90D1A30;
    private static final int NODE_STROKE = 0xFF2A4A7F;
    private static final int TXT = 0xFFEBF1FA;
    private static final int DIM = 0xFF8EA6C8;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean live = false, loopRunning = false;

    private String symbolsTxt = "— symbols";
    private String setupsTxt = "— setups";
    private String firedTxt = "— fired";
    private String bestTxt = "Score —";
    private String regimeTxt = "";

    private final String[] sources = {"Prices", "News", "India VIX", "Global"};
    private final String[] hubs = {"Time Machine", "Alerts", "Strategy Lab"};

    private float W, H;
    private float coreX, coreY, coreR;
    private final float[] srcX = new float[4];
    private float srcTop, srcW, srcH;
    private float hubTop, hubBot;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // deterministic starfield mesh
    private final float[][] stars = new float[42][2];
    {
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < stars.length; i++) {
            stars[i][0] = r.nextFloat();
            stars[i][1] = r.nextFloat();
        }
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!live) { loopRunning = false; return; }
            invalidate();
            handler.postDelayed(this, 40);
        }
    };

    public FlowView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private float dp(float v) { return v * density; }

    public void setStats(int loaded, int total, int setups, int fired, Integer bestScore, String regime) {
        symbolsTxt = loaded + "/" + total;
        setupsTxt = setups + " setups";
        firedTxt = fired + " fired";
        bestTxt = bestScore == null ? "Score —" : "Score " + bestScore;
        regimeTxt = regime == null ? "" : regime;
        invalidate();
    }

    public void setLive(boolean on) {
        live = on;
        if (on && !loopRunning) {
            loopRunning = true;
            handler.post(loop);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        live = false;
        handler.removeCallbacks(loop);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        coreX = W / 2f;
        coreY = H * 0.455f;
        coreR = Math.min(W * 0.175f, H * 0.185f);
        srcH = dp(54); srcTop = dp(24);
        srcW = (W - dp(16) * 2 - dp(7) * 3) / 4f;
        for (int i = 0; i < 4; i++) srcX[i] = dp(16) + i * (srcW + dp(7)) + srcW / 2f;
        hubBot = H - dp(10);
        hubTop = H * 0.755f;
    }

    // ---------------- drawing helpers ----------------

    private float[] cubic(float x0, float y0, float x1, float y1,
                          float x2, float y2, float x3, float y3, double t) {
        double u = 1 - t;
        double a = u * u * u, b = 3 * u * u * t, c = 3 * u * t * t, d = t * t * t;
        return new float[]{(float) (a * x0 + b * x1 + c * x2 + d * x3),
                (float) (a * y0 + b * y1 + c * y2 + d * y3)};
    }

    private void ribbon(Canvas cv, float sx, float sy, float ex, float ey, int color, double phase) {
        float c1x = sx, c1y = sy + (ey - sy) * 0.42f;
        float c2x = ex, c2y = ey - (ey - sy) * 0.42f;
        // perpendicular offset for the side strands
        float dx = ex - sx, dy = ey - sy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = len == 0 ? 0 : -dy / len, ny = len == 0 ? 0 : dx / len;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int s = -1; s <= 1; s++) {
            float off = s * dp(4.5f);
            path.reset();
            path.moveTo(sx + nx * off, sy + ny * off);
            path.cubicTo(c1x + nx * off, c1y + ny * off, c2x + nx * off, c2y + ny * off,
                    ex + nx * off, ey + ny * off);
            p.setColor(color);
            if (s == 0) {
                p.setAlpha(52); p.setStrokeWidth(dp(6)); cv.drawPath(path, p);
                p.setAlpha(165); p.setStrokeWidth(dp(1.8f)); cv.drawPath(path, p);
            } else {
                p.setAlpha(64); p.setStrokeWidth(dp(1.1f)); cv.drawPath(path, p);
            }
        }
        // data packets: 4 per stream, each with a fading 2-ghost trail,
        // plus an arrival flash where the stream meets its node
        for (int k = 0; k < 4; k++) {
            double t = (phase + k * 0.25) % 1.0;
            for (int g = 2; g >= 0; g--) {
                double tg = t - g * 0.028;
                if (tg < 0) continue;
                float[] pt = cubic(sx, sy, c1x, c1y, c2x, c2y, ex, ey, tg);
                p.setStyle(Paint.Style.FILL);
                if (g == 0) {
                    p.setColor(color);
                    p.setAlpha(56);
                    cv.drawCircle(pt[0], pt[1], dp(7f), p);
                    p.setAlpha(255);
                    p.setColor(0xFFEAF7FF);
                    cv.drawCircle(pt[0], pt[1], dp(2.6f), p);
                } else {
                    p.setColor(color);
                    p.setAlpha(g == 1 ? 130 : 60);
                    cv.drawCircle(pt[0], pt[1], dp(1.7f), p);
                }
            }
            if (t > 0.9) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                p.setAlpha((int) ((t - 0.9) / 0.1 * 130));
                cv.drawCircle(ex, ey, dp(5.5f), p);
            }
        }
        p.setAlpha(255);
    }

    private void glassRect(Canvas cv, RectF r, float rad, int stroke) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(NODE_FILL);
        cv.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(stroke);
        p.setAlpha(200);
        cv.drawRoundRect(r, rad, rad, p);
        p.setAlpha(255);
    }

    private void glyph(Canvas cv, int kind, float cx, float cy, float s, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(dp(1.6f));
        p.setStrokeCap(Paint.Cap.ROUND);
        float h = s / 2;
        switch (kind) {
            case 0: // prices: axes + rising line + arrowhead
                cv.drawLine(cx - h, cy - h, cx - h, cy + h, p);
                cv.drawLine(cx - h, cy + h, cx + h, cy + h, p);
                path.reset();
                path.moveTo(cx - h * 0.7f, cy + h * 0.35f);
                path.lineTo(cx - h * 0.15f, cy - h * 0.1f);
                path.lineTo(cx + h * 0.25f, cy + h * 0.15f);
                path.lineTo(cx + h * 0.75f, cy - h * 0.6f);
                cv.drawPath(path, p);
                cv.drawLine(cx + h * 0.35f, cy - h * 0.62f, cx + h * 0.75f, cy - h * 0.6f, p);
                cv.drawLine(cx + h * 0.73f, cy - h * 0.2f, cx + h * 0.75f, cy - h * 0.6f, p);
                break;
            case 1: // news: sheet + lines
                RectF sheet = new RectF(cx - h * 0.8f, cy - h * 0.85f, cx + h * 0.8f, cy + h * 0.85f);
                cv.drawRoundRect(sheet, dp(2), dp(2), p);
                cv.drawRect(new RectF(cx - h * 0.55f, cy - h * 0.55f, cx - h * 0.05f, cy - h * 0.05f), p);
                for (int i = 0; i < 3; i++) {
                    float yy = cy + h * (0.05f + i * 0.28f);
                    cv.drawLine(cx + h * 0.12f, yy, cx + h * 0.55f, yy, p);
                }
                cv.drawLine(cx - h * 0.55f, cy + h * 0.62f, cx + h * 0.55f, cy + h * 0.62f, p);
                break;
            case 2: // vix: waveform
                path.reset();
                for (int i = 0; i <= 24; i++) {
                    float xx = cx - h + (2 * h) * i / 24f;
                    float yy = cy + (float) Math.sin(i / 24f * Math.PI * 3) * h * 0.55f
                            * (1 - Math.abs(i - 12) / 26f);
                    if (i == 0) path.moveTo(xx, yy); else path.lineTo(xx, yy);
                }
                cv.drawPath(path, p);
                break;
            default: // global: globe
                cv.drawCircle(cx, cy, h * 0.85f, p);
                cv.drawLine(cx - h * 0.85f, cy, cx + h * 0.85f, cy, p);
                RectF oval = new RectF(cx - h * 0.4f, cy - h * 0.85f, cx + h * 0.4f, cy + h * 0.85f);
                cv.drawOval(oval, p);
                break;
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void caption(Canvas cv, String text, float x, float y, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(10.5f));
        p.setFakeBoldText(true);
        cv.drawText(text, x, y, p);
        p.setFakeBoldText(false);
    }

    // ---------------- main draw ----------------

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (W == 0) return;
        long nowMs = System.currentTimeMillis();
        double phase = (nowMs % 2100) / 2100.0;
        int[] srcColors = {CYAN, ORANGE, BLUE, ORANGE};

        // starfield mesh backdrop (twinkling)
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF9FD8FF);
        for (int si = 0; si < stars.length; si++) {
            float[] st = stars[si];
            p.setAlpha(16 + (int) (22 * (0.5 + 0.5 * Math.sin(nowMs / 640.0 + si * 1.7))));
            cv.drawCircle(st[0] * W, st[1] * H, dp(1.1f), p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(0.6f));
        p.setColor(0xFF4D9FFF);
        for (int i = 0; i + 3 < stars.length; i += 3) {
            p.setAlpha(12);
            cv.drawLine(stars[i][0] * W, stars[i][1] * H, stars[i + 3][0] * W, stars[i + 3][1] * H, p);
        }
        p.setAlpha(255);

        caption(cv, "D A T A   S O U R C E S", dp(16), dp(15), BLUE);

        // streams: sources -> core (behind tiles)
        for (int i = 0; i < 4; i++) {
            float ex = coreX + (i - 1.5f) * coreR * 0.42f;
            float ey = coreY - coreR * 0.92f;
            ribbon(cv, srcX[i], srcTop + srcH, ex, ey, srcColors[i], phase + i * 0.23);
        }
        // streams: core -> hub panel
        for (int i = 0; i < 3; i++) {
            float sx = coreX + (i - 1) * coreR * 0.55f;
            float sy = coreY + coreR * 0.95f;
            float ex = W * (0.22f + i * 0.28f);
            ribbon(cv, sx, sy, ex, hubTop, i == 1 ? ORANGE : CYAN, phase + 0.4 + i * 0.31);
        }

        // ---- engine core ----
        RadialGradient halo = new RadialGradient(coreX, coreY, coreR * 2.2f,
                new int[]{0x66358FFF, 0x1E358FFF, 0x00358FFF}, null, Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.FILL);
        p.setShader(halo);
        cv.drawCircle(coreX, coreY, coreR * 2.2f, p);
        p.setShader(null);

        // tick ring (Jarvis orb: layered rings at clearly visible,
        // different speeds - slow motion is what read as "static")
        p.setStyle(Paint.Style.STROKE);
        p.setColor(CYAN);
        double tickRot = (nowMs % 24000) / 24000.0 * 360;
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6 + tickRot);
            float r1 = coreR * 1.06f, r2 = coreR * (i % 5 == 0 ? 1.2f : 1.13f);
            p.setAlpha(i % 5 == 0 ? 200 : 90);
            p.setStrokeWidth(dp(i % 5 == 0 ? 1.6f : 1f));
            cv.drawLine(coreX + (float) Math.cos(a) * r1, coreY + (float) Math.sin(a) * r1,
                    coreX + (float) Math.cos(a) * r2, coreY + (float) Math.sin(a) * r2, p);
        }
        double rot = (nowMs % 14000) / 14000.0 * 360;
        double rotFast = (nowMs % 9000) / 9000.0 * 360;
        // outer dashed orbit ring, counter-rotating
        RectF ringOut = new RectF(coreX - coreR * 1.3f, coreY - coreR * 1.3f,
                coreX + coreR * 1.3f, coreY + coreR * 1.3f);
        p.setColor(BLUE);
        p.setStrokeWidth(dp(1.4f));
        for (int k = 0; k < 24; k++) {
            p.setAlpha(k % 6 == 0 ? 190 : 80);
            cv.drawArc(ringOut, (float) (-rotFast * 0.7 + k * 15), 8, false, p);
        }
        p.setStrokeWidth(dp(2.2f));
        RectF ring = new RectF(coreX - coreR, coreY - coreR, coreX + coreR, coreY + coreR);
        for (int k = 0; k < 3; k++) {
            p.setColor(CYAN);
            p.setAlpha(k == 0 ? 235 : 140);
            cv.drawArc(ring, (float) (rot + k * 120), 52, false, p);
        }
        // inner dashed ring, fast
        RectF ringDash = new RectF(coreX - coreR * 0.9f, coreY - coreR * 0.9f,
                coreX + coreR * 0.9f, coreY + coreR * 0.9f);
        p.setColor(CYAN);
        p.setStrokeWidth(dp(1.3f));
        for (int k = 0; k < 18; k++) {
            p.setAlpha(120);
            cv.drawArc(ringDash, (float) (rotFast + k * 20), 11, false, p);
        }
        RectF ring2 = new RectF(coreX - coreR * 0.78f, coreY - coreR * 0.78f,
                coreX + coreR * 0.78f, coreY + coreR * 0.78f);
        p.setStrokeWidth(dp(2.2f));
        for (int k = 0; k < 3; k++) {
            p.setColor(ORANGE);
            p.setAlpha(210);
            cv.drawArc(ring2, (float) (-rot * 1.4 + k * 120 + 40), 64, false, p);
        }
        // radar sweep: a soft wedge + bright leading edge, ~4s per turn
        double sweep = (nowMs % 4200) / 4200.0 * 360;
        RectF sweepBox = new RectF(coreX - coreR * 1.02f, coreY - coreR * 1.02f,
                coreX + coreR * 1.02f, coreY + coreR * 1.02f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CYAN);
        p.setAlpha(24);
        cv.drawArc(sweepBox, (float) (sweep - 32), 32, true, p);
        p.setStyle(Paint.Style.STROKE);
        p.setAlpha(170);
        p.setStrokeWidth(dp(1.6f));
        double sa = Math.toRadians(sweep);
        cv.drawLine(coreX + (float) Math.cos(sa) * coreR * 0.5f, coreY + (float) Math.sin(sa) * coreR * 0.5f,
                coreX + (float) Math.cos(sa) * coreR * 1.04f, coreY + (float) Math.sin(sa) * coreR * 1.04f, p);
        // orbiting particles on two radii, pulsing
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 9; i++) {
            boolean outer = i % 2 == 0;
            double spd = outer ? 11000 : -7500;
            double a = Math.toRadians((nowMs % (long) Math.abs(spd)) / Math.abs(spd) * 360 * Math.signum(spd) + i * 67);
            float rr = coreR * (outer ? 1.16f : 0.66f);
            float px = coreX + (float) Math.cos(a) * rr;
            float py = coreY + (float) Math.sin(a) * rr;
            double pulse = 0.5 + 0.5 * Math.sin(nowMs / 270.0 + i * 1.7);
            p.setColor(i % 3 == 0 ? ORANGE : CYAN);
            p.setAlpha(90 + (int) (150 * pulse));
            cv.drawCircle(px, py, dp(1.5f + 1.2f * (float) pulse), p);
        }
        // gear teeth ring
        p.setColor(BLUE);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(rot * 1.2 + i * 30);
            float gx = coreX + (float) Math.cos(a) * coreR * 0.6f;
            float gy = coreY + (float) Math.sin(a) * coreR * 0.6f;
            p.setAlpha(150);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(gx, gy, dp(2.2f), p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255); // the gear dots leave alpha at 150 - the disc and "AI" must be opaque
        // breathing core disc
        float breathe = 1f + 0.035f * (float) Math.sin(nowMs / 380.0);
        RadialGradient disc = new RadialGradient(coreX, coreY, coreR * 0.5f * breathe,
                new int[]{0xFF16406F, 0xFF081426}, null, Shader.TileMode.CLAMP);
        p.setShader(disc);
        cv.drawCircle(coreX, coreY, coreR * 0.48f * breathe, p);
        p.setShader(null);
        p.setColor(CYAN);
        p.setAlpha(200 + (int) (55 * (0.5 + 0.5 * Math.sin(nowMs / 380.0))));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(coreR * 0.36f * breathe);
        p.setFakeBoldText(true);
        cv.drawText("AI", coreX, coreY + coreR * 0.13f * breathe, p);
        p.setFakeBoldText(false);
        p.setAlpha(255);

        // stage labels around the core - they light up in pipeline
        // sequence (Scanner -> IraScore -> Time Machine -> Risk Gate)
        // so the eye sees data moving through the stages
        int stage = (int) ((nowMs / 900) % 4);
        p.setTextSize(dp(10f));
        p.setTextAlign(Paint.Align.RIGHT);
        p.setColor(stage == 0 ? TXT : DIM);
        cv.drawText("Scanner", coreX - coreR * 1.42f, coreY - coreR * 0.4f, p);
        p.setColor(stage == 1 ? TXT : DIM);
        cv.drawText("IraScore", coreX - coreR * 1.42f, coreY + coreR * 0.72f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(stage == 2 ? 0xFFFFB35C : ORANGE);
        cv.drawText("Time Machine", coreX + coreR * 1.42f, coreY - coreR * 0.4f, p);
        p.setColor(stage == 3 ? 0xFFFFB35C : ORANGE);
        cv.drawText("Risk Gate", coreX + coreR * 1.42f, coreY + coreR * 0.72f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CYAN);
        p.setAlpha(230);
        float dotY = (stage == 0 || stage == 2) ? coreY - coreR * 0.4f : coreY + coreR * 0.72f;
        float dotX = stage <= 1 ? coreX - coreR * 1.42f - dp(9) : coreX + coreR * 1.42f - dp(11);
        cv.drawCircle(dotX, dotY - dp(3.5f), dp(2.6f), p);
        p.setAlpha(255);
        p.setColor(TXT);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(11.5f));
        p.setFakeBoldText(true);
        cv.drawText("I R A T H I N K E R   E N G I N E", coreX, coreY + coreR * 1.38f + dp(6), p);
        p.setFakeBoldText(false);
        if (!regimeTxt.isEmpty()) {
            p.setColor(ORANGE);
            p.setTextSize(dp(10f));
            cv.drawText(regimeTxt, coreX, coreY + coreR * 1.38f + dp(20), p);
        }

        // ---- source tiles ----
        String[] srcSubs = {symbolsTxt, "headlines", "fear gauge", "overnight"};
        for (int i = 0; i < 4; i++) {
            RectF r = new RectF(srcX[i] - srcW / 2, srcTop, srcX[i] + srcW / 2, srcTop + srcH);
            glassRect(cv, r, dp(12), srcColors[i]);
            glyph(cv, i, srcX[i], srcTop + dp(17), dp(17), srcColors[i]);
            p.setStyle(Paint.Style.FILL);
            p.setColor(TXT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(10.5f));
            p.setFakeBoldText(true);
            cv.drawText(sources[i], srcX[i], srcTop + dp(40), p);
            p.setFakeBoldText(false);
            p.setColor(DIM);
            p.setTextSize(dp(8.5f));
            cv.drawText(srcSubs[i], srcX[i], srcTop + dp(50), p);
        }

        // ---- results hub panel ----
        RectF panel = new RectF(dp(12), hubTop, W - dp(12), hubBot);
        glassRect(cv, panel, dp(16), BLUE);
        caption(cv, "R E S U L T S   H U B", dp(26), hubTop + dp(20), CYAN);
        float cardW = (W - dp(24) - dp(28) - dp(8) * 2) / 3f;
        float cardTop = hubTop + dp(30);
        float cardBot = hubBot - dp(34);
        String[] hubSubs = {setupsTxt, firedTxt, bestTxt};
        for (int i = 0; i < 3; i++) {
            float x0 = dp(26) + i * (cardW + dp(8));
            RectF card = new RectF(x0, cardTop, x0 + cardW, cardBot);
            glassRect(cv, card, dp(10), i == 1 ? ORANGE : NODE_STROKE);
            float ccx = x0 + cardW / 2;
            // mini glyph: clock / bell / bars
            p.setStyle(Paint.Style.STROKE);
            p.setColor(i == 1 ? ORANGE : CYAN);
            p.setStrokeWidth(dp(1.5f));
            float gy = cardTop + dp(14);
            if (i == 0) {
                cv.drawCircle(ccx, gy, dp(6), p);
                cv.drawLine(ccx, gy, ccx, gy - dp(3.6f), p);
                cv.drawLine(ccx, gy, ccx + dp(2.8f), gy + dp(1.6f), p);
            } else if (i == 1) {
                path.reset();
                path.moveTo(ccx - dp(5), gy + dp(4));
                path.lineTo(ccx - dp(5), gy - dp(1));
                path.arcTo(new RectF(ccx - dp(5), gy - dp(7), ccx + dp(5), gy + dp(3)), 180, 180);
                path.lineTo(ccx + dp(5), gy + dp(4));
                path.close();
                cv.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(ccx, gy + dp(6), dp(1.6f), p);
            } else {
                float bw = dp(4.5f);
                for (int b = 0; b < 3; b++) {
                    float bx = ccx - bw * 1.6f + b * (bw + dp(2));
                    cv.drawRect(new RectF(bx, gy + dp(5) - dp(4 + b * 3.5f), bx + bw, gy + dp(5)), p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(TXT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(9.5f));
            p.setFakeBoldText(true);
            cv.drawText(hubs[i], ccx, cardTop + dp(34), p);
            p.setFakeBoldText(false);
            p.setColor(DIM);
            p.setTextSize(dp(8.5f));
            cv.drawText(hubSubs[i], ccx, cardTop + dp(46), p);
        }
        // stat pills row
        String[] pills = {symbolsTxt + " symbols", setupsTxt, bestTxt};
        float pw = (W - dp(24) - dp(28) - dp(8) * 2) / 3f;
        for (int i = 0; i < 3; i++) {
            float x0 = dp(26) + i * (pw + dp(8));
            RectF pr = new RectF(x0, hubBot - dp(28), x0 + pw, hubBot - dp(8));
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF14263F);
            cv.drawRoundRect(pr, dp(10), dp(10), p);
            p.setColor(i == 1 ? ORANGE : CYAN);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(9.5f));
            p.setFakeBoldText(true);
            cv.drawText(pills[i], x0 + pw / 2, hubBot - dp(14.5f), p);
            p.setFakeBoldText(false);
        }
    }
}
