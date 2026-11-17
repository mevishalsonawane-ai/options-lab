"""R186 engine, copied unchanged into R187 from claude/r186-fixed-profit (91f895e49; that branch is not on main).

R186 engine (R186 PREREG.md): R176's held-contract simulator as R179 priced its base (the arm's own exits + R162's breakeven
lock), plus two options that are off by default:

    tgt_abs  a resting limit sell (premium) that nets +T a lot after charges (r106engine.target_price); filled at the
             target when a minute's high reaches it, at the minute's open when the minute opens above it (a gap through)
    rl_trig / rl_lock  a net-rupee step lock (L500 / L-BE1000): once the minute high reaches rl_trig[k], the stop rises to
             rl_lock[k] from the next minute; it only moves up and is sold as a stop (R17 stop fill)

`simulate` is r176engine.simulate_step line for line with no rupee stop and no step trail (step_amt = stop_abs = -1, the
base R179 priced), plus the two options and an eighth return: the highest premium seen before the exit (the simulator's
own `peak`). With both off it returns what simulate_step returns (test_r186.py). Callers import r176cfg first.
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r176cfg as K  # noqa: F401  (stores and paths)
import r106engine as X
import engine as E                      # hunt/r51/engine.py
import r51cfg as K51
import r64engine as G

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5
R_TGAP = 10                             # the target, filled at the open of a minute that opened above it
R_RLOCK = 11                            # the step lock (L500 / L-BE1000 / P60 / SPLIT's breakeven)
R_IDX = 12                              # Amendment B's index lock (IDX)
REASONS = {**E.REASONS, R_TGAP: "target_gap", R_RLOCK: "step_lock", R_IDX: "index_lock"}
NONE = np.zeros(0)
BE_X = 0.10                             # R162's breakeven lock at +10% (the base)


@njit(cache=True)
def simulate(C, e, sq, fx, stop_f, lk_trig, lk_keep, lk_trail, steps, tmax, need, cstop, tick, qty, be, tgt_abs,
             rl_trig, rl_lock, ix, ix_lvl, ix_trig, ix_sgn, fb=1.0005, fs=0.9995, fst=0.999, tk=1.0, stop_abs=-1.0):
    """Returns (raw entry, exit price, real entry, real exit, fill row, exit row, reason, peak).
    Amendment B's index lock (IDX): ix = the index's 1-minute closes, ix_lvl = the index open of the fill minute (ix_trig
    <= 0: off); once the minute high has reached ix_trig, a 1-minute index close back through ix_lvl (ix_sgn +1 call:
    below it; -1 put: above it) sells at the next minute's open as a market sale (R17 stop fill, reason 12).
    Amendment C: stop_abs > 0 replaces the fractional entry stop with that premium (as r176engine.simulate_step; the
    caller passes it only where it is tighter than -15%); it is a stop order, so a minute opening below it fills at the
    open."""
    o, h, l, c, v = C[0], C[1], C[2], C[3], C[4]
    nan = np.nan
    n = o.shape[0]
    ef = -1
    for j in range(e, min(e + 3, sq)):
        if o[j] == o[j] and o[j] > 0:
            ef = j
            break
    if ef < 0:
        return nan, nan, nan, nan, -1, -1, 0, nan
    E_ = float(o[ef])
    real_e = _r2(E_ * fb) + tk * _ticks(_vol5(v, ef - 1, qty)) * tick
    stop = E_ * (1.0 - stop_f) if stop_f >= 0 else -1.0
    if stop_abs > 0:
        stop = stop_abs
    tgt = tgt_abs if tgt_abs > 0 else 1e18
    lock = -1.0
    rlock = -1.0
    nl = rl_trig.shape[0]
    k = 0
    peak = E_
    last = E_
    tend = ef + tmax - 1 if tmax > 0 else 10 ** 6
    end = sq
    if fx >= 0 and fx < end:
        end = fx
    m = ef
    pend_cstop = False
    pend_ix = False
    ix_on = ix_trig > 0 and ix.shape[0] > ef and ix_lvl == ix_lvl
    while m < end:
        if pend_cstop or pend_ix:
            if o[m] == o[m]:
                real_x = max(_r2(o[m] * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, float(o[m]), real_e, real_x, ef, m, (8 if pend_cstop else 12), peak
        if not (l[m] == l[m]):
            if m == tend:
                break
            m += 1
            continue
        if o[m] == o[m] and o[m] >= tgt:                       # opened above the resting limit: filled at the open
            return E_, float(o[m]), real_e, float(o[m]), ef, m, 10, peak
        lvl = stop if stop >= lock else lock
        rs = 2 if lock >= stop and lock > 0 else 1
        if rlock > lvl:
            lvl = rlock
            rs = 11
        if lvl > 0 and l[m] <= lvl:
            px = o[m] if (o[m] == o[m] and o[m] < lvl) else lvl
            real_x = max(_r2(px * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, px, real_e, real_x, ef, m, rs, peak
        if h[m] >= tgt:
            return E_, tgt, real_e, tgt, ef, m, 3, peak
        last = c[m]
        if h[m] > peak:
            peak = h[m]
        while k < nl and peak >= rl_trig[k]:                   # the step lock: acts from the next minute
            if rl_lock[k] > rlock:
                rlock = rl_lock[k]
            k += 1
        if lk_trig > 0 and (peak - E_) * qty >= lk_trig:
            lv = E_ + lk_keep / qty
            if lk_trail > 0:
                lv = max(lv, peak - lk_trail / qty)
            if lv > lock:
                lock = lv
        if steps.shape[0] > 0:
            g = peak / E_ - 1.0
            for j in range(steps.shape[0]):
                if g >= steps[j, 0] - 1e-12:
                    lv = E_ * (1.0 + steps[j, 1]) if steps[j, 1] == steps[j, 1] else be
                    if lv > lock:
                        lock = lv
        if cstop >= 0 and c[m] <= E_ * (1.0 - cstop):
            pend_cstop = True
        if ix_on and peak >= ix_trig and m < ix.shape[0]:
            if ix[m] == ix[m] and ix_sgn * (ix[m] - ix_lvl) < 0:
                pend_ix = True
        if m == tend:
            if need < 0 or c[m] < E_ * (1.0 + need):
                real_x = max(_r2(c[m] * fs) - tk * _ticks(_vol5(v, m, qty)) * tick, 0.0)
                return E_, float(c[m]), real_e, real_x, ef, m, 4, peak
        m += 1
    if m == tend and m < end:
        real_x = max(_r2(last * fs) - tk * _ticks(_vol5(v, m, qty)) * tick, 0.0)
        return E_, last, real_e, real_x, ef, m, 7, peak
    xr = end
    rs = 6 if (fx >= 0 and fx < sq) else 5
    px = o[xr] if (xr < n and o[xr] == o[xr]) else nan
    if not (px == px):
        for j in range(xr + 1, min(xr + 4, n)):
            if o[j] == o[j]:
                px = o[j]
                break
    if not (px == px):
        px = last
        rs = 7
    real_x = max(_r2(px * fs) - tk * _ticks(_vol5(v, xr - 1, qty)) * tick, 0.0)
    return E_, px, real_e, real_x, ef, xr, rs, peak


# ------------------------------------------------------------------------------------------------ levels (pure)
LADDER = {"L500": (500.0, None), "LBE": (0.0, None), "P60": (50.0, 0.60)}   # mode -> (step a lot, kept share)


def lock_levels(mode: str, real: float, qty: float, lots: int, ch, tks: float, hmax: float = np.inf) -> tuple:
    """(rl_trig, rl_lock) premium arrays. mode 'L500': trigger +1,000 + 500k a lot net, lock +500 + 500k a lot net;
    'LBE': one level, trigger +1,000 a lot, lock net Rs 0; 'P60' (Amendment B): trigger +1,000 + 50k a lot net, lock 60% of
    the trigger; '' / None: none. Triggers come from r106engine.target_price (net after charges at that premium, rounded
    up), locks from r106engine.stop_price with a negative loss (the stop sale nets the lock after the R17 stop slippage
    and charges, rounded up). Levels whose trigger is above `hmax` (the highest premium the contract trades at after the
    fill) can never act and are not built."""
    if not mode:
        return NONE, NONE
    step, share = LADDER[mode]
    tr, lk = [], []
    k = 0
    while True:
        t_amt = (1000.0 + step * k) * lots
        x = X.target_price(real, qty, t_amt, ch)
        if x > hmax and k > 0:
            break
        if mode == "LBE":
            keep = 0.0
        elif share is not None:
            keep = share * t_amt
        else:
            keep = t_amt - 500.0 * lots
        tr.append(x)
        lk.append(X.stop_price(real, qty, -keep, ch, tks, E.SLIP[2], E.SLIP[3]))
        k += 1
        if mode == "LBE" or k >= 4000:
            break
    return np.asarray(tr, float), np.asarray(lk, float)


def cap_stop_px(raw: float, real: float, qty: float, lots: int, cap_net: float, stop_f: float, ch, tks: float) -> float:
    """Amendment C (R179 Amendment A's M1): the premium whose stop sale nets -cap_net x lots after charges and the expected
    R17 stop slippage (r106engine.stop_price), or -1 where the arm's own fractional stop (raw x (1 - stop_f)) is already
    at or above it (-15% loses less, so -15% stays)."""
    s = X.stop_price(real, qty, cap_net * lots, ch, tks, E.SLIP[2], E.SLIP[3])
    own = raw * (1.0 - stop_f) if stop_f >= 0 else -1.0
    return float(s) if s > 0 and s > own else -1.0


def best_net(peak: float, rE: float, qty: float, ch) -> float:
    """The best open P&L net (PREREG 7a): (peak - real entry) x qty - charges(entry, peak)."""
    return float((peak - rE) * qty - ch(rE, peak))


def _charges_fn(und: str, qty: float, d):
    return lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, d)[0])  # noqa: E731


def price(und: str, D, cands: list, ex: E.Exits, lots: int = 1, tgt_net: float = -1.0, lock: str = "",
          be_x: float = BE_X, idx: bool = False, split: bool = False, cap_net: float = -1.0) -> list:
    """Price one day's candidates as R179's base (the arm's own exits + the breakeven lock at +be_x), with a net target of
    tgt_net a lot (> 0) and / or a lock ('L500' / 'LBE' / 'P60'), or (Amendment B) the index lock (idx=True) or the split
    exit (split=True, lots >= 2: one lot with a +Rs 1,000 net target, the rest moved to net breakeven when it fills).
    Trade dicts as r176engine.price plus 'peak', 'best' (best open P&L net), 'tgt_px' (and 'split_hit').
    Amendment C: cap_net > 0 = an entry stop at the premium whose stop sale nets -cap_net a lot (x lots) after charges and
    the expected stop slippage (cap_stop_px, R179 Amendment A's M1), only where tighter than the arm's own -15%; its exits
    are labelled 'cap' (stop_kind 'rupee', stop_px its premium)."""
    st = E.stack(und, D)
    lot1 = float(K51.lot(und, D.d))
    qty = lot1 * lots
    sq = E.sq_row(D, ex)
    stp = ex.steps_arr()
    out = []
    ch = _charges_fn(und, qty, D.d)
    ixc = np.ascontiguousarray(D.idx[3], dtype=float) if idx else NONE
    for c in cands:
        fx = c.get("fx", -1)
        fx = int(fx if fx is not None else -1)
        ci = st.idx(c["side"], c["strike"])
        if ci < 0:
            out.append(None)
            continue
        C = st.S[ci]
        raw, real, ef, tks = X.entry_info(C, c["er"], sq, qty)
        if ef < 0:
            out.append(None)
            continue
        hseg = C[1][ef:sq]
        hmax = float(np.nanmax(hseg)) if np.isfinite(hseg).any() else 0.0

        def base_lock(q_leg, ch_leg):
            be_ = real + ch_leg(real, real) / q_leg if G.needs_be(ex) else 0.0
            lkt_, lkk_ = ex.lock_trig, ex.lock_keep
            if be_x > 0:
                s0 = X.stop_price(real, q_leg, 0.0, ch_leg, tks, E.SLIP[2], E.SLIP[3])
                lkt_, lkk_ = be_x * raw * qty * (1 - 1e-12), (s0 - raw) * qty    # in the simulator's qty (= qty)
            return be_, lkt_, lkk_

        s_cap = cap_stop_px(raw, real, qty, lots, cap_net, ex.stop, ch, tks) if cap_net > 0 else -1.0

        def sim(tgt, rt, rl, ix_lvl=np.nan, ix_trig=-1.0, q_leg=qty, ch_leg=ch):
            be_, lkt_, lkk_ = base_lock(q_leg, ch_leg)
            return simulate(C, int(c["er"]), int(sq), fx, ex.stop, lkt_, lkk_, ex.lock_trail, stp, ex.tmax, ex.need,
                            ex.close_stop, K51.TICK, qty, be_, tgt, rt, rl, ixc, ix_lvl, ix_trig,
                            1.0 if c["side"] == "C" else -1.0, *E.SLIP, s_cap)

        split_hit = None
        if split and lots >= 2:
            ch1, q2 = _charges_fn(und, lot1, D.d), qty - lot1
            ch2 = _charges_fn(und, q2, D.d)
            tgt1 = X.target_price(real, lot1, 1000.0, ch1)
            s_be = X.stop_price(real, q2, 0.0, ch2, tks, E.SLIP[2], E.SLIP[3])
            a, b, rE, rX1, r0, r1a, ra, pk1 = sim(tgt1, NONE, NONE, q_leg=lot1, ch_leg=ch1)
            if ra == E.R_NOFILL:
                out.append(None)
                continue
            a2, b2, rE2, rX2, r02, r1b, rb, pk2 = sim(-1.0, np.array([tgt1]), np.array([s_be]), q_leg=q2, ch_leg=ch2)
            split_hit = int(ra) in (E.R_TARGET, R_TGAP)
            net = (rX1 - rE) * lot1 - ch1(rE, rX1) + (rX2 - rE2) * q2 - ch2(rE2, rX2)
            r, r1, rX, b, pk, tgt = (rb, max(r1a, r1b), (rX1 * lot1 + rX2 * q2) / qty, b2, max(pk1, pk2), tgt1)
        else:
            tgt = X.target_price(real, qty, tgt_net * lots, ch) if tgt_net > 0 else -1.0
            rt, rl = lock_levels(lock, real, qty, lots, ch, tks, hmax)
            ix_lvl, ix_trig = np.nan, -1.0
            if idx:
                ix_lvl = float(D.idx[0, ef]) if ef < D.idx.shape[1] else np.nan
                ix_trig = X.target_price(real, qty, 1000.0 * lots, ch)
            a, b, rE, rX, r0, r1, r, pk = sim(tgt, rt, rl, ix_lvl, ix_trig)
            if r == E.R_NOFILL:
                out.append(None)
                continue
            net = (rX - rE) * qty - ch(rE, rX)
        d = dict(und=und, day=D.d, side=c["side"], strike=float(c["strike"]), er=int(r0), xr=int(r1), E=float(a),
                 X=float(b), rE=float(rE), rX=float(rX), qty=qty, lots=lots, why=REASONS[int(r)], net=float(net),
                 gross=float(net + ch(rE, rX)), stop_px=float(s_cap), stop_kind="rupee" if s_cap > 0 else "own",
                 peak=float(pk),
                 best=best_net(pk, rE, qty, ch), tgt_px=float(tgt))
        if s_cap > 0 and int(r) == E.R_STOP:
            d["why"] = "cap"
        if split_hit is not None:
            d["split_hit"] = split_hit
            d["why"] = "split_target" if split_hit else REASONS[int(r)]
        out.append(d)
    return out


def forced_nets(und: str, D, trade: dict, ex: E.Exits, rows: np.ndarray, lots: int = 1, fx0: int = -1,
                be_x: float = BE_X, lock: str = "") -> np.ndarray:
    """The random-exit twin (PREREG 5.3): the net of `trade` (from its signal row er_sig) sold at the open of each row in
    `rows`, with the BASE's exits (own stop, ladder / L25, time stop, breakeven lock) active before it; fx0 (the arm's own
    index exit) still applies when earlier. lock (Amendment C, the base V5): that step lock ('L500' / 'LBE') is active too."""
    st = E.stack(und, D)
    C = st.S[st.idx(trade["side"], trade["strike"])]
    qty = float(K51.lot(und, D.d)) * lots
    sq = E.sq_row(D, ex)
    ch = lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, D.d)[0])  # noqa: E731
    raw, real, ef, tks = X.entry_info(C, trade["er_sig"], sq, qty)
    be = real + ch(real, real) / qty if G.needs_be(ex) else 0.0
    lkt, lkk = ex.lock_trig, ex.lock_keep
    if be_x > 0:
        s0 = X.stop_price(real, qty, 0.0, ch, tks, E.SLIP[2], E.SLIP[3])
        lkt, lkk = be_x * raw * qty * (1 - 1e-12), (s0 - raw) * qty
    stp = ex.steps_arr()
    rt = rl = NONE
    if lock:
        hseg = C[1][ef:sq]
        hmax = float(np.nanmax(hseg)) if np.isfinite(hseg).any() else 0.0
        rt, rl = lock_levels(lock, real, qty, lots, ch, tks, hmax)
    out = np.zeros(len(rows))
    for i, row in enumerate(np.asarray(rows, np.int64)):
        f = int(row) if fx0 < 0 else int(min(row, fx0))
        a, b, rE, rX, r0, r1, r, pk = simulate(C, int(trade["er_sig"]), int(sq), f, ex.stop, lkt, lkk, ex.lock_trail, stp,
                                               ex.tmax, ex.need, ex.close_stop, K51.TICK, qty, be, -1.0, rt, rl,
                                               NONE, np.nan, -1.0, 1.0, *E.SLIP)
        out[i] = 0.0 if r == 0 else (rX - rE) * qty - ch(rE, rX)
    return out
