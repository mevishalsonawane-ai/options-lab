#!/usr/bin/env python3
"""R28b (PREREG_B.md): the 30 one-minute index candles before each arm's decision, alone and with the GEX sign, as a
pattern filter. Design years only (the R28 holdouts and embargoes are excluded); walk-forward; BH over 378 patterns.

    python3 research/hunt/r28/patterns.py      # -> results/patterns.json, results/patterns.md, forward_candidates.json

Needs R28's caches (<OUT>/trades_<arm>.parquet, gex_<UND>.parquet) and the lake's index_1m files.
"""
from __future__ import annotations

import json
from datetime import date

import numpy as np
import pandas as pd

import r28cfg as K
import study as S

WIN = 30
FEATS = ("c_ret", "c_rng", "c_body", "c_loc", "c_wick", "c_bars", "c_late")
ARMS = {"orb": "A", "fade": "A", "liq": "A", "night": "A", "h0": "H", "l3": "H"}
MIN_TRAIN = {"A": 30, "H": 10}
MIN_DESIGN = {"A": 100, "H": 30}
FOLDS = {"A": [({2023}, {2024}), ({2023, 2024}, {2025}), ({2023, 2024, 2025}, {2026})],
         "H": [({2021, 2022}, {2023}), ({2021, 2022, 2023}, {2025, 2026})]}


# ------------------------------------------------------------------------------------------------ features
def window_features(ix: np.ndarray, row: int, side: int, atr: float) -> dict:
    """The 7 candle features of the 30 bars ending at `row` (rows row-29 .. row only). NaN when the window is short."""
    out = {f: np.nan for f in FEATS}
    a = row - WIN + 1
    if a < 0 or not np.isfinite(atr) or atr <= 0 or side == 0:
        return out
    w = ix[a:row + 1]
    ok = np.isfinite(w[:, 3])
    if ok.sum() < 25 or not np.isfinite(w[0, 0]) or not np.isfinite(w[-1, 3]):
        return out
    o, c = w[0, 0], w[-1, 3]
    hi, lo = np.nanmax(w[:, 1]), np.nanmin(w[:, 2])
    rng = hi - lo
    out["c_ret"] = side * (c - o) / atr
    out["c_rng"] = rng / atr
    if rng > 0:
        out["c_body"] = abs(c - o) / rng
        out["c_loc"] = (c - lo) / rng if side > 0 else (hi - c) / rng
        out["c_wick"] = (hi - max(o, c)) / rng if side > 0 else (min(o, c) - lo) / rng
    bars = 0
    for k in range(6):
        b = w[5 * k:5 * k + 5]
        if np.isfinite(b[0, 0]) and np.isfinite(b[-1, 3]):
            bars += int(side * (b[-1, 3] - b[0, 0]) > 0)
    out["c_bars"] = float(bars)
    c10 = w[-11, 3] if np.isfinite(w[-11, 3]) else np.nan
    out["c_late"] = side * (c - c10) / atr
    return out


def attach(t: pd.DataFrame, ix_by: dict, atr_by: dict) -> pd.DataFrame:
    rows = []
    for r in t.itertuples():
        ix = ix_by[r.und].get(r.day)
        f = window_features(ix, int(r.row), int(np.sign(r.side)), atr_by[r.und].get(r.day, np.nan)) if ix is not None \
            else {k: np.nan for k in FEATS}
        rows.append(f)
    return pd.concat([t.reset_index(drop=True), pd.DataFrame(rows)], axis=1)


# ------------------------------------------------------------------------------------------------ patterns
def patterns() -> list[tuple]:
    out = []
    for f in FEATS:
        for b in (0, 1, 2):
            out.append((f, b, None))
            out.append((f, b, "neg"))
            out.append((f, b, "pos"))
    return out


def pid(p) -> str:
    f, b, g = p
    return f"{f}:{['low', 'mid', 'high'][b]}" + (f"&S1{'<0' if g == 'neg' else '>=0'}" if g else "")


def cuts_of(t: pd.DataFrame) -> dict:
    return {f: tuple(np.nanquantile(t[f].astype(float), [1 / 3, 2 / 3])) if t[f].notna().sum() >= 6 else (np.nan, np.nan)
            for f in FEATS}


