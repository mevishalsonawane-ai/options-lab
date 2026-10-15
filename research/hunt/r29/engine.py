"""R29 engine: events -> option trades (ATM and 1-ITM, exits X1-X4, follow and fade), index labels, the one-at-a-time
gate and random-entry twins. Shared by the mining step and the rule tests, so both trade exactly the same way."""
from __future__ import annotations

import zlib
from datetime import date

import numpy as np
import pandas as pd

import r29cfg as K
import r29data as D
import sim as S

OPTS = {"ATM": 0, "ITM1": 1}
HORIZONS = (15, 30, 60)


class IndexGrid:
    """Index close/high/low on the (session, minute) grid, for forward-move labels (labels only, never features)."""

    def __init__(self, M: pd.DataFrame):
        self.days = sorted(pd.to_datetime(M.day.unique()).date)
        pos = {d: i for i, d in enumerate(self.days)}
        di = np.array([pos[d] for d in pd.to_datetime(M.day.values).date])
        ok = (M.flag.values == "ok")
        self.c = np.full((len(self.days), K.N), np.nan)
        self.h, self.l = self.c.copy(), self.c.copy()
        mm = M.minute.values
        self.c[di[ok], mm[ok]] = M.close.values[ok]
        self.h[di[ok], mm[ok]] = M.high.values[ok]
        self.l[di[ok], mm[ok]] = M.low.values[ok]
        self.pos = pos

    def labels(self, day, m, dirn) -> dict:
        i = self.pos[day]
        c0 = self.c[i, m]
        out = {}
        for hz in HORIZONS:
            j = m + hz
            out[f"mv{hz}"] = dirn * (self.c[i, j] / c0 - 1) * 1e4 if j < K.N else np.nan
        j = min(m + 60, K.N - 1)
        hh, ll = np.nanmax(self.h[i, m + 1:j + 1]) if j > m else np.nan, np.nanmin(self.l[i, m + 1:j + 1]) if j > m else np.nan
        up, dn = (hh / c0 - 1) * 1e4, (ll / c0 - 1) * 1e4
        out["mfe60"], out["mae60"] = (up, dn) if dirn > 0 else (-dn, -up)
        return out


class Paths:
    """Per-day cache of option paths."""

    def __init__(self, op):
        self.op, self.cache, self.day = op, {}, None

    def get(self, d, side, k):
        if d != self.day:
            self.cache, self.day = {}, d
        key = (side, k)
        if key not in self.cache:
            self.cache[key] = self.op.path(d, side, k)
        return self.cache[key]


def trade_one(paths, op, und, d_ord, t, dirn, opt, exits, qty, spread_mult=1.0):
    """Simulate one entry (signal row t on day ordinal d_ord) for every exit. Returns {exit: (entry, exit, row, reason,
    post_high)} or None if the strike has no data."""
    side = "C" if dirn > 0 else "P"
    k = S.strike_for(op, d_ord, t, side, OPTS[opt], K.STEP[und])
    if k is None:
        return None
    P = paths.get(d_ord, side, k)
    if P is None:
        return None
    sq = S.sq_row(op, d_ord)
    return {x: S.run(P, t, sq, x, qty, spread_mult) for x in exits}


