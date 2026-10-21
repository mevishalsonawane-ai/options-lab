"""R40 analyses (PREREG.md): pattern frequency before zero-to-hero (option + index + chain), the 30-candle shape atlas,
walk-forward combinations, and the four trading rules.

    python3 research/hunt/r40/r40study.py      # -> results/results.json
"""
from __future__ import annotations

import json
import sys
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r39", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r39study as S32  # noqa: E402  (r25-r39 on the path)
import r40flags as Z  # noqa: E402
import r25rules as RU  # noqa: E402

RESULTS = HERE / "results"
TEST_YEARS = S32.TEST_YEARS
K_SHAPES = 8
SEED = 33


def chain_flags(df: pd.DataFrame) -> pd.DataFrame:
    """The chain / pricing flags from R39's features (thresholds fixed in PREREG.md)."""
    return pd.DataFrame({
        "ch_pcr_gt1.2": df.pcr > 1.2, "ch_pcr_lt0.7": df.pcr < 0.7, "ch_pcr_rising": df.pcr_ch15 > 0,
        "ch_wall_within1em": (df.wall_em >= 0) & (df.wall_em <= 1), "ch_wall_oi_dn15": df.wall_oi <= -0.15,
        "ch_gex_short_up": df.gex_s > 1, "ch_gex_net_neg": df.gex_n < 0,
        "pr_ivrv_le1": df.iv_rv <= 1.0, "pr_ivrv_le0.8": df.iv_rv <= 0.8, "pr_dist_le1em": df.dist_em <= 1,
        "pr_vix_rising": df.vix_ch > 0,
        "hv_agree3_1m": df.hv_agree >= 3, "hv_avg_5m_with": df.hv_r5 > 0,      # PREREG: "all 3 over 5 min"; only the average is stored
    }, index=df.index).fillna(False).astype(bool)


def load():
    base = S32.load()
    fl = pd.read_parquet(Z.OUT / "flags.parquet")
    fl["day"] = pd.to_datetime(fl["day"]).dt.date
    sh = np.load(Z.OUT / "shapes.npz")
    fl["_i"] = np.arange(len(fl))
    m = base.merge(fl, on=["und", "day", "t", "strike", "side"], how="inner", validate="one_to_one")
    assert len(m) == len(base) == len(fl), (len(m), len(base), len(fl))
    so, si = sh["opt"][m._i.values], sh["idx"][m._i.values]
    flag_cols = [c for c in fl.columns if c not in ("und", "day", "t", "strike", "side", "_i")]
    ch = chain_flags(m)
    m = pd.concat([m, ch], axis=1)
    return m, flag_cols + list(ch.columns), so, si


def heroes(df: pd.DataFrame, k: float) -> pd.Index:
    y = df[df.y_peak >= k]
    return y.groupby(["und", "day", "strike", "side"]).price.idxmin().values


def controls(df: pd.DataFrame, L: pd.DataFrame) -> pd.Index:
    pool = df[~df.y2]
    keyed = {k: g for k, g in pool.groupby(["und", "day", "band"])}
    parts = []
    for _, r in L.iterrows():
        g = keyed.get((r.und, r.day, r.band))
        if g is not None:
            parts.append(g.index[(g.t - r.t).abs().values <= 2])
    return pd.Index(np.unique(np.concatenate(parts))) if parts else pd.Index([])


def shape_clusters(shp: np.ndarray, fit_rows: np.ndarray, seed=SEED):
    ok = np.isfinite(shp).all(axis=1)
    rng = np.random.default_rng(seed)
    pool = np.nonzero(ok & fit_rows)[0]
    samp = rng.choice(pool, size=min(len(pool), 60000), replace=False)
    km = KMeans(n_clusters=K_SHAPES, n_init=4, random_state=seed).fit(shp[samp])
    lab = np.full(len(shp), -1)
    lab[ok] = km.predict(shp[ok])
    return lab, km.cluster_centers_


