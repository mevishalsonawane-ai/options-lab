"""Descriptive attribution on the equity tape, equal-weighted; events matched to quiet windows."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd


def run(ledger,equity,out):
    raw=pd.read_parquet(equity)
    if raw.duplicated(['ts','symbol']).any():raise ValueError('Duplicate equity keys')
    prices=raw.pivot(index='ts',columns='symbol',values='close').sort_index()
    g=pd.read_parquet(ledger)
    g=g[(g.day>='2026-10-21')&(g['shift']==0)&(g.level==30)].copy()
    g['ts']=pd.to_datetime(g.day).dt.tz_localize('Asia/Kolkata')+pd.to_timedelta(g.clock+555,unit='min')
    cases=g[g.label!=0]
    controls=g[g.label==0]
    rows=[];examples=[]
    def returns(t,n):
        end=t+pd.Timedelta(minutes=int(n))
        if t not in prices.index or end not in prices.index:return pd.Series(dtype=float)
        return (prices.loc[end]/prices.loc[t]-1).dropna()
    for r in cases.itertuples():
        ct=controls[(controls.day==r.day)&(abs(controls.clock-r.clock)<=90)]
        if ct.empty:continue
        ct=ct.assign(distance=abs(np.log1p(ct.range15)-np.log1p(r.range15))+abs(ct.clock-r.clock)/180)
        ctrl=ct.sort_values(['distance','clock']).iloc[0]
        vals=[]
        for t in [r.ts,ctrl.ts]:
            before=returns(t-pd.Timedelta(minutes=5),5)
            during=returns(t,r.delay)
            if min(len(before),len(during))<45:break
            vals.extend([float((before*r.label>0).mean()),float((during*r.label>0).mean())])
        if len(vals)!=4:continue
        rows.append([r.day,r.label,*vals])
    for r in cases[cases.delay>=2].sort_values(['day','clock']).groupby('label').head(1).itertuples():
        delta=returns(r.ts,r.delay)
        subset=['HDFCBANK','ICICIBANK','RELIANCE','INFY','TCS','LT']
        examples.append(dict(day=r.day,anchor=str(r.ts),label=r.label,n=len(delta),
                             aligned=int((delta*r.label>0).sum()),returns_pct={k:round(float(delta[k])*100,3) for k in subset if k in delta}))
    paired=pd.DataFrame(rows,columns=['day','side','before','during','control_before','control_during'])
    result=dict(equities=int(prices.shape[1]),pairs=len(paired),means=paired.drop(columns=['day','side']).mean().to_dict(),
                by_side=paired.drop(columns='day').groupby('side').mean().to_dict('index'),examples=examples)
    (out/'equity_breadth.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))


if __name__=='__main__':
    p=argparse.ArgumentParser()
    for k in ['ledger','equity','out']:p.add_argument(k,type=Path)
    run(**vars(p.parse_args()))
