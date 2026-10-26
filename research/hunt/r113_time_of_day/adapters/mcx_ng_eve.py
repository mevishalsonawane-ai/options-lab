"""R113 adapter (26 Oct 2026): the MCX paper arm "NG evening breakout" (app McxEveRules = M3's EVE rule on NATURALGAS
options; research/MCX_INTRADAY.md, research/hunt/m3/run.py amendment 1 + m3lib.py, printed minutes = amendment 2).

Source of trades and rupees: M3's own committed per-trade lists research/hunt/m3/results/trades_{design,holdout}.csv.gz
(rule EVE, und NATURALGAS, inst OPT; net = M3 analyze.py's Zerodha charges + spread, 1 lot of 1,250 mmBtu).
Those rows carry the signal minute index `e`, the signal minute `tod` and the hold in grid minutes, but not the fill or
exit clock time. So this adapter re-runs M3's own EVE signal + sim_option code (unchanged, imported) on the lake's Dhan
rolling-option minutes (rebuilt in M3's raw layout by _mcx_raw.py), checks each re-run trade against the committed row
(same e, side, strike, fill, exit, hold, why) and reads the fill minute and exit minute off the minute grid.
Signal source: the NATURALGAS future (Dhan's `spot` on the option rows) = the underlying; the option is only traded.
usage: python3 -I research/hunt/r113_time_of_day/adapters/mcx_ng_eve.py"""
import json, os, sys
from pathlib import Path
import numpy as np, pandas as pd

REPO = Path("/home/user/options-lab")
sys.path.insert(0, str(REPO / "research/hunt/m3")); sys.path.insert(0, str(Path(__file__).parent))
os.environ.setdefault("M3_PRINTS", "1")
import _mcx_raw
import m3lib as M

OUT = Path(os.path.expanduser("~/.cache/iraalgo/r113/trades"))
SYM = "NATURALGAS"


