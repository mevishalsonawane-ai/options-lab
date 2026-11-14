"""Causal, calibrated underlying-direction forecasts; no option-profit labels."""
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.optimize import minimize
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score,balanced_accuracy_score,roc_auc_score,log_loss,brier_score_loss
import torch
from torch import nn

from research.neural_v2 import pipeline as P

HORIZONS=(5,15,30)


def save_forecasts(frame,path):
    arrays={c:(frame[c].to_numpy().astype(str) if frame[c].dtype==object else frame[c].to_numpy()) for c in frame}
    np.savez_compressed(path,**arrays)


def load_forecasts(path):
    with np.load(path,allow_pickle=False) as arrays:
        return pd.DataFrame({k:arrays[k] for k in arrays.files})


def features(day,previous,instrument):
    own=day[instrument];other=day['BANKNIFTY' if instrument=='NIFTY' else 'NIFTY'];vix=day.INDIAVIX
    out={}
    for label,values in [('self',own),('other',other),('vix',vix)]:
        for k in (1,5,15,30):out[f'{label}_ret{k}']=values.pct_change(k,fill_method=None)
        out[label+'_vol15']=values.pct_change(fill_method=None).rolling(15).std()
    high=day[instrument+'_high'];low=day[instrument+'_low']
    out['range']=(high-low)/own
    out['atr14']=pd.concat([high-low,(high-own.shift()).abs(),(low-own.shift()).abs()],axis=1).max(axis=1).rolling(14).mean()/own
    out['ema_gap']=(own.ewm(span=9,adjust=False).mean()-own.ewm(span=21,adjust=False).mean())/own
    out['from_open']=own/own.iloc[0]-1
    out['gap']=own.iloc[0]/previous['close']-1
    out['from_previous_high']=own/previous['high']-1
    out['from_previous_low']=own/previous['low']-1
    out['from_session_high']=own/high.cummax()-1
    out['from_session_low']=own/low.cummin()-1
    minute=day.index.hour*60+day.index.minute-555
    out['time']=minute/375
    out['nifty']=float(instrument=='NIFTY')
    return pd.DataFrame(out,index=day.index)


def elapsed_targets(close):
    targets=[]
    for h in HORIZONS:
        future=close.reindex(close.index+pd.Timedelta(minutes=h)).to_numpy()
        ret=future/close.to_numpy()-1
        targets.append(np.where(np.isfinite(ret)&(ret!=0),(ret>0).astype(float),np.nan))
    return np.stack(targets,axis=1)


def build(lake,out):
    series={};hashes={}
    for sym in ('NIFTY','BANKNIFTY','INDIAVIX'):
        paths=sorted(lake.glob(f'index_1m_{sym}_*.parquet'))
        if not paths:raise ValueError('Missing '+sym)
        for p in paths:hashes[p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
        d=pd.concat([pd.read_parquet(p) for p in paths]).sort_values('ts')
        if d.ts.duplicated().any() or d.ts.dt.tz is None:raise ValueError('Invalid timestamps')
        d.ts=d.ts.dt.tz_convert('Asia/Kolkata');series[sym]=d.set_index('ts')
    ix=pd.concat([d.close.rename(sym) for sym,d in series.items()],axis=1,join='inner').dropna()
    for sym in ('NIFTY','BANKNIFTY'):
        ix=ix.join(series[sym][['high','low']].rename(columns={c:sym+'_'+c for c in ('high','low')}))
    end=ix.index.max().normalize();start=end-pd.DateOffset(years=1)+pd.Timedelta(days=1)
    xs=[];ys=[];rows=[];names=None;previous={};missing_context=0
    for day,d in ix.groupby(ix.index.date):
        if pd.Timestamp(day).tz_localize('Asia/Kolkata')>=start:
            for sym in ('NIFTY','BANKNIFTY'):
                if sym not in previous:missing_context+=1;continue
                f=features(d,previous[sym],sym);names=list(f.columns);target=elapsed_targets(d[sym])
                for j,t in enumerate(d.index):
                    m=t.hour*60+t.minute+1
                    if j<59 or m%5 or not 615<=m<=885:continue
                    if (np.diff(d.index[j-59:j+1].asi8)!=60_000_000_000).any():continue
                    x=f.iloc[j-29:j+1].to_numpy(dtype=np.float32)
                    if not np.isfinite(x).all():continue
                    xs.append(x);ys.append(target[j]);rows.append(dict(instrument=sym,bar_ts=str(t),
                        available_at=str(t+pd.Timedelta(minutes=1)),day=str(day)))
        for sym in ('NIFTY','BANKNIFTY'):
            previous[sym]=dict(close=float(d[sym].iloc[-1]),high=float(d[sym+'_high'].max()),low=float(d[sym+'_low'].min()))
    if not rows:raise ValueError('No causal samples')
    meta=pd.DataFrame(rows);order=meta.sort_values(['available_at','instrument']).index.to_numpy()
    x=np.stack(xs)[order];y=np.stack(ys)[order];meta=meta.iloc[order].reset_index(drop=True)
    fingerprint=hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()+x.tobytes()+np.nan_to_num(y,nan=-1).tobytes()).hexdigest()
    audit=dict(files=hashes,start=str(start),end=str(end),samples=len(x),features=names,
        horizons=list(HORIZONS),masked_targets=np.isnan(y).sum(0).tolist(),missing_previous_session_context=missing_context,
        fingerprint=fingerprint,labels='Underlying future close at exact elapsed timestamp; ties/missing outcomes masked per head. Forecast coverage does not depend on future labels.')
    P.dump(out/'data_audit.json',audit)
    return x,y,meta,audit


