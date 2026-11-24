"""Retrospective decomposition of fixed-contract premium first passages."""
import argparse
import json
from itertools import permutations,product
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.special import ndtr


def mark(s,vol,time,k,side):
    width=vol*np.sqrt(time)
    d1=np.log(s/k)/width+width/2
    return side*(s*ndtr(side*d1)-k*ndtr(side*(d1-width)))


def decompose(d):
    # Average every update order, allocating interactions symmetrically.
    factors=[(d.s0,d.s1),(d.iv0/100,d.iv1/100),(d.t0,d.t1)]
    prices={state:mark(*(factors[i][state[i]] for i in range(3)),d.strike,d.side) for state in product([0,1],repeat=3)}
    values=np.zeros((3,len(d)))
    for order in permutations(range(3)):
        state=[0,0,0]
        for i in order:
            old=prices[tuple(state)]
            state[i]=1
            values[i]+=(prices[tuple(state)]-old)/6
    result=d.copy()
    for name,v in zip(['spot_effect','iv_effect','time_effect'],values):result[name]=v
    result['residual']=result.move-values.sum(axis=0)
    assert np.allclose(result.move,result[['spot_effect','iv_effect','time_effect','residual']].sum(axis=1))
    return result


def run(lake,ledger,out):
    anchors=pd.read_parquet(ledger).query('shift==0 and level==30')
    dates=json.loads((Path(__file__).resolve().parents[1]/'hunt/r50/results/expiry_days.json').read_text())['NIFTY']
    expiries=pd.DatetimeIndex(sorted(set(dates+['2026-10-19','2026-10-27','2026-11-03','2026-11-09','2026-11-17'])))
    rows=[];total={'discovery':0,'replication':0};skipped={'discovery':0,'replication':0}
    days=dict(tuple(anchors.groupby('day')))
    for path in sorted(lake.glob('opt_NIFTY_WEEK*.parquet')):
        frame=pd.read_parquet(path,columns=['ts','strike','side','close','spot','iv','volume'])
        for day,book in frame.groupby(frame.ts.dt.strftime('%Y-%m-%d'),sort=False):
            if day not in days:continue
            period='discovery' if day<'2026-10-21' else 'replication'
            book=book[~book.duplicated(['ts','side','strike'],keep=False)]
            bykey={k:g.set_index('ts').sort_index() for k,g in book.groupby(['strike','side'],observed=True)}
            expiry=expiries[expiries.searchsorted(pd.Timestamp(day))].tz_localize('Asia/Kolkata')+pd.Timedelta(hours=15,minutes=30)
            for r in days[day].itertuples():
                t=pd.Timestamp(day,tz='Asia/Kolkata')+pd.Timedelta(minutes=555+r.clock)
                for side,name in [(1,'C'),(-1,'P')]:
                    contract=bykey.get((r.strike,name))
                    if contract is None:
                        skipped[period]+=1
                        continue
                    g=contract.reindex(pd.date_range(t,periods=16,freq='min'))
                    if not (g.volume.gt(0)&g.close.gt(0)&g.spot.gt(0)).all():
                        skipped[period]+=1
                        continue
                    total[period]+=1
                    change=g.close.to_numpy()-g.close.iloc[0]
                    for L in [20,30,40]:
                        indices=np.flatnonzero(abs(change)>=L-1e-9)
                        if not len(indices):continue
                        i=int(indices[0]); a=g.iloc[0];b=g.iloc[i]
                        # Provider timestamps label the minute start; bar close is one minute later.
                        time0=(expiry-t-pd.Timedelta(minutes=1)).total_seconds()/(365*86400)
                        rows.append(dict(period=period,level=L,side=side,up=change[i]>0,delay=i,strike=r.strike,
                            s0=a.spot,s1=b.spot,iv0=a.iv,iv1=b.iv,t0=time0,t1=time0-i/(365*1440),move=change[i]))
    d=pd.DataFrame(rows)
    use=d.iv0.between(.1,200)&d.iv1.between(.1,200)&(d.t1>0)
    valid=decompose(d[use])
    valid['same_spot_direction']=np.sign(valid.s1-valid.s0)*valid.side==np.sign(valid.move)
    valid['largest_spot']=valid.spot_effect.abs()>=valid[['iv_effect','time_effect','residual']].abs().max(axis=1)
    keys=['period','level','up']
    stats=valid.groupby(keys).agg(usable=('move','size'),mean_move=('move','mean'),spot=('spot_effect','mean'),
        iv=('iv_effect','mean'),time=('time_effect','mean'),residual=('residual','mean'),
        aligned=('same_spot_direction','mean'),spot_largest=('largest_spot','mean'),median_minutes=('delay','median'))
    stats['all_crossings']=d.groupby(keys).size()
    stats.to_csv(out/'premium_attribution.csv')
    (out/'premium_coverage.json').write_text(json.dumps(dict(complete_contract_windows=total,incomplete_contract_windows=skipped),indent=2))
    valid.groupby(keys+['side']).agg(count=('move','size'),premium=('move','mean'),spot=('spot_effect','mean'),
        iv=('iv_effect','mean'),time=('time_effect','mean'),residual=('residual','mean')).to_csv(out/'premium_by_side.csv')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    for k in ['lake','ledger','out']:p.add_argument(k,type=Path)
    run(**vars(p.parse_args()))
