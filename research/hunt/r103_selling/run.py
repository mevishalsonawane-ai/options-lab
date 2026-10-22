"""R103 design pass: every pre-registered cell on Design A (and B as context), twins, BH, the buy-side rows, the
descriptive parts, the gates, and the one holdout read for passers only (PREREG.md sections 2, 6-8).

    python3 -I research/hunt/r103_selling/run.py                  # design: results/*.csv, *.json, report.md
    python3 -I research/hunt/r103_selling/run.py --twins 20       # fewer twins (a quick check)
    python3 -I research/hunt/r103_selling/run.py --holdout-only   # the ONE holdout read, for design passers only

The holdout is never simulated unless a cell passes every design gate AND --holdout / --holdout-only is given.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r103data as R  # noqa: E402
import r103sim as S  # noqa: E402

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
EVENTS = HERE / "events.csv"
DESIGN_A = (date(2024, 11, 20), date(2026, 7, 14))
DESIGN_B = (date(2021, 8, 5), date(2024, 11, 19))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))
N_TWINS, SEED = 100, 103
GATE_TRADES, GATE_P, GATE_Q, GATE_BLOCKS, GATE_DD, GATE_MONTH = 40, 0.05, 0.10, 3, -40000.0, -13000.0
DAYSETS = ("ALL", "EXPIRY")
EXPIRY_DAYS: dict[str, set] = {}


def in_window(d: date, w) -> bool:
    return w[0] <= d <= w[1]


def blocks_of(days: list[R.Day], n: int = 4) -> list[tuple[date, date]]:
    ds = sorted(d.day for d in days)
    cuts = np.linspace(0, len(ds), n + 1).astype(int)
    return [(ds[cuts[i]], ds[cuts[i + 1] - 1]) for i in range(n)]


def flat_stats(st: dict) -> dict:
    out = {k: v for k, v in st.items() if k not in ("reasons", "blocks")}
    for k, v in st.get("reasons", {}).items():
        out[f"n_{k}"] = v
    for i, v in enumerate(st.get("blocks", [])):
        out[f"block{i + 1}"] = v
    return out


def cell_rows(und, struct, entry, trades_by_exit, blocks, dayset, window):
    rows = []
    for (stop, tg), trades in trades_by_exit.items():
        tr = [t for t in trades if dayset == "ALL" or t.day in EXPIRY_DAYS[und]]
        st = S.stats(tr, blocks)
        net = np.array([t.net for t in tr])
        rows.append(dict(window=window, und=und, struct=struct, entry=entry, stop=stop, target=tg, dayset=dayset,
                         p_zero=S.sign_flip_p(net, seed=SEED) if len(tr) else 1.0, **flat_stats(st)))
    return rows


def design_pass(days_by_und: dict[str, list[R.Day]], window, label: str, blackout: set, twins: int, with_twins: bool):
    rows, buy_rows = [], []
    for und, days in days_by_und.items():
        ds = S.day_set([d for d in days if in_window(d.day, window)], "ALL", blackout)
        blocks = blocks_of(ds) if ds else None
        print(label, und, len(ds), "sessions after blackout", sum(d.expiry for d in ds), "expiries", flush=True)
        for struct in S.STRUCTS:
            twin = None
            if with_twins:
                twin = {"ALL": S.run_twins(ds, struct, twins, SEED + S.STRUCTS.index(struct) + 10 * R.UNDS.index(und)),
                        "EXPIRY": S.run_twins([d for d in ds if d.expiry], struct, twins,
                                              SEED + 100 + S.STRUCTS.index(struct) + 10 * R.UNDS.index(und))}
            for entry, row in S.ENTRIES.items():
                g = S.run_group(ds, struct, row)
                for dayset in DAYSETS:
                    cr = cell_rows(und, struct, entry, g, blocks, dayset, label)
                    if twin is not None:
                        for r in cr:
                            nets = np.array([tw[(r["stop"], r["target"])] for tw in twin[dayset]])
                            r["twin_mean"] = float(nets.mean())
                            r["twin_p"] = float(((nets >= r["net"]).sum() + 1) / (len(nets) + 1))
                    rows.extend(cr)
                b = S.run_buy(ds, struct, row)
                for rule, trades in b.items():
                    for dayset in DAYSETS:
                        tr = [t for t in trades if dayset == "ALL" or t.day in EXPIRY_DAYS[und]]
                        st = S.stats(tr, blocks)
                        buy_rows.append(dict(window=label, und=und, struct=struct, entry=entry, rule=rule, dayset=dayset,
                                             p_zero=S.sign_flip_p(np.array([t.net for t in tr]), seed=SEED) if tr else 1.0,
                                             **flat_stats(st)))
            print("  ", struct, "done", flush=True)
    return pd.DataFrame(rows), pd.DataFrame(buy_rows)


def gates(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["q"] = S.bh(df.p_zero.values)
    df["g_trades"] = df.trades >= GATE_TRADES
    df["g_p"] = (df.net > 0) & (df.p_zero <= GATE_P) & (df.q <= GATE_Q)
    df["g_blocks"] = df.get("blocks_positive", 0) >= GATE_BLOCKS
    df["g_account"] = (df.max_dd >= GATE_DD) & (df.worst_month >= GATE_MONTH)
    df["g_stop"] = df.stop != "X0"
    df["passes"] = df.g_trades & df.g_p & df.g_blocks & df.g_account & df.g_stop
    return df


# ------------------------------------------------------------------------------------------------ descriptives
HOURS = [(10, 15), (11, 15), (12, 15), (13, 15), (14, 15), (15, 5)]
LAST_HOUR = [(14, m) for m in range(0, 60, 5)] + [(15, m) for m in range(0, 26, 5)]


def theta_by_hour(days: list[R.Day]) -> dict:
    """The 09:20 ATM straddle's value later in the day as a share of its 09:20 value, by DTE bucket (each day once
    per mark; the expiry-day last-hour marks are separate keys)."""
    t0 = S.ENTRIES["0920"]
    recs = {}
    for d in days:
        legs = S.structure(d, t0, "STR")
        if legs is None:
            continue
        v0 = legs[0].close[t0] + legs[1].close[t0]
        if not np.isfinite(v0) or v0 <= 0:
            continue
        b = "expiry" if d.expiry else ("1" if d.dte == 1 else ("2" if d.dte == 2 else "3+"))
        marks = sorted(set(HOURS + (LAST_HOUR if d.expiry else [])))
        for hh, mm in marks:
            v = legs[0].close[R.minute(hh, mm)] + legs[1].close[R.minute(hh, mm)]
            if np.isfinite(v):
                recs.setdefault((b, f"{hh:02d}:{mm:02d}"), []).append(v / v0)
    out = {}
    for (b, hm), vals in sorted(recs.items()):
        a = np.array(vals)
        out[f"{b}|{hm}"] = dict(n=len(a), q25=float(np.percentile(a, 25)), median=float(np.median(a)),
                                q75=float(np.percentile(a, 75)), mean=float(a.mean()))
    return out


def tail_days(days_by_und: dict[str, list[R.Day]], lake: Path) -> dict:
    """The 20 biggest close-to-close index moves since 2017 and the naked 09:20 straddle's result where option data
    exists (design windows only: never a holdout day)."""
    out = {}
    daily = pd.read_parquet(lake / "index_daily" / "index_daily.parquet")
    daily["date"] = pd.to_datetime(daily.date).dt.date
    for und, days in days_by_und.items():
        by_day = {d.day: d for d in days}
        x = daily[(daily.symbol == und) & (daily.date >= date(2016, 12, 1))].sort_values("date").copy()
        x["ret"] = x.close.pct_change() * 100
        x["gap"] = (x.open / x.close.shift(1) - 1) * 100
        x["intraday"] = (x.close / x.open - 1) * 100
        x = x[(x.date >= date(2017, 1, 1)) & (x.date <= DESIGN_A[1])]
        top = x.reindex(x.ret.abs().sort_values(ascending=False).index).head(20)
        rows = []
        for _, r in top.iterrows():
            rec = dict(date=str(r.date), close_to_close_pct=round(float(r.ret), 2), gap_pct=round(float(r.gap), 2),
                       open_to_close_pct=round(float(r.intraday), 2))
            d = by_day.get(r.date)
            if d is not None:
                for stop in ("X0", "X2"):
                    tr = S.simulate(d, S.ENTRIES["0920"], "STR", stop, "T0")
                    rec[f"straddle_0920_{stop}_net"] = round(tr.net) if tr else None
                    rec["credit_pts"] = round(tr.credit, 1) if tr else None
                    rec["lot"] = tr.lot if tr else None
            rows.append(rec)
        out[und] = rows
        worst = []
        for d in days:
            if not (in_window(d.day, DESIGN_A) or in_window(d.day, DESIGN_B)):
                continue
            tr = S.simulate(d, S.ENTRIES["0920"], "STR", "X0", "T0")
            if tr:
                worst.append((tr.net, tr.net / tr.lot, str(d.day), round(tr.credit, 1), tr.lot, d.expiry))
        worst.sort(key=lambda z: z[1])
        out[f"{und}_worst_straddle_days_per_unit"] = [dict(date=w[2], net_rs=round(w[0]), loss_pts_per_unit=round(w[1], 1),
                                                           credit_pts=w[3], lot=w[4], expiry=w[5]) for w in worst[:20]]
    return out


def distribution(trades_by_und: dict[str, list[S.Trade]]) -> dict:
    out = {}
    for und, trades in trades_by_und.items():
        net = np.array(sorted(t.net for t in trades))
        if not len(net):
            continue
        n = len(net)
        k10, k2 = max(int(round(n * 0.10)), 1), max(int(round(n * 0.02)), 1)
        m, s = net.mean(), net.std()
        skew = float(((net - m) ** 3).mean() / s ** 3) if s > 0 else 0.0
        out[und] = dict(days=n, total=float(net.sum()), best10pct_days_sum=float(net[-k10:].sum()),
                        worst2pct_days_sum=float(net[:k2].sum()), skew=skew, median=float(np.median(net)),
                        mean=float(m), p5=float(np.percentile(net, 5)), p95=float(np.percentile(net, 95)))
    return out


def expiry_spikes(days_by_und: dict[str, list[R.Day]]) -> dict:
    """How often the expiry-day 09:20 straddle's value on closes reached 1.5x / 2x its credit before 15:05."""
    out = {}
    for und, days in days_by_und.items():
        n = hit15 = hit2 = 0
        for d in days:
            if not d.expiry:
                continue
            legs = S.structure(d, S.ENTRIES["0920"], "STR")
            if legs is None:
                continue
            t = S.ENTRIES["0920"]
            v0 = legs[0].close[t] + legs[1].close[t]
            v = (legs[0].close + legs[1].close)[t + 1:S.CLOSE_ROW_EXPIRY]
            if not np.isfinite(v0) or v0 <= 0 or not np.isfinite(v).any():
                continue
            n += 1
            hit15 += bool(np.nanmax(v) >= 1.5 * v0)
            hit2 += bool(np.nanmax(v) >= 2.0 * v0)
        out[und] = dict(expiries=n, reached_1_5x=hit15, reached_2x=hit2)
    return out