class Network(nn.Module):
    def __init__(self,n,kind):
        super().__init__();self.kind=kind
        if kind=='mlp':
            self.encoder=nn.Sequential(nn.Linear(n,64),nn.ReLU(),nn.Dropout(.2),nn.Linear(64,32),nn.ReLU(),nn.Dropout(.2),nn.Linear(32,16),nn.ReLU())
            width=16
        elif kind=='gru':
            self.conv=nn.Conv1d(n,32,3);self.rnn=nn.GRU(32,32,batch_first=True);self.attn=nn.Linear(32,1);self.drop=nn.Dropout(.2);width=32
        else:raise ValueError('Unknown architecture')
        self.head=nn.Linear(width,3)
    def forward(self,x):
        if self.kind=='mlp':z=self.encoder(x[:,-1])
        else:
            z=torch.relu(self.conv(nn.functional.pad(x.transpose(1,2),(2,0)))).transpose(1,2)
            z,_=self.rnn(z);z=self.drop((z*torch.softmax(self.attn(z),dim=1)).sum(1))
        return self.head(z)


def masked_loss(logits,y):
    mask=torch.isfinite(y)
    if not mask.any():raise ValueError('No observed targets')
    loss=nn.functional.binary_cross_entropy_with_logits(logits,torch.nan_to_num(y),reduction='none')
    return loss[mask].mean()


def fit(x,y,tr,va,kind,path,fingerprint,out,fold,max_epochs=20,patience=4):
    torch.manual_seed(42)
    code=''.join(inspect.getsource(f) for f in (features,elapsed_targets,build,Network,masked_loss,fit))
    config=dict(kind=kind,features=x.shape[-1],max_epochs=max_epochs,patience=patience,
        lr=.001,weight_decay=.001,seed=42,code_sha256=hashlib.sha256(code.encode()).hexdigest())
    mean=x[tr].mean((0,1));scale=x[tr].std((0,1)).clip(.000001)
    z=torch.tensor(np.clip((x-mean)/scale,-10,10),dtype=torch.float32);yy=torch.tensor(y,dtype=torch.float32)
    model=Network(x.shape[-1],kind);opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.001)
    ck=dict(epoch=0,best=None,stale=0,history=[],mean=mean,scale=scale,config=config,fingerprint=fingerprint)
    if path.exists():
        ck=torch.load(path,weights_only=False,map_location='cpu')
        if ck['config']!=config or ck['fingerprint']!=fingerprint:raise ValueError('Checkpoint mismatch')
        model.load_state_dict(ck['model']);opt.load_state_dict(ck['optimizer']);torch.set_rng_state(ck['rng'])
    for ep in range(ck['epoch'],max_epochs):
        if ck['stale']>=patience:break
        model.train()
        for ids in torch.tensor(tr)[torch.randperm(len(tr))].split(256):
            opt.zero_grad();loss=masked_loss(model(z[ids]),yy[ids]);loss.backward();nn.utils.clip_grad_norm_(model.parameters(),1);opt.step()
        model.eval()
        with torch.no_grad():vl=float(masked_loss(model(z[va]),yy[va]))
        if not np.isfinite(vl):raise ValueError('Nonfinite validation')
        ck['history'].append(dict(epoch=ep+1,validation_loss=vl));ck['epoch']=ep+1
        if ck['best'] is None or vl<ck['best']:
            ck['best']=vl;ck['best_model']={k:v.detach().clone() for k,v in model.state_dict().items()};ck['stale']=0
        else:ck['stale']+=1
        ck.update(model=model.state_dict(),optimizer=opt.state_dict(),rng=torch.get_rng_state())
        tmp=path.with_suffix('.tmp');torch.save(ck,tmp);tmp.replace(path)
        P.status(out,stage='direction_training',fold=fold,architecture=kind,epochs_completed=ep+1,validation_loss=vl,last_successful_checkpoint=path.name)
        print(f'fold{fold} {kind} epoch{ep+1} loss={vl:.6f}',flush=True)
    model.load_state_dict(ck['best_model']);model.eval()
    with torch.no_grad():logits=model(z).numpy()
    return logits,ck


