"""R162 engine (PREREG section 2): R106's held-contract simulator (r106engine.simulate, R64's line for line with no rupee
level) priced for every R162 rule at N lots.

Every level is known at the fill: percentages of the raw fill, the breakeven premium solved from the real entry fill
and its charges (R106's stop_price with a Rs 0 loss), the index stop from the five index minutes BEFORE the entry row,
the strike from the minute before the entry. Nothing looks ahead (test_r162.py checks the walls on synthetic days).

Each priced trade keeps its simulator settings ('p_*') so a twin can re-run it with a forced exit row.
Callers import r162cfg first (it points r51cfg at the clean stores). 12 Nov 2026.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r162cfg as K
import r162lib as L
import r152cfg  # noqa: F401  (paths, R51_CLEAN=1)
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G


# ================================================================================================ rules -> exits
def exits_for(rule: str, b0: E.Exits) -> E.Exits:
    """The Exits of a rule (B0's with the rule's change); 'B0', strike, scale-out and breakeven rules keep B0's."""
    if rule == "B0":
        return b0
    s = K.RULES[rule]
    if s["kind"] == "steps":
        return E.Exits(stop=b0.stop, target=b0.target, steps=tuple(s["add"]) + tuple(b0.steps), tmax=b0.tmax,
                       need=b0.need, sq_row=b0.sq_row)
    if s["kind"] == "time":
        return E.Exits(stop=b0.stop, target=b0.target, steps=b0.steps, tmax=s["tmax"], need=s["need"], sq_row=b0.sq_row)
    if s["kind"] == "ix" and not s["keep_prem"]:
        return E.Exits(stop=-1.0, target=b0.target, steps=b0.steps, tmax=b0.tmax, need=b0.need, sq_row=b0.sq_row)
    return b0


# ================================================================================================ strike choice
_FLAGGED: dict = {}


def flagged_expiries(und: str, days: dict) -> list:
    if und not in _FLAGGED:
        _FLAGGED[und] = sorted(d for d, D in days.items() if D.expiry)
    return _FLAGGED[und]


def strike_of(rule: str, und: str, D, side: str, er: int, days: dict | None = None, money: int | None = None):
    """The strike the rule buys (None = no strike): B0 and the exit rules 1-ITM; ATM / ITM2 by steps; D60 / D70 by
    Black-Scholes delta from each strike's close at the minute before the entry (fallback 1-ITM)."""
    if money is not None:
        return E.strike_for(D, und, side, er - 1, money)
    s = K.RULES.get(rule, {})
    if s.get("kind") != "strike":
        return E.strike_for(D, und, side, er - 1, 1)
    if "money" in s:
        return E.strike_for(D, und, side, er - 1, s["money"])
    key = (rule, und, D.d, side, int(er))
    if key not in _DSTRIKE:
        _DSTRIKE[key] = _delta_strike(s["delta"], und, D, side, er, days)
    return _DSTRIKE[key]


_DSTRIKE: dict = {}


def _delta_strike(target: float, und: str, D, side: str, er: int, days: dict | None):
    r = er - 1
    spot = float(D.idx[3, r]) if 0 <= r < K51.NROWS else float("nan")
    exp = L.next_expiry(D.d, flagged_expiries(und, days)) if days is not None else None
    if spot == spot and exp is not None:
        t = L.years_to_expiry(D.d, r, exp)
        quotes = {}
        for (sd, k), c in D.con.items():
            if sd == side and 0 <= r < c.shape[1]:
                px = float(c[3, r])
                if px == px and px > 0:
                    quotes[k] = px
        k = L.pick_delta_strike(side, spot, t, K.RATE, quotes, target)
        if k is not None:
            return k
    return E.strike_for(D, und, side, er - 1, 1)


