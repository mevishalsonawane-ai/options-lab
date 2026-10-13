"""C2: crypto in Boss's window, 00:00-09:00 IST only (PREREG_C2_WINDOW.md). Design data only.

    python -m research.brain.crypto.window --symbols BTCUSDT ETHUSDT [--out research/brain/crypto/results/c2_window.json]

Uses C1's models, statistics and costs (study.py) on decision points inside the window: the 9 IST hours (rows 18:29 ..
02:29 UTC, next 60 minutes) and the whole window (row 18:29 UTC, next 540 minutes, to 09:00 IST). Every target ends
by 09:00 IST, so every trade is flat before NSE opens.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from scipy import stats

from .. import split
from . import config, costs, cube, holdout, labels, study
from .features import store

OUT = Path(__file__).resolve().parent / "results" / "c2_window.json"
WIN_MIN = 540
FIRST_ROW = (18, 29)                       # UTC row known at 00:00 IST


def _ist_date(ts) -> pd.DatetimeIndex:
    return (pd.DatetimeIndex(ts) + pd.Timedelta(minutes=331)).normalize()      # the IST date of the bar's END


def load(root: Path, sym: str):
    last = config.DESIGN_END.date()
    study._check_store(root, sym, last)
    F = store.load(root, sym, to=last, columns=["ts", "precision", *study.HOURLY, "in_boss_window",
                                                "min_to_window_end"])
    L = labels.load(root, sym, to=last)
    D = split.join_forward(F, L[["ts", "y_ret_60m", "y_range_60m"]])
    D = D[D.ts >= D.ts.min().normalize() + pd.Timedelta(days=study.WARMUP_DAYS)]
    # window hours: rows at :29 UTC whose next 60 minutes lie inside the window (bar 18:29 ends at 00:00 IST, so the
    # row itself is just outside; its successor hour is the first window hour)
    nxt = D.ts + pd.Timedelta(minutes=1)
    ok_h = (D.ts.dt.minute == 29) & (((nxt.dt.hour >= 18) & (nxt.dt.minute == 30)) | (nxt.dt.hour <= 2))
    H = holdout.design(D[ok_h.values], 60)
    H = H[np.isfinite(H.y_ret_60m) & (H.y_range_60m > 0) & np.isfinite(H.ret_60m)].reset_index(drop=True)
    H["win_hour"] = (((H.ts + pd.Timedelta(minutes=1)) - _ist_date(H.ts)) / pd.Timedelta(hours=1)
                     + 5.5).round().astype(int) % 24                              # IST hour 0..8
    H["weekend"] = (_ist_date(H.ts).weekday >= 5).astype(float)
    assert H.win_hour.between(0, 8).all()
    # whole window: the 18:29 UTC row, target the next 540 minutes from the cube (design days only)
    files = [p for p in sorted(config.paths(root).cube(sym).glob("*/*.parquet"))
             if pd.Timestamp(p.stem).date() <= last]
    M = pd.concat([pd.read_parquet(p, columns=["ts", "open", "high", "low", "close", "flag"]) for p in files],
                  ignore_index=True)
    holdout.assert_design(M)
    Lw = labels.compute(M, (WIN_MIN,))
    W = D[(D.ts.dt.hour == FIRST_ROW[0]) & (D.ts.dt.minute == FIRST_ROW[1])].merge(
        Lw[["ts", f"y_ret_{WIN_MIN}m", f"y_range_{WIN_MIN}m"]], on="ts")
    W = holdout.design(W, WIN_MIN)
    W = W[np.isfinite(W[f"y_ret_{WIN_MIN}m"]) & (W[f"y_range_{WIN_MIN}m"] > 0) & np.isfinite(W.ret_1440m)]
    W = W.reset_index(drop=True).rename(columns={f"y_ret_{WIN_MIN}m": "y_ret_w", f"y_range_{WIN_MIN}m": "y_range_w"})
    W["weekend"] = (_ist_date(W.ts).weekday >= 5).astype(float)
    W["log_rw_20"] = np.log(W.y_range_w).shift(1).rolling(20, min_periods=20).mean()   # previous 20 windows only
    return H, W, dict(window_hours=len(H), windows=len(W), first=str(H.ts.min()), last=str(H.ts.max()))


def _log(a):
    return study._log(a)


def test_w1(H, sym):
    y = _log(H.y_range_60m)
    H = H.assign(_y=y, _hw=H.win_hour * 2 + H.weekend)

    def tod(tr, te):
        return te._hw.map(tr.groupby("_hw")._y.mean()).fillna(tr._y.mean()).to_numpy()

    def fit(tr, te):
        cols = lambda d: np.c_[_log(d.range_60m_pct), _log(d.range_240m_pct), _log(d.rv_1440m_pct), _log(d.dvol),
                               tod(tr, d), d.weekend]
        return study.ridge(cols(tr), tr._y.to_numpy(), cols(te))
    pred, base = study.walk(H, 60, fit), study.walk(H, 60, tod)
    ok = np.isfinite(pred) & np.isfinite(base) & np.isfinite(y)
    em, eb = np.abs(y - pred)[ok], np.abs(y - base)[ok]
    p, nw = study.weekly_t(eb - em, study._week(H.ts[ok]))
    sk = round(100 * (1 - em.mean() / eb.mean()), 1)
    return dict(id=f"W1-{sym}", n=int(ok.sum()), weeks=nw, mae_model=round(float(em.mean()), 4),
                mae_base=round(float(eb.mean()), 4), skill_pct=sk, p=p, effect=sk)


def test_w3(W, sym):
    y = _log(W.y_range_w)
    W = W.assign(_y=y)

    def fit(tr, te):
        cols = lambda d: np.c_[_log(d.range_240m_pct), _log(d.rv_1440m_pct), _log(d.dvol), d.weekend]
        return study.ridge(cols(tr), tr._y.to_numpy(), cols(te))
    pred = study.walk(W, WIN_MIN, fit)
    base = W.log_rw_20.to_numpy()
    ok = np.isfinite(pred) & np.isfinite(base) & np.isfinite(y)
    em, eb = np.abs(y - pred)[ok], np.abs(y - base)[ok]
    p, nw = study.weekly_t(eb - em, study._week(W.ts[ok]))
    sk = round(100 * (1 - em.mean() / eb.mean()), 1)
    return dict(id=f"W3-{sym}", n=int(ok.sum()), weeks=nw, mae_model=round(float(em.mean()), 4),
                mae_base=round(float(eb.mean()), 4), skill_pct=sk, p=p, effect=sk)


def test_w2(H, sym, rng):
    H = H.assign(is_weekend=H.weekend)
    def cols(d):
        hr = d.hour_utc.to_numpy() * 2 * np.pi / 24
        return np.c_[d.ret_60m, d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_60m_pct,
                     d.perp_taker_imb_60m, d.vwap24_z, np.sin(hr), np.cos(hr), d.is_weekend]
    r = study._direction(H, sym, "W2", 60, "y_ret_60m", cols, rng)
    return r


def test_w4(W, sym, rng):
    def cols(d):
        return np.c_[d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_24h_pct, d.vwap24_z, d.weekend]
    return study._direction(W, sym, "W4", WIN_MIN, "y_ret_w", cols, rng)


def test_w5(H, sym):
    rt = costs.round_trip(sym, "perp", 60) * 100
    out = []
    for h in range(9):
        r = H.y_ret_60m.to_numpy()[H.win_hour.to_numpy() == h]
        t = stats.ttest_1samp(r, 0)
        out.append(dict(id=f"W5-{h:02d}IST-{sym}", n=int(len(r)), mean_bps=round(100 * r.mean(), 2),
                        abs_bps=round(100 * np.abs(r).mean(), 1), p=float(t.pvalue), effect=round(100 * r.mean(), 2),
                        beats_costs=bool(abs(r.mean()) > rt)))
    return out


def describe(H, W, Hall=None) -> dict:
    """Post-hoc descriptions (not tests)."""
    by = H.groupby("win_hour").y_range_60m.median().round(3)
    return dict(median_window_hour_range_pct=round(float(H.y_range_60m.median()), 3),
                median_window_range_pct=round(float(W.y_range_w.median()), 2),
                window_range_p10_p90=[round(float(x), 2) for x in W.y_range_w.quantile([0.1, 0.9])],
                median_range_by_ist_hour_pct={int(k): float(v) for k, v in by.items()},
                window_up_pct=round(100 * float((W.y_ret_w > 0).mean()), 1),
                window_mean_ret_bps=round(100 * float(W.y_ret_w.mean()), 1))


def posthoc(root: Path, W, sym, rng) -> dict:
    """Post-hoc checks of W4 (written after the tests ran, 13 Oct 2026; descriptions, not tests): which single input
    carries it, whether it survives an entry one minute later, and whether it holds in each year."""
    y = (W.y_ret_w > 0).astype(float).to_numpy()
    single = {}
    for f in ["ret_240m", "ret_1440m", "funding_z_30d", "basis_z_7d", "oi_chg_24h_pct", "vwap24_z"]:
        v = W[f].to_numpy()
        m = np.isfinite(v) & (W.y_ret_w != 0).to_numpy()
        single[f] = round(study.auc(y[m], v[m]), 4)
    files = [p for p in sorted(config.paths(root).cube(sym).glob("*/*.parquet"))
             if pd.Timestamp(p.stem).date() <= config.DESIGN_END.date()]
    c = pd.concat([pd.read_parquet(p, columns=["ts", "close", "flag"]) for p in files], ignore_index=True)
    holdout.assert_design(c)
    c = c[c.flag.isin(["ok", "stale"])].set_index("ts").close
    t1 = W.ts + pd.Timedelta(minutes=1)
    with np.errstate(invalid="ignore", divide="ignore"):
        late = np.log(c.reindex(t1 + pd.Timedelta(minutes=WIN_MIN - 1)).to_numpy() / c.reindex(t1).to_numpy()) * 100
    Wl = W.assign(y_late=late)
    Wl = Wl[np.isfinite(Wl.y_late) & (Wl.y_late != 0)].reset_index(drop=True)

    def cols(d):
        return np.c_[d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_24h_pct, d.vwap24_z, d.weekend]
    r_late = study._direction(Wl.assign(y_ret_w=Wl.y_late), sym, "W4late", WIN_MIN, "y_ret_w", cols, rng)
    r = study._direction(W, sym, "W4", WIN_MIN, "y_ret_w", cols, rng)       # same predictions as the test (fixed fits)
    by_year = {}
    yr = W.ts.dt.year.to_numpy()
    for yv in sorted(set(yr)):
        sub = W[yr == yv].reset_index(drop=True)
        if len(sub) < 100:
            continue
        rr = -sub.ret_240m.to_numpy()                     # the single input that carries it (see single_input_auc)
        yy = (sub.y_ret_w > 0).astype(float).to_numpy()
        m = np.isfinite(rr) & (sub.y_ret_w != 0).to_numpy()
        by_year[int(yv)] = dict(n=int(m.sum()), auc_minus_ret240=round(study.auc(yy[m], rr[m]), 4))
    return dict(w4_single_input_auc=single, w4_entry_1min_later_auc=r_late["auc"], w4_entry_1min_later_costs=r_late["costs"],
                w4_auc_recheck=r["auc"], w4_by_year=by_year)


def _passes(t) -> bool:
    if t["q"] >= 0.05:
        return False
    k = t["id"].split("-")[0]
    if k in ("W1", "W3"):
        return t["skill_pct"] >= 5
    if k in ("W2", "W4"):
        return t["auc"] >= 0.52 and t["costs"].get("net_bps", -1) > 0
    return t["beats_costs"]


def run(root: Path, symbols, out: Path = OUT) -> dict:
    rng = np.random.default_rng(study.SEED + 2)
    tests, meta, desc = [], {}, {}
    for s in symbols:
        H, W, meta[s] = load(root, s)
        tests += [test_w1(H, s), test_w2(H, s, rng), test_w3(W, s), test_w4(W, s, rng)] + test_w5(H, s)
        desc[s] = describe(H, W)
        desc[s]["posthoc"] = posthoc(root, W, s, np.random.default_rng(study.SEED + 3))
        print(f"{s}: {len(H)} window hours, {len(W)} windows (design)", flush=True)
    q = study.bh(np.array([t["p"] for t in tests]))
    for t, qq in zip(tests, q):
        t["q"], t["p"] = round(float(qq), 4), round(float(t["p"]), 5)
        t["pass"] = bool(_passes(t))
    res = dict(study="C2", prereg="research/brain/crypto/PREREG_C2_WINDOW.md", window="00:00-09:00 IST",
               holdout_start=str(config.HOLDOUT_START), holdout_read=False, tests_in_family=len(tests),
               passed=[t["id"] for t in tests if t["pass"]], data=meta, describe=desc, tests=tests)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, indent=1) + "\n")
    print(f"{len(tests)} tests, {len(res['passed'])} pass: {res['passed']} -> {out}")
    return res


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.window")
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--cache", default=None)
    ap.add_argument("--out", default=str(OUT))
    a = ap.parse_args(argv)
    run(config.paths(a.cache).root, a.symbols, Path(a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
