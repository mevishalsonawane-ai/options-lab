"""h4 diagnostic (POST-HOC, labelled as such): Liquidity 15+5 alone on BANKNIFTY + FINNIFTY + MIDCPNIFTY, 1 lot each."""
import sys; sys.path.append('/root/.local/lib/python3.11/site-packages'); sys.path.insert(0,'/home/user/options-lab/research/hunt/h4')
sys.argv=['x']
sys.path.insert(0,'/home/user/options-lab/research')
import obuy
import portfolio as PF
ns=vars(PF)
import pandas as pd, numpy as np
H=PF.IN
HOLD=pd.Timestamp('2025-10-01')
keys=['liq_BN','liq_FIN','liq_MIDCP']
SH={"BANKNIFTY":"BN","FINNIFTY":"FIN","MIDCPNIFTY":"MIDCP","NIFTY":"NIFTY","SENSEX":"SENSEX"}
res={}
for e in ('gross','app','real'):
    t=pd.read_parquet(f'{H}/trades_{e}.parquet'); t=t[t.comp.isin(['liq_bnfin','liq_ext'])]; t['key']='liq_'+t.und.map(SH); t=t[t.key.isin(keys)]
    res[e]=t
D0=pd.read_csv(f'{H}/daily_real.csv',index_col=0,parse_dates=True)
cal=D0.index
def dly(t): return t.groupby('day').net.sum().reindex(cal,fill_value=0.0)
start=pd.Timestamp('2023-06-01')   # all three live
for e,t in res.items():
    x=dly(t); pre=x[(x.index>=start)&(x.index<HOLD)]; ho=x[x.index>=HOLD]
    print(e,'pre/day %.0f hold/day %.0f'%(pre.mean(),ho.mean()))
t=res['real']; x=dly(t); pre=x[(x.index>=start)&(x.index<HOLD)]; ho=x[x.index>=HOLD]
k=5000/pre.mean(); print('k',k)
r=ns['stats_row'](pre.values*k,pre.index); r['plm']=ns['p_losing_month'](pre.values*k); print('PRE',{a:((round(b,3) if abs(b)<10 else round(b)) if isinstance(b,float) else b) for a,b in r.items()})
for e in ('gross','app','real'):
    xx=dly(res[e]); hh=xx[xx.index>=HOLD]*k
    r=ns['stats_row'](hh.values,hh.index); print('HOLD',e,{a:((round(b,3) if abs(b)<10 else round(b)) if isinstance(b,float) else b) for a,b in r.items()})
print('per-year real 1 unit:', x.groupby(x.index.year).sum().round(0).to_dict())
print('per-index per year real:', t.groupby([t.day.dt.year,'key']).net.sum().unstack().round(0))
O=sum(ns['outlay'](res['real'][res['real'].key==kk],cal) for kk in keys)*k
print('outlay p95 %.0f max %.0f'%(O[O>0].quantile(.95),O.max()))
print('median entry premium x qty per lot', t.groupby('key').apply(lambda g:(g.entry*g.qty).median()).round(0).to_dict(), t.groupby('key').entry.median().to_dict(), t.groupby('key').qty.median().to_dict())
print('trades/day', len(t[(t.day>=start)])/len(x[x.index>=start]))
