"""R12 trade manager - step 1: rebuild every app strategy's ORIGINAL trades (entries + exits) with the scripts that
produced their numbers, and save each trade's option minute path for the manager replay.

    OBUY_CACHE=<scratch>/hunt/r12/cache python3 -I research/hunt/r12/build.py [arm ...]
    -> <scratch>/hunt/r12/arm_<arm>.npz   (O H L C V float32 [n, 375]; meta columns as arrays)

Sources (reused, not re-written):
  liq_bn      obuy/strategies/liquidity.py signals (liqx cache = line-by-line port of the Kotlin replay), BANKNIFTY only,
              1-ITM nearest, expiry days skipped, ARM_EXITS (-15% wick stop, 20-min time stop unless +5%, index stop /
              target / failed-break exit, 15:10), one position per book (obuy engine.positions).
  orb, orb_fresh, orb_sweep, range_fade
              hunt/h20/sim.py signals + Spec + "fixed" mode (the app since 07 Oct: ONE resting SL-M at max(-40, lock),
              lock ladder earned by minute HIGHS, +target an app check on the minute high sold next open) + h20 positions.
  vixdiv      HUNT_R1 N13 as VixDivRules.kt / r1 signals.py: checks 10:30/11:30/12:30/13:30, index vs 09:15 open > 0.2%
              and VIX vs first minute > 2% the same way -> PE (mirror -> CE); 1-ITM; first signal a day; NIFTY: LIQ exit
              (-15% wick, 20-min unless +5%, 15:10), BANKNIFTY: 15:10. Expiry days allowed.
  hero        hero_deep F07 NIFTY trades (v2_trades.parquet entries; the app's Hero arm is NIFTY only); exits re-run by
              sim.py's logic in r12/sim.py (half at 5x, rest at 20x or 15:05, close <= -60% -> next open; 1-tick fills).
"""
from __future__ import annotations

import importlib.util
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many, positions  # noqa: E402
from obuy.data import market, dnum  # noqa: E402
from obuy.strategies import liquidity as LQ  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "r12")
H20 = os.path.join(os.path.dirname(HERE), "h20")


def h20mod():
    sys.path.insert(0, H20)
    spec = importlib.util.spec_from_file_location("h20sim", os.path.join(H20, "sim.py"))
    m = importlib.util.module_from_spec(spec)
    sys.modules["h20sim"] = m
    spec.loader.exec_module(m)
    sys.path.remove(H20)
    return m


def save(name, pk, tr, extra=None):
    """pk: Pack of candidates, tr: the source's taken trades (cand ids). Saves the taken trades' paths + meta."""
    m = pk.meta.set_index("cand")
    tr = tr.sort_values(["day", "entry_min", "book"], kind="stable").reset_index(drop=True)
    idx = pk.meta.reset_index().set_index("cand").loc[tr.cand.values, "index"].values
    sub = pk.subset(idx)
    out = {k: sub.get(k, slice(0, len(sub))).astype(np.float32) for k in ("O", "H", "L", "Cl", "V")}
    meta = sub.meta.reset_index(drop=True)
    for c in ("c0", "e", "e1", "qty", "lot", "side", "strike", "dn", "ref", "idx_stop", "idx_target", "exit_at", "sig_min"):
        out["m_" + c] = meta[c].values.astype(np.float64)
    out["m_book"] = meta.book.astype(str).values
    out["m_und"] = meta.und.astype(str).values
    for c in ("exit_min", "exit", "why", "gross", "charges", "net"):
        v = tr[c].values
        out["src_" + c] = v.astype(str) if c == "why" else v.astype(np.float64)
    if extra:
        out.update(extra)
    np.savez_compressed(os.path.join(OUT, f"arm_{name}.npz"), **out)
    print(name, len(tr), "net", round(float(tr.net.sum())), flush=True)


def build_liq():
    mk = market()
    s = LQ.signals(mk, room=1.0, unds=("BANKNIFTY",))
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(s, StrikeRule(money=1), exe, 0, None, False)])
    tr = pk.run(LQ.ARM_EXITS, exe)
    tr = positions(tr, one_at_a_time=True)
    save("liq_bn", pk, tr)


def build_orb(arms):
    h = h20mod()
    mk = market()
    jobs, keys = [], []
    for arm in arms:
        kind, k0, k1, mx, tp = h.ARMS[arm]
        s = h.signals(mk, kind)
        jobs.append((s, StrikeRule(0), h.EXES["app"], 0, None, False))
        keys.append(arm)
    mk.release()
    packs = prepare_many(jobs)
    for arm, (pk, _) in zip(keys, packs):
        kind, k0, k1, mx, tp = h.ARMS[arm]
        sp = h.Spec("app_now", stop=40.0, tgt=tp, ladder=h.app_ladder(tp))
        tr = h.sim(pk, sp, h.EXES["app"], mode="fixed")
        tr = h.positions(tr, mx)
        save(arm, pk, tr, extra={"tp": np.array([tp])})


