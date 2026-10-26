package com.irathinker.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Signal Test: every ORB signal the engine produced over the last 60
 *  days, on every stock, each one resolved - TARGET, STOP or NEITHER -
 *  with the same outcome function the live tracker uses. A filter shows
 *  only the signals that would have passed the notification gates
 *  (the live gate: IraScore, the 10-point floor, range width, VIX
 *  timing), so the alert rules themselves can be judged on evidence,
 *  not on hope. */
public class SignalTestActivity extends Activity {

    private boolean alertOnly = false;
    private int side = 0; // 0 both, +1 buy, -1 buy put

    private LinearLayout controlsBox, resultsBox;
    private TextView status;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile int runGen = 0;
    private final List<Analysis.TestedSignal> allSignals = new ArrayList<>();
    private boolean loaded = false;
    private volatile Ledger.Stats ledgerStats;
    private volatile Ledger.Grade ledgerGrade;
    private volatile java.util.List<Ledger.Rec> ledgerRecs = new ArrayList<>();
    private final List<Ledger.Diag> postMortems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.screenBg());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        Ui.applySystemBarPadding(sv);
        setContentView(sv);

        root.addView(Ui.backButton(this, this::finish));
        root.addView(Ui.tv(this, "Signal Test", 24, Ui.TXT, true));
        TextView sub = Ui.tv(this, "Every signal from the last 60 days, tested: did it hit the "
                + "target or the stop? Filter to ALERT-RULES to test only the signals your "
                + "notification settings would have sent.", 13, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(sub);

        controlsBox = new LinearLayout(this);
        controlsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(controlsBox);

        status = Ui.tv(this, "", 13, Ui.DIM, false);
        status.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        root.addView(status);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);

        rebuildControls();
        run();
    }

    private TextView pill(String label, boolean active, View.OnClickListener oc) {
        TextView t = Ui.pill(this, label,
                active ? Ui.BLUE : Ui.CARD_ALT, active ? 0xFFFFFFFF : Ui.DIM);
        t.setOnClickListener(oc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 8));
        t.setLayoutParams(lp);
        return t;
    }

    private void rebuildControls() {
        controlsBox.removeAllViews();
        LinearLayout mRow = new LinearLayout(this);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.addView(Ui.tv(this, "Signals  ", 13, Ui.DIM, true));
        mRow.addView(pill("ALL", !alertOnly, v -> { alertOnly = false; rebuildControls(); render(); }));
        mRow.addView(pill("ALERT-RULES ONLY", alertOnly, v -> { alertOnly = true; rebuildControls(); render(); }));
        controlsBox.addView(mRow);
        LinearLayout sRow = new LinearLayout(this);
        sRow.setOrientation(LinearLayout.HORIZONTAL);
        sRow.addView(Ui.tv(this, "Side  ", 13, Ui.DIM, true));
        sRow.addView(pill("BOTH", side == 0, v -> { side = 0; rebuildControls(); render(); }));
        sRow.addView(pill("BUY", side == 1, v -> { side = 1; rebuildControls(); render(); }));
        sRow.addView(pill("BUY PUT", side == -1, v -> { side = -1; rebuildControls(); render(); }));
        controlsBox.addView(sRow);
    }

    private void run() {
        final int gen = ++runGen;
        status.setText("Testing signals…");
        resultsBox.removeAllViews();
        new Thread(() -> {
            try {
                java.util.List<Ledger.Rec> recs = Ledger.load(this);
                ledgerStats = Ledger.summarize(recs);
                ledgerGrade = Ledger.gradeOf(recs);
                ledgerRecs = recs;
            } catch (Throwable ignored) { }
            synchronized (postMortems) { postMortems.clear(); }
            YahooClient.Chart vixChart = null;
            try { vixChart = YahooClient.fetch("^INDIAVIX", "60d", "5m"); } catch (Exception ignored) { }
            final YahooClient.Chart vixF = vixChart;
            YahooClient.Chart niftyChart = null;
            try { niftyChart = YahooClient.fetch("^NSEI", "60d", "5m"); } catch (Exception ignored) { }
            final YahooClient.Chart niftyF = niftyChart;
            List<Universe.Sym> stocks = new ArrayList<>();
            for (Universe.Sym s : Universe.all()) {
                if (Universe.G_NIFTY.equals(s.group) || Universe.G_BANK.equals(s.group)) stocks.add(s);
            }
            final List<Analysis.TestedSignal> found = new ArrayList<>();
            final Object lock = new Object();
            final int[] done = {0};
            CountDownLatch latch = new CountDownLatch(stocks.size());
            for (Universe.Sym s : stocks) {
                pool.execute(() -> {
                    try {
                        YahooClient.Chart intra = YahooClient.fetch(s.yahoo, "60d", "5m");
                        List<Analysis.TestedSignal> h = Analysis.signalHistory(intra, s, vixF);
                        synchronized (lock) { found.addAll(h); }
                        List<Ledger.Diag> ds = new ArrayList<>();
                        List<Ledger.Rec> lr = ledgerRecs;
                        if (lr != null) {
                            for (Ledger.Rec r : lr) {
                                if (r.yahoo.equals(s.yahoo) && r.outcome != 0) {
                                    ds.add(Ledger.diagnose(intra, r, niftyF));
                                }
                            }
                        }
                        if (!ds.isEmpty()) synchronized (postMortems) { postMortems.addAll(ds); }
                    } catch (Exception ignored) { }
                    synchronized (lock) {
                        done[0]++;
                        if (gen == runGen) {
                            int d = done[0], n = stocks.size();
                            runOnUiThread(() -> { if (!isFinishing()) status.setText("Testing signals… " + d + "/" + n + " stocks"); });
                        }
                    }
                    latch.countDown();
                });
            }
            try { latch.await(); } catch (InterruptedException ignored) { }
            if (gen != runGen) return;
            found.sort((a, b) -> Long.compare(b.scan.breakTs, a.scan.breakTs));
            synchronized (allSignals) {
                allSignals.clear();
                allSignals.addAll(found);
            }
            loaded = true;
            runOnUiThread(() -> { if (!isFinishing()) render(); });
        }).start();
    }

    /** The Live Record card (v45): the app's own forward record -
     *  every plan it computed live, judged against real bars, split
     *  by the situations and price action that surrounded it. This
     *  is the learning made visible; it starts empty and grows from
     *  the next session on. */
    private LinearLayout ledgerCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "Live record — the app learning from live data"));
        Ledger.Stats st = ledgerStats;
        if (st == null || st.total == 0) {
            card.addView(Ui.tv(this, "Nothing recorded yet. From the next market session, every plan the "
                    + "app computes — alert or not — is written down with its situation and its price "
                    + "action, then judged against the real bars that followed. Forward-only: nothing "
                    + "here is backfilled or backtested.", 12f, Ui.DIM, false));
            return card;
        }
        card.addView(Ui.tv(this, String.format("%,d plans recorded since %s · %,d judged · %,d still open.",
                st.total, st.since, st.total - st.open, st.open), 12.5f, Ui.DIM, false));
        int[] all = st.buckets.get("ALL");
        if (all != null && all[0] > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            int decided = all[1] + all[2];
            row.addView(Ui.stat(this, decided > 0 ? String.format("%.0f%%", all[1] * 100.0 / decided) : "-",
                    "WIN RATE (decided)", decided > 0 && all[1] * 2 >= decided ? Ui.GREEN : Ui.RED));
            row.addView(Ui.stat(this, String.valueOf(all[1]), "TARGETS", Ui.GREEN));
            row.addView(Ui.stat(this, String.valueOf(all[2]), "STOPS", Ui.RED));
            row.addView(Ui.stat(this, String.valueOf(all[3]), "FLAT", Ui.DIM));
            card.addView(row);
        }
        addBucketLine(card, st, "GRADE", "Alert-grade plans", "NOGRADE", "the rest");
        addBucketLine(card, st, "VIXFIRST", "VIX-first breakdowns", "NOVIX", "VIX not first");
        addBucketLine(card, st, "NARROW", "Narrow ranges (≤0.8%)", "WIDE", "wide ranges");
        addBucketLine(card, st, "PA_STRONG", "Strong break bars", "PA_WEAK", "weak pokes");
        addBucketLine(card, st, "PA_WITH", "Breaks with the day's structure", "PA_AGAINST", "against it");
        addBucketLine(card, st, "RUN_CALM", "Calm 1-minute run-ups", "RUN_WILD", "wild run-ups");
        Ledger.Grade grd = ledgerGrade;
        TextView gl;
        if (grd == null || grd.baseline < 0) {
            gl = Ui.tv(this, "Self-grading: starts after 20 judged plans. From then on, the score shown "
                    + "for each plan shifts (−12…+12) by what its situations have actually done live.", 12f, Ui.FAINT, false);
        } else {
            gl = Ui.tv(this, String.format("Self-grading is ON (baseline win %.0f%% over %d decided). "
                            + "Scores shown for plans shift by their live situations. Strongest live context so far: %s — winning %.0f%% (n=%d).",
                    grd.baseline * 100, grd.baselineN, Ledger.ctxLabel(grd.bestKey), grd.bestWin * 100, grd.bestN),
                    12f, Ui.TXT, false);
        }
        gl.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(gl);
        return card;
    }

    private static String kindLabel(String kind) {
        switch (kind) {
            case "ORB_DOWN": return "PUT breakdown";
            case "ORB_UP": return "CALL breakout";
            case "FADE_UP": return "gap fade up";
            case "FADE_DN": return "gap fade down";
            default: return kind;
        }
    }

    /** One hindsight fact from the record itself: for a loser, the
     *  most suspicious thing about its birth; for a winner, the most
     *  helpful. Empty when nothing stands out. */
    private String hindsight(Ledger.Rec r, boolean won) {
        if (!won) {
            if ("ORB_DOWN".equals(r.kind) && r.vixFirst == 0) return "VIX had not moved first.";
            if (!Double.isNaN(r.widthPct) && r.widthPct > 0.8) return String.format("Range was wide (%.1f%%).", r.widthPct);
            if (!Double.isNaN(r.paBody) && r.paBody < 0.35) return "Break bar was a weak poke.";
            if (r.paStruct != 0 && (r.paStruct > 0) != r.up()) return "The day's structure was against it.";
            if (!Double.isNaN(r.runVol1) && r.runVol1 >= 0.20) return "The 1-minute run-up into it was wild.";
            return "";
        }
        if (!Double.isNaN(r.paBody) && r.paBody >= 0.6) return "Strong break bar.";
        if ("ORB_DOWN".equals(r.kind) && r.vixFirst == 1) return "VIX had moved first.";
        if (!Double.isNaN(r.widthPct) && r.widthPct <= 0.8) return "Narrow range.";
        return "";
    }

    /** The Post-mortems card (v49): every judged trade opened up -
     *  how far it ran, how far it went wrong, how long it took,
     *  what NIFTY did meanwhile - plus the failure-pattern summary
     *  on top, so WHY trades fail is visible at a glance. Sent
     *  alerts are the default scope once there are a few of them. */
    private LinearLayout postMortemCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "Post-mortems - why trades won or lost"));
        List<Ledger.Diag> all;
        synchronized (postMortems) { all = new ArrayList<>(postMortems); }
        if (all.isEmpty()) {
            card.addView(Ui.tv(this, "No judged trades yet. As the live record's plans resolve, each one "
                    + "is opened up here: how far it ran, how far it went wrong, how long the outcome "
                    + "took, and what the market did meanwhile - so the failure pattern becomes visible, "
                    + "not just the score.", 12f, Ui.DIM, false));
            return card;
        }
        List<Ledger.Diag> sent = new ArrayList<>();
        for (Ledger.Diag d : all) if (d.rec.sent) sent.add(d);
        boolean sentOnly = sent.size() >= 3;
        List<Ledger.Diag> use = sentOnly ? sent : all;
        int tgt = 0, stp = 0, flt = 0;
        for (Ledger.Diag d : use) {
            if (d.rec.outcome > 0) tgt++; else if (d.rec.outcome < 0) stp++; else flt++;
        }
        card.addView(Ui.tv(this, String.format("%s: %d judged - %d target · %d stop · %d flat.",
                sentOnly ? "Alerts actually sent" : "All recorded plans (sent alerts take over this view once there are a few)",
                use.size(), tgt, stp, flt), 12.5f, Ui.DIM, false));
        java.util.Map<String, Integer> counts = Ledger.classCounts(use);
        List<java.util.Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        String worstKlass = "";
        int worstN = 0;
        for (java.util.Map.Entry<String, Integer> e : sorted) {
            if ((e.getKey().endsWith("_STOP") || e.getKey().startsWith("FLAT_")) && e.getValue() > worstN) {
                worstN = e.getValue();
                worstKlass = e.getKey();
            }
        }
        int lines = 0;
        for (java.util.Map.Entry<String, Integer> e : sorted) {
            if (lines++ >= 3) break;
            TextView t = Ui.tv(this, String.format("%s - %d of %d.", Ledger.classLine(e.getKey()), e.getValue(), use.size()),
                    12f, Ui.DIM, false);
            t.setPadding(0, Ui.dp(this, 3), 0, 0);
            card.addView(t);
        }
        String takeaway = "";
        if ("DEAD_STOP".equals(worstKlass)) takeaway = "The biggest leak: breaks that never move. Entry quality - not the exits - is what is failing.";
        else if ("TEASED_STOP".equals(worstKlass)) takeaway = "The biggest leak: trades that had profit, then gave it back. The entries work; the exits are what is failing.";
        else if ("FLAT_RAN".equals(worstKlass)) takeaway = "The biggest leak: trades run, but the target sits too far for the day they are in.";
        else if ("SOME_LIFE_STOP".equals(worstKlass)) takeaway = "The biggest leak: breaks with a little life that cannot follow through.";
        if (!takeaway.isEmpty()) {
            TextView t = Ui.tv(this, takeaway, 12.5f, Ui.ORANGE, true);
            t.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(t);
        }
        List<Ledger.Diag> rows = new ArrayList<>(use);
        rows.sort((a, b) -> Long.compare(b.rec.ts, a.rec.ts));
        int shown = 0;
        for (Ledger.Diag d : rows) {
            if (shown++ >= 25) break;
            Ledger.Rec r = d.rec;
            String oc = r.outcome > 0 ? "TARGET" : r.outcome < 0 ? "STOP" : "FLAT";
            TextView head = Ui.tv(this, String.format("%s · %s · %s%s%s", r.name, kindLabel(r.kind), oc,
                    r.sent ? " · SENT" : "", d.mins >= 0 ? " · " + d.mins + " min" : ""), 12.5f,
                    r.outcome > 0 ? Ui.GREEN : r.outcome < 0 ? Ui.RED : Ui.DIM, true);
            head.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(head);
            StringBuilder sb = new StringBuilder(Ledger.classLine(d.klass)).append(".");
            if (!Double.isNaN(d.mfeR)) {
                sb.append(String.format(" Best %+.1fR, worst %.1fR against.", d.mfeR, d.maeR));
            }
            if (!Double.isNaN(d.mkt)) {
                sb.append(d.mkt >= 0.3 ? String.format(" NIFTY kept moving its way (%+.1f%%).", d.mkt)
                        : d.mkt <= -0.3 ? String.format(" NIFTY moved against it (%+.1f%%).", d.mkt)
                        : " NIFTY was flat during it.");
            }
            String hs = hindsight(r, r.outcome > 0);
            if (!hs.isEmpty()) sb.append(" ").append(hs);
            if ("CHART_GONE".equals(d.klass) && !Double.isNaN(r.movePct)) {
                sb.append(String.format(" Day move %+.1f%% in its direction.", r.movePct));
            }
            card.addView(Ui.tv(this, r.date + " · " + sb, 12f, Ui.DIM, false));
        }
        return card;
    }

    /** The Live verdicts card (v59): the Live Ledger's judged
     *  record read as one verdict per alert family - the ORB
     *  gate's alert-grade plans under their plan-target exit and
     *  under the 3R runner (the same trades both ways), and the
     *  gap fade. Judged n, win rate and average R per family,
     *  with a plain verdict line once a family reaches the
     *  20-judged sample the app already uses before it lets live
     *  data speak. Display-only: nothing gates on this card. */
    private LinearLayout verdictsCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "Live verdicts — the ledger judging the alerts"));
        java.util.List<Ledger.Rec> recs = ledgerRecs;
        if (recs == null || recs.isEmpty()) {
            card.addView(Ui.tv(this, "No judged plans yet — fills from live sessions. From the next "
                    + "market session, every alert-grade plan the app computes is judged against the "
                    + "real bars that followed, and each family's verdict builds here.", 12f, Ui.DIM, false));
            return card;
        }
        for (Ledger.Verdict v : Ledger.verdicts(recs)) {
            TextView head = Ui.tv(this, v.label, 13f, Ui.TXT, true);
            head.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(head);
            if (v.n == 0) {
                card.addView(Ui.tv(this, Ledger.verdictLine(v), 12f, Ui.DIM, false));
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(this, 4), 0, 0);
            row.addView(Ui.stat(this, String.valueOf(v.n), "JUDGED", Ui.TXT));
            row.addView(Ui.stat(this, String.format("%.0f%%", v.wins * 100.0 / v.n), "WIN RATE",
                    v.wins * 2 >= v.n ? Ui.GREEN : Ui.RED));
            row.addView(Ui.stat(this, String.format("%+.2fR", v.avgR), "AVG R",
                    v.avgR >= 0 ? Ui.GREEN : Ui.RED));
            card.addView(row);
            TextView vl = Ui.tv(this, Ledger.verdictLine(v), 12f,
                    v.n >= 20 ? Ui.TXT : Ui.FAINT, false);
            vl.setPadding(0, Ui.dp(this, 3), 0, 0);
            card.addView(vl);
        }
        card.addView(Ui.tv(this, "R is measured on each record's own entry/stop geometry: a target "
                + "pays its exit's multiple, a stop costs 1R, a flat day contributes the move it "
                + "actually made. The two ORB rows judge the same alert-grade trades under both "
                + "live exits. Display only — the alert gate never reads this.", 11.5f, Ui.FAINT, false));
        return card;
    }

    private static String ocWord(int o) { return o > 0 ? "TARGET" : o < 0 ? "STOP" : "NEITHER"; }

    private void addBucketLine(LinearLayout card, Ledger.Stats st, String k1, String l1, String k2, String l2) {
        int[] a = st.buckets.get(k1), b = st.buckets.get(k2);
        if (a == null || b == null || a[0] < 3 || b[0] < 3) return;
        int da = a[1] + a[2], db = b[1] + b[2];
        if (da == 0 || db == 0) return;
        TextView t = Ui.tv(this, String.format("%s: won %.0f%% of decided (%d judged) · %s: %.0f%% (%d).",
                l1, a[1] * 100.0 / da, a[0], l2, b[1] * 100.0 / db, b[0]), 12f, Ui.DIM, false);
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(t);
    }

    private void render() {
        if (!loaded) return;
        resultsBox.removeAllViews();
        List<Analysis.TestedSignal> view = new ArrayList<>();
        int alertTotal = 0;
        synchronized (allSignals) {
            for (Analysis.TestedSignal t : allSignals) {
                if (t.alertGrade) alertTotal++;
                if (alertOnly && !t.alertGrade) continue;
                boolean rowUp = "FADE".equals(t.kind) ? t.scan.fadeUp
                        : t.scan.setup == Analysis.Setup.ORB_UP;
                boolean rowDown = "FADE".equals(t.kind) ? !t.scan.fadeUp
                        : t.scan.setup == Analysis.Setup.ORB_DOWN;
                if (side == 1 && !rowUp) continue;
                if (side == -1 && !rowDown) continue;
                view.add(t);
            }
        }
        int tgt = 0, stp = 0, nei = 0;
        for (Analysis.TestedSignal t : view) {
            if (t.outcome > 0) tgt++; else if (t.outcome < 0) stp++; else nei++;
        }
        status.setText((alertOnly ? "Alert-rules signals" : "All signals") + " · 60 days · "
                + (side == 0 ? "both sides" : side == 1 ? "buy only" : "buy put only"));
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "Test result"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.stat(this, String.valueOf(view.size()), "SIGNALS", Ui.TXT));
        row.addView(Ui.stat(this, String.valueOf(tgt), "TARGET HIT", Ui.GREEN));
        row.addView(Ui.stat(this, String.valueOf(stp), "STOP HIT", Ui.RED));
        row.addView(Ui.stat(this, String.valueOf(nei), "NEITHER", Ui.DIM));
        card.addView(row);
        int decided = tgt + stp;
        double winPct = decided > 0 ? tgt * 100.0 / decided : 0;
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, Ui.dp(this, 10), 0, 0);
        row2.addView(Ui.stat(this, decided > 0 ? String.format("%.0f%%", winPct) : "-",
                "WIN RATE (decided)", winPct >= 50 ? Ui.GREEN : Ui.RED));
        row2.addView(Ui.stat(this, String.valueOf(alertTotal), "ALERT-GRADE (of all)", Ui.ORANGE));
        card.addView(row2);
        int orbRows = 0, t2 = 0, t3 = 0;
        for (Analysis.TestedSignal t : view) {
            if ("FADE".equals(t.kind)) continue;
            orbRows++;
            if (t.outcome2 > 0) t2++;
            if (t.outcome3 > 0) t3++;
        }
        if (orbRows > 0) {
            TextView ex = Ui.tv(this, String.format("Alert exits on these plans — 2R target hit %d of %d · 3R runner hit %d of %d.",
                    t2, orbRows, t3, orbRows), 12f, Ui.DIM, false);
            ex.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(ex);
        }
        resultsBox.addView(ledgerCard());
        resultsBox.addView(postMortemCard());
        resultsBox.addView(verdictsCard());
        resultsBox.addView(card);

        LinearLayout tc = Ui.card(this);
        tc.addView(Ui.sectionTitle(this, "Signals, newest first"));
        int cap = Math.min(view.size(), 200);
        for (int i = 0; i < cap; i++) {
            Analysis.TestedSignal t = view.get(i);
            Analysis.Scan sc = t.scan;
            boolean fadeRow = "FADE".equals(t.kind);
            boolean up = fadeRow ? sc.fadeUp : sc.setup == Analysis.Setup.ORB_UP;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView d = Ui.mono(this, t.day.toString().substring(5), 12, Ui.DIM, false);
            d.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), -2));
            r.addView(d);
            TextView nm = Ui.tv(this, sc.sym.name, 13, Ui.TXT, true);
            nm.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(nm);
            r.addView(Ui.pill(this, up ? "BUY" : "BUY PUT",
                    up ? Color.rgb(16, 62, 44) : Color.rgb(66, 26, 30), up ? Ui.GREEN : Ui.RED));
            TextView oc = Ui.pill(this,
                    t.outcome > 0 ? "TARGET +1R" : t.outcome < 0 ? "STOP -1R" : "NEITHER",
                    t.outcome > 0 ? Color.rgb(16, 62, 44) : t.outcome < 0 ? Color.rgb(66, 26, 30) : Ui.CARD_ALT,
                    t.outcome > 0 ? Ui.GREEN : t.outcome < 0 ? Ui.RED : Ui.DIM);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
            olp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            oc.setLayoutParams(olp);
            r.addView(oc);
            r.setPadding(0, Ui.dp(this, 6), 0, 0);
            tc.addView(r);
            TextView det = Ui.mono(this, "     E " + MainActivity.fmt(fadeRow ? sc.fadeEntry : sc.entry)
                    + " · S " + MainActivity.fmt(fadeRow ? sc.fadeStop : sc.stop)
                    + " · T " + MainActivity.fmt(fadeRow ? sc.fadeTarget : sc.target)
                    + (fadeRow ? String.format(" · GAP FADE %+.1f%%", sc.fadeGap) : " · score " + t.score)
                    + (fadeRow ? "" : " · exits 2R " + ocWord(t.outcome2) + " · 3R " + ocWord(t.outcome3))
                    + (Double.isNaN(t.movePts) ? "" : String.format(" · move %.0f pts", t.movePts))
                    + (t.alertGrade ? " · ★ ALERT" : ""), 11.5f, Ui.DIM, false);
            det.setPadding(0, 0, 0, Ui.dp(this, 6));
            tc.addView(det);
        }
        if (view.size() > cap) {
            tc.addView(Ui.tv(this, "Showing the latest " + cap + " of " + view.size() + " signals.",
                    11.5f, Ui.FAINT, false));
        }
        if (view.isEmpty()) {
            tc.addView(Ui.tv(this, "No signals match this filter.", 13, Ui.DIM, false));
        }
        resultsBox.addView(tc);

        TextView note = Ui.tv(this, "How to read this: outcomes use the same walker as the live "
                + "tracker (inside a bar the stop is checked before the target - conservative), "
                + "fills at bar prints. ALERT-RULES here is the live gate judged at signal time: "
                + "breakdowns only, fast IraScore above 65, at least 10 points to the target, and "
                + "India VIX already broken up before the stock broke - before the top-3 daily cap. "
                + "The move column is the best move available after the entry, in points - "
                + "what the trade could actually capture. Each ORB row also judges the two "
                + "live alert exits on the same walk - the 2R book target and the 3R runner - "
                + "so the exits can be compared head-to-head over the window.", 11.5f, Ui.FAINT, false);
        note.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        resultsBox.addView(note);
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
