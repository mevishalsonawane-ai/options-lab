"""R53 crypto rules CC1-CC8 (PREREG section 4), each with its original settings and the pre-registered re-tune (RT)
variants only. Every version has a stop-loss AND a profit lock (CLAUDE.md crypto rule): the exit objects refuse to
be built without both (rc2.exits.Exit, f1c.perp.PerpExit, perps.BrkExit) and `exit_of` refuses anything else.

Signals are the studies' own functions, run on the Binance USD-M perp (PREREG: the perp is the signal source):
  CC1-CC3: research/hunt/rc2/signals.py pdl_break / vol_burst / hero on 5-minute perp bars (+ Deribit DVOL);
  CC4-CC7: research/hunt/f1c/rules.py p07_prevday_break / p13_oi_divergence / o19_vol_burst on the F1C decision
           frame (5-minute closes whose entry minute is inside 00:00-09:00 IST; engine.frame), with the brain features
           recomputed in features.py;
  CC8:     research/hunt/rc1/strategies.py idea g (4-hour breakout) on 4-hour perp bars.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from research.hunt.f1c import perp as f1cperp
from research.hunt.f1c import rules as f1crules
from research.hunt.f1c.engine import in_window as f1c_in_window, window_end as f1c_window_end
from research.hunt.rc2 import signals as rc2sig
from research.hunt.rc2.exits import DIRECTIONAL, HERO, Exit

from .. import common as RC
from . import data, features, fetch, opt, perps

ONE = pd.Timedelta(minutes=1)
IST = pd.Timedelta(hours=5, minutes=30)
ACCOUNT = 100_000.0
RC2_DAY_CAP = 0.05          # rc2/config.py DAILY_LOSS_CAP: a rule stops for the IST day after losing 5% of the account
F1C_DAY_CAP = 0.03          # f1c/f1ccfg.py DAY_LOSS_CAP (per coin, as f1c engine.trades)
EXPIRY_H = 12               # Delta India daily options settle at 12:00 UTC (17:30 IST)
LAST_EXIT = pd.Timedelta(minutes=10)


@dataclass(frozen=True)
class CC:
    rid: str
    name: str
    kind: str                     # rc2opt | f1copt | f1cperp | rc1brk
    signal: str
    variants: tuple               # the original first, then the RT variants
    max_hold_min: int | None = None
    max_per_day: int | None = None
    min_left_h: float = 0.0
    strike: str = "atm"
    two_way: bool = True

    @property
    def original(self):
        return self.variants[0]

    @property
    def venue(self):
        return "option" if self.kind.endswith("opt") else "perp"


RULES = {
    "CC1": CC("CC1", "rc2-r8-pdl", "rc2opt", "R8_pdl_break", ("s40_step", "s40_trail", "s25_step"),
              max_hold_min=180, min_left_h=3),
    "CC2": CC("CC2", "rc2-r4-burst", "rc2opt", "R4_vol_burst", ("s40_trail", "s40_step"), max_hold_min=120,
              min_left_h=1.5),
    "CC3": CC("CC3", "rc2-r6-hero", "rc2opt", "R6_hero", ("s70_step", "s50_step"), max_hold_min=180,
              min_left_h=1 / 6, strike="cheap"),
    "CC4": CC("CC4", "f1c-p07", "f1cperp", "P07", ("s15_trail", "s10_trail", "s15_step"), max_hold_min=180,
              max_per_day=1),
    "CC5": CC("CC5", "f1c-p13", "f1cperp", "P13", ("s15_step", "s15_trail"), max_hold_min=120, max_per_day=2),
    "CC6": CC("CC6", "f1c-o19", "f1copt", "O19", ("s25_step", "s40_trail"), max_hold_min=120, max_per_day=2),
    "CC7": CC("CC7", "f1c-o18", "f1copt", "P07", ("s40_trail", "s25_step"), max_hold_min=180, max_per_day=1),
    "CC8": CC("CC8", "rc1-g-breakout", "rc1brk", "g", ("orig", "step_lock"), two_way=False),
}
# CC5: F1C's design pick for P13 was s15_step (f1c/results/design.json); its RT is "the other lock", s15_trail.

for _r in RULES.values():          # RC2 rules: hold limits and expiry rules are RC2's own (signals.RULES)
    if _r.kind == "rc2opt":
        _s = rc2sig.RULES[_r.signal]
        assert _r.max_hold_min == int(_s.max_hold_h * 60) and _r.min_left_h == _s.min_left_h and _r.strike == _s.strike


def exit_of(rule: CC, variant: str):
    if variant not in rule.variants:
        raise KeyError(f"{variant} is not a pre-registered version of {rule.rid}")
    if rule.venue == "option":
        pool = HERO if rule.strike == "cheap" else DIRECTIONAL
        ex = {e.name: e for e in pool}[variant]
    elif rule.kind == "f1cperp":
        ex = {e.name: e for e in f1cperp.EXITS}[variant]
    else:
        ex = perps.BRK_EXITS[variant]
    assert_stop_and_lock(ex)
    return ex


def assert_stop_and_lock(ex) -> None:
    """CLAUDE.md crypto rule: refuse any version without a stop-loss and a profit lock."""
    if isinstance(ex, Exit):
        ok = 0 < ex.stop < 1 and ex.lock in ("step", "trail")
    elif isinstance(ex, f1cperp.PerpExit):
        ok = ex.k_atr > 0 and ex.lock in ("step", "trail")
    elif isinstance(ex, perps.BrkExit):
        ok = ex.stop_atr > 0 and ex.trail_n >= 1
    else:
        ok = False
    if not ok:
        raise ValueError(f"refused: {ex!r} lacks a stop-loss or a profit lock")


# ------------------------------------------------------------------------------------------------ market of one coin
class Market:
    """Everything one coin's rules read. Signals and features use rows up to the decision only (tested)."""

    def __init__(self, coin: str, m1: pd.DataFrame | None = None, dvol: pd.Series | None = None,
                 oi: pd.DataFrame | None = None, index: pd.Series | None = None, store=None):
        self.coin = coin
        self.m1 = data.perp_1m(coin) if m1 is None else m1
        self.dvol = data.dvol_1h(coin) if dvol is None else dvol
        self.oi = data.metrics(coin) if oi is None else oi
        self.index = data.index_1m(coin) if index is None else index
        self.chain = opt.Chain(coin, store or fetch.OptionStore())
        self._b5 = self._F = self._dec = self._b4 = None

    @property
    def b5(self):
        if self._b5 is None:
            self._b5 = data.resample(self.m1, "5min")
        return self._b5

    @property
    def b4(self):
        if self._b4 is None:
            self._b4 = data.resample(self.m1, "4h")
        return self._b4

    @property
    def F(self):
        if self._F is None:
            self._F = features.build(self.m1, self.oi, self.dvol)
        return self._F

    @property
    def dec(self):
        """F1C's decision frame (engine.frame): rows closing on a 5-minute boundary whose entry minute is in the
        window, with ts (bar start), ist_min and ist_day."""
        if self._dec is None:
            F = self.F
            ts = F.index
            keep = ((ts.minute + 1) % 5 == 0) & f1c_in_window(ts)
            D = F[keep].reset_index().rename(columns={"t": "ts", "index": "ts"})
            D["ts"] = pd.DatetimeIndex(D.ts).tz_convert("UTC").tz_localize(None)   # naive UTC, as F1C's frames
            e = D.ts + ONE + IST
            D["ist_min"] = (e.dt.hour * 60 + e.dt.minute).astype(int)
            D["ist_day"] = e.dt.date
            self._dec = D
        return self._dec

    def spot_at(self, T) -> float:
        """The Delta index known at T: the close of its minute that ends at T (5-minute staleness at most)."""
        s = self.index
        v = s.get(T - ONE, np.nan)
        if np.isfinite(v):
            return float(v)
        i = s.index.searchsorted(T - ONE, side="right") - 1
        if i >= 0 and (T - ONE) - s.index[i] <= pd.Timedelta(minutes=5):
            return float(s.iloc[i])
        return np.nan


