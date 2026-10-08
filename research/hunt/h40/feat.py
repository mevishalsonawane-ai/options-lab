"""h40 features: parse the compacted public files into one row per (data date t) and map each to the NEXT trading
session D of every index (features for D use only files dated <= D-1). See PREREG.md.

    python3 -I research/hunt/h40/feat.py   -> <scratch>/hunt/h40/feat.parquet, raw_daily.parquet
"""
from __future__ import annotations

import glob
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
H = f"{S}/hunt/h40"
D = f"{H}/data"
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
HEAVY = ["RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "TCS", "BHARTIARTL", "ITC", "LT", "SBIN", "AXISBANK",
         "KOTAKBANK", "HINDUNILVR"]


def z60(s):
    m = s.rolling(60, min_periods=40).mean()
    sd = s.rolling(60, min_periods=40).std()
    return (s - m) / sd


def poi():
    fs = {}
    for f in sorted(glob.glob(f"{S}/hunt/h27/raw/poi/*.csv")) + sorted(glob.glob(f"{D}/poi/*.csv")):
        fs[os.path.basename(f)[:8]] = f
    rows = []
    for tag, f in sorted(fs.items()):
        for line in open(f, errors="replace"):
            p = [x.strip().strip('"') for x in line.strip().split(",")]
            if p and p[0] in ("Client", "DII", "FII", "Pro"):
                try:
                    v = [float(x) for x in p[1:9]]
                except ValueError:
                    continue
                rows.append([pd.Timestamp(tag).date(), p[0]] + v)
    x = pd.DataFrame(rows, columns=["day", "who", "fl", "fs", "sl", "ss", "cl", "pl", "cs", "ps"])
    w = x.pivot_table(index="day", columns="who", values=["fl", "fs", "cl", "pl", "cs", "ps"])
    w.columns = [f"{b}_{a}" for a, b in w.columns]
    out = pd.DataFrame(index=w.index)
    out["fii_lr"] = w.FII_fl / (w.FII_fl + w.FII_fs)
    out["fii_net"] = w.FII_fl - w.FII_fs
    out["fii_optbull"] = (w.FII_cl - w.FII_cs) - (w.FII_pl - w.FII_ps)
    out["pro_net"] = w.Pro_fl - w.Pro_fs
    out["cli_net"] = w.Client_fl - w.Client_fs
    return out


def fiistats():
    import xlrd
    rows = []
    for f in sorted(glob.glob(f"{D}/fii/*.xls")):
        try:
            sh = xlrd.open_workbook(f).sheet_by_index(0)
        except Exception:  # noqa: BLE001
            continue
        for r in range(sh.nrows):
            if str(sh.cell_value(r, 0)).strip() == "INDEX FUTURES":
                v = [float(str(sh.cell_value(r, c)).replace(",", "") or 0) for c in range(1, 7)]
                rows.append([pd.Timestamp(os.path.basename(f)[:8]).date()] + v)
                break
    x = pd.DataFrame(rows, columns=["day", "bq", "bv", "sq", "sv", "oiq", "oiv"]).set_index("day")
    return pd.DataFrame({"fii_if_netval": x.bv - x.sv, "fii_if_oi": x.oiq})


