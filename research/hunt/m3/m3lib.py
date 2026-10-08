"""M3 library: MCX minute data, signals (LIQ, USORB, INORB, EVT, H60), option/futures trade simulation, costs.
Data: scratchpad/hunt/m3/raw/opt_<SYM>.parquet + spot_<SYM>.parquet; CRUDEOIL from scratchpad/hunt/strad_crude/cache."""
from __future__ import annotations
import math, datetime as dt
from pathlib import Path
import numpy as np, pandas as pd

S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad")
RAW = S / "hunt" / "m3" / "raw"
import os
BASE_WORK = S / "hunt" / "m3" / "work"
PRINTS = os.environ.get("M3_PRINTS", "1") == "1"  # amendment 2: option prices from printed minutes only
WORK = BASE_WORK / ("prints" if PRINTS else "allrows")
WORK.mkdir(parents=True, exist_ok=True)
CRUDE = S / "hunt" / "strad_crude" / "cache"

MULT = {"CRUDEOIL": 100, "CRUDEOILM": 10, "NATURALGAS": 1250, "NATGASMINI": 250, "GOLD": 100, "GOLDM": 10,
        "SILVER": 30, "SILVERM": 5, "COPPER": 2500}
# futures tick (Rs per unit of quoted price)
TICK = {"CRUDEOIL": 1.0, "CRUDEOILM": 1.0, "NATURALGAS": 0.1, "NATGASMINI": 0.1, "GOLD": 1.0, "GOLDM": 1.0,
        "SILVER": 1.0, "SILVERM": 1.0, "COPPER": 0.05}
FAMILY = {"CRUDEOIL": "crude", "CRUDEOILM": "crude", "NATURALGAS": "natgas", "NATGASMINI": "natgas",
          "GOLD": "gold", "GOLDM": "gold", "SILVER": "silver", "SILVERM": "silver", "COPPER": "copper"}
MINI_FUT = {"crude": "CRUDEOILM", "natgas": "NATGASMINI", "gold": "GOLDM", "silver": "SILVERM"}
# US open (New York local time) per family
US_OPEN = {"crude": (9, 0), "natgas": (9, 0), "gold": (8, 20), "silver": (8, 20), "copper": (8, 10)}
DESIGN_END = pd.Timestamp("2026-07-07")
HOLD_START = pd.Timestamp("2026-07-08")

L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 9 * 60
FIRST_ENTRY, LAST_ENTRY = 9 * 60 + 5, 22 * 60 + 30


def us_dst(day: pd.Timestamp) -> bool:
    d = day.date() if hasattr(day, "date") else day
    if d < dt.date(2025, 11, 2): return True
    if d < dt.date(2026, 3, 8): return False
    if d < dt.date(2026, 11, 1): return True
    return False


def us_to_ist_min(day, hh, mm):
    """New York local hh:mm on `day` -> IST minutes of day (EDT = UTC-4 -> +9:30; EST = UTC-5 -> +10:30)."""
    off = 9 * 60 + 30 if us_dst(day) else 10 * 60 + 30
    return hh * 60 + mm + off


# ------------------------------------------------------------------ loading
def _load_opt_raw(sym):
    if sym == "CRUDEOIL":
        parts, spots = [], []
        for k in range(-3, 4):
            s = "ATM" if k == 0 else f"ATM{k:+d}"
            for cp, side in ((0, "CALL"), (1, "PUT")):
                d = pd.read_parquet(CRUDE / f"c1_{s}_{side}.parquet")
                if k == 0:
                    spots.append(d[["ts", "spot"]])
                d = d.drop(columns=["spot"]); d["k"] = np.int8(k); d["cp"] = np.int8(cp)
                parts.append(d)
        O = pd.concat(parts, ignore_index=True)
        sp = pd.concat(spots).sort_values("ts").drop_duplicates("ts")
        return O, sp
    return pd.read_parquet(RAW / f"opt_{sym}.parquet"), pd.read_parquet(RAW / f"spot_{sym}.parquet")