# ================================================================================================ simulator
@njit(cache=True)
def sim_rows(C, e, sq, fxs, stop_f, tgt_f, lk_trig, lk_keep, steps, tmax, need, tick, qty, be, fb, fs, fst, tk):
    """Real entry / exit of one contract for each forced exit row in fxs (twins); ok False = no fill."""
    n = fxs.shape[0]
    rE = np.zeros(n)
    rX = np.zeros(n)
    ok = np.zeros(n, np.bool_)
    for i in range(n):
        a, b, c_, d_, r0, r1, r = X.simulate(C, e, sq, fxs[i], stop_f, tgt_f, lk_trig, lk_keep, 0.0, steps, tmax, need,
                                             -1.0, tick, qty, be, -1.0, -1.0, fb, fs, fst, tk)
        if r != 0:
            rE[i], rX[i], ok[i] = c_, d_, True
    return rE, rX, ok


def _one(C, er, sq, fx, ex: E.Exits, qty, be, lkt=-1.0, lkk=0.0, tgt_f=None):
    return X.simulate(C, int(er), int(sq), int(fx), ex.stop, ex.target if tgt_f is None else tgt_f, lkt, lkk, 0.0,
                      ex.steps_arr(), ex.tmax, ex.need, -1.0, K51.TICK, qty, be, -1.0, -1.0, *E.SLIP)


def _ch(und, D, qty):
    return lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731


def _side_charge(und, D, buy: bool):
    dn = np.array([D.d.toordinal() - E.EPOCH])
    bse = und in K51.BSE
    return lambda px, q: float(E.COSTS.charge(buy, np.array([px]), np.array([q]), dn, bse)[0])  # noqa: E731


def price(und: str, D, cands: list, rule: str, b0: E.Exits, lots: int = 1, days: dict | None = None,
          money: int | None = None) -> list:
    """Price one day's candidates (dicts with side, er[, strike]) under `rule` ('B0' or an R162 rule) for `lots` lots.
    Trade dicts (None = no fill) with R106's fields plus 'lots', the simulator settings 'p_*' and, for scale-out,
    'rounds' (each exit order's own net) and 'xr1' (the scaled lot's exit row)."""
    st = E.stack(und, D)
    lot = float(K51.lot(und, D.d))
    qty = lot * lots
    ex = exits_for(rule, b0)
    sq = E.sq_row(D, ex)
    spec = K.RULES.get(rule, {"kind": "b0"})
    kind = spec["kind"]
    ch = _ch(und, D, qty)
    out = []
    for c in cands:
        k = strike_of(rule, und, D, c["side"], c["er"], days, money)
        if kind not in ("strike",) and money is None and c.get("strike") is not None:
            k = c["strike"]                      # exit rules buy the candidate's own (1-ITM) strike
        ci = st.idx(c["side"], k)
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
        lkt, lkk, fx = -1.0, 0.0, -1
        if kind == "be":
            s0 = X.stop_price(real, qty, 0.0, ch, tks, E.SLIP[2], E.SLIP[3])
            lkt, lkk = spec["x"] * raw * qty * (1 - 1e-12), (s0 - raw) * qty
        if kind == "ix":
            lvl = L.index_level(D.idx[2], D.idx[1], int(c["er"]), c["side"])
            fx = L.index_exit_row(D.idx[3], ef, lvl, c["side"], sq)
        base = dict(und=und, day=D.d, side=c["side"], strike=float(k), lots=lots, qty=qty, p_stop=ex.stop,
                    p_steps=tuple(ex.steps), p_tmax=ex.tmax, p_need=ex.need, p_lkt=lkt, p_lkk=lkk, p_be=be, p_fx=fx)
        if kind == "so":
            q1, q2 = lot, lot * (lots - 1)
            a1, b1, _, rX1, r0, x1, w1 = _one(C, c["er"], sq, -1, ex, q1, real + _ch(und, D, q1)(real, real) / q1
                                              if G.needs_be(ex) else 0.0, tgt_f=spec["x"])
            a2, b2, _, rX2, _, x2, w2 = _one(C, c["er"], sq, -1, ex, q2, real + _ch(und, D, q2)(real, real) / q2
                                             if G.needs_be(ex) else 0.0)
            if w1 == E.R_NOFILL or w2 == E.R_NOFILL:
                out.append(None)
                continue
            cb, cs = _side_charge(und, D, True), _side_charge(und, D, False)
            net = L.so_net(real, [(rX1, q1), (rX2, q2)], cb, cs)
            buy = cb(real, qty)
            rounds = [(rX1 - real) * q1 - cs(rX1, q1) - buy * q1 / qty, (rX2 - real) * q2 - cs(rX2, q2) - buy * q2 / qty]
            out.append(dict(base, er=int(r0), xr=int(max(x1, x2)), xr1=int(x1), E=float(a1), X=float(b1), rE=float(real),
                            rX=float(rX1), rX2=float(rX2), q1=q1, q2=q2, why=f"so:{E.REASONS[int(w1)]}/{E.REASONS[int(w2)]}",
                            net=float(net), rounds=[float(x) for x in rounds], p_tgt=spec["x"]))
            continue
        a, b, rE, rX, r0, r1, r = _one(C, c["er"], sq, fx, ex, qty, be, lkt, lkk)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(base, er=int(r0), xr=int(r1), E=float(a), X=float(b), rE=float(rE), rX=float(rX),
                        why=E.REASONS[int(r)], net=float(net), p_tgt=-1.0))
    return out


