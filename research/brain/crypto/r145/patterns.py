"""R145 patterns: lead-lag (P4), coin clusters (P5), feature importance and descriptions (PREREG section 5).

Design data only unless a holdout panel is passed explicitly by evaluate.holdout().
"""
from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats as st
from scipy.cluster.hierarchy import fcluster, linkage
from scipy.spatial.distance import squareform

from .. import config
from . import data, models as M, stats as S

LAGS_TESTED = (1, 2, 3, 5, 10)
N_CLUSTERS = 6
HALF = pd.Timestamp("2025-02-15")            # P5: first half 2024-01-08 .. 2025-02-14, second half 2025-02-15 .. 2026-03-31


def leadlag_tests(base: Path | None = None, hi_month: str = "2026-03", lo_month: str = "2023-12"
                  ) -> tuple[list[dict], dict]:
    """P4a (1-minute): mean across coin-months of corr(alt_t, BTC_{t-k}) > 0. Returns tests and the full profile."""
    base = base or data.root()
    fs = [p for p in (base / "leadlag").glob("*/*.parquet") if lo_month <= p.stem <= hi_month]
    LL = pd.concat([pd.read_parquet(p) for p in fs], ignore_index=True)
    LL = LL[LL.n >= 10000]
    prof = LL.groupby("lag")["corr"].agg(["mean", "median", "count"]).reset_index()
    tests = []
    for k in LAGS_TESTED:
        x = LL[LL.lag == k]["corr"].to_numpy()
        t, p, n = S.t_one_sided(x)
        tests.append(dict(id=f"P4-1m-lag{k}", lag_min=k, mean_corr=float(np.mean(x)), n=n, t=t, p=p,
                          effect=float(np.mean(x)), metric="corr", folds_pos=None))
    by_coin = (LL[LL.lag.isin([0, 1])].pivot_table(index="sym", columns="lag", values="corr", aggfunc="mean")
               .rename(columns={0: "same_minute", 1: "btc_1min_earlier"}).sort_values("btc_1min_earlier",
                                                                                        ascending=False))
    rev = LL[LL.lag == -1]["corr"]
    info = dict(profile=prof.to_dict("records"), coin_months=int(LL.groupby(["sym", "month"]).ngroups),
                by_coin_top=by_coin.head(10).round(4).reset_index().to_dict("records"),
                by_coin_bottom=by_coin.tail(5).round(4).reset_index().to_dict("records"),
                alt_leads_btc_1min=float(rev.mean()))
    return tests, info


def closes(symbols, frm, to, field="close") -> pd.DataFrame:
    out = {}
    for s in symbols:
        b = data.load_bars(s, frm, to)
        if b is not None:
            out[s] = b.set_index("ts")[field]
    return pd.DataFrame(out).sort_index()


def leadlag_5m(C5: pd.DataFrame, lo, hi) -> tuple[dict, dict]:
    """P4b: corr(alt 5-minute return, BTC's previous 5-minute return), per coin-month, mean > 0; plus the conditional
    description: after BTC's 5-minute move beyond +-2 sd, the alts' next 5-minute mean move vs the round-trip cost."""
    C5 = C5[(C5.index >= lo) & (C5.index < hi)]
    r = np.log(C5).diff()
    r = r.where(r.index.to_series().diff().eq(pd.Timedelta(minutes=5)), axis=0)
    b1 = r["BTCUSDT"].shift(1)
    month = r.index.to_period("M")
    cors = []
    for s in r.columns:
        if s == "BTCUSDT":
            continue
        d = pd.DataFrame({"a": r[s], "b": b1, "m": month}).dropna()
        for m, g in d.groupby("m"):
            if len(g) > 2000:
                cors.append(np.corrcoef(g.a, g.b)[0, 1])
    t, p, n = S.t_one_sided(cors)
    test = dict(id="P4-5m-lag1", lag_min=5, mean_corr=float(np.mean(cors)), n=n, t=t, p=p,
                effect=float(np.mean(cors)), metric="corr", folds_pos=None)
    sd = r["BTCUSDT"].rolling(288 * 7, min_periods=288).std().shift(1)
    z = r["BTCUSDT"] / sd
    alts = r.drop(columns="BTCUSDT").shift(-1)                   # the NEXT bar's alt returns
    up, dn = z > 2, z < -2
    desc = dict(btc_up_2sd_bars=int(up.sum()), btc_dn_2sd_bars=int(dn.sum()),
                alts_next5_after_btc_up_bps=float(np.nanmean(alts[up].values) * 1e4),
                alts_next5_after_btc_dn_bps=float(np.nanmean(alts[dn].values) * 1e4),
                round_trip_cost_bps_small_alt=float((0.0005 + 5e-4) * 2 * 1e4))
    return test, desc


