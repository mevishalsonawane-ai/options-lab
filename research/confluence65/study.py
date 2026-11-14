"""Predefined confluence candidates; persist all failures, no order API."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from research.neural_v2 import pipeline as P
from . import engine as E,features as F,robustness as R


def rule_name(rule):
    family,context,target,lookback,stop=rule
    return f'{family}__{context}__t{target:.2f}__l{lookback}__s{stop:.2f}'


def build(lake,out):
    hashes={};indices={}
    for sym in ('NIFTY','BANKNIFTY','INDIAVIX'):
        paths=sorted(lake.glob(f'index_1m_{sym}_*.parquet'))
        if not paths:raise ValueError('Missing index '+sym)
        for p in paths:hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        d=pd.concat([pd.read_parquet(p) for p in paths]).sort_values('ts')
        if d.ts.duplicated().any() or d.ts.dt.tz is None:raise ValueError('Invalid index timestamps')
        d.ts=d.ts.dt.tz_convert('Asia/Kolkata');indices[sym]=d.set_index('ts')
    ix=indices['NIFTY'][['open','high','low','close']].join(indices['INDIAVIX'].close.rename('INDIAVIX'),how='inner').dropna()
    end=ix.index.max().normalize();start=end-pd.DateOffset(years=1)+pd.Timedelta(days=1)
    option_paths=sorted(lake.glob('opt_NIFTY_WEEK_*.parquet'))
    if not option_paths:raise ValueError('Missing options')
    options=[]
    for p in option_paths:
        hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest();g=pd.read_parquet(p)
        g.ts=g.ts.dt.tz_convert('Asia/Kolkata');options.append(g[(g.ts>=start)&(g.ts<end+pd.Timedelta(days=1))])
    quotes=pd.concat(options);quotes['side']=quotes.side.astype(str)
    if quotes.duplicated(['ts','strike','side']).any():raise ValueError('Duplicate quotes')
    fp=lake/'fut_1m_live_index.parquet'
    if not fp.exists():raise ValueError('Missing observed futures volume')
    hashes[fp.name]=hashlib.sha256(fp.read_bytes()).hexdigest();fut=pd.read_parquet(fp)
    fut=fut[fut.symbol=='NIFTY'].copy();fut.ts=fut.ts.dt.tz_convert('Asia/Kolkata');fut['day']=fut.ts.dt.date
    optdays={day:g for day,g in quotes.groupby(quotes.ts.dt.date)}
    futdays={day:g for day,g in fut.groupby('day')}
    ex=pd.read_csv(P.HERE.parent/'brain/calendar_data/expiries.csv')
    observed=set(pd.to_datetime(ex.loc[ex.und=='NIFTY','date']).dt.date)
    sessions=[];previous=None;futures_sessions=0;coverage={'quotes':len(quotes),'positive_iv_fraction':float((quotes.iv>0).mean())}
    for day,d in ix.groupby(ix.index.date):
        if pd.Timestamp(day).tz_localize('Asia/Kolkata')<start:
            if len(d)>=300:previous=dict(high=float(d.high.max()),low=float(d.low.min()))
            continue
        if day not in optdays:raise ValueError('Missing option session '+str(day))
        g=optdays[day].copy();g['minute']=g.ts.dt.hour*60+g.ts.dt.minute
        expiry,source=P.expiry(day,observed);chain=F.chain_facts(g,d,expiry)
        future=pd.DataFrame(np.nan,index=d.index,columns=['f_close','f_rvol','f_vwap','f_poc'])
        if day in futdays:
            fd=futdays[day];available=fd[pd.to_datetime(fd.expiry).dt.date>=day]
            if len(available):
                chosen=available.expiry.min();f=available[available.expiry==chosen].sort_values('ts').set_index('ts')
                future=F.futures_facts(f).reindex(d.index);futures_sessions+=1
        masks={}
        for context in F.CONTEXTS:
            for side in (1,2):masks[context,side]=F.gate(d,chain,future,pd.Series(side,index=d.index),context).eq(side).to_numpy()
        raw={}
        for lb in (16,20,24):
            for family,values in F.raw_signals(d,previous,lb).items():raw[lb,family]=values.to_numpy(np.int8)
        book={(int(r.minute),float(r.strike),r.side):(r.open,r.high,r.low,r.close,r.volume) for r in g.itertuples()}
        picks={(int(r.minute)+1,r.side):float(r.strike) for r in g[((g.side=='C')&(g.offset==-1))|((g.side=='P')&(g.offset==1))].itertuples()}
        sessions.append(dict(day=str(day),expiry=str(expiry),expiry_source=source,book=book,picks=picks,
            minutes=(d.index.hour*60+d.index.minute+1).to_numpy(),raw=raw,masks=masks,eligible_expiry=1<=(expiry-day).days<=7))
        if len(d)>=300:previous=dict(high=float(d.high.max()),low=float(d.low.min()))
        else:previous=None
        if len(sessions)%50==0:print('features',len(sessions),'sessions',flush=True)
    audit=dict(start=str(start.date()),end=str(end.date()),sessions=len(sessions),files=hashes,
        futures_sessions=futures_sessions,coverage=coverage,fresh_data=False,
        limitations=['No aggressor-side delta or resting order book','Reported IV zero/missing excluded from Greek gates',
            'Futures nearest AVAILABLE expiry can be far month; volume profile assigns all minute volume to typical price',
            'Index volume not treated as executable futures volume','Expiry reconstructed from scheduled/observed dates'])
    P.dump(out/'audit.json',audit);return sessions,audit


def adapt(sessions,rule):
    family,context,target,lookback,stop=rule;out=[]
    for s in sessions:
        raw=s['raw'][lookback,family];keep=((raw==1)&s['masks'][context,1])|((raw==2)&s['masks'][context,2])
        candidates={int(m):('C' if side==1 else 'P',s['picks'].get((int(m),'C' if side==1 else 'P')))
            for m,side in zip(s['minutes'][keep],raw[keep])} if s['eligible_expiry'] else {}
        out.append(dict(day=s['day'],expiry=s['expiry'],book=s['book'],candidates=candidates))
    return out


def evaluate(sessions,rule,stress):
    ready=adapt(sessions,rule);ledger,summary=E.replay(ready,stress,stop_pct=rule[4],target_pct=rule[2])
    summary.pop('_state',None);summary['wilson95']=R.wilson(summary['wins'],summary['trades'])
    summary['block_p_value']=R.block_pvalue(ledger,[s['day'] for s in sessions])
    return ledger,summary


def walk_forward(sessions,rules,out):
    decisions=[];all_ledgers={};fold_results={};days=[s['day'] for s in sessions if s['day']>='2026-04-01']
    states={1.:None,2.:None};pieces={1.:[],2.:[]}
    for a,b in [('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')]:
        train=[s for s in sessions if s['day']<a];test=[s for s in sessions if a<=s['day']<=b]
        scores={}
        for rule in rules:
            normal=evaluate(train,rule,1.)[1];stress=evaluate(train,rule,2.)[1]
            scores[rule_name(rule)]=(normal,stress)
        chosen=R.select(scores);lookup={rule_name(r):r for r in rules}
        decisions.append(dict(evaluation_start=a,evaluation_end=test[-1]['day'] if test else None,
            training_end=train[-1]['day'] if train else None,selected=chosen or 'NO_TRADE',
            qualified=sum(R.qualifies(*s) for s in scores.values())))
        P.dump(out/f'walk_forward_training_{a}.json',scores)
        for stress in (1.,2.):
            ready=adapt(test,lookup[chosen]) if chosen else [dict(day=s['day'],expiry=s['expiry'],book=s['book'],candidates={}) for s in test]
            rule=lookup[chosen] if chosen else ('','','',20,.15)
            ledger,summary=E.replay(ready,stress,stop_pct=rule[4],target_pct=rule[2] if chosen else .3,state=states[stress])
            states[stress]=summary.pop('_state');pieces[stress].append(ledger);fold_results[a+'_'+str(stress)]=summary
    for stress in (1.,2.):
        ledger=pd.concat(pieces[stress],ignore_index=True);ledger.to_csv(out/f'walk_forward_{stress}.csv',index=False)
        all_ledgers[str(stress)]=ledger
    return dict(decisions=decisions,fold_results=fold_results,
        monte_carlo={k:R.monte_carlo(g,days) for k,g in all_ledgers.items()},
        normal_net=float(all_ledgers['1.0'].net.sum()),
        account_state=states)


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);sessions,audit=build(lake,out)
    rules=[(family,context,target,20,.15) for family in F.FAMILIES for context in F.CONTEXTS for target in (.30,.10)]
    train=[s for s in sessions if s['day']<='2026-03-31'];scores={}
    for rule in rules:scores[rule_name(rule)]=(evaluate(train,rule,1.)[1],evaluate(train,rule,2.)[1])
    # Descriptive leader only; an unqualified leader is never silently traded by WF.
    eligible=[k for k in scores if scores[k][0]['trades']>=30]
    if not eligible:eligible=[max(sorted(scores),key=lambda k:scores[k][0]['trades'])]
    leader=max(sorted(eligible),key=lambda k:scores[k][1]['net']);lookup={rule_name(r):r for r in rules};chosen=lookup[leader]
    P.dump(out/'design_selection.json',dict(scores=scores,descriptive_leader=leader,qualified=R.select(scores)))
    print('design leader frozen:',leader,'passing:',R.select(scores),flush=True)
    extra=[(*chosen[:3],lb,stop) for lb in (16,20,24) for stop in (.12,.15,.18)]
    unique=list(dict.fromkeys(rules+extra));full={};folds={};leader_ledgers={};sensitivity={}
    for i,rule in enumerate(unique):
        name=rule_name(rule)
        for stress in (1.,2.):
            ledger,summary=evaluate(sessions,rule,stress);key=name+'__x'+str(stress);full[key]=summary
            if name==leader:
                ledger.to_csv(out/f'leader_{stress}.csv',index=False);leader_ledgers[str(stress)]=ledger
            foldsummary={}
            for label,a,b in [('design','2025-01-01','2026-03-31'),('q2','2026-04-01','2026-06-30'),('q3','2026-07-01','2026-12-31')]:
                subset=[s for s in sessions if a<=s['day']<=b];foldsummary[label]=evaluate(subset,rule,stress)[1]
            folds[key]=foldsummary
            if rule in extra:sensitivity[key]=dict(lookback=rule[3],stop=rule[4],stress=stress,**summary)
        P.dump(out/'progress.json',dict(stage='replaying',completed_rules=i+1,total_rules=len(unique)))
        print('replayed',i+1,'/',len(unique),name,flush=True)
    qs=P.bh([s['block_p_value'] for s in full.values()])
    for s,q in zip(full.values(),qs):s['bh_q']=float(q)
    wf=walk_forward(sessions,rules,out)
    # No result on this reused history can by itself grant forward/paper approval.
    result=dict(audit=audit,primary_rule_count=len(unique),full=full,chronological=folds,sensitivity=sensitivity,
        design_leader=leader,walk_forward=wf,
        leader_monte_carlo={k:R.monte_carlo(g,[s['day'] for s in sessions]) for k,g in leader_ledgers.items()},
        demonstrated_65pct_strategy=False,approved_for_trading=False,
        explanation='Reused development history, no fresh proof. Check all gates, sampling uncertainty, stressed net and stability; never stop testing only because a pleasing number appears.')
    P.dump(out/'results.json',result);P.dump(out/'progress.json',dict(stage='completed',completed_rules=len(unique),total_rules=len(unique)))
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True);p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();run(a.lake,a.out)
