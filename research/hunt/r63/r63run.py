"""R63 per-process runner: builds one family's plain-rules trades for one period, adds the four trend rules and the
twins, and saves <R63_OUT>/tables/<STRAT>_<period>.pkl. Called by r63study.py (never by hand for the final test).

    python research/hunt/r63/r63run.py r51 design      # LIQ   (R51 Amendment A clean store)
    python research/hunt/r63/r63run.py r57 design      # DON, OOV, SOLO, SOLO2 (R57's store; SENSEX built by r57data)
    python research/hunt/r63/r63run.py r54 design      # HERO  (R54's expiry-day store, R55's T2 ticket)
"""
from __future__ import annotations

import os
import pickle
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _save(strat: str, period: str, T, twins: dict):
    import r63cfg as K
    d = K.OUT / "tables"
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{strat}_{period}.pkl").write_bytes(pickle.dumps(dict(T=T, twins=twins), protocol=pickle.HIGHEST_PROTOCOL))
    print(strat, period, "trades", len(T), {r: int(T[f"chg_{r}"].sum()) for r in ("T1", "T2", "T3", "T4")} if len(T) else {},
          flush=True)


# ------------------------------------------------------------------------------------------------ R51 store: Liquidity
LIQ_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5)}
LIQ_IDX_STOP_MIDCP = 8.0          # LiquidityRules: MIDCPNIFTY's index stop (points)


def run_r51(period: str):
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg  # noqa: F401  (R51_CLEAN read here)
    import common as C
    import fam_liq
    import pandas as pd
    import r63cfg as K
    import r63core as X
    K.check_final_allowed(period)
    if period == "final":
        C.open_final()
    X.record()
    fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", LIQ_IDX_STOP_MIDCP)
    lo, hi = K.span(period)
    parts = []
    for und, books in LIQ_BOOKS.items():
        T = fam_liq.make(und, books).run(lo, hi)
        if len(T):
            parts.append(T)
    T = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    if len(T):
        T["exk"] = "LIQ"
    T, tw = X.apply_rules(T, {"LIQ": fam_liq.EX}, "LIQ", period)
    _save("LIQ", period, T, tw)


# ------------------------------------------------------------------------------------------------ R57 store
def run_r57(period: str):
    sys.path.insert(0, str(HERE.parent / "r57"))
    import r57cfg as K57
    import r57data
    import r57setups as S
    import r57study as ST
    import r57model as M
    import r57eval
    import json
    import numpy as np
    import pandas as pd
    import r63cfg as K
    import r63core as X
    K.check_final_allowed(period)
    if period == "final":
        S.open_final()
    X.record()
    lo, hi = K.span(period)
    hi = min(hi, K57.END)
    for u in K.ALL_UNDS:
        r57data.load(u)
    cache = K.OUT / f"r57_{period}_cands.pkl"
    if cache.exists():
        a, sx = pickle.loads(cache.read_bytes())
    else:
        a = ST.a_rows(S.candidates(lo, hi, setups=("S1", "S2", "S3", "S4")))
        sx = []
        for d in S.days_in("SENSEX", lo, hi):
            D = r57data.load("SENSEX")[d]
            sx += S.s2_candidates("SENSEX", D) + S.s3_candidates("SENSEX", D)
        K.OUT.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(pickle.dumps((a, sx), protocol=pickle.HIGHEST_PROTOCOL))
    exits = {s: S.EXITS[s] for s in ("S1", "S2", "S3", "S4")}
    cols = ["und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "gross", "e_sig",
            "fx_own", "setup"]

    def frame(rows):
        T = pd.DataFrame([{c: r[c] for c in cols} for r in rows], columns=cols)
        T["exk"] = T["setup"]
        return T

    allr = a + sx
    don = [r for r in allr if r["setup"] == "S2"]
    oov = [r for r in allr if r["setup"] == "S3"]
    solo = ST.plain_solo(a, lo, hi)
    model = json.loads((HERE.parent / "r57" / "results" / "models" / "A2.json").read_text())
    names = model["features"]
    sc = np.array([r57eval.score(model, [r["f"].get(n, np.nan) for n in names]) for r in a])
    thr = np.full(len(a), model["threshold"])
    solo2 = ST.select_a(a, sc, thr, "logit")
    for strat, rows in (("DON", don), ("OOV", oov), ("SOLO", solo), ("SOLO2", solo2)):
        T, tw = X.apply_rules(frame(rows), exits, strat, period)
        _save(strat, period, T, tw)


# ------------------------------------------------------------------------------------------------ R54 store: Hero
def run_r54(period: str):
    import r63cfg as K
    K.check_final_allowed(period)
    import r63hero as H
    T, tw = H.run(period)
    _save("HERO", period, T, tw)


if __name__ == "__main__":
    proc, period = sys.argv[1], sys.argv[2]
    sys.path.insert(0, str(HERE))
    {"r51": run_r51, "r57": run_r57, "r54": run_r54}[proc](period)
