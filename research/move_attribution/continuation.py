"""First +/-10 close landmark. Measure continuation versus retracement or timeout."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from .events import read_inputs


def run(lake, ledger, equity, out):
    series=read_inputs(lake)
    prices=pd.concat({k:v.close for k,v in series.items()},axis=1)
    stocks=pd.read_parquet(equity).pivot(index='ts',columns='symbol',values='close')
    m=pd.read_parquet(ledger)
    anchors=m[(m['shift']==0)&(m.level==30)]
    rows=[]
    for r in anchors.itertuples():
        t=pd.Timestamp(r.day,tz='Asia/Kolkata')+pd.Timedelta(minutes=555+r.clock)
        path=prices.loc[t:t+pd.Timedelta(minutes=15)]
        delta=path.NIFTY.to_numpy()-path.NIFTY.iloc[0]
        hit=np.flatnonzero(abs(delta)>=10)
        if not len(hit) or hit[0]>=15:continue
        j=hit[0]
        side=int(np.sign(delta[j]))
        amount=abs(delta[j])
        if amount>=30:continue
        future=side*delta[j+1:]
        exits=np.flatnonzero((future>=30)|(future<=0))
        result='timeout' if not len(exits) else ('continue' if future[exits[0]]>=30 else 'retrace')
        others=path[['BANKNIFTY','SENSEX','FINNIFTY','MIDCPNIFTY']]
        state=side*(others.iloc[j]-others.iloc[0])
        if state.isna().any():continue
        equity_share=np.nan
        if t in stocks.index and path.index[j] in stocks.index:
            d=(stocks.loc[path.index[j]]-stocks.loc[t]).dropna()
            if len(d)>=45:equity_share=float((d*side>0).mean())
        rows.append(dict(period=r.period if hasattr(r,'period') else ('discovery' if r.day<'2026-10-21' else 'replication'),
            day=r.day,side=side,delay=j,amount=amount,result=result,success=result=='continue',
            equity_share=equity_share,breadth=int((state>0).sum())))
    d=pd.DataFrame(rows)
    d['delay_bin']=pd.cut(d.delay,[0,3,7,14])
    d['amount_bin']=pd.cut(d.amount,[9.999,15,20,30],right=False)
    output=[]
    for period,g in d.groupby('period'):
        for name,subset in [('all',g),('all_four_agree',g[g.breadth==4]),('fewer_agree',g[g.breadth<4]),
                              ('stocks_ge_70pct',g[g.equity_share>=.70]),('stocks_lt_70pct',g[g.equity_share<.70])]:
            if len(subset):output.append(dict(period=period,group=name,n=len(subset),continued=int(subset.success.sum()),
                retraced=int(subset.result.eq('retrace').sum()),timeout=int(subset.result.eq('timeout').sum()),rate=subset.success.mean()))
    pd.DataFrame(output).to_csv(out/'continuation.csv',index=False)
    contrasts=[]
    for period,g in d.groupby('period'):
        for flag in ['indices','equities']:
            h=g.copy() if flag=='indices' else g[g.equity_share.notna()].copy()
            if h.empty:continue
            h['flag']=h.breadth.eq(4) if flag=='indices' else h.equity_share.ge(.70)
            h['win']=h.success.astype(float)
            s=h.groupby(['delay_bin','amount_bin','flag'],observed=True).win.agg(['size','mean']).unstack('flag')
            if len(s['size'].columns)<2:continue
            s=s.loc[s['size'].min(axis=1,skipna=False)>=3]
            z=pd.DataFrame(dict(n1=s['size'][True],n0=s['size'][False],p0=s['mean'][False])).reset_index()
            h=h.merge(z,on=['delay_bin','amount_bin'])
            pos=h.flag
            n=int(pos.sum())
            if not n:continue
            a=h.loc[pos,'win'].mean();b=h.loc[pos,'p0'].mean()
            influence=np.where(pos,h.win-h.p0-(a-b),-h.n1/h.n0*(h.win-h.p0))
            blocks=pd.Series(influence,index=h.index).groupby(h.day).sum()
            se=np.sqrt((blocks**2).sum()*len(blocks)/(len(blocks)-1))/n
            contrasts.append(dict(period=period,signal=flag,n=n,rate=a,control=b,lift=a-b,low=a-b-1.96*se,high=a-b+1.96*se))
    pd.DataFrame(contrasts).to_csv(out/'continuation_matched.csv',index=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for k in ['lake','ledger','equity','out']:parser.add_argument(k,type=Path)
    run(**vars(parser.parse_args()))
