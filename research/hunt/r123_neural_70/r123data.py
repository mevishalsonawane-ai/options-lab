"""R123 data (PREREG section 4): decision rows, point-in-time inputs, labels and option-trade outcomes.

    build(part) -> <OUT>/data_<part>.npz + <OUT>/seq_<part>.npy

Everything at decision row t reads bars <= t only (`tab_features`, `seq_index`); labels and trade outcomes read bars
> t only (`simulate`, `index_labels`). The holdout part is built only inside run.py's one holdout read.
"""
from __future__ import annotations

import json
import math
from dataclasses import dataclass
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

import r123cfg as K
from research.obuy.costs import Costs, Fills, _dn

FILLS = Fills("liq")
COSTS = Costs("dated")
TICK = 0.05
O_, H_, L_, C_, V_ = range(5)
SEQ_CH = ("ret", "hl", "from_open", "vix_chg", "breadth", "new_session", "mask")
OFFS = 21                                       # offsets -10 .. +10


class HoldoutError(RuntimeError):
    pass


# ------------------------------------------------------------------------------------------------ holdout lock
def flag_path():
    return K.OUT / "HOLDOUT_READ.json"


def holdout_gate(status: str) -> None:
    """The ONE holdout read (PREREG section 3). 'start' refuses if a read finished or its result is committed."""
    p = flag_path()
    old = json.loads(p.read_text()) if p.exists() else None
    if status == "start":
        if (K.RESULTS / "holdout.json").exists():
            raise HoldoutError("refused: the holdout was already read (results/holdout.json is committed)")
        if old and old.get("status") == "done":
            raise HoldoutError(f"refused: the holdout was already read at {old.get('done_at')}")
        rec = dict(status="started", started_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                   resumed=bool(old))
    else:
        rec = dict(old or {}, status="done", done_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def holdout_open() -> bool:
    p = flag_path()
    return p.exists() and json.loads(p.read_text()).get("status") == "started"


# ------------------------------------------------------------------------------------------------ small helpers
def atm(x: float, step: float) -> float:
    """Half-up rounding to the strike step."""
    return math.floor(x / step + 0.5) * step


def charge(buy: bool, px: float, qty: int, d: date, und: str) -> float:
    if not px > 0 or qty <= 0:
        return 0.0
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), np.array([_dn(d)]), bse=und in K.BSE)[0])


def _ticks(vol5_lots: float) -> float:
    return 1.0 if vol5_lots >= FILLS.thin_lots else (2.0 if vol5_lots > 0 else 4.0)


def _ts_frame(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < K.N)]


# ------------------------------------------------------------------------------------------------ index minutes
@dataclass
class Grid:
    """One index (or VIX) on the minute grid: days x 375 arrays, NaN where no bar."""
    days: list
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray

    def pos(self) -> dict:
        return {d: i for i, d in enumerate(self.days)}


def load_grid(name: str, last_day: date) -> Grid:
    fs = sorted((K.LAKE / "index_1m").glob(f"index_1m_{name}_*.parquet"))
    df = pd.concat([_ts_frame(p, ["open", "high", "low", "close", "ts"]) for p in fs], ignore_index=True)
    df = df[(df.d <= last_day) & (df.d >= date(2021, 1, 1))]
    df = df.drop_duplicates(["d", "m"], keep="first")
    df = df[np.isfinite(df.close) & (df.close > 0)]
    days = sorted(df.d.unique())
    di = {d: i for i, d in enumerate(days)}
    a = {k: np.full((len(days), K.N), np.nan) for k in ("open", "high", "low", "close")}
    r = df.d.map(di).values
    m = df.m.values
    for k in a:
        a[k][r, m] = df[k].values
    return Grid(days, a["open"], a["high"], a["low"], a["close"])


def ffill_rows(x: np.ndarray) -> np.ndarray:
    """Forward fill along the last axis (within each row only: never from later bars)."""
    x = x.copy()
    idx = np.where(np.isfinite(x), np.arange(x.shape[-1]), 0)
    np.maximum.accumulate(idx, axis=-1, out=idx)
    out = np.take_along_axis(x, idx, axis=-1)
    out[~np.isfinite(np.take_along_axis(x, idx, axis=-1))] = np.nan
    return out


