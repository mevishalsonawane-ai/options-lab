"""Gold lab pack, step 5: the neural-network-ready dataset (15 Oct 2026).

Each sample is anchored at the CLOSE of a 1-minute row t (every STRIDE minutes, on UTC minutes divisible by STRIDE):
    x1   (W1=120, F1)  the last 120 one-minute rows up to and including t           (STEP1 features)
    x15  (W15=48, F15) the last 48 COMPLETED 15-minute bars as of t's close          (STEP15 features)
    ctx  (C,)          context at t from the feature store                          (CTX features)
    y    (K,)          labels from xauusd_1m_labels.parquet (LABELS); NaN where undefined
    ts, win_start, h15_start, label_end   int64 ns UTC (anchor open time, first 1m row, first 15m bar, horizon end)
Every input is normalised as (x - mean) / std with statistics fitted on the TRAIN span only (norm_stats.json),
clipped to +-CLIP, NaN -> 0, stored float16. Categorical inputs (session one-hot, trend, break events) are not scaled.

Splits (splits.json): final_test = the last 3 months (read ONCE, never for tuning), validation = the 3 months before,
train = everything earlier. A sample belongs to the part holding its anchor and is DROPPED when its 1-minute window,
its 15-minute window or its longest label horizon (240 min) reaches outside that part, so no sample sees or scores
data across a boundary. Walk-forward folds (expanding train, next month validation) all lie inside train +
validation; use `in_span` to keep a fold's samples clear of its own boundaries.

Shards: {split}_{YYYY-MM}.npz (np.savez_compressed). Experimental: gaf64_{split}_{YYYY-MM}.npz holds Gramian Angular
Summation Field images (64 x 64, uint8 = round((g + 1) * 127.5)) of the last 64 one-minute mid closes, for a CNN.
"""
from __future__ import annotations

import json
import os

import numpy as np
import pandas as pd

import xau_bars
import xau_features

W1, W15, STRIDE, GAF_N, GAF_STRIDE, CLIP = 120, 48, 5, 64, 15, 8.0
HORIZON_MIN = 240
STEP1 = ["ret_1", "hl_range", "body", "spread_bp", "spread_z", "vol_log", "vol_z", "gap_log", "rv_30",
         "sess_pos", "day_pos", "dist_vwap_sess"]
STEP15 = ["ret", "hl_range", "body", "close_pos", "spread_bp", "vol_log", "fill"]
SESSIONS = ["asia", "london", "overlap", "ny", "off"]
CATEGORICAL = [f"sess_{s}" for s in SESSIONS] + ["trend_15m", "trend_1h", "event_15m", "event_1h", "asia_done"]
CTX = (["ret_5", "ret_15", "ret_60", "ret_240", "rv_240"]
       + [f"atr_rel_{tf}" for tf in ("1m", "5m", "15m", "1h", "4h", "1d")]
       + [f"dist_{k}" for k in ("vwap_sess", "pdh", "pdl", "pdc", "asia_high", "asia_low", "poc_prev", "vah_prev",
                                "val_prev", "swing_hi_15m", "swing_lo_15m", "swing_hi_1h", "swing_lo_1h")]
       + ["bars_since_break_15m", "bars_since_break_1h", "tod_sin", "tod_cos", "dow_sin", "dow_cos",
          "log_min_to_event", "log_min_since_event"] + CATEGORICAL)
LABELS = (["fwd_ret_5", "fwd_ret_15", "fwd_ret_60", "fwd_ret_240"]
          + [f"{s}_{k}_{h}" for h in (60, 240) for s in ("long", "short") for k in ("hit", "mfe", "mae")]
          + ["R_atr15"])


