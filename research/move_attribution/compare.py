"""Time/range-standardized comparisons and inference clustered by session."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.stats import norm
from scipy.stats import false_discovery_control
from .events import LEVELS, FLAGS


def contrast(frame, direction, flag):
    name=('up_' if direction==1 else 'down_')+flag
    g=frame.loc[frame[name].notna()].copy()
    g['flag']=g[name].astype(int)
    g['outcome']=(g.label==direction).astype(float)
    g['period']=g.clock//60
    keys=['period'] if flag in ['large_range','compression'] else ['period','band']
    stats=g.groupby(keys+['flag'],observed=True).outcome.agg(['size','mean']).unstack('flag')
    if not {0,1}.issubset(stats['size'].columns):return None
    valid=stats['size'].min(axis=1,skipna=False)>=3
    stats=stats.loc[valid]
    if stats.empty:return None
    s=pd.DataFrame(dict(n1=stats['size'][1],n0=stats['size'][0],p1=stats['mean'][1],p0=stats['mean'][0])).reset_index()
    g=g.merge(s,on=keys)
    f=g.flag.eq(1)
    nf=f.sum()
    p1=g.loc[f,'outcome'].mean()
    p0=g.loc[f,'p0'].mean()
    diff=p1-p0
    # ATT-style standardized risk difference with matched strata; day-cluster sandwich.
    infl=np.where(f,g.outcome-g.p0-diff,-g.n1/g.n0*(g.outcome-g.p0))
    blocks=pd.Series(infl,index=g.index).groupby(g.day).sum()
    se=np.sqrt((blocks**2).sum()*len(blocks)/max(1,len(blocks)-1))/nf
    return dict(flag=flag,direction=direction,n_flag=int(nf),n_control=int((~f).sum()),n_days=len(blocks),
                hit_rate=p1,comparison=p0,difference=diff,low=diff-1.96*se,high=diff+1.96*se,
                p=2*norm.sf(abs(diff/se)) if se else 1)


def main(source,out):
    out.mkdir(parents=True,exist_ok=True)
    data=pd.read_parquet(source)
    dev=data[(data.day<'2026-10-21')&(data['shift']==0)&(data.level==30)]
    bounds=np.quantile(dev.range15,[1/3,2/3])
    data['band']=np.digitize(data.range15,bounds)
    data['period']=np.where(data.day<'2026-10-21','discovery','replication')
    summary=[]; estimates=[]; magnitudes=[]
    for (stage,shift,L),g in data.groupby(['period','shift','level']):
        base=dict(period=stage,shift=shift,level=L,windows=len(g))
        for side in [1,-1,0]:
            e=g[g.label==side]
            summary.append(dict(**base,side=side,n=len(e),rate=len(e)/len(g),
               median_candles=e.delay.median() if side else np.nan,multi_fraction=(e.delay>1).mean() if side else np.nan,
               bodies_fraction=(e.same_bodies/e.delay).median() if side else np.nan,
               contemporaneous_breadth=e.net_breadth.dropna().ge(3).mean() if side else np.nan,
               premium_median=e.premium_change.median() if side else np.nan))
        if L==30:
            bins=pd.cut(g.max_excursion,[-1,20-1e-7,30-1e-7,40-1e-7,np.inf],labels=['under20','20_to_under30','30_to_under40','40_plus'])
            for key,n in bins.value_counts().items():magnitudes.append(dict(**base,band=key,count=n))
        for direction in [1,-1]:
            for flag in FLAGS:
                res=contrast(g,direction,flag)
                if res:estimates.append(dict(**base,**res))
    e=pd.DataFrame(estimates)
    e['q']=e.groupby(['period','shift']).p.transform(lambda x:false_discovery_control(x))
    e.to_csv(out/'factor_comparisons.csv',index=False)
    pd.DataFrame(summary).to_csv(out/'move_counts.csv',index=False)
    pd.DataFrame(magnitudes).to_csv(out/'excursion_bands.csv',index=False)
    # Example selection is deterministic and unranked: first multibar crossing by side in replication.
    cases=data[(data['shift']==0)&(data.level==30)&(data.period=='replication')&(data.delay>=2)]
    rows=[]
    for side in [1,-1]:
        r=cases[cases.label==side].sort_values(['day','clock']).iloc[0]
        rows.append(r[['day','clock','label','delay','same_bodies','net_breadth','prior_move5','range15','end_move','strike','premium_change']])
    pd.DataFrame(rows).to_csv(out/'examples.csv',index=False)


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('source',type=Path);p.add_argument('out',type=Path)
    a=p.parse_args();main(a.source,a.out)
