"""R101 candidates (PREREG section 1): each arm's B0 candidate signals, priced ONCE on R64's engine with the arm's own
exits, with R101's features on every candidate. B0 and every variant then walk the books from these results, so a
removed signal frees its book exactly as the app would (R80's method).

A "unit" is one (index, day[, book]) with its candidates in time order, their priced results (None = no fill) and the
arm's taking rule: 'book' (one position per book: Liquidity), 'side2' (one open a side, at most 2 fills a side a day:
Donchian / Breadth Donchian) or 'one' (one a day: OOV).
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date

import numpy as np

import r101cfg as K
import r101feat as F

_M: dict = {}


def mods():
    """R51's modules on the clean stores, R64's engine and arms, R71/R72's rules and the Liquidity family."""
    if not _M:
        import r51cfg as R51
        import common as C
        import engine as E
        import fam_liq
        import fam_r44
        import r51data
        import r64arms as A
        import r64cfg as R64
        import r64engine as G
        import r66cfg as R66
        import r71cfg as R71
        import r71study as S71
        import r72rules as P72
        assert R51.CLEAN, "R101 prices on R51's Amendment A clean stores"
        fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", K.LIQ_IDX_STOP_MIDCP)
        _M.update(R51=R51, C=C, E=E, fam_liq=fam_liq, fam_r44=fam_r44, r51data=r51data, A=A, R64=R64, G=G, R66=R66,
                  R71=R71, S71=S71, P72=P72)
    return _M


def _xl():
    """Donchian / Breadth Donchian exits XL (ladder + L25, no target, 30 min, 15:15), r71cfg.EXITS['XL']."""
    M = mods()
    return M["S71"].exits_of("XL")


def _oov_l25():
    """OOV with R64 A4 + R66 L25 (the app's exits since 16 Oct)."""
    M = mods()
    spec = M["R66"].exit_spec(M["R64"].OWN["OOV"], "L25")
    E, R51 = M["E"], M["R51"]
    return E.Exits(stop=spec["stop"] if spec["stop"] is not None else -1.0,
                   target=spec["target"] if spec["target"] is not None else -1.0,
                   steps=tuple(spec["steps"]), tmax=spec["tmax"] if spec["tmax"] else -1, sq_row=R51.row(*spec["sq"]))


# ------------------------------------------------------------------------------------------------ shared features
_FEAT: dict = {}


def feats():
    """The day-level feature sources, loaded once: VIX minutes, daily frames, the five indices' bar colours."""
    if not _FEAT:
        M = mods()
        _FEAT["vix_min"] = F.vix_minutes()
        d = F.daily()
        _FEAT["daily"] = d
        _FEAT["colours"] = F.bar_colours({u: M["fam_r44"].bars5(u) for u in K.UNDS})
    return _FEAT


def day_feats(und: str, d: date) -> dict:
    fx = feats()
    dd = fx["daily"]
    return dict(f_vixreg=F.vix_regime(dd["INDIAVIX"], d), f_rv=F.rv_rank(dd[und], d), f_gap=F.gap_pct(dd[und], d),
                f_dow=d.weekday())


def sig_feats(und: str, d: date, er: int, side: str) -> dict:
    fx = feats()
    return dict(f_breadth=F.breadth_agree(fx["colours"], K.UNDS, d, er, side),
                f_vixchg=F.vix_change(fx["vix_min"].get(d), er))


# ------------------------------------------------------------------------------------------------ Liquidity 15+5
def liq_units(lo: date, hi: date) -> list:
    """[(key, cands, res, 'book')] for every tradable (index, day, book); each candidate simulated once with fam_liq's
    exits and forced index-exit row (as R80.candidates)."""
    M = mods()
    C, E, fam_liq, r51data = M["C"], M["E"], M["fam_liq"], M["r51data"]
    out = []
    for und, books in K.LIQ_BOOKS.items():
        days = r51data.load(und)
        for tf in books:
            sig = fam_liq.signals(und, tf, 1.0)
            for d in C.days_in(und, lo, hi):
                if d not in sig or not sig[d]:
                    continue
                D = days[d]
                df = day_feats(und, d)
                cands = []
                for s in sig[d]:
                    c = dict(s, fx=fam_liq.forced_row(D, s), book=tf, und=und)
                    c.update(df); c.update(sig_feats(und, d, c["er"], c["side"]))
                    cands.append(c)
                res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands],
                                [c["er"] for c in cands], fam_liq.EX, [c["fx"] for c in cands])
                out.append(((und, d, tf), cands, res, "book"))
    return out


def liq_reference(lo: date, hi: date) -> list:
    """fam_liq.make(und, books).run over the three indices: the published B0 path (R76 / R81 / R100)."""
    M = mods()
    rows = []
    for und, books in K.LIQ_BOOKS.items():
        rows += M["fam_liq"].make(und, books).run(lo, hi).to_dict("records")
    return rows


