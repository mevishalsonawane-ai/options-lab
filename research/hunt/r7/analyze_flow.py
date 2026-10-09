"""R7: order-flow features at 1-second bars from ticks.parquet, and a first lead-lag look.
Features per instrument (futures):
  OFI   Cont-Kukanov-Stoikov best-level order-flow imbalance, summed per second
  MOFI  same over 5 levels (sum of per-level OFI, Xu-Gould-Howison style, equal weights)
  QI    queue imbalance at best (bq0-aq0)/(bq0+aq0), last value in second
  DI5   5-level depth imbalance, last value
  TQI   total buy/sell qty imbalance (tbq-tsq)/(tbq+tsq)
  SV    signed volume: d(volume) signed by LTP vs previous mid (tick rule when at mid)
Options: signed option flow = sum over ATM+-1 calls of SV minus puts' SV (delta-ish direction), in lots.
Lead test: corr(feature summed over past W s, future mid log-return over next H s), sampled every 1 s (overlapping)
and every H s (non-overlapping), plus contemporaneous corr as a pipe check."""
import sys, json
from pathlib import Path
import numpy as np, pandas as pd
S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/r7")
df = pd.read_parquet(S / "ticks.parquet").sort_values("ts")
df["t"] = np.floor(df.ts).astype(np.int64)
out = []
def per_inst(g):
    g = g.copy()
    g = g[(g.bp0 > 0) & (g.ap0 > 0) & (g.ap0 >= g.bp0)]
    # Dhan's full feed repeats the same state many times (~80% exact duplicates) and now and then re-sends an OLDER
    # state (volume and last-trade time step back). Drop both: duplicates add nothing, stale states fake OFI.
    g = g[~g.drop(columns=["ts"]).duplicated()]
    g = g[g.vol >= g.vol.cummax()]
    pb, pa, qb, qa = g.bp0.shift(), g.ap0.shift(), g.bq0.shift(), g.aq0.shift()
    e = (np.where(g.bp0 >= pb, g.bq0, 0) - np.where(g.bp0 <= pb, qb, 0)
         - np.where(g.ap0 <= pa, g.aq0, 0) + np.where(g.ap0 >= pa, qa, 0))
    g["ofi"] = np.nan_to_num(e)
    m = np.zeros(len(g))
    for k in range(5):
        pbk, pak, qbk, qak = (g[f"bp{k}"].shift(), g[f"ap{k}"].shift(), g[f"bq{k}"].shift(), g[f"aq{k}"].shift())
        m += np.nan_to_num(np.where(g[f"bp{k}"] >= pbk, g[f"bq{k}"], 0) - np.where(g[f"bp{k}"] <= pbk, qbk, 0)
                           - np.where(g[f"ap{k}"] <= pak, g[f"aq{k}"], 0) + np.where(g[f"ap{k}"] >= pak, qak, 0))
    g["mofi"] = m
    g["mid"] = (g.bp0 + g.ap0) / 2
    g["qi"] = (g.bq0 - g.aq0) / (g.bq0 + g.aq0)
    B = sum(g[f"bq{k}"] for k in range(5)); A = sum(g[f"aq{k}"] for k in range(5))
    g["di5"] = (B - A) / (B + A)
    g["tqi"] = (g.tbq - g.tsq) / (g.tbq + g.tsq)
    dv = g.vol.diff().clip(lower=0).fillna(0)
    # Sign: tick rule (last non-zero LTP change). The quote rule against the previous packet's mid fails here because
    # the trade fields and the book fields arrive in separate updates (pipe check: same-10s corr ~0 vs +0.2..0.56).
    sgn = np.sign(g.ltp.diff()).replace(0, np.nan).ffill().fillna(0)
    g["sv"] = sgn.values * dv.values
    g["spread_bp"] = (g.ap0 - g.bp0) / g.mid * 1e4
    return g
bars = {}
for sid, g in df.groupby("sid"):
    g = per_inst(g); sym, kind = g.sym.iloc[0], g.kind.iloc[0]
    b = g.groupby("t").agg(ofi=("ofi", "sum"), mofi=("mofi", "sum"), sv=("sv", "sum"), mid=("mid", "last"), qi=("qi", "last"),
                           di5=("di5", "last"), tqi=("tqi", "last"), spread_bp=("spread_bp", "median"), n=("ofi", "size"))
    bars[sid] = (sym, kind, b)
    rt = np.unique(g.ts.values); dv = g.vol.diff().fillna(0)
    chg = g[(dv > 0)]
    print(f"   cadence: {len(rt)/(g.ts.max()-g.ts.min()+1e-9):.2f} frames/s, distinct-recv gap median {np.median(np.diff(rt)):.3f}s p90 {np.percentile(np.diff(rt),90):.2f}s; "
          f"packets with new volume {len(chg)/len(g):.0%}; dvol>LTQ (trades merged) {np.mean(dv[dv>0] > g.ltq[dv>0]):.0%}")
    print(f"{sym:10s} {kind:3s} {sid:>7s} ticks {len(g):6d} secs {len(b):5d} median gap {np.median(np.diff(g.ts)):.3f}s "
          f"spread {g.spread_bp.median():.1f}bp  dvol {g.vol.iloc[-1]-g.vol.iloc[0]}")
