"""R60 MCX half (PREREG sections 2-3): SoloMidday translated to each commodity, the app's MCX arm rules as candidates,
held-contract pricing, costs and point-in-time features. Signal source: the commodity's near future (R53's proxy, the
per-minute median of the option rows' `spot`); the option premium is used only for fills, the premium stop / lock and P&L.

    python research/hunt/r60/r60mcx.py build [SYM ...]      # cleaned markets -> <R60_OUT>/mcx/market_<SYM>.pkl

R53's MCX harness (hunt/r53/mcx) supplies the Market class, the lake loader, R42's F1 filter, the robust expiry rule,
the session end and the Zerodha charges. R60 adds: R47 foreign / stray rows, the lake spikes.py flag, IV / OI context,
ATR14, the "1-ITM else ATM" strike rule, next-minute sales after a future-level exit, the thin-side spread and the
expiry-eve exit cap.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r60cfg as C
from research.hunt.r53 import common as C53
from research.hunt.r53.mcx import data as D53
from research.hunt.r42.spikes import flag_spikes

NAN = float("nan")
MULT = {s: D53.MULT[s] for s in C.MCX_SYMS}
FAMILY = {s: D53.FAMILY[s] for s in C.MCX_SYMS}
FOREIGN_TOL = 0.03
SPIKE_JUMP = 1.5
OPEN_TOD = 9 * 60
FIRST_BY = 9 * 60 + 5
ENTRY_SLACK = 3                       # fill window: the minute after the signal minute and 3 more
SELL_LOOK = 10                        # a sale with no print looks up to 10 minutes ahead
SETUPS = ("SN", "SE", "EV", "MO")

# SoloMidday translated (PREREG section 3): decision tod, time exit tod, minutes needed before the decision
SOLO = {"SN": dict(D=12 * 60, exit=14 * 60 + 30, min_minutes=100), "SE": dict(D=18 * 60, exit=20 * 60 + 30, min_minutes=300)}
MOVE_ATR, OUTER, STOP_ATR, EARN_ATR = 0.5, 0.25, 0.3, 0.45
EV = dict(a=17 * 60, b=19 * 60, u=22 * 60, flat=23 * 60 + 15, maxh=120, opt_stop=0.15, min_range=60)
MO = dict(slot=9 * 60 + 15, otm=2, dte=(11, 20), hold=240, stop=0.30, lock=((0.40, 0.10),), prem=(500.0, 100_000.0))
TWIN_WIN = {"SN": (9 * 60 + 15, 14 * 60), "SE": (15 * 60, 20 * 60), "EV": (19 * 60, 22 * 60), "MO": (9 * 60 + 15, 10 * 60)}


# ------------------------------------------------------------------------------------------------ cleaning (pure)
def foreign_mask(spot: np.ndarray, med: np.ndarray, tol: float = FOREIGN_TOL) -> np.ndarray:
    """True where a row's own spot is more than `tol` off that minute's median spot (R47 foreign / stray rows)."""
    with np.errstate(all="ignore"):
        r = np.asarray(spot, float) / np.asarray(med, float)
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def spike_mask(day, side, strike, order, close, jump: float = SPIKE_JUMP) -> np.ndarray:
    """Lake spikes.py rule on printed contract-minutes: True where the close is more than `jump`x away from both the
    previous and the next printed close of the same contract that day, and those two are within `jump`x of each other."""
    x = pd.DataFrame(dict(day=day, side=side, strike=strike, r=order, c=np.asarray(close, float)))
    x["i"] = np.arange(len(x))
    x = x.sort_values(["day", "side", "strike", "r"], kind="stable")
    g = x.groupby(["day", "side", "strike"], sort=False, observed=True).c
    p, n, c = g.shift(1), g.shift(-1), x.c
    hi, lo = np.maximum(p, n), np.minimum(p, n)
    sp = (((c > jump * hi) | (c * jump < lo)) & (hi <= jump * lo)).fillna(False).values
    out = np.zeros(len(x), bool)
    out[x.i.values] = sp
    return out


def ffill_day(a: np.ndarray, day: np.ndarray) -> np.ndarray:
    """Carry the last finite value forward within each day (point in time: never backwards, never across days)."""
    return pd.Series(np.asarray(a, float)).groupby(np.asarray(day)).ffill().values


# ------------------------------------------------------------------------------------------------ market
def build_market(sym: str, raw: pd.DataFrame):
    """raw: lake rows (ts naive IST, offset -3..+3, side, strike, spot, OHLC, volume, oi, iv). Returns (Market, counts)."""
    counts = dict(rows=int(len(raw)))
    raw = raw[raw.spot > 0]
    med = raw.groupby("ts").spot.median()
    fm = foreign_mask(raw.spot.values, med.reindex(raw.ts).values)
    counts["foreign"] = int(fm.sum())
    raw = raw[~fm]
    sp = raw.groupby("ts").spot.median().sort_index()
    O = raw[["ts", "offset", "side", "strike", "open", "high", "low", "close", "volume", "oi", "iv"]].copy()
    O["cp"] = np.where(O.side.astype(str) == "C", 0, 1).astype(np.int8)
    O["f1"] = False
    tr = O[(O.volume > 0) & (O.close > 0)]
    if len(tr):
        fl = flag_spikes(tr[["ts", "side", "strike", "close", "high", "low"]])
        O.loc[fl.index[fl.f1.values], "f1"] = True
        counts["f1"] = int(fl.f1.sum())
        u = tr.drop_duplicates(["ts", "side", "strike"])
        sm = spike_mask(u.ts.dt.normalize().values, u.side.astype(str).values, u.strike.values, u.ts.values, u.close.values)
        bad = pd.MultiIndex.from_frame(u.loc[sm, ["ts", "side", "strike"]])
        key = pd.MultiIndex.from_frame(O[["ts", "side", "strike"]])
        hit = key.isin(bad)
        counts["spikes"] = int(sm.sum())
        counts["spikes_not_f1"] = int((hit & ~O.f1.values).sum())
        O.loc[hit, "f1"] = True
    m = D53.Market(sym, sp.index.values, sp.values, O)
    _context(m, O)
    _days(m)
    return m, counts


def _context(m, O: pd.DataFrame):
    """ATM IV (call / put mean at offset 0) and put/call OI (ATM-3..+3), the latest value at or before each minute."""
    idx = pd.Series(np.arange(len(m.ts)), index=pd.DatetimeIndex(m.ts))
    O = O[O.ts.isin(idx.index)]
    i = idx.loc[O.ts].values
    O = O.assign(i=i)
    a0 = O[O.offset == 0].drop_duplicates(["i", "cp"]).pivot(index="i", columns="cp", values="iv")
    iv = np.full(len(m.ts), np.nan)
    if 0 in a0 and 1 in a0:
        v = (a0[0] + a0[1]) / 2
        v = v.where(v > 0)
        iv[v.index.values] = v.values
    s = O.drop_duplicates(["i", "cp", "strike"]).groupby(["i", "cp"]).oi.sum().unstack("cp")
    pcr = np.full(len(m.ts), np.nan)
    if 0 in s and 1 in s:
        with np.errstate(all="ignore"):
            v = (s[1] / s[0]).replace([np.inf, -np.inf], np.nan)
        v = v.where(v > 0)
        pcr[v.index.values] = v.values
    m.iv_atm = ffill_day(iv, m.day).astype(np.float32)
    m.pcr = ffill_day(pcr, m.day).astype(np.float32)


def _days(m):
    """Per day: daily bar from proxy closes, ATR14, sessions to expiry, the expiry-eve flag, the new-contract flag."""
    ds = list(m.days)
    H, L, Cl, seg = [], [], [], []
    for d in ds:
        ix = m.day_ix(d)
        f = m.F[ix]
        H.append(np.nanmax(f)), L.append(np.nanmin(f)), Cl.append(f[-1]), seg.append(int(m.seg[ix[0]]))
    m.atr = dict(zip(ds, atr14(np.array(H), np.array(L), np.array(Cl), np.array(seg))))
    # Fixed 15 Oct 2026, after the final read (PREREG amendment 1): `.tolist()` turned the datetime64 expiry days into
    # integers, so no session matched and every `dte` was NaN (MO never fired; the expiry-eve cap never applied).
    exp = set(np.asarray(m.exp_days, dtype="datetime64[ns]"))
    pos = {d: i for i, d in enumerate(ds)}
    exp_i = sorted(pos[d] for d in ds if d in exp)
    m.dte, m.eve_of_expiry = {}, {}
    k = 0
    for i, d in enumerate(ds):
        while k < len(exp_i) and exp_i[k] < i:
            k += 1
        m.dte[d] = float(exp_i[k] - i) if k < len(exp_i) else np.nan
        m.eve_of_expiry[d] = m.dte[d] == 1.0


def atr14(H, L, Cl, seg) -> np.ndarray:
    """ATR14 of the 14 completed sessions before each day; on the first session of a new contract the true range is
    that day's high - low (the roll gap between two futures is never a price move). NaN until 14 sessions exist."""
    n = len(H)
    tr = np.full(n, np.nan)
    for i in range(n):
        if i == 0 or seg[i] != seg[i - 1] or not np.isfinite(Cl[i - 1]):
            tr[i] = H[i] - L[i]
        else:
            tr[i] = max(H[i], Cl[i - 1]) - min(L[i], Cl[i - 1])
    out = np.full(n, np.nan)
    for i in range(14, n):
        w = tr[i - 14:i]
        if np.isfinite(w).all():
            out[i] = float(w.mean())
    return out


