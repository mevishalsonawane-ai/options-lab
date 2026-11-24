"""Paired exit comparison. Run from repository root with the input lake and output directory."""
from pathlib import Path
import json
import sys
import numpy as np
import pandas as pd
from research.orb_win60.replay import signals, MIN, FEE

RULES=['base30','time15','time45','time60','extend_winners','cut_stalled','failed_edge']
ROOT=Path(__file__).resolve().parents[2]


def experiment(lake):
    calendar=json.loads((ROOT/'research/hunt/r50/results/expiry_days.json').read_text())['NIFTY']
    exp=sorted(set(calendar+[p.name[:10] for p in (ROOT/'options_lab/data/expiry_cache').glob('2024-11*_chain.parquet')]
                   +['2026-10-19','2026-10-27','2026-11-03','2026-11-09','2026-11-17']))
    records=[]
    for path in sorted(lake.glob('opt_NIFTY_WEEK*.parquet')):
        spot=pd.read_parquet(path.with_name(path.name.replace('opt_NIFTY_WEEK','index_1m_NIFTY'))).set_index('ts').sort_index()
        book=pd.read_parquet(path,columns=['ts','strike','side','volume','open'])
        book=book[book.ts>=pd.Timestamp('2024-11-01',tz='Asia/Kolkata')]
        for date,b in book.groupby(book.ts.dt.date):
            day=spot.loc[str(date)]
            expiry=exp[np.searchsorted(exp,str(date))]
            assert 0<=(pd.Timestamp(expiry).date()-date).days<=7
            lot=25 if expiry<='2024-12-26' else 75 if expiry<='2025-12-30' else 65
            for t,setup,d,close,trend,_ in signals(day):
                if setup!='sweep' or not trend:continue
                start=t+MIN
                if len(day.loc[start:start+29*MIN])!=30:continue
                strike=50*(np.floor(close/50+.5)-d)
                leg=b[(b.strike==strike)&(b.side==('C' if d==1 else 'P'))].set_index('ts').sort_index()
                leg=leg[~leg.index.duplicated(keep=False)&leg.volume.ge(lot)&leg.open.gt(0)]
                if start not in leg.index:continue
                orig=day.open.at[start]
                path30=day.close.loc[start:start+29*MIN]
                opening=day.between_time('09:15','09:29')
                edge=opening.low.min() if d==1 else opening.high.max()
                failed=path30[(path30-edge)*d<0]
                holds=dict(base30=30,time15=15,time45=45,time60=60,
                           extend_winners=60 if d*(path30.iloc[-1]-orig)>0 else 30,
                           cut_stalled=15 if d*(path30.iloc[14]-orig)<=0 else 30,
                           failed_edge=int((failed.index[0]-start)/MIN)+1 if len(failed) else 30)
                for rule,minutes in holds.items():
                    end=min(start+minutes*MIN,start.normalize()+pd.Timedelta(hours=15,minutes=20))
                    exits=leg.loc[end:]
                    assert len(exits)
                    dn=(date-pd.Timestamp('1970-01-01').date()).days
                    row=dict(date=str(date),rule=rule)
                    for key,slip in [('net',1),('stress',2)]:
                        buy=leg.open.at[start]+slip;sell=max(0,exits.open.iloc[0]-slip)
                        debit=buy*lot+FEE.charge(True,buy,lot,dn)
                        row[key+'_debit']=debit
                        row[key]=round(sell*lot-FEE.charge(False,sell,lot,dn)-debit,2)
                    records.append(row)
    return pd.DataFrame(records)


def run(lake,out):
    df=experiment(lake)
    table=[]
    for rule,g in df.groupby('rule'):
        g=g.sort_values('date')
        for key in ['net','stress']:
            cash=100000+g[key].cumsum().shift(fill_value=0)
            assert (g[key+'_debit']<=cash).all()
        for name,v in [('early',g[g.date<'2026-04-01']),('later',g[g.date>='2026-04-01']),('all',g)]:
            a=v.net.to_numpy();eq=np.r_[0,a.cumsum()]
            table.append(dict(rule=rule,period=name,n=len(a),wins=int(sum(a>0)),net=round(a.sum(),2),
                stress=round(v.stress.sum(),2),avg=round(a.mean(),2),max_dd=round(max(np.maximum.accumulate(eq)-eq),2)))
    r=pd.DataFrame(table)
    # Baseline eligible; ranking uses only earlier net profit.
    selected=r[r.period=='early'].sort_values(['net','rule'],ascending=[False,True]).iloc[0].rule
    paired=df.pivot(index='date',columns='rule',values='net')
    assert len(paired)==78 and np.isclose(paired.base30.sum(),6813.48)
    lift=paired.loc['2026-04-01':,selected]-paired.loc['2026-04-01':,'base30']
    ci=np.quantile(np.random.default_rng(31).choice(lift,(20000,len(lift))).mean(1),[.025,.975])
    out.mkdir(parents=True,exist_ok=True);r.to_csv(out/'profit_exit_results.csv',index=False)
    print('EARLY CHOICE',selected,'later paired mean CI',ci)
    print(r.pivot(index='rule',columns='period',values='net'))
    print(r[(r.rule==selected)|(r.rule=='base30')].to_string(index=False))


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
