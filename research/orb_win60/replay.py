"""Run from repository root: python -m research.orb_win60.replay LAKE OUT."""
import json
import sys
from pathlib import Path
from itertools import product
import numpy as np
import pandas as pd
from research.obuy.costs import Costs

MIN=pd.Timedelta(minutes=1)
EXITS=['timed','10_20','20_20','20_30','lock']
FEE=Costs('dated')


def signals(day):
    bars=day.resample('5min').agg({'open':'first','high':'max','low':'min','close':'last'})
    bars=bars[day.close.resample('5min').count()==5]
    first=bars.between_time('09:15','09:25')
    if len(first)!=3:return
    hi,lo=first.high.max(),first.low.min()
    broken=set(); seen=set(); prev=0; prev_time=None
    for t,r in bars.between_time('09:30','14:45').iterrows():
        outside=int(r.close>hi)-int(r.close<lo)
        sweep=(int(r.low<lo)-int(r.high>hi)) if lo<=r.close<=hi else 0
        sig=t+4*MIN
        ref=day.close.get(sig-60*MIN,day.open.iloc[0])
        tests=[('break',outside),('confirm',outside if outside==prev and t-prev_time==5*MIN else 0) if prev_time is not None else ('confirm',0),
               ('retest',outside if outside in broken and ((outside==1 and r.low<=hi) or (outside==-1 and r.high>=lo)) else 0),
               ('sweep',sweep)]
        for name,d in tests:
            if d and name not in seen:
                seen.add(name)
                yield sig,name,d,r.close,d*(r.close-ref)>0,d*(r.close-r.open)>=.5*max(.05,r.high-r.low)
        if outside:broken.add(outside)
        prev,prev_time=outside,t


def ledger(lake):
    spot=pd.concat([pd.read_parquet(p).set_index('ts') for p in sorted(lake.glob('index_1m_NIFTY_*.parquet'))]).sort_index()
    dates=json.loads((Path(__file__).resolve().parents[1]/'hunt/r50/results/expiry_days.json').read_text())['NIFTY']
    expiries=np.array(sorted(set(dates+['2026-10-19','2026-10-27','2026-11-03','2026-11-09','2026-11-17'])))
    records=[]; skipped=0; failures=0
    for p in sorted(lake.glob('opt_NIFTY_WEEK_*.parquet')):
        book=pd.read_parquet(p,columns=['ts','strike','side','open','volume'])
        for date,b in book.groupby(book.ts.dt.date):
            day=spot.loc[str(date)]
            expiry=expiries[np.searchsorted(expiries,str(date))]
            if not 0<=(pd.Timestamp(str(expiry)).date()-date).days<=7: raise ValueError(date)
            lot=75 if expiry<='2025-12-30' else 65
            dn=(date-pd.Timestamp('1970-01-01').date()).days
            b=b.loc[~b.duplicated(['ts','side','strike'],keep=False)&b.volume.ge(lot)&b.open.gt(0)]
            bykey={k:v.set_index('ts').open.sort_index() for k,v in b.groupby(['side','strike'],observed=True)}
            for t,name,d,close,trend,body in signals(day):
                start=t+MIN;end=start+30*MIN
                movement=d*(day.close.loc[start:end-MIN]-day.open.get(start,np.nan))
                if len(movement)!=30 or movement.isna().any():continue
                for offset in [0,1]:
                    strike=50*(np.floor(close/50+.5)-offset*d)
                    px=bykey.get(('C' if d==1 else 'P',strike))
                    if px is None or start not in px.index:
                        skipped+=1
                        continue
                    buy=px[start]
                    for ex in EXITS:
                        hits=movement.iloc[:0]
                        if ex=='lock':
                            armed=movement[movement>=10]
                            if len(armed):
                                sub=movement.loc[armed.index[0]:]
                                hits=sub[(sub>=30)|(sub<=0)]
                        elif ex!='timed':
                            target,stop=map(int,ex.split('_'))
                            hits=movement[(movement>=target)|(movement<=-stop)]
                        xt=hits.index[0]+MIN if len(hits) else end
                        quotes=px.loc[xt:]
                        sale=quotes.iloc[0] if len(quotes) else 0
                        failures+=int(not len(quotes))
                        row=dict(day=str(date),setup=name,strike=offset,exit=ex,trend=trend,body=body)
                        for slip,col in [(1,'net'),(2,'stress')]:
                            entry=buy+slip;leave=max(0,sale-slip)
                            cost=FEE.charge(True,entry,lot,dn)
                            row[col]=(leave-entry)*lot-cost-FEE.charge(False,leave,lot,dn)
                            row[col+'_debit']=entry*lot+cost
                        records.append(row)
    return pd.DataFrame(records),skipped,failures


