import sys;sys.path.insert(0,'/home/user/options-lab/research/hunt/nn_crypto');sys.path.append('/root/.local/lib/python3.11/site-packages')
import pandas as pd,numpy as np, warnings; warnings.filterwarnings('ignore')
from common import DATA
for sym in ('BTCUSDT','ETHUSDT'):
    m=pd.read_parquet(DATA+'/bn_metrics.parquet'); m=m[m.sym==sym].sort_values('ts')
    k=pd.read_parquet(DATA+f'/bn_perp_{sym}_1m.parquet'); k['w']=k.t//5
    g=k.groupby('w').agg(v=('v','sum'),tbv=('tbv','sum'))
    g['tbr']=np.log(g.tbv/(g.v-g.tbv))
    m['w']=m.ts//300; m['ltak']=np.log(m.takr.astype(float)); m['day']=pd.to_datetime(m.ts,unit='s').dt.date
    a=m.merge(g[['tbr']],left_on=m.w-1,right_index=True,how='left')
    b=m.merge(g[['tbr']],left_on=m.w,right_index=True,how='left')
    d=pd.DataFrame({'end':a.groupby('day').apply(lambda x:x.ltak.corr(x.tbr)),'start':b.groupby('day').apply(lambda x:x.ltak.corr(x.tbr))})
    d['conv']=np.where(d.start>d.end,'start','end')
    ch=d.conv.ne(d.conv.shift())
    print(sym); print(d[ch].round(3).to_string()[:3000])
