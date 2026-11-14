"""Paired immediate/retest hypotheses, fixed-grid sensitivity and full account replay."""
import argparse
import hashlib
from pathlib import Path
import numpy as np
import pandas as pd
from research.neural_v2 import pipeline as P,strategy as S
from research.confluence65 import engine as E,features as F,robustness as R
from . import signals as G

CENTRE=('range','retest','plain','runner',5,.1)


def name(rule):
    kind,entry,context,profile,wait,tol=rule
    return f'{kind}__{entry}__{context}__{profile}__w{wait}__a{tol:.2f}'


def exit_profile(bar,entry,stop,elapsed,profile):
    if profile not in ('quick','runner'):raise ValueError('Unknown exit')
    op,hi,lo,close,vol=bar
    if vol<=0 or not np.isfinite(bar).all() or min(op,hi,lo,close)<=0:return None
    stop=entry*.85
    if op<=stop:return op,'gap_stop'
    if profile=='quick':
        if elapsed>=20:return op,'twenty_minute_exit'
        if op>=entry*1.10:return op,'gap_target'
    else:
        if elapsed>=60:return op,'maximum_hold'
        if elapsed>=20 and op<entry*1.05:return op,'time_stop'
    if lo<=stop:return stop,'initial_stop'
    if profile=='quick' and hi>=entry*1.10:return entry*1.10,'target'
    return None


def load(lake,out):
    indices={};hashes={}
    for sym in ('NIFTY','INDIAVIX'):
        paths=sorted(lake.glob(f'index_1m_{sym}_*.parquet'))
        if not paths:raise ValueError('Missing '+sym)
        for p in paths:hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        d=pd.concat([pd.read_parquet(p) for p in paths]).sort_values('ts')
        if d.ts.dt.tz is None or d.ts.duplicated().any():raise ValueError('Invalid timestamps')
        d.ts=d.ts.dt.tz_convert('Asia/Kolkata');indices[sym]=d.set_index('ts')
    index=indices['NIFTY'][['open','high','low','close']].join(indices['INDIAVIX'].close.rename('INDIAVIX'),how='inner').dropna()
    trend=G.higher_trend(index);end=index.index.max().normalize();start=end-pd.DateOffset(years=1)+pd.Timedelta(days=1)
    paths=sorted(lake.glob('opt_NIFTY_WEEK_*.parquet'))
    if not paths:raise ValueError('Missing options')
    parts=[]
    for p in paths:
        hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest();g=pd.read_parquet(p);g.ts=g.ts.dt.tz_convert('Asia/Kolkata')
        parts.append(g[(g.ts>=start)&(g.ts<end+pd.Timedelta(days=1))])
    quotes=pd.concat(parts);quotes['side']=quotes.side.astype(str)
    if quotes.duplicated(['ts','strike','side']).any():raise ValueError('Duplicate quotes')
    days={day:g for day,g in quotes.groupby(quotes.ts.dt.date)}
    cal=pd.read_csv(P.HERE.parent/'brain/calendar_data/expiries.csv');observed=set(pd.to_datetime(cal.loc[cal.und=='NIFTY','date']).dt.date)
    sessions=[];previous=None
    variants=[(kind,entry,5,.1) for kind in G.KINDS for entry in ('immediate','retest')]
    variants=list(dict.fromkeys(variants+[('range','retest',w,t) for w in (4,5,6) for t in (.08,.1,.12)]))
    for day,d in index.groupby(index.index.date):
        if pd.Timestamp(day).tz_localize('Asia/Kolkata')<start:
            previous=dict(high=float(d.high.max()),low=float(d.low.min())) if len(d)>=300 else None;continue
        if day not in days:raise ValueError('Missing option session')
        g=days[day].copy();g['minute']=g.ts.dt.hour*60+g.ts.dt.minute
        exp,source=P.expiry(day,observed);eligible=1<=(exp-day).days<=7
        selected=g[((g.side=='C')&(g.offset==-1))|((g.side=='P')&(g.offset==1))].copy()
        vol=d.close.pct_change(fill_method=None).rolling(15).std()
        q=75 if str(exp)<'2026-01-01' else 65
        t=(pd.Timestamp(str(exp)+' 15:30',tz='Asia/Kolkata')-selected.ts).dt.total_seconds().to_numpy()/(365*86400)
        selected['delta']=F.delta(selected.spot,selected.strike,selected.iv/100,t,selected.side)
        selected['vol15']=vol.reindex(pd.DatetimeIndex(selected.ts)).to_numpy()
        selected['cost']=np.array([-S.trade_pnl(p,p,str(day),str(exp))[0] if np.isfinite(p) and p>0 else np.nan for p in selected.close])
        selected['reward_cost']=selected.delta.abs()*selected.spot*selected.vol15*np.sqrt(20)*q/selected.cost
        selected['cost_ok']=selected.delta.abs().between(.45,.85)&selected.iv.gt(0)&np.isfinite(selected.iv)&(selected.reward_cost>=3)
        masks={}
        for side,number in [('C',1),('P',2)]:
            passed=selected[selected.side==side].set_index('ts').cost_ok.reindex(d.index).eq(True).to_numpy(bool)
            aligned=trend.reindex(d.index).eq(number).to_numpy()
            masks['plain',number]=np.ones(len(d),bool);masks['trend',number]=aligned;masks['trend_cost',number]=aligned&passed
        raw={(kind,entry,w,t):G.detect(d,previous,kind,entry,w,t).to_numpy() for kind,entry,w,t in variants}
        picks={(int(r.minute)+1,r.side):float(r.strike) for r in selected.itertuples()}
        book={(int(r.minute),float(r.strike),r.side):(r.open,r.high,r.low,r.close,r.volume) for r in g.itertuples()}
        sessions.append(dict(day=str(day),expiry=str(exp),expiry_source=source,book=book,picks=picks,minutes=(d.index.hour*60+d.index.minute+1).to_numpy(),raw=raw,masks=masks,eligible_expiry=eligible))
        previous=dict(high=float(d.high.max()),low=float(d.low.min())) if len(d)>=300 else None
        if len(sessions)%50==0:print('built',len(sessions),'sessions',flush=True)
    audit=dict(start=str(start.date()),end=str(end.date()),sessions=len(sessions),files=hashes,fresh_holdout=False,
        feature_limits='Reported-IV delta and prior realized-vol move/cost are proxies, no actual order-flow or resting liquidity; fills are modeled OHLC next opens.')
    P.dump(out/'audit.json',audit);return sessions,audit


