"""R83 events worker (PREREG.md section 4, ideas E1, E2, E3, E4, E6), 18 Oct 2026.

Signal source of every rule: the US benchmark future (WTI for crude, Henry Hub for natural gas; Dukascopy 1-minute
CFD bars) as a PROXY for the MCX future it drives; trades are priced as the MCX mini future (CRUDEOILM / NATGASMINI)
with MCX costs (common.fut_net). The same frozen rules are replayed once on real MCX minutes (the near future's
per-minute `spot` from the option lake) as the mini future and as the 1-ITM near-month option.

Fills: a signal is read on a 1-minute close; the entry is the NEXT available minute's close (at most 5 minutes later,
else no trade). Stops and targets are checked on minute closes (a gap through a level fills at the close that crossed
it, the worse price); stops pay common.STOP_SLIP. Time exits at the close of the first minute at or after the exit
minute; everything is flat at common.flat_min(day) (15 minutes before the MCX close).

    python3 -m research.hunt.r83.events design      # all variants, walk-forward, design twins, frozen picks
    python3 -m research.hunt.r83.events final       # ONE read per idea: frozen pick + baseline, 2023 - 13 Oct 2026,
                                                    # plus the real-MCX replay (futures and 1-ITM option)
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np
import pandas as pd

from research.hunt.r83 import common as C

# ------------------------------------------------------------------------------------------------ calendars
EIA_EXC = {  # normal Wednesday of the week -> (official alternate date, ET time); eia.gov schedule 2025-26
    # (copied from research/hunt/nn_crude/calendar_events.py, fetched there 8 Oct 2026)
    "2025-01-01": ("2025-01-02", "11:00"), "2025-01-22": ("2025-01-23", "12:00"), "2025-02-19": ("2025-02-20", "12:00"),
    "2025-05-28": ("2025-05-29", "12:00"), "2025-09-03": ("2025-09-04", "12:00"), "2025-10-15": ("2025-10-16", "12:00"),
    "2025-11-12": ("2025-11-13", "12:00"), "2025-12-24": ("2025-12-29", "17:00"), "2026-01-21": ("2026-01-22", "12:00"),
    "2026-02-18": ("2026-02-19", "12:00"), "2026-05-27": ("2026-05-28", "12:00"), "2026-09-09": ("2026-09-10", "12:00"),
    "2026-10-14": ("2026-10-15", "12:00"), "2026-11-11": ("2026-11-12", "12:00")}


def _holidays():
    from pandas.tseries.holiday import USFederalHolidayCalendar
    return set(USFederalHolidayCalendar().holidays("2015-01-01", "2027-12-31").date)


HOL = _holidays()


def eia_events(start: date, end: date) -> dict:
    """{IST day: release minute of the IST day}. Wednesday 10:30 ET; official 2025-26 exceptions; before 2025 weeks
    with a US federal holiday Mon-Wed are skipped (their release day/time is not verified). A release that falls
    after IST midnight (e.g. 17:00 ET) is outside MCX hours and dropped."""
    out = {}
    for w in pd.date_range(pd.Timestamp(start) - pd.Timedelta(days=7), pd.Timestamp(end) + pd.Timedelta(days=7),
                           freq="W-WED"):
        wd = w.date()
        k = wd.isoformat()
        if k in EIA_EXC:
            d = date.fromisoformat(EIA_EXC[k][0])
            hh, mm = map(int, EIA_EXC[k][1].split(":"))
        elif wd.year < 2025 and any((wd - timedelta(days=i)) in HOL for i in range(3)):
            continue
        else:
            d, hh, mm = wd, 10, 30
        t = C.et_to_ist_min(d, hh, mm)
        if t >= 1440 or not (start <= d <= end):
            continue
        out[d] = t
    return out


def storage_events(start: date, end: date) -> dict:
    """{IST day: minute}: EIA natural gas storage, Thursday 10:30 ET. Weeks with a US federal holiday Mon-Thu are
    skipped in every year (the official moved dates were not verified this session)."""
    out = {}
    for w in pd.date_range(pd.Timestamp(start) - pd.Timedelta(days=7), pd.Timestamp(end) + pd.Timedelta(days=7),
                           freq="W-THU"):
        d = w.date()
        if any((d - timedelta(days=i)) in HOL for i in range(4)):
            continue
        t = C.et_to_ist_min(d, 10, 30)
        if t >= 1440 or not (start <= d <= end):
            continue
        out[d] = t
    return out


# ------------------------------------------------------------------------------------------------ books
@dataclass
class Book:
    """Minutes of one market inside MCX hours, by IST day: m (minute of day), h, l, c; optional global index g."""
    mkt: str
    days: dict = field(default_factory=dict)          # day -> (m, h, l, c, g)
    atr: dict = field(default_factory=dict)           # day -> previous day's ATR14 (price units)
    trend: dict = field(default_factory=dict)         # day -> +1 / -1 / 0: previous close vs its SMA20
    notional: object = None                           # f(px, day) -> Rs per lot

    def day(self, d):
        return self.days.get(d)


def book_from_frame(mkt: str, df: pd.DataFrame, daily: pd.DataFrame, notional, g=None) -> Book:
    """df: minutes with day, m, h, l, c (already inside MCX hours). daily: day-indexed OHLC (high, low, close) used for
    the previous day's ATR14 and SMA20 trend (shifted one bar: no look-ahead)."""
    b = Book(mkt=mkt, notional=notional)
    arr = df[["m", "h", "l", "c"]].to_numpy(float)
    gi = np.arange(len(df)) if g is None else np.asarray(g)
    dd = df.day.values
    brk = np.r_[0, np.nonzero(dd[1:] != dd[:-1])[0] + 1, len(df)]
    for a, z in zip(brk[:-1], brk[1:]):
        b.days[dd[a]] = (arr[a:z, 0].astype(int), arr[a:z, 1], arr[a:z, 2], arr[a:z, 3], gi[a:z])
    daily = daily.sort_index()
    a14 = C.atr(daily, 14).shift(1)
    sma = daily.close.rolling(20, min_periods=20).mean().shift(1)
    pc = daily.close.shift(1)
    tr = np.sign(pc - sma)
    for d, v in a14.items():
        if np.isfinite(v):
            b.atr[d] = float(v)
    for d, v in tr.items():
        if np.isfinite(v):
            b.trend[d] = int(v)
    return b