def main():
    M.RAW = _mcx_raw.build(SYM)
    m = M.Market(SYM)
    days = [pd.Timestamp(d) for d in m.days]
    di = pd.Series(np.arange(len(m.ts))).groupby(m.day)
    first_last = {d: (ix.values[0], ix.values[-1]) for d, ix in di}
    # run.py, amendment 1 (verbatim logic): EVE windows and the one-at-a-time book (run_book)
    win = {d.to_datetime64(): (17 * 60, 19 * 60, 22 * 60) for d in days}
    sigs = [dict(x, cut_tod=23 * 60 + 15) for x in M.range_break_signals(m, win, "EVE", 120)]
    rows, busy_until = [], -1
    for s in sorted(sigs, key=lambda s: s["e"]):
        if s["e"] <= busy_until:
            continue
        d = m.day[s["e"]]
        if m.is_exp[s["e"]]:
            continue
        cut = m.day_cut[d]
        a_, b_ = first_last[d]
        ix = np.arange(a_, b_ + 1); ix = ix[m.tod[ix] <= s["cut_tod"]]
        cut = min(cut, int(ix[-1]))
        if s["e"] >= cut:
            continue
        r = M.sim_option(m, s, cut)
        if r is None:
            continue
        busy_until = s["e"] + r["hold"]
        # the fill minute, as sim_option finds it (first printed minute in [e, e+2] of the bought strike)
        a = max(s["e"] - 3, 0); o, h, lo, c, v = m.leg(r["cp"], r["strike"], a, min(cut, s["e"] + s["maxh"]))
        k0 = s["e"] - a
        pk = [k for k in range(k0, min(k0 + 3, len(c))) if np.isfinite(c[k]) and v[k] > 0]
        fi = s["e"] + (pk[0] - k0)
        xi = fi + r["hold"]
        rows.append(dict(e=s["e"], side=s["side"], fill_i=fi, exit_i=xi, r_fill=r["fill"], r_exit=r["exit"], r_hold=r["hold"],
                         r_why=r["why"], r_strike=r["strike"], day=pd.Timestamp(d)))
    R = pd.DataFrame(rows)
    ts = pd.DatetimeIndex(m.ts)
    R["entry_ts"] = ts[R.fill_i.values].strftime("%Y-%m-%d %H:%M")
    R["exit_ts"] = ts[R.exit_i.values].strftime("%Y-%m-%d %H:%M")
    res = REPO / "research/hunt/m3/results"
    C = []
    for f, per in (("trades_design.csv.gz", "design"), ("trades_holdout.csv.gz", "final")):
        x = pd.read_csv(res / f)
        x = x[(x.rule == "EVE") & (x.und == SYM) & (x.inst == "OPT")].copy(); x["period"] = per
        C.append(x)
    C = pd.concat(C, ignore_index=True)
    # matched on (entry day, side): EVE takes at most one signal a day. The minute index `e` is not used, because the
    # lake's grid has a few more minutes than M3's raw copy from Feb 2026 on (indices shift; prices and times do not).
    C["day"] = pd.to_datetime(C.day)
    J = C.merge(R.drop(columns=["e"]), on=["day", "side"], how="left", indicator=True)
    match = (J._merge == "both") & np.isclose(J.fill, J.r_fill, atol=1e-3) & np.isclose(J.exit, J.r_exit, atol=1e-3) \
        & (J.hold == J.r_hold) & (J.why == J.r_why) & np.isclose(J.strike, J.r_strike)
    in_window = R[(R.day >= C.day.min()) & (R.day <= pd.Timestamp("2026-10-08"))]
    extra = sorted(str(d.date()) for d, sd in set(zip(in_window.day, in_window.side)) - set(zip(pd.to_datetime(C.day), C.side)))
    J["arm"] = "MCX_NG_EVE"; J["und"] = SYM
    J["side_cp"] = np.where(J.cp == 0, "C", "P")
    J["expiry"] = 0  # M3 skips option entries on expiry days (run_book: m.is_exp); exits are same evening
    ok = J[match].copy()
    out = pd.DataFrame(dict(arm=ok.arm, und=ok.und, day=pd.to_datetime(ok.day).dt.strftime("%Y-%m-%d"),
                            entry_ts=ok.entry_ts, exit_ts=ok.exit_ts, side=ok.side_cp, net_rs=ok.net.round(2),
                            period=ok.period, expiry=ok.expiry)).sort_values("entry_ts")
    out.to_csv(OUT / "MCX_NG_EVE.csv", index=False)
    summ = {p: dict(n=int((out.period == p).sum()), net=round(float(out[out.period == p].net_rs.sum()), 1)) for p in ("design", "final")}
    pub = {p: dict(n=int((C.period == p).sum()), net=round(float(C[C.period == p].net.sum()), 1)) for p in ("design", "final")}
    meta = dict(
        arm="MCX_NG_EVE", app_rule="McxEveRules (mcx_ng_eve, 'NG evening breakout'); switched off 25 Oct (R53/R83, McxEvePark)",
        source_code="research/hunt/m3/run.py (EVE, amendment 1) + m3lib.py range_break_signals/sim_option (M3_PRINTS=1); "
                    "rupees from research/hunt/m3/results/trades_{design,holdout}.csv.gz (analyze.py costs)",
        adapter="research/hunt/r113_time_of_day/adapters/mcx_ng_eve.py (+ _mcx_raw.py)",
        command="python3 -I research/hunt/r113_time_of_day/adapters/mcx_ng_eve.py",
        rule="17:00-19:00 IST futures range; first 1-min close beyond it 19:00-22:00 -> buy the 1-ITM near-month NATURALGAS "
             "call (up) / put (down) at the first printed minute in [e, e+2]; exits: option -15% stop on the minute low, "
             "futures back to range mid, target = 1 range width, 120 min, flat 23:15; one trade at a time; no entries on expiry days",
        signal_source="underlying: the NATURALGAS near-month future (Dhan rolling-option `spot`); option premium only for the -15% stop",
        periods=dict(design="5 Aug 2025 - 7 Jul 2026 (M3 design)", final="8 Jul - 8 Oct 2026 (M3 locked holdout, opened once)"),
        size="1 lot NATURALGAS option (1,250 mmBtu)",
        cost_model="M3 analyze.py: Zerodha Rs 20/order, CTT 0.05% sell, MCX 0.0418%, SEBI, stamp, GST, plus measured "
                   "option spread (spreads.json; AM/PM) - committed net used as is",
        n=summ, published=dict(design="197 trades, +Rs 47/trade (MCX_INTRADAY.md table)", final="55 trades, +Rs 93/trade",
                               committed_csv=pub),
        rerun_check=dict(committed_rows=int(len(C)), rerun_matched=int(match.sum()), rerun_unmatched=int((~match).sum()),
                         rerun_extra_trades_not_in_committed=extra),
        parity="yes" if int((~match).sum()) == 0 else f"partial: {int((~match).sum())} committed rows not reproduced (dropped from the CSV)",
        caveats=["entry_ts = the option fill minute (first printed minute in [e, e+2]); exit_ts = the minute the exit "
                 "condition fired; when that minute had no print M3 priced the exit at the next printed minute (<=10 min later, "
                 "stale_exit rows)",
                 "times re-derived by re-running M3's code on the lake's copy of the same Dhan rolling-option feed; "
                 "rupees are M3's committed numbers, not recomputed (spreads.json is not in git)",
                 "M3 verdict: not proven (BH q 0.53-0.63); app arm switched off 25 Oct after R53/R83",
                 "expiry = 0 for all: M3 takes no option entry on an expiry day",
                 "the re-run also finds an EVE trade on 8 Oct 2026 evening that M3's committed list does not have (M3's "
                 "data ended before it); left out, so the list is exactly M3's"],
    )
    (OUT / "MCX_NG_EVE.json").write_text(json.dumps(meta, indent=1))
    print(json.dumps(dict(n=summ, published=pub, check=meta["rerun_check"]), indent=1))
    if (~match).any():
        print(J[~match][["e", "side", "day", "fill", "r_fill", "exit", "r_exit", "hold", "r_hold", "why", "r_why", "_merge"]].head(20).to_string())


if __name__ == "__main__":
    main()