def load_market(sym: str, rebuild: bool = False):
    p = C.OUT / "mcx" / f"market_{sym}.pkl"
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            return pickle.load(f)
    raw = D53.lake_options(sym)
    m, counts = build_market(sym, raw)
    m.counts = counts
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "wb") as f:
        pickle.dump(m, f, protocol=pickle.HIGHEST_PROTOCOL)
    return m


_M: dict = {}


def market(sym: str):
    if sym not in _M:
        _M[sym] = load_market(sym)
    return _M[sym]


def dkey(d) -> np.datetime64:
    return np.datetime64(pd.Timestamp(d).normalize(), "ns")


def pydate(d) -> date:
    return pd.Timestamp(d).date()


def days_in(m, lo: date, hi: date) -> list:
    """Sessions of market m in [lo, hi] that are not an option expiry day, after the period guard."""
    C.check_range(lo, hi, mcx=True)
    out = []
    for d in m.days:
        pd_ = pydate(d)
        if lo <= pd_ <= hi and not m.is_exp[m.day_first[d]]:
            out.append(d)
    return out


# ------------------------------------------------------------------------------------------------ the Solo signal (pure)
def solo_signal(tod: np.ndarray, F: np.ndarray, atr: float, D: int, min_minutes: int, first_by: int = FIRST_BY):
    """SoloMidday translated, on one day's proxy minutes (tod ascending, F closes). Returns None or
    dict(k=position of the signal minute D-1, side, strength, entF). Uses only minutes before D."""
    if not (np.isfinite(atr) and atr > 0):
        return None
    pre = np.flatnonzero((tod >= OPEN_TOD) & (tod < D) & np.isfinite(F))
    if len(pre) < min_minutes or tod[pre[0]] > first_by or tod[pre[-1]] != D - 1:
        return None
    o, c = F[pre[0]], F[pre[-1]]
    hi, lo = F[pre].max(), F[pre].min()
    mv = c - o
    if abs(mv) < MOVE_ATR * atr or hi <= lo:
        return None
    s = 1 if mv > 0 else -1
    pos = (hi - c) / (hi - lo) if s > 0 else (c - lo) / (hi - lo)
    if pos > OUTER:
        return None
    return dict(k=int(pre[-1]), side=s, strength=float(abs(mv) / atr), entF=float(c))