def proxy_book(mkt: str) -> Book:
    raw = C.load_proxy(mkt)
    daily = C.daily_from_minutes(raw).set_index("day")
    mh = C.mcx_hours(raw)
    inr = {}

    def notional(px, d, _m=mkt):
        if d not in inr:                       # USD/INR of the previous Yahoo day, cached per day (speed only)
            inr[d] = C.usdinr_prev(d)
        return px * inr[d] * C.FACTOR[_m] * C.LOT[_m]
    return book_from_frame(mkt, mh, daily, notional)


# ------------------------------------------------------------------------------------------------ helpers
def idx_le(m, t, lo=None):
    """Last index with m <= t (and m >= lo), else -1."""
    i = int(np.searchsorted(m, t, side="right")) - 1
    if i < 0 or (lo is not None and m[i] < lo):
        return -1
    return i


def idx_ge(m, t, hi=None):
    i = int(np.searchsorted(m, t, side="left"))
    if i >= len(m) or (hi is not None and m[i] > hi):
        return -1
    return i


def walk(m, c, ie, side, stop, tgt, t_exit, flat):
    """From the entry index ie (filled at c[ie]) walk later minute closes: stop, then target, then time, then flat.
    Returns (exit index, why)."""
    n = len(m)
    if ie + 1 >= n:
        return ie, "eod"
    cc, mm = c[ie + 1:], m[ie + 1:]
    cand = []
    if stop is not None:
        h = np.nonzero(side * (cc - stop) <= 0)[0]
        if len(h):
            cand.append((h[0], 0, "stop"))
    if tgt is not None:
        h = np.nonzero(side * (cc - tgt) >= 0)[0]
        if len(h):
            cand.append((h[0], 1, "target"))
    h = np.nonzero(mm >= t_exit)[0]
    if len(h):
        cand.append((h[0], 2, "time"))
    h = np.nonzero(mm >= flat)[0]
    if len(h):
        cand.append((h[0], 3, "flat"))
    if not cand:
        return n - 1, "eod"
    k, _, why = min(cand)
    return ie + 1 + int(k), why