# ------------------------------------------------------------------------------------------------ Donchian family
def _side2_units(sig_by_und: dict, lo: date, hi: date, ex, extra=None) -> list:
    """[(key, cands, res, 'side2')] per (index, day): signals (er, side[, more]) in 09:20-15:10, 1-ITM from the minute
    before the entry, never on the index's expiry day; all priced once with `ex`."""
    M = mods()
    C, E, G, r51data = M["C"], M["E"], M["G"], M["r51data"]
    first, last = K.OPEN_ROW, K.row(15, 10)
    out = []
    for und in K.UNDS:
        sig = sig_by_und.get(und, {})
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            if D.expiry or d not in sig:
                continue
            df = day_feats(und, d)
            cands = []
            for row in sorted(sig[d]):
                er, side = row[0], row[1]
                if er < first or er > last:
                    continue
                k = E.strike_for(D, und, side, er - 1, 1)
                if k is None:
                    continue
                c = dict(er=er, side=side, strike=k, und=und)
                if len(row) > 2:
                    c.update(row[2])
                c.update(df); c.update(sig_feats(und, d, er, side))
                cands.append(c)
            if not cands:
                continue
            res = G.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands], [c["er"] for c in cands], ex)
            out.append(((und, d, None), cands, res, "side2"))
    return out


def r72_signals() -> dict:
    """{und: {day: [(er, side, {'f_count': 4|5})]}} from R72's breadth breakout (M = 4), with the breadth count."""
    M = mods()
    P72, fam_r44 = M["P72"], M["fam_r44"]
    panel = P72.Panel({u: fam_r44.bars5(u) for u in K.UNDS})
    four = P72.breadth_breakout(panel, 4)
    five = P72.breadth_breakout(panel, 5)
    out = {}
    for u in K.UNDS:
        f5 = {(i, s) for i, s, _ in five[u]}
        d, b = panel.f[u]["d"], panel.f[u]["b"]
        per = defaultdict(list)
        for i, s, _ in four[u]:
            per[d[i]].append((5 * (int(b[i]) + 1), "C" if s > 0 else "P", {"f_count": 5 if (i, s) in f5 else 4}))
        out[u] = {k: sorted(v, key=lambda x: (x[0], x[1])) for k, v in per.items()}
    return out


def r51_signals() -> dict:
    """{und: {day: [(er, side)]}} from fam_r44's Donchian-20 signal list (R51 / R64 / R66)."""
    M = mods()
    out = {}
    for u in K.UNDS:
        sig = M["fam_r44"].signal_list(u, "donchian20")
        out[u] = {d: [(5 * (b + 1), side) for b, side in v] for d, v in sig.items()}
    return out


def r72_units(lo: date, hi: date) -> list:
    return _side2_units(r72_signals(), lo, hi, _xl())


def r51_units(lo: date, hi: date) -> list:
    return _side2_units(r51_signals(), lo, hi, _xl())


# ------------------------------------------------------------------------------------------------ OOV
def r52_units(lo: date, hi: date) -> list:
    """[(key, cands, res, 'one')]: R64's OOV candidate (one a day per index) with FAR and OPEN3 features."""
    M = mods()
    C, E, G, A, R51, r51data = M["C"], M["E"], M["G"], M["A"], M["R51"], M["r51data"]
    ex = _oov_l25()
    sides, levs, opens = {}, {}, {}
    for und in K.UNDS:
        days = r51data.load(und)
        lev = A.prev_value_area(days, R51.ROOT)
        for d in C.days_in(und, lo, hi):
            D = days[d]
            side = None if D.expiry else A.oov_side(D.idx, lev.get(d))
            ok = np.flatnonzero(np.isfinite(D.idx[0, :5]))
            sides[(und, d)] = side
            levs[(und, d)] = lev.get(d)
            opens[(und, d)] = float(D.idx[0, ok[0]]) if len(ok) else float("nan")
    out = []
    for und in K.UNDS:
        days = r51data.load(und)
        for d in C.days_in(und, lo, hi):
            side = sides[(und, d)]
            if side is None:
                continue
            D = days[d]
            k = E.strike_for(D, und, side, 4, 1)
            if k is None:
                continue
            c = dict(er=5, side=side, strike=k, und=und, f_far=F.oov_far(levs[(und, d)], opens[(und, d)], side),
                     f_open3=sum(1 for u in K.UNDS if sides.get((u, d)) == side))
            c.update(day_feats(und, d)); c.update(sig_feats(und, d, 5, side))
            res = G.run_day(und, D, [side], [k], [5], ex)
            out.append(((und, d, None), [c], res, "one"))
    return out


UNITS = {"LIQ": liq_units, "R72": r72_units, "R51": r51_units, "R52": r52_units}
