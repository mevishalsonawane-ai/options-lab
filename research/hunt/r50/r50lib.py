"""R50 engine and rules (PREREG.md): every cancelled hero rule, one engine, the Nov 2024+ market only.

The held option's path, entry and target confirmation are R25's (`r25lib`: enter, path_px, confirmed, the liquidity
spread `Fills("liq")`). R50 adds: the charges in force on the trade date (`Costs("dated")`), the exits of every old study
under one roof, and the three sizing modes. Signals are the old studies' own code where it takes a `Day` (R21 HeroRules
port, R25 T1, R26 OI wall, R34 late range, R35 Supertrends, R36 GEX, R43 sweep signals).

Grid: row t = the 1-minute bar that STARTS at 09:15 + t; a decision at row t reads rows <= t and acts from row t + 1.
"""
from __future__ import annotations

import importlib.util
import math
import sys
from dataclasses import dataclass, field, replace

import numpy as np

import r50cfg as K
import r25data as D
import r25lib as R
import r25rules as RU
import r26lib as L26
import r43lib as L43
import r43sig as S43
from r25data import C, H, L, N, O, V, minute  # noqa: F401
from obuy.costs import Costs

COSTS = Costs("dated")
TICK = R.TICK
EPS = 1e-9
EXIT_ROW = R.EXIT_ROW              # 15:05 open
LAST_STOP_ROW = R.LAST_STOP_ROW    # closes of bars closing <= 15:04
TWIN_WINDOW = (R.close_row(12, 0), R.close_row(14, 45))     # fixed-time rules' twins (PREREG.md)


def _by_path(name: str, path):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


R21 = RU.R21
R21.atm = R.atm                     # R21's port only knew the NIFTY / BANKNIFTY steps; R25's reads every index's step
THETA_EXTRA = {"MIDCPNIFTY": 0.25}           # PREREG.md: 25% as BANKNIFTY / FINNIFTY (monthly-only indices)


def r35():
    return _by_path("r35lib", K.HUNT / "r35" / "r35lib.py")


def r36():
    return _by_path("r36gex", K.HUNT / "r36" / "r36gex.py")


def r34late():
    return _by_path("r34late", K.HUNT / "r34" / "r34late.py")


def triggers():
    import triggers as T  # R25's
    return T


# ------------------------------------------------------------------------------------------------ the trade
@dataclass
class Trade:
    und: str
    day: object
    rule: str
    side: int
    strike: float
    sig_row: int
    fill_row: int
    entry: float
    lots: int
    qty: int
    exits: list = field(default_factory=list)     # (row, qty, price, why)
    gross: float = 0.0
    charges: float = 0.0
    net: float = 0.0
    risked: float = 0.0
    peak_mult: float = 0.0                        # best confirmed price while held / fill
    best_exit_mult: float = 0.0                   # best exit price / fill
    proxy: bool = False

    def row(self) -> dict:
        return dict(und=self.und, d=self.day, side=self.side, strike=self.strike, sig_row=self.sig_row,
                    fill_row=self.fill_row, entry=self.entry, lots=self.lots, qty=self.qty, net=self.net,
                    risked=self.risked, peak=self.peak_mult, xmult=self.best_exit_mult,
                    why=self.exits[-1][3] if self.exits else "", proxy=self.proxy)


def charge(buy: bool, px: float, qty: int, und: str, d) -> float:
    if px <= 0 or qty <= 0:
        return 0.0
    dn = (d - type(d)(1970, 1, 1)).days
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]), np.array([dn]), bse=und in K.BSE)[0])


def lots_for(entry: float, lot: int, budget: float, size: str) -> int:
    """floor: whole lots within the budget (0 skips); min1: at least 1 lot (R26 / R34 / R36); skip: 0 when one lot costs
    more than the budget (R35), else whole lots within it."""
    if lot <= 0 or entry <= 0:
        return 0
    n = int(math.floor(budget / (entry * lot) + 1e-9))
    if size == "min1":
        return max(1, n)
    return n


