"""Frozen payoff forecasts, causal gates, independent account stress replay."""
import argparse
import json
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score,brier_score_loss,log_loss
from research.neural_v2 import pipeline as P
from research.confluence65 import engine as E,robustness as R
from research.audit_v3.direction import save_forecasts
from . import data as D,models as M


def metrics(frame):
    d=frame.dropna(subset=['actual_net']);y=(d.actual_net>0).to_numpy(int);p=d.p_win.to_numpy(float)
    if not len(d):return dict(samples=0)
    bins=[]
    for lo,hi in [(0,.4),(.4,.5),(.5,.6),(.6,.65),(.65,.7),(.7,1.000001)]:
        mask=(p>=lo)&(p<hi)
        if mask.any():bins.append(dict(lower=lo,upper=min(hi,1.),samples=int(mask.sum()),predicted=float(p[mask].mean()),observed=float(y[mask].mean())))
    return dict(samples=len(d),observed_win_rate=float(y.mean()),auc=float(roc_auc_score(y,p)) if len(np.unique(y))==2 else None,
        brier=float(brier_score_loss(y,p)),log_loss=float(log_loss(y,np.clip(p,1e-7,1-1e-7),labels=[0,1])),
        net_mae=float(np.abs(d.predicted_net-d.actual_net).mean()),calibration_bins=bins)


def forecast(x,meta,audit,out):
    pieces=[];folds=[];y=meta.actual_net.to_numpy(float)
    for fold,(start,end) in enumerate([('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')],1):
        days=np.array(sorted(meta.loc[meta.day<start,'day'].unique()));a,b=int(.7*len(days)),int(.85*len(days))
        if a<10 or b<=a or b>=len(days):raise ValueError('Insufficient chronological history')
        good=np.isfinite(y)
        tr=np.flatnonzero((meta.day<days[a])&good);va=np.flatnonzero((meta.day>=days[a])&(meta.day<days[b])&good)
        ca=np.flatnonzero((meta.day>=days[b])&(meta.day<start)&good);te=np.flatnonzero((meta.day>=start)&(meta.day<=end))
        if not len(te):continue
        cutoff=str(pd.Timestamp(days[-1]+' 15:30',tz='Asia/Kolkata'))
        folds.append(dict(fold=fold,fit_last_day=days[a-1],validation_start=days[a],calibration_start=days[b],training_label_cutoff=cutoff,evaluation_start=start,evaluation_last_day=meta.day.iloc[te[-1]],fit_rows=len(tr),validation_rows=len(va),calibration_rows=len(ca),evaluation_rows=len(te)))
        for kind in ('linear','trees','mlp'):
            p,net=M.fit(x.to_numpy(float),y,tr,va,ca,kind,out/f'fold{fold}_{kind}',audit['fingerprint'],out)
            d=meta.iloc[te].copy();d['p_win']=p[te];d['predicted_net']=net[te];d['model']=kind;d['fold']=fold;d['training_label_cutoff']=cutoff
            pieces.append(d);print('forecast',fold,kind,len(d),'rows',flush=True)
    result=pd.concat(pieces,ignore_index=True);save_forecasts(result,out/'forecasts.npz')
    P.dump(out/'folds.json',folds);P.dump(out/'candidate_metrics.json',{kind:metrics(g) for kind,g in result.groupby('model')})
    return result


def candidates(frame,kind,p_min=.65,net_min=0):
    if kind not in ('linear','trees','mlp','baseline_call','baseline_put','baseline_momentum'):raise ValueError('Unknown policy')
    decisions={}
    for (day,minute),g in frame.groupby(['day','minute'],sort=True):
        stamp=pd.Timestamp(g.available_at.iloc[0])
        expected=pd.Timestamp(day,tz='Asia/Kolkata')+pd.Timedelta(minutes=int(minute))
        if stamp.tzinfo is None or stamp!=expected or not g.available_at.eq(g.available_at.iloc[0]).all():raise ValueError('Forecast timestamp mismatch')
        if kind.startswith('baseline_'):
            side='C' if kind=='baseline_call' else 'P' if kind=='baseline_put' else g.momentum_side.iloc[0]
            allowed=g[g.side==side]
        else:
            if not g.model.eq(kind).all():raise ValueError('Wrong model forecast')
            stamp=pd.Timestamp(g.available_at.iloc[0]);cut=pd.to_datetime(g.training_label_cutoff,utc=True)
            if cut.isna().any() or (cut>=stamp).any():raise ValueError('Training cutoff reaches forecast time')
            allowed=g[(g.p_win>=p_min)&(g.predicted_net>=net_min)&np.isfinite(g.p_win)&np.isfinite(g.predicted_net)]
        if allowed.empty:continue
        chosen=allowed.sort_values(['predicted_net','side'],ascending=[False,True]).iloc[0]
        strike=float(chosen.strike);decisions[day,int(minute)]=(chosen.side,strike if np.isfinite(strike) else None)
    return decisions