def simulate_events(und: str, E: pd.DataFrame, M: pd.DataFrame, op, modes=("follow", "fade"), opts=("ATM", "ITM1"),
                    exits=S.EXIT_NAMES, spread_mult=1.0) -> pd.DataFrame:
    """Every event x mode, ungated. E: pattern, row, day, m, sign (already restricted to allowed parts)."""
    grid = IndexGrid(M)
    paths = Paths(op)
    bse = und in K.BSE
    rec = []
    E = E[E.day.map(lambda d: d.toordinal() in op.atm)].sort_values(["day", "m"])
    for ev in E.itertuples(index=False):
        d_ord = ev.day.toordinal()
        qty = K.lot(und, ev.day)
        lab_cache = {}
        for mode in modes:
            dirn = int(ev.sign) * (1 if mode == "follow" else -1)
            r = dict(und=und, pattern=ev.pattern, day=ev.day, m=int(ev.m), sign=int(ev.sign), mode=mode, dirn=dirn,
                     qty=qty, part=K.part_of(ev.day))
            if dirn not in lab_cache:
                lab_cache[dirn] = grid.labels(ev.day, int(ev.m), dirn)
            r.update(lab_cache[dirn])
            for opt in opts:
                res = trade_one(paths, op, und, d_ord, int(ev.m), dirn, opt, exits, qty, spread_mult)
                for x in exits:
                    e, xp, xr, why, ph = res[x] if res else (np.nan, np.nan, -1, S.R_NOFILL, np.nan)
                    r[f"{opt}_{x}_entry"], r[f"{opt}_{x}_exit"] = e, xp
                    r[f"{opt}_{x}_row"], r[f"{opt}_{x}_why"], r[f"{opt}_{x}_post"] = xr, why, ph
            rec.append(r)
    T = pd.DataFrame(rec)
    if not len(T):
        return T
    for opt in opts:
        for x in exits:
            T[f"{opt}_{x}_net"] = S.net(T[f"{opt}_{x}_entry"], T[f"{opt}_{x}_exit"], T.qty, bse)
    return T


def gate(T: pd.DataFrame, keys=("und", "pattern", "sign", "mode"), row_col="ATM_X1_row", max_per_day=K.MAX_PER_DAY):
    """One trade at a time per candidate and index, at most `max_per_day` entries a day: a signal is taken only if the
    previous taken trade's exit row is < its own signal row + 1 (the previous trade has closed before the fill)."""
    T = T[np.isfinite(T["ATM_X1_entry"])].sort_values(list(keys) + ["day", "m"])
    keep = np.zeros(len(T), bool)
    kk = T[list(keys)].astype(str).agg("|".join, axis=1).values
    dd, mm, xr = T.day.values, T.m.values, T[row_col].values
    last_key, last_day, busy, n = None, None, -1, 0
    for i in range(len(T)):
        if kk[i] != last_key or dd[i] != last_day:
            last_key, last_day, busy, n = kk[i], dd[i], -1, 0
        if n < max_per_day and mm[i] + 1 > busy:
            keep[i] = True
            busy, n = xr[i], n + 1
    return T[keep].reset_index(drop=True)


def _rng(*parts):
    return np.random.default_rng(zlib.crc32("|".join(map(str, parts)).encode()) + K.SEED)


def twins(und: str, G: pd.DataFrame, M: pd.DataFrame, op, n: int, opt="ATM", exit_name="X1", spread_mult=1.0):
    """For every taken trade, n random-entry twins: same day, same direction, same option and exit, signal row uniform
    over the signal window. Returns (n_trades, n) arrays: net and signed 30-minute index move."""
    grid = IndexGrid(M)
    paths = Paths(op)
    bse = und in K.BSE
    out_net = np.full((len(G), n), np.nan)
    out_mv = np.full((len(G), n), np.nan)
    order = np.argsort(G.day.values, kind="stable")
    for i in order:
        g = G.iloc[i]
        d_ord = g.day.toordinal()
        rng = _rng(und, g.pattern, g.day, g.m, g["mode"], g.sign)
        ts = rng.integers(K.SIG_FIRST, K.SIG_LAST + 1, n)
        ent, ex = np.full(n, np.nan), np.full(n, np.nan)
        for j, t in enumerate(ts):
            res = trade_one(paths, op, und, d_ord, int(t), int(g.dirn), opt, (exit_name,), g.qty, spread_mult)
            if res:
                ent[j], ex[j] = res[exit_name][0], res[exit_name][1]
            c0 = grid.c[grid.pos[g.day], t]
            j30 = t + 30
            if j30 < K.N:
                out_mv[i, j] = g.dirn * (grid.c[grid.pos[g.day], j30] / c0 - 1) * 1e4
        out_net[i] = S.net(ent, ex, np.full(n, g.qty), bse)
    return out_net, out_mv


def events_for(und: str, part: str, E: pd.DataFrame) -> pd.DataFrame:
    """The events of `part` (holdout / walk-forward guarded by r29data.check_part)."""
    D.check_part(part)
    ys = K.PARTS[part]
    return E[E.day.map(lambda d: d.year in ys)]