# ================================================================================================ twins
def forced_nets(und: str, D, t: dict, rows: np.ndarray, settings: dict, qty: float) -> np.ndarray:
    """Net of `t`'s contract bought at its signal entry row, sold at the open of each row in `rows` (or earlier by the
    settings' own exits: p_stop, p_steps, p_tmax, p_need, p_lkt, p_lkk, p_be, p_fx, p_tgt)."""
    st = E.stack(und, D)
    C = st.S[st.idx(t["side"], t["strike"])]
    sq = E.sq_row(D, E.Exits(sq_row=K51.row(15, 15)))      # every R162 rule squares off at 15:15 (XL)
    rows = np.asarray(rows, np.int64)
    fx0 = int(settings.get("p_fx", -1))
    fxs = rows if fx0 < 0 else np.minimum(rows, fx0)
    steps = np.array(settings["p_steps"], dtype=np.float64).reshape(-1, 2) if settings["p_steps"] else E.NOLOCK
    rE, rX, ok = sim_rows(C, int(t["er_sig"]), int(sq), fxs, float(settings["p_stop"]), float(settings.get("p_tgt", -1.0)),
                          float(settings["p_lkt"]), float(settings["p_lkk"]), steps, int(settings["p_tmax"]),
                          float(settings["p_need"]), K51.TICK, qty, float(settings["p_be"]), *E.SLIP)
    return rE, rX, ok


def twin_nets(und: str, D, t: dict, b0t: dict, n: int, rng) -> np.ndarray | None:
    """PREREG section 5 for one rule trade `t` and the same candidate priced by B0 at the same lots `b0t`: n twin nets,
    or None when the twin is the trade itself (same exit or empty window). Scale-out twins only the scaled lot."""
    so = "xr1" in t
    xr = t["xr1"] if so else t["xr"]
    w = L.twin_window(int(t["er"]), int(xr), int(b0t["xr"]))
    if w is None:
        return None
    lo, hi, which = w
    rows = rng.integers(lo, hi + 1, size=n)
    if so:
        if which == "rule":                      # a target never exits later than B0's own exits
            return None
        q1, q2 = t["q1"], t["q2"]
        s = dict(b0t, p_be=(t["rE"] + _ch(und, D, q1)(t["rE"], t["rE"]) / q1) if b0t["p_be"] else 0.0)
        _, rX1, ok = forced_nets(und, D, t, rows, s, q1)
        cb, cs = _side_charge(und, D, True), _side_charge(und, D, False)
        dn = np.full(n, D.d.toordinal() - E.EPOCH)
        bse = und in K51.BSE
        sell1 = E.COSTS.charge(False, rX1, np.full(n, q1), dn, bse)
        net = ((rX1 - t["rE"]) * q1 + (t["rX2"] - t["rE"]) * q2 - cb(t["rE"], q1 + q2) - sell1 - cs(t["rX2"], q2))
        return np.where(ok, net, t["net"])
    s = b0t if which == "b0" else t
    rE, rX, ok = forced_nets(und, D, t, rows, s, t["qty"])
    net = (rX - rE) * t["qty"] - E.charges(und, rE, rX, t["qty"], D.d)
    return np.where(ok, net, t["net"])
