"""R113 adapter (26 Oct 2026): the MCX paper arm "MCX 12-month trend" (app McxTrendRules = M2's TSM252 on the PORT legs,
long + short; research/MCX_TREND.md; research/hunt/m2/prep.py + engine.py signal()/run_futures()).

M2 is a DAILY-bar portfolio backtest: it writes daily P&L, never a trade list. This adapter runs M2's prep.build() and
engine.signal("TSM252") unchanged on the lake's copy of the same Dhan continuous near-month daily futures
(fut_daily_continuous, expiry code 0, with OI), and splits each leg's daily P&L into trades, mirroring run_futures'
own accounting for one leg (constrained=False): a trade = a run of the same non-zero target position, entered and left
at the day's open (M2 trades at the next session's open after a month-end signal); rolls stay inside the trade (M2
charges both sides of the roll and earns no roll gap). The split is checked to sum, day by day, to
engine.run_futures(..., constrained=False) for that leg.

NOT the published arm: the lake has no LEAD series, so only 6 of the 7 PORT legs exist (CRUDE, NATGAS, GOLD, SILVER,
ZINC, ALUM); and M2's published PORT numbers are the CONSTRAINED Rs 1 lakh book (margin checks, forced exits, ruin
stop), whose legs interact, so they cannot be split into per-trade rupees at all. Parity: no (stated in the json).
Signal source: each leg's own future (the underlying).
usage: python3 -I research/hunt/r113_time_of_day/adapters/mcx_trend12m.py"""
import json, os, sys
from pathlib import Path
import numpy as np, pandas as pd

REPO = Path("/home/user/options-lab")
sys.path.insert(0, str(REPO / "research/hunt/m2"))
import prep
import engine as E

LAKE = Path(os.path.expanduser("~/.cache/iraalgo/lake"))
WORK = Path(os.path.expanduser("~/.cache/iraalgo/r113/m2data"))
OUT = Path(os.path.expanduser("~/.cache/iraalgo/r113/trades"))
MISSING = [n for n, s in prep.SPEC.items() if s["src"] == "LEAD"]
HOLD_END = pd.Timestamp("2026-10-07")
CLOSE_LABEL = "23:30"  # nominal label for a trade still open at a period's end (marked at that day's close)


def build_panel():
    WORK.mkdir(parents=True, exist_ok=True)
    d = pd.read_parquet(LAKE / "fut_daily_continuous.parquet")
    d = d[d.expiry_code == 0].rename(columns={"symbol": "sym", "date": "ts", "oi": "open_interest"})
    d[["ts", "sym", "open", "high", "low", "close", "volume", "open_interest"]].to_parquet(WORK / "dhan_daily.parquet")
    for n in MISSING:          # no LEAD in the lake: drop the leg (runtime only; prep.py itself is unchanged)
        prep.SPEC.pop(n)
    prep.D = str(WORK); E.D = str(WORK)
    prep.build()


def leg_trades(data, n, s, start, end):
    """run_futures' one-leg, unconstrained loop, with each rupee booked to the trade it belongs to."""
    g = data[n]
    idx = pd.DatetimeIndex(sorted(g.index)); idx = idx[(idx >= start) & (idx <= end)]
    g = g.reindex(idx); sp = prep.SPEC[n]; L = sp["lots"]; pv = sp["pv"] * L
    o, c, cl = g.open.values, g.close.values, g.close.ffill().values
    roll = g.roll.fillna(False).values.astype(bool); has = g.open.notna().values
    sv = s.reindex(idx).ffill().fillna(0.0).values
    pos, cur, trades, daily = 0.0, None, [], np.zeros(len(idx))
    for t in range(len(idx)):
        if not has[t]:
            continue
        prev = pos; want = sv[t - 1] if t > 0 else 0.0
        cprev = cl[t - 1] if t > 0 else o[t]
        if not roll[t]:
            x = prev * (o[t] - cprev) * pv
            if cur: cur["pnl"] += x
            daily[t] += x
        elif prev != 0:
            x = E.side_cost(cprev, L, sp, True) + E.side_cost(cprev, L, sp, False)
            cur["pnl"] -= x; cur["rolls"] += 1; daily[t] -= x
        if want != prev:
            dlt = want - prev; units = int(abs(dlt)); k = 0
            if prev != 0:   # close the old trade at today's open
                x = E.side_cost(o[t], L, sp, dlt > 0); cur["pnl"] -= x; daily[t] -= x; k = 1
                cur["exit"] = idx[t]; cur["exit_open"] = True; trades.append(cur); cur = None
            if want != 0:
                cur = dict(leg=n, contract=sp["contract"], side=int(want), entry=idx[t], pnl=0.0, rolls=0, lots=L)
                for _ in range(units - k):
                    x = E.side_cost(o[t], L, sp, dlt > 0); cur["pnl"] -= x; daily[t] -= x
        x = want * (c[t] - o[t]) * pv
        if cur: cur["pnl"] += x
        daily[t] += x
        pos = want
    if cur:
        cur["exit"] = idx[-1]; cur["exit_open"] = False; trades.append(cur)
    ref = E.run_futures(data, {n: s}, "LS", start=start, end=end, constrained=False).pnl
    chk = float(np.max(np.abs(ref.reindex(idx).fillna(0).values - daily))) if len(idx) else 0.0
    return trades, chk