class Market:
    """Minute grid of one option underlying: F (futures), day/tod, expiry, option lookup by (cp, strike)."""

    def __init__(self, sym):
        self.sym = sym
        O, sp = _load_opt_raw(sym)
        sp = sp[sp.spot > 0].copy()
        sp["ts"] = sp.ts.dt.tz_localize(None) if sp.ts.dt.tz is not None else sp.ts
        O["ts"] = O.ts.dt.tz_localize(None) if O.ts.dt.tz is not None else O.ts
        sp = sp.sort_values("ts").reset_index(drop=True)
        self.ts = sp.ts.values
        self.F = sp.spot.values.astype(np.float64)
        t = pd.DatetimeIndex(self.ts)
        self.day = t.normalize().values
        self.tod = (t.hour * 60 + t.minute).values.astype(np.int32)
        self.days = np.unique(self.day)
        # option rows on the grid
        O = O[O.close > 0]
        O = O.sort_values(["ts", "cp", "strike", "volume"], ascending=[True, True, True, False])
        O = O.drop_duplicates(["ts", "cp", "strike"])  # keep the printed (volume>0) row of a duplicate pair
        idx = pd.Series(np.arange(len(self.ts)), index=pd.DatetimeIndex(self.ts))
        O = O[O.ts.isin(idx.index)]
        O["i"] = idx.loc[O.ts].values
        self.O = O
        # strike step per day (ATM vs ATM+1)
        a0 = O[(O.k == 0) & (O.cp == 0)].set_index("i").strike
        a1 = O[(O.k == 1) & (O.cp == 0)].set_index("i").strike
        st = (a1 - a0.reindex(a1.index)).dropna()
        st = st[st > 0]
        self.step = float(st.round(4).mode().iloc[0])
        self._cache = {}
        self.atm_straddle()
        self.expiries()
        self.sessions()

    def _index_legs(self):
        O = self.O.sort_values(["cp", "strike", "i"])
        if PRINTS:
            O = O[O.volume > 0]
        self._Oi = O.i.values.astype(np.int64)
        self._Ov = O[["open", "high", "low", "close", "volume"]].values.astype(np.float64)
        keys = list(zip(O.cp.values.tolist(), np.round(O.strike.values, 4).tolist()))
        self._legs = {}
        st = 0
        for kk, g in pd.Series(np.arange(len(keys))).groupby(pd.MultiIndex.from_tuples(keys), sort=False):
            self._legs[kk] = (g.values[0], g.values[-1] + 1)

    def leg(self, cp, strike, a, b):
        """Window [a, b] of the (cp, strike) option on the minute grid: o, h, lo, c, v arrays (NaN where no row)."""
        if not hasattr(self, "_legs"):
            self._index_legs()
        n = b - a + 1
        out = np.full((5, n), np.nan)
        r = self._legs.get((cp, round(float(strike), 4)))
        if r is None:
            return out
        ii = self._Oi[r[0]:r[1]]
        lo_, hi_ = np.searchsorted(ii, a), np.searchsorted(ii, b, side="right")
        out[:, ii[lo_:hi_] - a] = self._Ov[r[0] + lo_:r[0] + hi_].T
        return out

    def atm_straddle(self):
        a = self.O[self.O.k == 0].pivot_table(index="i", columns="cp", values="close")
        s = np.full(len(self.ts), np.nan)
        s[a.index.values] = (a[0] + a[1]).values
        self.strad = s
        ka = self.O[(self.O.k == 0) & (self.O.cp == 0)]
        self.atm_k = np.full(len(self.ts), np.nan); self.atm_k[ka.i.values] = ka.strike.values
        # 1-ITM strikes on the grid: call at ATM-1, put at ATM+1 (forward-filled)
        self.itm = {}
        for cp, k in ((0, -1), (1, 1)):
            r = self.O[(self.O.k == k) & (self.O.cp == cp)]
            a = np.full(len(self.ts), np.nan); a[r.i.values] = r.strike.values
            self.itm[cp] = pd.Series(a).ffill().values

    def expiries(self):
        df = pd.DataFrame({"day": self.day, "s": self.strad, "F": self.F})
        g = df.dropna().groupby("day")
        first, last = g.s.first(), g.s.last()
        jump = first / last.shift(1)
        days = list(first.index)
        cand = sorted([(j, d) for d, j in jump.items() if j > 1.6], reverse=True)
        acc = []
        for j, d in cand:  # strongest jumps first; one expiry per 15 days
            if all(abs((d - x).days) >= 15 for x in acc):
                acc.append(d)
        exp = sorted(days[days.index(d) - 1] for d in acc)
        self.exp_days = np.array(exp, dtype="datetime64[ns]")
        # segment id: number of expiries strictly before the day -> contract id; expiry day belongs to old contract
        self.seg = np.searchsorted(self.exp_days, self.day, side="left").astype(np.int32)
        self.is_exp = np.isin(self.day, self.exp_days)

    def sessions(self):
        """Cut-off minute index per day = last minute <= session end - 10 min (session end from data, 23:30/23:55)."""
        self.day_start = {}; self.day_cut = {}
        di = pd.Series(np.arange(len(self.ts))).groupby(self.day)
        for d, ix in di:
            ix = ix.values
            end = self.tod[ix[-1]]
            cut = end - 10
            ok = ix[self.tod[ix] <= cut]
            self.day_start[d] = ix[0]
            self.day_cut[d] = ok[-1] if len(ok) else ix[-1]

    def stop_unit(self):
        """0.4 x median 15-min futures bar range over the previous 10 sessions (per day)."""
        df = pd.DataFrame({"day": self.day, "b": (self.tod - OPEN_M) // 15, "F": self.F})
        r = df.groupby(["day", "b"]).F.agg(lambda x: x.max() - x.min())
        med = r.groupby("day").median()
        roll = med.shift(1).rolling(10, min_periods=3).median()
        return (0.4 * roll).to_dict()


# ------------------------------------------------------------------ liquidity zones (port of LiquidityRules.kt via h4)
class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, Cl):
    for z in zones:
        seg = Cl[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, Cl):
    n = len(H); out = []
    if n > 2 * L:
        from numpy.lib.stride_tricks import sliding_window_view as sw
        lmax = np.full(n, np.inf); rmax = np.full(n, np.inf); lmin = np.full(n, -np.inf); rmin = np.full(n, -np.inf)
        wh = sw(H, L).max(axis=1); wl = sw(Lo, L).min(axis=1)
        js = np.arange(L, n - L)
        lmax[js] = wh[js - L]; rmax[js] = wh[js + 1]; lmin[js] = wl[js - L]; rmin[js] = wl[js + 1]
        for i in range(2 * L, n):
            j = i - L
            if lmax[j] < H[j] and rmax[j] < H[j]:
                out.append(Zone("swing", 1, H[j], Lo[j], j, i))
            if lmin[j] > Lo[j] and rmin[j] > Lo[j]:
                out.append(Zone("swing", -1, H[j], Lo[j], j, i))
    mark_breaks(out, Cl)
    return out


def pool_zones(O, H, Lo, Cl):
    out = []; cand = []
    for i in range(len(Cl)):
        o, h, lo, c = O[i], H[i], Lo[i], Cl[i]
        keep = []
        for z in cand:
            side, top, bot, origin = z[0], z[1], z[2], z[3]
            if i - origin > MAX_AGE:
                continue
            if (side > 0 and c > top) or (side < 0 and c < bot):
                continue
            touched = (h >= bot and c < top) if side > 0 else (lo <= top and c > bot)
            if touched and i - z[5] >= GAP and z[4] < CONTACTS:
                z[4] += 1; z[5] = i
            if z[4] >= CONTACTS and i - z[5] >= CONFIRM:
                dup = any(q.side == side and q.bottom <= top and bot <= q.top for q in out[-50:])
                if not dup:
                    out.append(Zone("pool", side, top, bot, origin, i))
                continue
            keep.append(z)
        cand = keep
        bh, bl = max(o, c), min(o, c)
        if h > bh:
            cand.append([1, h, bh, i, 1, i])
        if lo < bl:
            cand.append([-1, bl, lo, i, 1, i])
    mark_breaks(out, Cl)
    return out


def fold_idx(m: Market, ix, tf):
    """Fold minute indices ix (one contract segment) into tf-min bars from 09:00 each day.
    Returns arrays O,H,Lo,Cl, bar_last (minute index of the bar's last minute), bar_day."""
    d = m.day[ix]; b = (m.tod[ix] - OPEN_M) // tf
    key = pd.MultiIndex.from_arrays([d, b])
    F = m.F[ix]
    df = pd.DataFrame({"F": F, "i": ix}, index=key)
    g = df.groupby(level=[0, 1], sort=True)
    O = g.F.first().values; H = g.F.max().values; Lo = g.F.min().values; Cl = g.F.last().values
    last = g.i.last().values; first = g.i.first().values
    bday = np.array([k[0] for k in g.F.first().index])
    bstart = np.array([k[1] for k in g.F.first().index]) * tf + OPEN_M
    return O, H, Lo, Cl, last, first, bday, bstart


# ------------------------------------------------------------------ signals
def liq_signals(m: Market, tf, su):
    """Liquidity entries on tf-min bars. Each row: entry minute index (minute after the deciding bar's last minute),
    side, level, stop price, target, fb_i (minute index where failed break exits), nl_i (new liquidity exit)."""
    rows = []
    for s in np.unique(m.seg):
        ix = np.where(m.seg == s)[0]
        if len(ix) < 100:
            continue
        O, H, Lo, Cl, last, first, bday, bstart = fold_idx(m, ix, tf)
        n = len(Cl)
        if n < 2 * L + 2:
            continue
        sw_, po = swing_zones(H, Lo, Cl), pool_zones(O, H, Lo, Cl)
        zones = sw_ + po
        by_break = {}
        for p in po:
            if p.broken >= 0:
                by_break.setdefault(p.broken, []).append(p)
        for i in sorted(by_break):
            pool = None
            for p in by_break[i]:
                if p.known > i:
                    continue
                if any(z.side == p.side and z.bottom <= p.top and p.bottom <= z.top and z.known <= i
                       and (z.broken < 0 or z.broken >= i) for z in sw_):
                    pool = p; break
            if pool is None:
                continue
            e = last[i] + 1
            if e >= len(m.ts) or m.day[e] != bday[i]:
                continue  # the deciding bar is the day's last
            done = bstart[i] + tf
            if done < FIRST_ENTRY or done > LAST_ENTRY:
                continue
            unit = su.get(bday[i], np.nan)
            if not np.isfinite(unit) or unit <= 0:
                continue
            side, close = pool.side, Cl[i]
            ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                     and side * (q.edge - close) > 0]
            target = (min(ahead) if side > 0 else max(ahead)) if ahead else np.nan
            if np.isfinite(target) and side * (target - close) < 1.0 * unit:
                continue
            level = pool.edge
            fb = next((last[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), -1)
            nl = [last[z.known] for z in zones if z.side == side and z.known >= i + 1]
            nl = min(nl) if nl else -1
            rows.append(dict(e=int(e), side=int(side), sigF=float(close), level=float(level),
                             stopF=float(level - side * unit), tgtF=float(target), fb=int(fb), nl=int(nl),
                             tstop=20, tgain=0.05, maxh=10 ** 6, unit=float(unit), book=f"LIQ{tf}"))
    return rows


def h60_signals(m: Market, su, tf=5, look=12):
    rows = []
    for s in np.unique(m.seg):
        ix = np.where(m.seg == s)[0]
        O, H, Lo, Cl, last, first, bday, bstart = fold_idx(m, ix, tf)
        n = len(Cl)
        for i in range(look + 1, n):
            if bday[i - look] != bday[i]:
                continue
            done = bstart[i] + tf
            if done < FIRST_ENTRY or done > LAST_ENTRY:
                continue
            hh, ll = H[i - look:i].max(), Lo[i - look:i].min()
            side = 1 if Cl[i] > hh else (-1 if Cl[i] < ll else 0)
            if side == 0:
                continue
            # a fresh break: previous bar was not already beyond
            if bday[i - look - 1] == bday[i] and (side > 0 and Cl[i - 1] > H[i - look - 1:i - 1].max()) or bday[i - look - 1] == bday[i] and (side < 0 and Cl[i - 1] < Lo[i - look - 1:i - 1].min()):
                continue
            e = last[i] + 1
            if e >= len(m.ts) or m.day[e] != bday[i]:
                continue
            unit = su.get(bday[i], np.nan)
            if not np.isfinite(unit) or unit <= 0:
                continue
            level = hh if side > 0 else ll
            fb = next((last[k] for k in range(i + 1, n) if bday[k] == bday[i] and side * (Cl[k] - level) < 0), -1)
            rows.append(dict(e=int(e), side=side, sigF=float(Cl[i]), level=float(level), stopF=float(level - side * unit),
                             tgtF=np.nan, fb=int(fb), nl=-1, tstop=20, tgain=0.05, maxh=60, unit=float(unit), book="H60"))
    return rows


def range_break_signals(m: Market, day_windows, book, maxh):
    """day_windows: {day: (range_start_min, range_end_min, entry_until_min)}. Range from 1-min futures
    closes in [start, end); entry on the first 1-min close beyond within [end, until]."""
    rows = []
    di = pd.Series(np.arange(len(m.ts))).groupby(m.day)
    for d, ix in di:
        ix = ix.values
        if d not in day_windows:
            continue
        a, b, u = day_windows[d]
        t = m.tod[ix]
        r = ix[(t >= a) & (t < b)]
        if len(r) < max(5, (b - a) // 2):
            continue
        hi, lo = m.F[r].max(), m.F[r].min()
        w = hi - lo
        if w <= 0:
            continue
        cand = ix[(t >= b) & (t <= u)]
        for j in cand:
            side = 1 if m.F[j] > hi else (-1 if m.F[j] < lo else 0)
            if side:
                e = j + 1
                if e >= len(m.ts) or m.day[e] != d:
                    break
                edge = hi if side > 0 else lo
                rows.append(dict(e=int(e), side=side, sigF=float(m.F[j]), level=float(edge), stopF=float((hi + lo) / 2),
                                 tgtF=float(edge + side * w), fb=-1, nl=-1, tstop=-1, tgain=0.0, maxh=maxh,
                                 unit=float(w / 2), book=book))
                break
    return rows


# ------------------------------------------------------------------ simulation
def sim_option(m: Market, sg, cut, strike=None, stop_pct=0.15):
    """Buy the 1-ITM option of sg.side at minute sg['e'] open. Returns dict or None (no price)."""
    e, side = sg["e"], sg["side"]
    cp = 0 if side > 0 else 1
    if strike is None:
        strike = m.itm[cp][e - 1]
        if not np.isfinite(strike):
            return None
    end = min(cut, e + sg["maxh"])
    if end <= e:
        return None
    a = max(e - 3, 0)
    o, h, lo, c, v = m.leg(cp, strike, a, end)
    k0 = e - a
    printed = np.isfinite(c) & (v > 0) if PRINTS else np.isfinite(c)
    if PRINTS:
        # entry: the first printed minute in [e, e+2]; its open
        pk = [k for k in range(k0, min(k0 + 3, len(c))) if printed[k]]
        if not pk:
            return None
        k_in = pk[0]
        fill = o[k_in]
    else:
        k_in = k0
        fill = o[k0]
        if not np.isfinite(fill):
            prev = c[:k0]
            prev = prev[np.isfinite(prev)]
            if len(prev) == 0:
                return None
            fill = prev[-1]
    seg = slice(k_in, None)
    cc = np.array(pd.Series(c[seg]).ffill().values, dtype=float)
    cc[~np.isfinite(cc)] = fill
    pr = printed[seg]
    ll = lo[seg].copy(); ll[~np.isfinite(ll)] = cc[~np.isfinite(ll)]
    oo = o[seg].copy(); oo[~np.isfinite(oo)] = cc[~np.isfinite(oo)]
    craw = c[seg]
    e = e + (k_in - k0)
    seg = slice(e, end + 1)
    F = m.F[seg]
    n = len(cc)
    big = n + 10
    stopp = fill * (1 - stop_pct)
    ev = {}
    hit = np.where(ll <= stopp)[0]
    ev["opt_stop"] = hit[0] if len(hit) else big
    hit = np.where(side * (F - sg["stopF"]) <= 0)[0]
    ev["idx_stop"] = hit[0] if len(hit) else big
    if np.isfinite(sg["tgtF"]):
        hit = np.where(side * (F - sg["tgtF"]) >= 0)[0]
        ev["target"] = hit[0] if len(hit) else big
    if sg["tstop"] > 0 and sg["tstop"] < n and cc[sg["tstop"]] < fill * (1 + sg["tgain"]) - 1e-9:
        ev["time_stop"] = sg["tstop"]
    for k in ("fb", "nl"):
        if sg[k] >= e:
            ev[k] = sg[k] - e if sg[k] - e < n else big
    ev["end"] = n - 1
    why = min(ev, key=lambda k: (ev[k], 0 if k == "opt_stop" else 1))
    t = ev[why]
    if why == "opt_stop":
        px = min(oo[t], stopp) if t > 0 else stopp
    elif pr[t] or not PRINTS:
        px = cc[t]
    else:
        nxt = np.where(pr[t + 1:t + 11])[0]
        if len(nxt):
            px = oo[t + 1 + nxt[0]]
        else:
            o2, h2, l2, c2, v2 = m.leg(cp, strike, e + t + 1, e + t + 10)
            q = np.where(v2 > 0)[0]
            px = o2[q[0]] if len(q) else cc[t]
    out_strike_missing = not bool(pr[t])
    return dict(fill=float(fill), exit=float(px), hold=int(t), why=why, strike=float(strike), cp=cp,
                stale_exit=bool(out_strike_missing))


def sim_fut(m: Market, sg, cut):
    e, side = sg["e"], sg["side"]
    end = min(cut, e + sg["maxh"])
    if end <= e:
        return None
    F = m.F[e:end + 1]
    fill = m.F[e]  # minute close as the fill proxy (no futures open in the spot series)
    n = len(F); big = n + 10
    ev = {}
    hit = np.where(side * (F - sg["stopF"]) <= 0)[0]; ev["idx_stop"] = hit[0] if len(hit) else big
    if np.isfinite(sg["tgtF"]):
        hit = np.where(side * (F - sg["tgtF"]) >= 0)[0]; ev["target"] = hit[0] if len(hit) else big
    if sg["tstop"] > 0 and sg["tstop"] < n and side * (F[sg["tstop"]] - fill) < 0.25 * sg["unit"]:
        ev["time_stop"] = sg["tstop"]
    for k in ("fb", "nl"):
        if sg[k] >= e:
            ev[k] = sg[k] - e if sg[k] - e < n else big
    ev["end"] = n - 1
    why = min(ev, key=ev.get)
    t = ev[why]
    return dict(fill=float(fill), exit=float(F[t]), hold=int(t), why=why)


# ------------------------------------------------------------------ costs (Zerodha MCX)
def opt_charges(buy_prem_rs, sell_prem_rs):
    brok = 40.0
    ctt = 0.0005 * sell_prem_rs
    txn = 0.000418 * (buy_prem_rs + sell_prem_rs)
    sebi = 10e-7 * (buy_prem_rs + sell_prem_rs)
    stamp = 0.00003 * buy_prem_rs
    gst = 0.18 * (brok + txn + sebi)
    return brok + ctt + txn + sebi + stamp + gst


def fut_charges(buy_rs, sell_rs):
    brok = np.minimum(20, 0.0003 * buy_rs) + np.minimum(20, 0.0003 * sell_rs)
    ctt = 0.0001 * sell_rs
    txn = 0.000021 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs
    gst = 0.18 * (brok + txn + sebi)
    return brok + ctt + txn + sebi + stamp + gst
