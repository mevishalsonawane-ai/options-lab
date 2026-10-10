"""h34 shared pieces: the premium-POINT exit menu, costs (dated charges + real half-spread), a fast multi-exit runner on
obuy packs, trade post-processing (gross at mid, net, stress) and the strategy list (see PREREG.md)."""
from __future__ import annotations

import os
import sys
from dataclasses import replace
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.dirname(HERE))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule, run_exits, positions  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h34")
HOLD = date(2025, 10, 1)
SQ10 = 15 * 60 + 10
UNDS5 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0020}
LOT_NOW = {"NIFTY": 65, "BANKNIFTY": 30, "FINNIFTY": 60, "SENSEX": 20, "MIDCPNIFTY": 120}
LOCK = ((1.0, 0.0), (2.0, 1.0), (3.0, 2.0))          # +10 -> BE, +20 -> +10, +30 -> +20 on R = 10 points
TGTS, STOPS, TSTOPS, LOCKS = (15, 20, 25, 30), (10, 15, 20, 30), (15, 30, 60, None), (False, True)
MENU, MLAB = [], []
for _t in TGTS:
    for _s in STOPS:
        for _ts in TSTOPS:
            for _lk in LOCKS:
                MENU.append(Exits(stop_pts=_s, tgt_pts=_t, time_stop=_ts, time_gain=None, ladder=LOCK if _lk else None,
                                  ladder_ref_pts=10.0 if _lk else None, sq_off=SQ10, sig_levels=False))
                MLAB.append(f"T{_t}/S{_s}/{('t' + str(_ts)) if _ts else '1510'}{'/L' if _lk else ''}")
NX = len(MENU)
RULES = (StrikeRule(0), StrikeRule(1))
RLAB = ("ATM", "ITM1")
BUCKETS = (9 * 60 + 15, 10 * 60 + 15, 11 * 60 + 15, 12 * 60 + 15, 13 * 60 + 15, 14 * 60 + 15)
BANDS = (0, 100, 200, 400, 1e9)
BLAB = ("<100", "100-200", "200-400", ">=400")


def bucket(minute):
    return np.clip(np.searchsorted(np.array(BUCKETS), np.asarray(minute), side="right") - 1, 0, len(BUCKETS) - 1)


def band(prem):
    return np.clip(np.searchsorted(np.array(BANDS), np.asarray(prem), side="right") - 1, 0, 3)


def exe_h34(exe: Execution) -> Execution:
    return replace(exe, fills=Fills("app"), costs=Costs("dated"))


class _Sub:
    """A slice of a Pack with its matrices materialised once (run_exits asks for them per exit)."""

    def __init__(self, pk, sl):
        self.meta = pk.meta.iloc[sl].reset_index(drop=True)
        self.nleg = pk.nleg
        self._c = {}
        self._pk, self._sl = pk, sl

    def get(self, name, sl):
        if name not in self._c:
            self._c[name] = self._pk.get(name, self._sl)
        return self._c[name]


def run_menu(pk, exits, exe, chunk=6000):
    """{exit index: trades DataFrame} for every exit, each pack slice materialised once."""
    out = {i: [] for i in range(len(exits))}
    for a in range(0, len(pk), chunk):
        sub = _Sub(pk, slice(a, min(a + chunk, len(pk))))
        n = len(sub.meta)
        for i, ex in enumerate(exits):
            out[i].append(run_exits(sub, slice(0, n), ex, exe))
        del sub
    return {i: (pd.concat(v, ignore_index=True) if v else pd.DataFrame()) for i, v in out.items()}


def post(tr: pd.DataFrame) -> pd.DataFrame:
    """Add mid-price gross, the half-spread cost, NET (decision metric) and STRESS (1.5x spread)."""
    if not len(tr):
        for c in ("gross_mid", "spread", "NET", "STRESS", "e_raw"):
            tr[c] = []
        return tr
    stop = (tr.why.values == "stop")
    e_raw = tr.entry.values / 1.0005
    x_raw = tr.exit.values / np.where(stop, 0.999, 0.9995)
    q = tr.qty.values.astype(float)
    hs = tr.und.map(HS).values
    tr["e_raw"] = e_raw
    tr["gross_mid"] = (x_raw - e_raw) * q
    tr["spread"] = hs * (e_raw + x_raw) * q
    tr["NET"] = tr.net.values - tr.spread.values
    tr["STRESS"] = tr.net.values - 1.5 * tr.spread.values
    return tr


def prep_signals(sig: pd.DataFrame, pre=True) -> pd.DataFrame:
    """Menu form of a strategy's signals: drop explicit strikes and multi-day exit columns; pre-holdout days only."""
    s = sig.copy()
    if len(s):
        dd = pd.to_datetime(s.day).dt.date
        s = s[(dd < HOLD) if pre else (dd >= HOLD)]
    for c in ("strike", "exit_day", "exit_min"):
        if c in s.columns:
            s = s.drop(columns=c)
    return s.reset_index(drop=True)


def period(sig: pd.DataFrame, pre=True) -> pd.DataFrame:
    if not len(sig):
        return sig
    dd = pd.to_datetime(sig.day).dt.date
    return sig[(dd < HOLD) if pre else (dd >= HOLD)].reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ extra strategies
def liq_ext_signals(mk, unds=("NIFTY", "SENSEX", "MIDCPNIFTY"), room=1.0):
    sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
    import comps
    return comps.liq_ext_signals(mk, unds=unds, room=room)


H18_EVENTS = os.path.join(C.SCRATCH, "hunt", "h18", "cache", "h18", "break_events_all.parquet")
H18_ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}


def h18_r60_signals(mk, levels="R60"):
    E = pd.read_parquet(H18_EVENTS)
    E = E[E.lvl.isin(["R60H", "R60L"])]
    out = [dict(und=r.und, day=r.day.date(), sig_min=int(555 + r.t), side=int(r.side), book=f"brk_{r.und}",
                idx_stop=float(r.lev - r.side * H18_ISTOP[r.und]), tag=r.lvl) for r in E.itertuples()]
    return pd.DataFrame(out)


def strategies():
    """[(name, Strategy)] in a fixed order: the registry, then liq_ext and h18_r60."""
    from obuy.strategies.registry import all_strategies
    from obuy.strategies.base import Strategy
    from obuy.strategies import liquidity as LQ
    S = list(all_strategies().items())
    S.append(("liq_ext", Strategy(name="liq_ext", family="level-break", signal_fn=liq_ext_signals, sig_grid=[{}],
                                  rules=[StrikeRule(money=1)], exe=Execution(expiry="skip"), exits=[LQ.ARM_EXITS],
                                  window=(9 * 60 + 19, 13 * 60 + 59))))
    S.append(("h18_r60", Strategy(name="h18_r60", family="break", signal_fn=h18_r60_signals, sig_grid=[dict(levels="R60")],
                                  rules=[StrikeRule(money=1)], exits=[LQ.ARM_EXITS], exe=Execution(expiry="skip"),
                                  pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 45, 14 * 60 + 30))))
    return S
