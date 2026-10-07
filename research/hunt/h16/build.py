"""h16 stage 1: path packs for Liquidity 15+5 (h4 cached signals, rules unchanged) for money ITM1/ATM/OTM1/OTM2 on the
nearest expiry, 'real' execution, 5 random alternatives per signal, + h13's elasticity pass (for the scaled stops).

    OBUY_CACHE=<scratch>/hunt/h16/cache flock <scratch>/obuy.lock python3 -I research/hunt/h16/build.py
"""
import importlib.util
import os
import pickle
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
spec = importlib.util.spec_from_file_location("h13build", os.path.join(os.path.dirname(HERE), "h13", "build.py"))
B13 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B13)
from obuy import config as C  # noqa: E402
from obuy.engine import StrikeRule, prepare_many  # noqa: E402

OUT = os.path.join(C.CACHE, "h16")
MONEY = (1, 0, -1, -2)


def main():
    os.makedirs(OUT, exist_ok=True)
    sig = B13.signals()
    print("signals", sig.groupby("und").size().to_dict(), flush=True)
    jobs, keys = [], []
    for mo in MONEY:
        jobs.append((sig, StrikeRule(money=mo, series="near"), B13.EXE, 5, B13.WINDOW, False))
        keys.append((mo, "near"))
    t0 = time.time()
    packs = prepare_many(jobs)
    print("pass done", round(time.time() - t0), flush=True)
    store = packs[0][0].store.arr
    metas = {}
    for k, (pk, pool) in zip(keys, packs):
        metas[k + ("real",)] = pk.meta
        metas[k + ("pool",)] = pool.meta
    metas = B13.elasticity(metas)
    print("elasticity done", round(time.time() - t0), flush=True)
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((metas, store, sig), f, protocol=4)
    print("saved", {k: v.shape for k, v in store.items()}, flush=True)


if __name__ == "__main__":
    main()