def vixdiv_signals(mk, und, a=0.002, b=0.02):
    ix = mk.index(und)
    M = ix.mat()
    vm = mk.vix.minutes()
    out = []
    for p, d in enumerate(ix.days):
        v = vm.get(d)
        if v is None:
            continue
        o, c = M["o"][p], M["c"][p]
        c = pd.Series(c).ffill().values
        v = pd.Series(v).ffill().values
        o0 = o[0] if np.isfinite(o[0]) else np.nan
        v0 = v[0]
        if not (np.isfinite(o0) and np.isfinite(v0)):
            continue
        for T in (10 * 60 + 30, 11 * 60 + 30, 12 * 60 + 30, 13 * 60 + 30):
            col = T - 1 - C.OPEN_M
            ri, rv = c[col] / o0 - 1, v[col] / v0 - 1
            side = -1 if (ri > a and rv > b) else 1 if (ri < -a and rv < -b) else 0
            if side:
                out.append(dict(und=und, day=d, sig_min=T - 1, side=side, book=f"vixdiv_{und}", ref_spot=float(c[col])))
                break
    return pd.DataFrame(out)


def build_vixdiv():
    from obuy.engine import Exits
    mk = market()
    exe = Execution(expiry="allow", max_delay=2)
    for und, ex in (("NIFTY", Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=15 * 60 + 10)),
                    ("BANKNIFTY", Exits(sq_off=15 * 60 + 10))):
        s = vixdiv_signals(mk, und)
        (pk, _), = prepare_many([(s, StrikeRule(money=1), exe, 0, None, False)])
        tr = pk.run(ex, exe)
        save(f"vixdiv_{und[:2].lower()}", pk, tr)
        mk.release()


def build_hero():
    """F07 NIFTY tickets (hero_deep v2_trades: signal minute, strike, side, qty, entry). hero_deep's own day files are
    gone, so the path comes from obuy's chain (today's expiry, ATM+-10); the fill minute is re-found with sim.py's LIMIT
    rule (limit = HeroRules.limitPrice(ltp + tick, ltp), first of the next 3 minutes whose open or low + 1 tick is under it)."""
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hero_deep", "out", "v2_trades.parquet"))
    t = t[(t.vid == "F07") & (t.u == "NIFTY")].reset_index(drop=True)
    t["tf"] = [int(x[:2]) * 60 + int(x[3:]) for x in t.t_fire]
    s = pd.DataFrame(dict(und="NIFTY", day=[pd.Timestamp(d).date() for d in t.day], sig_min=t.tf - 1,
                          side=np.where(t.side == "CE", 1, -1), book="hero", strike=t.strike.astype(float),
                          lot=t.lot.astype(float), cand=np.arange(len(t))))
    exe = Execution(expiry="only", max_delay=0)
    (pk, _), = prepare_many([(s, StrikeRule(0), exe, 0, None, False)])
    meta = pk.meta.copy()
    O, L, Cl = (pk.get(k, slice(0, len(pk))) for k in ("O", "L", "Cl"))
    TICK = 0.05
    keep, c0s = [], []
    for i, r in meta.iterrows():
        src = t.iloc[int(r.cand)]
        sc = int(r.sig_min) - C.OPEN_M
        lc = Cl[i, :sc + 1]
        lc = lc[np.isfinite(lc)]
        if not len(lc):
            continue
        ltp = lc[-1]
        ask = ltp + TICK
        lim = np.floor(min(ask + (2 if ask < 2 - 1e-9 else 1) * TICK, ltp * 1.2 + 0.1) / TICK + 1e-6) * TICK
        c0 = None
        for j in range(sc + 1, min(sc + 4, 15 * 60 + 5 - C.OPEN_M)):
            if np.isnan(O[i, j]):
                continue
            if O[i, j] + TICK <= lim + 1e-9 or L[i, j] + TICK <= lim + 1e-9:
                c0 = j
                break
        if c0 is None:
            continue
        keep.append(i)
        c0s.append(c0)
    sub = pk.subset(keep)
    m = sub.meta.reset_index(drop=True)
    src = t.iloc[m.cand.astype(int).values].reset_index(drop=True)
    out = {k: sub.get(k, slice(0, len(sub))).astype(np.float32) for k in ("O", "H", "L", "Cl", "V")}
    m["c0"] = c0s
    m["e"] = m["e1"] = src.entry.values
    m["qty"] = src.qty.values
    for c in ("c0", "e", "e1", "qty", "lot", "side", "strike", "dn", "ref", "idx_stop", "idx_target", "exit_at", "sig_min"):
        out["m_" + c] = m[c].values.astype(np.float64)
    out["m_book"] = m.book.astype(str).values
    out["m_und"] = m.und.astype(str).values
    for c in ("exit", "gross", "charges", "net"):
        out["src_" + c] = src[c].values.astype(np.float64)
    out["src_exit_min"] = np.full(len(m), np.nan)
    out["src_why"] = src.why.values.astype(str)
    np.savez_compressed(os.path.join(OUT, "arm_hero.npz"), **out)
    print("hero", len(m), "of", len(t), "net(src)", round(float(src.net.sum())), flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    want = sys.argv[1:] or ["liq", "orb", "vixdiv", "hero"]
    if "liq" in want:
        build_liq()
    if "orb" in want:
        build_orb(["orb", "orb_fresh", "orb_sweep", "range_fade"])
    if "vixdiv" in want:
        build_vixdiv()
    if "hero" in want:
        build_hero()