def _clusters(R: pd.DataFrame) -> np.ndarray:
    c = R.corr().fillna(0).to_numpy()
    dist = np.clip(1 - c, 0, 2)
    np.fill_diagonal(dist, 0)
    Z = linkage(squareform(dist, checks=False), method="average")
    return fcluster(Z, N_CLUSTERS, criterion="maxclust")


def ari(a, b) -> float:
    from sklearn.metrics import adjusted_rand_score
    return float(adjusted_rand_score(a, b))


def holdout_cluster_test(C5: pd.DataFrame, design_groups: dict, lo, hi, n_perm=1000, seed=0) -> dict:
    """Holdout check of P5: clusters fitted on the holdout hours alone vs the design clusters (ARI, permutations)."""
    H = C5[(C5.index >= lo) & (C5.index < hi)]
    H = H[(H.index + pd.Timedelta(minutes=5)).minute == 0]
    R = np.log(H).diff()
    R = R.sub(R.mean(axis=1), axis=0)
    lab = {s: int(g) for g, m in design_groups.items() for s in m}
    keep = [s for s in R.columns if s in lab and R[s].notna().mean() > 0.9]
    la = np.array([lab[s] for s in keep])
    lb = _clusters(R[keep])
    obs = ari(la, lb)
    rng = np.random.default_rng(seed)
    null = np.array([ari(la, rng.permutation(lb)) for _ in range(n_perm)])
    return dict(id="P5-cluster-stability", ari=obs, null_mean=float(null.mean()), n=len(keep),
                p=float((np.sum(null >= obs) + 1) / (n_perm + 1)), effect=obs, metric="ARI")


def cluster_test(C5: pd.DataFrame, lo, hi, n_perm=1000, seed=0) -> tuple[dict, dict]:
    """P5: hourly returns minus the equal-weight market's, average-linkage clusters (1 - corr), 6 clusters, on each
    half of design; ARI between halves against permutations of one half's labels."""
    H = C5[(C5.index >= lo) & (C5.index < hi)]
    H = H[(H.index + pd.Timedelta(minutes=5)).minute == 0]
    R = np.log(H).diff()
    R = R.sub(R.mean(axis=1), axis=0)
    a, b = R[R.index < HALF], R[R.index >= HALF]
    keep = [s for s in R.columns if a[s].notna().mean() > 0.9 and b[s].notna().mean() > 0.9]
    la, lb = _clusters(a[keep]), _clusters(b[keep])
    obs = ari(la, lb)
    rng = np.random.default_rng(seed)
    null = np.array([ari(la, rng.permutation(lb)) for _ in range(n_perm)])
    p = float((np.sum(null >= obs) + 1) / (n_perm + 1))
    full = _clusters(R[keep])
    groups = {}
    for s, g in zip(keep, full):
        groups.setdefault(int(g), []).append(s)
    corr_full = R[keep].corr()
    within = {g: float(np.nanmean(corr_full.loc[m, m].values[~np.eye(len(m), dtype=bool)])) if len(m) > 1 else None
              for g, m in groups.items()}
    raw = np.log(H).diff()[keep]
    test = dict(id="P5-cluster-stability", ari=obs, null_mean=float(null.mean()), n=len(keep), p=p, t=None,
                effect=obs, metric="ARI", folds_pos=None)
    desc = dict(coins=len(keep), groups={str(g): m for g, m in sorted(groups.items(), key=lambda x: -len(x[1]))},
                within_resid_corr={str(g): v for g, v in within.items()},
                mean_raw_corr_1h=float(np.nanmean(raw.corr().values[~np.eye(len(keep), dtype=bool)])))
    return test, desc