def main():
    build_panel()
    rows, checks, ref = [], {}, {}
    for per, data, start, end in (("design", E.load(E.DEV_END), pd.Timestamp("2013-01-01"), E.DEV_END),
                                  ("final", E.load(), E.HOLD_START, HOLD_END)):
        tot = 0.0
        for n in prep.SPEC:
            s = E.signal(data[n], "TSM252")
            tr, chk = leg_trades(data, n, s, start, end)
            checks[f"{per}:{n}"] = chk
            for t in tr:
                t["period"] = per; rows.append(t); tot += t["pnl"]
        # for reference only: M2's own constrained Rs 1 lakh book on these 6 legs (not the published 7-leg book)
        df = E.run_futures(data, {n: E.signal(data[n], "TSM252") for n in prep.SPEC}, "LS", start=start, end=end)
        st = E.stats(df)
        ref[per] = dict(unconstrained_6leg_net=round(tot, 1), constrained_6leg=dict(net=round(float(df.pnl.sum()), 1),
                        cagr_pct=round(st.get("cagr", np.nan), 1), rs_month=round(st.get("rs_month", np.nan), 0),
                        final=round(st.get("final", np.nan), 0), maxdd=round(st.get("maxdd", np.nan), 0)))
    T = pd.DataFrame(rows)
    T["entry_ts"] = T.entry.dt.strftime("%Y-%m-%d") + " 09:00"
    T["exit_ts"] = T.exit.dt.strftime("%Y-%m-%d") + np.where(T.exit_open, " 09:00", " " + CLOSE_LABEL)
    csv = pd.DataFrame(dict(arm="MCX_TREND12M", und=T.contract, day=T.entry.dt.strftime("%Y-%m-%d"), entry_ts=T.entry_ts,
                            exit_ts=T.exit_ts, side=np.where(T.side > 0, "L", "S"), net_rs=T.pnl.round(2), period=T.period,
                            expiry="")).sort_values(["entry_ts", "und"])
    csv.to_csv(OUT / "MCX_TREND12M.csv", index=False)
    summ = {p: dict(n=int((T.period == p).sum()), net=round(float(T[T.period == p].pnl.sum()), 1),
                    open_at_period_end=int(((T.period == p) & ~T.exit_open).sum()),
                    by_leg={k: dict(n=int(len(x)), net=round(float(x.pnl.sum()), 0)) for k, x in T[T.period == p].groupby("contract")})
            for p in ("design", "final")}
    meta = dict(
        arm="MCX_TREND12M", app_rule="McxTrendRules (mcx_trend12, 'MCX 12-month trend'), hidden 21 Oct; legs CRUDEOILM, "
        "NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI, GOLDPETAL, 1 lot each in the app",
        source_code="research/hunt/m2/prep.py build() + engine.py load()/signal('TSM252')/side_cost()/run_futures() "
                    "(trade split mirrors run_futures, constrained=False, one leg at a time)",
        adapter="research/hunt/r113_time_of_day/adapters/mcx_trend12m.py",
        command="python3 -I research/hunt/r113_time_of_day/adapters/mcx_trend12m.py",
        signal_source="each leg's own continuous near-month future (the underlying): sign of the 252-trading-day change of "
                      "the roll-adjusted price at each month end, traded at the next open; long and short",
        periods=dict(design="1 Jan 2013 - 31 Dec 2024 (M2 development; data truncated at 31 Dec 2024)",
                     final="1 Jan 2025 - 7 Oct 2026 (M2 locked holdout)"),
        size="1 lot each of the minis (pv from M2 SPEC), GOLDPETAL 5 lots as in M2 (the app holds 1); fixed lots, no compounding",
        cost_model="engine.side_cost: brokerage min(0.03%, Rs 20), MCX 0.0021%, SEBI, GST, CTT 0.01% sell / stamp 0.002% buy, "
                   "half the round-trip spread per side, 0.02% slippage; rolls pay both sides",
        n=summ, reference=ref, split_check_max_abs_rs_vs_run_futures=max(checks.values()),
        published=dict(design="PORT 12-month TSM long+short (7 legs, constrained Rs 1 lakh): +16.9% CAGR, +Rs 3,847/month, "
                              "max DD -Rs 1.81 L, end Rs 6.5 L, 11 trades/yr",
                       final="walk-forward pick PORT 12-month TSM L/S on the holdout: +92% CAGR, +Rs 9.8k/month, end Rs 3.15 L, "
                             "12 trades/yr"),
        parity="no: LEAD (LEADMINI) is not in the lake, so 6 of 7 legs; and the published book is the constrained Rs 1 lakh "
               "portfolio (margin, forced exits, ruin stop) that has no per-trade rupees. This list is the per-leg, "
               "unconstrained, fixed-lot version (what a 1-lot-per-leg paper arm does); compare only with care",
        caveats=["daily bars: every entry and exit is at a session OPEN (labelled 09:00, the MCX open); no intraday time "
                 "exists in the research, so time-of-day buckets are degenerate for this arm (use holding time/weekday only)",
                 f"trades still open at a period's end are marked at that last day's close; their exit_ts carries the nominal "
                 f"label {CLOSE_LABEL} (not a real fill)",
                 "rolls are inside a trade (M2 pays the roll costs, earns no roll gap); the app instead closes and reopens 5 "
                 "trading days before expiry",
                 "expiry blank: no MCX futures expiry calendar for 2013-2026 in the lake",
                 "M2 verdict: not proven (Reality Check p 0.13, SPA p 0.79); needs Rs 8-10 lakh"],
    )
    (OUT / "MCX_TREND12M.json").write_text(json.dumps(meta, indent=1, default=str))
    print(json.dumps(dict(n={p: {k: v for k, v in summ[p].items() if k != "by_leg"} for p in summ}, ref=ref,
                          check=meta["split_check_max_abs_rs_vs_run_futures"]), indent=1))
    print({p: summ[p]["by_leg"] for p in summ})


if __name__ == "__main__":
    main()
