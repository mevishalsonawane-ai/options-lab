"""R98f (PREREG_F.md): order flow at the hero move.

    python3 -I research/hunt/r98/orderflow.py     # -> results/orderflow.md, results/orderflow.json
"""
from __future__ import annotations

import json
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.stats import mannwhitneyu
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
from hero2 import md_table, pct  # noqa: E402

RES = Path(__file__).resolve().parent / "results"
FUT = R.LAKE / "fut" / "fut_1m_live_index.parquet"
SEED = 98
METRICS = ["vol_x", "oi_chg", "upvol", "side_vol_x", "opp_vol_x", "iv_chg"]


def flow(day, key, t0):
    """PRE (t0-30..t0-1) and EARLY (t0..t0+9) flow metrics of option `key`, against its BASE (09:15..t0-31)."""
    k, s = key
    a = day.opts[key]
    v = np.nan_to_num(a[:, R.V])
    c = L.ffill(a[:, R.C], 3)
    oi = L.ffill(a[:, R.OI], 30)
    iv = L.ffill(a[:, R.IV], 5)
    side = sum(np.nan_to_num(x[:, R.V]) for (kk, ss), x in day.opts.items() if ss == s)
    opp = sum(np.nan_to_num(x[:, R.V]) for (kk, ss), x in day.opts.items() if ss != s)
    b0, b1 = 0, t0 - 30
    if b1 < 10:
        return None
    out = {}
    for w, (w0, w1) in (("pre", (t0 - 30, t0)), ("early", (t0, min(t0 + 10, L.T_END + 1)))):
        base = lambda x: x[b0:b1].mean()
        win = lambda x: x[w0:w1].mean()
        out[f"{w}_vol_x"] = np.log((win(v) + 1) / (base(v) + 1))
        o0, o1 = oi[w0 - 1], oi[w1 - 1]
        out[f"{w}_oi_chg"] = o1 / o0 - 1 if o0 and o0 == o0 and o1 == o1 else np.nan
        dc = np.diff(c[w0 - 1:w1])
        vv = v[w0:w1]
        ok = ~np.isnan(dc)
        out[f"{w}_upvol"] = vv[ok][dc[ok] > 0].sum() / vv[ok].sum() if vv[ok].sum() > 0 else np.nan
        out[f"{w}_side_vol_x"] = np.log((win(side) + 1) / (base(side) + 1))
        out[f"{w}_opp_vol_x"] = np.log((win(opp) + 1) / (base(opp) + 1))
        i0, i1 = iv[w0 - 1], iv[w1 - 1]
        out[f"{w}_iv_chg"] = i1 - i0 if i0 == i0 and i1 == i1 else np.nan
    return out


def run_und(und):
    ev = pd.read_parquet(R.OUT / "events.parquet")
    h = ev[(ev.und == und) & (ev.mult >= 10)]
    days = R.load(und)
    byday = {d.day: d for d in days}
    rng = np.random.default_rng(SEED + R.UNDS.index(und))
    sp = {d.day: L.spot(d) for d in days}
    out = []
    for _, r in h.iterrows():
        d = byday[r.day]
        t0 = int(r.t0)
        f = flow(d, (float(r.strike), r.side), t0)
        if f is None:
            continue
        out.append(dict(und=und, day=r.day, grp="hero", mult=r.mult, t0=t0, **f))
        found = 0
        for j in rng.permutation(len(days)):
            o = days[j]
            if o.day == r.day or found >= 5:
                if found >= 5:
                    break
                continue
            s0 = sp[o.day][t0]
            if np.isnan(s0):
                continue
            cands = []
            for (k, s), a in o.opts.items():
                p = a[t0, R.C]
                if s != r.side or np.isnan(p) or p < 0.5 * r.p0 or p > 2 * r.p0 or not L.otm(k, s, s0) or not L.traded(a, t0):
                    continue
                fm = np.nanmax(a[t0 + 1:L.T_END + 1, R.C]) if np.any(~np.isnan(a[t0 + 1:L.T_END + 1, R.C])) else np.nan
                if fm == fm and fm < 2 * p:
                    cands.append((k, s))
            if not cands:
                continue
            key = cands[rng.integers(len(cands))]
            g = flow(o, key, t0)
            if g is None:
                continue
            out.append(dict(und=und, day=o.day, grp="control", mult=np.nan, t0=t0, **g))
            found += 1
    print(und, len(out), flush=True)
    return out


