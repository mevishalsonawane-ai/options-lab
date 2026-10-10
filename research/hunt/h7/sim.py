"""h7 stage 2 helpers: base Liquidity trades from the packs, then pyramiding / room-sizing overlays.

Overlay rules (entries and exits of the arm are NEVER changed; every lot leaves at the arm's own exit fill):
  pyramid 'prem' X, m : add 1 lot when the option's minute CLOSE first reaches e*(1+j*X), j = 1..m (e = first fill)
  pyramid 'idx'  Y, m : add 1 lot when the index has covered j*Y of the way from the entry close to the next liquidity
                        level (minute high for calls / low for puts), j = 1..m with j*Y < 1; no level -> no adds
  every add is a MARKET buy at the next minute's open (same contract; same fill model as the entry), and only if it
  fills strictly before the arm's exit bar. One add per decision minute; add j+1 is decided after add j has filled.
  sizing 'room': initial lots n = f(room), room = distance from the entry close to the next level in index stops
                 (no level -> top bucket); bucket edges from PRE-HOLDOUT real trades only.
"""
from __future__ import annotations
import os, pickle, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa
import numpy as np
import pandas as pd
from obuy import config as C
from obuy.costs import Costs, Fills
from obuy.engine import Execution, Pack, Store, positions
from obuy.strategies.liquidity import ARM_EXITS

OUT = os.path.join(C.CACHE, "h7")
EXES = {"gross": (Fills(mode="flat", pts=0.0), Costs(mode="flat", per_order=0.0)),
        "app": (Fills(), Costs()), "real": (Fills(mode="liq"), Costs())}
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "MIDCPNIFTY": 8.0}
SQC = ARM_EXITS.sq_off - C.OPEN_M


def load():
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        metas, arr, sig = pickle.load(f)
    st = Store(); st.arr = arr
    P = {}
    for en, (m, pm) in metas.items():
        P[en] = (Pack(m, st, 1), None if pm is None else Pack(pm, st, 1))
    return P


def base_trades(pk, exe, pos=True):
    tr = pk.run(ARM_EXITS, exe)
    rows = np.nonzero(pk.meta.c0.values < SQC - 1)[0]
    assert len(rows) == len(tr)
    tr["row"] = rows
    if pos:
        tr = positions(tr, one_at_a_time=True)
    tr["day"] = pd.to_datetime(tr["day"])
    m = pk.meta.iloc[tr.row.values]
    tr["c0"] = m.c0.values
    tr["xcol"] = tr.exit_min.values - C.OPEN_M
    tr["ref"] = m.ref.values
    tr["tgt"] = m.idx_target.values.astype(float)
    tr["room"] = np.where(np.isfinite(tr.tgt), tr.side * (tr.tgt - tr.ref), np.inf) / tr.und.map(ISTOP).values
    return tr.reset_index(drop=True)


def _first(mask):
    a = mask.any(axis=1)
    return np.where(a, mask.argmax(axis=1), C.W)


def adds(pk, tr, exe, kind, step, m):
    """-> (n_adds per trade, list of (fill col, fill price) arrays per add j)."""
    N = len(tr)
    rows = tr.row.values
    O = pk.store.arr["O"][pk.meta.crow.values[rows]].astype(np.float64).round(2)
    Cl = pk.store.arr["Cl"][pk.meta.crow.values[rows]].astype(np.float64).round(2)
    V = pk.store.arr["V"][pk.meta.crow.values[rows]].astype(np.float64)
    valid = ~np.isnan(Cl)
    cols = np.arange(C.W)[None, :]
    c0, xc = tr.c0.values, tr.xcol.values
    e, side, ref, tgt = tr.entry.values, tr.side.values, tr.ref.values, tr.tgt.values
    if kind == "idx":
        IH = pk.store.arr["IH"][pk.meta.irow.values[rows]].astype(np.float64)
        IL = pk.store.arr["IL"][pk.meta.irow.values[rows]].astype(np.float64)
        dist = side * (tgt - ref)
        prog = np.where((side > 0)[:, None], IH - ref[:, None], ref[:, None] - IL) / dist[:, None]
        prog = np.where(np.isfinite(prog), prog, -np.inf)
    nv = np.where(valid, cols, C.W)
    nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]           # first valid col at/after c
    lot = tr.lot.values.astype(float)
    cs = np.concatenate([np.zeros((N, 1)), np.cumsum(np.nan_to_num(V), axis=1)], axis=1)
    ridx = np.arange(N)
    start = c0.copy()
    alive = np.ones(N, bool)
    res = []
    for j in range(1, m + 1):
        if kind == "prem":
            cond = Cl >= (e * (1 + j * step) - 1e-9)[:, None]
        else:
            if j * step >= 1 - 1e-9:
                break
            cond = prog >= j * step - 1e-12
        k = _first(cond & valid & (cols >= start[:, None]))
        k1 = np.minimum(k + 1, C.W - 1)
        f = np.where(k + 1 < C.W, nv[ridx, k1], C.W)
        ok = alive & (k < C.W) & (f < xc)
        fc = np.where(ok, f, 0)
        raw = O[ridx, fc]
        v5 = (cs[ridx, fc] - cs[ridx, np.maximum(fc - 5, 0)]) / lot
        px = exe.fills.buy(raw, v5) if exe.fills.mode == "liq" else exe.fills.buy(raw)
        res.append((ok, fc, np.where(ok, px, np.nan)))
        alive = ok
        start = np.where(ok, f, start)
    return res


def overlay(tr, exe, n0, add_list, add_n=1.0):
    """P&L with n0 initial lots (array) + 1 lot per add; all lots exit at the arm's fill. Returns DataFrame cols."""
    co = exe.costs
    lot = tr.lot.values.astype(float)
    dn = (tr.day.values.astype("datetime64[D]").astype(np.int64))
    bse = False
    e, x = tr.entry.values, tr.exit.values
    q0 = n0 * lot
    gross = (x - e) * q0
    chg = co.charge(True, e, q0, dn, bse)
    nadd = np.zeros(len(tr))
    prem = e * q0
    for ok, fc, px in add_list:
        g = np.where(ok, (x - px) * lot * add_n, 0.0)
        gross = gross + g
        chg = chg + np.where(ok, co.charge(True, np.where(ok, px, 1.0), lot * add_n, dn, bse), 0.0)
        prem = prem + np.where(ok, px * lot * add_n, 0.0)
        nadd += ok * add_n
    qt = q0 + nadd * lot
    chg = chg + co.charge(False, x, qt, dn, bse)
    return pd.DataFrame(dict(gross=gross, charges=chg, net=gross - chg, lots=n0 + nadd, nadd=nadd, prem=prem))
