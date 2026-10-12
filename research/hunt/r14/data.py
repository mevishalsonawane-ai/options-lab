"""R14 step 1: every ORB / ORB Fresh CANDIDATE entry (each decision bar whose close is beyond the opening range, as the app's
OrbRules decides it - the h20 port, research/hunt/h20/sim.py `signals`) with its option minute path, for the re-entry study.
The candidates are all bars, not the taken trades: which ones are taken depends on each rule's exits and re-entry cap.

    OBUY_CACHE=<scratch>/hunt/r14/cache flock <scratch>/obuy.lock python3 -I research/hunt/r14/data.py
    -> <scratch>/hunt/r14/packs.pkl   (Pack meta per arm + the shared option-minute store)

Fills / costs: h20's "real" execution - the app's paper fills (+-5 bps, stops -10 bps) plus a modelled half-spread (1 tick,
2 on stops, `obuy.costs.Fills(mode='liq')`), and the app's charges (SandboxCosts: Rs 20 brokerage an order, STT 0.15% of the
sell, exchange 0.03553%, SEBI, stamp 0.003% on the buy, GST 18%). ATM strike from the 09:20 bar, BANKNIFTY, 1 lot, expiry
days allowed (the same-day option), premium <= 40 refused (as the arms).
"""
from __future__ import annotations

import importlib.util
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")

from obuy import config as C  # noqa: E402
from obuy.engine import StrikeRule, prepare_many, Pack, Store  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "r14")
ARMS = ("orb", "orb_fresh")


def h20():
    path = os.path.join(os.path.dirname(HERE), "h20", "sim.py")
    if "h20sim" in sys.modules:
        return sys.modules["h20sim"]
    spec = importlib.util.spec_from_file_location("h20sim", path)
    m = importlib.util.module_from_spec(spec)
    sys.modules["h20sim"] = m
    spec.loader.exec_module(m)
    return m


def build():
    from obuy.data import market
    h = h20()
    os.makedirs(OUT, exist_ok=True)
    mk = market()
    jobs = []
    for arm in ARMS:
        kind = h.ARMS[arm][0]
        s = h.signals(mk, kind)
        print(arm, "candidates", len(s), flush=True)
        jobs.append((s, StrikeRule(0), h.EXES["real"], 0, None, False))
    mk.release()
    packs = prepare_many(jobs)
    res = {arm: pk.meta for arm, (pk, _) in zip(ARMS, packs)}
    store = packs[0][0].store
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((res, store.arr), f, protocol=4)
    print("saved", round(sum(v.nbytes for v in store.arr.values()) / 1e6), "MB", flush=True)


def load():
    with open(os.path.join(OUT, "packs.pkl"), "rb") as f:
        res, arr = pickle.load(f)
    st = Store()
    st.arr = arr
    return {k: Pack(m, st, 1) for k, m in res.items()}


if __name__ == "__main__":
    build()
