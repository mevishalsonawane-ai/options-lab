"""R30 crypto data (PREREG Q5): BTC / ETH USDT-M perpetual 1-minute bars -> decisions every 15 minutes, point-in-time
inputs, labels and simulated perp trades, every one with a stop-loss and a profit lock (CLAUDE.md crypto rule).

    build(part) -> <OUT>/crypto/q5_<part>.pkl   (part "design" or "holdout"; the holdout only inside the one read)

Grid: one row per UTC minute, stamped at the bar's START (row t closes at t + 1 min). Missing minutes stay NaN (mask 0).
A decision at row t uses rows <= t; the trade fills at the open of row t + 1; labels and trades read rows > t only.
"""
from __future__ import annotations

import pickle

import numpy as np
import pandas as pd

import r30cfg as K
from india import HoldoutError, holdout_open

SEQ_CH = ("ret", "hl", "logvolz", "taker", "mask")


def load_bars(sym: str) -> pd.DataFrame:
    files = sorted((K.CRYPTO_RAW / sym).glob("*/*.parquet"))
    df = pd.concat([pd.read_parquet(p) for p in files], ignore_index=True)
    df["ts"] = pd.to_datetime(df.open_time, unit="ms")
    df = df.drop_duplicates("ts").set_index("ts").sort_index()
    grid = pd.date_range(df.index[0].floor("D"), df.index[-1], freq="1min")
    return df.reindex(grid)[["open", "high", "low", "close", "volume", "count", "taker_buy_volume"]]


def _roll(s: pd.Series, w: int, how: str) -> pd.Series:
    r = s.rolling(w, min_periods=int(w * 0.8))
    return getattr(r, how)()


def features(B: pd.DataFrame) -> tuple[pd.DataFrame, np.ndarray, pd.Series]:
    """Tabular features (rows <= t only: trailing windows), the sequence channels, and sigma_1m (SD of 1-minute log
    returns over the previous 1440 minutes, for the stop distances)."""
    c, h, lo, v = B.close, B.high, B.low, B.volume
    lc = np.log(c)
    r1 = lc.diff()
    F = pd.DataFrame(index=B.index)
    for k in (5, 15, 60, 240, 1440):
        F[f"ret_{k}"] = (lc - lc.shift(k)) * 100
    for k in (60, 240, 1440):
        F[f"rv_{k}"] = _roll(r1, k, "std") * 100
    F["rng_60"] = (_roll(h, 60, "max") - _roll(lo, 60, "min")) / c * 100
    v60, v1440 = _roll(v, 60, "sum"), _roll(v, 1440, "sum")
    F["volz"] = np.log(v60 / (v1440 / 24))
    for k in (15, 60):
        F[f"taker_{k}"] = _roll(B.taker_buy_volume, k, "sum") / _roll(v, k, "sum")
    F["countz"] = np.log(_roll(B["count"], 60, "sum") / (_roll(B["count"], 1440, "sum") / 24))
    mins = B.index.hour * 60 + B.index.minute
    F["hod_sin"], F["hod_cos"] = np.sin(2 * np.pi * mins / 1440), np.cos(2 * np.pi * mins / 1440)
    for d in range(7):
        F[f"dow_{d}"] = (B.index.dayofweek == d).astype(float)
    F["dist_hi"] = (lc - np.log(_roll(h, 1440, "max"))) * 100
    F["dist_lo"] = (lc - np.log(_roll(lo, 1440, "min"))) * 100
    F = F.replace([np.inf, -np.inf], np.nan)
    vm = _roll(v, 1440, "mean")
    with np.errstate(all="ignore"):
        ch = np.column_stack([r1.values * 100, ((h - lo) / c).values * 100, np.log((v / vm).values + 1e-9),
                              (B.taker_buy_volume / v).values])
    mask = np.isfinite(ch[:, 0]) & np.isfinite(ch[:, 1])
    ch = np.where(np.isfinite(ch), ch, 0.0)
    S = np.column_stack([ch, mask]).astype(np.float32)
    sig = _roll(r1, 1440, "std")
    return F, S, sig