def calibrate(logits,y,indices):
    params=[];prob=np.empty_like(logits,dtype=float)
    for k in range(3):
        good=indices[np.isfinite(y[indices,k])];z=logits[good,k];target=y[good,k]
        if len(good)<50 or len(np.unique(target))<2:a,b=0.,0.
        else:
            def objective(v):
                q=z/np.exp(v[0])+v[1]
                return np.mean(np.logaddexp(0,q)-target*q)
            result=minimize(objective,[0.,0.],method='L-BFGS-B',bounds=[(-3,3),(-3,3)])
            a,b=result.x if result.success else (0.,0.)
        q=np.clip(logits[:,k]/np.exp(a)+b,-40,40);prob[:,k]=1/(1+np.exp(-q))
        params.append(dict(log_temperature=float(a),intercept=float(b),samples=len(good)))
    return prob,params


def metrics(y,p,base,days,baseline_probability=None):
    good=np.isfinite(y);y=y[good].astype(int);p=np.clip(p[good],1e-7,1-1e-7);base=base[good];days=np.asarray(days)[good]
    if not len(y):return dict(samples=0)
    predicted=p>=.5;conf=np.maximum(p,1-p)>=.55
    daily=pd.DataFrame(dict(day=days,delta=(predicted==y).astype(float)-(base==y).astype(float))).groupby('day').delta.mean().to_numpy()
    rng=np.random.default_rng(42);centered=daily-daily.mean()
    pval=float((1+(rng.choice(centered,(2000,len(daily)),replace=True).mean(1)>=daily.mean()).sum())/2001)
    calibration=[];ece=0.
    for lo in np.arange(0,1,.1):
        mask=(p>=lo)&(p<lo+.1)
        if mask.any():
            confidence=float(p[mask].mean());observed=float(y[mask].mean());ece+=mask.mean()*abs(confidence-observed)
            calibration.append(dict(lower=float(lo),samples=int(mask.sum()),predicted_up=confidence,observed_up=observed))
    baseline_loss=None
    if baseline_probability is not None:
        bp=np.clip(np.asarray(baseline_probability)[good],1e-7,1-1e-7)
        baseline_loss=float(log_loss(y,bp,labels=[0,1]))
    return dict(samples=len(y),accuracy=float(accuracy_score(y,predicted)),balanced_accuracy=float(balanced_accuracy_score(y,predicted)),
        majority_accuracy=float((base==y).mean()),auc=float(roc_auc_score(y,p)) if len(np.unique(y))==2 else None,
        log_loss=float(log_loss(y,p,labels=[0,1])),baseline_log_loss=baseline_loss,brier=float(brier_score_loss(y,p)),ece=float(ece),
        confident_samples=int(conf.sum()),confident_coverage=float(conf.mean()),confident_accuracy=float((predicted[conf]==y[conf]).mean()) if conf.any() else None,
        day_clustered_accuracy_gain=float(daily.mean()),p_value=pval,calibration_bins=calibration)


