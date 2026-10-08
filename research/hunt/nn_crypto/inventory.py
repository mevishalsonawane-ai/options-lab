import sys,os,glob;sys.path.append('/root/.local/lib/python3.11/site-packages')
import pandas as pd
D='/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/nn_crypto/data'
for p in sorted(glob.glob(D+'/*.parquet')):
    d=pd.read_parquet(p)
    tc=[c for c in ('t','ts','time','timestamp','T','date','snap') if c in d.columns]
    rng=''
    if tc:
        c=tc[0]; v=d[c]
        if c=='t': v=v*60
        if c in('ts','timestamp'): v=v/1000 if v.max()>1e11 else v
        if c=='date': rng=f"{v.min()}..{v.max()}"
        else:
            v=pd.to_numeric(v); rng=f"{pd.to_datetime(v.min(),unit='s'):%Y-%m-%d}..{pd.to_datetime(v.max(),unit='s'):%Y-%m-%d}"
    print(f"{os.path.basename(p):42s} {len(d):>9d} rows {os.path.getsize(p)/1e6:7.1f} MB  {rng}")