def keep(t: pd.DataFrame, p, cuts) -> np.ndarray:
    """Kept trades: in the tercile (and the GEX side); a trade with a missing window or GEX is kept (cannot act)."""
    f, b, g = p
    x = t[f].values.astype(float)
    lo, hi = cuts[f]
    tb = np.where(x <= lo, 0, np.where(x <= hi, 1, 2))
    k = ~np.isfinite(x) | (tb == b)
    if g:
        v = t.near_net_cr.values.astype(float)
        k &= ~np.isfinite(v) | ((v < 0) if g == "neg" else (v >= 0))
        k |= ~np.isfinite(x)                        # a missing window is always kept (PREREG_B)
    return k


def delta(t, k, days) -> float:
    return -float(t.net.values[~k].sum()) / len(days) if days else 0.0


# ------------------------------------------------------------------------------------------------ run
def run_arm(arm: str, ix_by, atr_by) -> dict:
    fam = ARMS[arm]
    t = S.trades(arm)
    dd = S.period_days(arm, fam, "design")
    t = attach(S.subset(t, dd), ix_by, atr_by)
    res = dict(trades=int(len(t)), sessions=len(dd), missing=int(t.c_ret.isna().sum()))
    cuts = cuts_of(t)
    full = {}
    for p in patterns():
        k = keep(t, p, cuts)
        pv, mean = S.twin_p(t, k, dd, n=1000, tag=f"{arm}{pid(p)}")
        full[pid(p)] = dict(kept=int(k.sum()), delta=delta(t, k, dd), twin_p=pv, twin_mean=mean)
    for v in full.values():
        v["q378"] = None
    res["full"] = full
    res["cuts_full"] = {f: list(c) for f, c in cuts.items()}
    folds, oos_keep, oos_days = [], [], []
    for tr_y, te_y in FOLDS[fam]:
        trd = [d for d in dd if d.year in tr_y]
        ted = [d for d in dd if d.year in te_y]
        tt, te = t[t.day.map(lambda d: d.year in tr_y)], t[t.day.map(lambda d: d.year in te_y)]
        if not len(tt) or not len(te):
            continue
        c = cuts_of(tt)
        best, bv = None, -np.inf
        for p in patterns():
            k = keep(tt, p, c)
            if k.sum() >= MIN_TRAIN[fam] and delta(tt, k, trd) > bv:
                best, bv = p, delta(tt, k, trd)
        if best is None:
            continue
        kt = keep(te, best, c)
        folds.append(dict(train=sorted(tr_y), test=sorted(te_y), pattern=pid(best), train_delta=bv,
                          test_delta=delta(te, kt, ted), test_trades=int(len(te)), test_kept=int(kt.sum()),
                          test_sessions=len(ted), cuts={f: list(v) for f, v in c.items()}))
        oos_keep.append(pd.Series(kt, index=te.index))
        oos_days += ted
    res["folds"] = folds
    if folds:
        idx = pd.concat(oos_keep)
        te = t.loc[idx.index]
        k = idx.values
        pv, mean = S.twin_p(te, k, oos_days, n=K.TWINS, tag=f"{arm}oos")
        big = float(te.net.quantile(0.95))
        res["oos"] = dict(base=S.metrics(te, oos_days), kept=S.metrics(te[k], oos_days, big, te[~k]),
                          delta=delta(te, k, oos_days), twin_p=pv, twin_mean=mean,
                          folds_positive=int(sum(f["test_delta"] > 0 for f in folds)), n_folds=len(folds),
                          same_pattern=int(max(pd.Series([f["pattern"] for f in folds]).value_counts())))
    return res


