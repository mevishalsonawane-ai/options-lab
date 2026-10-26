package com.irathinker.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetailActivity extends Activity {

    private String name, yahoo;
    private LinearLayout body;
    private YahooClient.Chart daily1y;
    private double vixLtp = Double.NaN;
    private Smc.Report smcReport;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        name = i.getStringExtra("name");
        yahoo = i.getStringExtra("yahoo");

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.screenBg());
        Ui.applySystemBarPadding(sv);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        body.setPadding(pad, pad, pad, pad);
        sv.addView(body);
        setContentView(sv);

        body.addView(Ui.backButton(this, this::finish));
        LinearLayout loading = Ui.card(this);
        loading.addView(Ui.tv(this, name, 20, Ui.TXT, true));
        loading.addView(Ui.tv(this, "Loading full analysis (60 days of 5-min bars)...", 13, Ui.DIM, false));
        body.addView(loading);
        load();
    }

    private void load() {
        pool.execute(() -> {
            try {
                YahooClient.Chart intra = YahooClient.intraday(yahoo);
                YahooClient.Chart daily = null;
                try { daily = YahooClient.daily(yahoo); } catch (Exception ignored) { }
                try { daily1y = YahooClient.fetch(yahoo, "1y", "1d"); } catch (Exception ignored) { }
                if (OptionsMath.strikeStep(yahoo) > 0) {
                    try {
                        YahooClient.Chart vx = YahooClient.intradayShort("^INDIAVIX");
                        for (int k = vx.close.length - 1; k >= 0; k--) {
                            if (!Double.isNaN(vx.close[k])) { vixLtp = vx.close[k]; break; }
                        }
                    } catch (Exception ignored) { }
                }
                Universe.Sym sym = new Universe.Sym(name, yahoo, "");
                final Analysis.Scan s = Analysis.scan(sym, intra, daily);
                try { smcReport = Smc.analyze(intra); } catch (Exception ignored) { smcReport = null; }
                final double[] spark = todayCloses(intra);
                final Prefs prefs = new Prefs(this);
                final Analysis.Plan plan = Analysis.budget(s, prefs.capital(), prefs.maxLossPct());
                final Analysis.Odds odds = s.hasPlan() ? Analysis.timeMachine(intra, s, 5000, Ledger.load(this)) : null;
                Predict.Forecast fcTmp = null;
                try {
                    fcTmp = Predict.forecast(intra, PredictLive.weights(this));
                    // Live track (v47): judge older recorded calls
                    // for this symbol, then write down today's.
                    PredictLive.resolve(this, yahoo, intra);
                    if (fcTmp.combosLive > 0 && intra.ts.length > 0) {
                        int last = intra.ts.length - 1;
                        PredictLive.record(this, yahoo, intra.ts[last], intra.close[last],
                                fcTmp.dir[2], fcTmp.famDir60);
                    }
                } catch (Exception ignored) { }
                final Predict.Forecast fc = fcTmp;
                Ledger.Adj adjTmp = null;
                try {
                    java.util.List<Ledger.Rec> recs = Ledger.load(this);
                    Ledger.Grade grd = Ledger.gradeOf(recs);
                    int base = Analysis.liteScore(s);
                    adjTmp = Ledger.adjustFor(grd, s, base);
                } catch (Exception ignored) { }
                final Ledger.Adj liveAdj = adjTmp;
                final double stockIv = OptionsMath.realizedVol(daily);
                final java.util.List<NewsClient.Item> news = NewsClient.fetch(name + " stock", 3);
                runOnUiThread(() -> render(s, plan, odds, prefs, spark, news, fc, stockIv, liveAdj));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    body.removeAllViews();
                    LinearLayout c = Ui.card(this);
                    c.addView(Ui.tv(this, name, 20, Ui.TXT, true));
                    c.addView(Ui.tv(this, "Fetch failed: " + e.getMessage(), 13, Ui.RED, false));
                    body.addView(c);
                });
            }
        });
    }

    private double[] todayCloses(YahooClient.Chart intra) {
        if (intra.ts.length == 0) return new double[0];
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate last = Instant.ofEpochSecond(intra.ts[intra.ts.length - 1]).atZone(ist).toLocalDate();
        List<Integer> idx = YahooClient.indicesOfDay(intra, last);
        List<Double> v = new ArrayList<>();
        for (int i : idx) if (!Double.isNaN(intra.close[i])) v.add(intra.close[i]);
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    private void render(Analysis.Scan s, Analysis.Plan plan, Analysis.Odds odds, Prefs prefs,
                        double[] spark, java.util.List<NewsClient.Item> news, Predict.Forecast fc,
                        double stockIv, Ledger.Adj liveAdj) {
        body.removeAllViews();

        // ---- header card ----
        LinearLayout head = Ui.card(this);
        head.addView(Ui.tv(this, name, 21, Ui.TXT, true));
        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.addView(Ui.mono(this, MainActivity.fmt(s.ltp), 26, Ui.TXT, true));
        if (!Double.isNaN(s.dayPct)) {
            TextView pct = Ui.pill(this, String.format("%s%.2f%%", s.dayPct >= 0 ? "▲ +" : "▼ ", s.dayPct),
                    Ui.CARD_ALT, s.dayPct >= 0 ? Ui.GREEN : Ui.RED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 10), 0, 0, 0);
            prow.addView(pct, lp);
        }
        head.addView(prow);
        String meta = "";
        String secName = SectorMap.sectorOf(yahoo);
        if (secName != null) meta += secName + "    ";
        if (!Double.isNaN(s.dma20)) meta += "20DMA " + MainActivity.fmt(s.dma20) + "    ";
        if (!Double.isNaN(s.rsi)) meta += "RSI(14) " + MainActivity.fmt(s.rsi) + "    ";
        if (s.orReady) meta += "OR " + MainActivity.fmt(s.orLow) + " – " + MainActivity.fmt(s.orHigh);
        if (!meta.isEmpty()) head.addView(Ui.tv(this, meta, 12.5f, Ui.DIM, false));
        // v52: gold must say which Binance feed priced this screen -
        // the XAUUSDT perpetual, or the PAXG reference when fapi did
        // not answer. Never silent: a token price passed off as the
        // perp would be a false label.
        if (Universe.isGoldYahoo(yahoo)) {
            TextView fl = Ui.tv(this, BinanceClient.feedLine(), 12f, Ui.BLUE, false);
            fl.setPadding(0, Ui.dp(this, 4), 0, 0);
            head.addView(fl);
        }
        body.addView(head);

        // ---- IraScore card ----
        int score = odds != null ? Analysis.fullScore(s, odds) : Analysis.liteScore(s);
        if (odds != null) BuyAlerts.noteScore(yahoo, score);
        LinearLayout scoreCard = Ui.card(this);
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        TextView st = Ui.tv(this, "IRASCORE", 12, Ui.BLUE, true);
        srow.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
        srow.addView(Ui.mono(this, score + " / 100", 20,
                score >= 70 ? Ui.GREEN : score >= 50 ? Ui.ORANGE : Ui.RED, true));
        scoreCard.addView(srow);
        for (String w : s.why) {
            TextView wl = Ui.tv(this, "•  " + w, 12.5f, Ui.DIM, false);
            wl.setPadding(0, Ui.dp(this, 3), 0, 0);
            scoreCard.addView(wl);
        }
        if (liveAdj != null && liveAdj.points != 0) {
            TextView al = Ui.tv(this, String.format("Live adjustment %s%d — %s. (Display only; the alert gate still uses the plain score.)",
                    liveAdj.points > 0 ? "+" : "−", Math.abs(liveAdj.points), liveAdj.note),
                    12f, liveAdj.points > 0 ? Ui.GREEN : Ui.RED, false);
            al.setPadding(0, Ui.dp(this, 4), 0, 0);
            scoreCard.addView(al);
        }
        scoreCard.addView(Ui.tv(this, "Blends break strength, 20DMA trend, RSI zone, range quality, break timing"
                + (odds != null ? " and the Time Machine odds below." : "."), 11, Ui.FAINT, false));
        body.addView(scoreCard);

        // ---- watchlist + price alerts card ----
        LinearLayout watchCard = Ui.card(this);
        watchCard.addView(Ui.sectionTitle(this, "Watch & price alerts"));
        LinearLayout watchBox = new LinearLayout(this);
        watchBox.setOrientation(LinearLayout.VERTICAL);
        watchCard.addView(watchBox);
        buildWatchBox(watchBox, prefs, s.ltp);
        body.addView(watchCard);

        // ---- chart card ----
        if (spark.length > 1) {
            LinearLayout chart = Ui.card(this);
            chart.addView(Ui.sectionTitle(this, "Today · 5-min"));
            SparkView sv2 = new SparkView(this);
            sv2.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));
            sv2.setData(spark, s.orHigh, s.orLow, s.entry, s.stop, s.target,
                    s.setup == Analysis.Setup.ORB_UP);
            chart.addView(sv2);
            chart.addView(Ui.tv(this, "Shaded band = opening range (9:15–9:30) · dashed green = target · dashed red = stop",
                    11, Ui.FAINT, false));
            body.addView(chart);
        }

        // ---- setup + plan ----
        LinearLayout planCard = Ui.card(this);
        planCard.addView(Ui.sectionTitle(this, "Setup & plan"));
        String b = MainActivity.badge(s);
        if (!b.isEmpty()) {
            TextView pill = Ui.pill(this, b, Ui.CARD_ALT, MainActivity.badgeColor(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, 0, Ui.dp(this, 10));
            planCard.addView(pill, lp);
        }
        if (s.hasPlan()) {
            LinearLayout stats = new LinearLayout(this);
            stats.setOrientation(LinearLayout.HORIZONTAL);
            stats.addView(Ui.stat(this, MainActivity.fmt(s.entry), "ENTRY", Ui.TXT));
            stats.addView(Ui.stat(this, MainActivity.fmt(s.stop), "STOP · RANGE MID", Ui.RED));
            stats.addView(Ui.stat(this, MainActivity.fmt(s.target), "TARGET · 1R", Ui.GREEN));
            planCard.addView(stats);
            TextView sq = Ui.tv(this, "Square off before 15:30, no matter what.", 12, Ui.DIM, false);
            sq.setPadding(0, Ui.dp(this, 8), 0, 0);
            planCard.addView(sq);
        } else {
            planCard.addView(Ui.tv(this, "No ORB plan right now — price has not closed outside today's "
                    + "opening range. A plan appears the moment a breakout fires.", 13.5f, Ui.DIM, false));
        }
        body.addView(planCard);

        if (s.hasPlan() && plan != null) {
            // ---- budget + risk ----
            LinearLayout bud = Ui.card(this);
            bud.addView(Ui.sectionTitle(this, "Budget & risk · capital " + fmtRs(prefs.capital())
                    + " · max loss " + prefs.maxLossPct() + "%"));
            if (plan.qty > 0) {
                LinearLayout stats = new LinearLayout(this);
                stats.setOrientation(LinearLayout.HORIZONTAL);
                stats.addView(Ui.stat(this, String.valueOf(plan.qty), "QUANTITY", Ui.TXT));
                stats.addView(Ui.stat(this, fmtRs(plan.positionValue), "POSITION", Ui.TXT));
                stats.addView(Ui.stat(this, fmtRs(plan.qty * plan.riskPerShare), "LOSS AT STOP", Ui.RED));
                bud.addView(stats);
            }
            for (String c : plan.riskChecks) {
                boolean ok = c.contains("(ok)");
                TextView line = Ui.tv(this, (ok ? "✓  " : "✗  ") + c.replace(" (ok)", "").replace(" - FAIL)", ")"),
                        13, ok ? Ui.DIM : Ui.RED, false);
                line.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                bud.addView(line);
            }
            TextView verdict = Ui.pill(this, plan.pass ? "RISK · PASS" : "RISK · BLOCK",
                    plan.pass ? Ui.GREEN : Ui.RED, Ui.BG_BOT);
            verdict.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, Ui.dp(this, 10), 0, 0);
            bud.addView(verdict, lp);
            body.addView(bud);

            // ---- time machine ----
            if (odds != null) {
                LinearLayout tm = Ui.card(this);
                tm.addView(Ui.sectionTitle(this, "Time Machine · honest odds"));
                tm.addView(Ui.bar(this, "Target first", odds.targetFirst, Ui.GREEN));
                tm.addView(Ui.bar(this, "Stop first", odds.stopFirst, Ui.RED));
                tm.addView(Ui.bar(this, "Neither", odds.neither, Ui.DIM));
                String rec;
                if (odds.analogFires > 0) {
                    rec = String.format("Real record: fired %d times in the feed — target %d, stop %d, neither %d.",
                            odds.analogFires, odds.analogTarget, odds.analogStop, odds.analogNeither);
                } else {
                    rec = "Real record: this setup never fired before in the feed window.";
                }
                TextView r = Ui.tv(this, rec + String.format("  Monte Carlo: %,d replays of this stock's own bars.",
                        odds.paths), 12.5f, Ui.DIM, false);
                r.setPadding(0, Ui.dp(this, 8), 0, 0);
                tm.addView(r);
                if (odds.simOk) {
                    TextView sim = Ui.tv(this, String.format(
                            "Closest matches only — the ~%.0f past setups most like this one (range, score, break-bar strength, thrust, run-up, structure), from the feed history + this app's own live record: target first %.0f%% · stop %.0f%% · neither %.0f%%.",
                            odds.simEffN, odds.simTarget, odds.simStop, odds.simNeither), 12.5f, Ui.TXT, false);
                    sim.setPadding(0, Ui.dp(this, 4), 0, 0);
                    tm.addView(sim);
                }
                tm.addView(Ui.tv(this, "Odds are information, not a promise. Paper-trade first.", 11.5f, Ui.FAINT, false));
                body.addView(tm);
            }
        }

        // ---- prediction engine ----
        if (fc != null && fc.combosTotal > 0) {
            LinearLayout pc = Ui.card(this);
            pc.addView(Ui.sectionTitle(this, "Prediction Engine · " + String.format("%,d", fc.combosTotal) + " combinations"));
            for (int hi = 0; hi < Predict.HORIZON_LABEL.length; hi++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                TextView lab = Ui.tv(this, Predict.HORIZON_LABEL[hi], 12, Ui.DIM, true);
                lab.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 74), -2));
                row.addView(lab);
                if (fc.dir[hi] == 0) {
                    row.addView(Ui.tv(this, "No consensus — combinations abstain or cancel out", 12.5f, Ui.FAINT, false));
                } else {
                    boolean up = fc.dir[hi] > 0;
                    row.addView(Ui.pill(this, up ? "▲ UP" : "▼ DOWN",
                            up ? android.graphics.Color.rgb(16, 62, 44) : android.graphics.Color.rgb(66, 26, 30),
                            up ? Ui.GREEN : Ui.RED));
                    double pct = fc.lastClose > 0 ? fc.expPts[hi] / fc.lastClose * 100 : 0;
                    TextView val = Ui.mono(this, String.format("  %.0f%% agree · expect %s%.1f pts (%s%.2f%%)",
                            fc.consensus[hi], fc.expPts[hi] >= 0 ? "+" : "", fc.expPts[hi],
                            pct >= 0 ? "+" : "", pct), 12.5f, Ui.TXT, false);
                    row.addView(val);
                }
                pc.addView(row);
            }
            pc.addView(Ui.tv(this, String.format("%d combinations earned a vote (beat a coin flip in training) · "
                            + "%d voting on the current candles. Ensemble hit-rate on the newest days it never "
                            + "trained on: %.1f%% (n=%d).",
                    fc.combosLive, fc.firingNow, fc.holdoutAcc, fc.holdoutN), 12f, Ui.DIM, false));
            pc.addView(Ui.tv(this, "A consensus of tested candle rules — information, not a promise.",
                    11.5f, Ui.FAINT, false));
            String liveLine = "";
            try { liveLine = PredictLive.summaryLine(this); } catch (Throwable ignored) { }
            if (!liveLine.isEmpty()) {
                TextView ll = Ui.tv(this, liveLine, 12f, Ui.TXT, false);
                ll.setPadding(0, Ui.dp(this, 4), 0, 0);
                pc.addView(ll);
            }
            body.addView(pc);
        }

        // ---- options ticket: the options way to play this plan ----
        // (v52: skipped for XAU/USD gold - it has no NSE option to buy;
        // the plan levels above stand on their own.)
        if (s.hasPlan() && !Double.isNaN(s.ltp) && !Universe.isGoldYahoo(yahoo)) {
            java.time.LocalDate todayIst = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            boolean index = OptionsMath.strikeStep(yahoo) > 0;
            double iv = index && !Double.isNaN(vixLtp) ? vixLtp
                    : !Double.isNaN(stockIv) ? stockIv : 20;
            OptionsMath.Ticket tk = OptionsMath.ticket(s, iv, todayIst);
            int step = OptionsMath.strikeStepFor(yahoo, s.entry);
            boolean call = tk.call;
            LinearLayout oc2 = Ui.card(this);
            oc2.addView(Ui.sectionTitle(this, "Play it with options"));
            oc2.addView(Ui.mono(this, String.format("BUY %s %s · ATM", MainActivity.fmt(tk.strike).replace(",", ""),
                    call ? "CE" : "PE"), 17, Ui.TXT, true));
            LinearLayout orow = new LinearLayout(this);
            orow.setOrientation(LinearLayout.HORIZONTAL);
            orow.setPadding(0, Ui.dp(this, 8), 0, 0);
            orow.addView(Ui.stat(this, "₹" + MainActivity.fmt(tk.premium), "PREMIUM AT ENTRY", Ui.TXT));
            orow.addView(Ui.stat(this, "₹" + MainActivity.fmt(tk.premTarget), "AT STOCK TARGET", Ui.GREEN));
            orow.addView(Ui.stat(this, "₹" + MainActivity.fmt(tk.premStop), "AT STOCK STOP", Ui.RED));
            oc2.addView(orow);
            double itmStrike = call ? tk.strike - step : tk.strike + step;
            double itmPrem = OptionsMath.bsPrice(s.entry, itmStrike, tk.dte, iv, call);
            double gamma = OptionsMath.bsGamma(s.entry, tk.strike, tk.dte, iv);
            oc2.addView(Ui.tv(this, String.format("ITM instead: %s %s at ~₹%s (Δ %.2f) — costs more, moves faster per point.",
                    MainActivity.fmt(itmStrike).replace(",", ""), call ? "CE" : "PE",
                    MainActivity.fmt(itmPrem), OptionsMath.bsDelta(s.entry, itmStrike, tk.dte, iv, call)),
                    12.5f, Ui.DIM, false));
            oc2.addView(Ui.tv(this, String.format("ATM Greeks: Δ %.2f · Γ %.4f per point. Expiry %s%s. "
                            + "Exit at the premium target/stop above, or the 15:20 square-off — first comes first.",
                    tk.delta, gamma,
                    tk.expiry.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")),
                    tk.dte == 0 ? " (TODAY — gamma is extreme, premiums swing violently)" : ""),
                    12f, tk.dte == 0 ? Ui.RED : Ui.DIM, false));
            oc2.addView(Ui.tv(this, String.format("Black-Scholes estimates at IV ~%.0f%% (%s). Max loss is the premium paid. "
                            + "Live premiums and the nearest listed strike differ — check the chain before any order.",
                    iv, index ? "India VIX" : "the stock's own realized volatility"), 11.5f, Ui.FAINT, false));
            // Free NSE probe: real chain OI/IV when this network is
            // answered - an honest "no answer" when NSE blocks it.
            if (NseChain.endpointFor(yahoo) != null) {
                final android.widget.TextView nseLine = Ui.tv(this,
                        "NSE live chain: asking nseindia.com…", 12f, Ui.FAINT, false);
                oc2.addView(nseLine);
                final String probeYahoo = yahoo;
                final boolean probeCall = call;
                final double probeDayPct = s.dayPct;
                new Thread(() -> {
                    final NseChain.Probe p = NseChain.fetch(probeYahoo);
                    runOnUiThread(() -> {
                        try {
                            if (nseLine.getParent() == null) return;
                            nseLine.setText(p != null
                                    ? NseChain.line(p, probeCall) + "\n"
                                        + BullBear.line(p.addCeOi, p.addPeOi, p.pcr, probeDayPct)
                                    : "NSE live chain: no answer from this network — estimates above stand.");
                        } catch (Throwable ignored) { }
                    });
                }).start();
            }
            // Free BSE probe (v59): the Sensex card's mirror of the
            // NSE probe above, against BSE's public API - labelled
            // "BSE live chain" when it answers, the same honest
            // no-answer line when it does not (BSE's public chain
            // shape is unverified; the estimates above stand either
            // way until the phone's network proves it).
            if (BseChain.appliesTo(yahoo)) {
                final android.widget.TextView bseLine = Ui.tv(this,
                        "BSE live chain: asking bseindia.com…", 12f, Ui.FAINT, false);
                oc2.addView(bseLine);
                final String probeYahoo = yahoo;
                final boolean probeCall = call;
                final double probeSpot = s.ltp;
                new Thread(() -> {
                    final NseChain.Probe p = BseChain.fetch(probeYahoo, probeSpot);
                    runOnUiThread(() -> {
                        try {
                            if (bseLine.getParent() == null) return;
                            bseLine.setText(p != null
                                    ? BseChain.line(p, probeCall)
                                    : "BSE live chain: no answer from this network — estimates above stand.");
                        } catch (Throwable ignored) { }
                    });
                }).start();
            }
            body.addView(oc2);
        }

        // ---- gap fade plan (v42): the second tested setup ----
        if (s.hasFade()) {
            java.time.LocalDate fToday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            boolean fIndex = OptionsMath.strikeStep(yahoo) > 0;
            double fIv = fIndex && !Double.isNaN(vixLtp) ? vixLtp
                    : !Double.isNaN(stockIv) ? stockIv : 20;
            OptionsMath.Ticket ft = OptionsMath.ticket(s.sym, s.fadeUp,
                    s.fadeEntry, s.fadeStop, s.fadeTarget, Math.max(10, Math.min(fIv, 90)), fToday);
            LinearLayout fc2 = Ui.card(this);
            fc2.addView(Ui.sectionTitle(this, "Gap fade plan — the gap is filling"));
            fc2.addView(Ui.tv(this, String.format(
                    "Gapped %+.1f%% at the open, and by 09:45 it was already filling back — the tested play is against the gap: BUY %s.",
                    s.fadeGap, s.fadeUp ? "CALL" : "PUT"), 12.5f, Ui.DIM, false));
            fc2.addView(Ui.mono(this, String.format("Entry %s · stop %s · target %s (1R)",
                    MainActivity.fmt(s.fadeEntry), MainActivity.fmt(s.fadeStop), MainActivity.fmt(s.fadeTarget)),
                    12.5f, Ui.TXT, false));
            if (!Universe.isGoldYahoo(yahoo) && !Double.isNaN(ft.premium)) {
                fc2.addView(Ui.mono(this, String.format("BUY %s %s · ATM · premium ~₹%.1f → target ~₹%.1f · stop ~₹%.1f",
                        MainActivity.fmt(ft.strike).replace(",", ""), ft.call ? "CE" : "PE",
                        ft.premium, ft.premTarget, ft.premStop), 12.5f, Ui.TXT, false));
            }
            fc2.addView(Ui.tv(this, "The one new setup that passed the same 60-day test as ORB (both halves positive). "
                    + "Premiums are estimates — check the live chain before ordering.", 11.5f, Ui.FAINT, false));
            body.addView(fc2);
        }

        // ---- smart money card ----
        if (smcReport != null && smcReport.swingCount > 0) {
            Smc.Report sm = smcReport;
            LinearLayout smc = Ui.card(this);
            smc.addView(Ui.sectionTitle(this, "Smart money · structure & liquidity"));
            LinearLayout srow2 = new LinearLayout(this);
            srow2.setOrientation(LinearLayout.HORIZONTAL);
            srow2.addView(Ui.stat(this, sm.structure, "STRUCTURE",
                    sm.structure.contains("UP") ? Ui.GREEN : sm.structure.contains("DOWN") ? Ui.RED : Ui.DIM));
            srow2.addView(Ui.stat(this, sm.trend.split(" ")[0], "TREND",
                    sm.trend.startsWith("UP") ? Ui.GREEN : sm.trend.startsWith("DOWN") ? Ui.RED : Ui.DIM));
            String zone = Double.isNaN(sm.rangePosPct) ? "—"
                    : sm.rangePosPct >= 65 ? "PREMIUM" : sm.rangePosPct <= 35 ? "DISCOUNT" : "EQUILIB.";
            srow2.addView(Ui.stat(this, zone, "ZONE",
                    "PREMIUM".equals(zone) ? Ui.RED : "DISCOUNT".equals(zone) ? Ui.GREEN : Ui.DIM));
            srow2.addView(Ui.stat(this, Double.isNaN(sm.rvol) ? "—" : String.format("%.2fx", sm.rvol), "RVOL",
                    !Double.isNaN(sm.rvol) && sm.rvol >= 1.5 ? Ui.ORANGE : Ui.TXT));
            smc.addView(srow2);
            if (!Double.isNaN(sm.liqAbove)) {
                smc.addView(Ui.tv(this, String.format("Liquidity ABOVE at %s (%d touches) — stop pool %.2f%% away. Price loves to visit these before reversing.",
                        MainActivity.fmt(sm.liqAbove), Math.max(1, sm.liqAboveTouches),
                        (sm.liqAbove / s.ltp - 1) * 100), 12.5f, Ui.DIM, false));
            }
            if (!Double.isNaN(sm.liqBelow)) {
                smc.addView(Ui.tv(this, String.format("Liquidity BELOW at %s (%d touches) — stop pool %.2f%% away.",
                        MainActivity.fmt(sm.liqBelow), Math.max(1, sm.liqBelowTouches),
                        (1 - sm.liqBelow / s.ltp) * 100), 12.5f, Ui.DIM, false));
            }
            if (sm.sweepUp) {
                TextView w = Ui.tv(this, "⚠ SWEEP: wicked above yesterday's high and closed back inside — stops were hunted. Classic reversal warning.",
                        12.5f, Ui.RED, true);
                w.setPadding(0, Ui.dp(this, 4), 0, 0);
                smc.addView(w);
            }
            if (sm.sweepDown) {
                TextView w = Ui.tv(this, "⚠ SWEEP: wicked below yesterday's low and closed back inside — sell-side stops hunted. Classic reversal warning.",
                        12.5f, Ui.GREEN, true);
                w.setPadding(0, Ui.dp(this, 4), 0, 0);
                smc.addView(w);
            }
            if (sm.hasOb) {
                smc.addView(Ui.tv(this, String.format("%s order block: %s – %s (the launchpad of today's strongest move).",
                        sm.obBullish ? "Bullish" : "Bearish",
                        MainActivity.fmt(sm.obLow), MainActivity.fmt(sm.obHigh)), 12.5f, Ui.DIM, false));
            }
            if (sm.hasFvg) {
                smc.addView(Ui.tv(this, String.format("%s fair value gap: %s – %s (unfilled imbalance — price often returns to fill it).",
                        sm.fvgBullish ? "Bullish" : "Bearish",
                        MainActivity.fmt(sm.fvgLow), MainActivity.fmt(sm.fvgHigh)), 12.5f, Ui.DIM, false));
            }
            smc.addView(Ui.tv(this, "Computed from the bars themselves. Open interest, max pain and the live chain join with the Dhan feed.",
                    11, Ui.FAINT, false));
            body.addView(smc);
        }

        // ---- multi-timeframe + 52-week card ----
        if (daily1y != null && daily1y.close.length > 60) {
            java.util.List<Double> closes = new java.util.ArrayList<>();
            double hi52 = Double.NEGATIVE_INFINITY, lo52 = Double.POSITIVE_INFINITY;
            for (int k2 = 0; k2 < daily1y.close.length; k2++) {
                if (!Double.isNaN(daily1y.close[k2])) closes.add(daily1y.close[k2]);
                if (!Double.isNaN(daily1y.high[k2])) hi52 = Math.max(hi52, daily1y.high[k2]);
                if (!Double.isNaN(daily1y.low[k2])) lo52 = Math.min(lo52, daily1y.low[k2]);
            }
            if (closes.size() > 60) {
                java.util.function.IntFunction<Double> sma = n -> {
                    if (closes.size() < n) return Double.NaN;
                    double sum = 0;
                    for (int k3 = closes.size() - n; k3 < closes.size(); k3++) sum += closes.get(k3);
                    return sum / n;
                };
                double px = closes.get(closes.size() - 1);
                double s20 = sma.apply(20), s50 = sma.apply(50), s200 = sma.apply(200);
                LinearLayout mtf = Ui.card(this);
                mtf.addView(Ui.sectionTitle(this, "Multi-timeframe · 52-week"));
                LinearLayout mrow = new LinearLayout(this);
                mrow.setOrientation(LinearLayout.HORIZONTAL);
                mrow.addView(Ui.stat(this, Double.isNaN(s20) ? "—" : MainActivity.fmt(s20), "20DMA",
                        px >= s20 ? Ui.GREEN : Ui.RED));
                mrow.addView(Ui.stat(this, Double.isNaN(s50) ? "—" : MainActivity.fmt(s50), "50DMA",
                        px >= s50 ? Ui.GREEN : Ui.RED));
                mrow.addView(Ui.stat(this, Double.isNaN(s200) ? "—" : MainActivity.fmt(s200), "200DMA",
                        Double.isNaN(s200) ? Ui.DIM : (px >= s200 ? Ui.GREEN : Ui.RED)));
                mtf.addView(mrow);
                String trend = Double.isNaN(s200) ? "" :
                        (px >= s200 ? "Long-term trend: UP (above 200DMA). " : "Long-term trend: DOWN (below 200DMA). ");
                mtf.addView(Ui.tv(this, trend + String.format(
                        "52-week range %s – %s · now %.1f%% below the high, %.1f%% above the low.",
                        MainActivity.fmt(lo52), MainActivity.fmt(hi52),
                        (hi52 - px) / hi52 * 100, (px - lo52) / lo52 * 100), 12.5f, Ui.DIM, false));
                body.addView(mtf);
            }
        }

        // ---- selection path: how this stock travelled the pipeline ----
        LinearLayout path = Ui.card(this);
        path.addView(Ui.sectionTitle(this, "Selection path · why this signal"));
        pathStep(path, true, "DATA", "Yahoo 5-min bars + 6 months of daily history received.");
        if (s.hasPlan()) {
            String at = java.time.Instant.ofEpochSecond(s.breakTs)
                    .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"));
            pathStep(path, true, "SCANNER",
                    (s.setup == Analysis.Setup.ORB_UP ? "Broke ABOVE" : "Broke BELOW")
                            + " the opening range at " + at + " IST.");
        } else {
            pathStep(path, false, "SCANNER", "No breakout — still inside the opening range. Journey ends here today.");
        }
        pathStep(path, news != null && !news.isEmpty(), "NEWS",
                news == null || news.isEmpty() ? "No fresh headlines fetched."
                        : news.size() + " fresh headlines checked below — context, never the trigger.");
        if (s.hasPlan() && odds != null) {
            pathStep(path, odds.targetFirst >= odds.stopFirst, "TIME MACHINE",
                    String.format("Target first %.0f%% vs stop first %.0f%% over %,d replays; %d real past firings.",
                            odds.targetFirst, odds.stopFirst, odds.paths, odds.analogFires));
        }
        if (s.hasPlan() && plan != null) {
            pathStep(path, plan.pass, "RISK GATE",
                    plan.pass ? "PASS — quantity " + plan.qty + " sized on your capital and max-loss rule."
                              : "BLOCK — failed a risk check above. No trade.");
            String key = yahoo + "|" + s.setup + "|" + java.time.LocalDate.now(
                    java.time.ZoneId.of("Asia/Kolkata"))
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            boolean fired = prefs.setupFired(key);
            pathStep(path, fired, "SIGNAL → NTFY",
                    fired ? "Buy alert already sent to your ntfy today."
                          : "Alert fires within a minute in market hours (background check / app scan).");
        }
        body.addView(path);

        // ---- news card ----
        if (news != null && !news.isEmpty()) {
            LinearLayout nc = Ui.card(this);
            nc.addView(Ui.sectionTitle(this, "News · catalyst check"));
            for (NewsClient.Item n : news) {
                TextView t = Ui.tv(this, "• " + n.title + (n.source.isEmpty() ? "" : "  (" + n.source + ")"),
                        13, Ui.DIM, false);
                t.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                nc.addView(t);
            }
            body.addView(nc);
        }

        // ---- paper trade button ----
        if (s.hasPlan() && plan != null) {
            TextView paper = Ui.pill(this, "PAPER TRADE THIS PLAN", Ui.CARD_ALT, Ui.ORANGE);
            paper.setTextSize(13.5f);
            paper.setGravity(Gravity.CENTER);
            paper.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
            plp.setMargins(0, 0, 0, Ui.dp(this, 10));
            paper.setLayoutParams(plp);
            paper.setOnClickListener(v -> {
                PaperStore.add(this, s, plan.qty);
                Toast.makeText(this, "Added to Paper Journal (qty " + Math.max(1, plan.qty) + ")",
                        Toast.LENGTH_SHORT).show();
            });
            body.addView(paper);
        }

        // ---- send button ----
        TextView send = Ui.pill(this, "SEND THIS TO NTFY", Ui.BLUE, Ui.TXT);
        send.setTextSize(14);
        send.setBackground(Ui.accentBtnBg(this));
        send.setPadding(0, Ui.dp(this, 13), 0, Ui.dp(this, 13));
        send.setOnClickListener(v -> {
            boolean index = OptionsMath.strikeStep(yahoo) > 0;
            double iv = index && !Double.isNaN(vixLtp) ? vixLtp
                    : !Double.isNaN(stockIv) ? stockIv : 20;
            sendToNtfy(s, iv);
        });
        body.addView(send);
    }

    /** Watchlist star + one-shot price alerts for this symbol. Rebuilt in place after every change. */
    private void buildWatchBox(LinearLayout box, Prefs prefs, double ltp) {
        box.removeAllViews();
        boolean watched = prefs.isWatched(yahoo);
        TextView star = Ui.pill(this, watched ? "★  IN YOUR WATCHLIST — TAP TO REMOVE" : "☆  ADD TO WATCHLIST",
                watched ? Ui.ORANGE : Ui.CARD_ALT, watched ? Ui.BG_BOT : Ui.TXT);
        star.setGravity(Gravity.CENTER);
        star.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        star.setOnClickListener(v -> {
            boolean now = prefs.toggleWatch(yahoo);
            Toast.makeText(this, now ? name + " added to watchlist" : name + " removed from watchlist",
                    Toast.LENGTH_SHORT).show();
            buildWatchBox(box, prefs, ltp);
        });
        box.addView(star);

        for (String e : prefs.priceAlerts()) {
            String[] p = e.split("\\|");
            if (p.length < 3 || !p[0].equals(yahoo)) continue;
            // v59: a recurring alert says so on its row.
            boolean recurring = Prefs.priceAlertRecurring(e);
            TextView row = Ui.pill(this, "Alert when " + p[1].toLowerCase() + " " + p[2]
                            + (recurring ? " · repeats" : "") + "   ✕ remove",
                    Ui.CARD_ALT, Ui.DIM);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, Ui.dp(this, 8), 0, 0);
            row.setLayoutParams(rlp);
            final String entry = e;
            row.setOnClickListener(v -> { prefs.removePriceAlert(entry); buildWatchBox(box, prefs, ltp); });
            box.addView(row);
        }

        EditText level = new EditText(this);
        if (!Double.isNaN(ltp)) level.setText(String.format("%.2f", ltp));
        level.setHint("Price level");
        level.setTextColor(Ui.TXT);
        level.setHintTextColor(Ui.FAINT);
        level.setTextSize(13.5f);
        level.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        level.setBackground(Ui.cardBg(Ui.CARD_ALT, 10, this));
        level.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        level.setLayoutParams(llp);
        box.addView(level);

        // v59: the Repeat toggle - one-shot stays the default; a
        // ticked alert re-arms after firing instead of removing
        // itself, and fires again on the next fresh cross.
        android.widget.CheckBox repeatCb = new android.widget.CheckBox(this);
        repeatCb.setText("Repeat — keep this alert armed after it fires (fires again on the next fresh cross)");
        repeatCb.setTextColor(Ui.TXT);
        repeatCb.setTextSize(12.5f);
        repeatCb.setChecked(false);
        box.addView(repeatCb);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView above = Ui.pill(this, "ALERT ABOVE", Ui.CARD_ALT, Ui.GREEN);
        above.setGravity(Gravity.CENTER);
        above.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView below = Ui.pill(this, "ALERT BELOW", Ui.CARD_ALT, Ui.RED);
        below.setGravity(Gravity.CENTER);
        below.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, -2, 1);
        blp2.setMargins(Ui.dp(this, 8), 0, 0, 0);
        above.setOnClickListener(v -> addPriceAlert(prefs, level, true, repeatCb.isChecked(), box, ltp));
        below.setOnClickListener(v -> addPriceAlert(prefs, level, false, repeatCb.isChecked(), box, ltp));
        btnRow.addView(above, new LinearLayout.LayoutParams(0, -2, 1));
        btnRow.addView(below, blp2);
        box.addView(btnRow);
        TextView hint = Ui.tv(this, "One-shot: fires once on ntfy when the level is crossed (checked every minute), then removes itself. "
                        + "With Repeat ticked it stays armed instead and fires on each fresh cross.",
                11, Ui.FAINT, false);
        hint.setPadding(0, Ui.dp(this, 6), 0, 0);
        box.addView(hint);
    }

    private void addPriceAlert(Prefs prefs, EditText level, boolean above, boolean recurring,
                               LinearLayout box, double ltp) {
        try {
            double px = Double.parseDouble(level.getText().toString().trim());
            if (px <= 0) throw new NumberFormatException();
            prefs.addPriceAlert(yahoo, above, px, recurring);
            Toast.makeText(this, "Price alert set for " + name + (recurring ? " (repeating)" : ""),
                    Toast.LENGTH_SHORT).show();
            buildWatchBox(box, prefs, ltp);
        } catch (Exception e) {
            Toast.makeText(this, "Enter a valid price level", Toast.LENGTH_SHORT).show();
        }
    }

    /** One step of the selection path: a status dot, the stage, and what happened. */
    private void pathStep(LinearLayout card, boolean ok, String stage, String what) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        TextView dot = Ui.pill(this, ok ? "✓" : "·", ok ? Ui.GREEN : Ui.CARD_ALT, ok ? Ui.BG_BOT : Ui.DIM);
        row.addView(dot);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 10), 0, 0, 0);
        col.addView(Ui.tv(this, stage, 12.5f, Ui.TXT, true));
        col.addView(Ui.tv(this, what, 12.5f, Ui.DIM, false));
        row.addView(col);
        card.addView(row);
    }

    // v56: manual-share feed-leg modes for sendToNtfy / shareToast.
    private static final int FEED_NA = 0, FEED_SEND = 1, FEED_SKIP = 2;

    /** v56 completion wording for a manual share: the human leg
     *  first, then the feed leg's honest outcome. No-plan shares
     *  (FEED_NA) keep the pre-v56 wording word-for-word. */
    private static String shareToast(boolean humanOk, int feedMode, boolean feedOk, String skipWhy) {
        if (!humanOk) {
            return feedMode == FEED_NA ? "ntfy failed — check topic/internet"
                    : "ntfy failed — check topic/internet (feed not sent)";
        }
        if (feedMode == FEED_SEND) {
            return feedOk ? "Sent to your ntfy topics + order-system feed"
                    : "Sent to your ntfy topics — order-system feed FAILED";
        }
        if (feedMode == FEED_SKIP) {
            return "Sent to your ntfy topics only — feed skipped (" + skipWhy + ")";
        }
        return "Sent to your ntfy topics";
    }

    private void sendToNtfy(Analysis.Scan s, double iv) {
        Prefs prefs = new Prefs(this);
        String title = "IraThinker: " + name;
        String bodyText;
        OptionsMath.Ticket feedTk = null;
        if (s.hasPlan()) {
            // A manual share must never read as a fresh auto alert:
            // stamp what it is, whether the plan already resolved,
            // and whether it would even pass the user's alert rules.
            StringBuilder b = new StringBuilder("Manual share from the app — not an auto alert.\n");
            String fresh = Analysis.planFreshness(s);
            if ("TARGET CROSSED".equals(fresh) || "STOP CROSSED".equals(fresh)) {
                b.append("⚠ Already resolved: LTP is past the ")
                        .append(fresh.startsWith("TARGET") ? "target" : "stop")
                        .append(" — not a fresh signal.\n");
            }
            // Review 26 Oct 2026: a symbol that never strategy-alerts
            // (Universe.alertable) would not auto-fire either.
            if (!Universe.alertable(s.sym) || !Analysis.alertWorthy(s, BuyAlerts.scoreFor(s),
                    Math.max(Analysis.ALERT_MIN_SCORE, prefs.minAlertScore()))) {
                b.append("Note: this plan would not auto-fire under your alert rules — sharing manually.\n");
            }
            String ticket = "";
            if (!Universe.isGoldYahoo(yahoo)) { // v52: no NSE options ticket for gold
                try {
                    ticket = OptionsMath.ticketText(s, Math.max(10, Math.min(iv, 90)),
                            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
                } catch (Throwable ignored) { }
            }
            // v56: the System Feed leg needs the ticket as an object
            // (the body above keeps its text form, unchanged) - priced
            // at the 2R alert target, exactly as the auto alerts price
            // theirs. Gold never reaches here (no ticket exists).
            if (!Universe.isGoldYahoo(yahoo)) {
                try {
                    feedTk = OptionsMath.ticket(s, Math.max(10, Math.min(iv, 90)),
                            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")),
                            Analysis.alertTarget(s));
                } catch (Throwable ignored) { }
            }
            b.append(String.format("%s%s%nStock levels — entry %s · stop %s · target %s (LTP %s).",
                    MainActivity.badge(s), ticket,
                    MainActivity.fmt(s.entry), MainActivity.fmt(s.stop), MainActivity.fmt(s.target),
                    MainActivity.fmt(s.ltp)));
            bodyText = b.toString();
        } else {
            bodyText = "Manual share from the app — not an auto alert.\n"
                    + name + " at " + MainActivity.fmt(s.ltp) + " — no plan active.";
        }
        // v56 feed leg (user decision 2026-10-25: manual shares go to
        // BOTH ntfy channels). The decision and the payload are pure
        // and built here; the post itself rides the send thread
        // below. Modes: NA = no plan (human-only share, exactly as
        // before); SKIP = plan, but the feed is off / unconfigured
        // or the plan has no honest ticket; SEND = mirror the plan
        // to the machine topic as a MANUAL signal.
        final int feedMode;
        final String feedBody, feedSkipWhy;
        if (!s.hasPlan()) {
            feedMode = FEED_NA; feedBody = null; feedSkipWhy = null;
        } else if (SystemFeed.shouldSendManual(prefs.feedOn(), prefs.feedTopic(), s, feedTk)) {
            String built = null;
            try {
                double t1 = Analysis.alertTarget(s), t2 = Analysis.alertTarget3(s);
                // v59: the MANUAL payload carries the Risk Gate
                // sizing for the share's own ticket, exactly as the
                // auto alerts' payloads carry theirs.
                built = SystemFeed.manualPayload(s, BuyAlerts.scoreFor(s),
                        SystemFeed.manualSignalId(s, t1,
                                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString()),
                        t1, t2, feedTk, SystemFeed.nowIst(),
                        OptionsMath.sizingFor(feedTk, prefs.capital(), prefs.maxLossPct())).toString();
            } catch (Throwable ignored) { }
            feedMode = built != null ? FEED_SEND : FEED_SKIP;
            feedBody = built;
            feedSkipWhy = built != null ? null : "payload could not be built";
        } else {
            feedMode = FEED_SKIP; feedBody = null;
            feedSkipWhy = !SystemFeed.shouldSend(prefs.feedOn(), prefs.feedTopic())
                    ? (prefs.feedOn() ? "no feed topic set" : "feed off")
                    : "no option ticket for this plan";
        }
        new Thread(() -> {
            // v58 (user clarification 2026-10-25): the MANUAL human
            // leg goes to ALL his human topics - stored + private -
            // via manualShareTopics(), never the privateLive-narrowed
            // activeTopics() (that narrowing still governs the auto
            // alerts; this send is one the user triggers himself).
            boolean ok = NtfyClient.postToAll(prefs, prefs.manualShareTopics(), title, bodyText, "chart_with_upwards_trend");
            // The feed mirrors only a human post that actually landed
            // (the auto alerts' invariant), on this same thread,
            // through the one shared feed send path: a slow or dead
            // machine topic can never block, delay or fail the human
            // leg, and its outcome joins the Toast.
            boolean feedOk = false;
            if (ok && feedMode == FEED_SEND) {
                feedOk = BuyAlerts.postFeedNow(prefs, feedBody);
            }
            final String msg = shareToast(ok, feedMode, feedOk, feedSkipWhy);
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** Indian digit grouping (lakh/crore style). */
    static String fmtRs(double v) {
        long n = Math.round(v);
        String s = Long.toString(Math.abs(n));
        StringBuilder out = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            int fromRight = len - i;
            out.append(s.charAt(i));
            if (fromRight > 1 && (fromRight == 3 || (fromRight > 3 && (fromRight - 3) % 2 == 0))) {
                if (i < len - 1) out.append(',');
            }
        }
        return "Rs " + (n < 0 ? "-" : "") + out;
    }
}
