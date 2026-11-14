"""Train-only scaling, causal CNN/GRU attention, earlier-only calibration."""
import hashlib
from pathlib import Path
import joblib
import numpy as np
import torch
from torch import nn
from sklearn.linear_model import LogisticRegression, Ridge
from threadpoolctl import threadpool_limits
from research.payoff20 import models as B
from research.neural_v2 import pipeline as P


def scale(x,tr):
    n,t,f=x.shape;flat=x.reshape(n*t,f);fit=(tr[:,None]*t+np.arange(t)).ravel()
    z,params=B.scale(flat,fit)
    return z.reshape(n,t,-1),params


class Network(nn.Module):
    def __init__(self,features,kind):
        super().__init__();self.kind=kind
        if kind=='dense':
            self.body=nn.Sequential(nn.Linear(features,64),nn.ReLU(),nn.Dropout(.2),nn.Linear(64,32),nn.ReLU(),nn.Dropout(.2),nn.Linear(32,3))
        elif kind=='hybrid':
            self.conv=nn.Conv1d(features,32,3);self.gru=nn.GRU(32,32,batch_first=True)
            self.attention=nn.Linear(32,1)
            self.head=nn.Sequential(nn.Linear(32,32),nn.ReLU(),nn.Dropout(.2),nn.Linear(32,3))
        else:raise ValueError('Unknown neural architecture')
    def forward(self,x):
        if self.kind=='dense':return self.body(x[:,-1])
        z=nn.functional.pad(x.transpose(1,2),(2,0))
        z=torch.relu(self.conv(z)).transpose(1,2);z,_=self.gru(z)
        weight=torch.softmax(self.attention(z),dim=1)
        return self.head((z*weight).sum(dim=1))


def loss(pred,y):
    return (nn.functional.binary_cross_entropy_with_logits(pred[:,0],(y>=2000-1e-6).float())+
        nn.functional.binary_cross_entropy_with_logits(pred[:,1],(y>0).float())+
        .5*nn.functional.huber_loss(pred[:,2],y/2000))


def train(z,y,tr,va,kind,seed,path,signature,out,max_epochs=25):
    torch.manual_seed(seed);torch.set_num_threads(2)
    model=Network(z.shape[-1],kind);opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.001)
    xx=torch.tensor(z);yy=torch.tensor(np.nan_to_num(y),dtype=torch.float32)
    ck=dict(epoch=0,stale=0,best=None,history=[],signature=signature,kind=kind,seed=seed,input_features=z.shape[-1])
    if path.exists():
        ck=torch.load(path,weights_only=False,map_location='cpu')
        if ck['signature']!=signature:raise ValueError('Checkpoint mismatch')
        model.load_state_dict(ck['model']);opt.load_state_dict(ck['optimizer']);torch.set_rng_state(ck['rng'])
    for epoch in range(ck['epoch'],max_epochs):
        if ck['stale']>=5:break
        model.train()
        for ids in torch.tensor(tr)[torch.randperm(len(tr))].split(256):
            opt.zero_grad();v=loss(model(xx[ids]),yy[ids]);v.backward();nn.utils.clip_grad_norm_(model.parameters(),1);opt.step()
        model.eval()
        with torch.no_grad():v=float(loss(model(xx[va]),yy[va]))
        if not np.isfinite(v):raise ValueError('Nonfinite loss')
        ck['epoch']=epoch+1;ck['history'].append(dict(epoch=epoch+1,validation_loss=v))
        if ck['best'] is None or v<ck['best']:
            ck['best']=v;ck['best_model']={k:t.detach().clone() for k,t in model.state_dict().items()};ck['stale']=0
        else:ck['stale']+=1
        ck.update(model=model.state_dict(),optimizer=opt.state_dict(),rng=torch.get_rng_state())
        tmp=path.with_suffix('.tmp');torch.save(ck,tmp);tmp.replace(path)
        P.status(out,stage='training',architecture=kind,seed=seed,epochs_completed=epoch+1,
                 validation_loss=v,last_successful_checkpoint=str(path.relative_to(out)))
        print(path.stem,'epoch',epoch+1,'val',round(v,5),flush=True)
    model.load_state_dict(ck['best_model']);model.eval()
    with torch.no_grad():raw=torch.cat([model(batch) for batch in xx.split(1024)]).numpy()
    return raw,dict(epochs=ck['epoch'],best_validation_loss=ck['best'],history=ck['history'])


@threadpool_limits.wrap(limits=2)
def fit(x,y,tr,va,ca,kind,out,fold,fingerprint):
    z,params=scale(x,tr);dest=out/'models';dest.mkdir(exist_ok=True)
    signature=hashlib.sha256((fingerprint+Path(__file__).read_text()+kind+repr(tr.tolist())+repr(va.tolist())+repr(ca.tolist())).encode()).hexdigest()
    histories={}
    if kind=='linear':
        target=LogisticRegression(C=.1,max_iter=500,random_state=42).fit(z[tr,-1],y[tr]>=2000-1e-6)
        win=LogisticRegression(C=.1,max_iter=500,random_state=42).fit(z[tr,-1],y[tr]>0)
        reg=Ridge(alpha=100).fit(z[tr,-1],y[tr]/2000)
        raw=np.column_stack([target.decision_function(z[:,-1]),win.decision_function(z[:,-1]),reg.predict(z[:,-1])])
        joblib.dump(dict(target=target,win=win,regressor=reg,scaler=params,signature=signature),dest/f'fold{fold}_linear.joblib')
    else:
        predictions=[]
        for seed in ((42,) if kind=='dense' else (42,73)):
            path=dest/f'fold{fold}_{kind}_{seed}.pt'
            pred,history=train(z,y,tr,va,kind,seed,path,signature+str(seed),out)
            predictions.append(pred);histories[str(seed)]=history
        raw=np.mean(predictions,axis=0)
    target,tc=B.calibrate(raw[:,0],np.where(y>=2000-1e-6,1.,-1.),ca)
    win,wc=B.calibrate(raw[:,1],y,ca)
    P.dump(dest/f'fold{fold}_{kind}.json',dict(signature=signature,kind=kind,
        scaler={k:v.tolist() for k,v in params.items()},target_calibration=tc,win_calibration=wc,
        histories=histories,ensemble='average hybrid raw logits and net before calibration; no seed selection'))
    return target,win,raw[:,2]*2000
