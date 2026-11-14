"""Earlier-only fitting, epoch selection and probability calibration."""
import hashlib
import json
from pathlib import Path
import joblib
from threadpoolctl import threadpool_limits
import numpy as np
import torch
from torch import nn
from scipy.optimize import minimize
from scipy.special import expit
from sklearn.linear_model import LogisticRegression,Ridge
from sklearn.ensemble import HistGradientBoostingClassifier,HistGradientBoostingRegressor
from research.neural_v2 import pipeline as P


def scale(x,tr):
    with np.errstate(invalid='ignore'):
        median=np.array([np.nanmedian(v) if np.isfinite(v).any() else 0. for v in x[tr].T])
    a=np.concatenate([np.where(np.isfinite(x),x,median),(~np.isfinite(x)).astype(float)],axis=1)
    mean=a[tr].mean(0);std=a[tr].std(0).clip(1e-6)
    return np.clip((a-mean)/std,-10,10).astype(np.float32),dict(median=median,mean=mean,std=std)


class Network(nn.Module):
    def __init__(self,n):
        super().__init__();self.body=nn.Sequential(nn.Linear(n,64),nn.ReLU(),nn.Dropout(.2),nn.Linear(64,32),nn.ReLU(),nn.Dropout(.2),nn.Linear(32,16),nn.ReLU(),nn.Linear(16,2))
    def forward(self,x):return self.body(x)


def loss(pred,y):
    return nn.functional.binary_cross_entropy_with_logits(pred[:,0],(y>0).float())+.25*nn.functional.huber_loss(pred[:,1],y/1000)


def fit_neural(z,y,tr,va,path,signature,out,max_epochs=20):
    torch.manual_seed(42);torch.set_num_threads(2);model=Network(z.shape[1]);opt=torch.optim.AdamW(model.parameters(),lr=.001,weight_decay=.001)
    zz=torch.tensor(z);yy=torch.tensor(y,dtype=torch.float32)
    ck=dict(epoch=0,best=None,stale=0,history=[],signature=signature)
    if path.exists():
        ck=torch.load(path,weights_only=False,map_location='cpu')
        if ck['signature']!=signature:raise ValueError('Checkpoint mismatch')
        model.load_state_dict(ck['model']);opt.load_state_dict(ck['optimizer']);torch.set_rng_state(ck['rng'])
    for epoch in range(ck['epoch'],max_epochs):
        if ck['stale']>=4:break
        model.train()
        for ids in torch.tensor(tr)[torch.randperm(len(tr))].split(256):
            opt.zero_grad();v=loss(model(zz[ids]),yy[ids]);v.backward();nn.utils.clip_grad_norm_(model.parameters(),1);opt.step()
        model.eval()
        with torch.no_grad():v=float(loss(model(zz[va]),yy[va]))
        if not np.isfinite(v):raise ValueError('Nonfinite validation loss')
        ck['epoch']=epoch+1;ck['history'].append(dict(epoch=epoch+1,validation_loss=v))
        if ck['best'] is None or v<ck['best']:
            ck['best']=v;ck['best_model']={k:a.detach().clone() for k,a in model.state_dict().items()};ck['stale']=0
        else:ck['stale']+=1
        ck.update(model=model.state_dict(),optimizer=opt.state_dict(),rng=torch.get_rng_state())
        tmp=path.with_suffix('.tmp');torch.save(ck,tmp);tmp.replace(path)
        P.status(out,stage='training',architecture='mlp',epochs_completed=epoch+1,validation_loss=v,last_successful_checkpoint=path.name)
    model.load_state_dict(ck['best_model']);model.eval()
    with torch.no_grad():pred=model(zz).numpy()
    return pred[:,0],pred[:,1]*1000,ck


def calibrate(logits,y,ca):
    if len(ca)<50 or len(np.unique(y[ca]>0))<2:raise ValueError('Insufficient calibration sample')
    target=(y[ca]>0).astype(float);v=logits[ca]
    def objective(par):
        q=v/np.exp(par[0])+par[1]
        return float(np.mean(np.logaddexp(0,q)-target*q))
    result=minimize(objective,[0.,0.],method='L-BFGS-B',bounds=[(-3,3),(-3,3)])
    if not result.success:raise ValueError('Calibration failed')
    a,b=result.x;return expit(np.clip(logits/np.exp(a)+b,-40,40)),dict(log_temperature=float(a),intercept=float(b),samples=len(ca))


@threadpool_limits.wrap(limits=2)
def fit(x,y,tr,va,ca,kind,path,fingerprint,out):
    z,params=scale(x,tr)
    code=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    signature=hashlib.sha256((fingerprint+code+kind+repr(tr.tolist())+repr(va.tolist())).encode()).hexdigest()
    if kind=='linear':
        cls=LogisticRegression(C=.1,max_iter=500,random_state=42).fit(z[tr],y[tr]>0)
        reg=Ridge(alpha=100).fit(z[tr],y[tr]/1000)
        logits=cls.decision_function(z);net=reg.predict(z)*1000
        joblib.dump(dict(classifier=cls,regressor=reg,scaler=params,signature=signature),path.with_suffix('.joblib'))
    elif kind=='trees':
        cls=HistGradientBoostingClassifier(max_iter=100,max_depth=3,max_leaf_nodes=7,learning_rate=.05,l2_regularization=.1,random_state=42,early_stopping=False).fit(z[tr],y[tr]>0)
        reg=HistGradientBoostingRegressor(max_iter=100,max_depth=3,max_leaf_nodes=7,learning_rate=.05,l2_regularization=.1,random_state=42,early_stopping=False).fit(z[tr],y[tr]/1000)
        p=np.clip(cls.predict_proba(z)[:,1],1e-7,1-1e-7);logits=np.log(p/(1-p));net=reg.predict(z)*1000
        joblib.dump(dict(classifier=cls,regressor=reg,scaler=params,signature=signature),path.with_suffix('.joblib'))
    elif kind=='mlp':
        logits,net,ck=fit_neural(z,y,tr,va,path.with_suffix('.pt'),signature,out)
    else:raise ValueError('Unknown model')
    prob,cal=calibrate(logits,y,ca)
    P.dump(path.with_suffix('.json'),dict(kind=kind,signature=signature,scaler={k:v.tolist() for k,v in params.items()},calibration=cal))
    return prob,net