def simulate(day, k: float, sgn: int, sig_row: int, ref: float, budget: float, exit_: str, size: str = "floor",
             mult: float = 1.0, rule: str = "") -> Trade | None:
    """One ticket on the HELD contract (strike k, side sgn). exit_: app | hold | s50 | E2 | E3 | tr25 (PREREG.md)."""
    side = "C" if sgn > 0 else "P"
    if (k, side) not in day.opts:
        return None
    f = R.enter(day, k, side, sig_row, ref, mult)
    if f is None:
        return None
    fr, e = f
    lots = lots_for(e, day.lot, budget, size)
    if lots <= 0:
        return None
    tr = Trade(day.und, day.day, rule, sgn, k, sig_row, fr, e, lots, lots * day.lot)
    a = day.opts[(k, side)]
    held = lots
    t1 = {"app": 5.0, "E2": 4.0, "E3": 2.0}.get(exit_)
    t1 = R.floor_tick(t1 * e) if t1 else math.inf
    t2 = R.floor_tick(20 * e) if exit_ == "app" else math.inf
    first_all = exit_ == "E2"                       # E2 sells everything at its target
    half_sold = False
    best_hi = e
    best_c = e
    peak = e
    lock = -math.inf

    def sell(r, n, px, why):
        tr.exits.append((r, n * day.lot, px, why))

    def half(n):
        return n if n == 1 else min(n, max(1, int(round(n * 0.5))))

    r = fr
    while r < EXIT_ROW and held > 0:
        o, hi, c, px = R.path_px(day, k, side, r)
        tr.proxy |= px
        if not math.isnan(hi):
            conf = R.confirmed(a, r)
            if not math.isnan(conf) and not px:
                peak = max(peak, min(hi, conf))
            if not half_sold and hi >= t1 + TICK - EPS and conf >= t1 - EPS and not px:
                n = held if first_all else half(held)
                sell(r, n, t1, "target")
                held -= n
                half_sold = True
                if held <= 0:
                    break
            if half_sold and held > 0 and hi >= t2 + TICK - EPS and conf >= t2 - EPS and not px:
                sell(r, held, t2, "20x")
                held = 0
                break
        why = None
        if r > fr and r <= LAST_STOP_ROW and not math.isnan(c):
            if exit_ == "app" and c <= 0.4 * e + EPS:
                why = "stop"
            elif exit_ == "s50" and c <= 0.5 * e + EPS:
                why = "stop"
            elif exit_ == "E3" and c <= lock + EPS:
                why = "lock"
            elif exit_ == "tr25" and c <= 0.75 * max(best_c, c) + EPS:
                why = "trail"
        if not math.isnan(hi) and not px:
            best_hi = max(best_hi, hi)
        if not math.isnan(c) and not px:
            best_c = max(best_c, c)
        if exit_ == "E3" and best_hi >= 2 * e - EPS:     # takes effect from the next minute's close
            lock = max(lock, (math.floor(best_hi / e + 1e-9) - 1) * e)
        if why:
            x = R._next_open(day, k, side, r + 1, False)
            if x is not None:
                rr, oo, pxx = x
                tr.proxy |= pxx
                sell(rr, held, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), why)
                held = 0
                break
        r += 1
    if held > 0:
        x = R._next_open(day, k, side, EXIT_ROW, False)
        if x is None:
            p_, row = R.ltp(a, EXIT_ROW - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.proxy |= pxx
        sell(rr, held, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), "1505")
    tr.peak_mult = peak / e
    _settle(tr)
    return tr


def _settle(tr: Trade) -> None:
    buy = tr.entry * tr.qty
    ch = charge(True, tr.entry, tr.qty, tr.und, tr.day)
    tr.risked = buy + ch
    sold = 0.0
    best = 0.0
    for _, q, px, _ in tr.exits:
        best = max(best, px)
        if px <= 0:                       # worthless: no sell order, no charges
            continue
        sold += px * q
        ch += charge(False, px, q, tr.und, tr.day)
    tr.gross = round(sold - buy, 2)
    tr.charges = round(ch, 2)
    tr.net = round(tr.gross - ch, 2)
    tr.best_exit_mult = best / tr.entry if tr.entry > 0 else 0.0


# ------------------------------------------------------------------------------------------------ strike pickers
def pick_band(band):
    """Nearest-the-money eligible OTM in the band (R25 `eligible`: 5% of spot, traded in 5 bars, stale-slack)."""
    def f(day, sgn, t):
        el = R.eligible(day, sgn, t, band)
        return (el[0][0], el[0][1]) if el else None
    return f


def pick_near(band, target):
    """Eligible OTM in the band whose price is nearest `target` (R25 lottery_pick, R36 pair)."""
    def f(day, sgn, t):
        el = R.eligible(day, sgn, t, band)
        if not el:
            return None
        s = R.spot(day, t)
        el.sort(key=lambda kp: (abs(kp[1] - target), abs(kp[0] - s)))
        return el[0]
    return f


