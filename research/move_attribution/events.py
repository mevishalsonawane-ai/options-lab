"""Label first close-to-close passages and record information observable at the anchor."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd

INDICES=['NIFTY','BANKNIFTY','FINNIFTY','MIDCPNIFTY','SENSEX','INDIAVIX']
FLAGS=['large_range','compression','momentum','three_bodies','breadth','vix','breakout','failed_break',
       'near_opening_edge','premium','iv_expansion','oi_shift','option_volume']
LEVELS=[20,30,40]


def read_inputs(lake):
    series={}
    for symbol in INDICES:
        files=sorted(lake.glob(f'index_1m_{symbol}_*.parquet'))
        d=pd.concat([pd.read_parquet(f).set_index('ts') for f in files]).sort_index().loc['2025-11-14':'2026-11-13']
        if d.index.has_duplicates: raise ValueError(symbol+' duplicate index minute')
        series[symbol]=d
    return series


def generate(lake,out):
    series=read_inputs(lake)
    prices=pd.concat({k:g.close for k,g in series.items()},axis=1)
    rows=[]; rejected=0
    for file in sorted(lake.glob('opt_NIFTY_WEEK_*.parquet')):
        chain=pd.read_parquet(file,columns=['ts','strike','side','close','iv','volume','oi'])
        for day,book in chain.groupby(chain.ts.dt.date,sort=True):
            if str(day)<'2025-11-14' or str(day)>'2026-11-13':continue
            base=pd.Timestamp(day,tz='Asia/Kolkata')+pd.Timedelta(hours=9,minutes=15)
            times=pd.date_range(base,periods=375,freq='min')
            d=series['NIFTY'].reindex(times)
            h,l,o,c=[d[k].to_numpy() for k in ('high','low','open','close')]
            market=prices.reindex(times).to_numpy()
            book=book.loc[~book.duplicated(['ts','strike','side'],keep=False)]
            pairs={k:g.set_index('ts').sort_index() for k,g in book.groupby(['strike','side'],observed=True)}
            for shift in [0,5]:
                for i in range(30+shift,346+shift,15):
                    if not np.isfinite(c[i-30:i+16]).all():
                        rejected+=1
                        continue
                    t=times[i]; k=50*np.floor(c[i]/50+.5)
                    hi,lo=h[i-14:i+1].max(),l[i-14:i+1].min()
                    oldhi,oldlo=h[i-15:i].max(),l[i-15:i].min()
                    r15=hi-lo
                    oldr=h[i-29:i-14].max()-l[i-29:i-14].min()
                    cross5=market[i,1:5]/market[i-5,1:5]-1
                    part=[pairs.get((k,s)) for s in ['C','P']]
                    legs=[]
                    for leg in part:
                        legs.append(leg.reindex(times[i-9:i+16]) if leg is not None else None)
                    future=c[i+1:i+16]-c[i]
                    for level in LEVELS:
                        hits=np.flatnonzero(abs(future)>=level)
                        delay=int(hits[0]+1) if len(hits) else 0
                        direction=int(np.sign(future[delay-1])) if delay else 0
                        row=dict(day=str(day),clock=i,shift=shift,level=level,label=direction,delay=delay,
                                 range15=r15,prior_move5=c[i]-c[i-5],peak_up=future.max(),peak_down=-future.min(),
                                 max_excursion=float(abs(future).max()),strike=k,spot_anchor=c[i])
                        if delay:
                            j=i+delay
                            row.update(end_move=c[j]-c[i],same_bodies=int(sum(direction*(c[i+1:j+1]-o[i+1:j+1])>0)),
                                net_breadth=int(sum(direction*(market[j,1:5]-market[i,1:5])>0)) if np.isfinite(market[[i,j],1:5]).all() else np.nan)
                        for lab,side in [('up',1),('down',-1)]:
                            leg=legs[0 if side==1 else 1]
                            opp=legs[1 if side==1 else 0]
                            flags=dict(large_range=r15>=40,compression=r15<=.65*oldr,
                                momentum=side*(c[i]-c[i-5])>=10,
                                three_bodies=np.all(side*(c[i-2:i+1]-o[i-2:i+1])>0),
                                breadth=sum(side*cross5>0)>=3 if np.isfinite(cross5).all() else np.nan,
                                vix=side*(market[i,5]-market[i-5,5])<0 if np.isfinite(market[[i,i-5],5]).all() else np.nan,
                                breakout=c[i]>oldhi if side==1 else c[i]<oldlo,
                                failed_break=(l[i]<oldlo and c[i]>=oldlo) if side==1 else (h[i]>oldhi and c[i]<=oldhi),
                                near_opening_edge=abs(c[i]-(h[:15].max() if side==1 else l[:15].min()))<=10)
                            for name in ['premium','iv_expansion','oi_shift','option_volume']:flags[name]=np.nan
                            if leg is not None and opp is not None:
                                now,prev,other,oldother=leg.iloc[9],leg.iloc[4],opp.iloc[9],opp.iloc[4]
                                if prev.close>0 and now.close>0:flags['premium']=now.close/prev.close>=1.05
                                if prev.iv>0 and now.iv>0:flags['iv_expansion']=now.iv-prev.iv>=.5
                                if min(now.oi,prev.oi,other.oi,oldother.oi)>0:
                                    flags['oi_shift']=(now.oi/prev.oi)<(other.oi/oldother.oi)
                                vol=leg.volume.iloc[:10]
                                if vol.notna().all() and vol.iloc[:5].sum()>0:
                                    flags['option_volume']=vol.iloc[5:].sum()>=1.5*vol.iloc[:5].sum()
                                if direction==side and leg.iloc[9+delay].close>0 and now.close>0:
                                    row['premium_change']=float(leg.iloc[9+delay].close-now.close)
                            row.update({lab+'_'+key:value for key,value in flags.items()})
                        rows.append(row)
    events=pd.DataFrame(rows)
    assert not events.duplicated(['day','clock','shift','level']).any()
    events.to_parquet(out)
    print(len(events),'labels;',rejected,'incomplete windows;',events.day.nunique(),'sessions with anchors')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('lake',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();generate(a.lake,a.out)
