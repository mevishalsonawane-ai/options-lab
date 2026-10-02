"""The 1h GG family from research/gold_mtf_patterns.py, year by year (run: python research/gold_mtf_family.py <xau.csv.gz>)."""
import sys
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np, pandas as pd
import gold_session_strats as s, liquidity_gold as g
H,COMM=0.15,0.07
mid=s.load(sys.argv[1])
m30=s.bars(mid,30); m30=m30[m30.index.hour!=21]; h1=s.bars(mid,60); h1=h1[h1.index.hour!=21]
o,c=m30.open.values,m30.close.values; idx=m30.index; n=len(c)
yr=np.array([g.year_of(d) for d in idx.date]); years=sorted(set(yr),key=lambda x:x[4:8])
col30=np.where(c>o,"G",np.where(c<o,"R","D"))
hc=np.where(h1.close.values>h1.open.values,"G",np.where(h1.close.values<h1.open.values,"R","D"))
hend=(h1.index+pd.Timedelta(hours=1)).values; ct=(idx+pd.Timedelta(minutes=30)).values
lh=np.searchsorted(hend,ct,side="right")-1
gap=np.r_[(idx[1:]-idx[:-1])<=pd.Timedelta(minutes=60),False]; st=np.cumsum(~np.r_[True,gap[:-1]])
def fwd(N):
    j=np.minimum(np.arange(n)+N,n-1); nx=np.minimum(np.arange(n)+1,n-1); ok=(np.arange(n)+N<n)&(st[j]==st)
    f=np.full(n,np.nan); f[ok]=c[j][ok]-o[nx][ok]; return f
def run(mask,N):
    f=fwd(N); sel=np.where(mask&gap&~np.isnan(f))[0]; keep=[];busy=-1
    for i in sel:
        if i>busy: keep.append(i); busy=i+N
    keep=np.array(keep); return keep, 100*(f[keep]-2*H-COMM)
GG=(hc[np.maximum(lh-1,0)]=="G")&(hc[lh]=="G")
GGG=GG&(hc[np.maximum(lh-2,0)]=="G")
m1=col30; m2=np.concatenate([["X"],col30[:-1]])
cases={"any time (baseline)":np.ones(n,bool),"1h GG":GG,"1h GGG":GGG,"1h GG + 30m G":GG&(m1=="G"),"1h GG + 30m R":GG&(m1=="R"),
 "1h GG + 30m RG":GG&(m2=="R")&(m1=="G"),"1h RR (control)":(hc[np.maximum(lh-1,0)]=="R")&(hc[lh]=="R")}
for N in (4,8,16):
    print(f"hold {N} x 30 min")
    for nm,mk in cases.items():
        k,p=run(mk,N)
        t=p.mean()/(p.std(ddof=1)/len(p)**0.5)
        print(f"  {nm:22s} t {t:5.2f} | "+" | ".join(f"{y[4:8]}-{y[-2:]}: {(yr[k]==y).sum():4d} tr {p[yr[k]==y].mean():+5.0f}/tr {p[yr[k]==y].sum():+9,.0f}" for y in years))
print()
for N in (8,16):
    k,p=run(GG&(m2=="R")&(m1=="G"),N)
    eq=np.cumsum(p); dd=(eq-np.maximum.accumulate(eq)).min()
    days=(idx[-1]-idx[0]).days
    print(f"1h GG + 30m RG, hold {N}: {len(p)} trades ({len(p)/days*7:.1f} a week), win {100*(p>0).mean():.0f}%, total {p.sum():+,.0f}, "
          f"per trade {p.mean():+.0f}, deepest drawdown {dd:,.0f} (trade by trade), avg month at 0.01 lot {p.sum()/100/36:+.1f}, worst trade {p.min():,.0f}")