def pick_hero(band, k_up: float = 0.0):
    """HeroRules.pick (R21 port: 3% of spot, band, traded, stale-slack; + own close >= k_up x its low since 12:00)."""
    def f(day, sgn, t):
        return R21.pick(day, sgn, t, band, k_up)
    return f


def pick_atm(offset: int, band=None):
    """ATM + offset strikes in the trade's direction, traded in the last 5 bars, price in band (None: any)."""
    def f(day, sgn, t):
        s = R.spot(day, t)
        if math.isnan(s):
            return None
        k = R.atm(day, s) + sgn * offset * K.STEP[day.und]
        a = day.opts.get((k, "C" if sgn > 0 else "P"))
        if a is None:
            return None
        p, row = R.ltp(a, t)
        if row < 0 or row < t - (R.TRADED_BARS - 1) or math.isnan(p) or p <= 0:
            return None
        if band is not None and not (band[0] - EPS <= p <= band[1] + EPS):
            return None
        return k, p
    return f


def pick_otm2(day, sgn, t):
    return L43.pick_strike(day, sgn, t, "otm2")


PICKERS = {
    "hero1_5": pick_hero((1.0, 5.0)), "hero5_30": pick_hero((5.0, 30.0)), "hero1_15": pick_hero((1.0, 15.0)),
    "lot3": pick_near((1.0, 5.0), 3.0), "cheap": pick_band((5.0, 30.0)), "band1_10": pick_band((1.0, 10.0)),
    "r22": pick_near((15.0, 30.0), 22.5), "atm1_15_30": pick_atm(1, (15.0, 30.0)), "atm1": pick_atm(1),
    "otm2": pick_otm2,
}


# ------------------------------------------------------------------------------------------------ signals
@dataclass
class Sig:
    row: int
    side: int
    strike: float
    ref: float


def _hero_signals(day, rs) -> list[Sig]:
    if rs.kind == "hero":
        p = R21.Params(name="V0", trigger="straddle", first=R.close_row(13, 30), last=R.close_row(14, 45),
                       band=rs.arg["band"], exits="app")
    else:
        p = R21.Params(name="V4", trigger="onesided", k=2.5, first=R.close_row(13, 30), last=R.close_row(15, 0),
                       band=rs.arg["band"], exits="app")
    s = R21.first_signal(day, p)
    return [Sig(s.row, s.side, s.strike, s.ref)] if s else []


def _basket_signals(day, rs) -> list[Sig]:
    t = rs.arg["at"]
    out = []
    for sgn in (1, -1):
        c = PICKERS[rs.pick](day, sgn, t)
        if c:
            out.append(Sig(t, sgn, c[0], c[1]))
    return out


def _r26_rule(rs):
    base = L26.P if rs.arg.get("oi", True) else next(r for r in L26.RULES if r.id == "B1")
    return replace(base, unds=K.UNDS)


def _r26_signals(day, rs) -> list[Sig]:
    """R26's trigger (first minute 13:45-14:30, UP before DOWN) with the rule's own strike picker; R26 needs the pick
    to succeed for the minute to count (its `signal`), and so does this."""
    rule = _r26_rule(rs)
    ch = L26.chain(day)
    added = {k: v for k, v in THETA_EXTRA.items() if k not in L26.THETA}
    L26.THETA.update(added)                     # only while R50 reads it (R26's own table and tests stay as they are)
    try:
        for t in range(L26.FIRST, L26.LAST + 1):
            for sgn in (1, -1):
                cd = L26.conditions(day, rule, sgn, t, ch)
                if not L26.fires(cd, rule):
                    continue
                pk = PICKERS[rs.pick](day, sgn, t)
                if pk is None:
                    continue
                return [Sig(t, sgn, pk[0], pk[1])]
        return []
    finally:
        for k in added:
            L26.THETA.pop(k, None)


GEX_FLAGS: dict = {}         # (und, date) -> {"ok", "x1", "x2"} (filled by gex_flags())


