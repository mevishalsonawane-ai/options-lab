"""R167 engine (PREREG sections 1, 3, 6 + Amendment A): R106's held-contract simulator priced for every R167 rule.

A rule acts in one of two ways, both known without looking ahead:
  - a forced exit row fx: the rule's flags (r167lib) at the close of minute m sell at the open of m + 1;
  - a rupee lock (G1 / G2): the premium levels are solved from the trade's real entry fill and charges (R153's
    lock_levels) and passed to the simulator, which raises its stop once the premium's high reaches the trigger.
T versions keep all of B0's exits (so a T trade never exits later than B0's); F versions drop the parts listed in
r167cfg.RULES[rule]['free'].

Each priced trade keeps its simulator settings ('p_*') so a twin can re-run it with a forced row (R162's design).
Callers import r167cfg first (it points r51cfg at the clean stores). 12 Nov 2026.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from numba import njit

import r167cfg as K
import r167lib as L
import r152cfg  # noqa: F401  (paths, R51_CLEAN=1)
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r153_donchian_loseless"))
import r153engine as R153  # noqa: E402  (lock_levels: rupee lock levels from the real fill)


# ================================================================================================ rules -> exits
def exits_for(rule: str, ver: str, b0: E.Exits, item: str | None = None) -> E.Exits:
    """The Exits a rule runs with: B0's for T (and for B0 / giveback rules); F drops what the rule replaces."""
    if rule == "B0" or ver == "T":
        return b0
    free = K.RULES[rule]["free"]
    if free == "item":
        return exits_for(item, "F", b0) if item not in (None, "B0") else b0
    stop = -1.0 if free == "stop+time" else b0.stop
    return E.Exits(stop=stop, target=b0.target, lock_trig=b0.lock_trig, lock_keep=b0.lock_keep,
                   lock_trail=b0.lock_trail, steps=b0.steps, tmax=-1, need=b0.need, sq_row=b0.sq_row)


def fires(rule: str, ver: str, ctx, s: int, ef: int, sq: int, C: np.ndarray, oi, raw: float, extra: dict) -> np.ndarray:
    """(375,) exit flags of a flag rule (not G1 / G2, whose lock is in the simulator)."""
    ratchet = ver == "T"
    if rule == "SR":
        return L.sr_fire(ctx, s, ef, sq, C[3], raw)
    if rule == "VOLX":
        return L.volx_fire(ctx, s, ef, sq, C[3], raw, ratchet)
    if rule == "VOLP":
        return L.volp_fire(C, ef, sq, ratchet)
    if rule == "FLOW":
        a, t = L.flow_fire(ctx, s, ef, sq, C, oi)
        return (a | t) if ver == "F" else a
    if rule == "MOM":
        return L.mom_fire(ctx, s, ef, sq)
    if rule == "G3":
        return L.g3_fire(ctx, s, ef, sq, C[1], extra["x_trig"])
    if rule == "ML":
        mdl = extra["model"]
        rows = list(range(ef + L.MINHOLD, sq))
        if not rows:
            return np.zeros(L.N, bool)
        vm = L.vol_median(C[4])
        Xf = np.array([L.ml_features(ctx, s, ef, m, C, oi, raw, vm) for m in rows])
        return L.ml_fire(mdl.proba(Xf), rows, mdl.theta)
    if rule == "CTX":
        reg = L.regimes(ctx, extra["vix_med"])
        fs = {it: fires(it, "T", ctx, s, ef, sq, C, oi, raw, extra) for it in K.CTX_MENU_T if it != "B0"}
        return L.ctx_fire(fs, reg, extra["choice_T"])
    raise ValueError(rule)


def ctx_f_item(ctx, ef: int, extra: dict) -> str:
    """CTX-F: the F item chosen for the regime at the minute before the fill."""
    reg = L.regimes(ctx, extra["vix_med"])
    return extra["choice_F"].get(int(reg[max(ef - 1, 0)]), "B0")


# ================================================================================================ simulator
@njit(cache=True)
def sim_rows(C, e, sq, fxs, stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, tick, qty, be, fb, fs, fst, tk):
    """Real entry / exit of one contract for each forced exit row in fxs (twins); ok False = no fill."""
    n = fxs.shape[0]
    rE = np.zeros(n)
    rX = np.zeros(n)
    ok = np.zeros(n, np.bool_)
    for i in range(n):
        a, b, c_, d_, r0, r1, r = X.simulate(C, e, sq, fxs[i], stop_f, tgt_f, lk_trig, lk_keep, lk_trail, steps, tmax,
                                             need, -1.0, tick, qty, be, -1.0, -1.0, fb, fs, fst, tk)
        if r != 0:
            rE[i], rX[i], ok[i] = c_, d_, True
    return rE, rX, ok


def _ch(und, D, qty):
    return lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731


def best_net(C, ef: int, xr: int, rE: float, qty: float, ch) -> float | None:
    """Amendment A2: the trade's best open profit, the sale at the highest high from the fill to the exit row, net."""
    seg = C[1][ef:max(xr, ef) + 1]
    seg = seg[np.isfinite(seg)]
    if not len(seg):
        return None
    hx = float(seg.max())
    return float((hx - rE) * qty - ch(rE, hx))


