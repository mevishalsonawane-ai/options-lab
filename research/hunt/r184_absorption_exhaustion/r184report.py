"""Renders results/report.md and results/summary.md from results/selection.json and results/holdout.json.
python research/hunt/r184_absorption_exhaustion/r184report.py. Plain-English notes, 16 Nov 2026."""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
CELLS = ("ABS NIFTY", "ABS BANKNIFTY", "EXH NIFTY", "EXH BANKNIFTY")
LONG = {"ABS": "Absorption", "EXH": "Exhaustion"}
EXITS = ("time", "day_end", "index_stop", "prem_stop", "breakeven")


def rs(x):
    if x is None:
        return "-"
    return f"Rs -{abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def pct(x):
    return "-" if x is None else f"{x:.0%}"


def name(cell):
    s, u = cell.split()
    return f"{LONG[s]} {u}"


def holdout_table(h):
    out = ["| setup, index | chosen version | trades | win | net (2x slip) | a trade | random twins, a trade | twin p | BH q | "
           "verdict |", "|---|---|---|---|---|---|---|---|---|---|"]
    for k in CELLS:
        c = h["cells"][k]
        S = c["holdout"]
        out.append(f"| {name(k)} | {c['vid']} | {S['n']} | {pct(S['win'])} | {rs(S['net'])} ({rs(S['net2'])}) | "
                   f"{rs(S['mean'])} | {rs(c['twins']['mean'])} | {c['p_twin']:.2f} | {c['q_twin']:.2f} | {c['verdict']} |")
    return "\n".join(out)


def design_table(sel):
    out = ["| cell | versions with >= 12 trades | versions with net > 0 | median version net | chosen | trades | win | net | "
           "a trade | twins p (descriptive, after selection) |", "|---|---|---|---|---|---|---|---|---|---|"]
    for k in CELLS:
        c = sel["cells"][k]
        v = c["variants"]
        nets = sorted(x["net"] for x in v)
        med = (nets[15] + nets[16]) / 2
        ch = c["choice"]
        S = ch["summary"]
        out.append(f"| {name(k)} | {sum(x['n'] >= 12 for x in v)} / 32 | {sum(x['net'] > 0 for x in v)} / 32 | {rs(med)} | "
                   f"{ch['vid']} | {S['n']} | {pct(S['win'])} | {rs(S['net'])} | {rs(S['mean'])} | "
                   f"{ch['twins_descriptive']['p']:.2f} |")
    return "\n".join(out)


