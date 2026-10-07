import sys, pickle; sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd, numpy as np
S="/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/"
res=pickle.load(open(S+"h15/cache/h15/tab_real.pkl","rb"))
T=pd.read_parquet(S+"h23/trades.parquet"); T["day"]=pd.to_datetime(T.day)
for u in ["MIDCPNIFTY","BANKNIFTY"]:
    m,d=res[u]; a=d["E2_k02"]
    o=m[["day","entry_min","exit_min","why","entry","exit"]].copy(); o["day"]=pd.to_datetime(o.day)
    o["net_old"]=a["net"][:,1]; o["gross_old"]=a["gross"][:,1]
    t=T[T.und==u][["day","entry_min","exit_min","why","entry","exit","net_0.02","gross_0.02","lots_0.02","hs","spread_extra_0.02"]]
    for nm,lo,hi in [("pre","2021-01-01","2025-10-01"),("hold","2025-10-01","2030-01-01")]:
        oo=o[(o.day>=lo)&(o.day<hi)]; tt=t[(t.day>=lo)&(t.day<hi)]
        j=oo.merge(tt,on=["day","entry_min"],how="outer",suffixes=("_o","_n"),indicator=True)
        print(u,nm,"old n",len(oo),"new n",len(tt), j._merge.value_counts().to_dict())
        b=j[j._merge=="both"]
        print("  matched: old net %.0f gross %.0f | new net %.0f gross %.0f spread_extra %.0f"%(b.net_old.sum(),b.gross_old.sum(),b["net_0.02"].sum(),b["gross_0.02"].sum(),b["spread_extra_0.02"].sum()))
        print("  old-only net %.0f gross %.0f ; new-only net %.0f"%(j[j._merge=="left_only"].net_old.sum(),j[j._merge=="left_only"].gross_old.sum(),j[j._merge=="right_only"]["net_0.02"].sum()))
        b=b.assign(dx=b.exit_min_n-b.exit_min_o, dentry=b.entry_n-b.entry_o, dexit=b.exit_n-b.exit_o)
        print("  exit changed %d of %d; why changed %d; mean d-entry %.2f, mean d-exit %.2f"%((b.dx!=0).sum(),len(b),(b.why_o!=b.why_n).sum(),b.dentry.mean(),b.dexit.mean()))
        if u=="MIDCPNIFTY" and nm=="hold":
            print(pd.crosstab(b.why_o,b.why_n))
            print(b.assign(g=b["gross_0.02"]-b.gross_old).groupby("why_o").g.agg(["count","sum"]))
u="MIDCPNIFTY"; m,d=res[u]; a=d["E2_k02"]
o=m[["day","book","entry_min","exit_min","why","entry","exit"]].copy(); o["day"]=pd.to_datetime(o.day); o["gross_old"]=a["gross"][:,1]; o["lots_old"]=a["lots"][:,1]
t=T[T.und==u][["day","book","entry_min","exit_min","why","entry","exit","gross_0.02","lots_0.02","lot"]]
j=o.merge(t,on=["day","book","entry_min"],suffixes=("_o","_n"))
j=j[(j.day>="2025-10-01")&(j.why_o=="index_target")]
j["dg"]=j["gross_0.02"]-j.gross_old
print(j.sort_values("dg").head(12).to_string())
print(j[["entry_o","entry_n","exit_o","exit_n"]].describe())
