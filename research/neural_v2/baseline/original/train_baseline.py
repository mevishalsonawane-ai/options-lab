import numpy as np, pandas as pd, torch, json
from pathlib import Path
from flat_parquet import read_parquet
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import balanced_accuracy_score, accuracy_score
p=Path('/mnt/data'); torch.manual_seed(42); torch.set_num_threads(2)
frames=[]
for sym in ['NIFTY','BANKNIFTY','INDIAVIX']:
 d=pd.concat([read_parquet(p/f'index_1m_{sym}_{y}.parquet.bin',['ts','close']) for y in [2025,2026]]).drop_duplicates('ts').set_index('ts').sort_index();frames.append(d.close.rename(sym))
d=pd.concat(frames,axis=1,join='inner').dropna();d=d.loc['2025-10-20':'2026-10-19'];g=d.groupby(d.index.date);f={}
for s in d:
 for k in [1,5,15,30]:f[f'{s}_{k}']=g[s].pct_change(k,fill_method=None)
 f[f'{s}_vol']=g[s].transform(lambda x:x.pct_change(fill_method=None).rolling(15).std())
f['time']=(d.index.hour*60+d.index.minute-555)/375
x=pd.DataFrame(f,index=d.index); future=g.NIFTY.shift(-15)/d.NIFTY-1; x['target']=np.where(future.notna(),(future>0).astype(float),np.nan);x=x.replace([np.inf,-np.inf],np.nan).dropna(); dates=np.unique(x.index.date); a,b=dates[int(len(dates)*.7)],dates[int(len(dates)*.85)]; masks=[x.index.date<a,(x.index.date>=a)&(x.index.date<b),x.index.date>=b]; X=x.drop(columns='target');y=x.target;sc=StandardScaler().fit(X[masks[0]]); sets=[(torch.tensor(sc.transform(X[m]),dtype=torch.float32),torch.tensor(y[m].values,dtype=torch.float32)) for m in masks]; model=torch.nn.Sequential(torch.nn.Linear(X.shape[1],64),torch.nn.ReLU(),torch.nn.Linear(64,32),torch.nn.ReLU(),torch.nn.Linear(32,16),torch.nn.ReLU(),torch.nn.Linear(16,1)); opt=torch.optim.Adam(model.parameters(),lr=.001); loss=torch.nn.BCEWithLogitsLoss(); history=[]; best=float('inf'); state=None
for ep in range(8):
 model.train(); xx,yy=sets[0]
 for ix in torch.randperm(len(xx)).split(512):
  opt.zero_grad(); l=loss(model(xx[ix]).flatten(),yy[ix]);l.backward(); opt.step()
 model.eval()
 with torch.no_grad():
  vx,vy=sets[1]; vl=loss(model(vx).flatten(),vy).item()
 history.append({'epoch':ep+1,'validation_loss':vl}); print(history[-1],flush=True)
 if vl<best:best=vl;state={k:v.clone() for k,v in model.state_dict().items()}
model.load_state_dict(state);tx,ty=sets[2]
with torch.no_grad():pred=(model(tx).flatten()>0).numpy()
r={'rows':[len(s[0]) for s in sets],'split_dates':[str(a),str(b)],'epochs':history,'test_accuracy':accuracy_score(ty.numpy(),pred),'test_balanced_accuracy':balanced_accuracy_score(ty.numpy(),pred),'test_majority_baseline':max(float(ty.mean()),1-float(ty.mean()))};print(r);out=Path(__file__).parent; (out/'baseline_metrics.json').write_text(json.dumps(r,indent=2));torch.save({'model':state,'features':list(X.columns),'mean':sc.mean_,'scale':sc.scale_},out/'baseline.pt')