def fo():
    fut = pd.read_parquet(f"{D}/fo_futures.parquet", columns=["day", "inst", "sym", "exp", "c", "settle", "oi", "doi"])
    for c in ("day", "inst", "sym", "exp"):
        fut[c] = fut[c].astype(str)
    for c in ("c", "settle", "oi", "doi"):
        fut[c] = fut[c].astype(float)
    fut["day"] = pd.to_datetime(fut.day).dt.date
    fut["exp"] = pd.to_datetime(fut.exp).dt.date
    days = sorted(fut.day.unique())
    prev = dict(zip(days[1:], days[:-1]))
    # price change of each contract vs its own previous-day close
    k = fut.set_index(["day", "sym", "exp"]).c
    fut["pday"] = fut.day.map(prev)
    pc = k.reindex(pd.MultiIndex.from_arrays([fut.pday, fut.sym, fut.exp])).values
    fut["dc"] = fut.c.values - pc
    fut = fut.sort_values(["day", "sym", "exp"])
    near = fut.groupby(["day", "sym"]).first()            # nearest expiry contract
    tot = fut.groupby(["day", "sym"]).agg(oi=("oi", "sum"), doi=("doi", "sum"))
    st = near.join(tot, rsuffix="_tot")
    st = st[st.inst == "STF"]
    lb = ((st.dc > 0) & (st.doi_tot > 0)).groupby(level=0).sum()
    sb = ((st.dc < 0) & (st.doi_tot > 0)).groupby(level=0).sum()
    n = st.dc.notna().groupby(level=0).sum()
    out = pd.DataFrame({"stk_buildup": (lb - sb) / n, "stk_n": n})
    idx = near.join(tot, rsuffix="_tot").reset_index()
    idx = idx[idx.inst == "IDF"]
    for s in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        g = idx[idx.sym == s].set_index("day")
        out[f"bu_{s}"] = pd.Series(np.where(g.doi_tot > 0, np.sign(g.dc), 0), index=g.index)
    # rollover (NIFTY / BANKNIFTY), on monthly futures: near and next
    for s in ("NIFTY", "BANKNIFTY"):
        g = fut[(fut.sym == s) & (fut.inst == "IDF")].sort_values(["day", "exp"])
        r = []
        for d, h in g.groupby("day"):
            if len(h) < 2:
                continue
            a, b = h.iloc[0], h.iloc[1]
            r.append(dict(day=d, exp=a.exp, roll=b.oi / (a.oi + b.oi), spr=b.c / a.c - 1))
        r = pd.DataFrame(r)
        r["off"] = r.groupby("exp").cumcount(ascending=False)          # sessions left to expiry (0 = expiry day)
        out[f"roll_{s}"] = r.set_index("day").roll
        out[f"rspr_{s}"] = r.set_index("day").spr
        out[f"roff_{s}"] = r.set_index("day").off
        out[f"rexp_{s}"] = r.set_index("day").exp
    # own-index near-expiry option OI change
    oa = pd.read_parquet(f"{D}/fo_idxopt_agg.parquet")
    for c in ("day", "sym", "exp", "typ"):
        oa[c] = oa[c].astype(str)
    for c in ("oi", "doi"):
        oa[c] = oa[c].astype(float)
    oa["day"] = pd.to_datetime(oa.day).dt.date
    oa["exp"] = pd.to_datetime(oa.exp).dt.date
    oa = oa[oa.exp >= oa.day]
    for s in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        g = oa[oa.sym == s]
        ne = g.groupby("day").exp.min()
        g = g[g.exp.values == ne.reindex(g.day).values]
        p = g.pivot_table(index="day", columns="typ", values=["oi", "doi"], aggfunc="sum")
        out[f"opt_{s}"] = (p["doi"]["PE"] - p["doi"]["CE"]) / (p["oi"]["PE"] + p["oi"]["CE"])
    return out


def cm():
    x = pd.read_parquet(f"{D}/cm_eq_delivery.parquet", columns=["day", "SYMBOL", "SERIES", "PREV_CLOSE", "CLOSE_PRICE",
                                                                "DELIV_QTY"])
    for c in ("day", "SYMBOL", "SERIES"):
        x[c] = x[c].astype(str)
    x = x[(x.SERIES == "EQ") & x.SYMBOL.isin(HEAVY)].copy()
    for c in ("PREV_CLOSE", "CLOSE_PRICE"):
        x[c] = x[c].astype(float)
    x["day"] = pd.to_datetime(x.day).dt.date
    x["DELIV_QTY"] = pd.to_numeric(x.DELIV_QTY, errors="coerce")
    dq = x.pivot_table(index="day", columns="SYMBOL", values="DELIV_QTY")
    up = x.pivot_table(index="day", columns="SYMBOL", values="CLOSE_PRICE") - \
        x.pivot_table(index="day", columns="SYMBOL", values="PREV_CLOSE")
    lg = np.log(dq)
    z = (lg - lg.rolling(20, min_periods=15).mean().shift(1)) / lg.rolling(20, min_periods=15).std().shift(1)
    surge = z >= 1.5
    net = (surge & (up > 0)).sum(axis=1) - (surge & (up < 0)).sum(axis=1)
    return pd.DataFrame({"deliv_net": net, "deliv_n": dq.notna().sum(axis=1)})


