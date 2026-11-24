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
        b=pd.read_parquet(f,columns=['ts','side','strike','open','high','low','volume'])
        for day,quotes in b.groupby(b.ts.dt.date):
            date=str(day)
            if date not in original.index:continue
            s=spot.loc[date]
            t,_,direction,close,_,_=next(v for v in signals(s) if v[1]=='sweep' and v[4])
            start=t+MIN
            lot=25 if date<='2024-12-26' else 75 if date<='2025-12-30' else 65
            dn=(day-pd.Timestamp('1970-01-01').date()).days
            for depth in (2,):
                strike=50*(np.floor(close/50+.5)-depth*direction)
                leg=quotes[(quotes.side==('C' if direction==1 else 'P'))&(quotes.strike==strike)].set_index('ts').sort_index()
                leg=leg[~leg.index.duplicated(keep=False)&leg.volume.ge(lot)&leg.open.gt(0)]
                assert start in leg.index
                end=min(start+60*MIN,start.normalize()+pd.Timedelta(hours=15,minutes=20))
                anchor=s.open.at[start]
                signed=direction*(s.close.loc[start:start+4*MIN]-anchor)
                terminal=leg.loc[end:].open.iloc[0]
                entries={'immediate':start, **{f'delay_{n}':start+n*MIN for n in (1,3,5)}}
                for name,series in [('follow',signed[signed>0]),('pullback',signed[signed<0])]:
                    entries[name]=series.index[0]+MIN if len(series) else None
                for mode,tm in entries.items():
                    for slip in (1,2):
                        filled=tm is not None and tm in leg.index
                        debit=net=0
                        if filled:
                            buy=leg.open.at[tm]+slip
                            sell=max(0,terminal-slip)
                            debit=buy*lot+FEE.charge(True,buy,lot,dn)
                            net=round(lot*sell-FEE.charge(False,sell,lot,dn)-debit,2)
                        rows.append(dict(date=date,mode=mode,slip=slip,filled=filled,net=net,debit=debit))
    df=pd.DataFrame(rows).sort_values('date')
    table=[]
    for (mode,slip),g in df.groupby(['mode','slip']):
        assert len(g)==78 and (g.debit<=100000+g.net.cumsum().shift(fill_value=0)).all()
        for stage,v in [('early',g[g.date<'2026-04-01']),('later',g[g.date>='2026-04-01']),('all',g)]:
            a=v.net.to_numpy();eq=np.r_[0,a.cumsum()]
            table.append(dict(mode=mode,slip=slip,period=stage,n=int(v.filled.sum()),wins=int(sum(a>0)),
                net=round(a.sum(),2),dd=round(max(np.maximum.accumulate(eq)-eq),2)))
    r=pd.DataFrame(table);r['wr']=r.wins/r.n
    r.to_csv(out/'entry_timing.csv',index=False)
    e=r.query("slip==1 and period=='early'").set_index('mode')
    benchmark=e.loc['immediate']
    picks=e[(e.net>benchmark.net)&(e.wr>benchmark.wr)].sort_values('net',ascending=False)
    print('Earlier choices',list(picks.index))
    assert np.isclose(r.query("slip==1 and mode=='immediate' and period=='all'").net.iloc[0],40563.74)
    print(r.query("slip==1").to_string(index=False))


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