res = []
for sym in ("CRUDEOIL", "NATURALGAS"):
    fut = [b for s, k, b in bars.values() if s == sym and k == "FUT"][0]
    t0, t1 = fut.index.min(), fut.index.max(); idx = np.arange(t0, t1 + 1)
    F = fut.reindex(idx); F["mid"] = F.mid.ffill()
    for c in ("ofi", "mofi", "sv"): F[c] = F[c].fillna(0)
    for c in ("qi", "di5", "tqi"): F[c] = F[c].ffill()
    of = np.zeros(len(idx))
    for s, k, b in bars.values():
        if s == sym and k in ("CE", "PE"):
            of += (1 if k == "CE" else -1) * b.sv.reindex(idx).fillna(0).values
    F["optsv"] = of
    lm = np.log(F.mid.values)
    print(f"\n== {sym}: {len(idx)} s, {pd.to_datetime(t0, unit='s')} - {pd.to_datetime(t1, unit='s')} UTC, "
          f"move {1e4*(lm[-1]-lm[0]):+.0f} bp")
    # contemporaneous pipe check: 10-s sums vs 10-s mid change
    r10 = pd.Series(lm).diff(10)
    for c in ("ofi", "mofi", "sv", "optsv"):
        x = F[c].rolling(10).sum().values
        print(f"  same-10s corr {c:6s} {pd.Series(x).corr(r10):+.3f}")
    for W in (10, 60):
        for c in ("ofi", "mofi", "sv", "optsv", "qi", "di5", "tqi"):
            x = F[c].rolling(W).sum().values if c in ("ofi", "mofi", "sv", "optsv") else F[c].values
            for H in (60, 180, 300):
                fwd = np.full(len(lm), np.nan)
                if len(lm) > H: fwd[:-H] = lm[H:] - lm[:-H]
                ok = ~np.isnan(x) & ~np.isnan(fwd)
                c_ov = np.corrcoef(x[ok], fwd[ok])[0, 1] if ok.sum() > 30 else np.nan
                # null band: circular shifts of the feature by >= 300 s (keeps autocorrelation, breaks timing)
                rng = np.random.default_rng(7); nulls = []
                xv, fv = x[ok], fwd[ok]
                if len(xv) > 700:
                    for _ in range(200):
                        nulls.append(np.corrcoef(np.roll(xv, rng.integers(300, len(xv) - 300)), fv)[0, 1])
                null95 = np.nanpercentile(np.abs(nulls), 95) if nulls else np.nan
                sel = np.arange(W, len(idx) - H, H)
                xs, fs = x[sel], fwd[sel]; k2 = ~np.isnan(xs) & ~np.isnan(fs)
                c_no = np.corrcoef(xs[k2], fs[k2])[0, 1] if k2.sum() > 5 else np.nan
                # top/bottom-quintile spread in bp (overlapping)
                if ok.sum() < 50: continue
                q = pd.qcut(pd.Series(x[ok]).rank(method="first"), 5, labels=False)
                d = pd.Series(fwd[ok] * 1e4).groupby(q.values).mean()
                res.append(dict(sym=sym, feat=c, W=W if c in ("ofi", "mofi", "sv", "optsv") else 0, H=H, corr_overlap=c_ov, null95=null95,
                                corr_nonoverlap=c_no, n_nonoverlap=int(k2.sum()), q5_minus_q1_bp=d.iloc[-1] - d.iloc[0]))
    print(f"  fut spread median {fut.spread_bp.median():.2f} bp; |60s move| median {np.nanmedian(np.abs(lm[60:]-lm[:-60]))*1e4:.1f} bp; "
          f"|300s move| median {(np.nanmedian(np.abs(lm[300:]-lm[:-300]))*1e4 if len(lm)>300 else np.nan):.1f} bp")
R = pd.DataFrame(res).drop_duplicates(["sym", "feat", "H", "W"])
pd.set_option("display.width", 200); pd.set_option("display.max_rows", 200)
print(R.round(3).to_string(index=False))
R.to_csv(S / "leadlag.csv", index=False)
