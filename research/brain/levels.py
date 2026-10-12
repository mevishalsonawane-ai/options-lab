"""Support and resistance levels, in index points: the L3 `levels` table and the features that read it.

One row per level per session: price, type, source, formula version, valid_from (the first minute row that may use it)
and how often levels of the same kind HELD or BROKE on EARLIER sessions only (the previous STATS_SESSIONS sessions; the
session itself never counts towards its own row).

Level types (code = position in LEVEL_TYPES, from 1):
  pdh pdl pdc            previous session high / low / close                                   valid 09:15
  pwh pwl pwc            previous ISO week's high / low / last close                           valid 09:15
  pmh pml pmc            previous calendar month's high / low / last close                     valid 09:15
  pivot r1 s1 r2 s2 r3 s3, cpr_tc cpr_bc   classic floor pivots and the central pivot range, previous session
  swing_high swing_low   5-minute fractal swings (SWING_K bars each side, continuous across sessions): those confirmed
                         in the previous SWING_CARRY sessions (valid 09:15) and today's (valid from the confirming bar)
  round_major round_minor  multiples of ROUND[und] within ±ROUND_SPAN of the previous close (a major multiple is only
                         listed as major)
  or15_high or15_low     15-minute opening range, valid from 09:29 (the row that completes it)
  prev_poc prev_vah prev_val  the previous session's final futures volume profile, moved to index points with that
                         session's last basis (precision "estimate"; absent without futures volume)
  oi_call oi_put         strikes with the largest call / put OI at the previous close (ATM±10), absent when those
                         contracts expired at that close
  gap_fill               the previous close, when today's open gapped away from it by >= GAP_MIN

Held / broke (judged after the session, used only for later sessions): from valid_from on, the level is TOUCHED when a
bar's range comes within TOUCH of it; it BROKE when, at or after the first touch, a close goes through it by more than
BREAK (support: price started above it; resistance: below). Touched and not broken = HELD. Untouched = no test.
Counts are kept per type, and per (type, price) for levels that recur at the same price (swings, round numbers).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import config, profile
from .features.bars import bars

FORMULA_VERSION = 1
LEVEL_TYPES = ("pdh", "pdl", "pdc", "pwh", "pwl", "pwc", "pmh", "pml", "pmc", "pivot", "r1", "s1", "r2", "s2", "r3",
               "s3", "cpr_tc", "cpr_bc", "swing_high", "swing_low", "round_major", "round_minor", "or15_high",
               "or15_low", "prev_poc", "prev_vah", "prev_val", "oi_call", "oi_put", "gap_fill")
CODE = {t: i + 1 for i, t in enumerate(LEVEL_TYPES)}
PER_PRICE = {"swing_high", "swing_low", "round_major", "round_minor"}
ROUND = {"BANKNIFTY": (100.0, 500.0), "NIFTY": (50.0, 100.0)}
ROUND_SPAN = 0.02
SWING_K = 6
SWING_CARRY = 3
OR_MIN = 15
GAP_MIN = 0.001
TOUCH = 0.0003
BREAK = 0.001
STATS_SESSIONS = 60
MIN_TESTS = 3
NEAR = 0.002
# sessions of history the level features read: the stats window + the oldest level input of its first session
# (a previous calendar month is at most ~45 sessions back)
LOOKBACK_SESSIONS = STATS_SESSIONS + 50

SOURCE = dict(pdh="index:prev_day", pdl="index:prev_day", pdc="index:prev_day", pwh="index:prev_week",
              pwl="index:prev_week", pwc="index:prev_week", pmh="index:prev_month", pml="index:prev_month",
              pmc="index:prev_month", pivot="index:pivot_classic", r1="index:pivot_classic",
              s1="index:pivot_classic", r2="index:pivot_classic", s2="index:pivot_classic", r3="index:pivot_classic",
              s3="index:pivot_classic", cpr_tc="index:cpr", cpr_bc="index:cpr", swing_high=f"index:swing_5m_k{SWING_K}",
              swing_low=f"index:swing_5m_k{SWING_K}", round_major="round", round_minor="round",
              or15_high="index:opening_range_15m", or15_low="index:opening_range_15m",
              prev_poc="fut_profile:prev_day-basis", prev_vah="fut_profile:prev_day-basis",
              prev_val="fut_profile:prev_day-basis", oi_call="chain:max_oi_prev_close",
              oi_put="chain:max_oi_prev_close", gap_fill="index:prev_close")


def _per_day(ctx, a, how):
    return ctx.day_complete_agg(a, how).reindex(range(len(ctx.days))).values


def _last_finite(ctx, col):
    if col not in ctx.m:
        return np.full(len(ctx.days), np.nan)
    return pd.Series(ctx.m[col].values.astype(float)).groupby(ctx.dayid).last().reindex(range(len(ctx.days))).values


def _period_prev(ctx, key: np.ndarray, H, L, C):
    """For each session: high / low / last close of the latest earlier period (week or month) in the data."""
    nd = len(key)
    out = np.full((nd, 3), np.nan)
    agg = {}
    for d in range(nd):
        k = key[d]
        a = agg.setdefault(k, [np.nan, np.nan, np.nan])
        prev_keys = [x for x in agg if x < k]
        if prev_keys:
            out[d] = agg[max(prev_keys)]
        # add today's complete values only after today's levels are set
        a[0] = np.fmax(a[0], H[d])
        a[1] = np.fmin(a[1], L[d])
        a[2] = C[d] if np.isfinite(C[d]) else a[2]
    return out


def _swings(ctx):
    """Confirmed 5-minute swings: (dayid of the confirming bar, minute it is confirmed, price, +1 high / -1 low)."""
    b = bars(ctx, 5)
    out = []
    if len(b) < 2 * SWING_K + 1:
        return out
    h, l = b.high.values, b.low.values
    for i in range(SWING_K, len(b) - SWING_K):
        c = i + SWING_K
        lr = int(b.last_row.values[c])
        if h[i] > h[i - SWING_K:i].max() and h[i] >= h[i + 1:c + 1].max():
            out.append((int(ctx.dayid[lr]), int(ctx.minute[lr]), float(h[i]), 1))
        if l[i] < l[i - SWING_K:i].min() and l[i] <= l[i + 1:c + 1].min():
            out.append((int(ctx.dayid[lr]), int(ctx.minute[lr]), float(l[i]), -1))
    return out


def raw_levels(ctx) -> pd.DataFrame:
    """Every level of every session in ctx: d (dayid), vmin (first minute it may be used), price, type."""
    def make():
        nd = len(ctx.days)
        H, L, C = _per_day(ctx, ctx.h, "max"), _per_day(ctx, ctx.l, "min"), _per_day(ctx, ctx.c, "last")
        O = pd.Series(ctx.o).groupby(ctx.dayid).first().reindex(range(nd)).values
        days = pd.DatetimeIndex(ctx.days)
        wk = days.isocalendar()
        W = _period_prev(ctx, (wk.year.values * 100 + wk.week.values).astype(int), H, L, C)
        Mo = _period_prev(ctx, (days.year * 100 + days.month).values.astype(int), H, L, C)
        basis = _last_finite(ctx, "basis")
        cmax, pmax = _last_finite(ctx, "oi_max_call_strike"), _last_finite(ctx, "oi_max_put_strike")
        chain_exp = (pd.Series(pd.to_datetime(ctx.m.expiry)).groupby(ctx.dayid).first().reindex(range(nd)).values
                     if "expiry" in ctx.m else np.full(nd, np.datetime64("NaT")))
        P = profile.day_profiles(ctx)["final"] if "fut_volume" in ctx.m else [None] * nd
        minor, major = ROUND.get(ctx.und, (config.STRIKE_STEP.get(ctx.und, 100.0),) * 2)
        if minor == major:
            major = minor * 5
        # opening range from rows of the first OR_MIN minutes (known at row OR_MIN - 1)
        first = ctx.minute < OR_MIN
        orh = pd.Series(np.where(first, ctx.h, np.nan)).groupby(ctx.dayid).max().reindex(range(nd)).values
        orl = pd.Series(np.where(first, ctx.l, np.nan)).groupby(ctx.dayid).min().reindex(range(nd)).values
        or_done = pd.Series(ctx.minute == OR_MIN - 1).groupby(ctx.dayid).any().reindex(range(nd)).fillna(False).values
        sw = _swings(ctx)
        rows = []

        def add(d, vmin, price, t):
            if np.isfinite(price) and price > 0:
                rows.append((d, vmin, float(price), t))

        for d in range(1, nd):
            ph, pl, pc = H[d - 1], L[d - 1], C[d - 1]
            for t, v in (("pdh", ph), ("pdl", pl), ("pdc", pc), ("pwh", W[d, 0]), ("pwl", W[d, 1]),
                         ("pwc", W[d, 2]), ("pmh", Mo[d, 0]), ("pml", Mo[d, 1]), ("pmc", Mo[d, 2])):
                add(d, 0, v, t)
            p = (ph + pl + pc) / 3
            bc = (ph + pl) / 2
            for t, v in (("pivot", p), ("r1", 2 * p - pl), ("s1", 2 * p - ph), ("r2", p + (ph - pl)),
                         ("s2", p - (ph - pl)), ("r3", ph + 2 * (p - pl)), ("s3", pl - 2 * (ph - p)),
                         ("cpr_tc", 2 * p - bc), ("cpr_bc", bc)):
                add(d, 0, v, t)
            if np.isfinite(pc):
                lo, hi = pc * (1 - ROUND_SPAN), pc * (1 + ROUND_SPAN)
                for k in np.arange(np.ceil(lo / minor), np.floor(hi / minor) + 1):
                    x = k * minor
                    add(d, 0, x, "round_major" if abs(x / major - round(x / major)) < 1e-9 else "round_minor")
            fp = P[d - 1]
            if fp is not None and np.isfinite(basis[d - 1]):
                for t, k in (("prev_poc", "poc"), ("prev_vah", "vah"), ("prev_val", "val")):
                    add(d, 0, fp[k] - basis[d - 1], t)
            if pd.notna(chain_exp[d - 1]) and pd.notna(chain_exp[d]) and chain_exp[d - 1] == chain_exp[d]:
                add(d, 0, cmax[d - 1], "oi_call")
                add(d, 0, pmax[d - 1], "oi_put")
            if np.isfinite(O[d]) and np.isfinite(pc) and abs(O[d] / pc - 1) >= GAP_MIN:
                add(d, 0, pc, "gap_fill")
        for d in range(nd):
            if or_done[d]:
                add(d, OR_MIN - 1, orh[d], "or15_high")
                add(d, OR_MIN - 1, orl[d], "or15_low")
        for (dd, mn, price, s) in sw:
            t = "swing_high" if s > 0 else "swing_low"
            add(dd, mn, price, t)                                       # confirmed today: valid from that minute
            for d in range(dd + 1, min(nd, dd + 1 + SWING_CARRY)):      # carried into the next sessions
                add(d, 0, price, t)
        df = pd.DataFrame(rows, columns=["d", "vmin", "price", "type"])
        df = df.drop_duplicates(["d", "type", "price"], keep="first")
        df["code"] = df.type.map(CODE).astype(int)
        df["key"] = np.where(df.type.isin(PER_PRICE), df.type + ":" + df.price.round(2).astype(str), df.type)
        return df.sort_values(["d", "price", "code", "vmin"]).reset_index(drop=True)
    return ctx.memo("levels_raw", make)


def _outcomes(ctx, lv: pd.DataFrame) -> np.ndarray:
    """Per level row: +1 held, -1 broke, 0 untested, judged on its own session's rows from vmin on. Only ever used
    for LATER sessions."""
    out = np.zeros(len(lv))
    for d, g in lv.groupby("d"):
        r = np.flatnonzero(ctx.dayid == d)
        mn, h, l, c = ctx.minute[r], ctx.h[r], ctx.l[r], ctx.c[r]
        P = g.price.values[:, None]
        after = mn[None, :] >= g.vmin.values[:, None]
        okc = after & np.isfinite(c)[None, :]
        j0 = np.argmax(okc, axis=1)
        has = okc.any(axis=1)
        ref = np.where(has, c[j0], np.nan)
        support = ref[:, None] >= P
        with np.errstate(invalid="ignore"):
            touch = after & (l[None, :] <= P * (1 + TOUCH)) & (h[None, :] >= P * (1 - TOUCH))
            t0 = np.where(touch.any(axis=1), np.argmax(touch, axis=1), r.size)
            since = np.arange(r.size)[None, :] >= t0[:, None]
            brk = since & np.where(support, c[None, :] < P * (1 - BREAK), c[None, :] > P * (1 + BREAK))
        out[g.index.values] = np.where(~touch.any(axis=1) | ~has, 0, np.where(brk.any(axis=1), -1, 1))
    return out


def with_stats(ctx) -> pd.DataFrame:
    """raw_levels + held / broke / tests / strength from the previous STATS_SESSIONS sessions (same key)."""
    def make():
        lv = raw_levels(ctx).copy()
        nd = len(ctx.days)
        oc = _outcomes(ctx, lv)
        lv["held"], lv["broke"], lv["n_days"] = 0, 0, np.minimum(lv.d.values, STATS_SESSIONS)
        if len(lv):
            o = pd.DataFrame(dict(d=lv.d, key=lv.key, held=(oc > 0).astype(int), broke=(oc < 0).astype(int)))
            per = o.groupby(["key", "d"])[["held", "broke"]].sum()
            for key, g in lv.groupby("key"):
                if key not in per.index.get_level_values(0):
                    continue
                s = per.loc[key].reindex(range(nd), fill_value=0)
                ch = np.r_[0, np.cumsum(s.held.values)]
                cb = np.r_[0, np.cumsum(s.broke.values)]
                d = g.d.values
                lo = np.maximum(d - STATS_SESSIONS, 0)
                lv.loc[g.index, "held"] = ch[d] - ch[lo]           # sessions lo .. d-1: strictly earlier
                lv.loc[g.index, "broke"] = cb[d] - cb[lo]
        lv["tests"] = lv.held + lv.broke
        with np.errstate(invalid="ignore", divide="ignore"):
            lv["strength"] = np.where(lv.tests >= MIN_TESTS, lv.held / lv.tests, np.nan)
        return lv
    return ctx.memo("levels", make)


def nearest(ctx) -> dict:
    """Per row: nearest level above / below the close among today's levels already valid; their type code and
    strength; and the number of levels within NEAR of the close."""
    def make():
        lv = with_stats(ctx)
        n = ctx.n
        out = {k: np.full(n, np.nan) for k in ("up", "dn", "up_code", "dn_code", "up_str", "dn_str", "near")}
        by_day = {d: g for d, g in lv.groupby("d")}
        for d in range(len(ctx.days)):
            r = np.flatnonzero(ctx.dayid == d)
            c = ctx.c[r]
            fin = np.isfinite(c)
            g = by_day.get(d)
            if g is None or not len(g):
                out["near"][r] = np.where(fin, 0.0, np.nan)
                continue
            P, code, st = g.price.values, g.code.values.astype(float), g.strength.values
            valid = ctx.minute[r][:, None] >= g.vmin.values[None, :]
            with np.errstate(invalid="ignore"):
                above = valid & (P[None, :] > c[:, None])
                below = valid & (P[None, :] < c[:, None])
                # levels are sorted by (price, code): argmin / argmax-from-the-end pick the lowest code at a tie
                ja = np.argmin(np.where(above, P[None, :], np.inf), axis=1)
                rev = np.where(below, P[None, :], -np.inf)[:, ::-1]
                jb = len(P) - 1 - np.argmax(rev, axis=1)
                # at a tie below, prefer the lowest code: move to the first column with the same price
                first_at = {p: i for i, p in reversed(list(enumerate(P)))}
                jb = np.array([first_at[P[j]] for j in jb])
                near = (valid & (np.abs(P[None, :] / c[:, None] - 1) <= NEAR)).sum(axis=1).astype(float)
            ha, hb = above.any(axis=1) & fin, below.any(axis=1) & fin
            out["up"][r] = np.where(ha, (P[ja] / c - 1) * 100, np.nan)
            out["dn"][r] = np.where(hb, (c / P[jb] - 1) * 100, np.nan)
            out["up_code"][r] = np.where(ha, code[ja], np.nan)
            out["dn_code"][r] = np.where(hb, code[jb], np.nan)
            out["up_str"][r] = np.where(ha, st[ja], np.nan)
            out["dn_str"][r] = np.where(hb, st[jb], np.nan)
            out["near"][r] = np.where(fin, near, np.nan)
        return out
    return ctx.memo("levels_nearest", make)


def table(ctx, days_from=None) -> pd.DataFrame:
    """The L3 `levels` rows for the sessions in ctx (from `days_from` on)."""
    lv = with_stats(ctx)
    days = pd.DatetimeIndex(ctx.days)
    h, m = config.SESSION_OPEN
    day = days[lv.d.values]
    out = pd.DataFrame(dict(
        day=day, price=lv.price.values, type=lv.type.values, code=lv.code.values,
        source=lv.type.map(SOURCE).values, formula_version=FORMULA_VERSION,
        valid_from=day + pd.to_timedelta(h * 60 + m + lv.vmin.values, unit="min"),
        held=lv.held.values, broke=lv.broke.values, tests=lv.tests.values, strength=lv.strength.values,
        stats_sessions=lv.n_days.values,
        # precision applies to volume-based values: the profile levels are estimates from 1-minute bars
        precision=np.where(lv.type.isin(["prev_poc", "prev_vah", "prev_val"]), "estimate", "n/a")))
    if days_from is not None:
        out = out[out.day.dt.date >= days_from]
    return out.reset_index(drop=True)
