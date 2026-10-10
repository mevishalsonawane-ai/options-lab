"""R9 proxy check (PREREG 'Data'): OPTV vs FUTV on the futures-minute days. python3 -I check.py"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import spearmanr  # noqa: E402

rows = []
for u in ["NIFTY", "BANKNIFTY", "MIDCPNIFTY", "FINNIFTY"]:
    P = R.panel(u)
    idx = np.nonzero(P["has_fut"])[0]
    rho, poc, ov, pocT, ovT = [], [], [], [], []
    for di in idx:
        a, b = P["optv"][di], P["futv"][di]
        ok = np.isfinite(a) & np.isfinite(b) & (b > 0)
        if ok.sum() > 100:
            rho.append(spearmanr(a[ok], b[ok])[0])
        if di + 1 < P["nd"]:
            lo = R.levels(P, di + 1, "optv")
            lf = R.levels(P, di + 1, "futv")
            lt = R.levels(P, di + 1, "tpo")
            if lo and lf:
                poc.append(abs(lo["POC"] - lf["POC"]) <= 2 * lf["bw"])
                inter = max(0, min(lo["VAH"], lf["VAH"]) - max(lo["VAL"], lf["VAL"]))
                uni = max(lo["VAH"], lf["VAH"]) - min(lo["VAL"], lf["VAL"])
                ov.append(inter / uni if uni > 0 else np.nan)
            if lt and lf:
                pocT.append(abs(lt["POC"] - lf["POC"]) <= 2 * lf["bw"])
                inter = max(0, min(lt["VAH"], lf["VAH"]) - max(lt["VAL"], lf["VAL"]))
                uni = max(lt["VAH"], lf["VAH"]) - min(lt["VAL"], lf["VAL"])
                ovT.append(inter / uni if uni > 0 else np.nan)
    rows.append(dict(und=u, fut_days=len(idx), minute_spearman=np.nanmedian(rho) if rho else np.nan,
                     poc_agree_optv=np.mean(poc) if poc else np.nan, va_overlap_optv=np.nanmean(ov) if ov else np.nan,
                     poc_agree_tpo=np.mean(pocT) if pocT else np.nan, va_overlap_tpo=np.nanmean(ovT) if ovT else np.nan,
                     n=len(poc)))
df = pd.DataFrame(rows)
df["usable"] = (df.poc_agree_optv >= 0.5) & (df.va_overlap_optv >= 0.6)
print(df.round(3).to_string(index=False))
df.to_csv(os.path.join(R.OUT, "proxy_check.csv"), index=False)
