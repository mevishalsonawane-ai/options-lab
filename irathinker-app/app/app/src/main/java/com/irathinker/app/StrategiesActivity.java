package com.irathinker.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Strategies (v53, strangle section v54, FINNIFTY + Sensex
 *  mom60 sections v55): the home of the strategy watchers -
 *  BANKNIFTY mom60 (intraday), the FINNIFTY and Sensex mom60
 *  paper tracks (intraday), and the strangle paper track
 *  (positional) - their switches, last signals, and their paper
 *  logs. The logs are each strategy's own record: every alert
 *  the watcher fired, resolved automatically against the tape
 *  repriced through Black-Scholes. Nothing here places an order;
 *  IraThinker stays the support system. */
public class StrategiesActivity extends Activity {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private LinearLayout body;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
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
        body.addView(Ui.tv(this, "Strategies", 24, Ui.TXT, true));
        TextView sub = Ui.tv(this, "Resolving the open position against the latest bars…", 13, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        body.addView(sub);

        new Thread(() -> {
            Mom60Engine.check(this);
            Mom60IdxEngine.check(this);
            StrangleEngine.check(this);
            runOnUiThread(this::render);
        }).start();
    }

    private void render() {
        // keep the header (back + title), rebuild the rest - the
        // "Resolving…" line is transient, like the Journal's
        while (body.getChildCount() > 2) body.removeViewAt(2);
        List<Mom60Store.Rec> all = Mom60Store.load(this);

        renderCombinedBoard(all);

        // ---- the strategy card: switch + what it is ----
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "BANKNIFTY mom60 · intraday"));
        CheckBox cb = new CheckBox(this);
        cb.setText("mom60 alerts on — fire the human ntfy alert the minute a signal closes");
        cb.setTextColor(Ui.TXT);
        cb.setTextSize(13.5f);
        cb.setChecked(prefs.mom60On());
        cb.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        cb.setOnCheckedChangeListener((v, on) -> prefs.setMom60On(on));
        card.addView(cb);
        card.addView(Ui.tv(this, "When BANKNIFTY's trailing 60-minute move reaches ±0.50% (the study's "
                + "top-decile size), buy the ATM monthly option in the move's direction, hold "
                + "60 minutes, exit early if the premium falls 30%. One position at a time; "
                + "last entry 14:30. Alerts are paper-logged here and graded automatically. "
                + "Not posted to the System Feed.", 12.5f, Ui.DIM, false));
        card.addView(Ui.tv(this, Mom60.STUDY_LINE, 12, Ui.FAINT, false));
        body.addView(card);

        // ---- last signal status ----
        LinearLayout status = Ui.card(this);
        status.addView(Ui.sectionTitle(this, "Last signal"));
        if (all.isEmpty()) {
            status.addView(Ui.tv(this, "No mom60 signal yet. The watcher runs with the market "
                    + "(09:15–15:30); the first possible signal is about 10:16, once 61 minutes "
                    + "of tape exist.", 13, Ui.DIM, false));
        } else {
            Mom60Store.Rec last = all.get(all.size() - 1);
            status.addView(Ui.mono(this, lastLine(last), 13, Ui.TXT, false));
            if (!last.closed) {
                status.addView(Ui.tv(this, "Position open — exit by "
                        + hhmm(last.exitTs) + " IST, or earlier if the premium touches the stop.",
                        12.5f, Ui.ORANGE, false));
            }
        }
        body.addView(status);

        // ---- paper log summary ----
        int open = 0, closed = 0, wins = 0;
        double pnlSum = 0;
        for (Mom60Store.Rec r : all) {
            if (r.closed) { closed++; pnlSum += r.pnlPct; if (r.pnlPct > 0) wins++; }
            else open++;
        }
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.sectionTitle(this, "Paper log"));
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.stat(this, String.valueOf(open), "OPEN", Ui.TXT));
        r1.addView(Ui.stat(this, String.valueOf(closed), "CLOSED", Ui.TXT));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%.0f%%", wins * 100.0 / closed),
                "WIN RATE", closed == 0 ? Ui.DIM : (wins * 2 >= closed ? Ui.GREEN : Ui.RED)));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%+.1f%%", pnlSum / closed),
                "AVG P&L / TRADE", closed == 0 ? Ui.DIM : (pnlSum >= 0 ? Ui.GREEN : Ui.RED)));
        sum.addView(r1);
        sum.addView(Ui.tv(this, "P&L is premium % from Black-Scholes repricing of the spot path "
                + "(the app has no live BANKNIFTY chain) — it grades the rule, not real fills.",
                11.5f, Ui.FAINT, false));
        body.addView(sum);

        if (!all.isEmpty()) {
            for (int i = all.size() - 1; i >= 0; i--) {
                Mom60Store.Rec r = all.get(i);
                LinearLayout c = Ui.card(this);
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(Ui.pill(this, r.call ? "BUY CE" : "BUY PE",
                        r.call ? android.graphics.Color.rgb(16, 62, 44) : android.graphics.Color.rgb(66, 26, 30),
                        r.call ? Ui.GREEN : Ui.RED));
                TextView nm = Ui.tv(this, String.format(Locale.US, "  %.0f %s", r.strike, r.call ? "CE" : "PE"),
                        15, Ui.TXT, true);
                head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                head.addView(Ui.pill(this, r.closed ? r.exitReason : "OPEN",
                        Ui.CARD_ALT, r.closed ? Ui.DIM : Ui.ORANGE));
                c.addView(head);
                c.addView(Ui.mono(this, String.format(Locale.US,
                        "%s · entry %s · spot %s · move %+.2f%% · exp %s",
                        r.date, hhmm(r.entryTs), MainActivity.fmt(r.spot), r.ret60, r.expiry),
                        12, Ui.DIM, false));
                c.addView(Ui.mono(this, String.format(Locale.US,
                        "Premium ₹%.1f · stop ₹%.1f · IV ~%.0f%%",
                        r.entryPremium, r.stopPremium, r.iv), 12, Ui.DIM, false));
                if (r.closed) {
                    TextView res = Ui.mono(this, String.format(Locale.US, "Exit ₹%.1f · %+.2f%%",
                            r.exitPremium, r.pnlPct), 13, r.pnlPct >= 0 ? Ui.GREEN : Ui.RED, true);
                    res.setPadding(0, Ui.dp(this, 6), 0, 0);
                    c.addView(res);
                }
                body.addView(c);
            }
        }

        renderMom60Idx();
        renderStrangle();
    }

    // ---- v59: the combined mom60 board, above the per-index sections ----
    /** One card over all three mom60 paper logs: per-index
     *  totals side by side, plus the DTE-cell split per index -
     *  the split the family's DTE study made its most portable
     *  fact. Aggregated by the pure Mom60Board from the very
     *  records the sections below render; a record whose stored
     *  dates cannot yield a DTE counts in the totals but in no
     *  cell, and the card says so only when that fraction is
     *  material. */
    private void renderCombinedBoard(List<Mom60Store.Rec> bn) {
        Mom60Board.Idx b = Mom60Board.stats(Mom60.BN, bn);
        Mom60Board.Idx f = Mom60Board.stats(Mom60.FIN, Mom60Store.load(this, Mom60Store.FIN_FILE));
        Mom60Board.Idx x = Mom60Board.stats(Mom60.SENSEX, Mom60Store.load(this, Mom60Store.SENSEX_FILE));
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "mom60 — all indices (paper)"));
        for (Mom60Board.Idx ix : new Mom60Board.Idx[]{b, f, x}) {
            TextView head = Ui.tv(this, ix.name, 13.5f, Ui.TXT, true);
            head.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(head);
            if (ix.signals == 0) {
                card.addView(Ui.tv(this, "No signals yet.", 12.5f, Ui.DIM, false));
                continue;
            }
            card.addView(Ui.mono(this, String.format(Locale.US,
                    "%d signals · %d closed · %d open · win %s · avg %s · total %s (1 lot × %d)",
                    ix.signals, ix.closed, ix.open,
                    ix.closed == 0 ? "—" : String.format(Locale.US, "%.0f%%", ix.wins * 100.0 / ix.closed),
                    ix.closed == 0 ? "—" : String.format(Locale.US, "%+.1f%%/trade", ix.avgPct),
                    ix.closed == 0 ? "—" : String.format(Locale.US, "%s₹%,.0f",
                            ix.totalRs >= 0 ? "+" : "−", Math.abs(ix.totalRs)),
                    ix.lot), 12, Ui.DIM, false));
            if (ix.closed > 0) {
                StringBuilder sb = new StringBuilder("DTE cells (avg %/trade): ");
                for (int c = 0; c < 4; c++) {
                    if (c > 0) sb.append(" · ");
                    sb.append(Mom60Board.CELL_LABELS[c]).append(": ");
                    if (ix.cellN[c] == 0) sb.append("—");
                    else sb.append(String.format(Locale.US, "n=%d %+.1f%%", ix.cellN[c], ix.cellAvg[c]));
                }
                card.addView(Ui.mono(this, sb.toString(), 11.5f, Ui.FAINT, false));
            }
        }
        if (Mom60Board.unknownMaterial(b, f, x)) {
            int closed = b.closed + f.closed + x.closed, unk = b.cellUnknown + f.cellUnknown + x.cellUnknown;
            card.addView(Ui.tv(this, String.format(Locale.US,
                    "DTE split covers %d of %d closed trades — the rest are records whose stored "
                            + "dates cannot yield a DTE; they count in the totals only.",
                    closed - unk, closed), 11.5f, Ui.FAINT, false));
        }
        card.addView(Ui.tv(this, "Paper logs on a Black-Scholes tape — they grade the rule, not real fills.",
                11.5f, Ui.FAINT, false));
        body.addView(card);
    }

    // ---- v55: the FINNIFTY and Sensex mom60 paper tracks ----
    private void renderMom60Idx() {
        renderMom60IdxSection(Mom60.FIN, Mom60Store.load(this, Mom60Store.FIN_FILE),
                prefs.mom60FinOn(), on -> prefs.setMom60FinOn(on));
        renderMom60IdxSection(Mom60.SENSEX, Mom60Store.load(this, Mom60Store.SENSEX_FILE),
                prefs.mom60SensexOn(), on -> prefs.setMom60SensexOn(on));
    }

    /** A Prefs boolean setter, so each v55 section wires its own
     *  switch without a switch statement. */
    private interface BoolSetter { void set(boolean b); }

    /** One v55 index section, mirroring the BANKNIFTY mom60 layout
     *  above: switch card, last-signal status, paper-log summary
     *  and the newest-first record cards. The switch gates firing
     *  only - the engine keeps resolving an open position with it
     *  off, exactly as for BANKNIFTY. */
    private void renderMom60IdxSection(Mom60.Cfg cfg, List<Mom60Store.Rec> all,
                                       boolean on, BoolSetter setter) {
        // ---- the strategy card: switch + what it is ----
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, cfg.name + " mom60 · intraday (PAPER)"));
        CheckBox cb = new CheckBox(this);
        cb.setText("mom60 paper-track alerts on — fire the human ntfy alert the minute a signal closes");
        cb.setTextColor(Ui.TXT);
        cb.setTextSize(13.5f);
        cb.setChecked(on);
        cb.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        cb.setOnCheckedChangeListener((v, checked) -> setter.set(checked));
        card.addView(cb);
        card.addView(Ui.tv(this, String.format(Locale.US,
                "When %s's trailing 60-minute move reaches ±%.2f%% (the study's top-decile "
                        + "size), buy the ATM monthly option in the move's direction, hold "
                        + "60 minutes, exit early if the premium falls 30%%. One position at "
                        + "a time; last entry 14:30. Monthly expiry is the %s. Alerts are "
                        + "paper-logged here and graded automatically. Not posted to the "
                        + "System Feed.",
                cfg.name, cfg.thresholdPct, cfg.bseExpiry ? "last Thursday (BSE)" : "last Tuesday"),
                12.5f, Ui.DIM, false));
        card.addView(Ui.tv(this, cfg.studyLine, 12, Ui.FAINT, false));
        body.addView(card);

        // ---- last signal status ----
        LinearLayout status = Ui.card(this);
        status.addView(Ui.sectionTitle(this, "Last signal — " + cfg.name));
        if (all.isEmpty()) {
            status.addView(Ui.tv(this, "No " + cfg.name + " mom60 signal yet. The watcher runs "
                    + "with the market (09:15–15:30); the first possible signal is about 10:16, "
                    + "once 61 minutes of tape exist.", 13, Ui.DIM, false));
        } else {
            Mom60Store.Rec last = all.get(all.size() - 1);
            status.addView(Ui.mono(this, lastIdxLine(cfg, last), 13, Ui.TXT, false));
            if (!last.closed) {
                status.addView(Ui.tv(this, "Position open — exit by "
                        + hhmm(last.exitTs) + " IST, or earlier if the premium touches the stop.",
                        12.5f, Ui.ORANGE, false));
            }
        }
        body.addView(status);

        // ---- paper log summary ----
        int open = 0, closed = 0, wins = 0;
        double pnlSum = 0, rsSum = 0;
        for (Mom60Store.Rec r : all) {
            if (r.closed) {
                closed++; pnlSum += r.pnlPct; if (r.pnlPct > 0) wins++;
                rsSum += r.entryPremium * r.pnlPct / 100.0 * cfg.lot;
            } else open++;
        }
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.sectionTitle(this, cfg.name + " paper log"));
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.stat(this, String.valueOf(open), "OPEN", Ui.TXT));
        r1.addView(Ui.stat(this, String.valueOf(closed), "CLOSED", Ui.TXT));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%.0f%%", wins * 100.0 / closed),
                "WIN RATE", closed == 0 ? Ui.DIM : (wins * 2 >= closed ? Ui.GREEN : Ui.RED)));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%+.1f%%", pnlSum / closed),
                "AVG P&L / TRADE", closed == 0 ? Ui.DIM : (pnlSum >= 0 ? Ui.GREEN : Ui.RED)));
        sum.addView(r1);
        sum.addView(Ui.tv(this, String.format(Locale.US,
                "P&L is premium %% from Black-Scholes repricing of the spot path (the app has "
                        + "no live %s chain) — it grades the rule, not real fills. "
                        + "Total ₹ (paper, 1 lot × %d): %s.",
                cfg.name, cfg.lot, closed == 0 ? "—" : String.format(Locale.US, "%s₹%,.0f",
                        rsSum >= 0 ? "+" : "−", Math.abs(rsSum))),
                11.5f, Ui.FAINT, false));
        body.addView(sum);

        for (int i = all.size() - 1; i >= 0; i--) {
            Mom60Store.Rec r = all.get(i);
            LinearLayout c = Ui.card(this);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.pill(this, r.call ? "BUY CE" : "BUY PE",
                    r.call ? android.graphics.Color.rgb(16, 62, 44) : android.graphics.Color.rgb(66, 26, 30),
                    r.call ? Ui.GREEN : Ui.RED));
            TextView nm = Ui.tv(this, String.format(Locale.US, "  %.0f %s", r.strike, r.call ? "CE" : "PE"),
                    15, Ui.TXT, true);
            head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Ui.pill(this, r.closed ? r.exitReason : "OPEN",
                    Ui.CARD_ALT, r.closed ? Ui.DIM : Ui.ORANGE));
            c.addView(head);
            c.addView(Ui.mono(this, String.format(Locale.US,
                    "%s · %s · entry %s · spot %s · move %+.2f%% · exp %s",
                    cfg.name, r.date, hhmm(r.entryTs), MainActivity.fmt(r.spot), r.ret60, r.expiry),
                    12, Ui.DIM, false));
            c.addView(Ui.mono(this, String.format(Locale.US,
                    "Premium ₹%.1f · stop ₹%.1f · IV ~%.0f%%",
                    r.entryPremium, r.stopPremium, r.iv), 12, Ui.DIM, false));
            if (r.closed) {
                TextView res = Ui.mono(this, String.format(Locale.US, "Exit ₹%.1f · %+.2f%% · %s₹%,.0f (1 lot)",
                        r.exitPremium, r.pnlPct,
                        r.pnlPct >= 0 ? "+" : "−",
                        Math.abs(r.entryPremium * r.pnlPct / 100.0 * cfg.lot)),
                        13, r.pnlPct >= 0 ? Ui.GREEN : Ui.RED, true);
                res.setPadding(0, Ui.dp(this, 6), 0, 0);
                c.addView(res);
            }
            body.addView(c);
        }
    }

    private static String lastIdxLine(Mom60.Cfg cfg, Mom60Store.Rec r) {
        return String.format(Locale.US, "%s %s — %s %.0f %s @ ₹%.1f (%s)",
                r.date, hhmm(r.entryTs), cfg.name, r.strike, r.call ? "CE" : "PE", r.entryPremium,
                r.closed ? r.exitReason + String.format(Locale.US, " %+.2f%%", r.pnlPct) : "OPEN");
    }

    // ---- v54: the strangle paper track, below mom60 ----
    private void renderStrangle() {
        List<StrangleStore.Rec> all = StrangleStore.load(this);
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM");

        // ---- the strategy card: switch + what it is ----
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "BANKNIFTY short strangle · positional (PAPER)"));
        CheckBox cb = new CheckBox(this);
        cb.setText("strangle paper-track alerts on — one paper alert each Monday, just after the 09:30 bar");
        cb.setTextColor(Ui.TXT);
        cb.setTextSize(13.5f);
        cb.setChecked(prefs.strangleOn());
        cb.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        cb.setOnCheckedChangeListener((v, on) -> prefs.setStrangleOn(on));
        card.addView(cb);
        card.addView(Ui.tv(this, "Every Monday, paper-sell the monthly strangle — CE at ATM+200, "
                + "PE at ATM−200, strikes off the 09:30 bar — and grade it at Thursday's close; in an "
                + "expiry week it settles at Tuesday's expiry instead. This is a paper track of the "
                + "study's one REAL-CANDIDATE: the strategy needs ≈₹2.14L of margin, beyond the ₹1L "
                + "account, so nothing here is a live signal and nothing is posted to the System Feed.", 12.5f, Ui.DIM, false));
        card.addView(Ui.tv(this, Strangle.STUDY_LINE, 12, Ui.FAINT, false));
        body.addView(card);

        // ---- last entry status ----
        LinearLayout status = Ui.card(this);
        status.addView(Ui.sectionTitle(this, "Last strangle week"));
        if (all.isEmpty()) {
            status.addView(Ui.tv(this, "No strangle week yet. The first paper entry fires on a Monday "
                    + "between 09:31 and 09:45 — a late start skips the week rather than entering late.",
                    13, Ui.DIM, false));
        } else {
            StrangleStore.Rec last = all.get(all.size() - 1);
            status.addView(Ui.mono(this, lastStrangleLine(last, df), 13, Ui.TXT, false));
            if (!last.closed) {
                status.addView(Ui.tv(this, "Week open — exit "
                        + (last.expiryWeek ? "settles at expiry " : "at Thu close ")
                        + java.time.LocalDate.parse(last.exitDate).format(df) + ".",
                        12.5f, Ui.ORANGE, false));
            }
        }
        body.addView(status);

        // ---- paper log summary ----
        int open = 0, closed = 0, wins = 0;
        double pctSum = 0, rsSum = 0;
        for (StrangleStore.Rec r : all) {
            if (r.closed) { closed++; pctSum += r.netPct; rsSum += r.pnlRs; if (r.netPct > 0) wins++; }
            else open++;
        }
        LinearLayout sum = Ui.card(this);
        sum.addView(Ui.sectionTitle(this, "Strangle paper log"));
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.addView(Ui.stat(this, String.valueOf(closed), "WEEKS", Ui.TXT));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%.0f%%", wins * 100.0 / closed),
                "WIN RATE", closed == 0 ? Ui.DIM : (wins * 2 >= closed ? Ui.GREEN : Ui.RED)));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%+.1f%%", pctSum / closed),
                "AVG % OF PREMIUM", closed == 0 ? Ui.DIM : (pctSum >= 0 ? Ui.GREEN : Ui.RED)));
        r1.addView(Ui.stat(this, closed == 0 ? "—" : String.format(Locale.US, "%s₹%,.0f", rsSum >= 0 ? "+" : "−", Math.abs(rsSum)),
                "TOTAL ₹ (PAPER)", closed == 0 ? Ui.DIM : (rsSum >= 0 ? Ui.GREEN : Ui.RED)));
        sum.addView(r1);
        sum.addView(Ui.tv(this, "Open weeks: " + open + ". P&L is % of premium from Black-Scholes "
                + "repricing of the spot path, less the study's flat 1.0 cost (the app has no live "
                + "BANKNIFTY chain) — it grades the rule, not real fills. ₹ at 1 lot × 30.",
                11.5f, Ui.FAINT, false));
        body.addView(sum);

        for (int i = all.size() - 1; i >= 0; i--) {
            StrangleStore.Rec r = all.get(i);
            LinearLayout c = Ui.card(this);
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.pill(this, "PAPER", android.graphics.Color.rgb(62, 52, 16), Ui.ORANGE));
            TextView nm = Ui.tv(this, String.format(Locale.US, "  SELL %.0f CE / %.0f PE", r.ceStrike, r.peStrike),
                    15, Ui.TXT, true);
            head.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Ui.pill(this, r.closed ? r.exitReason : "OPEN",
                    Ui.CARD_ALT, r.closed ? Ui.DIM : Ui.ORANGE));
            c.addView(head);
            c.addView(Ui.mono(this, String.format(Locale.US,
                    "Week of %s · spot %s · expiry %s · exit %s",
                    java.time.LocalDate.parse(r.week).format(df), MainActivity.fmt(r.spot),
                    java.time.LocalDate.parse(r.expiry).format(df),
                    java.time.LocalDate.parse(r.exitDate).format(df)),
                    12, Ui.DIM, false));
            c.addView(Ui.mono(this, String.format(Locale.US,
                    "Credit %.1f pts (₹%,.0f) · IV ~%.0f%% · breakevens %s – %s",
                    r.credit, r.credit * Strangle.LOT, r.iv,
                    MainActivity.fmt(r.peStrike - r.credit), MainActivity.fmt(r.ceStrike + r.credit)),
                    12, Ui.DIM, false));
            if (r.closed) {
                TextView res = Ui.mono(this, String.format(Locale.US,
                        "Exit spot %s · buy-back %.1f pts · net %+.2f%% of premium · %s₹%,.0f",
                        MainActivity.fmt(r.exitSpot), r.exitCost, r.netPct,
                        r.pnlRs >= 0 ? "+" : "−", Math.abs(r.pnlRs)),
                        13, r.netPct >= 0 ? Ui.GREEN : Ui.RED, true);
                res.setPadding(0, Ui.dp(this, 6), 0, 0);
                c.addView(res);
            }
            body.addView(c);
        }
    }

    private static String lastStrangleLine(StrangleStore.Rec r, java.time.format.DateTimeFormatter df) {
        return String.format(Locale.US, "Mon %s — SELL %.0f CE / %.0f PE @ %.1f pts (%s)",
                java.time.LocalDate.parse(r.week).format(df), r.ceStrike, r.peStrike, r.credit,
                r.closed ? r.exitReason + String.format(Locale.US, " %+.2f%% · %s₹%,.0f",
                        r.netPct, r.pnlRs >= 0 ? "+" : "−", Math.abs(r.pnlRs)) : "OPEN");
    }

    private static String hhmm(long epochSec) {
        return DateTimeFormatter.ofPattern("HH:mm").withZone(IST)
                .format(java.time.Instant.ofEpochSecond(epochSec));
    }

    private static String lastLine(Mom60Store.Rec r) {
        return String.format(Locale.US, "%s %s — BANKNIFTY %.0f %s @ ₹%.1f (%s)",
                r.date, hhmm(r.entryTs), r.strike, r.call ? "CE" : "PE", r.entryPremium,
                r.closed ? r.exitReason + String.format(Locale.US, " %+.2f%%", r.pnlPct) : "OPEN");
    }
}