def make_trade(book: Book, d, day_arr, j, ie, side, stop, tgt, t_exit, flat, meta=None):
    """j = signal index, ie = entry index (next minute). Returns a trade dict (with a twin template) or None."""
    m, h, l, c, g = day_arr
    px = c[ie]
    x, why = walk(m, c, ie, side, stop, tgt, t_exit, flat)
    notional = book.notional(px, d)
    gross, net = C.fut_net(book.mkt, side, px, c[x], notional, stop=(why == "stop"))
    t = dict(entry_ts=pd.Timestamp(d) + pd.Timedelta(minutes=int(m[ie])),
             exit_ts=pd.Timestamp(d) + pd.Timedelta(minutes=int(m[x])),
             side=int(side), entry=float(px), exit=float(c[x]), why=why, gross=float(gross), net=float(net),
             sig_m=int(m[j]), entry_m=int(m[ie]), gj=int(g[j]), gx_exit_m=int(min(t_exit, flat)),
             stop=None if stop is None else float(stop), tgt=None if tgt is None else float(tgt),
             tpl=dict(mod=int(m[ie]), stop_frac=None if stop is None else float(abs(px - stop) / px),
                      tgt_frac=None if tgt is None else float(abs(tgt - px) / px), hold=int(t_exit - m[ie])))
    if meta:
        t.update(meta)
    return t


def next_entry(m, j, flat):
    ie = j + 1
    if ie >= len(m) or m[ie] > m[j] + 5 or m[ie] >= flat:
        return -1
    return ie


# ------------------------------------------------------------------------------------------------ rules
def fade(book: Book, d, T: int, k: float):
    """E1 / E3. m = close(T+15) - close(T-1); |m| >= k x ATR14d -> against m at the next close. Stop beyond the T..T+15
    extreme (bar highs / lows; close-only data: closes) by 0.1 ATR; target close(T-1); time exit T+120."""
    arr = book.day(d)
    a = book.atr.get(d)
    if arr is None or a is None:
        return None
    m, h, l, c, g = arr
    flat = C.flat_min(d)
    ia, ib = idx_le(m, T - 1, T - 6), idx_le(m, T + 15, T + 10)
    if ia < 0 or ib < 0 or ib <= ia:
        return None
    mv = c[ib] - c[ia]
    if abs(mv) < k * a or mv == 0:
        return None
    side = -int(np.sign(mv))
    seg = np.nonzero((m >= T) & (m <= T + 15))[0]
    stop = (h[seg].max() + 0.1 * a) if side < 0 else (l[seg].min() - 0.1 * a)
    ie = next_entry(m, ib, flat)
    if ie < 0:
        return None
    return make_trade(book, d, arr, ib, ie, side, stop, c[ia], T + 120, flat, dict(move=float(mv), atr=a))


