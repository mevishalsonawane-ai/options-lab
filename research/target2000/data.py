"""Use prospective snapshots; no future outcome controls sequence eligibility."""
import hashlib
import numpy as np
import pandas as pd
from research.payoff20 import data as D
from research.neural_v2 import pipeline as P
from . import execution as E


def sequence_indices(meta, length=6):
    lookup={(r.day,r.side,int(r.minute)):i for i,r in enumerate(meta.itertuples())}
    ids=[];endpoints=[]
    for i,r in enumerate(meta.itertuples()):
        sequence=[lookup.get((r.day,r.side,int(r.minute)-5*j)) for j in reversed(range(length))]
        if all(j is not None for j in sequence):ids.append(sequence);endpoints.append(i)
    return np.asarray(ids,dtype=int),np.asarray(endpoints,dtype=int)


def build(lake,out):
    old=D.outcome
    try:
        D.outcome=E.outcome;x,meta,sessions,audit=D.build(lake,out)
    finally:D.outcome=old
    ids,end=sequence_indices(meta);seq=x.to_numpy(float)[ids];m=meta.iloc[end].reset_index(drop=True)
    y=m.actual_net.to_numpy(float)
    audit.update(label='One whole lot, next-open fixed contract, normal-cost +2000/-1500 tick-rounded barriers,30minute horizon',
        sequence_steps=6,sequence_rows=len(m),sequence_observed_labels=int(np.isfinite(y).sum()),
        sequence_missing_labels=int((~np.isfinite(y)).sum()),
        normal_target_prevalence=float((y[np.isfinite(y)]>=2000-1e-6).mean()),
        normal_win_prevalence=float((y[np.isfinite(y)]>0).mean()))
    audit['fingerprint']=hashlib.sha256((audit['fingerprint']+str(ids.tolist())).encode()+np.nan_to_num(y,nan=-999999).tobytes()).hexdigest()
    P.dump(out/'data_audit.json',audit)
    return seq,m,sessions,audit