def stats(g,col):
    cash=100000; vals=[]
    for cost,pnl in g[[col+'_debit',col]].itertuples(index=False,name=None):
        if cost<=cash:
            vals.append(pnl);cash+=pnl
    a=np.array(vals);equity=np.r_[0,a.cumsum()]
    return len(a),int(sum(a>0)),round(a.sum(),2),round(max(np.maximum.accumulate(equity)-equity),2),a


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True)
    df,skipped,failed=ledger(lake)
    rows=[]
    for (setup,k,ex),g in df.groupby(['setup','strike','exit']):
        for fil in ['all','trend','body','both']:
            v=g if fil=='all' else g[g.trend & g.body] if fil=='both' else g[g[fil]]
            row=dict(id=f'{setup}-{k}-{ex}-{fil}')
            for title,start,end in [('design','2025','2025-10'),('validation','2025-10','2026-04'),('test','2026-04','2026-12')]:
                period=v[(v.day>=start)&(v.day<end)].sort_values('day')
                n,w,net,dd,a=stats(period,'net')
                row.update({title+'_'+s:x for s,x in [('n',n),('wins',w),('wr',w/n if n else 0),('net',net),('avg',net/n if n else 0),('dd',dd),('stress',stats(period,'stress')[2])]})
                if title=='test' and n:
                    # One observation per trading day per configuration.
                    boot=np.random.default_rng(60).choice(a,(5000,n)).mean(axis=1)
                    ph=w/n;z=1.96
                    row['lower_win']=(ph+z*z/(2*n)-z*np.sqrt(ph*(1-ph)/n+z*z/(4*n*n)))/(1+z*z/n)
                    row['lower_mean']=np.quantile(boot,.025)
            rows.append(row)
    table=pd.DataFrame(rows)
    feasible=table.query('design_n>=40 and validation_n>=40')
    qual=feasible.query('design_net>0 and validation_net>0')
    chosen=(qual if len(qual) else feasible).sort_values(['validation_wr','validation_avg','id'],ascending=[False,False,True]).iloc[0]
    strong=table.query('lower_win>.6 and lower_mean>0 and test_stress>0')
    nominal=table.query('test_wr>=.6 and test_net>0')
    table.to_csv(out/'comparisons.csv',index=False)
    print('variants, qualified, nominal, strong, skipped entries, exit writeoffs',len(table),len(qual),len(nominal),len(strong),skipped,failed)
    print('FROZEN',chosen.to_dict())
    print('HINDSIGHT',table.sort_values('test_wr',ascending=False).head(4).to_string(index=False))
    print('BASE',table[table.id.isin(['break-0-timed-all','sweep-0-timed-all','confirm-0-timed-all','retest-0-timed-all'])][['id','test_n','test_wr','test_net']].to_string(index=False))
    print('NOMINAL',nominal[['id','test_n','test_wr','test_net','test_stress','design_net','validation_net']].to_string(index=False))
    (out/'selection.json').write_text(json.dumps(dict(selected=str(chosen.id),qualified=len(qual),nominal=len(nominal),strong=len(strong),skipped_entries=skipped,missing_exits=failed),indent=2))


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
