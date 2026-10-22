"""R98c (PREREG_C.md): trade the continuation after a k-straddle move from 13:00, with ATM / ITM1 / OTM1 / Rs 5 options
and index-based exits; plus an index-only diagnostic.

    python3 -I research/hunt/r98/conf.py             # -> $R98_OUT/c_opt.parquet, c_idx.parquet; results/conf.md, conf.json
    python3 -I research/hunt/r98/conf.py report
"""
from __future__ import annotations

import json
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
from hero2 import net_legs, pick_near5, md_table, pct, rs  # noqa: E402

RES = Path(__file__).resolve().parent / "results"
KS = (1.5, 2.0)
INSTR = ("ATM", "ITM1", "OTM1", "R5")
XS = ("X1_hold", "X2_stop0.5", "X3_stop1", "X4_stop0.5_tgt1", "X5_stop1_tgt1", "X6_trail0.5")
IDX_COST_PCT = 0.05
HOLDOUT_FROM = 2024
SEED = 98


def exit_minute(x: str, ix: np.ndarray, d: float, lt: float, e13: float) -> int:
    """First index-close minute (relative to the entry minute) at which exit x fires, or -1."""
    rel = d * (ix - lt)                                     # signed distance from the trigger level, in points
    ok = ~np.isnan(ix)
    if x == "X1_hold":
        return -1
    if x == "X6_trail0.5":
        best = np.maximum.accumulate(np.where(ok, d * ix, -np.inf))
        m = ok & (d * ix <= best - 0.5 * e13)
    else:
        stop = 0.5 if "stop0.5" in x else 1.0
        m = ok & (rel <= -stop * e13)
        if "tgt1" in x:
            m |= ok & (rel >= 1.0 * e13)
    return int(np.argmax(m)) if m.any() else -1


def option_exit_price(c: np.ndarray, j: int) -> float:
    """The option's close in minute j, else the next printed close, else the last printed close (0 if none)."""
    ok = ~np.isnan(c)
    if j >= 0:
        nxt = np.nonzero(ok[j:])[0]
        if len(nxt):
            return float(c[j + nxt[0]])
    prev = np.nonzero(ok)[0]
    return float(c[prev[-1]]) if len(prev) else 0.0


def pick(day, inst, side, t, s_t):
    step = R.STEP[day.und]
    if inst == "R5":
        p = pick_near5(day, t, s_t, side)
        return p.get(side)
    atm = round(s_t / step) * step
    sgn = 1 if side == "C" else -1
    k = {"ATM": atm, "ITM1": atm - sgn * step, "OTM1": atm + sgn * step}[inst]
    a = day.opts.get((float(k), side))
    if a is None or not L.traded(a, t):
        return None
    return float(k)


def day_trades(day):
    sp = L.spot(day)
    em, _ = L.straddle(day)
    t13 = R.minute(13, 0)
    s13, e13 = sp[t13], em[t13]
    opt, idx = [], []
    if np.isnan(s13) or np.isnan(e13) or e13 <= 0:
        return opt, idx
    for k in KS:
        trig = None
        for t in range(R.minute(13, 15), R.minute(14, 45) + 1):
            if not np.isnan(sp[t]) and abs(sp[t] - s13) >= k * e13:
                trig = t
                break
        if trig is None:
            continue
        t = trig
        d = 1.0 if sp[t] > s13 else -1.0
        side = "C" if d > 0 else "P"
        lt = s13 + d * k * e13
        te = t + 1
        ix = day.ix[te:L.T_END + 1, 3]
        hi, lo = day.ix[te:L.T_END + 1, 1], day.ix[te:L.T_END + 1, 2]
        cont = bool((np.nanmax(hi) - lt >= e13) if d > 0 else (lt - np.nanmin(lo) >= e13))
        base = dict(und=day.und, day=day.day, k=k, d=d, t=t, s13=s13, e13=e13, cont1=cont)
        # index proxy
        i0 = day.ix[te, 0] if not np.isnan(day.ix[te, 0]) else sp[t]
        for x in XS:
            j = exit_minute(x, ix, d, lt, e13)
            ok = np.nonzero(~np.isnan(ix))[0]
            lvl = ix[j] if j >= 0 else (ix[ok[-1]] if len(ok) else i0)
            idx.append(dict(base, exit=x, pnl_em=d * (lvl - i0) / e13, pnl_pct=d * (lvl - i0) / i0 * 100 - IDX_COST_PCT))
        for inst in INSTR:
            kk = pick(day, inst, side, t, sp[t])
            if kk is None:
                continue
            a = day.opts[(kk, side)]
            if np.isnan(a[te, R.O]):
                continue
            e = float(a[te, R.O]) + L.TICK
            c = a[te:L.T_END + 1, R.C]
            r = dict(base, inst=inst, strike=kk, side=side, entry=e)
            for x in XS:
                j = exit_minute(x, ix, d, lt, e13)
                r[x] = net_legs(day.und, e, [(1.0, option_exit_price(c, j))])
            opt.append(r)
    return opt, idx


