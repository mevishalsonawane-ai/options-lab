package com.irathinker.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Strategy Lab: replay the app's own buy/sell rules over 60 days of
 *  5-minute bars and grade them - win rate, profit factor, expectancy,
 *  drawdown, equity curve, and every trade. The honesty layer: the
 *  same rules the alerts use, tested in the open. */
public class LabActivity extends Activity {

    private Universe.Sym sym = new Universe.Sym("NIFTY 50", "^NSEI", Universe.G_INDEX);
    private int orMinutes = 15;
    private double rMult = 1.0;
    private int side = 0; // 0 both, +1 buy only, -1 sell only
    private boolean portfolio = false;

    private LinearLayout controlsBox, resultsBox;
    private TextView status;
    private final Map<String, YahooClient.Chart> cache = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile int runGen = 0;

    private static final String[][] QUICK = {
            {"NIFTY", "^NSEI"}, {"BANKNIFTY", "^NSEBANK"}, {"RELIANCE", "RELIANCE.NS"},
            {"TCS", "TCS.NS"}, {"HDFC BANK", "HDFCBANK.NS"}, {"INFY", "INFY.NS"},
            {"SBIN", "SBIN.NS"}, {"TATA MOTORS", "TMPV.NS"}, {"ITC", "ITC.NS"},
            {"TITAN", "TITAN.NS"}, {"SUN PHARMA", "SUNPHARMA.NS"}, {"MARUTI", "MARUTI.NS"},
    };

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
        root.addView(Ui.tv(this, "Strategy Lab", 24, Ui.TXT, true));
        TextView sub = Ui.tv(this, "Test the buy / sell rules on the last 60 days of 5-minute data. "
                + "Same ORB engine as the live alerts - no cherry-picking.", 13, Ui.DIM, false);
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

    private LinearLayout pillRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void rebuildControls() {
        controlsBox.removeAllViews();

        // symbol chips (scrollable)
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = pillRow();
        for (String[] q : QUICK) {
            boolean active = !portfolio && sym.yahoo.equals(q[1]);
            chips.addView(pill(q[0], active, v -> {
                portfolio = false;
                sym = new Universe.Sym(q[0], q[1], Universe.G_INDEX);
                rebuildControls(); run();
            }));
        }
        chips.addView(pill("ALL NIFTY 50", portfolio, v -> {
            portfolio = true;
            rebuildControls(); run();
        }));
        hs.addView(chips);
        controlsBox.addView(hs);

        LinearLayout orRow = pillRow();
        orRow.addView(Ui.tv(this, "Opening range  ", 13, Ui.DIM, true));
        for (int m : new int[]{15, 30}) {
            orRow.addView(pill(m + " min", orMinutes == m, v -> { orMinutes = m; rebuildControls(); run(); }));
        }
        controlsBox.addView(orRow);

        LinearLayout rRow = pillRow();
        rRow.addView(Ui.tv(this, "Target  ", 13, Ui.DIM, true));
        for (double r : new double[]{0.5, 1.0, 1.5, 2.0}) {
            rRow.addView(pill(r + "R", rMult == r, v -> { rMult = r; rebuildControls(); run(); }));
        }
        controlsBox.addView(rRow);

        LinearLayout sRow = pillRow();
        sRow.addView(Ui.tv(this, "Side  ", 13, Ui.DIM, true));
        sRow.addView(pill("BOTH", side == 0, v -> { side = 0; rebuildControls(); run(); }));
        sRow.addView(pill("BUY ONLY", side == 1, v -> { side = 1; rebuildControls(); run(); }));
        sRow.addView(pill("SELL ONLY", side == -1, v -> { side = -1; rebuildControls(); run(); }));
        controlsBox.addView(sRow);
    }

    private YahooClient.Chart chart(String yahoo) throws Exception {
        synchronized (cache) {
            YahooClient.Chart c = cache.get(yahoo);
            if (c != null) return c;
        }
        YahooClient.Chart c = YahooClient.fetch(yahoo, "60d", "5m");
        synchronized (cache) { cache.put(yahoo, c); }
        return c;
    }

    private void run() {
        final int gen = ++runGen;
        resultsBox.removeAllViews();
        status.setText("Running backtest…");
        new Thread(() -> {
            try {
                List<Analysis.Trade> trades = new ArrayList<>();
                if (portfolio) {
                    List<Universe.Sym> list = new ArrayList<>();
                    for (Universe.Sym s : Universe.all()) {
                        if (Universe.G_NIFTY.equals(s.group)) list.add(s);
                    }
                    final Object lock = new Object();
                    final int[] done = {0};
                    List<Thread> ts = new ArrayList<>();
                    // simple fan-out with the shared pool via latches
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(list.size());
                    for (Universe.Sym s : list) {
                        pool.execute(() -> {
                            try {
                                List<Analysis.Trade> t = Analysis.orbBacktest(chart(s.yahoo), orMinutes, rMult, side);
                                synchronized (lock) { trades.addAll(t); }
                            } catch (Exception ignored) { }
                            synchronized (lock) {
                                done[0]++;
                                if (gen == runGen) {
                                    int d = done[0], n = list.size();
                                    runOnUiThread(() -> status.setText("Running backtest… " + d + "/" + n + " stocks"));
                                }
                            }
                            latch.countDown();
                        });
                    }
                    latch.await();
                } else {
                    trades.addAll(Analysis.orbBacktest(chart(sym.yahoo), orMinutes, rMult, side));
                }
                if (gen != runGen) return;
                trades.sort((a, b) -> Long.compare(a.breakTs, b.breakTs));
                final List<Analysis.Trade> ft = trades;
                runOnUiThread(() -> { if (!isFinishing()) render(ft); });
            } catch (Exception e) {
                if (gen == runGen) runOnUiThread(() -> {
                    if (!isFinishing()) status.setText("Could not load data - check connection and retry.");
                });
            }
        }).start();
    }