def freq_table(df, cols, H10, H5, Cn) -> list:
    allm = df[cols].mean()
    h10, h5, cc = df.loc[H10, cols].mean(), df.loc[H5, cols].mean(), df.loc[Cn, cols].mean()
    out = []
    y10 = df.y10.values
    for c in cols:
        v = df[c].values
        n_true = int(v.sum())
        out.append({"pattern": c, "hero10_pct": round(100 * float(h10[c]), 1), "hero5_pct": round(100 * float(h5[c]), 1),
                    "control_pct": round(100 * float(cc[c]), 1), "all_rows_pct": round(100 * float(allm[c]), 1),
                    "lift_vs_all": round(float(h10[c] / allm[c]), 2) if allm[c] > 0 else None,
                    "lift_vs_control": round(float(h10[c] / cc[c]), 2) if cc[c] > 0 else None,
                    "heroes_with_it": int(df.loc[H10, c].sum()),
                    "row_10x_rate_pct": round(100 * float(y10[v].mean()), 2) if n_true else None, "rows_with_it": n_true})
    return out


def mine(train: pd.DataFrame, cols: list, H10_train) -> tuple:
    """Pairs and triples of patterns present in >= 15% of the training heroes, ranked by the row 10x rate (>= 2,000 rows on
    >= 50 expiries)."""
    share = train.loc[H10_train, cols].mean()
    cand = [c for c in cols if share[c] >= 0.15]
    y = train.y10.values
    dk = (train.und.astype(str) + train.day.astype(str)).values
    M = {c: train[c].values for c in cand}
    scored = []
    for a, b in combinations(cand, 2):
        m = M[a] & M[b]
        n = int(m.sum())
        if n >= 2000:
            scored.append((float(y[m].mean()), (a, b), n))
    scored.sort(key=lambda x: -x[0])
    trip = []
    for rate, (a, b), n in scored[:40]:
        m0 = M[a] & M[b]
        for c in cand:
            if c in (a, b):
                continue
            m = m0 & M[c]
            n3 = int(m.sum())
            if n3 >= 2000:
                trip.append((float(y[m].mean()), (a, b, c), n3))
    allc = sorted(scored + trip, key=lambda x: -x[0])
    for rate, combo, n in allc:
        m = np.ones(len(train), bool)
        for c in combo:
            m &= M[c]
        if len(set(dk[m])) >= 50:
            return combo, rate, n, len(cand)
    return None, None, 0, len(cand)


def first_rows(df: pd.DataFrame, mask: np.ndarray) -> list:
    """The first matching minute each expiry; ties: the lowest price."""
    sub = df[mask]
    out = []
    for _, g in sub.groupby(["und", "day"], sort=True):
        h = g[g.t == g.t.min()]
        out.append(int(h.price.idxmin()))
    return out