def make_splits(first_ts, last_ts, test=pd.DateOffset(months=3), val=pd.DateOffset(months=3),
                fold=pd.DateOffset(months=1)):
    """Fixed walk-forward split by calendar: final test = last 3 months, validation = the 3 months before."""
    end = pd.Timestamp(last_ts).tz_convert("UTC").normalize() + pd.Timedelta(days=1)
    test0 = end - test
    val0 = test0 - val
    start = pd.Timestamp(first_ts).tz_convert("UTC").normalize()
    iso = lambda t: t.isoformat()  # noqa: E731
    folds = []
    k = val0
    while k + fold <= test0:
        folds.append({"train": [iso(start), iso(k)], "validation": [iso(k), iso(k + fold)]})
        k = k + fold
    return {"train": [iso(start), iso(val0)], "validation": [iso(val0), iso(test0)],
            "final_test": [iso(test0), iso(end)], "walk_forward_folds": folds,
            "rules": "Normalisation fitted on train only. final_test is read ONCE, after every choice is frozen. "
                     "A sample is kept only when its inputs and its 240-min label horizon lie inside its part."}


def _ns(x):
    return pd.Series(x).to_numpy("datetime64[ns]").astype("int64")


def step1_frame(df, feat):
    c = df.close.to_numpy(float)
    return pd.DataFrame({
        "ret_1": feat.ret_1.to_numpy(), "hl_range": (df.high - df.low).to_numpy() / c,
        "body": (df.close - df.open).to_numpy() / c, "spread_bp": feat.spread_bp.to_numpy(),
        "spread_z": feat.spread_z.to_numpy(), "vol_log": feat.vol_log.to_numpy(), "vol_z": feat.vol_z.to_numpy(),
        "gap_log": np.log(np.maximum(df.gap_min.to_numpy(float), 1.0)), "rv_30": feat.rv_30.to_numpy(),
        "sess_pos": feat.sess_pos.to_numpy(), "day_pos": feat.day_pos.to_numpy(),
        "dist_vwap_sess": feat.dist_vwap_sess.to_numpy()})


def step15_frame(b15):
    c = b15.close.to_numpy(float)
    rng = (b15.high - b15.low).to_numpy()
    return pd.DataFrame({
        "ret": np.log(b15.close / b15.close.shift(1)).to_numpy(), "hl_range": rng / c,
        "body": (b15.close - b15.open).to_numpy() / c,
        "close_pos": np.where(rng > 0, (b15.close - b15.low).to_numpy() / np.where(rng > 0, rng, 1), 0.5),
        "spread_bp": b15.spread_mean.to_numpy() / c * 1e4, "vol_log": np.log1p(b15.volume.to_numpy()),
        "fill": b15.n_minutes.to_numpy() / 15.0})


def ctx_frame(df, feat):
    c = df.close.to_numpy(float)
    out = {k: feat[k].to_numpy() for k in ("ret_5", "ret_15", "ret_60", "ret_240", "rv_240")}
    for tf in ("1m", "5m", "15m", "1h", "4h", "1d"):
        out[f"atr_rel_{tf}"] = feat[f"atr14_{tf}"].to_numpy() / c
    for k in CTX:
        if k.startswith("dist_"):
            out[k] = feat[k].to_numpy()
    out["bars_since_break_15m"] = np.log1p(feat.bars_since_break_15m.to_numpy())
    out["bars_since_break_1h"] = np.log1p(feat.bars_since_break_1h.to_numpy())
    tod = df.utc_minute.to_numpy(float) / 1440 * 2 * np.pi
    dow = df.dow_trading.to_numpy(float) / 7 * 2 * np.pi
    out.update(tod_sin=np.sin(tod), tod_cos=np.cos(tod), dow_sin=np.sin(dow), dow_cos=np.cos(dow))
    out["log_min_to_event"] = np.log1p(feat.min_to_event.to_numpy(float))
    out["log_min_since_event"] = np.log1p(feat.min_since_event.to_numpy(float))
    sess = np.asarray(feat.session.astype(str))
    for s in SESSIONS:
        out[f"sess_{s}"] = (sess == s).astype(float)
    for k in ("trend_15m", "trend_1h"):
        out[k] = feat[k].to_numpy()
    out["event_15m"], out["event_1h"] = feat.event_15m.to_numpy(), feat.event_1h.to_numpy()
    out["asia_done"] = feat.asia_done.to_numpy(float)
    return pd.DataFrame(out)[CTX]