def event_sensitivity(days_by_und, blackout) -> list[dict]:
    rows = []
    for und, days in days_by_und.items():
        ev = [d for d in days if d.day in blackout and in_window(d.day, DESIGN_A)]
        for struct in S.STRUCTS:
            for stop in ("X0", "X2", "X3"):
                tr = S.run_cell(ev, struct, S.ENTRIES["0920"], stop, "T0")
                st = S.stats(tr)
                rows.append(dict(und=und, struct=struct, stop=stop, event_days=len(ev), trades=st["trades"],
                                 net=st["net"], worst=st.get("worst_day")))
    return rows


def descriptives(design_days: dict, blackout: set) -> dict:
    desc = dict(
        theta_by_hour={u: theta_by_hour([d for d in ds if in_window(d.day, DESIGN_A)]) for u, ds in design_days.items()},
        theta_by_hour_B={u: theta_by_hour([d for d in ds if in_window(d.day, DESIGN_B)]) for u, ds in design_days.items()},
        tail_days=tail_days(design_days, R.LAKE),
        expiry_spikes_A=expiry_spikes({u: [d for d in ds if in_window(d.day, DESIGN_A)] for u, ds in design_days.items()}),
        event_days_A=event_sensitivity(design_days, blackout),
        sessions={u: dict(A=sum(in_window(d.day, DESIGN_A) for d in ds), B=sum(in_window(d.day, DESIGN_B) for d in ds),
                          expiries_A=sum(in_window(d.day, DESIGN_A) and d.expiry for d in ds),
                          blackout_A=sum(in_window(d.day, DESIGN_A) and d.day in blackout for d in ds))
                  for u, ds in design_days.items()},
    )
    dist = {}
    for u, ds in design_days.items():
        dist[u] = S.run_cell(S.day_set([d for d in ds if in_window(d.day, DESIGN_A)], "ALL", blackout), "STR",
                             S.ENTRIES["0920"], "X0", "T0")
    desc["distribution_str_0920_X0_A"] = distribution(dist)
    return desc


