"""MCX near-month minutes vs WTI(CL=F) x USDINR at 5-minute and 1-hour resolution: level ratio, return
correlation, lead/lag (does NYMEX lead MCX?)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *
out = open(f"{LOGS}/proxy_fit_intraday.txt", "w")
def P(*a):
    s = " ".join(str(x) for x in a); print(s); out.write(s + "\n")
b = pd.read_parquet(f"{D}/mcx_min.parquet")
b = b[~b.index.duplicated()]
for tf, fr in (("5m", "5min"), ("1h", "60min")):
    cl, inr = yahoo(tf, "CL_F"), yahoo(tf, "INR_X")
    # Yahoo bar start (UTC) -> close known at start+tf; express as IST end time
    dtf = pd.Timedelta(fr)
    for x in (cl, inr):
        x.index = (x.index + dtf).tz_localize("UTC").tz_convert("Asia/Kolkata").tz_localize(None)
    cl = cl[~cl.index.duplicated()]; inr = inr[~inr.index.duplicated()]
    inr_a = inr.close.reindex(inr.index.union(cl.index)).sort_index().ffill().reindex(cl.index)
    prox = (cl.close * inr_a).dropna()
    m = b.spot.reindex(prox.index - pd.Timedelta(minutes=1))   # MCX minute bar ending at the same instant
    m.index = prox.index
    df = pd.DataFrame({"mcx": m, "proxy": prox}).dropna()
    df = df[~df.index.normalize().isin(b.index.normalize()[b.roll_day])]
    ratio = df.mcx / df.proxy
    r = np.log(df).diff()
    same_day = pd.Series(df.index.normalize(), index=df.index)
    r = r[same_day == same_day.shift()]
    P(f"== {tf}: overlap {len(df)} bars {df.index.min()} .. {df.index.max()}")
    P(f"   MCX / (WTI x USDINR): median {ratio.median():.4f}, p5 {ratio.quantile(.05):.4f}, p95 {ratio.quantile(.95):.4f} (near-month MCX vs CL=F front month)")
    for lag in (-2, -1, 0, 1, 2):
        P(f"   corr(MCX ret[t], proxy ret[t{lag:+d}]) = {r.mcx.corr(r.proxy.shift(lag)):+.3f}")
