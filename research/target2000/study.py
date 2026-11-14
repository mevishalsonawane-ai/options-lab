"""Whole-lot Rs2000 target forecasts, walk-forward evaluation, full cost replay."""
import argparse
import json
import zipfile
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score,brier_score_loss
from research.neural_v2 import pipeline as P
from research.confluence65 import robustness as R
from research.audit_v3.direction import save_forecasts
from . import data as D,models as M,execution as E


def metrics(frame):
    d=frame[np.isfinite(frame.actual_net)];result={'samples':len(d)}
    for name,pcol,threshold in [('target','p_target',2000-1e-6),('win','p_win',1e-12)]:
        y=(d.actual_net>=threshold).to_numpy(int);p=d[pcol].to_numpy(float)
        result[name]=dict(prevalence=float(y.mean()),mean_prediction=float(p.mean()),
            auc=float(roc_auc_score(y,p)) if len(np.unique(y))==2 else None,
            brier=float(brier_score_loss(y,p)),constant_prevalence_brier=float(np.mean((y-y.mean())**2)),
            bins=[dict(low=lo,high=hi,n=int(mask.sum()),predicted=float(p[mask].mean()),actual=float(y[mask].mean()))
                for lo,hi in [(0,.25),(.25,.4),(.4,.55),(.55,.6),(.6,.65),(.65,1.000001)]
                if (mask:=((p>=lo)&(p<hi))).any()])
    result['net_mae']=float((d.predicted_net-d.actual_net).abs().mean())
    return result


def probability_audit(frame):
    return {kind:dict(rows=len(g),target_exceeds_win=int((g.p_target>g.p_win).sum()),
        maximum_win_probability=float(g.p_win.max()),maximum_predicted_net=float(g.predicted_net.max()),
        weakest_frozen_gate_rows=int(((g.p_win>=.55)&(g.p_target>=.25)&(g.predicted_net>=0)).sum()))
        for kind,g in frame.groupby('model')}


def forecast(x,meta,audit,out):
    pieces=[];folds=[];y=meta.actual_net.to_numpy(float)
    for fold,(start,end) in enumerate([('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')],1):
        days=np.array(sorted(meta.loc[meta.day<start,'day'].unique()));a,b=int(.7*len(days)),int(.85*len(days))
        if a<10 or b<=a or b>=len(days):raise ValueError('Insufficient chronological history')
        good=np.isfinite(y)
        tr=np.flatnonzero((meta.day<days[a])&good)
        va=np.flatnonzero((meta.day>=days[a])&(meta.day<days[b])&good)
        ca=np.flatnonzero((meta.day>=days[b])&(meta.day<start)&good)
        te=np.flatnonzero((meta.day>=start)&(meta.day<=end))
        cutoff=days[-1]+' 15:30:00+05:30'
        folds.append(dict(fold=fold,fit_last_day=days[a-1],validation_start=days[a],calibration_start=days[b],
            training_label_cutoff=cutoff,evaluation_start=start,fit_rows=len(tr),validation_rows=len(va),calibration_rows=len(ca),evaluation_rows=len(te)))
        for kind in ('linear','dense','hybrid'):
            target,win,net=M.fit(x,y,tr,va,ca,kind,out,fold,audit['fingerprint'])
            d=meta.iloc[te].copy();d['p_target']=target[te];d['p_win']=win[te];d['predicted_net']=net[te]
            d['model']=kind;d['fold']=fold;d['training_label_cutoff']=cutoff;pieces.append(d)
            save_forecasts(d,out/f'fold{fold}_{kind}_forecasts.npz')
            print('forecast',fold,kind,len(d),flush=True)
    frame=pd.concat(pieces,ignore_index=True);save_forecasts(frame,out/'forecasts.npz');P.dump(out/'folds.json',folds)
    P.dump(out/'candidate_metrics.json',{kind:metrics(g) for kind,g in frame.groupby('model')})
    return frame