def follow(book: Book, d, T: int, R: int):
    """E2 / E4. Range of closes T..T+R; first close beyond it in (T+R, T+120] -> with it at the next close. Stop the
    range middle; target edge + 1 width; time exit 90 minutes after the entry."""
    arr = book.day(d)
    if arr is None:
        return None
    m, h, l, c, g = arr
    flat = C.flat_min(d)
    rg = np.nonzero((m >= T) & (m <= T + R))[0]
    if len(rg) < max(3, R // 2):
        return None
    hi, lo = c[rg].max(), c[rg].min()
    if not hi > lo:
        return None
    for j in np.nonzero((m > T + R) & (m <= T + 120))[0]:
        side = 1 if c[j] > hi else (-1 if c[j] < lo else 0)
        if side == 0:
            continue
        ie = next_entry(m, j, flat)
        if ie < 0:
            return None
        edge = hi if side > 0 else lo
        return make_trade(book, d, arr, j, ie, side, (hi + lo) / 2, edge + side * (hi - lo), m[ie] + 90, flat)
    return None


def orb(book: Book, d, filt: str | None = None, other: Book | None = None):
    """E6. NYMEX open O = 09:00 ET; range = closes O..O+14; first close beyond it in [O+15, O+120] -> with it at the next
    close; stop range middle; target edge + 1 width; time exit 60 minutes after the entry (M3 USORB exits).
    filt 'sma': previous close above (below) its SMA20 for a buy (sell); 'breadth': the other market's move from its
    first close at/after O to its last close at/before the signal minute has the trade's sign."""
    arr = book.day(d)
    if arr is None:
        return None
    m, h, l, c, g = arr
    flat = C.flat_min(d)
    O = C.et_to_ist_min(d, 9, 0)
    rg = np.nonzero((m >= O) & (m <= O + 14))[0]
    if len(rg) < 8:
        return None
    hi, lo = c[rg].max(), c[rg].min()
    if not hi > lo:
        return None
    for j in np.nonzero((m >= O + 15) & (m <= O + 120))[0]:
        side = 1 if c[j] > hi else (-1 if c[j] < lo else 0)
        if side == 0:
            continue
        if filt == "sma" and book.trend.get(d, 0) != side:
            return None
        if filt == "breadth":
            oa = other.day(d) if other is not None else None
            if oa is None:
                return None
            om, _, _, oc, _ = oa
            i0, i1 = idx_ge(om, O, O + 5), idx_le(om, m[j], O)
            if i0 < 0 or i1 < 0 or i1 <= i0 or np.sign(oc[i1] - oc[i0]) != side:
                return None
        ie = next_entry(m, j, flat)
        if ie < 0:
            return None
        edge = hi if side > 0 else lo
        return make_trade(book, d, arr, j, ie, side, (hi + lo) / 2, edge + side * (hi - lo), m[ie] + 60, flat)
    return None


# ------------------------------------------------------------------------------------------------ variants
def run_variant(book, idea, var, days, events=None, other=None):
    out = []
    for d in days:
        if idea in ("E1", "E3"):
            if d in events:
                t = fade(book, d, events[d], var)
            else:
                continue
        elif idea in ("E2", "E4"):
            if d in events:
                t = follow(book, d, events[d], var)
            else:
                continue
        else:
            t = orb(book, d, var, other)
        if t is not None:
            out.append(t)
    return out


IDEAS = {
    "E1": dict(mkt="CRUDE", cal="eia", variants={"k0.20": 0.20, "k0.35": 0.35}, primary="k0.20"),
    "E2": dict(mkt="CRUDE", cal="eia", variants={"R30": 30, "R15": 15}, primary="R30"),
    "E3": dict(mkt="NG", cal="storage", variants={"k0.20": 0.20, "k0.35": 0.35}, primary="k0.20"),
    "E4": dict(mkt="NG", cal="storage", variants={"R30": 30, "R15": 15}, primary="R30"),
    "E6_CRUDE": dict(mkt="CRUDE", cal=None, other="NG", variants={"sma": "sma", "breadth": "breadth"}, primary="sma",
                     baseline=None),
    "E6_NG": dict(mkt="NG", cal=None, other="CRUDE", variants={"sma": "sma", "breadth": "breadth"}, primary="sma",
                  baseline=None),
}


# ------------------------------------------------------------------------------------------------ twins
def replay_twin(book: Book, d, tpl, side):
    arr = book.day(d)
    if arr is None:
        return 0.0
    m, h, l, c, g = arr
    flat = C.flat_min(d)
    ie = idx_ge(m, tpl["mod"], tpl["mod"] + 5)
    if ie < 0 or m[ie] >= flat:
        return 0.0
    px = c[ie]
    stop = None if tpl["stop_frac"] is None else px * (1 - side * tpl["stop_frac"])
    tgt = None if tpl["tgt_frac"] is None else px * (1 + side * tpl["tgt_frac"])
    x, why = walk(m, c, ie, side, stop, tgt, tpl["mod"] + tpl["hold"], flat)
    return C.fut_net(book.mkt, side, px, c[x], book.notional(px, d), stop=(why == "stop"))[1]


def twin_totals(book: Book, trades, pool, seed, n=C.N_TWINS):
    """n twin sets: each trade re-run with the same side and exits (as fractions of the entry price, same hold) from the
    same clock minute on a random pool day of the same calendar year."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in trades:
        d = C.day_of(t["entry_ts"])
        ds = C.twin_dates(d, pool, rng, n)
        if not ds:
            continue
        tot += np.array([replay_twin(book, x, t["tpl"], t["side"]) for x in ds])
    return tot


def twin_pool(book: Book, idea, events, lo, hi):
    days = [d for d in book.days if lo <= d <= hi]
    if idea in ("E6_CRUDE", "E6_NG"):
        return days
    # every Wednesday is an EIA day and every Thursday a storage day, so "non-event same weekday" does not exist:
    # the pool is non-event Mondays, Tuesdays and Fridays (NOTES_events.md, deviation 2)
    return [d for d in days if pd.Timestamp(d).weekday() in (0, 1, 4) and d not in events]


# ------------------------------------------------------------------------------------------------ runs
def _books():
    books = {}
    for mkt in ("CRUDE", "NG"):
        try:
            books[mkt] = proxy_book(mkt)
        except FileNotFoundError:
            books[mkt] = None
    return books


def _events(cal, lo, hi):
    return eia_events(lo, hi) if cal == "eia" else (storage_events(lo, hi) if cal == "storage" else {})


def _seed(idea: str) -> int:
    """A stable per-idea seed offset (Python's hash() is salted per process)."""
    return sum(ord(ch) * (i + 1) for i, ch in enumerate(idea)) % 997


def strip(trs):
    return [{k: v for k, v in t.items() if k != "tpl"} for t in trs]


def design():
    books = _books()
    lo, hi = C.DESIGN
    out = {}
    for idea, spec in IDEAS.items():
        book = books.get(spec["mkt"])
        if book is None or (spec.get("other") and books.get(spec["other"]) is None):
            out[idea] = {"status": "not tested (no data)"}
            continue
        other = books.get(spec.get("other")) if spec.get("other") else None
        ev = _events(spec["cal"], lo, hi)
        days = sorted(d for d in book.days if lo <= d <= hi)
        vt = {v: run_variant(book, idea, p, days, ev, other) for v, p in spec["variants"].items()}
        base = run_variant(book, idea, None, days, ev, other) if idea.startswith("E6") else []
        wf = C.walk_forward(vt, spec["primary"])
        pool = twin_pool(book, idea, ev, lo, hi)
        res = {"events": len(ev), "variants": {}, "walk_forward": wf, "frozen": wf["frozen"]}
        for i, (v, trs) in enumerate(vt.items()):
            s = C.summary(trs)
            tot = twin_totals(book, trs, pool, C.SEED + 101 * i + _seed(idea))
            s["twin_p"] = C.twin_p(s["net"], tot)
            s["twin_mean"] = float(tot.mean())
            s["gross"] = float(sum(t["gross"] for t in trs))
            s["by_year"] = {str(y): C.net_between(trs, date(y, 1, 1), date(y, 12, 31)) for y in range(2016, 2023)}
            s["longs_net"] = float(sum(t["net"] for t in trs if t["side"] > 0))
            s["shorts_net"] = float(sum(t["net"] for t in trs if t["side"] < 0))
            res["variants"][v] = s
        if idea.startswith("E6"):
            sb = C.summary(base)
            sb["gross"] = float(sum(t["gross"] for t in base))
            sb["wf_net"] = float(sum(C.net_between(base, a, b) for a, b in C.FOLDS))
            res["baseline"] = sb
        else:
            res["baseline"] = {"net": 0.0, "wf_net": 0.0, "note": "flat"}
        C.save_json(f"{idea}_design.json", res)
        out[idea] = {"frozen": wf["frozen"], "wf": wf["wf_net"],
                     **{v: (r["trades"], round(r["net"]), round(r["twin_p"], 3)) for v, r in res["variants"].items()},
                     "base": (res["baseline"].get("trades"), round(res["baseline"]["net"]), round(res["baseline"]["wf_net"]))}
        print(idea, out[idea], flush=True)
    C.save_json("events_design_summary.json", out)
    return out


# ------------------------------------------------------------------------------------------------ real MCX replay
OPT_HALF = {"CRUDE": (0.0030, 0.0060), "NG": (0.0045, 0.0090)}          # (from 17:00, before 17:00), PREREG section 2
SYM = {"CRUDE": "CRUDEOIL", "NG": "NATURALGAS"}
OPT_MULT = {"CRUDE": 100, "NG": 1250}


def opt_half(mkt, minute):
    a, b = OPT_HALF[mkt]
    return a if minute >= 17 * 60 else b


def mcx_real_books():
    from research.hunt.r53.mcx import data as D
    books, markets = {}, {}
    for mkt in ("CRUDE", "NG"):
        mk = D.load_market(SYM[mkt])
        t = pd.DatetimeIndex(mk.ts)
        df = pd.DataFrame({"day": t.date, "m": t.hour * 60 + t.minute, "h": mk.F, "l": mk.F, "c": mk.F})
        df["g"] = np.arange(len(df))
        df = df[np.isfinite(df.c) & (df.c > 0)]
        close = df.day.map({d: C.mcx_close_min(d) for d in df.day.unique()})
        df = df[(df.m >= C.MCX_OPEN_MIN) & (df.m < close)].reset_index(drop=True)
        dl = C.mcx_daily(SYM[mkt]).set_index("date")
        books[mkt] = book_from_frame(mkt, df, dl, lambda px, d, _m=mkt: C.mcx_notional(_m, px), g=df.g.values)
        markets[mkt] = mk
    return books, markets


def option_for(mk, mkt, t):
    """The same signal as a 1-ITM near-month option (R53 sim.sim_option: strike at the signal minute, entry at the
    first print within 2 minutes after it, exits on the future's stop / target and the time exit), re-costed with
    R83's strict option half-spreads. Expiry days skipped."""
    from research.hunt.r53.mcx import sim as S
    j = t["gj"]
    if mk.is_exp[j]:
        return None
    d = mk.day[j]
    ix = mk.day_ix(d)
    ok = ix[mk.tod[ix] <= t["gx_exit_m"]]
    if not len(ok):
        return None
    r = S.sim_option(mk, j, t["side"], exit_i=int(ok[-1]), fam="crude" if mkt == "CRUDE" else "natgas",
                     mult=OPT_MULT[mkt], strike="itm1",
                     stopF=t["stop"] if t["stop"] is not None else float("nan"),
                     tgtF=t["tgt"] if t["tgt"] is not None else float("nan"))
    if r is None or not np.isfinite(r["exit"]):
        return None
    buy, sell = r["fill"] * OPT_MULT[mkt], r["exit"] * OPT_MULT[mkt]
    cost = C.mcx_opt_charges(buy, sell) + opt_half(mkt, int(mk.tod[r["entry_i"]])) * buy + \
        opt_half(mkt, int(mk.tod[r["exit_i"]])) * sell
    r["cost"], r["net"] = float(cost), float(sell - buy - cost)
    return r


def final():
    books = _books()
    lo, hi = C.FINAL
    rb, rm = None, None
    out = {}
    for idea, spec in IDEAS.items():
        dz = C.RESULTS / f"{idea}_design.json"
        if not dz.exists():
            out[idea] = "no design"
            continue
        des = json.loads(dz.read_text())
        frozen = des["frozen"]
        book = books[spec["mkt"]]
        other = books.get(spec.get("other")) if spec.get("other") else None
        C.open_final(idea)
        ev = _events(spec["cal"], lo, hi)
        days = sorted(d for d in book.days if lo <= d <= hi)
        trs = run_variant(book, idea, spec["variants"][frozen], days, ev, other)
        base = run_variant(book, idea, None, days, ev, other) if idea.startswith("E6") else []
        s = C.summary(trs)
        tot = twin_totals(book, trs, twin_pool(book, idea, ev, lo, hi), C.SEED + 7 + _seed(idea))
        s["twin_p"] = C.twin_p(s["net"], tot)
        s["twin_mean"] = float(tot.mean())
        s["gross"] = float(sum(t["gross"] for t in trs))
        s["top5_removed"] = C.top5_removed(trs)
        s["by_year"] = {str(y): C.net_between(trs, date(y, 1, 1), date(y, 12, 31)) for y in range(2023, 2027)}
        bnet = float(sum(t["net"] for t in base)) if idea.startswith("E6") else 0.0
        bwf = des["baseline"]["wf_net"]
        res = {"frozen": frozen, "final": s, "baseline_final_net": bnet,
               "baseline_final_trades": len(base) if idea.startswith("E6") else 0,
               "wf_net": des["walk_forward"]["wf_net"], "baseline_wf_net": bwf, "trades": strip(trs)}
        # real MCX replay (same read)
        if rb is None:
            rb, rm = mcx_real_books()
        rbook = rb[spec["mkt"]]
        rother = rb.get(spec.get("other")) if spec.get("other") else None
        rlo, rhi = C.MCX_REAL
        rev = _events(spec["cal"], rlo, rhi)
        rdays = sorted(d for d in rbook.days if rlo <= d <= rhi)
        rtr = run_variant(rbook, idea, spec["variants"][frozen], rdays, rev, rother)
        ropt = [o for o in (option_for(rm[spec["mkt"]], spec["mkt"], t) for t in rtr) if o is not None]
        proxy_same = [t for t in trs if rlo <= C.day_of(t["entry_ts"]) <= rhi]
        res["mcx_real"] = {
            "futures": C.summary(rtr) | {"gross": float(sum(t["gross"] for t in rtr))},
            "option_1itm": C.summary([dict(o, entry_ts=pd.Timestamp(o["entry_ts"])) for o in ropt])
            | {"gross": float(sum(o["gross"] for o in ropt))},
            "proxy_same_window": C.summary(proxy_same),
        }
        C.save_json(f"{idea}_final.json", res)
        out[idea] = dict(frozen=frozen, n=s["trades"], net=round(s["net"]), p=round(s["twin_p"], 3),
                         top5_rest=round(s["top5_removed"]), base=round(bnet), wf=round(res["wf_net"]),
                         base_wf=round(bwf), real_fut=(len(rtr), round(res["mcx_real"]["futures"]["net"])),
                         real_opt=(len(ropt), round(res["mcx_real"]["option_1itm"]["net"])))
        print(idea, out[idea], flush=True)
    C.save_json("events_final_summary.json", out)
    return out


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1]]()
