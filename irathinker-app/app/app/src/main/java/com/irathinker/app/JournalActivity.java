package com.irathinker.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Paper journal: every plan the user chose to track, resolved
 *  against real bars, with paper P&L in rupees. Practice with the
 *  market's real tape - and a record that cannot be edited after
 *  the fact. */
public class JournalActivity extends Activity {

    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        body.addView(Ui.tv(this, "Paper Journal", 24, Ui.TXT, true));
        TextView exportBtn = Ui.pill(this, "EXPORT / SHARE RESULTS", Ui.CARD_ALT, Ui.ORANGE);
        exportBtn.setGravity(Gravity.CENTER);
        exportBtn.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.setMargins(0, Ui.dp(this, 8), 0, 0);
        exportBtn.setLayoutParams(elp);
        exportBtn.setOnClickListener(v -> exportResults());
        body.addView(exportBtn);
        TextView sub = Ui.tv(this, "Resolving open trades against the latest bars…", 13, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        body.addView(sub);

        new Thread(() -> {
            PaperStore.resolveAll(this);
            runOnUiThread(this::render);
        }).start();
    }

    private void render() {
        // keep the header (first 3 children), rebuild the rest
        while (body.getChildCount() > 3) body.removeViewAt(3);
        List<PaperStore.Trade> trades = PaperStore.load(this);
        int open = 0, closed = 0, wins = 0;
        double pnl = 0;
        for (PaperStore.Trade t : trades) {
            if (t.closed) { closed++; pnl += t.pnlRs(); if (t.pnlRs() > 0) wins++; }
            else open++;
        }
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.sectionTitle(this, "Paper track record"));
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.stat(this, String.valueOf(open), "OPEN", Ui.TXT));
        r1.addView(Ui.stat(this, String.valueOf(closed), "CLOSED", Ui.TXT));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format("%.0f%%", wins * 100.0 / closed),
                "WIN RATE", closed == 0 ? Ui.DIM : (wins * 2 >= closed ? Ui.GREEN : Ui.RED)));
        r1.addView(Ui.stat(this, (pnl >= 0 ? "+" : "−") + "₹" + String.format("%,.0f", Math.abs(pnl)),
                "PAPER P&L", pnl >= 0 ? Ui.GREEN : Ui.RED));
        sum.addView(r1);
        body.addView(sum);

        if (trades.isEmpty()) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.tv(this, "No paper trades yet.\n\nOpen any stock from the board, and tap "
                    + "\"Paper trade this plan\" - the journal will track it against real prices "
                    + "and grade the result for you.", 13.5f, Ui.DIM, false));
            body.addView(c);
            return;
        }
        for (int i = trades.size() - 1; i >= 0; i--) {
            PaperStore.Trade t = trades.get(i);
            LinearLayout card = Ui.card(this);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.pill(this, t.up ? "BUY" : "BUY PUT",
                    t.up ? android.graphics.Color.rgb(16, 62, 44) : android.graphics.Color.rgb(66, 26, 30),
                    t.up ? Ui.GREEN : Ui.RED));
            TextView nm = Ui.tv(this, "  " + t.name, 15, Ui.TXT, true);
            head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Ui.pill(this, t.closed ? t.exitReason : "OPEN",
                    Ui.CARD_ALT, t.closed ? Ui.DIM : Ui.ORANGE));
            card.addView(head);
            card.addView(Ui.mono(this, String.format("E %s · SL %s · T %s · qty %d · %s",
                    MainActivity.fmt(t.entry), MainActivity.fmt(t.stop), MainActivity.fmt(t.target),
                    t.qty, t.date), 12, Ui.DIM, false));
            if (t.closed) {
                TextView res = Ui.mono(this, String.format("Exit %s · %+.2f%% · %s₹%,.0f",
                        MainActivity.fmt(t.exit), t.retPct(),
                        t.pnlRs() >= 0 ? "+" : "−", Math.abs(t.pnlRs())), 13,
                        t.pnlRs() >= 0 ? Ui.GREEN : Ui.RED, true);
                res.setPadding(0, Ui.dp(this, 6), 0, 0);
                card.addView(res);
            } else {
                TextView close = Ui.pill(this, "CLOSE NOW AT MARKET", Ui.CARD_ALT, Ui.TXT);
                close.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
                close.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
                clp.setMargins(0, Ui.dp(this, 8), 0, 0);
                close.setLayoutParams(clp);
                final long tid = t.id;
                close.setOnClickListener(v -> closeNow(tid));
                card.addView(close);
            }
            body.addView(card);
        }
    }

    /** Share the journal + the live signal track record as text.
     *  The user picks the destination (Drive, mail, chat) - nothing
     *  leaves the phone until they tap this and choose. */
    private void exportResults() {
        List<PaperStore.Trade> trades = PaperStore.load(this);
        Prefs prefs = new Prefs(this);
        int[] oc = prefs.outcomeCounts();
        StringBuilder sb = new StringBuilder();
        sb.append("IraThinker results export\n");
        sb.append("Signal track record (ntfy alerts): ")
                .append(oc[0]).append(" targets, ").append(oc[1]).append(" stops, ")
                .append(oc[2]).append(" flat, ").append(prefs.openSignals().size()).append(" open\n\n");
        sb.append("Paper trades:\n");
        if (trades.isEmpty()) sb.append("(none yet)\n");
        double pnl = 0;
        int closed = 0, wins = 0;
        for (PaperStore.Trade t : trades) {
            sb.append(t.date).append("  ").append(t.up ? "BUY    " : "BUY PUT")
                    .append("  ").append(t.name)
                    .append("  E ").append(MainActivity.fmt(t.entry))
                    .append("  SL ").append(MainActivity.fmt(t.stop))
                    .append("  T ").append(MainActivity.fmt(t.target))
                    .append("  qty ").append(t.qty);
            if (t.closed) {
                closed++;
                pnl += t.pnlRs();
                if (t.pnlRs() > 0) wins++;
                sb.append("  -> ").append(t.exitReason).append(" @ ").append(MainActivity.fmt(t.exit))
                        .append(String.format("  %+.2f%%  Rs %,.0f", t.retPct(), t.pnlRs()));
            } else {
                sb.append("  -> OPEN");
            }
            sb.append("\n");
        }
        if (closed > 0) {
            sb.append(String.format("\nClosed: %d · win rate %.0f%% · paper P&L Rs %,.0f\n",
                    closed, wins * 100.0 / closed, pnl));
        }
        android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(android.content.Intent.EXTRA_SUBJECT, "IraThinker results");
        share.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        startActivity(android.content.Intent.createChooser(share, "Share results via"));
    }

    private void closeNow(long tradeId) {
        new Thread(() -> {
            List<PaperStore.Trade> all = PaperStore.load(this);
            for (PaperStore.Trade t : all) {
                if (t.id != tradeId || t.closed) continue;
                try {
                    YahooClient.Chart intra = YahooClient.intradayShort(t.yahoo);
                    double px = Double.NaN;
                    for (int i = intra.close.length - 1; i >= 0; i--) {
                        if (!Double.isNaN(intra.close[i])) { px = intra.close[i]; break; }
                    }
                    if (!Double.isNaN(px)) {
                        t.closed = true; t.exit = px; t.exitReason = "MANUAL";
                        PaperStore.save(this, all);
                    }
                } catch (Exception ignored) { }
            }
            runOnUiThread(() -> {
                Toast.makeText(this, "Closed", Toast.LENGTH_SHORT).show();
                render();
            });
        }).start();
    }
}