def adapt(sessions,rule,delay=0):
    kind,entry,context,profile,wait,tol=rule;out=[]
    for s in sessions:
        a=s['raw'][kind,entry,wait,tol];keep=((a==1)&s['masks'][context,1])|((a==2)&s['masks'][context,2])
        c={int(m)+delay:('C' if side==1 else 'P',s['picks'].get((int(m),'C' if side==1 else 'P')))
            for m,side in zip(s['minutes'][keep],a[keep])} if s['eligible_expiry'] else {}
        out.append(dict(day=s['day'],expiry=s['expiry'],book=s['book'],candidates=c))
    return out


def replay(sessions,rule,stress,delay=0,state=None,diagnostics=True):
    original=E.exit_fill
    try:
        E.exit_fill=lambda bar,entry,stop,elapsed,target_pct:exit_profile(bar,entry,stop,elapsed,rule[3])
        ledger,s=E.replay(adapt(sessions,rule,delay),stress,state=state)
    finally:E.exit_fill=original
    account=s.pop('_state');days=[v['day'] for v in sessions]
    if diagnostics:
        s['wilson95']=R.wilson(s['wins'],s['trades']);s['block_p_value']=R.block_pvalue(ledger,days);s['monte_carlo']=R.monte_carlo(ledger,days)
    return ledger,s,account


def walk_forward(sessions,rules,out):
    states={1.:None,2.:None};pieces={1.:[],2.:[]};decisions=[];testdays=[]
    for start,end in [('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')]:
        train=[s for s in sessions if s['day']<start];test=[s for s in sessions if start<=s['day']<=end];testdays +=[s['day'] for s in test]
        scores={name(r):(replay(train,r,1.,diagnostics=False)[1],replay(train,r,2.,diagnostics=False)[1]) for r in rules}
        chosen=R.select(scores);lookup={name(r):r for r in rules}
        decisions.append(dict(training_last_day=train[-1]['day'],evaluation_start=start,selected=chosen or 'NO_TRADE',qualified=sum(R.qualifies(*v) for v in scores.values())))
        P.dump(out/f'wf_training_{start}.json',scores)
        for stress in (1.,2.):
            if chosen:l,s,states[stress]=replay(test,lookup[chosen],stress,state=states[stress],diagnostics=False)
            else:
                l,s=E.replay([dict(day=t['day'],expiry=t['expiry'],book=t['book'],candidates={}) for t in test],stress,state=states[stress]);states[stress]=s.pop('_state')
            pieces[stress].append(l)
    accounts={}
    for stress in (1.,2.):
        d=pd.concat(pieces[stress],ignore_index=True);d.to_csv(out/f'walk_forward_{stress}.csv',index=False)
        s=P.summarize(d.assign(stress_net=d.net),testdays);s.pop('stress_net');s['monte_carlo']=R.monte_carlo(d,testdays);accounts[str(stress)]=s
    return dict(decisions=decisions,accounts=accounts)


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);P.status(out,stage='ingestion',errors=[]);sessions,audit=load(lake,out)
    primary=[(kind,entry,context,profile,5,.1) for kind in G.KINDS for entry in ('immediate','retest') for context in G.CONTEXTS for profile in ('quick','runner')]
    neighbours=[(*CENTRE[:4],w,t) for w in (4,5,6) for t in (.08,.1,.12)]
    rules=list(dict.fromkeys(primary+neighbours));results={};folds={}
    scenarios=[(r,0) for r in rules]+[(CENTRE,1)]
    for i,(rule,delay) in enumerate(scenarios):
        for stress in (1.,2.):
            key=name(rule)+f'__d{delay}__x{stress}';d,s,_=replay(sessions,rule,stress,delay);results[key]=s;d.to_csv(out/(key+'.csv'),index=False)
            folds[key]={}
            for label,a,b in [('design','2025-01-01','2026-03-31'),('q2','2026-04-01','2026-06-30'),('q3','2026-07-01','2026-12-31')]:
                sub=[v for v in sessions if a<=v['day']<=b];folds[key][label]=replay(sub,rule,stress,delay,diagnostics=False)[1]
        P.dump(out/'partial.json',dict(results=results,chronological=folds));P.status(out,stage='replaying',completed_scenarios=i+1,total_scenarios=len(scenarios));print('replayed',i+1,'/',len(scenarios),name(rule),delay,flush=True)
    qs=P.bh([s['block_p_value'] for s in results.values()])
    for s,q in zip(results.values(),qs):s['bh_q']=float(q)
    wf=walk_forward(sessions,primary,out)
    result=dict(audit=audit,unique_rules=len(rules),account_endpoints=len(results),results=results,chronological=folds,walk_forward=wf,demonstrated_65pct_strategy=False,approved_for_trading=False)
    P.dump(out/'results.json',result);P.status(out,stage='completed',demonstrated_65pct_strategy=False);return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True);p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();run(a.lake,a.out)
