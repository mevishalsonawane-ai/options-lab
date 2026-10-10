"""NN-CRUDE market facts, daily level: MCX continuous near-month (Dhan, 2012+) and the WTI x USDINR proxy."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
from common import *

out = open(f"{LOGS}/analyze_daily.txt", "w")
def P(*a):
    s = " ".join(str(x) for x in a); print(s); out.write(s + "\n")
pd.set_option("display.width", 200)

m = mcx_daily("CRUDEOIL")
m = m[m.volume > 0]
# April 2020: WTI/MCX May contract settled negative (-$37 / Rs -2,884); drop 2020-04-15..2020-05-08 from statistics
m = m[(m.close > 0) & ~((m.index >= "2020-04-15") & (m.index <= "2020-05-08"))]
r = np.log(m.close).diff()
gap = np.log(m.open / m.close.shift())
rng = (m.high - m.low) / m.close.shift()
# roll days: continuous near-month switches contract after expiry (~19-20th). Flag the largest gap per month
# around day 17-23 when |gap|>1.5%? Instead report robust stats (median) and stats excluding |gap|>4%.
tab = pd.DataFrame({"days": r.groupby(m.index.year).count(),
                    "mean_abs_ret_%": (r.abs() * 100).groupby(m.index.year).mean().round(2),
                    "median_abs_ret_%": (r.abs() * 100).groupby(m.index.year).median().round(2),
                    "mean_range_%": (rng * 100).groupby(m.index.year).mean().round(2),
                    "mean_abs_gap_%": (gap.abs() * 100).groupby(m.index.year).mean().round(2),
                    "ann_vol_%": (r.groupby(m.index.year).std() * np.sqrt(252) * 100).round(1),
                    "close_end": m.close.groupby(m.index.year).last()})
P("== MCX CRUDEOIL continuous near-month daily (Dhan /charts/historical), by year"); P(tab.to_string())
P("\nRs per lot (100 bbl) of the mean daily range, 2025-26:", round(float((m.high - m.low)[m.index >= "2025-01-01"].mean() * 100)))
# trend persistence
P("\n== Daily return autocorrelation (lag 1..5) and P(same sign as yesterday)")
for per, sl in [("2012-2019", slice("2012", "2019")), ("2020-2026", slice("2020", "2026")), ("2024-2026", slice("2024", "2026"))]:
    rr = r[sl].dropna()
    ac = [round(rr.autocorr(k), 3) for k in range(1, 6)]
    same = ((np.sign(rr) == np.sign(rr.shift())) & (rr != 0)).mean()
    vr = (rr.rolling(5).sum().var() / (5 * rr.var()))
    P(per, "acf", ac, "P(same sign)", round(same, 3), "variance ratio 5d", round(vr, 2))
# gap vs intraday
P("\n== Gap (open vs prev close) and open->close, 2020-2026")
oc = np.log(m.close / m.open)
sl = m.index >= "2020-01-01"
P("corr(gap, open->close) =", round(np.corrcoef(gap[sl].fillna(0), oc[sl])[0, 1], 3),
  " share of close-close variance from gap:", round(gap[sl].var() / r[sl].var(), 2))

# proxy fit
cl, inr, bz = yahoo("1d", "CL_F"), yahoo("1d", "INR_X"), yahoo("1d", "BZ_F")
for x in (cl, inr, bz):
    x.index = x.index.normalize()
px = pd.DataFrame({"mcx": m.close, "wti": cl.close, "brent": bz.close, "inr": inr.close}).dropna()
px = px[px.wti > 0]
px["proxy"] = px.wti * px.inr
px["ratio"] = px.mcx / px.proxy
P("\n== MCX close vs WTI(CL=F) x USDINR(INR=X), same calendar date (MCX closes 23:30 IST; CL settles 14:30 ET)")
P("ratio MCX/(WTI*INR) by year (median, p5, p95):")
P(px.ratio.groupby(px.index.year).describe(percentiles=[.05, .5, .95])[["count", "5%", "50%", "95%"]].round(3).to_string())
lr = np.log(px[["mcx", "wti", "brent", "inr", "proxy"]]).diff().dropna()
lr = lr[(lr.wti.abs() < 0.3)]
for per, sl in [("2012-2019", slice("2012", "2019")), ("2020-2026", slice("2020", "2026")), ("2025-26", slice("2025", "2026"))]:
    x = lr[sl]
    beta = np.polyfit(x.proxy, x.mcx, 1)[0]
    P(per, "n", len(x), "corr(mcx, wti*inr)", round(x.mcx.corr(x.proxy), 3), "corr(mcx,brent)", round(x.mcx.corr(x.brent), 3),
      "corr(mcx,inr)", round(x.mcx.corr(x.inr), 3), "beta", round(beta, 2),
      "corr with NEXT-day wti*inr", round(x.mcx.corr(x.proxy.shift(-1)), 3), "prev-day", round(x.mcx.corr(x.proxy.shift(1)), 3))
px.to_parquet(f"{D}/proxy_daily.parquet", compression="zstd")