# ------------------------------------------------------------------------------------------------ report
def rs(x) -> str:
    return f"{x:+,.0f}" if isinstance(x, (int, float, np.floating)) and np.isfinite(x) else "-"


def fmt_cells(df: pd.DataFrame, cols) -> str:
    head = "| " + " | ".join(cols) + " |\n|" + "---|" * len(cols) + "\n"
    body = ""
    for _, r in df.iterrows():
        vals = []
        for c in cols:
            v = r.get(c, np.nan)
            if c in ("win_rate", "rom", "modelled_share"):
                vals.append(f"{v * 100:.0f}%" if np.isfinite(v) else "-")
            elif c in ("p_zero", "q", "twin_p"):
                vals.append(f"{v:.3f}" if np.isfinite(v) else "-")
            elif isinstance(v, (float, np.floating)):
                vals.append(rs(v))
            else:
                vals.append(str(v))
        body += "| " + " | ".join(vals) + " |\n"
    return head + body


def write_report(A, B, buyA, desc, hold, twins):
    L = ["# R103 results (generated by run.py; the write-up is research/DEEP_OPTIONS_SELLING.md)\n\n",
         f"Design A {DESIGN_A[0]} .. {DESIGN_A[1]}; Design B {DESIGN_B[0]} .. {DESIGN_B[1]}; holdout {HOLDOUT[0]} .. "
         f"{HOLDOUT[1]} ({'read for ' + str(len(hold)) + ' cells' if len(hold) else 'NOT read'}). "
         f"{len(A)} cells in the BH family, {twins} twins per structure.\n\n",
         "Sessions: " + json.dumps(desc["sessions"]) + "\n\n## Gate summary (Design A)\n\n",
         f"- cells with net > 0: {int((A.net > 0).sum())} of {len(A)}; p <= 0.05: {int((A.p_zero <= 0.05).sum())}; "
         f"BH q <= 0.10: {int((A.q <= 0.10).sum())}; all gates: {int(A.passes.sum())}\n\n"]
    cols = ["und", "struct", "entry", "stop", "target", "dayset", "trades", "win_rate", "net", "avg_win", "avg_loss",
            "worst_day", "max_dd", "rom", "days_below_13k", "months_below_13k", "blocks_positive", "modelled_share", "p_zero",
            "q", "twin_mean", "twin_p", "passes"]
    L.append("## Every cell, Design A (sorted by net)\n\n" + fmt_cells(A.sort_values("net", ascending=False), cols))
    if len(B):
        L.append("\n## Design B (earlier regime, context only)\n\n")
        L.append(fmt_cells(B.sort_values("net", ascending=False), [c for c in cols if c not in ("q", "twin_mean", "twin_p", "passes")]))
    L.append("\n## BUY side (comparison rows, Design A)\n\n")
    L.append(fmt_cells(buyA.sort_values("net", ascending=False),
                       ["und", "struct", "entry", "rule", "dayset", "trades", "win_rate", "net", "avg_win", "avg_loss", "worst_day", "max_dd", "p_zero"]))
    if len(hold):
        L.append("\n## Holdout (read once)\n\n")
        L.append(fmt_cells(hold, ["und", "struct", "entry", "stop", "target", "dayset", "trades", "win_rate", "net", "worst_day", "max_dd", "holds"]))
    (RESULTS / "report.md").write_text("".join(L), encoding="utf-8")       # not committed (it repeats cells_A.csv)
    print("report written", RESULTS / "report.md")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--twins", type=int, default=N_TWINS)
    ap.add_argument("--no-b", action="store_true", help="skip Design B")
    ap.add_argument("--holdout", action="store_true", help="the ONE holdout read for design passers (final run only)")
    ap.add_argument("--holdout-only", action="store_true", help="only the holdout step, from the saved results")
    args = ap.parse_args()
    RESULTS.mkdir(exist_ok=True)
    blackout = S.events(EVENTS)
    days_by_und = {u: R.load(u) for u in R.UNDS}
    for u, ds in days_by_und.items():
        EXPIRY_DAYS[u] = {d.day for d in ds if d.expiry}
    design_days = {u: [d for d in ds if d.day <= DESIGN_A[1]] for u, ds in days_by_und.items()}

    if args.holdout_only:
        A = pd.read_csv(RESULTS / "cells_A.csv")
        B = pd.read_csv(RESULTS / "cells_B.csv") if (RESULTS / "cells_B.csv").exists() else pd.DataFrame()
        buyA = pd.read_csv(RESULTS / "buy_A.csv")
        desc = json.loads((RESULTS / "descriptives.json").read_text(encoding="utf-8"))
        args.holdout = True
    else:
        A, buyA = design_pass(design_days, DESIGN_A, "A", blackout, args.twins, True)
        A = gates(A)
        A.to_csv(RESULTS / "cells_A.csv", index=False)
        buyA.to_csv(RESULTS / "buy_A.csv", index=False)
        if not args.no_b:
            B, buyB = design_pass(design_days, DESIGN_B, "B", blackout, args.twins, False)
            B.to_csv(RESULTS / "cells_B.csv", index=False)
            buyB.to_csv(RESULTS / "buy_B.csv", index=False)
        else:
            B = pd.DataFrame()
        # labelled sensitivity outside the BH family: BANKNIFTY on DTE <= 5 only (PREREG amendment 1)
        bn = {"BANKNIFTY": [d for d in design_days["BANKNIFTY"] if 0 <= d.dte <= 5]}
        sens, _ = design_pass(bn, DESIGN_A, "A-BN-DTE5", blackout, 0, False)
        sens.to_csv(RESULTS / "sens_bn_dte5.csv", index=False)
        desc = descriptives(design_days, blackout)
        (RESULTS / "descriptives.json").write_text(json.dumps(desc, indent=1, default=str), encoding="utf-8")

    passers = A[A.passes.astype(bool)]
    hold = pd.DataFrame()
    if len(passers) and not args.holdout:
        print(len(passers), "cells pass the design gates; the holdout is NOT read (run with --holdout-only once)", flush=True)
    elif len(passers):
        print("HOLDOUT READ for", len(passers), "passing cells", flush=True)
        hrows = []
        for _, r in passers.iterrows():
            ds = S.day_set([d for d in days_by_und[r.und] if in_window(d.day, HOLDOUT)], r.dayset, blackout)
            tr = S.run_cell(ds, r.struct, S.ENTRIES[str(r.entry).zfill(4)], r.stop, r.target)
            st = S.stats(tr)
            hrows.append(dict(und=r.und, struct=r.struct, entry=r.entry, stop=r.stop, target=r.target, dayset=r.dayset,
                              **flat_stats(st), holds=bool(st["net"] > 0 and st.get("max_dd", 0) >= GATE_DD
                                                           and st.get("worst_month", 0) >= GATE_MONTH)))
        hold = pd.DataFrame(hrows)
        hold.to_csv(RESULTS / "holdout.csv", index=False)
    else:
        print("no cell passes the design gates: the holdout stays locked (not read)", flush=True)
    write_report(A, B, buyA, desc, hold, args.twins)


if __name__ == "__main__":
    main()