def labels(B: pd.DataFrame, h: int) -> tuple[np.ndarray, np.ndarray]:
    """y_ret (log return x 100 from the close of t to the close of t+h) and y_range (log of the range of rows
    t+1..t+h, % of close t). NaN when any bar of the window is missing."""
    c, hi, lo = B.close.values, B.high.values, B.low.values
    n = len(c)
    ret = np.full(n, np.nan)
    ret[:-h] = np.log(c[h:] / c[:-h]) * 100
    hs = pd.Series(hi[::-1]).rolling(h, min_periods=h).max().values[::-1]     # hs[t] = max hi[t .. t+h-1]
    ls = pd.Series(lo[::-1]).rolling(h, min_periods=h).min().values[::-1]
    rng = np.full(n, np.nan)
    rng[:-1] = (hs[1:] - ls[1:]) / c[:-1] * 100
    with np.errstate(all="ignore"):
        return ret, np.log(rng)


def sim_perp(B: pd.DataFrame, rows: np.ndarray, side: int, h: int, sig_h: np.ndarray, stress: float = 1.0,
             sym: str = "BTCUSDT") -> dict:
    """Vectorised perp trades: enter at the open of t+1 on `side` (+1 long / -1 short); stop-loss at 1.0 sigma_h;
    profit lock (break-even after costs once +1.0 sigma_h, then trail best - 1.0 sigma_h, never below break-even);
    time exit at the close of t+h. Within a bar the stop is taken before a new extreme (conservative); a bar that opens
    through the stop fills at its open. Returns net Rs per trade (qty C_QTY) and flags."""
    o, hi, lo, c = (B[k].values for k in ("open", "high", "low", "close"))
    n = len(rows)
    px0 = o[rows + 1]
    fee = K.C_FEE * (1 + K.C_GST)
    slip = K.C_SLIP * stress
    cost_frac = 2 * (fee + slip)
    sg = sig_h / 100.0
    stop = px0 * (1 - side * K.C_STOP_SIG * sg)
    be = px0 * (1 + side * cost_frac)
    best = px0.copy()
    locked = np.zeros(n, bool)
    done = np.zeros(n, bool)
    exit_px = np.full(n, np.nan)
    exit_k = np.full(n, h, dtype=int)
    for k in range(1, h + 1):
        r = rows + k
        bo, bh, bl, bc = o[r], hi[r], lo[r], c[r]
        live = ~done & np.isfinite(bo)
        if side > 0:
            gap = live & (bo <= stop)
            hit = live & ~gap & (bl <= stop)
        else:
            gap = live & (bo >= stop)
            hit = live & ~gap & (bh >= stop)
        exit_px[gap] = bo[gap]
        exit_px[hit] = stop[hit]
        exit_k[gap | hit] = k
        done |= gap | hit
        live &= ~done
        ext = bh if side > 0 else bl
        best = np.where(live & np.isfinite(ext), (np.maximum(best, ext) if side > 0 else np.minimum(best, ext)), best)
        newlock = live & ~locked & (side * (best / px0 - 1) >= K.C_LOCK_SIG * sg)
        locked |= newlock
        trail = best * (1 - side * K.C_TRAIL_SIG * sg)
        cand = np.where(side > 0, np.maximum(trail, be), np.minimum(trail, be))
        stop = np.where(locked & live, (np.maximum(stop, cand) if side > 0 else np.minimum(stop, cand)), stop)
        if k == h:
            last = live & np.isfinite(bc)
            exit_px[last] = bc[last]
            done |= last
    ok = np.isfinite(px0) & np.isfinite(exit_px)
    gross = side * (exit_px / px0 - 1)
    net_frac = gross - cost_frac
    qty = K.C_QTY[sym]
    net = np.where(ok, net_frac * px0 * qty * K.INR_PER_USD, np.nan)
    # big winner cut short: within h more minutes after the exit, price goes a further 2 sigma_h our way
    cut = np.zeros(n, bool)
    after_end = np.minimum(rows + exit_k + h, len(c) - 1)
    for i in np.flatnonzero(ok):
        a, b = rows[i] + exit_k[i] + 1, after_end[i]
        if b > a:
            ext = np.nanmax(hi[a:b + 1]) if side > 0 else np.nanmin(lo[a:b + 1])
            cut[i] = bool(np.isfinite(ext) and side * (ext / exit_px[i] - 1) >= 2 * sg[i])
    return dict(net=net, cut=cut, exit_k=exit_k)