def fit_norm(frame, train_mask, categorical=()):
    """{column: [mean, std]} from the train rows; categorical columns get [0, 1]."""
    st = {}
    for k in frame.columns:
        if k in categorical:
            st[k] = [0.0, 1.0]
            continue
        v = frame[k].to_numpy(float)[train_mask]
        v = v[np.isfinite(v)]
        mu, sd = (float(v.mean()), float(v.std())) if len(v) else (0.0, 1.0)
        st[k] = [mu, sd if sd > 0 else 1.0]
    return st


def apply_norm(frame, st):
    x = np.stack([(frame[k].to_numpy(float) - st[k][0]) / st[k][1] for k in frame.columns], axis=1)
    x = np.clip(x, -CLIP, CLIP)
    x[~np.isfinite(x)] = 0.0
    return x.astype(np.float16)


def part_of(ts_ns, splits):
    """Part name per anchor ("train" / "validation" / "final_test" / "" outside)."""
    out = np.full(len(ts_ns), "", dtype=object)
    for p in ("train", "validation", "final_test"):
        a, b = (_ns([pd.Timestamp(x)])[0] for x in splits[p])
        out[(ts_ns >= a) & (ts_ns < b)] = p
    return out


def in_span(meta, start, end):
    """Samples whose inputs AND label horizon lie inside [start, end) (use for walk-forward folds)."""
    a, b = (_ns([pd.Timestamp(x)])[0] for x in (start, end))
    return (meta["win_start"] >= a) & (meta["h15_start"] >= a) & (meta["ts"] >= a) & (meta["label_end"] <= b)


def gasf(x):
    """Gramian Angular Summation Field of each row of x (n, N): rescale to [-1, 1] by the row's min / max,
    G_ij = cos(phi_i + phi_j) = x_i x_j - sqrt(1 - x_i^2) sqrt(1 - x_j^2). Returns uint8 = round((G + 1) * 127.5)."""
    lo, hi = x.min(axis=1, keepdims=True), x.max(axis=1, keepdims=True)
    s = np.where(hi > lo, (2 * x - hi - lo) / np.where(hi > lo, hi - lo, 1), 0.0).clip(-1, 1)
    r = np.sqrt(1 - s ** 2)
    g = s[:, :, None] * s[:, None, :] - r[:, :, None] * r[:, None, :]
    return np.round((g + 1) * 127.5).astype(np.uint8)