def run(lake,out):
    out.mkdir(parents=True,exist_ok=True);torch.set_num_threads(2);P.status(out,stage='direction_ingestion',errors=[])
    x,y,meta,audit=build(lake,out);days=np.array(sorted(meta.day.unique()));edges=[int(len(days)*f) for f in (.5,.65,.8,1.)]
    forecasts=[];folds=[]
    for fold,(a,b) in enumerate(zip(edges[:-1],edges[1:]),1):
        v,c=int(a*.6),int(a*.8)
        tr=np.flatnonzero(meta.day<days[v]);va=np.flatnonzero((meta.day>=days[v])&(meta.day<days[c]));ca=np.flatnonzero((meta.day>=days[c])&(meta.day<days[a]));te=np.flatnonzero(meta.day.isin(days[a:b]))
        cutoff=str(pd.Timestamp(meta.available_at.iloc[ca[-1]])+pd.Timedelta(minutes=30))
        folds.append(dict(fold=fold,fit_end=days[v-1],validation_start=days[v],calibration_start=days[c],evaluation_start=days[a],evaluation_end=days[b-1],training_label_cutoff=cutoff))
        frequency=np.nanmean(y[tr],axis=0);majority=(frequency>=.5).astype(int)
        mean=x[tr,-1].mean(0);scale=x[tr,-1].std(0).clip(.000001);last=np.clip((x[:,-1]-mean)/scale,-10,10)
        logits=np.empty_like(y)
        for k in range(3):
            good=tr[np.isfinite(y[tr,k])];lr=LogisticRegression(C=.1,max_iter=500,random_state=42).fit(last[good],y[good,k])
            logits[:,k]=lr.decision_function(last)
        candidates={'logistic':logits}
        for kind in ('mlp','gru'):
            candidates[kind],ck=fit(x,y,tr,va,kind,out/f'fold{fold}_{kind}.pt',audit['fingerprint'],out,fold)
            folds[-1][kind+'_epochs']=ck['epoch']
        for kind,logits in candidates.items():
            pro,params=calibrate(logits,y,ca);P.dump(out/f'fold{fold}_{kind}_calibration.json',params)
            for i in te:
                row=meta.iloc[i].to_dict();row.update(model=kind,fold=fold,training_label_cutoff=cutoff)
                for k,h in enumerate(HORIZONS):row.update({f'p_up_{h}':float(pro[i,k]),f'actual_up_{h}':None if not np.isfinite(y[i,k]) else float(y[i,k]),f'majority_{h}':int(majority[k]),f'base_up_{h}':float(frequency[k])})
                forecasts.append(row)
    frame=pd.DataFrame(forecasts);save_forecasts(frame,out/'forecasts.npz')
    results=[]
    for (kind,sym),g in frame.groupby(['model','instrument']):
        for h in HORIZONS:
            m=metrics(g[f'actual_up_{h}'].to_numpy(float),g[f'p_up_{h}'].to_numpy(),g[f'majority_{h}'].to_numpy(),g.day.to_numpy(),g[f'base_up_{h}'].to_numpy())
            m.update(model=kind,instrument=sym,horizon=h);results.append(m)
    q=P.bh([r['p_value'] for r in results])
    for r,value in zip(results,q):r['bh_q']=float(value)
    P.dump(out/'results.json',dict(folds=folds,metrics=results,fresh_test=False,approved_for_trading=False))
    lines=['# Underlying-direction development results','','Predictions are calibrated on prior data and evaluated on later sessions. Historical periods were previously seen; this is not fresh proof.',
        '', '| Instrument | Model | Minutes | N | Accuracy | Majority | Balanced | AUC | Confident coverage | Confident accuracy | BH q |',
        '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for r in results:
        ca='n/a' if r['confident_accuracy'] is None else f"{r['confident_accuracy']*100:.2f}%"
        lines.append(f"| {r['instrument']} | {r['model']} | {r['horizon']} | {r['samples']} | {r['accuracy']*100:.2f}% | {r['majority_accuracy']*100:.2f}% | {r['balanced_accuracy']*100:.2f}% | {r['auc']:.3f} | {r['confident_coverage']*100:.1f}% | {ca} | {r['bh_q']:.3f} |")
    lines+=['','Accuracy predicts the sign of an underlying return; it is not an option-trade win rate. Exact ties/missing outcomes are masked only for scoring and loss; all valid feature rows still receive forecasts.',
        'Features exclude option prices, OI/IV and unverifiable volume/VWAP. Magnitude and direction are separate tasks. No all-data model enters evaluation.',
        'The 55% gate is fixed in advance. Calibration is monotone and fitted on its own preceding window. High-confidence subsets may be tiny; lower coverage is not a guaranteed edge.',
        'Full calibration bins, Brier/log loss, clustered tests and cutoffs are in results.json. BH covers 18 model/instrument/horizon tests, not the entire historically reused research program. No trade/paper/live approval.']
    (out/'REPORT.md').write_text('\n'.join(lines)+'\n');P.status(out,stage='direction_completed',fresh_test=False,approved_for_trading=False)
    return frame,results


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--lake',type=Path,required=True);p.add_argument('--out',type=Path,default=Path(__file__).parent/'direction_results');a=p.parse_args();run(a.lake,a.out)