def price(und: str, D, ctx, oi_of, cands: list, rule: str, ver: str, b0: E.Exits, lots: int = 1,
          extra: dict | None = None) -> list:
    """Price one day's candidates (dicts with side, strike, er) under (rule, ver) for `lots` lots. oi_of(side, strike)
    -> (375,) OI or None. Trade dicts (None = no fill) with R106's fields plus 'lots', 'best_net', 'item' and the
    simulator settings 'p_*'."""
    extra = extra or {}
    st = E.stack(und, D)
    qty = float(K51.lot(und, D.d)) * lots
    ch = _ch(und, D, qty)
    out = []
    for c in cands:
        k = c["strike"]
        ci = st.idx(c["side"], k)
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        item = None
        if rule == "CTX" and ver == "F":
            # the regime needs the fill row: price the entry with B0's settings first (same fill whatever the exits)
            sq0 = E.sq_row(D, b0)
            _, _, ef0, _ = X.entry_info(C, c["er"], sq0, qty)
            item = ctx_f_item(ctx, ef0, extra) if ef0 >= 0 else "B0"
            ex = exits_for("CTX", "F", b0, item)
        else:
            ex = exits_for(rule, ver, b0)
        sq = E.sq_row(D, ex)
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
        s = 1 if c["side"] == "C" else -1
        lkt, lkk, lktr, fx = ex.lock_trig, ex.lock_keep, ex.lock_trail, -1
        if rule in ("G1", "G2"):
            keep = L.GB_KEEP_G1 if rule == "G1" else L.GB_KEEP_G2
            lkt, lkk = R153.lock_levels(raw, real, qty, ch, L.GB_TRIG, keep)
            lktr = L.GB_TRAIL_G2 if rule == "G2" else 0.0
        elif rule == "G3":
            x_trig = X.target_price(real, qty, L.GB_TRIG, ch)
            fx = L.exit_row(fires("G3", "T", ctx, s, ef, sq, C, None, raw, dict(x_trig=x_trig)), ef, sq)
        elif rule not in ("B0",):
            oi = oi_of(c["side"], k) if oi_of is not None else None
            r_eff, v_eff = (item, "F") if item is not None else (rule, ver)
            if r_eff != "B0":
                fx = L.exit_row(fires(r_eff, v_eff, ctx, s, ef, sq, C, oi, raw, extra), ef, sq)
        steps = ex.steps_arr()
        a, b, rE, rX, r0, r1, r = X.simulate(C, int(c["er"]), int(sq), int(fx), ex.stop, ex.target, lkt, lkk, lktr, steps,
                                             ex.tmax, ex.need, ex.close_stop, K51.TICK, qty, be, -1.0, -1.0, *E.SLIP)
        if r == E.R_NOFILL:
            out.append(None)
            continue
        net = (rX - rE) * qty - ch(rE, rX)
        out.append(dict(und=und, day=D.d, side=c["side"], strike=float(k), er=int(r0), xr=int(r1), E=float(a), X=float(b),
                        rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=E.REASONS[int(r)], net=float(net),
                        best_net=best_net(C, int(r0), int(r1), float(rE), qty, ch), item=item, p_stop=ex.stop,
                        p_steps=tuple(ex.steps), p_tmax=ex.tmax, p_need=ex.need, p_lkt=lkt, p_lkk=lkk, p_lktr=lktr,
                        p_be=be, p_fx=fx, p_tgt=ex.target))
    return out


# ================================================================================================ twins
def forced_nets(und: str, D, t: dict, rows: np.ndarray, settings: dict, qty: float) -> tuple:
    """t's contract bought at its signal entry row and sold at the open of each row in `rows` (or earlier by the
    settings' own exits)."""
    st = E.stack(und, D)
    C = st.S[st.idx(t["side"], t["strike"])]
    sq = E.sq_row(D, E.Exits(sq_row=K51.row(15, 15)))      # every R167 rule squares off at 15:15 (XL)
    rows = np.asarray(rows, np.int64)
    fx0 = int(settings.get("p_fx", -1))
    fxs = rows if fx0 < 0 else np.minimum(rows, fx0)
    steps = np.array(settings["p_steps"], dtype=np.float64).reshape(-1, 2) if settings["p_steps"] else E.NOLOCK
    return sim_rows(C, int(t["er_sig"]), int(sq), fxs, float(settings["p_stop"]), float(settings.get("p_tgt", -1.0)),
                    float(settings["p_lkt"]), float(settings["p_lkk"]), float(settings.get("p_lktr", 0.0)), steps,
                    int(settings["p_tmax"]), float(settings["p_need"]), K51.TICK, qty, float(settings["p_be"]), *E.SLIP)


def twin_nets(und: str, D, t: dict, b0t: dict, n: int, rng) -> np.ndarray | None:
    """PREREG section 6 for one rule trade t and the same candidate priced by B0 at the same lots b0t: n twin nets, or
    None when the twin is the trade itself."""
    w = L.twin_window(int(t["er"]), int(t["xr"]), int(b0t["xr"]))
    if w is None:
        return None
    lo, hi, which = w
    rows = rng.integers(lo, hi + 1, size=n)
    s = b0t if which == "b0" else t
    rE, rX, ok = forced_nets(und, D, t, rows, s, t["qty"])
    net = (rX - rE) * t["qty"] - E.charges(und, rE, rX, t["qty"], D.d)
    return np.where(ok, net, t["net"])