def futures_part():
    f = pd.read_parquet(FUT)
    ts = pd.to_datetime(f.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    f["d"], f["m"] = ts.dt.date, ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    f["exp"] = pd.to_datetime(f.expiry).dt.date
    f = f[(f.m >= 0) & (f.m < R.N)]
    days = pd.read_parquet(R.OUT / "days.parquet")
    ev = pd.read_parquet(R.OUT / "events.parquet")
    top = ev[ev.mult >= 10].sort_values("mult").groupby(["und", "day"]).tail(1).set_index(["und", "day"])
    rows = []
    t0s = top.t0.values.astype(int)
    rng = np.random.default_rng(SEED)
    for (u, d), g in f.groupby(["symbol", "d"]):
        if u not in R.UNDS:
            continue
        dd = days[(days.und == u) & (days.day == d)]
        if dd.empty:
            continue                                                  # not an expiry day of this index
        near = g[g.exp >= d]
        if near.empty:
            continue
        near = near[near.exp == near.exp.min()]
        v = np.zeros(R.N); oi = np.full(R.N, np.nan); c = np.full(R.N, np.nan)
        v[near.m.values] = near.volume.values; oi[near.m.values] = near.oi.values; c[near.m.values] = near.close.values
        oi, c = L.ffill(oi, 30), L.ffill(c, 5)
        hero = (u, d) in top.index
        if hero:
            t0, t1, side = int(top.loc[(u, d)].t0), int(top.loc[(u, d)].t1), top.loc[(u, d)].side
        else:
            t0 = int(rng.choice(t0s)); t1 = min(t0 + 60, L.T_END); side = "C" if c[t1] >= c[t0] else "P"
        if t0 < 45:
            continue
        sign = 1 if side == "C" else -1
        base = v[:t0 - 30].mean()
        rows.append(dict(und=u, day=d, hero=hero, pre_vol_x=np.log((v[t0 - 30:t0].mean() + 1) / (base + 1)),
                         run_vol_x=np.log((v[t0:t1 + 1].mean() + 1) / (base + 1)),
                         run_move=sign * (c[t1] / c[t0] - 1) * 100, run_oi_chg=oi[t1] / oi[t0] - 1 if oi[t0] else np.nan,
                         pre_oi_chg=oi[t0 - 1] / oi[t0 - 31] - 1 if oi[t0 - 31] else np.nan))
    fr = pd.DataFrame(rows)
    # 13 Oct case
    g = f[(f.symbol == "NIFTY") & (f.d == pd.Timestamp("2026-10-13").date())]
    g = g[g.exp == g.exp.min()].set_index("m")
    day = [x for x in R.load("NIFTY") if x.day == pd.Timestamp("2026-10-13").date()][0]
    ce, ce2 = day.opts[(22650.0, "C")], day.opts[(22700.0, "C")]
    case = []
    for t in range(R.minute(12, 30), R.minute(15, 25) + 1, 5):
        w = range(t - 4, t + 1)
        case.append({"time": f"{(555 + t) // 60:02d}:{(555 + t) % 60:02d}",
                     "fut close": f"{g.close.reindex(w).ffill().iloc[-1]:.1f}",
                     "fut vol (5 min)": int(g.volume.reindex(w).fillna(0).sum()),
                     "fut OI": f"{g.oi.reindex(w).ffill().iloc[-1]:,.0f}",
                     "22650 CE": f"{L.ffill(ce[:, R.C], 3)[t]:.2f}", "22650 CE vol (5 min)": int(np.nansum(ce[t - 4:t + 1, R.V])),
                     "22650 CE OI": f"{L.ffill(ce[:, R.OI], 30)[t]:,.0f}",
                     "22700 CE": f"{L.ffill(ce2[:, R.C], 3)[t]:.2f}", "22700 CE OI": f"{L.ffill(ce2[:, R.OI], 30)[t]:,.0f}"})
    return fr, case


def main():
    with Pool(4) as p:
        parts = p.map(run_und, R.UNDS)
    df = pd.DataFrame([r for part in parts for r in part])
    df.to_parquet(R.OUT / "orderflow.parquet")
    S, md = {}, ["# R98f: order flow at the hero move (generated by orderflow.py)\n"]
    hero, ctrl = df[df.grp == "hero"], df[df.grp == "control"]
    md.append(f"Heroes (≥ 10×): {len(hero)} option-days; matched controls (same index, side and minute on other days, "
              f"similar price, never reached 2×): {len(ctrl)}.\n")
    t = []
    for w in ("pre", "early"):
        for m in METRICS:
            c = f"{w}_{m}"
            a, b = hero[c].dropna(), ctrl[c].dropna()
            p = mannwhitneyu(a, b).pvalue if len(a) > 5 and len(b) > 5 else np.nan
            y = np.r_[np.ones(len(a)), np.zeros(len(b))]
            auc = roc_auc_score(y, np.r_[a.values, b.values]) if len(a) and len(b) else np.nan
            t.append(dict(window=w, metric=m, hero_median=round(float(a.median()), 3), control_median=round(float(b.median()), 3),
                          mw_p=float(p), auc=round(float(max(auc, 1 - auc)), 3), direction="hero higher" if auc > 0.5 else "hero lower"))
    S["part1"] = t
    tt = pd.DataFrame(t)
    tt["mw_p"] = tt.mw_p.map(lambda x: f"{x:.1e}")
    md += ["## Part 1: option flow before (PRE, 30 min) and at the start (EARLY, 10 min) of the run\n",
           "vol_x / side_vol_x / opp_vol_x are log(window volume per minute / the morning's), so 0 = normal and 0.69 = "
           "double. upvol = the share of volume on up-close minutes. AUC: how well the metric separates heroes from "
           "duds (0.5 = not at all).\n", md_table(tt), ""]
    pre_ok = [r for r in t if r["window"] == "pre" and r["auc"] >= 0.65 and r["mw_p"] < 1e-3]
    S["pre_candidates"] = pre_ok
    md += [f"PRE-window metrics meeting the pre-registered bar (AUC ≥ 0.65 and p < 0.001): "
           f"{', '.join(r['metric'] for r in pre_ok) if pre_ok else 'none'}.", ""]
    fr, case = futures_part()
    S["futures_days"] = len(fr)
    g = fr.groupby("hero").agg(days=("day", "size"), pre_vol_x=("pre_vol_x", "median"), run_vol_x=("run_vol_x", "median"),
                               run_move=("run_move", "median"), run_oi_chg=("run_oi_chg", "median"),
                               pre_oi_chg=("pre_oi_chg", "median")).round(4).reset_index()
    hr = fr[fr.hero]
    cov = (hr.run_oi_chg < 0).mean() if len(hr) else np.nan
    S["futures"] = g.to_dict(orient="records")
    S["hero_runs_oi_falling"] = float(cov)
    md += ["## Part 2: index-futures flow (29 Jul - 19 Oct 2026; near-month futures)\n", md_table(g), "",
           f"Hero runs with futures OI falling (covering) rather than rising (fresh positions): {pct(cov)} of {len(hr)}.", "",
           "## 13 Oct 2026, NIFTY: futures and the 22650 / 22700 CE, every 5 minutes\n", md_table(pd.DataFrame(case)), ""]
    S["oct13"] = case
    RES.mkdir(exist_ok=True)
    (RES / "orderflow.md").write_text("\n".join(md) + "\n")
    (RES / "orderflow.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


if __name__ == "__main__":
    main()