    private void render(List<Analysis.Trade> trades) {
        resultsBox.removeAllViews();
        String name = portfolio ? "ALL NIFTY 50 (combined)" : sym.name;
        status.setText(name + " · OR " + orMinutes + " · target " + rMult + "R · "
                + (side == 0 ? "both sides" : side == 1 ? "buy only" : "sell only") + " · 60 days");
        Analysis.Stats st = Analysis.summarize(trades);
        if (st.n == 0) {
            resultsBox.addView(Ui.tv(this, "No trades fired under these settings.", 14, Ui.DIM, false));
            return;
        }
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "Result"));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(Ui.stat(this, String.valueOf(st.n), "TRADES", Ui.TXT));
        row1.addView(Ui.stat(this, String.format("%.0f%%", st.winPct), "WIN RATE",
                st.winPct >= 50 ? Ui.GREEN : Ui.RED));
        row1.addView(Ui.stat(this, String.format("%.2f", st.pf), "PROFIT FACTOR",
                st.pf >= 1 ? Ui.GREEN : Ui.RED));
        card.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, Ui.dp(this, 10), 0, 0);
        row2.addView(Ui.stat(this, String.format("%+.2f%%", st.expectancy), "PER TRADE",
                st.expectancy >= 0 ? Ui.GREEN : Ui.RED));
        row2.addView(Ui.stat(this, String.format("%+.1f%%", st.total), "TOTAL RETURN",
                st.total >= 0 ? Ui.GREEN : Ui.RED));
        row2.addView(Ui.stat(this, String.format("%.1f%%", st.maxDd), "MAX DRAWDOWN", Ui.RED));
        card.addView(row2);
        EquityView eq = new EquityView(this);
        eq.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 130)));
        eq.setEquity(st.equity);
        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) eq.getLayoutParams();
        elp.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(eq);
        TextView eqCap = Ui.tv(this, "Equity curve (cumulative % per trade; pink shade = drawdown)", 10.5f, Ui.FAINT, false);
        eqCap.setGravity(Gravity.CENTER);
        card.addView(eqCap);
        resultsBox.addView(card);

        if (side == 0) {
            List<Analysis.Trade> buys = new ArrayList<>(), sells = new ArrayList<>();
            for (Analysis.Trade t : trades) (t.up ? buys : sells).add(t);
            resultsBox.addView(sideCard("BUY side (breakouts up)", buys));
            resultsBox.addView(sideCard("SELL side (breakdowns)", sells));
        }

        LinearLayout tc = Ui.card(this);
        tc.addView(Ui.sectionTitle(this, "Latest trades"));
        int from = Math.max(0, trades.size() - 15);
        for (int i = trades.size() - 1; i >= from; i--) {
            Analysis.Trade t = trades.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView d = Ui.mono(this, t.date.substring(5), 12, Ui.DIM, false);
            d.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 52), -2));
            r.addView(d);
            r.addView(Ui.pill(this, t.up ? "BUY" : "SELL",
                    t.up ? android.graphics.Color.rgb(16, 62, 44) : android.graphics.Color.rgb(66, 26, 30),
                    t.up ? Ui.GREEN : Ui.RED));
            TextView mid = Ui.mono(this, "  " + MainActivity.fmt(t.entry) + " → " + MainActivity.fmt(t.exit)
                    + "  " + t.reason, 12, Ui.DIM, false);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(mid);
            r.addView(Ui.mono(this, String.format("%+.2f%%", t.retPct), 12.5f,
                    t.retPct >= 0 ? Ui.GREEN : Ui.RED, true));
            r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            tc.addView(r);
        }
        resultsBox.addView(tc);

        TextView note = Ui.tv(this, "How to read this: fills at bar prints (not live bid/ask), 0.05% cost per trade, "
                + "and inside a bar the stop is checked before the target (conservative). "
                + "A red result is the app telling you the truth about its own rules.", 11.5f, Ui.FAINT, false);
        note.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));
        resultsBox.addView(note);
    }

    private LinearLayout sideCard(String title, List<Analysis.Trade> trades) {
        Analysis.Stats s = Analysis.summarize(trades);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, title));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (s.n == 0) {
            card.addView(Ui.tv(this, "No trades on this side.", 13, Ui.DIM, false));
            return card;
        }
        row.addView(Ui.stat(this, String.valueOf(s.n), "TRADES", Ui.TXT));
        row.addView(Ui.stat(this, String.format("%.0f%%", s.winPct), "WIN RATE",
                s.winPct >= 50 ? Ui.GREEN : Ui.RED));
        row.addView(Ui.stat(this, String.format("%.2f", s.pf), "PROFIT FACTOR",
                s.pf >= 1 ? Ui.GREEN : Ui.RED));
        row.addView(Ui.stat(this, String.format("%+.1f%%", s.total), "TOTAL",
                s.total >= 0 ? Ui.GREEN : Ui.RED));
        card.addView(row);
        return card;
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