def run_und(und):
    O, I = [], []
    for day in R.load(und):
        o, i = day_trades(day)
        O += o
        I += i
    print(und, len(O), len(I), flush=True)
    return O, I


def summ(g, col, rng=None):
    x = g[col].values
    by = g.groupby(["und", "day"])[col].sum().sort_values(ascending=False)
    yrs = g.groupby("yr")[col].sum()
    r = dict(n=len(g), win=float((x > 0).mean()), mean=float(x.mean()), wo5=float((x.sum() - by.head(5).sum()) / max(len(g), 1)),
             years_pos=int((yrs > 0).sum()), years=int(len(yrs)))
    if rng is not None and len(g):
        v = by.values
        cnt = g.groupby(["und", "day"]).size().reindex(by.index).values
        bs = [v[i].sum() / cnt[i].sum() for i in (rng.integers(0, len(v), len(v)) for _ in range(2000))]
        r["lo95"], r["hi95"] = float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))
    return r


def report():
    o = pd.read_parquet(R.OUT / "c_opt.parquet")
    ix = pd.read_parquet(R.OUT / "c_idx.parquet")
    for df in (o, ix):
        df["yr"] = pd.to_datetime(df.day).dt.year
        df["part"] = np.where(df.yr >= HOLDOUT_FROM, "holdout", "design")
    S, md = {}, ["# R98c: trading the continuation after a k-straddle move (generated by conf.py)\n"]
    # trigger counts and continuation
    trig = ix[ix.exit == "X1_hold"]
    t = trig.groupby(["k", "part"]).agg(days=("cont1", "size"), cont=("cont1", "mean")).reset_index()
    t["goes 1 more straddle"] = t.cont.map(lambda v: pct(v, 1))
    md += ["## Triggers\n", md_table(t.drop(columns="cont")), ""]
    S["triggers"] = t.drop(columns="cont").to_dict(orient="records")
    # design: all 48
    rows = []
    for (k, inst), g in o.groupby(["k", "inst"]):
        for x in XS:
            dsg = summ(g[g.part == "design"], x)
            hld = summ(g[g.part == "holdout"], x)
            rows.append(dict(k=k, inst=inst, exit=x, d_n=dsg["n"], d_win=dsg["win"], d_mean=dsg["mean"], d_wo5=dsg["wo5"],
                             d_years=f"{dsg['years_pos']}/{dsg['years']}", h_n=hld["n"], h_win=hld["win"], h_mean=hld["mean"],
                             h_wo5=hld["wo5"], h_years=f"{hld['years_pos']}/{hld['years']}"))
    allr = pd.DataFrame(rows).sort_values("d_mean", ascending=False)
    best = allr.iloc[0]
    g = o[(o.k == best.k) & (o.inst == best.inst) & (o.part == "holdout")]
    rng = np.random.default_rng(SEED)
    s = summ(g, best.exit, rng)
    passed = s["mean"] > 0 and s["lo95"] > 0 and s["wo5"] > 0 and s["years_pos"] == s["years"] == 3
    S["selected"] = dict(k=float(best.k), inst=best.inst, exit=best.exit, design_mean=float(best.d_mean), holdout=s,
                         passed=bool(passed))
    md += ["## The test: the rule chosen on design 2021-23, read once on 2024-26\n",
           f"- Chosen on design: **k = {best.k:g}, {best.inst}, {best.exit}**, with a design mean of Rs {best.d_mean:,.0f} on "
           f"{int(best.d_n)} tickets ({pct(best.d_win, 1)} wins, years > 0 {best.d_years}).",
           f"- Holdout: {s['n']} tickets, win {pct(s['win'], 1)}, mean **Rs {s['mean']:,.0f}**, bootstrap 95% "
           f"{rs(s['lo95'])} .. {rs(s['hi95'])}, without the 5 best days Rs {s['wo5']:,.0f}, years > 0 "
           f"{s['years_pos']}/{s['years']}.",
           f"- **Passes: {'YES' if passed else 'no'}.**", ""]
    show = allr.copy()
    for c in ("d_win", "h_win"):
        show[c] = show[c].map(lambda v: pct(v))
    for c in ("d_mean", "d_wo5", "h_mean", "h_wo5"):
        show[c] = show[c].map(rs)
    show.columns = ["k", "option", "exit", "design n", "design win", "design mean", "design w/o 5 best", "design years > 0",
                    "holdout n", "holdout win", "holdout mean", "holdout w/o 5 best", "holdout years > 0"]
    n_pos_d = int((allr.d_mean > 0).sum())
    n_pos_h = int((allr.h_mean > 0).sum())
    n_both = int(((allr.d_mean > 0) & (allr.h_mean > 0)).sum())
    S["counts"] = dict(rules=len(allr), design_pos=n_pos_d, holdout_pos=n_pos_h, both_pos=n_both)
    md += [f"## All 48 option rules (best on design first). Mean > 0: {n_pos_d} on design, {n_pos_h} on the holdout, "
           f"{n_both} on both\n", md_table(show), ""]
    S["all"] = allr.to_dict(orient="records")
    # by instrument / exit means across both halves (to see structure)
    t = o.groupby(["k", "inst"]).agg(tickets=("entry", "size"), entry=("entry", "median")).reset_index()
    t["median entry Rs"] = t.entry.round(2)
    md += ["Median entry price by option choice:\n", md_table(t.drop(columns="entry")), ""]
    # index proxy
    rows = []
    for (k, x), g in ix.groupby(["k", "exit"]):
        for part in ("design", "holdout"):
            h = g[g.part == part]
            rows.append(dict(k=k, exit=x, part=part, days=len(h), mean_em=round(float(h.pnl_em.mean()), 3),
                             mean_pct_after_cost=round(float(h.pnl_pct.mean()), 3), win=pct((h.pnl_pct > 0).mean())))
    t = pd.DataFrame(rows)
    S["index_proxy"] = rows
    md += ["## Index-only diagnostic (futures-like: in at the next open, out on the exit; 0.05% round-trip cost)\n",
           md_table(t.rename(columns={"mean_em": "mean, straddles", "mean_pct_after_cost": "mean % after cost"})), ""]
    # 13 Oct
    x = o[(o.und == "NIFTY") & (o.day == pd.Timestamp("2026-10-13").date())]
    if len(x):
        tt = x[["k", "t", "inst", "strike", "entry"] + list(XS)].copy()
        tt["t"] = tt.t.map(lambda m: f"{(555 + m) // 60:02d}:{(555 + m) % 60:02d}")
        for c in XS:
            tt[c] = tt[c].map(rs)
        tt["entry"] = tt.entry.map(lambda v: f"{v:.2f}")
        md += ["## 13 Oct 2026 NIFTY\n", md_table(tt), ""]
        S["oct13"] = tt.to_dict(orient="records")
    else:
        md += ["## 13 Oct 2026 NIFTY\n", "No trigger: the index never moved 1.5 straddles from its 13:00 level by 14:45.", ""]
        S["oct13"] = None
    RES.mkdir(exist_ok=True)
    (RES / "conf.md").write_text("\n".join(md) + "\n")
    (RES / "conf.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


def main():
    if sys.argv[1:2] != ["report"]:
        with Pool(4) as p:
            parts = p.map(run_und, R.UNDS)
        pd.DataFrame([r for a, _ in parts for r in a]).to_parquet(R.OUT / "c_opt.parquet")
        pd.DataFrame([r for _, b in parts for r in b]).to_parquet(R.OUT / "c_idx.parquet")
    report()


if __name__ == "__main__":
    main()