def cash(tdays):
    out = pd.DataFrame(index=pd.Index(tdays, name="day"))
    n = pd.read_csv(f"{D}/nsdl_fpi_equity.csv")
    n["rep"] = pd.to_datetime(n.rep_date).dt.date
    n = n.drop_duplicates("rep").set_index("rep").net
    td = np.array(sorted(tdays))
    # reporting date R covers the previous trading day's trades
    pos = np.searchsorted(td, np.array(list(n.index)), side="left") - 1
    m = pos >= 0
    s = pd.Series(n.values[m], index=td[pos[m]])
    out["nsdl_net"] = s.groupby(level=0).sum()
    me = pd.read_csv(f"{D}/mendeley_fiidii.csv")
    me["day"] = pd.to_datetime(me.day).dt.date
    me = me.drop_duplicates(["fii_net", "dii_net"]).set_index("day")
    out["mfii_net"] = me.fii_net
    out["mdii_net"] = me.dii_net
    return out


def main():
    P = poi()
    F = fiistats()
    O = fo()
    Cm = cm()
    tdays = sorted(set(O.index))
    Ca = cash(tdays)
    raw = P.join([F, O, Cm, Ca], how="outer").sort_index()
    raw = raw[raw.index >= pd.Timestamp("2019-06-01").date()]
    raw.to_parquet(f"{H}/raw_daily.parquet")
    x = pd.DataFrame(index=raw.index)
    x["S01"] = z60(raw.fii_lr)
    x["S02"] = z60(raw.fii_net.diff())
    x["S03"] = z60(raw.fii_optbull.diff())
    x["S04"] = z60(raw.pro_net.diff())
    x["S05"] = -z60(raw.cli_net.diff())
    x["S06"] = z60(raw.fii_if_netval)
    x["S07"] = z60(raw.stk_buildup)
    for s in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        x[f"S08_{s}"] = raw[f"bu_{s}"]
        x[f"S09_{s}"] = z60(raw[f"opt_{s}"])
    for s in ("NIFTY", "BANKNIFTY"):
        r = raw[[f"roll_{s}", f"rspr_{s}", f"roff_{s}", f"rexp_{s}"]].dropna().copy()
        r.columns = ["roll", "spr", "off", "exp"]
        side = pd.Series(0.0, index=r.index)
        for d, row in r.iterrows():
            if row.off > 5 or row.off < 1:     # last 5 sessions before expiry (expiry day itself excluded)
                continue
            past = r[(r.off == row.off) & (r.exp < row.exp)].tail(6)
            if len(past) < 4:
                continue
            hi_roll = row.roll > past.roll.mean()
            side[d] = (1.0 if row.spr > past.spr.mean() else -1.0) if hi_roll else 0.0
        x[f"S10_{s}"] = side
    x["S11"] = raw.deliv_net / 2.0
    x["S12"] = z60(raw.nsdl_net)
    x["S12L"] = x["S12"].shift(1)                    # strict: one more day of lag
    x["S13"] = z60(raw.mfii_net)
    x["S14"] = z60(raw.mdii_net)
    comp = sum(np.where(x[c].abs() >= 0.5, np.sign(x[c]), 0) for c in ("S01", "S02", "S03", "S06", "S07", "S12"))
    x["S15"] = pd.Series(comp, index=x.index) / 2.0
    x.to_parquet(f"{H}/feat_bydate.parquet")
    # map data date t -> next session D of each index
    idd = pd.read_parquet(f"{H}/index_days.parquet") if os.path.exists(f"{H}/index_days.parquet") else None
    rows = []
    xd = np.array(x.index)
    for und in UNDS:
        if idd is not None:
            dd = sorted(idd[idd.und == und].day.unique())
        else:
            dd = [d for d in xd]
        for d in dd:
            i = np.searchsorted(xd, d) - 1           # last data date strictly before D
            if i < 0:
                continue
            r = x.iloc[i]
            row = dict(und=und, day=d, src=xd[i])
            for c in ("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S11", "S12", "S12L", "S13", "S14", "S15"):
                row[c] = r[c]
            own = und if und != "SENSEX" else "NIFTY"
            row["S08"] = r[f"S08_{own}"]
            row["S09"] = r[f"S09_{own}"]
            ro = {"NIFTY": "NIFTY", "SENSEX": "NIFTY", "FINNIFTY": "NIFTY", "MIDCPNIFTY": "NIFTY",
                  "BANKNIFTY": "BANKNIFTY"}[und]
            row["S10"] = r[f"S10_{ro}"]
            rows.append(row)
    f = pd.DataFrame(rows)
    f.to_parquet(f"{H}/feat.parquet")
    print(f.groupby("und").day.agg(["min", "max", "count"]))
    print(f.drop(columns=["und", "day", "src"]).describe().T.round(2).to_string())


if __name__ == "__main__":
    main()