def range_break(tod, F, a, b, u, min_range):
    """R53 / m3lib range break on one day: range of closes in [a, b) (>= min_range minutes, positive width), the first
    close beyond it in [b, u]. Returns (k, side, hi, lo) or None."""
    r = np.flatnonzero((tod >= a) & (tod < b) & np.isfinite(F))
    if len(r) < max(5, min_range):
        return None
    hi, lo = F[r].max(), F[r].min()
    if hi - lo <= 0:
        return None
    for k in np.flatnonzero((tod >= b) & (tod <= u)):
        side = 1 if F[k] > hi else (-1 if F[k] < lo else 0)
        if side:
            return int(k), side, float(hi), float(lo)
    return None


# ------------------------------------------------------------------------------------------------ the held contract
def half_spread(fam: str, tod: int, thin: bool, cost_mult: float = 1.0) -> float:
    return C53.opt_half_spread(fam, int(tod)) * (2.0 if thin else 1.0) * cost_mult


def thin_before(ii: np.ndarray, t: int, m) -> bool:
    """No print of the held contract in the 5 grid minutes before minute t (same day)."""
    a = np.searchsorted(ii, t - 5)
    b = np.searchsorted(ii, t)
    return not any(m.day[x] == m.day[t] for x in ii[a:b])


def sale(m, ii, V, t: int, use_open: bool):
    """Sell the held contract at minute t: its open (use_open) or close if printed; else the next print's open within
    SELL_LOOK minutes the same day; else the last printed close at or before t (stale). Returns (price, minute, stale)."""
    q = np.searchsorted(ii, t)
    if q < len(ii) and ii[q] == t:
        return float(V[q, 0] if use_open else V[q, 3]), int(t), False
    if q < len(ii) and m.day[ii[q]] == m.day[t] and m.tod[ii[q]] <= m.tod[t] + SELL_LOOK:
        return float(V[q, 0]), int(ii[q]), False
    if q > 0 and m.day[ii[q - 1]] == m.day[t]:
        return float(V[q - 1, 3]), int(t), True
    return NAN, int(t), True


