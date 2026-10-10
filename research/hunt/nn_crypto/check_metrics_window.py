import sys;sys.path.insert(0,'/home/user/options-lab/research/hunt/nn_crypto');sys.path.append('/root/.local/lib/python3.11/site-packages')
import pandas as pd,numpy as np
from common import DATA
m=pd.read_parquet(DATA+'/bn_metrics.parquet'); m=m[m.sym=='BTCUSDT'].sort_values('ts')
k=pd.read_parquet(DATA+'/bn_perp_BTCUSDT_1m.parquet')
k['w']=k.t//5
g=k.groupby('w').agg(v=('v','sum'),tbv=('tbv','sum'),c=('c','last'),o=('o','first'))
g['tbr']=np.log(g.tbv/(g.v-g.tbv))   # buy/sell vol ratio
g['r']=np.log(g.c/g.o)
m['w']=m.ts//300
m['ltak']=np.log(m.takr.astype(float)); m['loi']=np.log(m.oi.astype(float)); m['doi']=m.loi.diff()
m['year']=pd.to_datetime(m.ts,unit='s').dt.year
for lag,name in ((-2,'window ending 5m before ts'),(-1,'window [ts-5m,ts)'),(0,'window [ts,ts+5m)'),(1,'[ts+5,ts+10)')):
    x=m.merge(g[['tbr','r']].rename(lambda c:c,axis=1),left_on=m.w+lag,right_index=True,how='left')
    print(name, x.groupby('year').apply(lambda d: round(d.ltak.corr(d.tbr),3)).to_dict(), 'doi~r', x.groupby('year').apply(lambda d: round(d.doi.corr(d.r),3)).to_dict())
print(m.groupby('year').ts.apply(lambda s:(s%300).value_counts().head(3).to_dict()))
