"""NIFTY sweep: paired fixed-strike/holding-time comparison, one lot per setup."""
import sys
from pathlib import Path
import numpy as np
import pandas as pd
from .profit_exits import experiment
from .replay import signals, MIN, FEE


def run(lake, out):
    original=experiment(lake).query("rule=='time60'").set_index('date')
    assert len(original)==78 and np.isclose(original.net.sum(),35741.77)
    rows=[]
    for f in sorted(lake.glob('opt_NIFTY_WEEK*.parquet')):
        spot=pd.read_parquet(f.with_name(f.name.replace('opt_NIFTY_WEEK','index_1m_NIFTY'))).set_index('ts').sort_index()
        b=pd.read_parquet(f,columns=['ts','side','strike','open','volume'])
        for day,quotes in b.groupby(b.ts.dt.date):
            date=str(day)
            if date not in original.index:continue
            s=spot.loc[date]
            t,_,direction,close,_,_=next(v for v in signals(s) if v[1]=='sweep' and v[4])
            start=t+MIN
            lot=25 if date<='2024-12-26' else 75 if date<='2025-12-30' else 65
            dn=(day-pd.Timestamp('1970-01-01').date()).days
            for depth in (0,1,2):
                strike=50*(np.floor(close/50+.5)-depth*direction)
                leg=quotes[(quotes.side==('C' if direction==1 else 'P'))&(quotes.strike==strike)].set_index('ts').sort_index()
                leg=leg[~leg.index.duplicated(keep=False)&leg.volume.ge(lot)&leg.open.gt(0)]
                assert start in leg.index
                for hold in (45,60,75,90):
                    end=min(start+hold*MIN,start.normalize()+pd.Timedelta(hours=15,minutes=20))
                    raw=leg.loc[end:].open.iloc[0]
                    row=dict(date=date,rule=f'{depth}ITM_{hold}')
                    for key,slip in [('net',1),('stress',2)]:
                        buy=leg.at[start,'open']+slip
                        sell=max(0,raw-slip)
                        debit=buy*lot+FEE.charge(True,buy,lot,dn)
                        row[key+'_debit']=debit
                        row[key]=round(lot*sell-FEE.charge(False,sell,lot,dn)-debit,2)
                    rows.append(row)
    trades=pd.DataFrame(rows)
    base='1ITM_60'
    paired=trades.pivot(index='date',columns='rule',values='net').sort_index()
    assert np.allclose(paired[base],original.reindex(paired.index).net)
    table=[]
    for rule,g in trades.groupby('rule'):
        for key in ['net','stress']:
            assert (g[key+'_debit'] <= 100000+g[key].cumsum().shift(fill_value=0)).all()
        for stage,v in [('earlier',g[g.date<'2026-04-01']),('later',g[g.date>='2026-04-01']),('all',g)]:
            a=v.net.to_numpy();eq=np.r_[0,a.cumsum()]
            table.append(dict(rule=rule,stage=stage,n=len(a),wins=sum(a>0),net=round(a.sum(),2),
                stress=round(v.stress.sum(),2),without_best=round(a.sum()-max(a),2),
                drawdown=round(max(np.maximum.accumulate(eq)-eq),2),peak_debit=round(v.net_debit.max(),2)))
    r=pd.DataFrame(table)
    early=r[r.stage=='earlier'].set_index('rule')
    selected=early[early.drawdown<=1.25*early.at[base,'drawdown']].without_best.idxmax()
    print('EARLY SELECTION',selected)
    print(r.pivot(index='rule',columns='stage',values='net'))
    print(r[r.rule.isin([base,selected])].to_string(index=False))
    delta=(paired[selected]-paired[base]).loc['2026-04-01':].to_numpy()
    print('Later paired mean increment CI',np.quantile(np.random.default_rng(62).choice(delta,(20000,len(delta))).mean(axis=1),[.025,.975]))
    out.mkdir(exist_ok=True,parents=True)
    r.to_csv(out/'strike_duration.csv',index=False)


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