def choose(m, j: int, side: int, rule):
    """The held contract and its fill: rule 'itm_else_atm' (1-ITM, else ATM, in the fill window) or ('otm', n).
    Returns (cp, strike, i_in, fill, ii, V, offset) or None."""
    cp = 0 if side > 0 else 1
    t0 = int(m.tod[j]) + 1
    offs = [-side, 0] if rule == "itm_else_atm" else [side * rule[1]]
    for off in offs:
        k = m.strike(cp, int(off), j)
        if not np.isfinite(k):
            continue
        lg = m.leg(m.day[j], cp, k)
        if lg is None:
            continue
        ii, V = lg
        a = np.searchsorted(ii, j + 1)
        if a < len(ii) and m.day[ii[a]] == m.day[j] and m.tod[ii[a]] <= t0 + ENTRY_SLACK and V[a, 0] > 0:
            return cp, float(k), int(ii[a]), float(V[a, 0]), ii, V, int(off)
    return None


def exit_cap(m, d) -> int:
    """Latest tod a position may be held on day d: the session end - 11 (R53 cut), and on the day before an option
    expiry min(23:00, session end - 15) (McxExpiry.optionExitAt)."""
    end = m.session_end(d)
    cap = end - 11
    if m.eve_of_expiry.get(d, False):
        cap = min(cap, 23 * 60, end - 15)
    return int(cap)


