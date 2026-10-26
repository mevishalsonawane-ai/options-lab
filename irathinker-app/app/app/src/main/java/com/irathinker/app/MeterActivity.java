package com.irathinker.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Market Meter screen: NIFTY and BANKNIFTY sentiment dials built by
 *  Meter.java from Yahoo scans, the free NSE chain probe and universe
 *  breadth. Refreshes every 60 seconds while open. Our own gauge -
 *  not GOC Technology's meter; display only, never an alert gate. */
public class MeterActivity extends Activity {

    private static class IndexState {
        Universe.Sym sym;
        volatile Analysis.Scan scan;
        volatile NseChain.Probe probe;
    }

    private LinearLayout content;
    private TextView status;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile int runGen = 0;
    private volatile boolean destroyed = false;

    private final IndexState nifty = new IndexState();
    private final IndexState bank = new IndexState();
    private volatile Analysis.Scan vixScan;

    private static volatile int bUp = -1, bDown = -1;
    private static volatile long breadthAt = 0;
    private volatile boolean breadthRunning = false;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private boolean ticking = false;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!ticking || destroyed) return;
            load();
            tick.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nifty.sym = sym("^NSEI");
        bank.sym = sym("^NSEBANK");
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
        root.addView(Ui.tv(this, "Market Meter", 24, Ui.TXT, true));
        TextView sub = Ui.tv(this, "Market-wide bull/bear sentiment for NIFTY and BANKNIFTY, "
                + "computed on this phone from live prices, the NSE option chain and the "
                + "breadth of all 55 stocks. Our own gauge - not GOC Technology's meter.", 13, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        root.addView(sub);

        TextView refresh = Ui.pill(this, "REFRESH NOW", Ui.CARD_ALT, Ui.ORANGE);
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        refresh.setOnClickListener(v -> load());
        root.addView(refresh);

        status = Ui.tv(this, "Loading…", 12, Ui.FAINT, false);
        status.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        LinearLayout foot = Ui.card(this);
        foot.addView(Ui.sectionTitle(this, "How this meter works"));
        foot.addView(Ui.tv(this, "Six factors, each scored -1 (bearish) to +1 (bullish), "
                + "combined at the weights printed on their rows: OI added today in the live "
                + "chain (put-side additions count bullish), the PCR level, India VIX level + "
                + "trend, the index's day move and position vs its 20-day average, its RSI, and "
                + "universe breadth. A factor with no data - NSE not answering, breadth still "
                + "warming - drops out and the rest re-weight; the card says which are in. "
                + "A sentiment gauge is information, not a signal: it never triggers an alert.", 12, Ui.DIM, false));
        root.addView(foot);
    }

    @Override protected void onResume() {
        super.onResume();
        ticking = true;
        load();
        tick.postDelayed(ticker, 60_000);
    }

    @Override protected void onPause() {
        super.onPause();
        ticking = false;
        tick.removeCallbacks(ticker);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        pool.shutdownNow();
        super.onDestroy();
    }

    private Universe.Sym sym(String yahoo) {
        for (Universe.Sym s : Universe.all()) if (s.yahoo.equals(yahoo)) return s;
        return null;
    }

    private Analysis.Scan scanOf(Universe.Sym s) {
        try {
            YahooClient.Chart intra = YahooClient.intraday(s.yahoo);
            YahooClient.Chart daily = YahooClient.daily(s.yahoo);
            return Analysis.scan(s, intra, daily);
        } catch (Throwable t) {
            return null;
        }
    }

    private void load() {
        final int gen = ++runGen;
        status.setText("Refreshing…");
        pool.execute(() -> {
            vixScan = scanOf(sym("^INDIAVIX"));
            nifty.scan = scanOf(nifty.sym);
            bank.scan = scanOf(bank.sym);
            post(gen);
        });
        pool.execute(() -> {
            nifty.probe = NseChain.fetch(nifty.sym.yahoo);
            post(gen);
        });
        pool.execute(() -> {
            bank.probe = NseChain.fetch(bank.sym.yahoo);
            post(gen);
        });
        maybeBreadth(gen);
    }

    private void maybeBreadth(final int gen) {
        if (breadthRunning || System.currentTimeMillis() - breadthAt < 300_000) return;
        breadthRunning = true;
        pool.execute(() -> {
            int up = 0, down = 0;
            java.util.List<Universe.Sym> eq = new java.util.ArrayList<>();
            for (Universe.Sym s : Universe.all()) if (s.yahoo.endsWith(".NS")) eq.add(s);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(eq.size());
            java.util.concurrent.atomic.AtomicInteger u = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger d = new java.util.concurrent.atomic.AtomicInteger();
            ExecutorService bp = Executors.newFixedThreadPool(6);
            for (Universe.Sym s : eq) {
                bp.execute(() -> {
                    try {
                        YahooClient.Chart daily = YahooClient.daily(s.yahoo);
                        double last = Double.NaN, prev = Double.NaN;
                        for (int i = daily.close.length - 1; i >= 0; i--) {
                            if (Double.isNaN(daily.close[i])) continue;
                            if (Double.isNaN(last)) last = daily.close[i];
                            else { prev = daily.close[i]; break; }
                        }
                        if (!Double.isNaN(last) && !Double.isNaN(prev) && prev > 0) {
                            double pct = last / prev - 1;
                            if (pct > 0.0005) u.incrementAndGet();
                            else if (pct < -0.0005) d.incrementAndGet();
                        }
                    } catch (Throwable ignored) { }
                    latch.countDown();
                });
            }
            try { latch.await(120, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }
            bp.shutdownNow();
            if (u.get() + d.get() >= 10) {
                bUp = u.get();
                bDown = d.get();
                breadthAt = System.currentTimeMillis();
            }
            breadthRunning = false;
            post(gen);
        });
    }

    private void post(final int gen) {
        runOnUiThread(() -> {
            if (destroyed || gen != runGen || isFinishing()) return;
            render();
        });
    }

    private void render() {
        content.removeAllViews();
        content.addView(indexCard(nifty));
        content.addView(indexCard(bank));
        LinearLayout ctx = Ui.card(this);
        ctx.addView(Ui.sectionTitle(this, "Market context"));
        String vixLine = vixScan == null ? "India VIX: no data yet"
                : String.format(Locale.US, "India VIX %.1f (%+.1f%% today)", vixScan.ltp, vixScan.dayPct);
        ctx.addView(Ui.tv(this, vixLine, 13, Ui.TXT, false));
        String brLine = bUp < 0 ? "Breadth: still warming up (scans all 55 stocks in the background)"
                : String.format(Locale.US, "Breadth: %d advancing · %d declining of %d stocks",
                        bUp, bDown, bUp + bDown);
        TextView br = Ui.tv(this, brLine, 13, Ui.TXT, false);
        br.setPadding(0, Ui.dp(this, 4), 0, 0);
        ctx.addView(br);
        content.addView(ctx);
        status.setText("Updated " + java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + " · auto-refresh every 60s while open");
    }

    private LinearLayout indexCard(IndexState st) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, st.sym.name));
        double oi = st.probe != null ? Meter.oiFactor(st.probe.addCeOi, st.probe.addPeOi) : Double.NaN;
        double pcr = st.probe != null ? Meter.pcrFactor(st.probe.pcr) : Double.NaN;
        double vixF = vixScan != null ? Meter.vixFactor(vixScan.ltp, vixScan.dayPct) : Double.NaN;
        double mom = st.scan != null ? Meter.momFactor(st.scan.dayPct, st.scan.ltp, st.scan.dma20) : Double.NaN;
        double rsi = st.scan != null ? Meter.rsiFactor(st.scan.rsi) : Double.NaN;
        double brd = bUp >= 0 ? Meter.breadthFactor(bUp, bDown) : Double.NaN;
        Meter.Result r = Meter.combine(oi, pcr, vixF, mom, rsi, brd);

        MeterView dial = new MeterView(this);
        dial.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 148)));
        boolean has = st.scan != null || st.probe != null;
        dial.setScore(r.score, has);
        card.addView(dial);

        TextView big = Ui.tv(this, has ? String.format(Locale.US, "%+d · %s", r.score, r.zone()) : "No data yet",
                22, !has ? Ui.FAINT : r.score >= 15 ? Ui.GREEN : r.score <= -15 ? Ui.RED : Ui.ORANGE, true);
        big.setGravity(Gravity.CENTER);
        card.addView(big);
        TextView fac = Ui.tv(this, has
                ? String.format(Locale.US, "Bull power %d%% · Bear power %d%% · %d of 6 factors in",
                        (r.score + 100) / 2, 100 - (r.score + 100) / 2, r.factorsUsed)
                : "Waiting for the first prices…", 12, Ui.FAINT, false);
        fac.setGravity(Gravity.CENTER);
        fac.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(fac);

        card.addView(factorRow("OI added today", (int) Meter.W_OI, oi));
        card.addView(factorRow("PCR level", (int) Meter.W_PCR, pcr));
        card.addView(factorRow("India VIX", (int) Meter.W_VIX, vixF));
        card.addView(factorRow("Momentum + 20DMA", (int) Meter.W_MOM, mom));
        card.addView(factorRow("RSI", (int) Meter.W_RSI, rsi));
        card.addView(factorRow("Breadth (55 stocks)", (int) Meter.W_BREADTH, brd));

        TextView note = Ui.tv(this, st.probe != null
                ? String.format(Locale.US, "NSE chain: live · PCR %.2f · OI added PE %s vs CE %s",
                        st.probe.pcr, lakhs(st.probe.addPeOi), lakhs(st.probe.addCeOi))
                : "NSE chain: no answer from this network - OI factors are out, the rest re-weighted.",
                11.5f, Ui.FAINT, false);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(note);
        return card;
    }

    private LinearLayout factorRow(String name, int weight, double score) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        TextView n = Ui.tv(this, name + " · " + weight + "%", 12, Ui.DIM, false);
        row.addView(n, new LinearLayout.LayoutParams(Ui.dp(this, 150), -2));
        FactorBarView bar = new FactorBarView(this);
        bar.setScore(score);
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 16), 1));
        TextView v = Ui.tv(this, Double.isNaN(score) ? "—"
                : String.format(Locale.US, "%+.2f", score), 12,
                Double.isNaN(score) ? Ui.FAINT : score >= 0 ? Ui.GREEN : Ui.RED, true);
        v.setGravity(Gravity.RIGHT);
        row.addView(v, new LinearLayout.LayoutParams(Ui.dp(this, 52), -2));
        return row;
    }

    private static String lakhs(double contracts) {
        if (contracts >= 100_000) return String.format(Locale.US, "%.1fL", contracts / 100_000.0);
        if (contracts >= 1_000) return String.format(Locale.US, "%.1fk", contracts / 1_000.0);
        return String.format(Locale.US, "%.0f", contracts);
    }
}
