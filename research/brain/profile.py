"""Volume profile ("vertical volume") from 1-minute futures OHLCV. Precision: "estimate".

Each futures bar's volume is spread uniformly over its high-low range into fixed price bins (BIN points; a bar with
high == low puts all of it in one bin). The histogram of bars <= t is the *developing* profile at t. A session's final
profile is the developing profile at its last bar, and it is valid only after the close (valid_from = 15:30): intraday
features may read only the developing profile (tests/test_profiles_levels.py checks this).

  POC   the bin with the most volume (ties: the lowest price), reported at the bin's middle
  VA    value area: start at the POC and add the neighbouring bin with more volume (ties: the one above) until 70% of
        the volume is inside; VAH / VAL are the top / bottom edges of the value area
  HVN   high-volume nodes: local peaks of the 3-bin smoothed histogram at or above its 70th percentile (non-zero bins)
  LVN   low-volume nodes: local troughs of the smoothed histogram inside the traded range at or below its 30th percentile

Everything is in absolute bins (bin j covers [j*BIN, (j+1)*BIN)), so the result never depends on the price range of
bars after t. Prices are futures prices; `levels` converts them to index points with the same session's last basis.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

FORMULA_VERSION = 1
VA_SHARE = 0.70
BIN = {"BANKNIFTY": 10.0, "NIFTY": 5.0, "FINNIFTY": 5.0, "MIDCPNIFTY": 2.5, "SENSEX": 10.0, "BANKEX": 10.0}


def bin_size(und: str) -> float:
    return BIN.get(und, 10.0)


def spread(lo, hi, close, vol, bs: float) -> tuple[int, np.ndarray]:
    """Per-bar volume spread over absolute bins: (b0, H) with H[i, j] the volume bar i puts in bin b0 + j."""
    lo, hi, close, vol = (np.asarray(a, float) for a in (lo, hi, close, vol))
    ok = np.isfinite(lo) & np.isfinite(hi) & np.isfinite(vol) & (vol > 0)
    if not ok.any():
        return 0, np.zeros((len(lo), 0))
    b0 = int(np.floor(lo[ok].min() / bs))
    b1 = int(np.floor(hi[ok].max() / bs))
    nb = b1 - b0 + 1
    e_lo = (b0 + np.arange(nb)) * bs
    e_hi = e_lo + bs
    rng = hi - lo
    with np.errstate(invalid="ignore", divide="ignore"):
        ov = np.clip(np.minimum(hi[:, None], e_hi[None, :]) - np.maximum(lo[:, None], e_lo[None, :]), 0, None)
        H = np.where((rng > 0)[:, None], ov / rng[:, None], 0.0)
    flat = ok & ~(rng > 0)
    if flat.any():
        j = np.floor(np.where(flat, hi, 0) / bs).astype(int) - b0
        H[np.flatnonzero(flat), j[flat]] = 1.0
    H = np.where(ok[:, None], H * np.where(ok, vol, 0)[:, None], 0.0)
    return b0, H


def poc_va(Hc: np.ndarray, share: float = VA_SHARE) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """For each row of a (cumulative) histogram: POC bin, VA low bin, VA high bin (relative indices; -1 if empty)."""
    n, nb = Hc.shape
    tot = Hc.sum(axis=1)
    if nb == 0:
        e = np.full(n, -1)
        return e, e.copy(), e.copy()
    r = np.arange(n)
    poc = np.argmax(Hc, axis=1)
    nz = Hc > 0
    first = np.argmax(nz, axis=1)
    last = nb - 1 - np.argmax(nz[:, ::-1], axis=1)
    lo, hi = poc.copy(), poc.copy()
    acc = Hc[r, poc].copy()
    target = share * tot
    active = (tot > 0) & (acc < target)
    while active.any():
        below = np.where(lo > first, Hc[r, np.maximum(lo - 1, 0)], -1.0)
        above = np.where(hi < last, Hc[r, np.minimum(hi + 1, nb - 1)], -1.0)
        up = above >= below
        hi = np.where(active & up, hi + 1, hi)
        lo = np.where(active & ~up, lo - 1, lo)
        acc = acc + np.where(active, np.where(up, above, below), 0.0)
        active = active & (acc < target) & ((lo > first) | (hi < last))
    empty = tot <= 0
    return np.where(empty, -1, poc), np.where(empty, -1, lo), np.where(empty, -1, hi)


def nodes(hist: np.ndarray) -> tuple[list[int], list[int]]:
    """HVN and LVN bins (relative) of one final histogram."""
    if len(hist) < 3 or hist.sum() <= 0:
        return [], []
    s = np.convolve(hist, np.ones(3) / 3, mode="same")
    nzb = np.flatnonzero(hist > 0)
    a, b = nzb[0], nzb[-1]
    pos = s[a:b + 1][s[a:b + 1] > 0]
    if not len(pos):
        return [], []
    hi_t, lo_t = np.percentile(pos, 70), np.percentile(pos, 30)
    hvn, lvn = [], []
    for j in range(a, b + 1):
        l_, r_ = s[j - 1] if j > 0 else -np.inf, s[j + 1] if j + 1 < len(s) else -np.inf
        if s[j] >= l_ and s[j] >= r_ and (s[j] > l_ or s[j] > r_) and s[j] >= hi_t:
            hvn.append(j)
        l2, r2 = (s[j - 1] if j > a else np.inf), (s[j + 1] if j < b else np.inf)
        if a < j < b and s[j] <= l2 and s[j] <= r2 and (s[j] < l2 or s[j] < r2) and s[j] <= lo_t:
            lvn.append(j)
    return hvn, lvn


def _contract_per_day(ctx) -> np.ndarray:
    nd = len(ctx.days)
    if "fut_expiry" not in ctx.m:
        return np.zeros(nd, "int64")
    e = pd.Series(pd.to_datetime(ctx.m.fut_expiry)).groupby(ctx.dayid).first().reindex(range(nd))
    return e.astype("datetime64[ns]").values.astype("int64")          # NaT (continuous series) compares equal to NaT


def _iso_week(ctx) -> np.ndarray:
    wk = pd.DatetimeIndex(ctx.days).isocalendar()
    return (wk.year.values * 100 + wk.week.values).astype(int)


def _week_sum(final: list, d: int, week, exp) -> tuple[list, int]:
    """Final profiles of the sessions before d in the same ISO week and on the same contract; and the first such day."""
    acc, start = [], d
    for e in range(d - 1, -1, -1):
        if week[e] != week[d] or exp[e] != exp[d]:
            break
        if final[e] is not None:
            acc.append(final[e])
        start = e
    return acc, start


def _stack(parts: list[tuple[int, np.ndarray]]) -> tuple[int, np.ndarray]:
    lo_b = min(b for b, _ in parts)
    hi_b = max(b + h.shape[-1] for b, h in parts)
    return lo_b, hi_b


def day_profiles(ctx) -> dict:
    """Developing day profile per row (POC/VAH/VAL in futures points), the final profile per session (valid only
    after its close), and the developing week profile's POC per row. Memoised on the context."""
    def make():
        f = ctx.fut()
        bs = bin_size(ctx.und)
        n, nd = ctx.n, len(ctx.days)
        week, exp = _iso_week(ctx), _contract_per_day(ctx)
        dev = {k: np.full(n, np.nan) for k in ("poc", "vah", "val", "week_poc")}
        final: list = []
        for d in range(nd):
            r = np.flatnonzero(ctx.dayid == d)
            b0, H = spread(f["l"][r], f["h"][r], f["c"][r], f["v"][r], bs)
            if H.shape[1] == 0:
                final.append(None)
                continue
            Hc = np.cumsum(H, axis=0)
            poc, lo, hi = poc_va(Hc)
            has = poc >= 0
            dev["poc"][r] = np.where(has, (b0 + poc + 0.5) * bs, np.nan)
            dev["val"][r] = np.where(has, (b0 + lo) * bs, np.nan)
            dev["vah"][r] = np.where(has, (b0 + hi + 1) * bs, np.nan)
            # developing week: earlier sessions of this ISO week on the same contract + today's developing profile
            acc, _ = _week_sum(final, d, week, exp)
            lo_b, hi_b = _stack([(b0, Hc)] + [(x["b0"], x["hist"]) for x in acc])
            W = np.zeros((len(r), hi_b - lo_b))
            W[:, b0 - lo_b:b0 - lo_b + Hc.shape[1]] = Hc
            for x in acc:
                W[:, x["b0"] - lo_b:x["b0"] - lo_b + len(x["hist"])] += x["hist"]
            wp = np.argmax(W, axis=1)
            dev["week_poc"][r] = np.where(W.sum(axis=1) > 0, (lo_b + wp + 0.5) * bs, np.nan)
            hist = Hc[-1]
            if hist.sum() <= 0:
                final.append(None)
                continue
            hv, lv = nodes(hist)
            final.append(dict(b0=b0, hist=hist, poc=dev["poc"][r[-1]], vah=dev["vah"][r[-1]], val=dev["val"][r[-1]],
                              hvn=[(b0 + j + 0.5) * bs for j in hv], lvn=[(b0 + j + 0.5) * bs for j in lv],
                              volume=float(hist.sum())))
        return dict(dev=dev, final=final, bin=bs, week=week, exp=exp)
    return ctx.memo("profiles", make)