def sim(m, j: int, side: int, sym: str, *, rule="itm_else_atm", exit_tod: int, maxh=None, stop_pct=None, steps=(),
        entF=NAN, stop_d=NAN, earn_d=-1.0, stopF=NAN, tgtF=NAN, prem=None, cost_mult: float = 1.0):
    """Buy the call (side +1) / put (-1) chosen at signal minute j; price that one contract to its exit.

    Exits: a resting premium stop / step lock on printed lows (gap: the open); future-level exits on proxy closes from
    the fill minute (stop entF - side x stop_d, breakeven once the future has gone earn_d its way, a fixed stopF / tgtF),
    sold at the next minute; a time exit at the close of the last minute <= min(exit_tod, fill + maxh, exit_cap)."""
    d = m.day[j]
    ch = choose(m, j, side, rule)
    if ch is None:
        return None
    cp, k, i_in, fill, ii, V, off = ch
    if prem is not None and not (prem[0] <= fill * MULT[sym] <= prem[1]):
        return None
    last_tod = min(int(exit_tod), exit_cap(m, d))
    if maxh is not None:
        last_tod = min(last_tod, int(m.tod[i_in]) + int(maxh))
    ix = m.day_ix(d)
    ok = ix[(ix >= i_in) & (m.tod[ix] <= last_tod)]
    if not len(ok) or ok[-1] <= i_in:
        return None
    T = int(ok[-1])
    ev = []                                                   # (minute of sale, priority, why, price or None)
    a, z = np.searchsorted(ii, i_in), np.searchsorted(ii, T, side="right")
    lock = -1.0
    stop = fill * (1 - stop_pct) if stop_pct is not None else -1.0
    peak = fill
    for q in range(a, z):
        lvl = max(stop, lock)
        if lvl > 0 and V[q, 2] <= lvl:
            px = lvl if q == a else min(V[q, 0], lvl)
            ev.append((int(ii[q]), 1, "lock" if lock >= stop and lock > 0 else "opt_stop", px))
            break
        peak = max(peak, V[q, 1])
        for trig, keep in steps:
            if peak / fill - 1 >= trig - 1e-12:
                lock = max(lock, fill * (1 + keep))
    F = m.F
    best, locked = 0.0, False
    for t in ok:
        if np.isfinite(stop_d) and side * (F[t] - (entF - side * stop_d)) <= 0:
            ev.append((int(t) + 1, 0, "fut_stop", None))
            break
        if locked and side * (F[t] - entF) <= 0:
            ev.append((int(t) + 1, 0, "fut_lock", None))
            break
        if np.isfinite(stopF) and side * (F[t] - stopF) <= 0:
            ev.append((int(t) + 1, 0, "fut_stop", None))
            break
        if np.isfinite(tgtF) and side * (F[t] - tgtF) >= 0:
            ev.append((int(t) + 1, 0, "fut_target", None))
            break
        if np.isfinite(entF):
            best = max(best, side * (F[t] - entF))
            if earn_d >= 0 and best >= earn_d - 1e-9:
                locked = True
    ev.append((T, 2, "time", None))
    t, pri, why, px = min(ev, key=lambda x: (x[0], x[1]))
    stale = False
    if px is None:
        if why == "time" or t >= len(m.ts) or m.day[min(t, len(m.ts) - 1)] != d or m.tod[t] > last_tod + 1:
            t = min(t, T)
            px, t, stale = sale(m, ii, V, t, use_open=False)
        else:
            px, t, stale = sale(m, ii, V, t, use_open=True)
    if not np.isfinite(px):
        return None
    mult = MULT[sym]
    fam = FAMILY[sym]
    buy, sell = fill * mult, px * mult
    tin, tout = int(m.tod[i_in]), int(m.tod[t])
    sp = half_spread(fam, tin, thin_before(ii, i_in, m), 1.0) * buy + half_spread(fam, tout, thin_before(ii, t, m), 1.0) * sell
    charges = C53.mcx_opt_charges(buy, sell)
    net = sell - buy - charges - sp
    net2 = sell - buy - 2 * charges - 2 * sp
    return dict(und=sym, day=pydate(d), entry_ts=m.ts[i_in], exit_ts=m.ts[t], i_in=i_in, i_out=int(t), side=int(side),
                cp=cp, strike=k, offset=off, rE=fill, rX=float(px), qty=float(mult), capital=float(buy), why=why,
                stale=bool(stale), gross=float(sell - buy), cost=float(charges + sp), net=float(net), net2=float(net2))


# ------------------------------------------------------------------------------------------------ candidates
def day_arrays(m, d):
    ix = m.day_ix(d)
    return ix, m.tod[ix], m.F[ix]


def cand_solo(m, sym: str, d, setup: str):
    p = SOLO[setup]
    ix, tod, F = day_arrays(m, d)
    atr = m.atr.get(d, NAN)
    s = solo_signal(tod, F, atr, p["D"], p["min_minutes"])
    if s is None:
        return None
    j = int(ix[s["k"]])
    t = sim(m, j, s["side"], sym, exit_tod=p["exit"], entF=s["entF"], stop_d=STOP_ATR * atr, earn_d=EARN_ATR * atr)
    if t is None:
        return None
    t.update(setup=setup, j=j, er=int(m.tod[j]), strength=s["strength"], atr=float(atr))
    return t


