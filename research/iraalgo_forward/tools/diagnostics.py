"""Independent checkpoint replay, block inference, liquidity snapshot and data audit."""
import argparse
import json
import hashlib
from pathlib import Path
import numpy as np
import pandas as pd
import torch
from research.iraalgo_forward.train import FOLDS, Network
from research.iraalgo_forward.metrics import select, measure


def verify(cache, run, depth):
    meta = pd.read_parquet(cache/'candidates.parquet')
    x = np.load(cache/'history.npy')
    a = json.loads((run/'audit.json').read_text())
    pred = np.where(meta.day>='2026-10-21')[0]
    m = meta.iloc[pred]
    actual = json.loads((run/'results.json').read_text())
    sessions = [s for s in a['sessions'] if s>='2026-10-21']
    assert not meta.duplicated(['day','clock','side']).any()
    assert (meta.day <= meta.expiry).all() and np.isfinite(x).all()
    family = json.loads((run/'frozen.json').read_text())['family']
    saved = torch.load(run/f'f4_{family}.pt',weights_only=True)
    model = Network(family,x.shape[-1])
    model.load_state_dict(saved['model'])
    model.eval()
    def scores(indices):
        v=torch.tensor(x[indices])
        z=((v-saved['mean'])/saved['std']).clamp(-8,8)
        with torch.no_grad():return model(z).sigmoid().numpy()
    gates = select(m,scores(pred),.55)
    pnl = gates.timed.to_numpy()
    daily = gates.groupby('day').timed.sum().reindex(sessions,fill_value=0)
    assert abs(pnl.sum()-actual['folds']['3'][family]['net']) < .01
    assert len(pnl)==actual['folds']['3'][family]['trades']
    # One-sided session sign-flip test under symmetry of zero-centred daily P&L.
    rng = np.random.default_rng(24)
    null=(rng.choice([-1,1],size=(20000,len(daily)))*daily.to_numpy()).mean(axis=1)
    p=(1+np.count_nonzero(null>=daily.mean()))/(1+len(null))
    train=np.where(meta.day<'2026-10-21')[0]
    in_sample=measure(select(meta.iloc[train],scores(train),.55),'timed',[s for s in a['sessions'] if s<'2026-10-21'])
    book=pd.read_parquet(depth)
    q=book[(book.underlying=='NIFTY') & (book.strike==(book.spot/50).round()*50) &
           (book.bid>0) & (book.ask>=book.bid) & (book.bid_qty>=65) & (book.ask_qty>=65)].copy()
    half=(q.ask-q.bid)/2
    modeled=.5+.0005*(q.ask+q.bid)/2
    summary=dict(loaded_weights=family,trades=len(pnl),net=float(pnl.sum()),training_replay=in_sample,
         p_one_sided_daily_sign_flip=p,bonferroni_p_four_models=min(1,4*p),
         depth_file=depth.name,depth_sha256=hashlib.sha256(depth.read_bytes()).hexdigest(),depth_rows=len(book),
         quote_start=str(q.answered.min()),quote_end=str(q.answered.max()),two_sided_atm_snapshots=len(q),
         halfspread_median=float(half.median()),halfspread_p95=float(half.quantile(.95)),
         simulated_allowance_median=float(modeled.median()),observed_halfspread_above_allowance=float((half>modeled).mean()))
    (run/'verification.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps({k:v for k,v in summary.items() if k!='training_replay'},indent=1))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for k in ['cache','run','depth']:parser.add_argument('--'+k,type=Path,required=True)
    verify(**vars(parser.parse_args()))
