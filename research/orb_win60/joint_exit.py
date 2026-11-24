"""NIFTY sweep: paired fixed-strike/holding-time comparison, one lot per setup."""
import sys
from pathlib import Path
import numpy as np
import pandas as pd
from .profit_exits import experiment
from .replay import signals, MIN, FEE


def outcomes(path, terminal_open, lot, dn, slip, start, spot, direction):
    entry=path.open.iloc[0]+slip
    debit=entry*lot+FEE.charge(True,entry,lot,dn)
    def pnl(sale):
        return np.round(sale*lot-FEE.charge(False,sale,lot,dn)-debit,2)
    ticks=np.arange(max(0,int(entry*20)-100),int(path.high.max()*20)+201)/20
    values=pnl(np.maximum(0,ticks-slip))
    marks=pnl(path.high.to_numpy())
    origin=spot.open.at[start]
    result={}
    # No early loss cut, or exit at 15/30 minutes if spot is back on
    # the losing side of both the entry and the swept opening boundary.
    opening=spot.between_time('09:15','09:29')
    edge=opening.low.min() if direction==1 else opening.high.max()
    for check in (0,15,30):
        for arm,method in [(0,'none'),(1000,'floor'),(2000,'floor'),(3000,'floor'),(2000,'trail'),(3000,'trail')]:
            stop=0.;hit=False;sale=max(0,terminal_open-slip)
            checkpoint=start+check*MIN
            early=check and checkpoint-MIN in spot.index and direction*(spot.close.at[checkpoint-MIN]-origin)<0 and direction*(spot.close.at[checkpoint-MIN]-edge)<0
            for bar,mark in zip(path.itertuples(),marks):
                if early and bar.Index>=checkpoint:
                    sale=max(0,bar.open-slip);hit=True;break
                if stop and bar.low<=stop:
                    sale=max(0,min(stop,bar.open)-slip);hit=True;break
                if arm and mark>=arm:
                    floor=500 if method=='floor' else max(500,500*int(mark//500)-(arm-500))
                    stop=max(stop,ticks[np.searchsorted(values,floor)])
            result[f'{check}_{arm}_{method}']=(float(pnl(sale)),hit,debit)
    return result


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
                path=leg.loc[start:end-MIN]
                terminal=leg.loc[end:].open.iloc[0]
                assert path[['high','low','open']].notna().all().all()
                label='ATM' if depth==0 else str(abs(depth))+('ITM' if depth>0 else 'OTM')
                for slip in [1,2]:
                    for mode,(net,hit,debit) in outcomes(path,terminal,lot,dn,slip,start,s,direction).items():
                        rows.append(dict(date=date,strike=label,slip=slip,mode=mode,net=net,hit=hit,debit=debit))
    df=pd.DataFrame(rows).sort_values('date')
    baseline=df.query("slip==1 and mode=='0_0_none'").set_index('date')
    assert len(baseline)==78 and np.isclose(baseline.net.sum(),40563.74) and (baseline.net>0).sum()==43
    summary=[]
    for (strike,mode,slip),g in df.groupby(['strike','mode','slip']):
        assert len(g)==78 and (g.debit<=100000+g.net.cumsum().shift(fill_value=0)).all()
        for period,v in [('all',g),('early',g[g.date<'2026-04-01']),('later',g[g.date>='2026-04-01'])]:
            p=v.net.to_numpy()
            eq=np.r_[0,p.cumsum()]
            summary.append(dict(strike=strike,mode=mode,slip=slip,period=period,trades=len(p),
                wins=int(sum(p>0)),flat=int(sum(p==0)),hits=int(v.hit.sum()),net=round(p.sum(),2),
                dd=round(max(np.maximum.accumulate(eq)-eq),2)))
    r=pd.DataFrame(summary)
    out.mkdir(exist_ok=True,parents=True)
    r.to_csv(out/'joint_exit.csv',index=False)
    # Rank pre-April candidates only; require both more wins and more net
    # than the benchmark. Assess winner on the later dates without retuning.
    early=r.query("slip==1 and period=='early'").set_index('mode')
    base=early.loc['0_0_none']
    choices=early[(early.net>base.net)&(early.wins>base.wins)].sort_values('net',ascending=False)
    chosen=choices.index[0] if len(choices) else '0_0_none'
    print('Early nominees:',len(choices),'selected:',chosen)
    print(r.query('slip==1').pivot(index='mode',columns='period',values=['net','wins']).to_string())
    print(r[r['mode'].isin([chosen,'0_0_none'])].to_string(index=False))
    paired=df.query('slip==1').pivot(index='date',columns='mode',values='net').sort_index()
    lift=(paired[chosen]-paired['0_0_none']).loc['2026-04-01':].to_numpy()
    print('Later paired total without best increment:',lift.sum()-lift.max(),
          'mean increment CI:',np.quantile(np.random.default_rng(91).choice(lift,(20000,len(lift))).mean(1),[.025,.975]))


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