def cand_ev(m, sym: str, d):
    ix, tod, F = day_arrays(m, d)
    r = range_break(tod, F, EV["a"], EV["b"], EV["u"], EV["min_range"])
    if r is None:
        return None
    k, side, hi, lo = r
    j = int(ix[k])
    edge = hi if side > 0 else lo
    t = sim(m, j, side, sym, exit_tod=EV["flat"], maxh=EV["maxh"], stop_pct=EV["opt_stop"], stopF=(hi + lo) / 2,
            tgtF=edge + side * (hi - lo))
    if t is None:
        return None
    t.update(setup="EV", j=j, er=int(m.tod[j]), stop_dist=float(side * (F[k] - (hi + lo) / 2)),
             tgt_dist=float(side * (edge + side * (hi - lo) - F[k])))
    return t


def cand_mo(m, sym: str, d):
    if sym != "SILVERM":
        return None
    dte = m.dte.get(d, NAN)
    if not (np.isfinite(dte) and MO["dte"][0] <= dte <= MO["dte"][1]):
        return None
    ix, tod, F = day_arrays(m, d)
    k = np.flatnonzero((tod >= OPEN_TOD) & (tod < MO["slot"]))
    if not len(k) or tod[k[-1]] < MO["slot"] - 5:
        return None
    j = int(ix[k[-1]])
    t = sim(m, j, 1, sym, rule=("otm", MO["otm"]), exit_tod=24 * 60, maxh=MO["hold"], stop_pct=MO["stop"],
            steps=MO["lock"], prem=MO["prem"])
    if t is None:
        return None
    t.update(setup="MO", j=j, er=int(m.tod[j]))
    return t


def candidates(lo: date, hi: date, syms=C.MCX_SYMS, setups=SETUPS) -> list:
    out = []
    for sym in syms:
        m = market(sym)
        for d in days_in(m, lo, hi):
            for s in setups:
                t = cand_solo(m, sym, d, s) if s in SOLO else cand_ev(m, sym, d) if s == "EV" else cand_mo(m, sym, d)
                if t is not None:
                    out.append(t)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_one(m, sym: str, t: dict, rng, tries: int = 30) -> float:
    """One random-entry twin of trade t: same day and market, signal minute uniform in its setup's window, side by
    coin flip, the same strike rule and exits (future levels from the twin's own entry). Redrawn when no fill."""
    lo_t, hi_t = TWIN_WIN[t["setup"]]
    d = dkey(t["day"])
    ix, tod, F = day_arrays(m, d)
    w = ix[(tod >= lo_t) & (tod <= hi_t)]
    if not len(w):
        return 0.0
    for _ in range(tries):
        j = int(w[rng.integers(len(w))])
        side = int(rng.choice([-1, 1]))
        s = t["setup"]
        if s in SOLO:
            atr = t["atr"]
            r = sim(m, j, side, sym, exit_tod=SOLO[s]["exit"], entF=float(m.F[j]), stop_d=STOP_ATR * atr,
                    earn_d=EARN_ATR * atr)
        elif s == "EV":
            Fj = float(m.F[j])
            r = sim(m, j, side, sym, exit_tod=EV["flat"], maxh=EV["maxh"], stop_pct=EV["opt_stop"],
                    stopF=Fj - side * t["stop_dist"], tgtF=Fj + side * t["tgt_dist"])
        else:
            r = sim(m, j, side, sym, rule=("otm", MO["otm"]), exit_tod=24 * 60, maxh=MO["hold"], stop_pct=MO["stop"],
                    steps=MO["lock"], prem=MO["prem"])
        if r is not None:
            return r["net"]
    return 0.0