def replay(sessions,decisions,stress):
    ready=[dict(day=s['day'],expiry=s['expiry'],book=s['book'],candidates={m:a for (day,m),a in decisions.items() if day==s['day']}) for s in sessions]
    original=E.exit_fill
    try:
        E.exit_fill=D.horizon_exit;ledger,summary=E.replay(ready,stress)
    finally:E.exit_fill=original
    summary.pop('_state',None);days=[s['day'] for s in sessions]
    summary['wilson95']=R.wilson(summary['wins'],summary['trades']);summary['block_p_value']=R.block_pvalue(ledger,days)
    summary['monte_carlo']=R.monte_carlo(ledger,days)
    return ledger,summary


def hindsight(meta):
    net=meta.pivot(index=['day','minute'],columns='side',values='actual_net');gross=meta.pivot(index=['day','minute'],columns='side',values='actual_gross')
    pair=net.dropna();index=meta.drop_duplicates(['day','minute']).set_index(['day','minute']).index_up.reindex(pair.index)
    best=pair.max(axis=1);g=gross.reindex(pair.index)
    dirnet=np.where(index==1,pair.C,np.where(index==0,pair.P,np.nan));good=np.isfinite(dirnet)
    return dict(nontradable=True,paired_observed_decisions=len(pair),missing_one_or_both=int(len(net)-len(pair)),
        actual_best_option_positive_share=float((best>0).mean()),best_option_mean_net=float(best.mean()),
        either_option_gross_positive_share=float((g.max(axis=1)>0).mean()),
        perfect_endpoint_direction_win_rate=float((dirnet[good]>0).mean()) if good.any() else None,
        perfect_endpoint_direction_mean_net=float(dirnet[good].mean()) if good.any() else None,
        note='Both diagnostics use future outcomes and cannot be traded. Observed paired-quote cohort only; no cash/risk admission or day caps. A selective strategy may exceed this always-on opportunity fraction, so it is not a universal ceiling.')


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);P.status(out,stage='ingestion',errors=[])
    x,meta,sessions,audit=D.build(lake,out);P.dump(out/'hindsight_diagnostics.json',hindsight(meta))
    f=forecast(x,meta,audit,out);sessions=[s for s in sessions if s['day']>=f.day.min()]
    policies=[(kind,p,net) for kind in ('linear','trees','mlp') for p in (.60,.65,.70) for net in (0,100,200)]
    policies += [(kind,0,0) for kind in ('baseline_call','baseline_put','baseline_momentum')]
    results={};folds={}
    for i,(kind,p,net) in enumerate(policies):
        name=kind+f'__p{p:.2f}__net{net}';g=f[f.model==('linear' if kind.startswith('baseline_') else kind)]
        decisions=candidates(g,kind,p,net)
        for stress in (1.,2.):
            ledger,summary=replay(sessions,decisions,stress);key=name+'__x'+str(stress);results[key]=summary
            ledger.to_csv(out/(key+'.csv'),index=False)
            folds[key]={}
            # Diagnostic window accounts restart; full account above does not.
            for label,a,b in [('q2','2026-04-01','2026-06-30'),('q3','2026-07-01','2026-12-31')]:
                subset=[s for s in sessions if a<=s['day']<=b]
                subdec={k:v for k,v in decisions.items() if a<=k[0]<=b}
                # Avoid repeating Monte Carlo for diagnostic restarts.
                original=E.exit_fill
                try:
                    E.exit_fill=D.horizon_exit
                    l,s=E.replay([dict(day=s['day'],expiry=s['expiry'],book=s['book'],candidates={m:v for (d,m),v in subdec.items() if d==s['day']}) for s in subset],stress)
                finally:E.exit_fill=original
                s.pop('_state',None);folds[key][label]=s
        P.dump(out/'partial_results.json',dict(results=results,chronological=folds))
        P.status(out,stage='backtesting',completed_policies=i+1,total_policies=len(policies))
        print('replayed',i+1,'/',len(policies),name,flush=True)
    q=P.bh([s['block_p_value'] for s in results.values()])
    for s,value in zip(results.values(),q):s['bh_q']=float(value)
    result=dict(audit=audit,results=results,chronological=folds,fresh_holdout=False,demonstrated_65pct_strategy=False,approved_for_trading=False)
    P.dump(out/'results.json',result);P.status(out,stage='completed',demonstrated_65pct_strategy=False)
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True);p.add_argument('--out',type=Path,default=Path(__file__).parent/'results');a=p.parse_args();run(a.lake,a.out)