def table(ctx, days_from=None) -> pd.DataFrame:
    """The L3 `profiles` rows for the sessions in ctx (from `days_from` on): one "day" row per session (the final
    profile, valid from the close) and one "week" row per session (the week so far through that close, same
    contract), plus "dev" rows: the developing POC/VAH/VAL at every minute, valid from that minute's row."""
    from . import calendar
    P = day_profiles(ctx)
    bs, week, exp = P["bin"], P["week"], P["exp"]
    fexp = (pd.Series(pd.to_datetime(ctx.m.fut_expiry)).groupby(ctx.dayid).first().reindex(range(len(ctx.days)))
            .values if "fut_expiry" in ctx.m else [pd.NaT] * len(ctx.days))
    rows = []
    for d, day in enumerate(pd.DatetimeIndex(ctx.days)):
        if days_from is not None and day.date() < days_from:
            continue
        fd = P["final"][d]
        close = calendar.expiry_ts(day.date())             # 15:30 IST that day: the session close
        base = dict(day=day, precision="estimate", formula_version=FORMULA_VERSION, bin=bs, fut_expiry=fexp[d])
        if fd is not None:
            rows.append(dict(base, kind="day", start=day, valid_from=close, b0=fd["b0"], hist=fd["hist"].tolist(),
                             poc=fd["poc"], vah=fd["vah"], val=fd["val"], hvn=fd["hvn"], lvn=fd["lvn"],
                             volume=fd["volume"]))
            acc, start = _week_sum(P["final"], d, week, exp)
            parts = [(fd["b0"], fd["hist"])] + [(x["b0"], x["hist"]) for x in acc]
            lo_b, hi_b = _stack(parts)
            h = np.zeros(hi_b - lo_b)
            for b, x in parts:
                h[b - lo_b:b - lo_b + len(x)] += x
            poc, lo, hi = poc_va(h[None, :])
            hv, lv = nodes(h)
            rows.append(dict(base, kind="week", start=pd.Timestamp(ctx.days[start]), valid_from=close, b0=lo_b,
                             hist=h.tolist(), poc=(lo_b + poc[0] + 0.5) * bs, vah=(lo_b + hi[0] + 1) * bs,
                             val=(lo_b + lo[0]) * bs, hvn=[(lo_b + j + 0.5) * bs for j in hv],
                             lvn=[(lo_b + j + 0.5) * bs for j in lv], volume=float(h.sum())))
        r = np.flatnonzero(ctx.dayid == d)
        dev = P["dev"]
        for i in r[np.isfinite(dev["poc"][r])]:
            rows.append(dict(base, kind="dev", start=day, valid_from=ctx.ts[i], b0=np.nan, hist=None,
                             poc=dev["poc"][i], vah=dev["vah"][i], val=dev["val"][i], hvn=None, lvn=None,
                             volume=np.nan))
    cols = ["day", "kind", "start", "valid_from", "poc", "vah", "val", "hvn", "lvn", "volume", "bin", "b0", "hist",
            "fut_expiry", "precision", "formula_version"]
    return pd.DataFrame(rows, columns=cols)


def asof(tbl: pd.DataFrame, ts, kind: str = "day") -> pd.DataFrame:
    """Profiles of `kind` usable at minute row `ts`: valid_from <= ts. The only sanctioned way to read the table."""
    t = tbl[(tbl.kind == kind) & (pd.to_datetime(tbl.valid_from) <= pd.Timestamp(ts))]
    return t