def gex_flags(days_warm: dict) -> dict:
    """R36's regime flags for every expiry day (the scale reads the previous 20 expiries, the cuts every earlier one), at
    the 14:00 close. days_warm: {und: every Day incl. the warm-up days before 20 Nov 2024}."""
    m = r36()
    out = {}
    for u, ds in days_warm.items():
        ds = sorted(ds, key=lambda d: d.day)
        meas = []
        for d in ds:
            sig = m.atm_iv(d, m.T0)
            meas.append((d, *m.gex(d, m.T0, sig)))
        fl = m.regime_flags([x[1] for x in meas], [x[2] for x in meas])
        for (d, _, _), f in zip(meas, fl):
            out[(u, d.day)] = f
    GEX_FLAGS.update(out)
    return out


def _gex_signals(day, rs) -> list[Sig]:
    f = GEX_FLAGS.get((day.und, day.day))
    if not f or not f.get("ok") or not f.get(rs.arg["flag"]):
        return []
    return _basket_signals(day, rs)


_BARS: dict = {}


def sweep_table(und: str) -> dict:
    """{date: {family: {bar j: +-1}}} from the continuous 5-minute index bars (R43 signals)."""
    if und not in _BARS:
        import r50data
        b = r50data.load_bars(und)
        sg = S43.signals(b)
        out: dict = {}
        js = b.j.values
        ds = b.d.values
        for fam in S43.FAMILIES:
            v = sg[fam].values
            nz = np.nonzero(v)[0]
            for i in nz:
                out.setdefault(ds[i], {}).setdefault(fam, {})[int(js[i])] = int(v[i])
        _BARS[und] = out
    return _BARS[und]


def _sweep_signals(day, rs) -> list[Sig]:
    """Every signal of the family in the window, in time order (the run takes the first that trades, as R43)."""
    j0, j1 = rs.arg["win"]
    fs = sweep_table(day.und).get(day.day, {}).get(rs.arg["fam"], {})
    out = []
    for j in sorted(fs):
        if j0 <= j <= j1:
            t = L43.sig_row(j)
            pk = PICKERS[rs.pick](day, fs[j], t)
            if pk:
                out.append(Sig(t, fs[j], pk[0], pk[1]))
    return out


def _st_signals(day, rs) -> list[Sig]:
    """R35's trigger (14:00-14:45, first minute, CE before PE) with the rule's chart; strike ATM+-1 (R35 nearest_otm)."""
    m = r35()
    rule = next(r for r in m.RULES if r.id == rs.arg["r35"])
    ctx = m.Ctx(day, None)
    for t in range(m.FIRST, m.LAST + 1):
        for sgn in (1, -1):
            f = m.fires(day, rule, ctx, t, sgn)
            if f is not None:
                return [Sig(t, sgn, f[0], f[1])]
    return []


def _k1_signals(day, rs) -> list[Sig]:
    """R34 K1: the 14:30-14:45 index range; the first 1-minute close outside it by 15:00; then the rule's strike."""
    m = r34late()
    a, b, last = m.WINDOWS[1]
    hi, lo = m.rng(day, 1)
    if math.isnan(hi) or math.isnan(lo):
        return []
    for t in range(b + 1, last + 1):
        c = day.ix[t, 3]
        if math.isnan(c):
            continue
        sgn = 1 if c > hi else (-1 if c < lo else 0)
        if not sgn:
            continue
        pk = PICKERS[rs.pick](day, sgn, t)
        return [Sig(t, sgn, pk[0], pk[1])] if pk else []
    return []


def _t1_signals(day, rs) -> list[Sig]:
    T = triggers()
    rule = replace(T.make(T.SPECS[0], {"dist_em_max": 1.25}, "app"), unds=K.UNDS)
    return [Sig(s.row, s.side, s.strike, s.ref) for s in T.signals(day, rule)]


SIGNALS = {"hero": _hero_signals, "onesided": _hero_signals, "basket": _basket_signals, "r26": _r26_signals,
           "gex": _gex_signals, "sweep": _sweep_signals, "st": _st_signals, "k1": _k1_signals, "t1": _t1_signals}


# ------------------------------------------------------------------------------------------------ rules
@dataclass(frozen=True)
class RuleSpec:
    id: str
    family: str
    kind: str
    pick: str
    budget: float
    exit: str
    size: str = "floor"
    unds: tuple = K.UNDS
    arg: dict = field(default_factory=dict, hash=False, compare=False)
    window: tuple = TWIN_WINDOW       # the twins' entry window (rows)
    take: str = "first"               # first: the first signal only; first_fill: the first that trades; all: every one
    source: str = "index"
    orig: str = ""
    note: str = ""

    def with_(self, **kw) -> "RuleSpec":
        return replace(self, **kw)


