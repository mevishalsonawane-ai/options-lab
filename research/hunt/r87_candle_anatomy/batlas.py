"""R87B Part B1 (bigger timeframes: 30m, 60m, daily) and B2 (candle sequences and data-driven sequence clusters).
PREREG_B.md. Run with R87_OUT pointing at the R87B build folder (the option snapshot of R87B).

    python -m research.hunt.r87_candle_anatomy.batlas build      # 30m / 60m / daily tables
    python -m research.hunt.r87_candle_anatomy.batlas design     # results/b/batlas_design_*
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import build as B
from . import candles as CA
from . import cfg
from . import context as CX
from . import outcome as OC
from . import stats as ST

RES = cfg.RESULTS / "b"
FINAL_FLAG = RES / "FINAL_READ"
DAILY_START = date(2015, 1, 1)
DAILY_FOLDS = ((date(2015, 1, 1), date(2017, 12, 31)), (date(2018, 1, 1), date(2020, 12, 31)),
               (date(2021, 1, 1), date(2022, 12, 31)), (date(2023, 1, 1), cfg.DESIGN_END))
PRIMARY = {5: "f_ret5", 15: "f_ret5", 30: "f_ret3", 60: "f_ret3", 1440: "f_ret5"}
SECONDARY = ("f_ret1", "f_ret10", "f_upfirst20", "f_lobrk10", "f_hibrk10", "f_mfe10", "f_mae10")
STRATA_D = ("und", "tf", "trend", "volreg", "col")
K_CLUSTERS = 24
MIN_N_SEQ = 200


# ------------------------------------------------------------------------------------------------ daily candles
def daily_raw(und: str) -> pd.DataFrame:
    from . import data as D
    d = D._read_chunks(f"{cfg.RAW}/idx_daily/{und}/*.parquet")
    d = d.assign(ts=pd.to_datetime(d.timestamp.astype("int64"), unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
                 .dt.tz_localize(None).dt.normalize()).rename(columns=dict(open="o", high="high", low="low",
                                                                            close="c"))
    d = d.rename(columns=dict(high="h", low="l"))[["ts", "o", "h", "l", "c"]].drop_duplicates("ts", keep="last")
    d = d[(d.ts.dt.dayofweek < 5) & (d.ts.dt.date >= DAILY_START) & (d.ts.dt.date <= cfg.END)]
    return d[(d[["o", "h", "l", "c"]] > 0).all(axis=1)].sort_values("ts").reset_index(drop=True)


def daily_table(d: pd.DataFrame, und: str, expiries: set) -> pd.DataFrame:
    """Daily candles with the same anatomy / shape / pattern / outcome columns as the intraday tables. 'Session'
    logic is switched off (every outcome may cross days: that is what a daily candle's next 5 candles are)."""
    one = pd.Timestamp("2000-01-03")
    k = d.assign(day=one, slot=0, tl=d.ts, n=1)
    k = CA.anatomy(k)
    c, h, l = k.c.to_numpy(float), k.h.to_numpy(float), k.l.to_numpy(float)
    k["trend"], k["ema50"] = CX.trend_state(c)
    k["pos50"] = CX.rolling_pos(h, l, c, 50)
    k["tod"] = "day"
    k["volreg"] = CX.vol_regime(k.atr.to_numpy())
    pc = pd.Series(c).shift(1).to_numpy()
    k["gap"] = k.o / pc - 1
    k["pdh"], k["pdl"] = pd.Series(h).shift(1).to_numpy(), pd.Series(l).shift(1).to_numpy()
    for x in ("dhi_before", "dlo_before", "orh", "orl", "vwap_dist", "act_ratio", "pd_vah", "pd_val", "dpoi", "dcoi",
              "gex_ratio", "delta_share", "up_wick_act", "lo_wick_act", "act"):
        k[x] = np.nan
    k["expiry"] = k.ts.dt.date.isin(expiries)
    sh, pt = CA.shapes(k), CA.patterns(k)
    cond = pd.DataFrame(CX.conditions(k), index=k.index).add_prefix("c_")
    k = pd.concat([k, sh, pt, cond], axis=1)
    k["cell"] = CA.grid_cell(k)
    k = pd.concat([k, OC.outcomes(k)], axis=1)
    k["day"] = k.ts                                       # real dates for clustering and splits
    k["und"], k["tf"] = und, 1440
    return k


# ------------------------------------------------------------------------------------------------ sequences
def tokens(k: pd.DataFrame) -> np.ndarray:
    """One letter-pair per candle: D (body <= 10%) or G/R + S (body 10-50%) / B (> 50%); then the wick tilt: U (upper
    - lower >= 20% of range), L (lower - upper >= 20%), N. 15 tokens, e.g. 'GBN', 'RSL', 'DU'."""
    b, u, w, col = (k[x].to_numpy() for x in ("body_pct", "up_pct", "lo_pct", "col"))
    tilt = np.where(u - w >= 0.2, "U", np.where(w - u >= 0.2, "L", "N"))
    body = np.where(b <= 0.10, "D", np.where(col > 0, "G", "R"))
    size = np.where(b <= 0.10, "", np.where(b > 0.5, "B", "S"))
    return np.char.add(np.char.add(body.astype(str), size.astype(str)), tilt.astype(str))


def seq3(k: pd.DataFrame) -> pd.Series:
    t = pd.Series(tokens(k), index=k.index)
    d = k.day
    ok = (d == d.shift(1)) & (d == d.shift(2)) & k.atr.notna()
    s = t.shift(2) + "-" + t.shift(1) + "-" + t
    return s.where(ok)


def seq_features(k: pd.DataFrame) -> np.ndarray:
    """(n, 12): for each of the 3 candles (oldest first) the signed body (colour x body %), upper %, lower %, and
    log(range / ATR) clipped to [-2, 2]. NaN rows where the 3 candles are not in one session."""
    cols = []
    for lag in (2, 1, 0):
        x = k.shift(lag)
        cols += [x.col * x.body_pct, x.up_pct, x.lo_pct, np.clip(np.log(x["size"].clip(lower=1e-3)), -2, 2)]
    F = np.column_stack([np.asarray(c, float) for c in cols])
    d = k.day
    ok = ((d == d.shift(1)) & (d == d.shift(2))).to_numpy() & np.isfinite(F).all(1)
    F[~ok] = np.nan
    return F


def fit_clusters(F: np.ndarray, seed: int = cfg.SEED) -> np.ndarray:
    from sklearn.cluster import KMeans
    ok = np.isfinite(F).all(1)
    km = KMeans(n_clusters=K_CLUSTERS, n_init=4, random_state=seed).fit(F[ok])
    return km.cluster_centers_


def assign(F: np.ndarray, C: np.ndarray) -> np.ndarray:
    ok = np.isfinite(F).all(1)
    out = np.full(len(F), -1)
    if ok.any():
        d2 = ((F[ok][:, None, :] - C[None, :, :]) ** 2).sum(-1)
        out[ok] = d2.argmin(1)
    return out


# ------------------------------------------------------------------------------------------------ scanning
def scan(df: pd.DataFrame, masks: dict, primary: str, strata, folds, rule_window, label: dict, min_n: int) -> list:
    """Effects for every family mask on one (und, tf) table, matched baseline over the whole table."""
    df = df.reset_index(drop=True)
    y = df[primary].to_numpy(float)
    day = (df.und + "|" + df.day.dt.strftime("%Y-%m-%d")).to_numpy()
    base = ST.baseline(df, primary, strata)
    sb = {s: ST.baseline(df, s, strata) for s in SECONDARY}
    upf = (df[primary] > 0).astype(float).where(df[primary].notna())
    upb = ST.baseline(df.assign(_u=upf), "_u", strata)
    fo = np.full(len(df), -1)
    if folds:
        dd = df.day.dt.date.to_numpy()
        for i, (a, b) in enumerate(folds):
            fo[(dd >= a) & (dd <= b)] = i
    rw = (df.day.dt.date >= rule_window[0]).to_numpy() if rule_window else None
    atr = df.atr.to_numpy(float)
    out = []
    for name, m in masks.items():
        m = np.asarray(m, bool)
        r = dict(label, family=name, primary=primary)
        r.update(ST.effect(y, base, day, m))
        ok = m & np.isfinite(y)
        r["move_pts"] = float(np.mean((y * atr)[ok])) if ok.any() else np.nan
        r["up_rate"] = float(np.mean(y[ok] > 0)) if ok.any() else np.nan
        r["base_up_rate"] = float(np.nanmean(upb[ok])) if ok.any() else np.nan
        for s in SECONDARY:
            e = ST.effect(df[s].to_numpy(float), sb[s], day, m)
            r[s + "_raw"], r[s + "_base"], r[s + "_p"] = e["raw"], e["base"], e["p"]
        if folds:
            r["fold_eff"] = json.dumps([None if not np.isfinite(v) else v for v in
                                        (ST.effect(y, base, day, m & (fo == i))["eff"] for i in range(len(folds)))])
        if rw is not None:
            e = ST.effect(y, base, day, m & rw)
            okw = m & rw & np.isfinite(y)
            r["rw_n"], r["rw_eff"] = e["n"], e["eff"]
            r["rw_move_pts"] = float(np.mean((y * atr)[okw])) if okw.any() else np.nan
        r["tested"] = r["n"] >= min_n
        out.append(r)
    return out


def finish(rows: list) -> pd.DataFrame:
    t = pd.DataFrame(rows)
    if t.empty:
        return t
    t["q"] = np.nan
    t.loc[t.tested, "q"] = ST.bh(t.loc[t.tested, "p"].to_numpy())
    return t


def families_b1(k: pd.DataFrame) -> dict:
    return {f: k[f].to_numpy(bool) for f in CA.FAMILIES}


def run_b1(frm: date, to: date, folds_intra, folds_daily, rule_window) -> dict:
    rows = []
    for u in cfg.UNDS:
        for tf in (30, 60):
            k = B.load(u, tf, frm, to)
            rows += scan(k, families_b1(k), PRIMARY[tf], ST.STRATA, folds_intra, rule_window,
                         dict(und=u, tf=tf, cond=""), cfg.MIN_N)
        kd = pd.read_parquet(cfg.OUT / f"candles_{u}_daily.parquet")
        kd = kd[(kd.day.dt.date >= max(frm, DAILY_START)) & (kd.day.dt.date <= to)]
        rows += scan(kd, families_b1(kd), PRIMARY[1440], STRATA_D, folds_daily, None, dict(und=u, tf=1440, cond=""),
                     cfg.MIN_N)
    return {"B1": finish(rows)}


def run_b2(frm: date, to: date, folds, rule_window, centres: dict | None) -> tuple[dict, dict]:
    seq_rows, cl_rows, fitted = [], [], {}
    for tf in (5, 15):
        ks = {u: B.load(u, tf, frm, to) for u in cfg.UNDS}
        if centres is None:
            F = np.vstack([seq_features(k) for k in ks.values()])
            fitted[tf] = fit_clusters(F)
        C = (centres or fitted)[tf]
        for u, k in ks.items():
            s = seq3(k)
            vc = s.value_counts()
            masks = {f"seq:{q}": (s == q).to_numpy() for q in vc[vc >= MIN_N_SEQ].index} if centres is None else \
                {f"seq:{q}": (s == q).to_numpy() for q in vc.index if q in KEEP_SEQ.get((u, tf), set())}
            seq_rows += scan(k, masks, PRIMARY[tf], ST.STRATA, folds, rule_window, dict(und=u, tf=tf, cond=""),
                             MIN_N_SEQ if centres is None else cfg.MIN_N)
            lab = assign(seq_features(k), C)
            cm = {f"cluster{tf}:{i:02d}": lab == i for i in range(K_CLUSTERS)}
            cl_rows += scan(k, cm, PRIMARY[tf], ST.STRATA, folds, rule_window, dict(und=u, tf=tf, cond=""), cfg.MIN_N)
    return {"B2seq": finish(seq_rows), "B2cl": finish(cl_rows)}, fitted


KEEP_SEQ: dict = {}          # holdout: re-measure exactly the design-tested sequences (filled by final_b)


def save(out: dict, tag: str) -> dict:
    RES.mkdir(parents=True, exist_ok=True)
    summ = {}
    for fam, t in out.items():
        t.to_csv(RES / f"batlas_{tag}_{fam}.csv.gz", index=False, float_format="%.6g")
        summ[fam] = dict(cells=int(len(t)), tested=int(t.tested.sum()) if len(t) else 0,
                         bh_pass=int((t.q < cfg.Q).sum()) if len(t) else 0)
    return summ


def build_tables() -> None:
    for u in cfg.UNDS:
        B.build(u, tfs=(5, 15, 30, 60))
        ex = CX.expiry_set(u, DAILY_START, cfg.END)
        daily_table(daily_raw(u), u, ex).to_parquet(cfg.OUT / f"candles_{u}_daily.parquet", compression="zstd")


def design() -> None:
    out = run_b1(cfg.START, cfg.DESIGN_END, cfg.FOLDS, DAILY_FOLDS, (cfg.RULE_START, cfg.DESIGN_END))
    o2, cent = run_b2(cfg.START, cfg.DESIGN_END, cfg.FOLDS, (cfg.RULE_START, cfg.DESIGN_END), None)
    out.update(o2)
    RES.mkdir(parents=True, exist_ok=True)
    (RES / "clusters_design.json").write_text(json.dumps({str(tf): c.round(6).tolist() for tf, c in cent.items()}))
    summ = save(out, "design")
    (RES / "batlas_design_summary.json").write_text(json.dumps(summ, indent=1))
    print(json.dumps(summ, indent=1))


if __name__ == "__main__":
    {"build": build_tables, "design": design}[sys.argv[1]]()
