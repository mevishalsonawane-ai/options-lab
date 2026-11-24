"""NIFTY sweep: paired fixed-strike/holding-time comparison, one lot per setup."""
import sys
from pathlib import Path
import numpy as np
import pandas as pd
from .profit_exits import experiment
from .replay import signals, MIN, FEE


def outcomes(path, terminal_open, lot, dn, slip):
    raw=path.open.iloc[0]
    entry=raw+slip
    debit=entry*lot+FEE.charge(True,entry,lot,dn)
    def pnl(sale):
        return np.round(sale*lot-FEE.charge(False,sale,lot,dn)-debit,2)
    # Minimum exchange tick at which a stop filled with assumed slippage
    # recovers the desired net profit. A gap may yield less.
    ticks=np.arange(max(0,int(raw*20)-100),int(path.high.max()*20)+201)/20
    proceeds=pnl(np.maximum(0,ticks-slip))
    high_net=pnl(path.high.to_numpy())
    result={}
    for mode in ['timed','breakeven','step','combined']:
        stop=0.;hit=False
        for bar,mark in zip(path.itertuples(),high_net):
            if mode=='timed':break
            if stop and bar.low<=stop:
                sale=max(0,min(bar.open,stop)-slip)
                hit=True
                break
            lock=None
            if mode in ('breakeven','combined') and bar.high>=raw*1.1-1e-9:
                lock=0
            if mode in ('step','combined') and mark>=1000:
                lock=500*(int(mark//500)-1)
            if lock is not None:
                stop=max(stop,ticks[np.searchsorted(proceeds,lock)])
        if not hit:sale=max(0,terminal_open-slip)
        result[mode]=(float(pnl(sale)),hit,debit)
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
            for depth in (-2,-1,0,1,2):
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
                    for mode,(net,hit,debit) in outcomes(path,terminal,lot,dn,slip).items():
                        rows.append(dict(date=date,strike=label,slip=slip,mode=mode,net=net,hit=hit,debit=debit))
    df=pd.DataFrame(rows).sort_values('date')
    baseline=df.query("strike=='1ITM' and slip==1 and mode=='timed'").set_index('date')
    assert np.allclose(baseline.net,original.reindex(baseline.index).net)
    prior=pd.read_csv(Path(__file__).parent/'results/strike_duration_otm.csv')
    summary=[]
    for (strike,mode,slip),g in df.groupby(['strike','mode','slip']):
        assert len(g)==78 and (g.debit<=100000+g.net.cumsum().shift(fill_value=0)).all()
        for period,v in [('all',g),('early',g[g.date<'2026-04-01']),('later',g[g.date>='2026-04-01'])]:
            p=v.net.to_numpy()
            eq=np.r_[0,p.cumsum()]
            summary.append(dict(strike=strike,mode=mode,slip=slip,period=period,trades=len(p),
                wins=int(sum(p>0)),flat=int(sum(p==0)),hits=int(v.hit.sum()),net=round(p.sum(),2),
                dd=round(max(np.maximum.accumulate(eq)-eq),2)))
        if mode=='timed':
            ref=prior[(prior.rule==strike+'_60')&(prior.stage=='all')].iloc[0]
            assert np.isclose(g.net.sum(),ref['net' if slip==1 else 'stress'])
    r=pd.DataFrame(summary)
    out.mkdir(exist_ok=True,parents=True)
    r.to_csv(out/'orb_profit_lock.csv',index=False)
    print(r.query("slip==1 and period=='all'").to_string(index=False))
    print(r.query("strike=='2ITM' and mode in ['combined','timed'] and (period=='later' or slip==2)").to_string(index=False))


if __name__=='__main__':run(Path(sys.argv[1]),Path(sys.argv[2]))
