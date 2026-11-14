"""Offline inference from trusted published checkpoints; no broker integration."""
import json
from pathlib import Path
import joblib
import numpy as np
import torch
from scipy.special import expit
from .models import Network


def predict(raw, kind, fold, model_directory):
    directory=Path(model_directory)
    metadata=json.loads((directory/f'fold{fold}_{kind}.json').read_text())
    scaler={k:np.asarray(v,float) for k,v in metadata['scaler'].items()}
    x=np.asarray(raw,float)
    if x.ndim!=3 or x.shape[1]!=6 or x.shape[2]!=len(scaler['median']):
        raise ValueError('Expected six snapshots in the saved audit feature order')
    a=np.concatenate([np.where(np.isfinite(x),x,scaler['median']),(~np.isfinite(x)).astype(float)],axis=-1)
    z=np.clip((a-scaler['mean'])/scaler['std'],-10,10).astype(np.float32)
    if kind=='linear':
        bundle=joblib.load(directory/f'fold{fold}_linear.joblib')
        raw_prediction=np.column_stack([bundle['target'].decision_function(z[:,-1]),
            bundle['win'].decision_function(z[:,-1]),bundle['regressor'].predict(z[:,-1])])
    elif kind in ('dense','hybrid'):
        values=[]
        for seed in ((42,) if kind=='dense' else (42,73)):
            ck=torch.load(directory/f'fold{fold}_{kind}_{seed}.pt',map_location='cpu',weights_only=False)
            model=Network(z.shape[-1],kind);model.load_state_dict(ck['best_model']);model.eval()
            with torch.no_grad():values.append(torch.cat([model(t) for t in torch.tensor(z).split(1024)]).numpy())
        raw_prediction=np.mean(values,axis=0)
    else:raise ValueError('Unknown model')
    target=metadata['target_calibration'];win=metadata['win_calibration']
    pt=expit(np.clip(raw_prediction[:,0]/np.exp(target['log_temperature'])+target['intercept'],-40,40))
    pw=expit(np.clip(raw_prediction[:,1]/np.exp(win['log_temperature'])+win['intercept'],-40,40))
    return pt,pw,raw_prediction[:,2]*2000
