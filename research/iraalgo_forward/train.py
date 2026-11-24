"""Run from repository root with python -m research.iraalgo_forward.train."""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
import torch
from torch import nn
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, balanced_accuracy_score, log_loss
from .dataset import build, EXITS
from .metrics import select, measure

FAMILIES = ['logistic','dense','gru','cnn']
FOLDS = [('2026-04-01','2026-07-01'),('2026-07-01','2026-09-01'),('2026-09-01','2026-10-21'),('2026-10-21','2026-11-14')]


def write(path, obj):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(obj,indent=1,allow_nan=False))
    tmp.replace(path)


class Network(nn.Module):
    def __init__(self, family, n):
        super().__init__()
        self.family = family
        if family == 'dense':
            self.encoder = nn.Sequential(nn.Flatten(),nn.Linear(20*n,32),nn.ReLU(),nn.Linear(32,16),nn.ReLU())
        elif family == 'cnn':
            self.encoder = nn.Sequential(nn.Conv1d(n,24,3),nn.ReLU(),nn.Conv1d(24,16,3),nn.ReLU(),nn.AdaptiveAvgPool1d(1),nn.Flatten())
        else:
            self.encoder = nn.GRU(n,16,batch_first=True)
        self.head = nn.Linear(16,1)

    def forward(self,x):
        if self.family=='gru':
            enc = self.encoder(x)[1][-1]
        else:
            enc = self.encoder(x.transpose(1,2) if self.family=='cnn' else x)
        return self.head(enc).flatten()


def fit(family, x, y, train, predict, out, tag, record, signature):
    torch.manual_seed(24)
    scale = x[train].reshape(-1,x.shape[-1])
    mean, std = scale.mean(0), np.maximum(scale.std(0),1e-6)
    z = np.clip((x-mean)/std,-8,8)
    if family=='logistic':
        m = LogisticRegression(max_iter=400,C=.05).fit(z[train].mean(1),y[train])
        joblib.dump(dict(model=m,mean=mean,std=std),out/(tag+'.joblib'))
        return m.predict_proba(z[predict].mean(1))[:,1]
    t = torch.as_tensor(z)
    target = torch.tensor(y,dtype=torch.float32)
    model = Network(family,x.shape[-1])
    opt = torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.001)
    loss = nn.BCEWithLogitsLoss()
    checkpoint = out/(tag+'.pt')
    start = 0
    if checkpoint.exists():
        saved = torch.load(checkpoint,weights_only=True)
        if saved['signature']!=signature: raise ValueError('Checkpoint input or code mismatch')
        model.load_state_dict(saved['model'])
        opt.load_state_dict(saved['optimizer'])
        start=saved['epoch']
    for epoch in range(start,8):
        ids = np.random.default_rng(24+epoch).permutation(train)
        total=0.
        for batch in np.array_split(ids, np.ceil(len(ids)/128).astype(int)):
            opt.zero_grad()
            l = loss(model(t[batch]),target[batch])
            l.backward()
            opt.step()
            total += l.item()*len(batch)
        tmp = checkpoint.with_suffix('.part')
        torch.save(dict(model=model.state_dict(),optimizer=opt.state_dict(),epoch=epoch+1,
                        mean=torch.tensor(mean),std=torch.tensor(std),family=family,signature=signature),tmp)
        tmp.replace(checkpoint)
        record(task='training',experiment=tag,epoch=epoch+1,train_loss=total/len(ids),checkpoint=checkpoint.name)
    with torch.no_grad():
        return torch.sigmoid(model(t[predict])).numpy()


def run(lake,cache,out):
    out.mkdir(parents=True,exist_ok=True)
    cache.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    def record(**data):
        data.update(utc=pd.Timestamp.now(tz='UTC').isoformat(),cpu_seconds=time.process_time(),
                    torch_threads=torch.get_num_threads(),gpu=torch.cuda.is_available())
        with (out/'events.jsonl').open('a') as fp: fp.write(json.dumps(data)+'\n')
        write(out/'status.json',data)
    sources = list(Path(__file__).parent.glob('*.py'))
    h=hashlib.sha256()
    for f in sorted(sources): h.update(f.read_bytes())
    for f in sorted(lake.glob('*.parquet')):
        if f.name.startswith(('index_1m_','opt_NIFTY_WEEK_')): h.update(f.read_bytes())
    signature=h.hexdigest()
    if not (cache/'fingerprint').exists() or (cache/'fingerprint').read_text()!=signature:
        record(task='building features')
        build(lake,cache)
        (cache/'fingerprint').write_text(signature)
    x=np.load(cache/'history.npy')
    meta=pd.read_parquet(cache/'candidates.parquet').reset_index(drop=True)
    audit=json.loads((cache/'audit.json').read_text())
    write(out/'audit.json',audit)
    y=meta.timed.gt(0).astype(int).to_numpy()
    folds={}; classification={}; winners={k:[] for k in FAMILIES}; assessments={}
    for i,(start,end) in enumerate(FOLDS):
        train=np.flatnonzero((meta.day<start)&meta.filled)
        pred=np.flatnonzero((meta.day>=start)&(meta.day<end))
        sessions=[d for d in audit['sessions'] if start<=d<end]
        if i==3:
            eligible={k:sum(v['net'] for v in vals) for k,vals in winners.items() if sum(v['trades'] for v in vals)>=40}
            champion=max(eligible,key=eligible.get) if eligible else 'no_trade'
            write(out/'frozen.json',dict(family=champion,walk_forward_net=eligible,threshold=.55,signature=signature))
        m=meta.iloc[pred]
        flows={'random':np.random.default_rng(24+i).random(len(m)),'momentum':m.momentum.to_numpy(),
               'no_trade':np.zeros(len(m))}
        folds[i]={};classification[i]={}
        variants=FAMILIES+(['dense_context_only'] if i<3 else [])
        for name in variants:
            family='dense' if name=='dense_context_only' else name
            inputs=x[:,:,:6] if name=='dense_context_only' else x
            probs=fit(family,inputs,y,train,pred,out,f'f{i+1}_{name}',record,signature)
            flows[name]=probs
            valid=m.filled.to_numpy()
            actual=y[pred][valid]
            classification[i][name]=dict(train_observations=len(train),eval_observations=int(valid.sum()),
                accuracy=float(accuracy_score(actual,probs[valid]>=.5)),balanced=float(balanced_accuracy_score(actual,probs[valid]>=.5)),
                majority=float(max(actual.mean(),1-actual.mean())),validation_log_loss=float(log_loss(actual,probs[valid])))
        for name,p in flows.items():
            gates=select(m,p,(-np.inf if name in ['random','momentum'] else .55))
            scores=measure(gates,'timed',sessions)
            scores['stress_net']=measure(gates,'timed_stress',sessions)['net']
            folds[i][name]=scores
            if i<3 and name in winners:winners[name].append(scores)
            if i==3 and name==champion:
                for mode in EXITS:
                    assessments[mode]=measure(gates,mode,sessions)
                    assessments[mode]['stress_net']=measure(gates,mode+'_stress',sessions)['net']
                for threshold in [.50,.60]:
                    assessments[f'threshold_{threshold}']=measure(select(m,p,threshold),'timed',sessions)
        write(out/'results.json',dict(folds=folds,classification=classification,exit_sensitivity=assessments))
        record(task='fold complete',fold=i+1,models=list(classification[i]),candidates=len(m))
    record(task='replay complete',selected=champion,holdout=folds[3][champion],completed_folds=4)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for key in ['lake','cache','out']:parser.add_argument('--'+key,required=True,type=Path)
    args=parser.parse_args()
    run(args.lake,args.cache,args.out)