def detail_table(h):
    out = ["| cell | signals | trades | win | net | 2x slip | bid/ask | avg win | avg loss | worst day | best day | calls (n, net) "
           "| puts (n, net) | exits (n, net) |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for k in CELLS:
        c = h["cells"][k]
        S = c["holdout"]
        sd = S["by_side"]
        ex = ", ".join(f"{e} {S['by_exit'][e]['n']} ({rs(S['by_exit'][e]['net'])})" for e in EXITS if e in S["by_exit"])
        out.append(f"| {k} | {c['signals']} | {S['n']} | {pct(S['win'])} | {rs(S['net'])} | {rs(S['net2'])} | "
                   f"{rs(S['net_ba'])} | {rs(S['avg_win'])} | {rs(S['avg_loss'])} | {rs(S['worst_day'])} | "
                   f"{rs(S['best_day'])} | {sd['L']['n']}, {rs(sd['L']['net'])} | {sd['S']['n']}, {rs(sd['S']['net'])} | {ex} |")
    return "\n".join(out)


def tests_table(h):
    out = ["| test | p | BH q (m 4,512) | q within R184's 8 |", "|---|---|---|---|"]
    for t in h["tests"]:
        lab = t["id"].replace("T1 twins", "vs random twins (T1)").replace("T2 zero", "mean > 0, day bootstrap (T2)")
        out.append(f"| {lab} | {t['p']:.3f} | {t['q_all']:.2f} | {t['q_within']:.2f} |")
    return "\n".join(out)


def facts(sel, h):
    C = h["cells"]
    abs_ = [C["ABS NIFTY"], C["ABS BANKNIFTY"]]
    exh = [C["EXH NIFTY"], C["EXH BANKNIFTY"]]
    hold_abs_sides = {s: (sum(c["holdout"]["by_side"][s]["n"] for c in abs_), sum(c["holdout"]["by_side"][s]["net"] for c in abs_))
                      for s in ("L", "S")}
    d_abs = [sel["cells"][k]["choice"]["summary"] for k in ("ABS NIFTY", "ABS BANKNIFTY")]
    mde = [c["power"]["mde_5pct"] for c in C.values()]
    mdeb = [c["power"]["mde_bh"] for c in C.values()]
    sds = [c["holdout"]["sd"] for c in C.values()]
    ns = [c["holdout"]["n"] for c in C.values()]
    ov = h.get("overlap", {})
    return dict(abs_sides=hold_abs_sides, d_abs_n=sum(s["n"] for s in d_abs), d_abs_net=sum(s["net"] for s in d_abs),
                mde=(min(mde), max(mde)), mdeb=(min(mdeb), max(mdeb)), sd=(min(sds), max(sds)), n=(min(ns), max(ns)),
                best_mean=max(c["holdout"]["mean"] for c in C.values()), ov=ov)


def render(sel, h):
    P = h["pooled"]
    F = facts(sel, h)
    ov = F["ov"]
    exh_trades = sum(ov[k]["trades_in_arm_days"] for k in ("EXH NIFTY", "EXH BANKNIFTY")) if ov else 0
    exh_opp = sum(ov[k]["opposite_within_15m"] for k in ("EXH NIFTY", "EXH BANKNIFTY")) if ov else 0
    exh_same = sum(ov[k]["same_direction_within_15m"] for k in ("EXH NIFTY", "EXH BANKNIFTY")) if ov else 0
    abs_trades = sum(ov[k]["trades_in_arm_days"] for k in ("ABS NIFTY", "ABS BANKNIFTY")) if ov else 0
    abs_same = sum(ov[k]["same_direction_within_15m"] for k in ("ABS NIFTY", "ABS BANKNIFTY")) if ov else 0
    abs_opp = sum(ov[k]["opposite_within_15m"] for k in ("ABS NIFTY", "ABS BANKNIFTY")) if ov else 0
    by_arm = {}
    for k in ("ABS NIFTY", "ABS BANKNIFTY"):
        for a, n in (ov.get(k, {}).get("same_by_arm") or {}).items():
            by_arm[a] = by_arm.get(a, 0) + n
    arms_n = ov.get("_arms", {}).get("arm_trades_nifty_banknifty")
    cvar = {k: sel["cells"][k] for k in CELLS}
    med = {k: sorted(x["net"] for x in cvar[k]["variants"]) for k in CELLS}
    med = {k: (v[15] + v[16]) / 2 for k, v in med.items()}
    npos = {k: sum(x["net"] > 0 for x in cvar[k]["variants"]) for k in CELLS}
    q = h["cells"]
    tw = "; ".join(f"{k} {rs(q[k]['twins']['mean'])} ({rs(q[k]['twins']['p10'])} .. {rs(q[k]['twins']['p90'])})" for k in CELLS)
    pw = "; ".join(f"{k} {rs(q[k]['power']['mde_5pct'])} (5%) / {rs(q[k]['power']['mde_bh'])} (BH level), n {q[k]['holdout']['n']}, "
                   f"sd {rs(q[k]['holdout']['sd'])}" for k in CELLS)
    qual = h["quality"]
    fut = ", ".join(f"{d[5:]} {qual[d]['NIFTY']['fut_minutes']}" for d in h["holdout_days"])
    thin = max(qual[d]["NIFTY"]["thin"] for d in h["holdout_days"])
    worst = min(q[k]["holdout"]["worst_day"] for k in CELLS)
    sm = F["abs_sides"]
    return f"""# R184: absorption and exhaustion as option-buying rules (NIFTY / BANKNIFTY, phone recordings)

*16 Nov 2026. Research only; nothing in the app changes. Pre-registered (PREREG.md, commit c0ba6fc44, pushed before any
signal or rupee was computed). Design on 13 days, selection committed (db1edde53), read flag committed (18dcb4566), then
the 7-day holdout read once. Per-trade rows are outside git (`~/.cache/iraalgo/r184/`).*

Boss, 16 Nov 2026: "Now check absorption and exhaustion technique in options buying."

## For Boss, in plain words

**What was tested.** The two classic order-flow reversal setups, written as exact rules on the index FUTURE's own price
and order flow (signal on the underlying), each trading 1 lot of the ATM option:

- **Absorption.** Over the last 3 or 5 minutes aggressive selling is heavy (the futures delta is 2x or 3x the day's own
  normal for such a window) but price does not fall (it stays within 3 or 6 basis points, about 7-14 NIFTY points, of
  where the window began): someone is soaking up the selling -> **buy a CALL**. The mirror (heavy buying, price will not
  rise) -> **buy a PUT**. Optionally wait for the next minute to close in the trade's direction ("turn").
- **Exhaustion.** A push makes a new 15- or 30-minute low on a spike of selling (2x or 3x the day's normal minute delta);
  in the next 1-3 minutes the selling dries up (less than half the spike), price stops falling (no new low beyond the
  band) and delta flips to buying -> **buy a CALL**. The mirror at a new high -> **buy a PUT**. Optionally also need the
  close back above the climax minute's close ("flip+close").
- **Exits, the same for all:** -15% on the premium, stop to breakeven (fill + charges) once the option is +10%, an index
  stop just beyond the absorption / climax extreme, a time stop of 15 or 30 minutes, 15:15 (15:05 on expiry).
- Real dated charges and the repo's liquidity slippage; also 2x slippage and the recorded bid / ask.
- 32 versions per setup (window, heaviness, band, confirmation, time stop). The best version per setup and index was
  chosen on the first 13 days (15 Oct - 3 Nov), then read ONCE on the last 7 days (4 - 13 Nov).

**What happened on the 7 locked days (the only numbers that count):**

{holdout_table(h)}

(1 lot; rupees summed over the 7 days; twins = the same number of entries at the same minutes of the day, same side and
same exits, on other days of the same period, 1,000 sets.)

**What it means.**
- **Exhaustion: no.** It lost on both indices on the locked days ({P['EXH']['n']} trades, {rs(P['EXH']['net'])},
  {pct(P['EXH']['win'])} won) and was no better than random entries at the same minutes (p {q['EXH NIFTY']['p_twin']:.2f}
  and {q['EXH BANKNIFTY']['p_twin']:.2f}). On the design days the best of 32 versions had made only
  {rs(cvar['EXH NIFTY']['choice']['summary']['mean'])} and {rs(cvar['EXH BANKNIFTY']['choice']['summary']['mean'])} a
  trade, and most versions lost there (median version {rs(med['EXH NIFTY'])} NIFTY, {rs(med['EXH BANKNIFTY'])} BANKNIFTY).
- **Absorption: positive, but far too few trades to mean anything.** {P['ABS']['n']} trades in the locked week made
  {rs(P['ABS']['net'])} ({rs(P['ABS']['mean'])} a trade), after {rs(F['d_abs_net'])} on {F['d_abs_n']} design trades. But
  it did not beat random entries clearly (p {q['ABS NIFTY']['p_twin']:.2f} and {q['ABS BANKNIFTY']['p_twin']:.2f}); the
  chosen versions were the lucky tail of the design grid (only {npos['ABS NIFTY']} of 32 NIFTY and {npos['ABS BANKNIFTY']}
  of 32 BANKNIFTY versions made money on the design days; the median version lost {rs(-med['ABS NIFTY'])} and
  {rs(-med['ABS BANKNIFTY'])}); and the money came from CALLS in a strong rally (locked week: calls {sm['L'][0]} trades
  {rs(sm['L'][1])}, puts {sm['S'][0]} trades {rs(sm['S'][1])}). The pre-registered bar needs at least 10 locked trades per
  index: neither had them ({q['ABS NIFTY']['holdout']['n']} and {q['ABS BANKNIFTY']['holdout']['n']}).
- **How big an edge could 20 days see?** With {F['n'][0]}-{F['n'][1]} locked trades per cell and a spread of about
  {rs(F['sd'][0])}-{rs(F['sd'][1])} a trade, an edge would need to be **{rs(F['mde'][0])}-{rs(F['mde'][1])} a trade** to
  show at the usual 5% level with 80% power, and {rs(F['mdeb'][0])}-{rs(F['mdeb'][1])} at the multiple-testing level. The
  best observed mean was {rs(F['best_mean'])}. This sample can only catch a huge edge; it cannot rule out a modest one.
- **Win rates are low (21-50%)**: these are small-stop, let-it-run trades. The money is made by the 30-minute time exits
  and lost at the index stop.
- **Nothing passes** (all 8 BH q = 1.0 with m = {h['m_total']:,}). Nothing goes to paper or live. Absorption could be
  re-checked when a few more months of recordings exist, with the same frozen rules (a forward test, no new tuning).

**Overlap with the arms (descriptive, 21 Oct - 13 Nov, {arms_n} arm paper trades on NIFTY / BANKNIFTY).** Exhaustion fired
in the same direction as an R51 / R72 / Liquidity entry within 15 minutes {exh_same} times in {exh_trades} trades on arm
days and the opposite way {exh_opp} times: it is a counter-trend idea against breakout arms. Absorption fired in the
arms' direction {abs_same} of {abs_trades} times ({', '.join(f'{a} {n}' for a, n in sorted(by_arm.items()))}) and against
them {abs_opp} times. Neither duplicates an arm.

## Exact rules (signal source: the near index future; the option only prices the trade)

From PREREG.md sections 2-4. Minute bars from the future's 1-second rows; delta D = buy_vol - sell_vol (the app's tick
rule). A bar with fewer than 20 recorded seconds is "thin" and cannot be part of a signal. References are the same day's
earlier minutes only (at least 20): ref1 = mean |D| of earlier minutes; ref_w = mean |w-minute delta| of earlier windows.
Signals 09:40 - 14:44; the option is bought at the start of the next minute.

- ABSORPTION (w, k, beta, confirm). CALL when the w-minute delta <= -k x ref_w and the window's low >= the close before
  the window - beta bp; PUT is the mirror. "turn": the next minute must close up (CALL) / down (PUT); the signal is then
  that minute. Index stop: the window's extreme -/+ beta bp. Grid w {{3, 5}}, k {{2, 3}}, beta {{3, 6}} bp, confirm
  {{none, turn}}.
- EXHAUSTION (X, k, beta, confirm). Climax c: a new X-minute low with D[c] <= -k x ref1. Signal: the first minute r in
  c+1..c+3 where every minute after c sold less than half of D[c], no low went beyond L[c] - beta bp, and D[r] > 0
  ("flip+close": also C[r] > C[c]); a broken condition kills the climax. PUT is the mirror at a new high. Index stop:
  the extreme -/+ beta bp. Grid X {{15, 30}}, k {{2, 3}}, beta {{3, 6}} bp, confirm {{flip, flip+close}}.
- Both: time stop T {{15, 30}} minutes; one trade at a time, at most 4 a day per index. ATM strike from the index at the
  signal minute (recorded strike within 2 steps otherwise). Fill = option `last` + liquidity slippage.

## Design (13 days, 15 Oct - 3 Nov): every version, and the choice

{design_table(sel)}

Every chosen version uses the 30-minute time stop. Absorption's choices both use the wide band (6 bp) and the "turn"
confirmation, which also makes them rare. The design twin p values are flattered by choosing the best of 32 and are not
tests. All 128 versions are in `results/selection.json`.

## Holdout (7 days, 4 - 13 Nov), detail

{detail_table(h)}

Signals not traded were blocked by an open trade or the 4-a-day cap; none was unpriced in the holdout (design: a few
unpriced for no index price, mostly 15 Oct, when the NIFTY index row starts at 10:30).

**Tests (m = {h['m_total']:,} = 4,376 after R183 + 128 design versions + 8 tests):**

{tests_table(h)}

Twin sets' mean net a trade (10th .. 90th percentile): {tw}. Random entries at the same minutes, side mix and exits in
the locked week lost on 3 of 4 cells; the NIFTY absorption twins made money too (mostly calls in the rally).

**Power** (smallest mean edge a trade detectable with 80% power; sd = the holdout trades'): {pw}.

**Pooled (descriptive):** absorption {P['ABS']['n']} trades, {pct(P['ABS']['win'])} won, {rs(P['ABS']['net'])} (2x
{rs(P['ABS']['net2'])}); exhaustion {P['EXH']['n']} trades, {pct(P['EXH']['win'])} won, {rs(P['EXH']['net'])} (2x
{rs(P['EXH']['net2'])}).

## Data and caveats

- **The recorder may hide absorption.** The 1-second file the app keeps drops big prints that did not move the price
  (R182: 17-35% of futures volume) - exactly the "heavy volume, no price move" absorption reads. R184's absorption is
  absorption of the volume the recorder KEPT. The minute recorder (complete volume, the better delta in R182) stops on
  28 Oct, so it could not cover a design / holdout split.
- **Delta is an estimate** (tick rule; Zerodha gives no aggressor flag) and tracks price only loosely (R182: +0.27).
- **Missing data in the holdout:** NIFTY futures minutes recorded of 375: {fut}; thin minutes up to {thin} a day. Thin
  minutes cannot be part of a signal, so these days give fewer signals.
- **One regime.** The locked week was mostly a one-way rally; the calls / puts split shows it. 7 days cannot separate
  "the rule" from "the week".
- R181's loader kept one recorded role per option strike (CE or REC_CE, the same token); R184 merges both. Noted for any
  later reuse of that loader.
- 1 lot throughout; the worst holdout day was {rs(worst)} (inside the -Rs 13k month / -Rs 40k drawdown account rule).

## How it was run

- `r184.py --stage design` (the code refuses any other day split) -> `results/selection.json`, committed before the read
  flag.
- `results/FINAL_READ` ({h['final_read_flag']}) committed, then `r184.py --stage holdout` once at {h['read_at'][:19]} UTC
  -> `results/holdout.json` (records PREREG's and the selection's SHA-256) and `results/decision.json`.
- Tests: `python -m pytest research/hunt/r184_absorption_exhaustion -q` (30 tests, including the look-ahead guard: every
  signal at minute r is unchanged when all later minutes are replaced by random values, for all 32 signal settings).
- No amendment to PREREG.md.
"""


def render_summary(sel, h):
    P = h["pooled"]
    q = h["cells"]
    return f"""# R184 summary: absorption and exhaustion in options buying (16 Nov 2026)

Signal source: the NIFTY / BANKNIFTY near index future's own 1-minute price and tick-rule delta (the underlying); trade:
1 lot of the recorded ATM option; exits -15% premium, breakeven at +10%, index stop beyond the extreme, 15 / 30-minute
time stop. Design 15 Oct - 3 Nov (13 days, 32 versions per setup); holdout 4 - 13 Nov (7 days) read once.

{holdout_table(h)}

- Exhaustion lost on the locked days ({P['EXH']['n']} trades, {rs(P['EXH']['net'])}) and was no better than random
  entries at the same minutes: **rejected**.
- Absorption made {rs(P['ABS']['net'])} on {P['ABS']['n']} locked trades but did not beat random entries clearly (p
  {q['ABS NIFTY']['p_twin']:.2f} / {q['ABS BANKNIFTY']['p_twin']:.2f}), had fewer than the 10 trades per index the bar
  needs, and rode calls in a rally: **inconclusive, too few trades**.
- All 8 tests BH q = 1.0 (m = {h['m_total']:,}). 20 days can only detect an edge of roughly Rs 850-1,950 a trade.
- Exhaustion runs against the breakout arms (never the same direction as an arm entry within 15 minutes).
- Nothing in the app changes. Re-check absorption with the same frozen rules when months more of recordings exist.

Full write-up: `results/report.md`.
"""


def main():
    sel = json.loads((RES / "selection.json").read_text())
    h = json.loads((RES / "holdout.json").read_text())
    (RES / "report.md").write_text(render(sel, h))
    (RES / "summary.md").write_text(render_summary(sel, h))


if __name__ == "__main__":
    main()
