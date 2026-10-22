"""R98b (PREREG_B.md): exits that keep the hero, confirmation / basket entries, and two descriptive checks.

    python3 -I research/hunt/r98/hero2.py            # simulate -> $R98_OUT/b_{A,B,C1,C15}.parquet, then report
    python3 -I research/hunt/r98/hero2.py report     # report only -> results/hero2.md, results/hero2.json
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
import lock as K  # noqa: E402

RES = Path(__file__).resolve().parent / "results"
HOLDOUT_FROM = 2024
SEED = 98


def _exits():
    ex = []
    for t in (2.0, 3.0):
        for s in (0.0, 0.5):
            ex.append((f"E1_half{t:g}x_s{s:g}", "E1", dict(half=t, s=s)))
    for a in (2.0, 3.0, 5.0):
        for g in (0.3, 0.5):
            for s in (0.0, 0.5):
                ex.append((f"E2_arm{a:g}x_g{g:g}_s{s:g}", "E2", dict(arm=a, g=g, s=s)))
    for r in (0.5, 1.0):
        for s in (0.0, 0.5):
            ex.append((f"E3_idx{r:g}em_s{s:g}", "E3", dict(r=r, s=s)))
    for x in (30, 60):
        ex.append((f"E4_time{x}", "E4", dict(x=x, half=None)))
        ex.append((f"E4_time{x}_half2x", "E4", dict(x=x, half=2.0)))
    for s in (0.0, 0.5):
        ex.append((f"E12_half2x_trail5x_s{s:g}", "E12", dict(s=s)))
    return ex


EXITS = _exits()                                     # the 26 new exits
BASE = [("B_hold", None), ("B_stop50", 0.5), ("B_stop20", 0.8), ("B_lock30", "add30_s0"), ("B_lock30_stop20", "add30_s0.8")]
COLS = [n for n, _, _ in EXITS] + [n for n, _ in BASE]


def net_legs(und: str, e: float, legs) -> float:
    """legs: [(fraction of the quantity, sell price)]. Each leg is a sell order (none under Rs 0.10)."""
    q = L.BUDGET / e
    ex = L.exch(und)
    out = -L.BUDGET - (L.BROKER * (1 + L.GST) + L.BUDGET * ex * (1 + L.GST) + L.BUDGET * L.STAMP)
    for f, px in legs:
        if f <= 0 or px < 0.10:
            continue
        sell = f * q * max(px - L.TICK, 0.0)
        out += sell - (L.BROKER * (1 + L.GST) + sell * ex * (1 + L.GST) + sell * L.STT)
    return out


def first(mask: np.ndarray) -> int:
    return int(np.argmax(mask)) if mask.any() else -1


def stop_fill(o, j, level):
    return o[j] if (o[j] == o[j] and o[j] <= level) else level


def simulate(und, e, o, h, l, c, ixc, sign, em) -> np.ndarray:
    n = len(c)
    hh = np.where(np.isnan(h), -np.inf, h)
    pk = np.maximum(np.concatenate([[e], np.maximum.accumulate(hh)[:-1]]), e)      # peak BEFORE each minute
    lows = np.where(np.isnan(l), np.inf, l)
    okc = ~np.isnan(c)
    last = float(c[okc][-1]) if okc.any() else 0.0
    cc = np.where(okc, c, -np.inf)
    out = []

    def initial_stop(s, upto=n):
        """First minute < upto at which the resting stop s x entry is hit, and its fill."""
        if s <= 0:
            return -1, None
        j = first(lows[:upto] <= s * e)
        return (j, stop_fill(o, j, s * e)) if j >= 0 else (-1, None)

    def half_sale(t):
        j = first(cc >= t * e)
        return j

    def last_close_upto(j):
        ok = np.nonzero(okc[:j + 1])[0]
        return float(c[ok[-1]]) if len(ok) else None

    for name, kind, p in EXITS:
        if kind == "E1":
            jh = half_sale(p["half"])
            js, fs = initial_stop(p["s"], jh + 1 if jh >= 0 else n)
            if js >= 0:
                legs = [(1.0, fs)]
            elif jh >= 0:
                legs = [(0.5, p["half"] * e), (0.5, last)]
            else:
                legs = [(1.0, last)]
        elif kind in ("E2", "E3"):
            arm = p.get("arm", 2.0)
            ja = first(pk >= arm * e - 1e-9)
            js, fs = initial_stop(p["s"], ja if ja >= 0 else n)
            legs = None
            if js >= 0:
                legs = [(1.0, fs)]
            elif ja >= 0:
                if kind == "E2":
                    st = np.where(np.arange(n) >= ja, e + p["g"] * (pk - e), -np.inf)
                    jx = first(lows <= st)
                    legs = [(1.0, stop_fill(o, jx, st[jx]))] if jx >= 0 else [(1.0, last)]
                else:
                    xi = pd.Series(ixc).ffill().values
                    best = np.maximum.accumulate(np.where(np.isnan(xi), -np.inf, sign * xi))
                    retr = best - sign * xi
                    cond = (np.arange(n) >= ja) & (retr >= p["r"] * em)
                    jx = first(cond)
                    if jx >= 0:
                        later = np.nonzero(okc[jx:])[0]
                        legs = [(1.0, float(c[jx + later[0]]))] if len(later) else [(1.0, last)]
                    else:
                        legs = [(1.0, last)]
            else:
                legs = [(1.0, last)]
        elif kind == "E4":
            jt = min(p["x"] - 1, n - 1)
            jh = half_sale(p["half"]) if p["half"] else -1
            if jh >= 0 and jh <= jt:
                legs = [(0.5, p["half"] * e), (0.5, last)]
            else:
                pt = last_close_upto(jt)
                if pt is not None and pt < e:
                    legs = [(1.0, pt)]
                elif jh >= 0:
                    legs = [(0.5, p["half"] * e), (0.5, last)]
                else:
                    legs = [(1.0, last)]
        else:  # E12: half at 2x, the rest a give-back 0.5 trail armed at 5x
            jh = half_sale(2.0)
            js, fs = initial_stop(p["s"], jh + 1 if jh >= 0 else n)
            if js >= 0:
                legs = [(1.0, fs)]
            elif jh >= 0:
                ja = first(pk >= 5 * e - 1e-9)
                rest = last
                if ja >= 0:
                    st = np.where(np.arange(n) >= max(ja, jh + 1), e + 0.5 * (pk - e), -np.inf)
                    jx = first(lows <= st)
                    if jx >= 0:
                        rest = stop_fill(o, jx, st[jx])
                legs = [(0.5, 2.0 * e), (0.5, rest)]
            else:
                legs = [(1.0, last)]
        out.append(net_legs(und, e, legs))
    base_lock = dict(zip(K.COLS, K.simulate(und, e, o, h, l, c)))
    for name, b in BASE:
        if b is None:
            out.append(net_legs(und, e, [(1.0, last)]))
        elif isinstance(b, float):
            js, fs = initial_stop(b)
            out.append(net_legs(und, e, [(1.0, fs if js >= 0 else last)]))
        else:
            out.append(float(base_lock[b]))
    return np.array(out, dtype=np.float32)


def pick_near5(day, t, sp_t, side=None):
    """The OTM option(s) priced nearest Rs 5 within Rs 2-10 at minute t, traded in the last 3 minutes."""
    best = {}
    for (k, s), a in day.opts.items():
        if side and s != side:
            continue
        p = a[t, R.C]
        if np.isnan(p) or p < 2.0 or p > L.HI or not L.otm(k, s, sp_t) or not L.traded(a, t):
            continue
        d = abs(p - 5.0)
        if s not in best or d < best[s][0] or (d == best[s][0] and abs(k - sp_t) < abs(best[s][1] - sp_t)):
            best[s] = (d, k)
    return {s: v[1] for s, v in best.items()}


def ticket(day, k, s, t, em_t, uni, extra=None):
    a = day.opts[(k, s)]
    te = t + 1
    if te > L.T_END or np.isnan(a[te, R.O]):
        return None
    e = float(a[te, R.O]) + L.TICK
    seg = a[te:L.T_END + 1]
    if np.all(np.isnan(seg[:, R.C])):
        return None
    ixc = day.ix[te:L.T_END + 1, 3]
    sign = 1.0 if s == "C" else -1.0
    x = simulate(day.und, e, seg[:, R.O], seg[:, R.H], seg[:, R.L], seg[:, R.C], ixc, sign, em_t)
    r = dict(uni=uni, und=day.und, day=day.day, t=t, strike=k, side=s, entry=e,
             best_mult=float(np.nanmax(seg[:, R.C]) / e), **(extra or {}))
    r.update(dict(zip(COLS, x.tolist())))
    return r


def run_und(und: str):
    days = R.load(und)
    rows = pd.read_parquet(R.OUT / "rows.parquet", columns=["und", "day", "t", "strike", "side"],
                           filters=[("und", "==", und)])
    byday = {d: g for d, g in rows.groupby("day")}
    A, B, C = [], [], []
    t13 = R.minute(13, 0)
    for day in days:
        sp = L.spot(day)
        em, _ = L.straddle(day)
        for _, r in byday.get(day.day, pd.DataFrame()).iterrows():
            x = ticket(day, float(r.strike), r.side, int(r.t), em[int(r.t)], "A")
            if x:
                A.append(x)
        for hh, mm in ((13, 30), (14, 30)):
            t = R.minute(hh, mm)
            if np.isnan(sp[t]):
                continue
            for s, k in pick_near5(day, t, sp[t]).items():
                x = ticket(day, k, s, t, em[t], "B", dict(slot=f"{hh}:{mm:02d}"))
                if x:
                    B.append(x)
        s13, e13 = sp[t13], em[t13]
        if np.isnan(s13) or np.isnan(e13) or e13 <= 0:
            continue
        hi, lo = day.ix[:, 1], day.ix[:, 2]
        for kk, uni in ((1.0, "C1"), (1.5, "C15")):
            for t in range(R.minute(13, 15), R.minute(14, 45) + 1):
                if np.isnan(sp[t]) or abs(sp[t] - s13) < kk * e13:
                    continue
                side = "C" if sp[t] > s13 else "P"
                sign = 1.0 if side == "C" else -1.0
                # D3: from the trigger, does the index go on to 2.5 straddles from 13:00 the same way by 15:20?
                ext = (np.nanmax(hi[t + 1:L.T_END + 1]) - s13) if sign > 0 else (s13 - np.nanmin(lo[t + 1:L.T_END + 1]))
                ks = pick_near5(day, t, sp[t], side)
                x = ticket(day, ks[side], side, t, em[t], uni, dict(cont25=bool(ext >= 2.5 * e13))) if ks else None
                C.append(x if x else dict(uni=uni, und=day.und, day=day.day, t=t, side=side, cont25=bool(ext >= 2.5 * e13),
                                          entry=np.nan))
                break
    print(und, len(A), len(B), len(C), flush=True)
    return A, B, C


# --------------------------------------------------------------------------------------------------------- report
def pct(x, d=0):
    return f"{x * 100:.{d}f}%" if x == x else "-"


def rs(x):
    return f"{x:,.0f}" if x == x else "-"


def md_table(df):
    cols = list(df.columns)
    out = ["| " + " | ".join(map(str, cols)) + " |", "|" + "---|" * len(cols)]
    out += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for _, r in df.iterrows()]
    return "\n".join(out)


def summ(g: pd.DataFrame, col: str, rng=None) -> dict:
    x = g[col].values
    by = g.groupby(["und", "day"])[col].sum().sort_values(ascending=False)
    yrs = g.groupby(g.yr)[col].sum()
    r = dict(n=len(g), win=float((x > 0).mean()), mean=float(x.mean()),
             wo5=float((x.sum() - by.head(5).sum()) / len(g)), years_pos=int((yrs > 0).sum()), years=int(len(yrs)))
    if rng is not None:
        dsum = by.values
        dn = g.groupby(["und", "day"]).size().reindex(by.index).values
        bs = []
        for _ in range(2000):
            i = rng.integers(0, len(dsum), len(dsum))
            bs.append(dsum[i].sum() / dn[i].sum())
        r["lo95"], r["hi95"] = float(np.percentile(bs, 2.5)), float(np.percentile(bs, 97.5))
    return r


def report():
    S, md = {}, ["# R98b: exits that keep the hero, confirmation entries, and two patterns (generated by hero2.py)\n"]
    unis = {}
    for u in ("A", "B", "C1", "C15"):
        df = pd.read_parquet(R.OUT / f"b_{u}.parquet")
        df["yr"] = pd.to_datetime(df.day).dt.year
        df["part"] = np.where(df.yr >= HOLDOUT_FROM, "holdout", "design")
        unis[u] = df
    names = {"A": "U-A every ticket", "B": "U-B daily basket (13:30 + 14:30, CE + PE near Rs 5)",
             "C1": "U-C1 confirmation (index moved 1 straddle from 13:00)", "C15": "U-C15 confirmation (1.5 straddles)"}
    # ---- design: every exit, every universe
    sel = {}
    for u, df in unis.items():
        d = df[(df.part == "design") & df.entry.notna()]
        t = []
        for c in COLS:
            s = summ(d, c)
            t.append(dict(exit=c, tickets=s["n"], win=pct(s["win"], 1), mean=rs(s["mean"]), wo5=rs(s["wo5"]),
                          years=f"{s['years_pos']}/{s['years']}", _m=s["mean"]))
        t = pd.DataFrame(t).sort_values("_m", ascending=False)
        new = t[~t.exit.str.startswith("B_")]
        sel[u] = new.iloc[0].exit
        S[f"design_{u}"] = t.drop(columns="_m").to_dict(orient="records")
        md += [f"## Design 2021-23, {names[u]}: every exit, best first\n",
               md_table(t.drop(columns="_m").rename(columns={"wo5": "mean w/o 5 best days", "years": "years > 0"})), ""]
    # ---- holdout: the selected exits, read once
    rng = np.random.default_rng(SEED)
    t = []
    for u, c in sel.items():
        h = unis[u][(unis[u].part == "holdout") & unis[u].entry.notna()]
        s = summ(h, c, rng)
        b = summ(h, "B_hold")
        ok = s["mean"] > 0 and s["lo95"] > 0 and s["wo5"] > 0 and s["years_pos"] == s["years"]
        t.append({"universe": names[u], "exit chosen on design": c, "holdout tickets": s["n"], "win": pct(s["win"], 1),
                  "mean Rs": rs(s["mean"]), "bootstrap 95%": f"{rs(s['lo95'])} .. {rs(s['hi95'])}",
                  "w/o 5 best days": rs(s["wo5"]), "years > 0": f"{s['years_pos']}/{s['years']}",
                  "hold-to-15:20 baseline": rs(b["mean"]), "resolves it?": "YES" if ok else "no"})
    S["holdout_selected"] = t
    md += ["## HOLDOUT 2024-26 (read once): the exit each universe chose on design\n", md_table(pd.DataFrame(t)), ""]
    # ---- holdout, all exits, transparency (U-A, U-B, U-C1)
    for u in ("A", "B", "C1"):
        h = unis[u][(unis[u].part == "holdout") & unis[u].entry.notna()]
        t = []
        for c in COLS:
            s = summ(h, c)
            t.append(dict(exit=c, win=pct(s["win"], 1), mean=rs(s["mean"]), wo5=rs(s["wo5"]), years=f"{s['years_pos']}/{s['years']}",
                          _m=s["mean"]))
        t = pd.DataFrame(t).sort_values("_m", ascending=False).drop(columns="_m")
        S[f"holdout_all_{u}"] = t.to_dict(orient="records")
        md += [f"Holdout, {names[u]}, every exit (for transparency; the test is the row above):\n",
               md_table(t.rename(columns={"wo5": "mean w/o 5 best days", "years": "years > 0"})), ""]
    # ---- 13 Oct ticket
    a = unis["A"]
    x = a[(a.und == "NIFTY") & (a.day == pd.Timestamp("2026-10-13").date()) & (a.t == R.minute(13, 30)) & (a.strike == 22650.0)
          & (a.side == "C")]
    if len(x):
        r = x.iloc[0]
        t = pd.DataFrame([dict(exit=c, net=rs(r[c])) for c in COLS])
        S["oct13"] = t.to_dict(orient="records")
        md += [f"## 13 Oct 2026, NIFTY 22650 CE bought at 13:30 (entry Rs {r.entry:.2f}): what each exit makes\n", md_table(t), ""]
    # ---- D3 continuation
    why = pd.read_parquet(R.OUT / "why.parquet")
    w13 = why[why.tau == "13:00"]
    unc = float(((w13.up_em >= 2.5) | (w13.dn_em >= 2.5)).mean())
    t = []
    for u in ("C1", "C15"):
        c = unis[u]
        for part in ("design", "holdout", "all"):
            g = c if part == "all" else c[c.part == part]
            t.append(dict(trigger=names[u], part=part, days=len(g), cont=pct(g.cont25.mean(), 1)))
    S["d3"] = dict(uncond=unc, rows=t)
    md += ["## D3: once the index has moved k straddles from 13:00, does it reach 2.5 the same way by 15:20?\n",
           f"Unconditional (from 13:00, either way): {pct(unc, 1)} of expiries.\n",
           md_table(pd.DataFrame(t).rename(columns={"cont": "goes on to 2.5 straddles"})), ""]
    # ---- D1 clustering and D2 morning activity
    w = w13.copy()
    w["hero"] = w[["best_call", "best_put"]].max(axis=1) >= 10
    w["best_em"] = w[["up_em", "dn_em"]].max(axis=1)
    w = w.sort_values("day").reset_index(drop=True)
    w["dts"] = pd.to_datetime(w.day)
    prev_hero, prev_em = [], []
    for i, r in w.iterrows():
        p = w[(w.dts < r.dts) & (w.dts >= r.dts - pd.Timedelta(days=3))]
        if len(p):
            last = p[p.dts == p.dts.max()]
            prev_hero.append(bool(last.hero.any()))
            prev_em.append(float(last.best_em.max()))
        else:
            prev_hero.append(np.nan)
            prev_em.append(np.nan)
    w["prev_hero"], w["prev_em"] = prev_hero, prev_em
    t = []
    for name, m in (("previous expiry (any index, <= 3 days) had a hero", w.prev_hero == True),
                    ("previous expiry had no hero", w.prev_hero == False)):
        t.append(dict(state=name, days=int(m.sum()), hero=pct(w[m].hero.mean(), 1)))
    for lo, hi in ((0, 1), (1, 1.5), (1.5, 2), (2, 2.5), (2.5, 99)):
        m = (w.prev_em >= lo) & (w.prev_em < hi)
        t.append(dict(state=f"previous expiry's best move {lo}-{hi if hi < 99 else '+'} straddles", days=int(m.sum()),
                      hero=pct(w[m].hero.mean(), 1)))
    days = pd.read_parquet(R.OUT / "days.parquet")
    dd = days.merge(w13[["und", "day", "em_pct"]], on=["und", "day"]).merge(w[["und", "day", "hero"]], on=["und", "day"])
    dd["rviv"] = dd.am_range_pct / dd.em_pct
    for lo, hi in ((0, 2), (2, 3), (3, 4), (4, 6), (6, 99)):
        m = (dd.rviv >= lo) & (dd.rviv < hi)
        t.append(dict(state=f"09:15-13:00 range / 13:00 straddle {lo}-{hi if hi < 99 else '+'}", days=int(m.sum()),
                      hero=pct(dd[m].hero.mean(), 1)))
    S["d1d2"] = t
    md += [f"## D1 / D2: clustering and morning activity (hero = a 10x from options cheap at 13:00; base {pct(w.hero.mean(), 1)})\n",
           md_table(pd.DataFrame(t).rename(columns={"hero": "hero rate"})), ""]
    RES.mkdir(exist_ok=True)
    (RES / "hero2.md").write_text("\n".join(md) + "\n")
    (RES / "hero2.json").write_text(json.dumps(S, indent=1, default=str))
    print("\n".join(md))


def main():
    if sys.argv[1:2] != ["report"]:
        with Pool(4) as p:
            parts = p.map(run_und, R.UNDS)
        for j, u in enumerate(("A", "B", "C")):
            df = pd.DataFrame([x for part in parts for x in part[j]])
            if u == "C":
                for v in ("C1", "C15"):
                    df[df.uni == v].reset_index(drop=True).to_parquet(R.OUT / f"b_{v}.parquet")
            else:
                df.to_parquet(R.OUT / f"b_{u}.parquet")
    report()


if __name__ == "__main__":
    main()