def main():
    df, cols, so, si = load()
    H10, H5 = heroes(df, 10), heroes(df, 5)
    L = df.loc[H10]
    Cn = controls(df, L)
    # descriptive shape atlas (one fit on all rows)
    lab_o, cen_o = shape_clusters(so, np.ones(len(df), bool))
    lab_i, cen_i = shape_clusters(si, np.ones(len(df), bool))
    for j in range(K_SHAPES):
        df[f"shape_opt_c{j}"] = lab_o == j
        df[f"shape_ix_c{j}"] = lab_i == j
    shape_cols = [f"shape_opt_c{j}" for j in range(K_SHAPES)] + [f"shape_ix_c{j}" for j in range(K_SHAPES)]
    table = freq_table(df, cols + shape_cols, H10, H5, Cn)
    by_freq = sorted(table, key=lambda r: -r["hero10_pct"])
    by_lift = sorted([r for r in table if r["heroes_with_it"] >= 50 and r["lift_vs_all"]], key=lambda r: -r["lift_vs_all"])
    atlas = {}
    for name, cen, cols_ in (("option", cen_o, "shape_opt_c"), ("index", cen_i, "shape_ix_c")):
        atlas[name] = [{"cluster": j, "path_every5": [round(float(x), 3) for x in cen[j][::5]] + [1.0],
                        **{k: v for k, v in next(r for r in table if r["pattern"] == f"{cols_}{j}").items() if k != "pattern"}}
                       for j in range(K_SHAPES)]
    out = {"rows": int(len(df)), "patterns": len(cols) + len(shape_cols), "heroes10": int(len(H10)), "heroes5": int(len(H5)),
           "controls": int(len(Cn)), "most_occurring_top30": by_freq[:30], "highest_lift_top30": by_lift[:30],
           "lowest_lift_top15": sorted([r for r in table if r["heroes_with_it"] >= 50 and r["lift_vs_all"]],
                                       key=lambda r: r["lift_vs_all"])[:15],
           "shape_atlas": atlas, "table": table}
    print("frequency done", len(table), flush=True)
    # walk-forward: B1 (most occurring), B3 (best mined combination); shapes refit on training years only
    picks = {"B1": [], "B2": [], "B3": [], "B4": []}
    choices = {}
    for y in TEST_YEARS:
        trm = (df.year < y).values
        lo, _ = shape_clusters(so, trm, SEED + y)
        li, _ = shape_clusters(si, trm, SEED + y)
        wcols = list(cols)
        extra = {}
        for j in range(K_SHAPES):
            extra[f"wf_opt_c{j}"] = lo == j
            extra[f"wf_ix_c{j}"] = li == j
        dfx = pd.concat([df[["und", "day", "t", "strike", "side", "price", "year", "y10"] + cols],
                         pd.DataFrame(extra, index=df.index)], axis=1)
        wcols += list(extra)
        tr, te = dfx[dfx.year < y], dfx[dfx.year == y]
        Htr = heroes(df[df.year < y], 10)
        share = tr.loc[Htr, wcols].mean().sort_values(ascending=False)
        top = share.index[0]
        idx = first_rows(te, te[top].values)
        picks["B1"] += [(i, "TR25") for i in idx]
        picks["B2"] += [(i, "E1") for i in idx]
        combo, rate, n, ncand = mine(tr, wcols, Htr)
        if combo:
            m = np.ones(len(te), bool)
            for c in combo:
                m &= te[c].values
            idx3 = first_rows(te, m)
            test_rate = float(te.y10.values[m].mean()) if m.any() else None
        else:
            idx3, test_rate = [], None
        picks["B3"] += [(i, "TR25") for i in idx3]
        picks["B4"] += [(i, "E1") for i in idx3]
        choices[y] = {"most_occurring": top, "hero_share_pct": round(100 * float(share.iloc[0]), 1),
                      "its_test_10x_rate_pct": round(100 * float(te.y10.values[te[top].values].mean()), 2) if te[top].any() else None,
                      "test_base_pct": round(100 * float(te.y10.mean()), 2),
                      "combo": combo, "combo_train_10x_rate_pct": round(100 * rate, 2) if rate else None, "combo_train_rows": n,
                      "candidates": ncand, "combo_test_10x_rate_pct": round(100 * test_rate, 2) if test_rate is not None else None,
                      "trades_B1": len(idx), "trades_B3": len(idx3)}
        print(y, choices[y], flush=True)
    out["choices"] = choices
    from r38trail import earlier_family
    r38 = json.loads((ROOT / "research/hunt/r38/results/results.json").read_text())["rules"]
    r39 = json.loads((ROOT / "research/hunt/r39/results/results.json").read_text())["rules"]
    fam = earlier_family() | {f"R38-{k}": v["p"] for k, v in r38.items()} | {f"R39-{k}": v["twins"]["p"] for k, v in r39.items()}
    res = {}
    for rid, pk in picks.items():
        m, trs = S32.metrics(df, pk)
        tw = S32.twins(df, pk)
        p_ = float((tw >= m["net"] - 1e-9).mean()) if pk else 1.0
        fam[f"R40-{rid}"] = p_
        res[rid] = {"m": m, "twins": {"p": round(p_, 4), "mean": round(float(tw.mean()), 0)},
                    "trades_list": [dict(und=t.und, day=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike, sig=t.sig_row,
                                         entry=t.entry, peak=round(t.peak_mult, 2), net=round(t.net, 0)) for t in trs]}
        print(rid, m["trades"], m["net"], "p", round(p_, 3), flush=True)
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid, v in res.items():
        v["q"] = round(qs[f"R40-{rid}"], 4)
        m = v["m"]
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 >= 3 of 4 years > 0": sum(x > 0 for x in m["years"].values()) >= 3, "4 BH q < 0.05": v["q"] < 0.05,
             "5 x2 spread > 0": m["x2_spread"] > 0, "6 w/o best day > 0": m["net_without_best_day"] > 0,
             "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    out["rules"] = res
    out["bh_size"], out["bh_min_q"] = len(fam), round(min(qs.values()), 4)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH", len(fam), "min q", out["bh_min_q"], "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