def main():
    import describe as DS
    ix_by, atr_by = {}, {}
    for u in K.UNDS:
        ix_by[u] = DS.index_days(u)
        atr_by[u] = DS.atr14(ix_by[u])
    out = {arm: run_arm(arm, ix_by, atr_by) for arm in ARMS}
    allp = {f"{a} {k}": v["twin_p"] for a, r in out.items() for k, v in r["full"].items()}
    q = S.bh(allp)
    for a, r in out.items():
        for k, v in r["full"].items():
            v["q378"] = q.get(f"{a} {k}")
    cand = []
    for a, r in out.items():
        fam = ARMS[a]
        o = r.get("oos")
        if not o:
            r["gates"] = None
            continue
        top = pd.Series([f["pattern"] for f in r["folds"]]).value_counts().index[0]
        g = dict(g1=bool(o["delta"] > 0 and (o["twin_p"] or 1) < 0.05), g2=bool(o["folds_positive"] >= 2),
                 g3=bool(o["same_pattern"] >= 2), g4=bool((r["full"][top]["q378"] or 1) < K.BH_Q),
                 g5=bool(r["full"][top]["kept"] >= MIN_DESIGN[fam]), pattern=top)
        r["gates"] = g
        if all(g[k] for k in ("g1", "g2", "g3", "g4", "g5")):
            cand.append(dict(arm=a, pattern=top, cuts=r["cuts_full"], frozen=str(date.today())))
    res = dict(m=len(allp), arms=out, candidates=cand)
    K.RESULTS.mkdir(exist_ok=True)
    json.dump(S._js(res), open(K.RESULTS / "patterns.json", "w"), indent=1)
    json.dump(S._js(cand), open(K.RESULTS / "forward_candidates.json", "w"), indent=1)
    (K.RESULTS / "patterns.md").write_text(md(res))
    for a, r in out.items():
        print(a, r["trades"], r.get("oos", {}).get("delta"), r.get("oos", {}).get("twin_p"), r.get("gates"))
    print("candidates", cand)


def md(res) -> str:
    L = ["# R28b: 30-minute pre-decision candle patterns (+ GEX sign), design years only (generated by patterns.py)", "",
         f"{res['m']} patterns, BH over all of them (q < 0.10). Forward candidates: "
         f"{[c['arm'] + ' ' + c['pattern'] for c in res['candidates']] or 'none'}.", "",
         "## Walk-forward: the pattern chosen on the training years, scored on the next year", "",
         "| arm | train | test | pattern chosen | train change Rs/day | test change Rs/day | test trades / kept | test sessions |",
         "|---|---|---|---|---|---|---|---|"]
    for a, r in res["arms"].items():
        for f in r["folds"]:
            L.append(f"| {a} | {f['train'][0]}-{f['train'][-1]} | {'/'.join(map(str, f['test']))} | `{f['pattern']}` | "
                     f"{f['train_delta']:+.1f} | {f['test_delta']:+.1f} | {f['test_trades']} / {f['test_kept']} | "
                     f"{f['test_sessions']} |")
    L += ["", "## Out of sample, folds joined", "",
          "| arm | trades base / kept | win % base / kept | avg win / loss kept | Rs/day base / kept | change | % green days "
          "base / kept | worst day kept | max DD base / kept | big winners cut | twin p | folds + | same pattern | gates 1-5 |",
          "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for a, r in res["arms"].items():
        o, g = r.get("oos"), r.get("gates")
        if not o:
            continue
        b, k = o["base"], o["kept"]
        f = lambda x, n=0: "-" if x is None else f"{x:,.{n}f}"  # noqa: E731
        L.append(f"| {a} | {b['trades']} / {k['trades']} | {f(b['win_pct'], 1)} / {f(k['win_pct'], 1)} | "
                 f"{f(k['avg_win'])} / {f(k['avg_loss'])} | {f(b['per_day'], 1)} / {f(k['per_day'], 1)} | {o['delta']:+.1f} | "
                 f"{f(b['green_days_pct'], 1)} / {f(k['green_days_pct'], 1)} | {f(k['worst_day'])} | "
                 f"{f(b['max_dd'])} / {f(k['max_dd'])} | {k.get('big_winners_cut', '-')} | {f(o['twin_p'], 3)} | "
                 f"{o['folds_positive']}/{o['n_folds']} | {o['same_pattern']} | "
                 f"{''.join('Y' if g[x] else 'n' for x in ('g1', 'g2', 'g3', 'g4', 'g5'))} |")
    L += ["", "## Every pattern on all design trades (cuts fitted on all design trades)", "",
          "| arm | pattern | kept | change Rs/day | twin p | q (m 378) |", "|---|---|---|---|---|---|"]
    for a, r in res["arms"].items():
        best = sorted(r["full"].items(), key=lambda kv: (kv[1]["twin_p"] if kv[1]["twin_p"] is not None else 2))[:8]
        for k, v in best:
            tp = "-" if v["twin_p"] is None else f"{v['twin_p']:.3f}"
            qq = "-" if v["q378"] is None else f"{v['q378']:.3f}"
            L.append(f"| {a} | `{k}` | {v['kept']} | {v['delta']:+.1f} | {tp} | {qq} |")
    L.append("")
    L.append("(Only each arm's 8 lowest-p patterns are listed here; all 378 are in patterns.json.)")
    return "\n".join(L) + "\n"


if __name__ == "__main__":
    main()