# ------------------------------------------------------------------------------------------------ signals
def signals(rule: CC, mk: Market) -> pd.DataFrame:
    """All decisions of the rule on the coin's full series: [T (the decision instant: the signal bar's close), side
    (+1 long / call, -1 short / put), ts (the F1C decision row, for its ATR)]."""
    if rule.kind == "rc2opt":
        b = mk.b5[["o", "h", "l", "c"]]
        if rule.signal == "R8_pdl_break":
            e = rc2sig.pdl_break(b)
        elif rule.signal == "R4_vol_burst":
            e = rc2sig.vol_burst(b, mk.dvol)
        elif rule.signal == "R6_hero":
            e = rc2sig.hero(b, mk.dvol)
        else:
            raise KeyError(rule.signal)
        T = pd.DatetimeIndex(e["T"])
        return pd.DataFrame({"T": T, "side": np.where(e.side.to_numpy() == "C", 1, -1), "ts": T - ONE})
    if rule.kind in ("f1cperp", "f1copt"):
        F = mk.dec
        fn = {"P07": f1crules.p07_prevday_break, "P13": f1crules.p13_oi_divergence,
              "O19": f1crules.o19_vol_burst}[rule.signal]
        d = fn(F)
        d = d[f1c_in_window(d.ts)].sort_values("ts").reset_index(drop=True)
        ts = utc(d.ts)
        return pd.DataFrame({"T": ts + ONE, "side": d.side.to_numpy(int), "ts": ts})
    raise KeyError(rule.kind)