GROUPS = {
    "own returns": lambda f: f.startswith("rn_") or f in ("r_60", "r_1440"),
    "own volatility": lambda f: f.startswith("lrv_") or f == "vol_ratio",
    "range / VWAP position": lambda f: f.startswith("pos_") or f == "vwap_dist",
    "volume / trades": lambda f: f in ("qv_z", "trades_z"),
    "taker flow": lambda f: f.startswith("taker_"),
    "funding": lambda f: f.startswith("fund_"),
    "link to BTC (beta, corr, residual)": lambda f: f in ("beta_btc", "corr_btc", "resid_60", "resid_240"),
    "listing age": lambda f: f == "age_log",
    "cross-sectional ranks": lambda f: f.startswith("xr_"),
    "BTC / ETH moves": lambda f: f.startswith("btc_") or f.startswith("eth_"),
    "breadth / dispersion / market vol": lambda f: f.startswith("breadth_") or f.startswith("mkt_")
    or f == "disp_60",
    "calendar (IST hour, weekday)": lambda f: f.startswith("hour_") or f.startswith("dow_") or f == "weekend",
}
IMP_TARGETS = ("y_dir_60", "y_vol_60", "y_xs_60", "y_big_60", "y_size_60")


def importance(D: pd.DataFrame, feats: list[str], folds=(4, 5), n_rows=150_000, seed=0, tag="design") -> dict:
    """Permutation importance of N1 (seed 0 net of each fold) by feature group on that fold's test rows: how much the
    loss rises (IC falls) when the group's columns are shuffled across rows. Reported as a share of the full model's
    skill over B0 (descriptive, not a test)."""
    import torch
    from . import run
    rng = np.random.default_rng(seed)
    res = {t: {g: [] for g in GROUPS} for t in IMP_TARGETS}
    skill = {t: [] for t in IMP_TARGETS}
    mdir = run.out_dir() / tag / "models"
    for fold in folds:
        pk = pickle.load(open(mdir / f"fold{fold}_prep.pkl", "rb"))
        _, _, _, te = run.split(D, fold)
        te = np.sort(rng.choice(te, min(n_rows, len(te)), replace=False))
        X = D[feats].to_numpy(np.float64)[te]
        Z = pk["prep"](X)
        nets = []
        for sd in run.N1_SEEDS:
            net = M._nets(len(feats), len(M.TARGETS), False)
            net.load_state_dict(torch.load(mdir / f"fold{fold}_N1_s{sd}.pt"))
            nets.append(net)

        def pred(Zm):
            return np.mean([pk["tscale"].inv(M.predict_net(n_, Zm)) for n_ in nets], axis=0)

        base = pred(Z)
        sub = D.iloc[te]

        def score(pr, t):
            i = M.TARGETS.index(t)
            y = sub[t].to_numpy(float)
            m = np.isfinite(y)
            if t.startswith("y_xs"):
                return -float(S.ic_by_time(sub.ts.values[m], pr[m, i], y[m]).mean())
            return float(np.mean(S.row_loss(pr[m, i], y[m], M.is_binary(t))))

        for t in IMP_TARGETS:
            y = sub[t].to_numpy(float)
            m = np.isfinite(y)
            if t.startswith("y_xs"):
                b0 = 0.0
            elif t.startswith("y_vol"):
                b0 = float(np.mean(S.row_loss(np.full(m.sum(), np.nanmean(y)), y[m], False)))
            else:
                b0 = float(np.mean(S.row_loss(np.full(m.sum(), np.nanmean(y)), y[m], M.is_binary(t))))
            skill[t].append(b0 - score(base, t))
        for g, f in GROUPS.items():
            cols = [i for i, c in enumerate(feats) if f(c)]
            if not cols:
                continue
            Zp = Z.copy()
            perm = rng.permutation(len(Zp))
            Zp[:, cols] = Zp[perm][:, cols]
            pp = pred(Zp)
            for t in IMP_TARGETS:
                res[t][g].append(score(pp, t) - score(base, t))
    out = {}
    for t in IMP_TARGETS:
        sk = float(np.mean(skill[t]))
        out[t] = dict(skill_over_mean=sk, groups={g: dict(loss_rise=float(np.mean(v)),
                                                           share_of_skill=float(np.mean(v) / sk) if sk > 0 else None)
                                                  for g, v in res[t].items() if v})
    return out
