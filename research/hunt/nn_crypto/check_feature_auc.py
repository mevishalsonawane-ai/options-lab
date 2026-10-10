import sys;sys.path.insert(0,'/home/user/options-lab/research/hunt/nn_crypto');sys.path.append('/root/.local/lib/python3.11/site-packages')
import pandas as pd,numpy as np
from sklearn.metrics import roc_auc_score
from model import load, STATIC
f,ch=load()
for yr in (2021,2023,2025):
    g=f[(pd.to_datetime(f["T"],unit="s").dt.year==yr)]
    out={}
    for c in STATIC+["c_r","c_tb","c_lv","c_rng"]:
        x=g[c].values; ok=np.isfinite(x)
        if ok.sum()<1000: continue
        out[c]=[roc_auc_score(g[f"d{h}"].values[ok],x[ok]) for h in (15,60,240)]
    s=pd.DataFrame(out,index=["a15","a60","a240"]).T
    s["dev"]=(s.a60-0.5).abs()
    print(yr);print(s.sort_values("dev",ascending=False).head(8).round(4))