# ------------------------------------------------------------------------------------------------ options
class DayOptions:
    """One session's option bars from the lake's rolling weekly file: the chain by offset (for the context features)
    and each contract's own bars (for the trade simulator). Rows more than 12 strikes from spot are dropped (R25)."""

    def __init__(self, g: pd.DataFrame, step: float):
        g = g[(g.strike - g.spot).abs() <= 12 * step]
        self.chain = {k: np.full((K.N, OFFS, 2), np.nan) for k in ("close", "oi", "iv", "volume")}
        off = g.offset.values.astype(int) + 10
        side = (g.side.values == "P").astype(int)
        m = g.m.values
        okk = (off >= 0) & (off < OFFS)
        for k in self.chain:
            self.chain[k][m[okk], off[okk], side[okk]] = g[k].values[okk].astype(float)
        self.contracts: dict = {}
        g = g.drop_duplicates(["m", "strike", "side"], keep="first")
        for (s, k), h in g.groupby(["side", "strike"], observed=True):
            a = np.full((K.N, 5), np.nan)
            mm = h.m.values
            for j, col in enumerate(("open", "high", "low", "close", "volume")):
                a[mm, j] = h[col].values.astype(float)
            self.contracts[(str(s), float(k))] = a


class LakeOptions:
    COLS = ["ts", "offset", "side", "strike", "open", "high", "low", "close", "volume", "oi", "iv", "spot"]

    def __init__(self, und: str, last_day: date):
        self.und, self.last_day = und, last_day
        self.files = sorted((K.LAKE / "options_rolling_1m").glob(f"opt_{und}_WEEK_*.parquet"))
        self.year, self.by_day = None, {}

    def tv_1515(self) -> pd.Series:
        """ATM time value at 15:15 in % of spot by session (R25's expiry rule input)."""
        tv = []
        for p in self.files:
            o = _ts_frame(p, ["ts", "offset", "side", "strike", "close", "spot"])
            o = o[o.d <= self.last_day]
            o["side"] = o.side.astype(str).str[0].str.upper()
            x = o[(o.offset == 0) & (o.m == K.row(15, 15))]
            if x.empty:
                continue
            pv = x.pivot_table(index="d", columns="side", values="close", aggfunc="first")
            sp = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
            j = pv.join(sp)
            if "C" in j and "P" in j:
                tv.append((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100)
        t = pd.concat(tv).sort_index() if tv else pd.Series(dtype=float)
        return t[~t.index.duplicated()]

    def expiry_days(self) -> set:
        t = self.tv_1515()
        nxt = t.shift(-1)
        ok = (t < 0.6) & (nxt > 2.0 * t.clip(lower=1e-6)) & nxt.notna()
        return {d for d, v in ok.items() if v}

    def day(self, d: date) -> DayOptions | None:
        if self.year != d.year:
            ps = [p for p in self.files if p.stem.endswith(str(d.year))]
            self.by_day = {}
            if ps:
                o = _ts_frame(ps[0], self.COLS)
                o = o[o.d <= self.last_day]
                o["side"] = o.side.astype(str).str[0].str.upper()
                self.by_day = dict(tuple(o.groupby("d")))
            self.year = d.year
        g = self.by_day.get(d)
        return None if g is None or g.empty else DayOptions(g, K.STEP[self.und])


# ------------------------------------------------------------------------------------------------ trade simulator
def target_stop(buy: float, lot: int, x: float, y: float, vol5_lots: float, d: date, und: str):
    """Raw premiums whose fills net +x (a resting sell) and -y (a stop) after both legs' charges, rounded to the tick
    (target up, stop up: never beyond the rupee amount by more than a tick). Stop None when it would be <= a tick."""
    tk = _ticks(vol5_lots)
    cb = charge(True, buy, lot, d, und)
    s = buy + x / lot
    for _ in range(3):
        s = buy + (x + cb + charge(False, s, lot, d, und)) / lot
    pt = (s + tk * TICK) / (1 - FILLS.bps_mkt / 1e4)
    pt = math.ceil(pt / TICK - 1e-9) * TICK
    s2 = buy - y / lot
    for _ in range(3):
        s2 = buy + (cb + charge(False, max(s2, 0.0), lot, d, und) - y) / lot
    sp = (s2 + 2 * tk * TICK) / (1 - FILLS.bps_stop / 1e4)
    sp = math.ceil(sp / TICK - 1e-9) * TICK
    return round(pt, 2), (round(sp, 2) if sp > TICK else None)


def simulate(a: np.ndarray, t: int, end: int, lot: int, d: date, und: str, x: float, y: float) -> dict | None:
    """Buy 1 lot at the open of bar t+1 and exit at +x / -y net or the close of bar `end`. The target is a resting sell
    filled when a bar's high reaches it; the stop triggers when a bar's low reaches it and fills at the lower of the
    stop and that bar's open (stop fills). Both in one bar: the stop. Bars without a print are skipped."""
    r0 = t + 1
    if r0 >= K.N or end < r0 or not np.isfinite(a[r0, O_]) or a[r0, O_] <= 0:
        return None
    p0 = float(a[r0, O_])
    v0 = float(np.nansum(a[max(r0 - 5, 0):r0, V_])) / lot
    buy = float(FILLS.buy(np.array([p0]), np.array([v0]))[0])
    pt, sp = target_stop(buy, lot, x, y, v0, d, und)
    seg = a[r0:end + 1]
    hi_hit = np.flatnonzero(np.nan_to_num(seg[:, H_], nan=-1.0) >= pt)
    lo_hit = np.flatnonzero(np.nan_to_num(seg[:, L_], nan=np.inf) <= sp) if sp is not None else np.array([], int)
    ft = hi_hit[0] if len(hi_hit) else None
    fs = lo_hit[0] if len(lo_hit) else None
    if fs is not None and (ft is None or fs <= ft):
        r = r0 + fs
        op = a[r, O_]
        raw = float(min(sp, op)) if np.isfinite(op) else float(sp)
        vr = float(np.nansum(a[max(r - 5, 0):r, V_])) / lot
        sell = float(FILLS.sell(np.array([raw]), np.array([vr]), stop=True)[0])
        cls, xr = K.LOSS, r
    elif ft is not None:
        r = r0 + ft
        raw = pt
        vr = float(np.nansum(a[max(r - 5, 0):r, V_])) / lot
        sell = float(FILLS.sell(np.array([raw]), np.array([vr]))[0])
        cls, xr = K.WIN, r
    else:
        fin = np.flatnonzero(np.isfinite(seg[:, C_]))
        if not len(fin):
            return None
        r = r0 + fin[-1]
        raw = float(a[r, C_])
        vr = float(np.nansum(a[max(r - 5, 0):r, V_])) / lot
        sell = float(FILLS.sell(np.array([raw]), np.array([vr]))[0])
        cls, xr = K.NEITHER, r
    net = (sell - buy) * lot - charge(True, buy, lot, d, und) - charge(False, sell, lot, d, und)
    slip = (buy - p0 + raw - sell) * lot
    return dict(cls=cls, net=net, net_x2=net - slip, exit_row=xr, buy=buy, pt=pt, sp=sp)


# ------------------------------------------------------------------------------------------------ features
TAB_COLS = (["u_" + u for u in K.UNDS] +
            ["tod", "weekday", "to_expiry", "is_expiry", "is_budget", "gap", "from_open", "ret5", "ret15", "ret30",
             "ret60", "rv30", "day_range", "dist_hi", "dist_lo", "prev_range", "prev_ret", "avg_range20",
             "vix", "vix_chg", "vix_chg30", "br_up", "br_from_open", "br_ret15",
             "atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs", "atm_iv", "skew",
             "pcr", "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15"])


def _lr(a, b):
    with np.errstate(all="ignore"):
        return float(np.log(a / b) * 100) if np.isfinite(a) and np.isfinite(b) and a > 0 and b > 0 else np.nan


def chain_features(ch: dict | None, t: int, lot: int, spot: float) -> dict:
    """Option-chain state at bar t from bars <= t of this session only (forward-filled within the session)."""
    out = {k: np.nan for k in ("atm_c_pct", "atm_p_pct", "strad_pct", "log_strad_rs", "log_c_rs", "log_p_rs",
                               "atm_iv", "skew", "pcr", "pcr_chg15", "c_oi_chg15", "p_oi_chg15", "cp_vol15")}
    if ch is None or not np.isfinite(spot):
        return out
    cl = ffill_rows(ch["close"][:t + 1].transpose(1, 2, 0))[..., -1]          # (21, 2) last known at t
    iv = ffill_rows(ch["iv"][:t + 1].transpose(1, 2, 0))
    oi = ffill_rows(ch["oi"][:t + 1].transpose(1, 2, 0))
    cc, pp = cl[10, 0], cl[10, 1]
    if np.isfinite(cc) and cc > 0:
        out["atm_c_pct"], out["log_c_rs"] = cc / spot * 100, math.log(cc * lot)
    if np.isfinite(pp) and pp > 0:
        out["atm_p_pct"], out["log_p_rs"] = pp / spot * 100, math.log(pp * lot)
    if np.isfinite(cc) and np.isfinite(pp) and cc > 0 and pp > 0:
        out["strad_pct"], out["log_strad_rs"] = (cc + pp) / spot * 100, math.log((cc + pp) * lot)
    ivt = iv[..., -1]
    with np.errstate(all="ignore"):
        out["atm_iv"] = float(np.nanmean(ivt[10])) if np.isfinite(ivt[10]).any() else np.nan
        out["skew"] = float(ivt[7, 1] - ivt[13, 0])
        oit = oi[5:16, :, -1]
        sc, sp_ = np.nansum(oit[:, 0]), np.nansum(oit[:, 1])
        out["pcr"] = sp_ / sc if sc > 0 else np.nan
        if t >= 15:
            o15 = oi[5:16, :, t - 15]
            c15, p15 = np.nansum(o15[:, 0]), np.nansum(o15[:, 1])
            if c15 > 0 and p15 > 0 and sc > 0:
                out["pcr_chg15"] = out["pcr"] - p15 / c15
                out["c_oi_chg15"] = (sc / c15 - 1) * 100
                out["p_oi_chg15"] = (sp_ / p15 - 1) * 100
        v = ch["volume"][max(t - 14, 0):t + 1, 5:16]
        vc, vp = np.nansum(v[..., 0]), np.nansum(v[..., 1])
        out["cp_vol15"] = math.log((vc + 1) / (vp + 1))
    return out


# ------------------------------------------------------------------------------------------------ the per-index build
class IndexData:
    """Continuous minute arrays of one index (sessions x 375, flattened), aligned VIX and breadth."""

    def __init__(self, und: str, grids: dict, vix: Grid):
        g = grids[und]
        self.und, self.g = und, g
        self.days = g.days
        self.dpos = g.pos()
        D = len(self.days)
        vpos = vix.pos()
        vc = np.full((D, K.N), np.nan)
        for i, d in enumerate(self.days):
            j = vpos.get(d)
            if j is not None:
                vc[i] = vix.c[j]
        self.vix = vc
        # other indices' close on the same (day, minute) for breadth
        self.others = {}
        for u, gu in grids.items():
            if u == und:
                continue
            pu = gu.pos()
            oc = np.full((D, K.N), np.nan)
            for i, d in enumerate(self.days):
                j = pu.get(d)
                if j is not None:
                    oc[i] = gu.c[j]
            self.others[u] = oc
        c = g.c.reshape(-1)
        self.cf = pd.Series(c).ffill().values                 # last known close (never from later bars)
        prev = np.concatenate([[np.nan], self.cf[:-1]])
        o0 = np.repeat(np.array([_first(g.o[i]) for i in range(D)]), K.N)
        vf = pd.Series(vc.reshape(-1)).ffill().values
        vprev = np.concatenate([[np.nan], vf[:-1]])
        with np.errstate(all="ignore"), __import__("warnings").catch_warnings():
            __import__("warnings").simplefilter("ignore", RuntimeWarning)
            ret = np.log(c / prev) * 100
            hl = (g.h.reshape(-1) - g.l.reshape(-1)) / c * 100
            fo = np.log(c / o0) * 100
            vch = np.log(vc.reshape(-1) / vprev) * 100
            brs = []
            for u, oc in self.others.items():
                of = pd.Series(oc.reshape(-1)).ffill().values
                op = np.concatenate([[np.nan], of[:-1]])
                brs.append(np.log(oc.reshape(-1) / op) * 100)
            br = np.nanmean(np.vstack(brs), 0) if brs else np.full(len(c), np.nan)
        mask = np.isfinite(ret) & np.isfinite(hl)
        new = np.zeros(len(c))
        new[::K.N] = 1.0
        ch = np.column_stack([ret, hl, fo, vch, br, new, mask.astype(float)])
        ch[:, :5] = np.where(np.isfinite(ch[:, :5]), ch[:, :5], 0.0)
        ch[~mask, :5] = 0.0
        self.seq = np.clip(ch, -50, 50).astype(np.float32)
        # daily stats from completed sessions
        with np.errstate(all="ignore"):
            hi = np.nanmax(g.h, 1)
            lo = np.nanmin(g.l, 1)
            cl = np.array([_last(g.c[i]) for i in range(D)])
            self.d_range = (hi - lo) / cl * 100
            self.d_close = cl
            self.d_open = np.array([_first(g.o[i]) for i in range(D)])

    def gpos(self, i: int, t: int) -> int:
        return i * K.N + t


def _first(x):
    f = np.flatnonzero(np.isfinite(x))
    return x[f[0]] if len(f) else np.nan


def _last(x):
    f = np.flatnonzero(np.isfinite(x))
    return x[f[-1]] if len(f) else np.nan


def tab_features(I: IndexData, i: int, t: int, ctx: dict, ch: dict | None) -> dict:
    """The tabular inputs at decision row t of session i: bars <= t of this session, earlier sessions' daily values,
    VIX, breadth, the calendar and the option chain at t."""
    g, und = I.g, I.und
    d = I.days[i]
    p = I.gpos(i, t)
    c = I.cf[p]
    f = {"u_" + u: float(u == und) for u in K.UNDS}
    f["tod"] = t / K.N
    f["weekday"] = d.weekday() / 4
    f["to_expiry"] = min(ctx["to_expiry"], 25) if ctx["to_expiry"] is not None else np.nan
    f["is_expiry"] = float(ctx["is_expiry"])
    f["is_budget"] = float(ctx["is_budget"])
    pc = I.d_close[i - 1] if i > 0 else np.nan
    o0 = I.d_open[i]
    f["gap"] = _lr(o0, pc)
    f["from_open"] = _lr(c, o0)
    for k in (5, 15, 30, 60):
        f[f"ret{k}"] = _lr(c, I.cf[p - k]) if p - k >= 0 else np.nan
    r = I.seq[max(p - 29, 0):p + 1]
    rr = r[r[:, 6] > 0, 0]
    f["rv30"] = float(rr.std()) if len(rr) > 5 else np.nan
    with np.errstate(all="ignore"):
        hi = np.nanmax(g.h[i, :t + 1])
        lo = np.nanmin(g.l[i, :t + 1])
    f["day_range"] = (hi - lo) / c * 100 if np.isfinite(hi) else np.nan
    f["dist_hi"] = (hi - c) / c * 100 if np.isfinite(hi) else np.nan
    f["dist_lo"] = (c - lo) / c * 100 if np.isfinite(lo) else np.nan
    f["prev_range"] = I.d_range[i - 1] if i > 0 else np.nan
    f["prev_ret"] = _lr(I.d_close[i - 1], I.d_close[i - 2]) if i > 1 else np.nan
    f["avg_range20"] = float(np.nanmean(I.d_range[max(i - 20, 0):i])) if i > 0 else np.nan
    vrow = ffill_rows(I.vix[i, :t + 1][None])[0]
    vt = vrow[-1]
    vprev = _last(I.vix[i - 1]) if i > 0 else np.nan
    f["vix"] = vt
    f["vix_chg"] = _lr(vt, vprev)
    f["vix_chg30"] = _lr(vt, vrow[t - 30]) if t >= 30 else np.nan
    ups, fos, r15 = [], [], []
    for u, oc in list(I.others.items()) + [(und, g.c)]:
        row_ = ffill_rows(oc[i, :t + 1][None])[0]
        cu = row_[-1]
        if not np.isfinite(cu):
            continue
        prev_c = _last(oc[i - 1]) if i > 0 else np.nan
        ou = _first(oc[i, :t + 1])
        if np.isfinite(prev_c):
            ups.append(float(cu > prev_c))
        fos.append(_lr(cu, ou))
        if t >= 15:
            r15.append(_lr(cu, row_[t - 15]))
    f["br_up"] = float(np.mean(ups)) if ups else np.nan
    f["br_from_open"] = float(np.nanmean(fos)) if fos and np.isfinite(fos).any() else np.nan
    f["br_ret15"] = float(np.nanmean(r15)) if r15 and np.isfinite(r15).any() else np.nan
    f.update(chain_features(ch, t, K.LOT[und], c))
    return f


def index_labels(I: IndexData, i: int, t: int) -> dict:
    """Heads (b) and (c): the index's next-H range (log, % of the close at t) and direction. Bars > t only, this
    session only."""
    g = I.g
    c = I.cf[I.gpos(i, t)]
    out = {}
    for h in K.HS:
        if t + h >= K.N:
            out[f"y_range_{h}"] = out[f"y_dir_{h}"] = np.nan
            continue
        seg_h, seg_l = g.h[i, t + 1:t + h + 1], g.l[i, t + 1:t + h + 1]
        ce = g.c[i, t + h]
        if np.isfinite(seg_h).sum() < h // 2 or not np.isfinite(ce):
            out[f"y_range_{h}"] = out[f"y_dir_{h}"] = np.nan
            continue
        rg = (max(np.nanmax(seg_h), c) - min(np.nanmin(seg_l), c)) / c * 100
        out[f"y_range_{h}"] = math.log(max(rg, 1e-3))
        out[f"y_dir_{h}"] = float(ce > c) if ce != c else np.nan
    return out


def trade_labels(opts: DayOptions | None, I: IndexData, i: int, t: int, is_exp: bool) -> dict:
    """Head (a) labels and every cell-side's trade outcome (class, net, net at 2x slippage) for the ATM call and put."""
    und, d = I.und, I.days[i]
    lot = K.LOT[und]
    k = atm(I.cf[I.gpos(i, t)], K.STEP[und])
    last = K.EXIT_LAST_EXPIRY if is_exp else K.EXIT_LAST
    out = {}
    for s in K.SIDES:
        a = opts.contracts.get((s, k)) if opts is not None else None
        for ci, (x, y, h) in enumerate(K.CELLS):
            j = 2 * ci + (s == "P")
            r = None
            if a is not None and t + h <= last:
                r = simulate(a, t, t + h, lot, d, und, x, y)
            out[f"cls_{j}"] = K.UNDEF if r is None else r["cls"]
            out[f"net_{j}"] = np.nan if r is None else r["net"]
            out[f"netx2_{j}"] = np.nan if r is None else r["net_x2"]
            out[f"exit_{j}"] = -1 if r is None else int(r["exit_row"])
    return out


def budget_days() -> set:
    try:
        ev = pd.read_csv(K.EVENTS_CSV)
        return {date.fromisoformat(x) for x in ev[ev.kind == "budget"].date}
    except Exception:
        return set()


def build(part: str, unds=K.UNDS, log=print) -> pd.DataFrame:
    """Every decision row of `part` with its inputs, labels and trade outcomes. Returns the frame; the sequence index
    (global minute position) is in column `gpos` with `und`."""
    if part == "holdout" and not holdout_open():
        raise HoldoutError("the holdout is built only inside run.py's one holdout read")
    last_day = K.DESIGN[1] if part == "design" else K.HOLDOUT[1]
    grids = {u: load_grid(u, last_day) for u in K.UNDS}
    vix = load_grid("INDIAVIX", last_day)
    budget = budget_days()
    rows, seqs = [], {}
    for und in unds:
        I = IndexData(und, grids, vix)
        LO = LakeOptions(und, last_day)
        exp = LO.expiry_days()
        exp_sorted = sorted(exp)
        seqs[und] = I.seq
        n0 = len(rows)
        for i, d in enumerate(I.days):
            if K.part(d) != part:
                continue
            opts = LO.day(d)
            if opts is None:
                continue
            nxt = [e for e in exp_sorted if e >= d]
            to_exp = sum(1 for x in I.days[i:] if x <= nxt[0]) - 1 if nxt else None
            ctx = dict(to_expiry=to_exp, is_expiry=d in exp, is_budget=d in budget)
            for t in K.DECISION_ROWS:
                p = I.gpos(i, t)
                if not np.isfinite(I.g.c[i, t]):
                    continue
                rec = dict(und=und, day=d, row=t, gpos=p, close=float(I.cf[p]))
                rec.update(tab_features(I, i, t, ctx, opts.chain))
                rec.update(index_labels(I, i, t))
                rec.update(trade_labels(opts, I, i, t, ctx["is_expiry"]))
                rows.append(rec)
        log(f"{und} {part}: {len(rows) - n0} rows", flush=True)
    F = pd.DataFrame(rows)
    return F, seqs


def _build_one(args):
    part, und = args
    return build(part, unds=(und,))


def save(part: str, log=print):
    from multiprocessing import Pool
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_build_one, [(part, u) for u in K.UNDS])
    F = pd.concat([r[0] for r in res], ignore_index=True)
    seqs = {u: r[1][u] for u, r in zip(K.UNDS, res)}
    log(f"{part}: {len(F)} rows", flush=True)
    K.OUT.mkdir(parents=True, exist_ok=True)
    F.to_parquet(K.OUT / f"rows_{part}.parquet")
    np.savez(K.OUT / f"seq_{part}.npz", **seqs)
    return F


def load(part: str):
    if part == "holdout" and not holdout_open() and not (K.RESULTS / "holdout.json").exists():
        raise HoldoutError("the holdout is read only inside run.py's one holdout read")
    F = pd.read_parquet(K.OUT / f"rows_{part}.parquet")
    S = dict(np.load(K.OUT / f"seq_{part}.npz"))
    return F, S