def build(df, feat, lab, out_dir, stride=STRIDE, gaf=True, gaf_stride=GAF_STRIDE, splits=None, log=print):
    """Write splits.json, norm_stats.json and the month shards; returns a summary dict."""
    df, feat, lab = (x.reset_index(drop=True) for x in (df, feat, lab))
    os.makedirs(out_dir, exist_ok=True)
    splits = splits or make_splits(df.ts_utc.iloc[0], df.ts_utc.iloc[-1])
    ts = _ns(df.ts_utc)
    b15 = xau_bars.make_bars(df, "15m")
    idx15 = xau_features.last_completed(df, b15)
    b15_ts = _ns(b15.ts_utc)
    tr0, tr1 = (_ns([pd.Timestamp(x)])[0] for x in splits["train"])
    train_rows = (ts >= tr0) & (ts < tr1)
    s1, s15, sc = step1_frame(df, feat), step15_frame(b15), ctx_frame(df, feat)
    stats = {"step1": fit_norm(s1, train_rows),
             "step15": fit_norm(s15, (b15_ts >= tr0) & (_ns(b15.ts_end) <= tr1)),
             "ctx": fit_norm(sc, train_rows, CATEGORICAL)}
    X1, X15, XC = apply_norm(s1, stats["step1"]), apply_norm(s15, stats["step15"]), apply_norm(sc, stats["ctx"])
    Y = lab[LABELS].to_numpy(np.float32)
    part = part_of(ts, splits)
    bounds = {p: (_ns([pd.Timestamp(splits[p][0])])[0], _ns([pd.Timestamp(splits[p][1])])[0]) for p in splits
              if p in ("train", "validation", "final_test")}
    lo_part = np.array([bounds[p][0] if p else 0 for p in part], dtype=np.int64)
    hi_part = np.array([bounds[p][1] if p else 0 for p in part], dtype=np.int64)
    rows = np.arange(len(df))
    label_end = ts + (HORIZON_MIN + 1) * 60_000_000_000
    win_start = np.where(rows >= W1 - 1, ts[np.maximum(rows - (W1 - 1), 0)], np.iinfo(np.int64).min)
    h15_first = idx15 - (W15 - 1)
    h15_start = np.where(h15_first >= 0, b15_ts[np.maximum(h15_first, 0)], np.iinfo(np.int64).min)
    keep_base = (part != "") & (win_start >= lo_part) & (h15_start >= lo_part) & (label_end <= hi_part)
    summary = {"splits": splits, "shards": {}, "gaf_shards": {}}
    month = df.ts_utc.dt.strftime("%Y-%m").to_numpy()
    utc_min = df.utc_minute.to_numpy()
    with open(os.path.join(out_dir, "splits.json"), "w") as fh:
        json.dump(splits, fh, indent=1)
    with open(os.path.join(out_dir, "norm_stats.json"), "w") as fh:
        json.dump({"fitted_on": splits["train"], "clip": CLIP, **stats}, fh, indent=1)
    for p in ("train", "validation", "final_test"):
        for mo in sorted(set(month[part == p])):
            sel = np.flatnonzero(keep_base & (part == p) & (month == mo) & (utc_min % stride == 0))
            if len(sel):
                w = sel[:, None] + np.arange(-(W1 - 1), 1)
                w15 = idx15[sel][:, None] + np.arange(-(W15 - 1), 1)
                fn = f"{p}_{mo}.npz"
                np.savez_compressed(os.path.join(out_dir, fn), x1=X1[w], x15=X15[w15], ctx=XC[sel], y=Y[sel],
                                    ts=ts[sel], win_start=win_start[sel], h15_start=h15_start[sel],
                                    label_end=label_end[sel])
                summary["shards"][fn] = int(len(sel))
            if gaf:
                gk = keep_base & (part == p) & (month == mo) & (utc_min % gaf_stride == 0) & (rows >= GAF_N - 1)
                gk &= np.where(rows >= GAF_N - 1, ts[np.maximum(rows - (GAF_N - 1), 0)], 0) >= lo_part
                gsel = np.flatnonzero(gk)
                if len(gsel):
                    c = df.close.to_numpy(float)[gsel[:, None] + np.arange(-(GAF_N - 1), 1)]
                    fn = f"gaf64_{p}_{mo}.npz"
                    np.savez_compressed(os.path.join(out_dir, fn), gaf=gasf(c), ts=ts[gsel], y=Y[gsel])
                    summary["gaf_shards"][fn] = int(len(gsel))
            log(p, mo, summary["shards"].get(f"{p}_{mo}.npz", 0))
    summary["shapes"] = {"x1": [W1, len(STEP1)], "x15": [W15, len(STEP15)], "ctx": [len(CTX)], "y": [len(LABELS)],
                         "gaf": [GAF_N, GAF_N]}
    summary["names"] = {"step1": STEP1, "step15": STEP15, "ctx": CTX, "labels": LABELS}
    with open(os.path.join(out_dir, "dataset.json"), "w") as fh:
        json.dump(summary, fh, indent=1)
    return summary
