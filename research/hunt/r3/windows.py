"""R3: how often does an 11-session window look like the outside report (all-signals system split by entry time)?
Also: XAU vs GOLDM 60-min return correlation (proxy check)."""
import sys
sys.path.insert(0, "/home/user/options-lab/research/hunt/r3")
from r3lib import *
W = R3 / "work"
R = pd.read_parquet(W / "trades_all.parquet")
for src, strike, sp, fill, col in [("GOLDM_C", "ATM", "nospread", "RAW", "net100"), ("GOLDM_C", "ITM1", "nospread", "RAW", "net100"),
                                   ("GOLDM_C", "ATM", "base", "CLEAN", "net")]:
    T = R[(R.src == src) & (R.tf == 5) & (R.strike == strike) & (R.spread_case == sp) & (R.fill == fill) & (R.days == "allday") & (R.filt == "ALL") & (R.status == "ok")].copy()
    T["net100"] = T.gross - 100
    days = sorted(T.day.unique())
    d_all = sorted(pd.read_parquet(W / "min_GOLDM_C.parquet").day.unique())
    rows = []
    for i in range(len(d_all) - 10):
        w = d_all[i:i + 11]
        t = T[T.day.isin(w)]
        am, pm = t[t.start < 900][col], t[t.start >= 900][col]
        rows.append(dict(start=w[0], am=am.sum(), pm=pm.sum(), am_n=len(am), am_allwin=(len(am) >= 5 and (am > 0).all()), am_wr=(am > 0).mean() if len(am) else np.nan))
    X = pd.DataFrame(rows)
    print(f"{src} {strike} {fill}/{col}: {len(X)} rolling 11-session windows; AM>0 & PM<0 in {100*((X.am>0)&(X.pm<0)).mean():.0f}%; "
          f"AM<0 & PM>0 in {100*((X.am<0)&(X.pm>0)).mean():.0f}%; AM >= +10k in {100*(X.am>=10000).mean():.0f}%; AM <= -10k in {100*(X.am<=-10000).mean():.0f}%; "
          f"AM win-rate >= 80% in {100*(X.am_wr>=0.8).mean():.0f}%; median AM {X.am.median():+.0f} PM {X.pm.median():+.0f}")
# proxy check
g = pd.read_parquet(W / "min_GOLDM_C.parquet"); x = pd.read_parquet(W / "min_XAU.parquet")
gd, gi, gC = grid(g); xd, xi, xC = grid(x)
common = [d for d in gd if d in xi]
a = np.array([gC[gi[d]] for d in common]); b = np.array([xC[xi[d]] for d in common])
ra = (a[:, 60::60] / a[:, :-60:60][:, :a[:, 60::60].shape[1]] - 1).ravel(); rb = (b[:, 60::60] / b[:, :-60:60][:, :b[:, 60::60].shape[1]] - 1).ravel()
ok = ~np.isnan(ra) & ~np.isnan(rb)
print(f"proxy: hourly return corr GOLDM vs XAUUSD over {len(common)} common days = {np.corrcoef(ra[ok], rb[ok])[0,1]:.2f} (n={ok.sum()})")