def entry_time(rule: CC, T):
    """Perps fill at the next bar's open (T); Delta options one minute later (T + 1 min)."""
    return pd.DatetimeIndex(T) + (ONE if rule.venue == "option" else pd.Timedelta(0))


def in_part(rule: CC, T, part: str) -> np.ndarray:
    return np.array([RC.part_of(t) == part for t in entry_time(rule, T)], bool)


def rc2_expiry(T: pd.Timestamp, min_left_h: float):
    """rc2/sim.py pick_expiry: the nearest daily 12:00 UTC expiry with at least min_left_h hours left."""
    base = T.floor("D") + pd.Timedelta(hours=EXPIRY_H)
    for k in range(3):
        E = base + pd.Timedelta(days=k)
        if E > T and (E - T) >= pd.Timedelta(hours=min_left_h):
            return E
    return None


def f1c_expiry(T: pd.Timestamp) -> pd.Timestamp:
    """f1c/engine.py option_trades: the expiry at 12:00 UTC of the entry's IST date."""
    return pd.Timestamp((T + IST).date(), tz="UTC") + pd.Timedelta(hours=EXPIRY_H)


def utc(ts) -> pd.DatetimeIndex:
    t = pd.DatetimeIndex(ts)
    return t.tz_localize("UTC") if t.tz is None else t.tz_convert("UTC")


def deadline_of(rule: CC, T: pd.Timestamp, E=None) -> pd.Timestamp:
    if rule.kind == "rc2opt":
        return min(T + pd.Timedelta(minutes=rule.max_hold_min), E - LAST_EXIT)
    we = f1c_window_end([T - ONE])[0]
    return min(we, T + pd.Timedelta(minutes=rule.max_hold_min)) if rule.max_hold_min else we


# ------------------------------------------------------------------------------------------------ trades
def option_candidate(rule: CC, mk: Market, T, side, ex):
    E = rc2_expiry(T, rule.min_left_h) if rule.kind == "rc2opt" else f1c_expiry(T)
    if E is None:
        return "no_expiry"
    dl = deadline_of(rule, T, E)
    return opt.trade(mk.chain, T, "C" if side > 0 else "P", E, dl, ex, rule.strike, mk.spot_at, data.fx_at)


def option_keys(rule: CC, mk: Market, T, side):
    E = rc2_expiry(T, rule.min_left_h) if rule.kind == "rc2opt" else f1c_expiry(T)
    s = mk.spot_at(T)
    if E is None or not np.isfinite(s):
        return []
    return opt.keys_needed(mk.chain, T, "C" if side > 0 else "P", E, rule.strike, s)


def prefetch(rule: CC, mk: Market, ents: pd.DataFrame, threads: int = 6) -> int:
    if rule.venue != "option" or ents.empty:
        return 0
    keys = [k for T, s in zip(ents["T"], ents.side) for k in option_keys(rule, mk, T, s)]
    return mk.chain.store.prefetch(keys, threads)


