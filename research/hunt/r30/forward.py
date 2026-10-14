"""R30 forward test of M4-NIFTY (PREREG.md, "Forward test"): the frozen model scores every new NIFTY session.

    python3 research/hunt/r30/forward.py freeze     # once: train M4 exactly as fold F3 (all indices, days < 2026-01-01),
                                                    # save model + threshold to <OUT>/forward/m4_frozen.pkl, print sha256
    python3 research/hunt/r30/forward.py score      # after each lake refresh: rebuild NIFTY (+ BANKNIFTY context) tables,
                                                    # score sessions from 13 Oct 2026, append to results/forward_trades.csv
    python3 research/hunt/r30/forward.py verdict    # only once 40 sessions are scored: the one pre-registered read

Nothing here changes the model after `freeze`; `score` refuses to run if the frozen file's sha256 differs from the one
recorded in PREREG.md. Logged only: no order is placed anywhere.
"""
from __future__ import annotations

import hashlib
import json
import pickle
import re
import sys
from datetime import date

import numpy as np
import pandas as pd

import r30cfg as K
import r30data as RD
import models as MO

FWD = K.OUT / "forward"
FROZEN = FWD / "m4_frozen.pkl"
FORWARD_FROM = date(2026, 10, 13)
SESSIONS = 40
LOG = K.RESULTS / "forward_trades.csv"
UND = "NIFTY"


def sha(path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def freeze():
    if FROZEN.exists():
        raise SystemExit(f"already frozen: {FROZEN} sha256 {sha(FROZEN)}")
    T = pd.read_parquet(K.OUT / "table.parquet")
    T["day"] = pd.to_datetime(T.day).dt.date
    T["year"] = [d.year for d in T.day]
    X = MO.feature_cols(T)
    name, tr_end, _, _ = K.FOLDS[2]
    tr = T[T.day < tr_end].reset_index(drop=True)
    _, mdl = MO.sig_m4(tr, tr.iloc[:1], X)
    pr = np.maximum(mdl["C"].predict_proba(tr[X])[:, 1], mdl["P"].predict_proba(tr[X])[:, 1])
    thr = float(np.quantile(pr, 1 - MO.TOP))
    FWD.mkdir(parents=True, exist_ok=True)
    FROZEN.write_bytes(pickle.dumps(dict(models=mdl, threshold=thr, features=X, fold=name, train_end=str(tr_end))))
    print("frozen", FROZEN, "sha256", sha(FROZEN), "threshold", thr, "features", len(X))


def recorded_sha() -> str | None:
    m = re.search(r"frozen model sha256 `([0-9a-f]{64})`", (K.HERE / "PREREG.md").read_text())
    return m.group(1) if m else None


def load_frozen() -> dict:
    want = recorded_sha()
    if want is None or sha(FROZEN) != want:
        raise SystemExit("the frozen model does not match the sha256 recorded in PREREG.md")
    return pickle.loads(FROZEN.read_bytes())


def signals(F: pd.DataFrame, fz: dict) -> np.ndarray:
    """M4's rule with the frozen models and threshold: the better side when its P(net > 0) >= threshold."""
    X = fz["features"]
    pc = fz["models"]["C"].predict_proba(F[X])[:, 1]
    pp = fz["models"]["P"].predict_proba(F[X])[:, 1]
    side = np.where(pc > pp, "C", np.where(pp > pc, "P", ""))
    return np.where(np.maximum(pc, pp) >= fz["threshold"], side, "")


def score():
    fz = load_frozen()
    import r29data as D29
    for u in (UND, "BANKNIFTY"):
        D29.build(u)                                           # refresh R29's index / option tables from the lake
    K.LAST = date(2100, 1, 1)                                  # forward days are allowed from here on
    tabs = {}
    for u in (UND, "BANKNIFTY"):
        F = RD.features(u)
        F = F[F.day >= FORWARD_FROM]
        tabs[u] = F
    if not len(tabs[UND]):
        print("no forward session in the lake yet")
        return
    Ch = RD.chain_features(UND)
    Ch["day"] = pd.to_datetime(Ch.day).dt.date
    L = RD.labels(UND)
    L["day"] = pd.to_datetime(L.day).dt.date
    c = tabs["BANKNIFTY"][["day", "m", "ret_15m", "ret_day", "rib5_stack"]].rename(
        columns={"ret_15m": "x_ret_15m", "ret_day": "x_ret_day", "rib5_stack": "x_rib5_stack"})
    T = (tabs[UND].merge(Ch, on=["day", "m"], how="left").merge(L, on=["day", "m"], how="inner")
         .merge(c, on=["day", "m"], how="left"))
    T = T[T.day >= FORWARD_FROM].reset_index(drop=True)
    T["und"], T["und_id"] = UND, K.UNDS.index(UND)
    side = signals(T, fz)
    Tr = MO.trade(T, side, "X1")
    days = sorted(T.day.unique())[:SESSIONS]
    Tr = Tr[Tr.day.isin(days)]
    K.RESULTS.mkdir(exist_ok=True)
    Tr.to_csv(LOG, index=False)
    T[T.day.isin(days)][["day", "m", "C_X1", "P_X1"]].to_parquet(FWD / "forward_labels.parquet")
    print(f"{len(days)} forward sessions scored ({days[0]} .. {days[-1]}), {len(Tr)} trades, net Rs {Tr.net.sum():+,.0f}")


def verdict():
    flag = K.RESULTS / "FORWARD_VERDICT"
    if flag.exists():
        raise SystemExit("the forward verdict has been read already")
    Tr = pd.read_csv(LOG, parse_dates=["day"])
    Tr["day"] = Tr.day.dt.date
    lab = pd.read_parquet(FWD / "forward_labels.parquet")
    lab["day"] = pd.to_datetime(lab.day).dt.date
    if lab.day.nunique() < SESSIONS:
        raise SystemExit(f"only {lab.day.nunique()} of {SESSIONS} sessions scored; the verdict waits")
    flag.write_text(pd.Timestamp.now(tz="Asia/Kolkata").isoformat() + "\n")
    lab["und"] = UND
    p, tw = MO.twin_p(Tr.assign(und=UND), lab, "X1")
    r = dict(sessions=int(lab.day.nunique()), trades=len(Tr), net=float(Tr.net.sum()),
             per_trade=float(Tr.net.mean()) if len(Tr) else np.nan, net_2x=float(np.nansum(Tr.net_2x)),
             p_twins=p, twin_per_trade=tw)
    r["passes"] = bool(len(Tr) and r["net"] > 0 and p < 0.05 and r["net_2x"] > 0)
    (K.RESULTS / "forward_verdict.json").write_text(json.dumps(r, indent=1, default=float))
    print(r)


if __name__ == "__main__":
    {"freeze": freeze, "score": score, "verdict": verdict}[sys.argv[1]]()
