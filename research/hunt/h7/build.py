"""h7 stage 1: one data pass -> path packs for Liquidity 15+5 on BANKNIFTY / FINNIFTY / MIDCPNIFTY (signals = h4's cached,
validated port; rules unchanged), executions gross / app / real, plus 5 random-entry alternatives per signal (real).

    OBUY_CACHE=<scratch>/hunt/h7/cache flock <scratch>/obuy.lock python3 -I research/hunt/h7/build.py
"""
import dataclasses, os, pickle, sys, time
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import obuy  # noqa
import pandas as pd
from obuy import config as C
from obuy.costs import Costs, Fills
from obuy.engine import Execution, StrikeRule, prepare_many

H4 = os.path.join(C.SCRATCH, "hunt/h4/cache/h4")
OUT = os.path.join(C.CACHE, "h7")
EXES = {"gross": (Fills(mode="flat", pts=0.0), Costs(mode="flat", per_order=0.0)),
        "app": (Fills(), Costs()), "real": (Fills(mode="liq"), Costs())}
WINDOW = (9 * 60 + 19, 14 * 60 - 1)


def main():
    os.makedirs(OUT, exist_ok=True)
    a = pd.read_pickle(os.path.join(H4, "sig_liq_bnfin_0.pkl"))
    b = pd.read_pickle(os.path.join(H4, "sig_liq_ext_0.pkl"))
    b = b[b.und == "MIDCPNIFTY"]
    sig = pd.concat([a, b], ignore_index=True)
    print("signals", sig.groupby("und").size().to_dict(), flush=True)
    jobs = []
    for en, (fl, co) in EXES.items():
        exe = Execution(expiry="skip", fills=fl, costs=co)
        jobs.append((sig, StrikeRule(money=1), exe, 5 if en == "real" else 0, WINDOW, False))
    t0 = time.time()
    packs = prepare_many(jobs)
    store = packs[0][0].store.arr
    metas = {en: (pk.meta, None if pool is None else pool.meta) for en, (pk, pool) in zip(EXES, packs)}
    with open(os.path.join(OUT, "packs.pkl"), "wb") as f:
        pickle.dump((metas, store, sig), f, protocol=4)
    print("done", time.time() - t0, {k: v.shape for k, v in store.items()}, flush=True)


if __name__ == "__main__":
    main()