def twin_totals(trades: list, n: int, seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in trades:
        m = market(t["und"])
        tot += np.array([twin_one(m, t["und"], t, rng) for _ in range(n)])
    return tot


# ------------------------------------------------------------------------------------------------ features (pure)
FEATS = ["ret_15m", "ret_60m", "ret_open", "range_atr", "edge_dist", "rv_30m", "atr_pct", "minute", "us_session", "dte",
         "iv_atm", "pcr_oi"]
SIGNED = ("ret_15m", "ret_60m", "ret_open")
ONEHOT = [f"mkt_{s}" for s in C.MCX_SYMS] + [f"setup_{s}" for s in SETUPS] + ["side"]
NAMES = FEATS + ONEHOT


def us_open_tod(d) -> int:
    """The US cash open (09:30 New York) in IST minutes: 19:00 in US summer time, 20:00 in winter."""
    return 19 * 60 if D53.us_dst(d) else 20 * 60


def features_at(tod, F, k: int, atr: float, side: int, d) -> dict:
    """Proxy features at position k of one day's minutes (only minutes 0..k are read)."""
    tod, F = tod[:k + 1], F[:k + 1]
    f = float(F[k])

    def back(mins):
        q = np.flatnonzero(tod <= tod[k] - mins)
        return float(F[q[-1]]) if len(q) else NAN

    day0 = np.flatnonzero(tod >= OPEN_TOD)
    o = float(F[day0[0]]) if len(day0) else NAN
    hi, lo = float(np.nanmax(F)), float(np.nanmin(F))
    r = np.diff(np.log(F[max(0, k - 30):k + 1])) if k >= 2 else np.array([])
    out = {
        "ret_15m": (f / back(15) - 1) * 100 * side, "ret_60m": (f / back(60) - 1) * 100 * side,
        "ret_open": (f / o - 1) * 100 * side,
        "range_atr": (hi - lo) / atr if np.isfinite(atr) and atr > 0 else NAN,
        "edge_dist": ((hi - f) / (hi - lo) if side > 0 else (f - lo) / (hi - lo)) if hi > lo else NAN,
        "rv_30m": float(np.std(r) * 100) if len(r) >= 5 else NAN,
        "atr_pct": atr / f * 100 if np.isfinite(atr) and f > 0 else NAN,
        "minute": float(tod[k]), "us_session": 1.0 if tod[k] >= us_open_tod(d) else 0.0,
    }
    return out


def row(t: dict) -> dict:
    m = market(t["und"])
    d = dkey(t["day"])
    ix, tod, F = day_arrays(m, d)
    k = int(np.searchsorted(ix, t["j"]))
    r = features_at(tod, F, k, m.atr.get(d, NAN), t["side"], d)
    r["dte"] = float(m.dte.get(d, NAN))
    r["iv_atm"] = float(m.iv_atm[t["j"]])
    r["pcr_oi"] = float(m.pcr[t["j"]])
    for s in C.MCX_SYMS:
        r[f"mkt_{s}"] = 1.0 if s == t["und"] else 0.0
    for s in SETUPS:
        r[f"setup_{s}"] = 1.0 if s == t["setup"] else 0.0
    r["side"] = float(t["side"])
    return r


def proxy_check(sym: str, live: pd.DataFrame) -> dict:
    """Data check (not a signal): the proxy against the live future's 1-minute close, minute by minute, where both
    exist (live: fut_1m_live_mcx rows of one symbol, nearest expiry each minute)."""
    m = market(sym)
    s = pd.Series(m.F, index=pd.DatetimeIndex(m.ts))
    lv = live.sort_values(["ts", "expiry"]).drop_duplicates("ts").set_index("ts").close
    j = s.to_frame("p").join(lv.rename("f"), how="inner").dropna()
    if j.empty:
        return dict(minutes=0)
    rel = (j.p / j.f - 1).abs()
    return dict(minutes=int(len(j)), median_abs_gap_pct=round(float(rel.median() * 100), 4),
                share_within_0_2pct=round(float((rel <= 0.002).mean()), 4))


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for s in sys.argv[2:] or C.MCX_SYMS:
            m = load_market(s, rebuild=True)
            print(s, m.counts, "days", len(m.days), "expiries", len(m.exp_days), flush=True)
