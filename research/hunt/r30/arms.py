"""R30 Q2 data: the arms' rebuilt trades (R28's r28arms.py, each arm's own code, fills and charges) with the
point-in-time inputs at each trade's signal row.

    build(part) -> <OUT>/india/q2_<part>.pkl

Trades come from <OUT>/arms/trades_<arm>.parquet (r28arms.py run with R28_OUT=<OUT>/arms; see run.sh). The label is
1{net Rs after the arm's own costs > 0}; it is a trade outcome (after the signal row) and never an input.
"""
from __future__ import annotations

import pickle

import numpy as np
import pandas as pd

import r30cfg as K
from india import HoldoutError, Minutes, feature_rows, holdout_open

ARMS = {"orb": "R7", "fade": "R8", "liq": "R9", "h0": "R10", "night": "R11", "l3": "R17", "solo": "R18"}


def trades(part: str) -> pd.DataFrame:
    out = []
    for arm in ARMS:
        p = K.OUT / "arms" / f"trades_{arm}.parquet"
        if not p.exists():
            continue
        t = pd.read_parquet(p)
        t["day"] = pd.to_datetime(t.day).dt.date
        t = t[[K.part(d) == part for d in t.day]]
        out.append(t.assign(arm=arm))
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


def build(part: str) -> dict:
    if part == "holdout" and not holdout_open("india"):
        raise HoldoutError("the India holdout is built only inside run.py's one holdout read")
    T = trades(part)
    frames, seqs = [], []
    for und, g in T.groupby("und"):
        g = g.reset_index(drop=True)
        MM = Minutes(und, max(g.day))
        F = feature_rows(und, list(zip(g.day, g.sig_row.astype(int))))
        S = []
        for d, r in zip(g.day, g.sig_row.astype(int)):
            p = MM.pos(d, int(r))
            S.append(MM.seq_at(p) if p is not None else np.zeros((K.SEQ_N, 5), np.float32))
        G = pd.concat([g[["arm", "und", "day", "side", "net", "net_x2"]].rename(
            columns={"net": "tr_net", "net_x2": "tr_net_x2", "side": "side"}),
            F.drop(columns=["day"]).rename(columns={"row": "row"})], axis=1)
        G["y_win"] = (G.tr_net > 0).astype(float)
        frames.append(G)
        seqs.append(np.stack(S))
    D = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
    return dict(q2=D, q2_seq=np.concatenate(seqs) if seqs else np.zeros((0, K.SEQ_N, 5), np.float32))


def path(part: str):
    return K.OUT / "india" / f"q2_{part}.pkl"


def save(part: str) -> dict:
    out = build(part)
    path(part).parent.mkdir(parents=True, exist_ok=True)
    path(part).write_bytes(pickle.dumps(out, protocol=4))
    print("q2", part, out["q2"].groupby("arm").size().to_dict() if len(out["q2"]) else {}, flush=True)
    return out


def load(part: str) -> dict:
    if part == "holdout" and not holdout_open("india"):
        raise HoldoutError("the India holdout is read only inside run.py's one holdout read")
    return pickle.loads(path(part).read_bytes())