def select(B: pd.DataFrame, cap: float, max_per_day: int | None, by_coin: bool = True) -> pd.DataFrame:
    """f1c/book.py select: in entry order, one position at a time (per coin), none after the IST day's net (Delta
    book, before tax) reaches -cap x account, nor beyond max_per_day a day."""
    if B.empty:
        return B
    out = []
    groups = B.groupby("coin") if by_coin else [(None, B)]
    for _, g in groups:
        g = g.sort_values("entry_ts", kind="stable").reset_index(drop=True)
        keep = np.zeros(len(g), bool)
        busy, day, dpnl, dn = None, None, 0.0, 0
        ist = (pd.DatetimeIndex(g.entry_ts) + IST).date
        for i, (t0, t1, pnl) in enumerate(zip(g.entry_ts, g.exit_ts, g.net_delta)):
            if ist[i] != day:
                day, dpnl, dn = ist[i], 0.0, 0
            if (busy is not None and t0 < busy) or dpnl <= -cap * ACCOUNT or (max_per_day and dn >= max_per_day):
                continue
            keep[i] = True
            busy, dpnl, dn = t1, dpnl + pnl, dn + 1
        out.append(g[keep])
    return pd.concat(out).sort_values("entry_ts").reset_index(drop=True)


def rc2_day_cap(B: pd.DataFrame, cap: float = RC2_DAY_CAP) -> pd.DataFrame:
    """rc2/stats.py money: the rule (both coins) stops for the IST day once its realised loss reaches cap x account."""
    if B.empty:
        return B
    B = B.sort_values("T", kind="stable").reset_index(drop=True)
    ist = (pd.DatetimeIndex(B["T"]) + IST).date
    keep, dp = [], {}
    for i, p in enumerate(B.net_delta):
        if dp.get(ist[i], 0.0) <= -cap * ACCOUNT:
            keep.append(False)
            continue
        keep.append(True)
        dp[ist[i]] = dp.get(ist[i], 0.0) + p
    return B[np.array(keep)].reset_index(drop=True)


def rule_trades(rule: CC, variant: str, mk: Market, part: str, skips: dict | None = None) -> pd.DataFrame:
    """The rule's selected trades for one coin, one version, entries inside `part` only."""
    ex = exit_of(rule, variant)
    skips = {} if skips is None else skips
    if rule.kind == "rc1brk":
        b4 = mk.b4
        sp = perps.brk_signals(b4.o.to_numpy(float), b4.h.to_numpy(float), b4.l.to_numpy(float),
                               b4.c.to_numpy(float), ex)
        B = perps.brk_trades(mk.coin, b4, sp, data.fx_at, lambda t: data.funding_rate_at(mk.coin, t))
        if B.empty:
            return B
        return B[[RC.part_of(t) == part for t in B.entry_ts]].reset_index(drop=True)
    ents = signals(rule, mk)
    ents = ents[in_part(rule, ents["T"], part)].reset_index(drop=True)
    skips["signals"] = skips.get("signals", 0) + len(ents)
    if ents.empty:
        return pd.DataFrame()
    if rule.kind == "f1cperp":
        atr = mk.F.atr14_1h_pct.reindex(utc(ents.ts)).to_numpy()
        dl = [deadline_of(rule, T) for T in ents["T"]]
        B = perps.f1c_trades(mk.coin, mk.m1, ents["T"], ents.side, atr, dl, ex, data.fx_at,
                             lambda t: data.funding_rate_at(mk.coin, t))
        return select(B, F1C_DAY_CAP, rule.max_per_day)
    prefetch(rule, mk, ents)
    rows, busy = [], None
    day, dpnl, dn = None, 0.0, 0
    for T, s in zip(ents["T"], ents.side):
        if busy is not None and T + (ONE if rule.kind == "f1copt" else pd.Timedelta(0)) < busy:
            skips["busy"] = skips.get("busy", 0) + 1                    # one position at a time (rc2 sim / f1c select)
            continue
        if rule.kind == "f1copt":                                         # f1c/book.py select, applied in entry order
            d = (T + ONE + IST).date()
            if d != day:
                day, dpnl, dn = d, 0.0, 0
            if dpnl <= -F1C_DAY_CAP * ACCOUNT or (rule.max_per_day and dn >= rule.max_per_day):
                skips["day_limit"] = skips.get("day_limit", 0) + 1
                continue
        r = option_candidate(rule, mk, T, s, ex)
        if isinstance(r, str):
            skips[r] = skips.get(r, 0) + 1
            continue
        rows.append(r)
        busy = r["exit_ts"]
        if rule.kind == "f1copt":
            dpnl, dn = dpnl + r["net_delta"], dn + 1
    return pd.DataFrame(rows)                                            # rc2 day cap: across coins, in run_rule