def ist_minute(ts: pd.DatetimeIndex) -> np.ndarray:
    return np.asarray((ts.hour * 60 + ts.minute + 330) % 1440)


def build(part: str) -> dict:
    if part == "holdout" and not holdout_open("crypto"):
        raise HoldoutError("the crypto holdout is built only inside run.py's one holdout read")
    frames, seqs = [], []
    for sym in K.COINS:
        B = load_bars(sym)
        F, S, sig = features(B)
        idx = np.flatnonzero(np.asarray(B.index.minute % 15 == 14))          # bars closing on :00 / :15 / :30 / :45
        idx = idx[(idx >= K.C_SEQ_N) & (idx < len(B) - max(K.C_HORIZONS) * 2 - 2)]
        ts = B.index[idx]
        keep = np.array([K.c_part(t, max(K.C_HORIZONS) + 1) == part for t in ts])
        # rows whose 240-minute window leaves the part are kept for the shorter horizons (their own check below)
        keep |= np.array([K.c_part(t, 16) == part for t in ts])
        idx, ts = idx[keep], ts[keep]
        T = F.iloc[idx].reset_index(drop=True)
        T.insert(0, "coin_eth", float(sym == "ETHUSDT"))
        T.insert(0, "ts", ts)
        T.insert(0, "und", sym)
        T["day"] = ts.normalize()
        ist = ist_minute(ts + pd.Timedelta(minutes=1))                      # the entry minute (open of t+1)
        for h in K.C_HORIZONS:
            ok_h = np.array([K.c_part(t, h + 1) == part for t in ts])
            ret, rng = labels(B, h)
            T[f"y_ret_{h}"] = np.where(ok_h, ret[idx], np.nan)
            T[f"y_range_{h}"] = np.where(ok_h, rng[idx], np.nan)
            sig_h = sig.values[idx] * np.sqrt(h)
            T[f"tr_{h}_sig"] = sig_h
            win = ok_h & (ist < K.IST_WINDOW[1]) & (ist + h <= K.IST_WINDOW[1]) & np.isfinite(sig_h)
            T[f"tr_{h}_inwin"] = win
            ok_cut = np.array([K.c_part(t, 2 * h + 2) == part for t in ts])      # the after-exit look stays in the part
            for side, nm in ((1, "L"), (-1, "S")):
                for stress, suf in ((1.0, ""), (2.0, "_x2")):
                    out = sim_perp(B, idx, side, h, np.where(np.isfinite(sig_h), sig_h, 0.0), stress, sym)
                    T[f"tr_{h}_{nm}_net{suf}"] = np.where(win, out["net"], np.nan)
                    if not suf:
                        T[f"tr_{h}_{nm}_cut"] = np.where(win & ok_cut, out["cut"], np.nan)
        frames.append(T)
        seqs.append(np.stack([S[i - K.C_SEQ_N + 1:i + 1] for i in idx]))
        print(sym, part, len(T), flush=True)
    return dict(q5=pd.concat(frames, ignore_index=True), q5_seq=np.concatenate(seqs))


def path(part: str):
    return K.OUT / "crypto" / f"q5_{part}.pkl"


def save(part: str) -> dict:
    out = build(part)
    p = path(part)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out, protocol=4))
    return out


def load(part: str) -> dict:
    if part == "holdout" and not holdout_open("crypto"):
        raise HoldoutError("the crypto holdout is read only inside run.py's one holdout read")
    return pickle.loads(path(part).read_bytes())
