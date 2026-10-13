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
    return study._direction(W, sym, "W4", WIN_MIN, "y_ret_w", _w4_cols, rng)


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


def _w4_cols(d):
    return np.c_[d.ret_240m, d.ret_1440m, d.funding_z_30d, d.basis_z_7d, d.oi_chg_24h_pct, d.vwap24_z, d.weekend]


def w4_trades(W, sym):
    """W4's own out-of-sample probabilities and trades (the same fixed fits as test_w4)."""
    keep = (W.y_ret_w != 0).to_numpy()
    df = W[keep].reset_index(drop=True)
    y = (df.y_ret_w > 0).astype(float).to_numpy()
    df = df.assign(_y=y)
    prob = study.walk(df, WIN_MIN, lambda tr, te: study.logistic(_w4_cols(tr), tr._y.to_numpy(), _w4_cols(te)))
    side = np.where(prob >= study.P_LONG, 1, np.where(prob <= study.P_SHORT, -1, 0))
    return df, prob, side


def _net(df, side, ret, entry_ts, sym, stress=1.0):
    t = (side != 0) & np.isfinite(ret)
    net = costs.net_returns(ret[t], sym, side[t], WIN_MIN, df.funding_bps.to_numpy()[t] / 1e4, entry_ts=entry_ts[t],
                            stress=stress)
    g = side[t] * ret[t]
    return dict(trades=int(t.sum()), gross_bps=round(100 * float(g.mean()), 2), net_bps=round(100 * float(net.mean()), 2))


def posthoc(root: Path, W, sym, rng=None) -> dict:
    """Post-hoc checks of W4 (written after the tests ran, 13 Oct 2026; descriptions, not tests), all on W4's OWN
    signals (the same fitted probabilities and sides as the test, never a refit):
      1. the same trades entered one minute later (00:01 IST, the close of the 18:30 UTC bar) and held to 09:00 IST;
      2. the same trades split by year (n, gross, net), and the AUC of the model's probabilities by year;
      3. single-input AUCs (which inputs carry it)."""
    df, prob, side = w4_trades(W, sym)
    files = [p for p in sorted(config.paths(root).cube(sym).glob("*/*.parquet"))
             if pd.Timestamp(p.stem).date() <= config.DESIGN_END.date()]
    c = pd.concat([pd.read_parquet(p, columns=["ts", "close", "flag"]) for p in files], ignore_index=True)
    holdout.assert_design(c)
    c = c[c.flag.isin(["ok", "stale"])].set_index("ts").close
    t1 = df.ts + pd.Timedelta(minutes=1)                         # the decision bar's successor: entry one minute later
    with np.errstate(invalid="ignore", divide="ignore"):
        late = np.log(c.reindex(df.ts + pd.Timedelta(minutes=WIN_MIN)).to_numpy() / c.reindex(t1).to_numpy()) * 100
    ret = df.y_ret_w.to_numpy()
    ent = (df.ts + pd.Timedelta(minutes=1)).to_numpy()
    out = dict(w4_as_tested=_net(df, side, ret, pd.DatetimeIndex(ent), sym),
               w4_as_tested_2x_slip=_net(df, side, ret, pd.DatetimeIndex(ent), sym, 2.0),
               w4_entry_1min_later=_net(df, side, late, pd.DatetimeIndex(ent) + pd.Timedelta(minutes=1), sym),
               w4_entry_1min_later_2x_slip=_net(df, side, late, pd.DatetimeIndex(ent) + pd.Timedelta(minutes=1), sym, 2.0))
    yr = df.ts.dt.year.to_numpy()
    y = (df.y_ret_w > 0).astype(float).to_numpy()
    by = {}
    for yv in sorted(set(yr)):
        m = (yr == yv) & np.isfinite(prob)
        if m.sum() < 30:
            continue
        r = _net(df[m].reset_index(drop=True), side[m], ret[m], pd.DatetimeIndex(ent[m]), sym)
        r["auc"] = round(study.auc(y[m], prob[m]), 4)
        r["windows"] = int(m.sum())
        by[int(yv)] = r
    out["w4_by_year"] = by
    single = {}
    for f in ["ret_240m", "ret_1440m", "funding_z_30d", "basis_z_7d", "oi_chg_24h_pct", "vwap24_z"]:
        v = df[f].to_numpy()
        m = np.isfinite(v)
        single[f] = round(study.auc(y[m], v[m]), 4)
    out["w4_single_input_auc"] = single
    return out


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
        desc[s]["posthoc"] = posthoc(root, W, s)
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