def decisions(frame,p_min,net_min,ungated=False,momentum=False):
    result={}
    for (day,minute),g in frame.groupby(['day','minute'],sort=True):
        stamp=pd.Timestamp(day,tz='Asia/Kolkata')+pd.Timedelta(minutes=int(minute))
        if not (pd.to_datetime(g.available_at,utc=True)==stamp).all():raise ValueError('Forecast time mismatch')
        cut=pd.to_datetime(g.training_label_cutoff,utc=True)
        if cut.isna().any() or (cut>=stamp).any():raise ValueError('Future training cutoff')
        if momentum:g=g[g.side==g.momentum_side]
        elif not ungated:g=g[(g.p_win>=p_min)&(g.p_target>=.25)&(g.predicted_net>=net_min)]
        g=g[np.isfinite(g.strike)&np.isfinite(g.predicted_net)&np.isfinite(g.p_win)&np.isfinite(g.p_target)]
        if g.empty:continue
        chosen=g.sort_values(['predicted_net','side'],ascending=[False,True]).iloc[0]
        result[day,int(minute)]=(chosen.side,float(chosen.strike))
    return result


def write_report(out,result):
    rows=result['accounts'];lines=['# Whole-lot Rs2000 neural target study','',
        '**Research only. No dependable65% profitable strategy or Rs2000-per-trade expectancy established.**','',
        f'Inputs: {result["audit"]["start"]}–{result["audit"]["end"]}; {result["audit"]["sequence_rows"]} prospective sequence candidates. Training uses earlier dates; account evaluation starts April2026.',
        'One whole historical NIFTY lot per position, Rs100000 account. Fixed next-open contract; target +Rs2000 NET at normal modeled costs, planned stop about-Rs1500, maximum30minutes. Tick rounding and gaps change realized payoff. Stress preserves identical premium barriers, doubles modeled friction and independently replays cash/risk eligibility.',
        'Neural dense64/32 and causal CNN32→GRU32→attention→dense32; hybrid averages seeds42/73. Each has target probability, win probability and net payoff heads. Two expanding chronological folds; prior-only fit/validation/calibration. Logistic/ridge and momentum comparisons included.',
        'The 2000-rupee objective is a winning-trade target, not a promised profit or a guaranteed average over losses. No fractional lots or profit scaling. Every date was previously researched, so these are development results rather than an untouched final test.','',
        '| Policy | Trades | Win % | Rs2000 reaches | Avg winner Rs | All-trade avg Rs | Net Rs | Stress net Rs | DD Rs | BH q |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for key,a in rows.items():
        if not key.endswith('_x1.0'):continue
        b=rows[key.replace('_x1.0','_x2.0')]
        win=f'{100*a["win_rate"]:.2f}' if a['trades'] else 'undefined'
        avg=f'{a["average_net_win"]:.2f}' if a['average_net_win'] is not None else 'undefined'
        lines.append(f'| {key[:-5]} | {a["trades"]} | {win} | {a["target_2000_reaches"]} | {avg} | {a["expectancy"]:.2f} | {a["net"]:.2f} | {b["net"]:.2f} | {a["max_drawdown"]:.2f} | {a["bh_q"]:.3f} |')
    lines+=['','## Candidate prediction quality','',
        'Candidate metrics describe overlapping proposed trades before account admission, not executed-trade performance. The constant-prevalence Brier is a hindsight diagnostic, not a fitted trading strategy.',
        '```json',json.dumps(result['candidate_metrics'],indent=2),'```','',
        '## Decision and limitations','',f'Historically qualifying policies: {result["qualifying_historical"]}.',
        'A policy needs100executed trades,65%net wins,positive normal/stress net and existing risk gates to qualify even retrospectively. Fresh forward evidence is still required. Empty accounts have undefined win rates and cannot be an edge.',
        'A Rs2000 winning target is below Rs2000 average expectancy whenever some trades lose or close earlier. We report all-trade mean separately; no success claim from the target amount alone.',
        'Each full account carries across both outer windows; fold-ledger slices in results.json retain this account path. No best outer threshold or seed is selected.',
        '1000closed-trade permutations and1000five-day block resamples per endpoint; fixed rupee reordering does not replay cash gates. BH covers62endpoints within this batch only, not all prior project trials.',
        'Only six five-minute state snapshots are modeled. True10second, executable depth, aggressor delta and full intrabar paths are unavailable. Signed-volume and delta inputs are proxies. Option state snapshots may select different strikes over time; held positions never roll strike.',
        'Labels are standalone trade outcomes; account gates can force earlier exits. Missing labels mask fitting only; candidates with unavailable future outcomes remain forecast rows. Unpriceable held terminal contracts abort replay.',
        'Calibration uses earlier data and does not establish future calibration. Structural regime changes, inferred expiry mapping and modeled fills remain limitations. No production changes or real orders.','',
        'The two probability heads are calibrated independently and are not constrained jointly. probability_audit.json records any target-probability>win-probability inconsistencies, plus gate admission counts; these are diagnostics, not post-result corrections to forecasts.', '',
        '## Reproduce','', '```bash','python -m research.target2000.study --lake /absolute/path/to/lake',
        'python -m pytest research/target2000 -q','```','',
        'See ../PREREG.md and ../README.md. Model architecture/checkpoints/scalers/calibration are in models.zip;62full ledgers in ledgers.zip; all outer forecasts in forecasts.npz. Data hashes in data_audit.json.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n')


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);P.status(out,stage='ingestion',errors=[])
    x,meta,sessions,audit=D.build(lake,out)
    f=forecast(x,meta,audit,out);P.dump(out/'probability_audit.json',probability_audit(f))
    sessions=[s for s in sessions if s['day']>=f.day.min()]
    specs=[(k,p,n,False,False) for k in ('linear','dense','hybrid') for p in (.55,.60,.65) for n in (0,250,500)]
    specs += [(k,0,0,True,False) for k in ('linear','dense','hybrid')]+[('linear',0,0,False,True)]
    accounts={};folds={};payloads={}
    for i,(kind,p,n,ungated,momentum) in enumerate(specs):
        label='momentum' if momentum else kind+'_ungated' if ungated else kind+f'_p{p:.2f}_net{n}'
        chosen=decisions(f[f.model==kind],p,n,ungated,momentum)
        for stress in (1.,2.):
            ledger,s,_=E.replay(sessions,chosen,stress);key=label+f'_x{stress}'
            days=[v['day'] for v in sessions]
            s['wilson95']=R.wilson(s['wins'],s['trades']);s['block_p_value']=R.block_pvalue(ledger,days)
            s['monte_carlo']=R.monte_carlo(ledger,days);accounts[key]=s
            payloads[key+'.csv']=ledger.to_csv(index=False).encode()
            folds[key]={}
            for a,b in [('2026-04-01','2026-06-30'),('2026-07-01','2026-12-31')]:
                d=ledger[(ledger.day>=a)&(ledger.day<=b)]
                folds[key][a]=dict(basis='slice of continuous executed ledger',trades=len(d),wins=int((d.net>0).sum()),net=float(d.net.sum()))
        P.dump(out/'partial_results.json',dict(accounts=accounts,chronological=folds))
        P.status(out,stage='backtesting',completed_policies=i+1,total_policies=len(specs))
        print('replayed',i+1,'/',len(specs),label,flush=True)
    for s,q in zip(accounts.values(),P.bh([s['block_p_value'] for s in accounts.values()])):s['bh_q']=float(q)
    qualified=[k[:-5] for k,s in accounts.items() if k.endswith('_x1.0') and R.qualifies(s,accounts[k.replace('_x1.0','_x2.0')])]
    result=dict(audit=audit,accounts=accounts,chronological=folds,qualifying_historical=qualified,
        candidate_metrics=json.loads((out/'candidate_metrics.json').read_text()),
        approved_for_trading=False,fresh_holdout=False,whole_lots_only=True)
    for filename,files in [('ledgers.zip',payloads),('models.zip',{p.name:p.read_bytes() for p in (out/'models').iterdir() if p.is_file()})]:
        with zipfile.ZipFile(out/filename,'w',zipfile.ZIP_DEFLATED) as z:
            for name,data in sorted(files.items()):
                info=zipfile.ZipInfo(name,(2026,11,15,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
        with zipfile.ZipFile(out/filename) as z:
            assert set(z.namelist())==set(files)
            for name,data in files.items():assert z.read(name)==data
    P.dump(out/'results.json',result);write_report(out,result)
    P.status(out,stage='completed',approved_for_trading=False)
    print('COMPLETE',len(accounts),'account endpoints; qualifying:',qualified,flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lake',type=Path,required=True)
    parser.add_argument('--out',type=Path,default=Path(__file__).parent/'results');args=parser.parse_args()
    try:run(args.lake,args.out)
    except Exception as exc:
        P.status(args.out,stage='failed',errors=[repr(exc)]);raise