def run_rule(rule: CC, variant: str, mkts: dict, part: str) -> tuple[pd.DataFrame, dict]:
    skips: dict = {}
    parts = [rule_trades(rule, variant, mkts[c], part, skips) for c in mkts]
    parts = [p for p in parts if len(p)]
    B = pd.concat(parts).sort_values("entry_ts").reset_index(drop=True) if parts else pd.DataFrame()
    if rule.kind == "rc2opt" and len(B):
        B = rc2_day_cap(B)
    return B, skips


# ------------------------------------------------------------------------------------------------ random-entry twins
def twin_entries(rule: CC, mk: Market, part: str, n: int, rng: np.random.Generator) -> pd.DataFrame:
    """Random entries inside the rule's own entry window on random days of the part (PREREG section 3): random
    decision bars of the window, a random side for two-way rules (CC8 is long-only)."""
    if rule.kind == "rc2opt":
        r = rc2sig.RULES[rule.signal]
        bars = mk.b5.index
        T = bars[rc2sig.in_window(r, bars)] + pd.Timedelta(minutes=5)
    elif rule.kind in ("f1cperp", "f1copt"):
        T = utc(mk.dec.ts) + ONE
    else:
        b4 = mk.b4
        T = b4.index[20:]                    # a 4-hour bar with its ATR and 10-bar low defined (entry bar start)
    T = T[in_part(rule, T, part)]
    if len(T) == 0:
        return pd.DataFrame(columns=["T", "side", "ts"])
    pick = np.sort(rng.choice(len(T), min(n, len(T)), replace=False))
    side = rng.choice([-1, 1], len(pick)) if rule.two_way else np.ones(len(pick), int)
    TT = T[pick]
    return pd.DataFrame({"T": TT, "side": side, "ts": TT - ONE})


def twin_pool(rule: CC, variants, mk: Market, ents: pd.DataFrame) -> dict:
    """Each random entry simulated on its own (no one-at-a-time, no caps) with each version's exits. Returns
    {variant: DataFrame of trades}."""
    out = {}
    if ents.empty:
        return {v: pd.DataFrame() for v in variants}
    if rule.venue == "option":
        prefetch(rule, mk, ents)
    for v in variants:
        ex = exit_of(rule, v)
        if rule.kind == "rc1brk":
            b4 = mk.b4
            o, h, l, c = (b4[k].to_numpy(float) for k in ("o", "h", "l", "c"))
            a, ll, _ = perps.brk_levels(h, l, c)
            pos = b4.index.get_indexer(pd.DatetimeIndex(ents["T"]))
            sp = [(e,) + perps.brk_walk(e, o, h, l, c, a, ll, ex) for e in pos if e >= 1 and np.isfinite(a[e - 1])]
            out[v] = perps.brk_trades(mk.coin, b4, sp, data.fx_at, lambda t: data.funding_rate_at(mk.coin, t))
        elif rule.kind == "f1cperp":
            atr = mk.F.atr14_1h_pct.reindex(utc(ents.ts)).to_numpy()
            dl = [deadline_of(rule, T) for T in ents["T"]]
            out[v] = perps.f1c_trades(mk.coin, mk.m1, ents["T"], ents.side, atr, dl, ex, data.fx_at,
                                      lambda t: data.funding_rate_at(mk.coin, t))
        else:
            rows = [option_candidate(rule, mk, T, s, ex) for T, s in zip(ents["T"], ents.side)]
            out[v] = pd.DataFrame([r for r in rows if isinstance(r, dict)])
    return out