def signals(day, rs: RuleSpec) -> list[Sig]:
    if day.und not in rs.unds:
        return []
    return SIGNALS[rs.kind](day, rs)


def run_day(day, rs: RuleSpec, mult: float = 1.0) -> list[Trade]:
    out = []
    for s in signals(day, rs):
        tr = simulate(day, s.strike, s.side, s.row, s.ref, rs.budget, rs.exit, rs.size, mult, rs.id)
        if tr is not None:
            out.append(tr)
            if rs.take != "all":
                break
        elif rs.take == "first":
            break
    return out


def run(days: list, rs: RuleSpec, mult: float = 1.0) -> list[Trade]:
    out = []
    for d in days:
        out += run_day(d, rs, mult)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twins(days: list, rs: RuleSpec, trades: list, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Net of n twin sets: each trade re-entered at a uniformly random minute of the rule's window, same day, side,
    strike rule, budget, sizing and exit; up to 20 draws for a minute that trades, else 0."""
    rng = np.random.default_rng(seed)
    byday = {(d.und, d.day): d for d in days}
    a, b = rs.window
    cache: dict = {}
    out = np.zeros(n)

    def net_at(d, sgn, t):
        key = (d.und, d.day, sgn, t)
        if key not in cache:
            pk = PICKERS[rs.pick](d, sgn, t)
            tr = simulate(d, pk[0], sgn, t, pk[1], rs.budget, rs.exit, rs.size, 1.0, rs.id) if pk else None
            cache[key] = None if tr is None else tr.net
        return cache[key]

    for i in range(n):
        tot = 0.0
        for tr in trades:
            d = byday[(tr.und, tr.day)]
            for _ in range(20):
                x = net_at(d, tr.side, int(rng.integers(a, b + 1)))
                if x is not None:
                    tot += x
                    break
        out[i] = tot
    return out


def p_value(net: float, tw: np.ndarray) -> float:
    return float((np.sum(tw >= net - 1e-9) + 1) / (len(tw) + 1))


def bh(ps: list[float]) -> list[float]:
    return L43.bh(ps)


# ------------------------------------------------------------------------------------------------ metrics
def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.cumsum(daily)
    return float(np.max(np.maximum.accumulate(np.r_[0.0, eq])[1:] - eq))


def metrics(trades: list, n_days: int) -> dict:
    """n_days: index-expiry-days of the rule's universe in the period."""
    nets = np.array([t.net for t in trades], dtype=float)
    out = dict(trades=len(trades), n_days=n_days, net=round(float(nets.sum()), 0) if len(nets) else 0.0)
    if not len(nets):
        out.update(win=0.0, per_day=0.0, worst_day=0.0, max_dd=0.0, hero5=0, hero10=0, banked5=0, top1=0.0, top3=0.0,
                   top5=0.0, ex1=0.0, ex3=0.0, ex5=0.0, risked=0.0)
        return out
    daily: dict = {}
    for t in trades:
        daily[t.day] = daily.get(t.day, 0.0) + t.net
    dk = sorted(daily)
    dv = np.array([daily[k] for k in dk])
    srt = np.sort(nets)[::-1]
    tot = float(nets.sum())
    out.update(
        win=round(float((nets > 0).mean() * 100), 1),
        per_day=round(tot / max(n_days, 1), 0),
        worst_day=round(float(dv.min()), 0),
        max_dd=round(max_dd(dv), 0),
        hero5=int(sum(t.peak_mult >= 5 - 1e-9 for t in trades)),
        hero10=int(sum(t.peak_mult >= 10 - 1e-9 for t in trades)),
        banked5=int(sum(t.best_exit_mult >= 5 - 1e-9 for t in trades)),
        risked=round(float(sum(t.risked for t in trades)), 0),
    )
    for k in (1, 3, 5):
        top = float(srt[:k].sum())
        out[f"top{k}"] = round(top / tot * 100, 0) if tot > 0 else None      # share of net (None: net <= 0)
        out[f"ex{k}"] = round(tot - top, 0)
    return out


def by_block(trades: list) -> dict:
    out = {b: 0.0 for b, _, _ in K.BLOCKS}
    for t in trades:
        b = K.block(t.day)
        if b:
            out[b] += t.net
    return {k: round(v, 0) for k, v in out.items()}
