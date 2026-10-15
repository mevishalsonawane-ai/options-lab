#!/usr/bin/env python3
"""Validate the data lake against independent sources and write research/data/VALIDATION.md.

  python -I validate.py LAKE_DIR BHAV_DIR [OUT_MD]
  python -I validate.py --spikes [LAKE_DIR]   # rewrite only section 6 (bad ticks) of VALIDATION.md/.json, from the
                                              # option files in LAKE_DIR, else from CATALOG.json qa.spikes

LAKE_DIR  : built Parquet files (lake_build.py output, with _qa/*.json)
BHAV_DIR  : sampled NSE F&O bhavcopies (bhav_fetch.py), plus BHAV_DIR/idx/<date>.csv (NSE all-index close)
BHAV_DIR/yahoo : Yahoo chart JSON for ^NSEI ^NSEBANK ^INDIAVIX ^BSESN (also written by bhav_fetch.py)
"""
import glob, io, json, os, sys, zipfile
import numpy as np, pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import spikes

LAKE = BHAV = YAH = None
OUT = os.path.join(HERE, "VALIDATION.md")
R = {}  # machine-readable summary
md = []


def pct(a, b):
    return f"{100.0 * a / b:.2f}%" if b else "n/a"


def H(s):
    md.append(f"\n## {s}\n")


def table(rows, cols):
    md.append("| " + " | ".join(cols) + " |")
    md.append("|" + "---|" * len(cols))
    for r in rows:
        md.append("| " + " | ".join(str(r.get(c, "")) for c in cols) + " |")
    md.append("")


# ---------------------------------------------------------------- sources
def load_bhav():
    rows = []
    for f in sorted(glob.glob(f"{BHAV}/*.csv.zip")):
        d = pd.Timestamp(os.path.basename(f)[:10])
        z = zipfile.ZipFile(f)
        b = pd.read_csv(io.BytesIO(z.read(z.namelist()[0])))
        if "INSTRUMENT" in b:
            b = b[b.INSTRUMENT.isin(["FUTIDX", "OPTIDX", "OPTSTK", "FUTSTK"])]
            x = pd.DataFrame(dict(date=d, kind=np.where(b.INSTRUMENT.str.startswith("FUT"), "FUT", "OPT"), symbol=b.SYMBOL,
                                  expiry=pd.to_datetime(b.EXPIRY_DT, format="%d-%b-%Y"), strike=b.STRIKE_PR.astype(float),
                                  opt=b.OPTION_TYP.str[:1], open=b.OPEN, high=b.HIGH, low=b.LOW, close=b.CLOSE, last=b.CLOSE, settle=b.SETTLE_PR,
                                  oi=b.OPEN_INT, traded=b.CONTRACTS > 0))
        else:
            b = b[b.FinInstrmTp.isin(["IDF", "IDO", "STO", "STF"])]
            x = pd.DataFrame(dict(date=d, kind=np.where(b.FinInstrmTp.isin(["IDF", "STF"]), "FUT", "OPT"), symbol=b.TckrSymb,
                                  expiry=pd.to_datetime(b.XpryDt), strike=b.StrkPric.fillna(0).astype(float),
                                  opt=b.OptnTp.fillna("X").str[:1], open=b.OpnPric, high=b.HghPric, low=b.LwPric, close=b.ClsPric, last=b.LastPric,
                                  settle=b.SttlmPric, oi=b.OpnIntrst, traded=b.TtlTradgVol > 0))
        rows.append(x)
    return pd.concat(rows, ignore_index=True)


def yahoo(tick):
    j = json.load(open(f"{YAH}/%5E{tick}.json"))["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    idx = pd.to_datetime(np.array(j["timestamp"]) + j["meta"]["gmtoffset"], unit="s").normalize()
    d = pd.DataFrame({k: q[k] for k in ("open", "high", "low", "close")}, index=idx).dropna()
    return d[~d.index.duplicated(keep="last") & (d.close > 0)]


def nse_index_close():
    m = {"Nifty 50": "NIFTY", "Nifty Bank": "BANKNIFTY", "Nifty Financial Services": "FINNIFTY",
         "Nifty Midcap Select": "MIDCPNIFTY", "India VIX": "INDIAVIX"}
    out = []
    for f in sorted(glob.glob(f"{BHAV}/idx/*.csv")):
        x = pd.read_csv(f)
        x = x[x["Index Name"].isin(m)]
        out.append(pd.DataFrame(dict(date=pd.Timestamp(os.path.basename(f)[:10]), symbol=x["Index Name"].map(m),
                                     close=pd.to_numeric(x["Closing Index Value"], errors="coerce"),
                                     high=pd.to_numeric(x["High Index Value"], errors="coerce"),
                                     low=pd.to_numeric(x["Low Index Value"], errors="coerce"))))
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


# ---------------------------------------------------------------- checks
def check_index_daily():
    H("1. Index daily candles vs Yahoo Finance and NSE")
    dd = pd.read_parquet(f"{LAKE}/index_daily.parquet")
    rows = []
    for sym, tick in (("NIFTY", "NSEI"), ("BANKNIFTY", "NSEBANK"), ("INDIAVIX", "INDIAVIX"), ("SENSEX", "BSESN")):
        a = dd[dd.symbol == sym].set_index("date")
        y = yahoo(tick)
        lo = max(a.index.min(), y.index.min())
        y = y[y.index >= lo]; a2 = a[a.index >= lo]
        com = a2.index.intersection(y.index)
        rel = (a2.loc[com, "close"] / y.loc[com, "close"] - 1).abs()
        rows.append(dict(symbol=sym, source="Yahoo", dhan_range=f"{a.index.min().date()}..{a.index.max().date()}",
                         compared=len(com), **{"match <=0.05%": pct((rel <= 0.0005).sum(), len(com)),
                         "match <=0.5%": pct((rel <= 0.005).sum(), len(com)), ">2% (bad)": int((rel > 0.02).sum()),
                         "days only in Dhan": len(a2.index.difference(y.index)),
                         "days only in Yahoo": len(y.index.difference(a2.index))}))
        R[f"index_daily_yahoo_{sym}"] = rows[-1]
        big = rel[rel > 0.02]
        if len(big):
            md.append(f"- {sym}: >2% close differences vs Yahoo on {', '.join(str(x.date()) for x in big.index[:8])}"
                      f"{' ...' if len(big) > 8 else ''} ({len(big)} days; not checked against NSE for those dates)")
    n = nse_index_close()
    for sym, g in n.groupby("symbol"):
        a = dd[dd.symbol == sym].set_index("date")
        g = g.set_index("date")
        com = a.index.intersection(g.index)
        rel = (a.loc[com, "close"] / g.loc[com, "close"] - 1).abs()
        rows.append(dict(symbol=sym, source="NSE ind_close_all (sample)", dhan_range=f"{a.index.min().date()}..{a.index.max().date()}",
                         compared=len(com), **{"match <=0.05%": pct((rel <= 0.0005).sum(), len(com)),
                         "match <=0.5%": pct((rel <= 0.005).sum(), len(com)), ">2% (bad)": int((rel > 0.02).sum()),
                         "days only in Dhan": "", "days only in Yahoo": f"{len(g.index.difference(a.index))} sample days missing in Dhan"}))
        R[f"index_daily_nse_{sym}"] = rows[-1]
    table(rows, ["symbol", "source", "dhan_range", "compared", "match <=0.05%", "match <=0.5%", ">2% (bad)",
                 "days only in Dhan", "days only in Yahoo"])


def check_index_1m():
    H("2. Index 1-minute bars: completeness and agreement with the daily candle")
    dd = pd.read_parquet(f"{LAKE}/index_daily.parquet")
    rows = []
    for sym in sorted({os.path.basename(f).split("_")[2] for f in glob.glob(f"{LAKE}/index_1m_*.parquet")}):
        m = pd.concat([pd.read_parquet(f) for f in sorted(glob.glob(f"{LAKE}/index_1m_{sym}_*.parquet"))])
        m["date"] = m.ts.dt.tz_localize(None).dt.normalize()
        g = m.groupby("date").agg(n=("close", "size"), hi=("high", "max"), lo=("low", "min"), last=("close", "last"))
        a = dd[dd.symbol == sym].set_index("date")
        a = a[(a.index >= g.index.min()) & (a.index <= g.index.max())]
        com = g.index.intersection(a.index)
        relh = (g.loc[com, "hi"] / a.loc[com, "high"] - 1).abs()
        rell = (g.loc[com, "lo"] / a.loc[com, "low"] - 1).abs()
        relc = (g.loc[com, "last"] / a.loc[com, "close"] - 1).abs()
        missing_days = a.index.difference(g.index)
        full = (g.n >= 375).sum()
        rows.append({"symbol": sym, "range": f"{g.index.min().date()}..{g.index.max().date()}", "days": len(g),
                     "rows": len(m), "days with 375 bars": pct(full, len(g)),
                     "days <300 bars": int((g.n < 300).sum()),
                     "daily dates missing in 1m": len(missing_days),
                     "1m high = daily high (<=0.05%)": pct((relh <= 0.0005).sum(), len(com)),
                     "1m low = daily low (<=0.05%)": pct((rell <= 0.0005).sum(), len(com)),
                     "15:29 close vs daily close, median": f"{relc.median() * 100:.3f}%"})
        R[f"index_1m_{sym}"] = rows[-1]
        if len(missing_days):
            md.append(f"- {sym}: daily dates with no minute bars: {', '.join(str(x.date()) for x in missing_days[:10])}"
                      f"{' ...' if len(missing_days) > 10 else ''}")
        short = g[g.n < 300]
        if len(short):
            md.append(f"- {sym}: short days (<300 bars, usually special sessions or outages): "
                      + ", ".join(f"{d.date()} ({n})" for d, n in short.n.items()))
    table(rows, list(rows[0]))
    md.append("The index close is NSE's/BSE's official close (computed from constituents' closing prices), so it "
              "differs slightly from the last traded minute; the high/low agreement is the stronger test.\n")


def pick_expiry(exps, d, monthly, strict):
    e = sorted(exps)
    if monthly:
        s = pd.Series(e)
        e = sorted(s.groupby([s.dt.year, s.dt.month]).max())
    for x in e:
        if (x > d) if strict else (x >= d):
            return x
    return None


def contract_days(und, flag, dates, pattern=None):
    """Per (date, strike, side): the contract's whole-day OHLCV rebuilt from all ATM offsets, plus its last-bar OI.
    Kept only when the contract was inside the ATM-10..+10 band all day (so the minutes are complete)."""
    out, sizes = [], []
    for f in sorted(glob.glob(pattern or f"{LAKE}/opt_{und}_{flag}_*.parquet")):
        x = pd.read_parquet(f, columns=["ts", "offset", "side", "open", "high", "low", "close", "volume", "oi", "strike"])
        x["date"] = x.ts.dt.tz_localize(None).dt.normalize()
        sizes.append(x.groupby(["date", "offset", "side"], observed=True).size())
        x = x[x.date.isin(dates)]
        if not len(x):
            continue
        atm = x[x.offset == 0].groupby("date").strike.agg(["min", "max"])
        step = (x[x.offset == 1].groupby("date").strike.median() - x[x.offset == 0].groupby("date").strike.median()).abs()
        x = x.sort_values("ts")
        g = x.groupby(["date", "strike", "side"], observed=True)
        c = g.agg(open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"),
                  volume=("volume", "sum"), oi=("oi", "last"), first_ts=("ts", "first"), last_ts=("ts", "last")).reset_index()
        c = c.join(atm, on="date").join(step.rename("step"), on="date")
        band = int(x.offset.abs().max())  # 10 for index options, 5 for stock/MCX options
        inband = (c.strike >= c["min"]) & (c.strike <= c["max"]) & ((c["max"] - c["min"]) <= band * c.step)
        out.append(c[inband])
    if not out:
        return None, None
    return pd.concat(out, ignore_index=True), (pd.concat(sizes) if sizes else None)


def check_options(bh):
    H("3. Expired options (rolling ATM-10..ATM+10, 1-minute) vs NSE F&O bhavcopy")
    md.append("Method: for every sampled bhavcopy date, each contract's whole-day candle is rebuilt from the Dhan minute "
              "bars of all offsets carrying that strike, using only contracts that stayed inside the fetched ATM band (±10 index, ±5 stocks) all day, "
              "so their minutes are complete. That candle is matched to NSE's bhavcopy row for the same strike and type at "
              "the nearest weekly (WEEK) or monthly (MONTH) expiry on or after the date. Only contracts NSE shows as "
              "traded are scored. *High/low/open match*: within 0.05 or 0.5%. *Last price*: Dhan's last minute close vs "
              "NSE `LastPric`. That column only exists in the UDiFF bhavcopy, from 8 Jul 2024; the old format's `CLOSE` is "
              "not a last-trade price. *OI*: Dhan's OI on the last minute bar vs NSE end-of-day OI.\n")
    rows, comp = [], []
    opt = bh[bh.kind == "OPT"]
    stocks = sorted(os.path.basename(f)[7:-14] for f in glob.glob(f"{LAKE}/stkopt_*_MONTH.parquet"))
    for und in ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"] + stocks:
        b = opt[opt.symbol == und]
        if not len(b):
            continue
        dates = set(b.date)
        stock = und in stocks
        for flag in (("MONTH",) if stock else ("WEEK", "MONTH")):
            c, sizes = contract_days(und, flag, dates, f"{LAKE}/stkopt_{und}_MONTH.parquet" if stock else None)
            if c is None:
                continue
            if sizes is not None and stock:
                R[f"stkopt_{und}_bars"] = dict(series_days=int(len(sizes)), full_375=float((sizes >= 375).mean()))
            elif sizes is not None:
                comp.append({"series": f"{und} {flag}", "series-days": len(sizes),
                             "days with 375 bars": f"{100 * (sizes >= 375).mean():.1f}%",
                             "days <300 bars": int((sizes < 300).sum())})
            exp = {d: pick_expiry(list(g.expiry.unique()), d, flag == "MONTH", False) for d, g in b.groupby("date")}
            c["expiry"] = c.date.map(exp)
            m = c.merge(b.rename(columns={"opt": "side"}), on=["date", "expiry", "strike", "side"], how="left",
                        suffixes=("", "_n"))
            found = m.close_n.notna()
            m = m[found & m.traded.fillna(False).astype(bool)]
            tol = lambda a, n: ((a - n).abs() <= 0.05) | ((a / n - 1).abs() <= 0.005)
            hi, lo, op = tol(m.high, m.high_n), tol(m.low, m.low_n), tol(m.open, m.open_n)
            new = m[m.date >= "2024-07-08"]
            lp = new[new["last"] > 0]
            rl = (lp.close / lp["last"] - 1).abs()
            oir = (m.oi / m.oi_n.replace(0, np.nan))
            ed = m.date == m.expiry
            rr = {"underlying": und, "flag": flag, "days": m.date.nunique(), "contract-days": len(m),
                  "found in bhavcopy": pct(found.sum(), len(found)),
                  "high match": pct(hi.sum(), len(m)), "low match": pct(lo.sum(), len(m)), "open match": pct(op.sum(), len(m)),
                  "last price <=0.5% (2024-07+)": pct((rl <= 0.005).sum(), len(rl)),
                  "last price <=2% (2024-07+)": pct((rl <= 0.02).sum(), len(rl)),
                  "OI Dhan/NSE median": f"{oir.median():.3f}", "OI within 1%": pct(((oir - 1).abs() <= 0.01).sum(), len(m)),
                  "high&low match on expiry days": pct((hi & lo)[ed].sum(), ed.sum())}
            rows.append(rr)
            R[f"opt_{und}_{flag}"] = rr
            bad = m[~(hi & lo)].assign(err=lambda d: np.maximum((d.high / d.high_n - 1).abs(), (d.low / d.low_n - 1).abs()))
            R[f"opt_{und}_{flag}_worst"] = bad.sort_values("err", ascending=False).head(5)[
                ["date", "expiry", "strike", "side", "high", "high_n", "low", "low_n"]].astype(str).to_dict("records")
    idx_rows = [r for r in rows if r["underlying"] in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")]
    stk_rows = [r for r in rows if r not in idx_rows and r["contract-days"] > 0]
    if idx_rows:
        table(idx_rows, list(idx_rows[0]))
    if stk_rows:
        num = lambda v: float(str(v).rstrip("%")) if str(v).rstrip("%").replace(".", "", 1).isdigit() else np.nan
        agg = {"underlying": f"{len(stk_rows)} stocks (median of per-stock rates)", "flag": "MONTH",
               "days": int(np.median([r["days"] for r in stk_rows])),
               "contract-days": sum(r["contract-days"] for r in stk_rows)}
        for k in list(stk_rows[0])[4:]:
            vals = [num(r[k]) for r in stk_rows]
            agg[k] = f"{np.nanmedian(vals):.2f}%" if "median" not in k else f"{np.nanmedian(vals):.3f}"
        table([agg], list(agg))
        R["stkopt_median"] = agg
        md.append("Stock options: the median contract-day matches NSE exactly, but about 30% of contract-days differ by "
                  "more than 0.5% at the high or low (5th/95th percentile about -7%/+13%). Some show minute prices NSE never "
                  "printed for that strike, which suggests strikes are mislabelled in Dhan's rolling stock-option data. "
                  "That cause is not established. Use stock-option minutes with care; check against bhavcopy before "
                  "trusting extremes.\n")
        worst = sorted(stk_rows, key=lambda r: num(r["high match"]))[:5]
        md.append("Stock options with the weakest high-match rate: " + ", ".join(
            f"{r['underlying']} {r['high match']}" for r in worst) + " (per-stock numbers in VALIDATION.json).\n")
    for r in idx_rows:
        w = R.get(f"opt_{r['underlying']}_{r['flag']}_worst")
        if w:
            md.append(f"- {r['underlying']} {r['flag']} largest high/low mismatches (date expiry strike side: Dhan H/L vs NSE H/L): "
                      + "; ".join(f"{x['date'][:10]} {x['expiry'][:10]} {x['strike']} {x['side']}: "
                                  f"{x['high']}/{x['low']} vs {x['high_n']}/{x['low_n']}" for x in w[:3]))
    md.append("")
    if comp:
        md.append("Minute completeness per (series, day). Options do not trade every minute, so missing minutes are real "
                  "no-trade minutes and are not filled:\n")
        table(comp, list(comp[0]))


def check_futures(bh):
    H("4. Continuous index futures daily (near month) vs NSE F&O bhavcopy")
    md.append("Dhan's `/charts/historical` returns the identical near-month series for expiryCode 0, 1 and 2 (checked: "
              "100% equal), so only code 0 is published. The series rolls to the next contract on expiry day itself, so "
              "expiry days compare against the next expiry here.\n")
    fd = pd.read_parquet(f"{LAKE}/fut_daily_continuous.parquet")
    fut = bh[bh.kind == "FUT"]
    rows = []
    for und in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"):
        for code in (0,):
            a = fd[(fd.symbol == und) & (fd.expiry_code == code)].set_index("date")
            n = c = o = 0
            for d, g in fut[fut.symbol == und].groupby("date"):
                if d not in a.index:
                    continue
                ex = sorted(g.expiry)
                ex = [x for x in ex if x > d]  # Dhan rolls on expiry day
                if len(ex) <= code:
                    continue
                r = g[g.expiry == ex[code]].iloc[0]
                n += 1
                c += abs(a.loc[d, "close"] / r["close"] - 1) <= 0.0005
                if "oi" in a:
                    o += a.loc[d, "oi"] == r["oi"]
            rr = {"underlying": und, "expiry code": code, "compared days": n, "close match (<=0.05%)": pct(c, n),
                  "OI exact": pct(o, n)}
            rows.append(rr)
            R[f"fut_{und}_c{code}"] = rr
    table(rows, list(rows[0]))


def check_qa():
    H("5. Per-file integrity (from lake_build QA)")
    rows = []
    for f in sorted(glob.glob(f"{LAKE}/_qa/*.json")):
        m = json.load(open(f)); q = m["qa"]
        rows.append({"file": m["file"], "rows": q["rows"], "first": q.get("first", "")[:16], "last": q.get("last", "")[:16],
                     "dups dropped": q.get("duplicates_dropped", q.get("duplicates_dropped_series_total",
                                                                       q.get("duplicates_dropped_symbol_total", ""))),
                     "rows dropped (session/calendar)": sum((q.get("rows_dropped") or q.get("rows_dropped_series_total")
                                                             or q.get("rows_dropped_symbol_total") or {}).values()),
                     "price<=0": q.get("nonpositive_price_rows", ""), "high<low": q.get("high_lt_low", ""),
                     "close outside H-L": q.get("close_outside_hl", ""), "open outside H-L": q.get("open_outside_hl", ""),
                     "weekend rows": q.get("weekend_rows", ""), "IV null/0": q.get("iv_null_or_zero", "")})
    table(rows, list(rows[0]))


SPIKE_H = "6. Bad ticks: one-minute price spikes in option files"
SPIKE_SUMMARY = "- Bad ticks (one-minute spikes"


def check_spikes():
    """Section 6. Per file from LAKE option files when present, else from CATALOG.json qa.spikes. Returns summary line."""
    H(SPIKE_H)
    md.append("A *spike* is a contract-minute whose close is more than 50% above both neighbouring closes, or below "
              "both by more than a factor 1.5, while the two neighbours are within 50% of each other (the price "
              "reverts). Neighbours are the previous and next bar of the same strike and side on the same day (no-trade "
              "minutes are skipped); a sustained jump, or a spike lasting two minutes, is not counted. Rows are "
              "de-duplicated on (ts, strike, side); zero/missing prices are left out. Share = spikes / contract-minutes. "
              "*Early* = 09:15-10:44 IST for NSE/BSE, 09:00-10:44 (the first 105 minutes) for MCX; *late* = 10:45 onward. "
              "Exact definition and tests: `spikes.py`, `test_spikes.py`.\n")
    cat = json.load(open(os.path.join(HERE, "CATALOG.json")))["files"]
    per = {}
    for e in cat:
        b = spikes.book(e["file"])
        if not b:
            continue
        fn = f"{LAKE}/{e['file']}" if LAKE else None
        m = spikes.file_spikes(fn) if fn and os.path.exists(fn) else e.get("qa", {}).get("spikes")
        if m:
            per[e["file"]] = dict(m, book=b, same_contract_as=e.get("same_contract_as"))
    R["spikes_per_file"] = {f: {k: v for k, v in m.items() if k != "book"} for f, m in per.items()}
    books = {}
    for f, m in per.items():
        und, flag, _ = m["book"]
        key = ("stock options" if f.startswith("stkopt_") else und, flag)
        a = books.setdefault(key, dict(files=0, **{f"{p}{k}": 0 for p in ("", "early_", "late_") for k in ("minutes", "spikes")}))
        a["files"] += 1
        for k in list(a):
            if k != "files":
                a[k] += m[k]
    sh = lambda a, p: f"{100 * a[f'{p}spikes'] / a[f'{p}minutes']:.3f}%" if a[f"{p}minutes"] else "n/a"
    rows = []
    for (und, flag), a in sorted(books.items(), key=lambda kv: (kv[0][0] == "stock options", spikes.exchange(kv[0][0]), kv[0])):
        rows.append({"book": f"{und} {flag}", "exchange": "MCX" if spikes.exchange(und) == "MCX" else "NSE/BSE", "files": a["files"],
                     "contract-minutes": a["minutes"], "spikes": a["spikes"], "share": sh(a, ""),
                     "share early": sh(a, "early_"), "share late": sh(a, "late_")})
        R[f"spikes_{und}_{flag}".replace(" ", "_")] = rows[-1]
    missing = sorted(e["file"] for e in cat if spikes.book(e["file"]) and e["file"] not in per)
    if not rows:
        md.append("No spike counts yet (run `make_catalog.py --annotate LAKE_DIR` or pass LAKE_DIR).\n")
        return f"{SPIKE_SUMMARY}): not computed yet."
    table(rows, list(rows[0]))
    if missing:
        md.append(f"Not counted (file not available): {', '.join(missing)}.\n")
    stk = sorted(((m["share"] or 0, f) for f, m in per.items() if f.startswith("stkopt_")), reverse=True)[:5]
    if stk:
        md.append("Stock options with the highest spike share: " + ", ".join(
            f"{f[7:-14]} {100 * v:.3f}%" for v, f in stk) + " (per file in VALIDATION.json `spikes_per_file`).\n")
    mcx = [r for r in rows if r["exchange"] == "MCX"]
    md.append("### Known issues\n")
    md.append("- Bad-tick share per book (spikes / contract-minutes): " + ", ".join(f"{r['book']} {r['share']}" for r in rows)
              + ". Books where the early window has the lower share: "
              + f"{sum(a['early_spikes'] * a['late_minutes'] < a['late_spikes'] * a['early_minutes'] for a in books.values())} of {len(rows)}"
              + ". Drop or cap flagged minutes "
              "(`spikes.spike_flags`) before using minute extremes, e.g. for stop-loss or high/low studies.")
    why = {}
    for e in cat:
        if e.get("same_contract_as") and e["file"] < e["same_contract_as"]:
            why.setdefault(e.get("same_contract_reason", ""), []).append(f"{e['file']} = {e['same_contract_as']}")
    for r, ps in why.items():
        md.append(f"- Same contract in two byte-identical files (`same_contract_as` in CATALOG.json; each is counted in "
                  f"its own book above): {'; '.join(ps)}. Why: {r}")
    md.append("")
    return (f"{SPIKE_SUMMARY}, section 6): " + ", ".join(f"{r['book'].split()[0]} {r['share']}" for r in mcx)
            + " of contract-minutes in MCX option books; index books "
            + ", ".join(f"{r['book']} {r['share']}" for r in rows if r["exchange"] != "MCX" and r["book"] != "stock options MONTH")
            + (f"; stock options {next(r['share'] for r in rows if r['book'] == 'stock options MONTH')}"
               if any(r["book"] == "stock options MONTH" for r in rows) else "") + ".")


def spikes_only():
    """Replace section 6 and its summary line in the existing VALIDATION.md / VALIDATION.json."""
    global R
    old = open(OUT).read()
    R = json.load(open(OUT.replace(".md", ".json")))
    R = {k: v for k, v in R.items() if not k.startswith("spikes_")}
    md.clear()
    line = check_spikes()
    sec = "\n".join(md) + "\n"
    i = old.find(f"\n## {SPIKE_H}")
    old = old[:i].rstrip("\n") + "\n" if i >= 0 else old
    lines = [l for l in old.split("\n") if not l.startswith(SPIKE_SUMMARY)]
    j = next(k for k, l in enumerate(lines) if l.startswith("- No prices were invented"))
    lines.insert(j, line)
    open(OUT, "w").write("\n".join(lines) + sec)
    json.dump(R, open(OUT.replace(".md", ".json"), "w"), indent=1, default=str)
    print("wrote section 6 of", OUT)


if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "--spikes":
    LAKE = sys.argv[2] if len(sys.argv) > 2 else None
    spikes_only()
elif __name__ == "__main__":
    LAKE, BHAV = sys.argv[1:3]
    YAH = os.path.join(BHAV, "yahoo")
    OUT = sys.argv[3] if len(sys.argv) > 3 else OUT
    md.append("# Data lake validation\n")
    md.append(f"Generated by `research/data/validate.py` on {pd.Timestamp.now(tz='Asia/Kolkata'):%Y-%m-%d %H:%M} IST. "
              "All timestamps in the lake are IST (Asia/Kolkata); minute bars are stamped at the bar's start "
              "(09:15 = first NSE minute, 15:29 = last).\n")
    bh = load_bhav()
    md.append(f"NSE F&O bhavcopies used: {bh.date.nunique()} trading days sampled about weekly from "
              f"{bh.date.min().date()} to {bh.date.max().date()} (old CSV format to 5 Jul 2024, UDiFF after).\n")
    head = len(md)
    check_index_daily()
    check_index_1m()
    check_options(bh)
    check_futures(bh)
    check_qa()
    spike_line = check_spikes()
    g = lambda k, f: R.get(k, {}).get(f, "n/a")
    summ = ["\n## Summary\n",
            f"- Index daily closes: NIFTY {g('index_daily_yahoo_NIFTY', 'match <=0.05%')}, BANKNIFTY "
            f"{g('index_daily_yahoo_BANKNIFTY', 'match <=0.05%')}, SENSEX {g('index_daily_yahoo_SENSEX', 'match <=0.05%')} "
            f"within 0.05% of Yahoo. Against NSE's official closes (sampled days) NIFTY {g('index_daily_nse_NIFTY', 'match <=0.05%')}, "
            f"BANKNIFTY {g('index_daily_nse_BANKNIFTY', 'match <=0.05%')}, FINNIFTY {g('index_daily_nse_FINNIFTY', 'match <=0.05%')}, "
            f"MIDCPNIFTY {g('index_daily_nse_MIDCPNIFTY', 'match <=0.05%')}, INDIAVIX {g('index_daily_nse_INDIAVIX', 'match <=0.05%')}.",
            f"- Index 1-minute: NIFTY has 375 bars on {g('index_1m_NIFTY', 'days with 375 bars')} of days; Muhurat sessions "
            "are missing from Dhan's minute data, and BANKEX/SENSEX have short days in Aug–Sep 2021 (see section 2).",
            f"- Expired options vs NSE bhavcopy (contract-day high/low): NIFTY weekly {g('opt_NIFTY_WEEK', 'high match')}/"
            f"{g('opt_NIFTY_WEEK', 'low match')}, BANKNIFTY weekly {g('opt_BANKNIFTY_WEEK', 'high match')}/"
            f"{g('opt_BANKNIFTY_WEEK', 'low match')}. Last price within 2% of NSE's last trade (Jul 2024 onward): NIFTY weekly "
            f"{g('opt_NIFTY_WEEK', 'last price <=2% (2024-07+)')}. Dhan's last-minute OI is a median "
            f"{g('opt_NIFTY_WEEK', 'OI Dhan/NSE median')}× NSE's end-of-day OI (cause unknown), so treat it as "
            "an intraday OI series, not the official end-of-day figure.",
            f"- Continuous futures (near month) close vs NSE: NIFTY {g('fut_NIFTY_c0', 'close match (<=0.05%)')}, BANKNIFTY "
            f"{g('fut_BANKNIFTY_c0', 'close match (<=0.05%)')}, MIDCPNIFTY {g('fut_MIDCPNIFTY_c0', 'close match (<=0.05%)')} "
            "(MIDCPNIFTY's early 2022 history has zero prices, which were dropped).",
            f"- Stock options ({R.get('stkopt_median', {}).get('underlying', 'n/a')}): high/low match "
            f"{R.get('stkopt_median', {}).get('high match', 'n/a')}/{R.get('stkopt_median', {}).get('low match', 'n/a')} — "
            "clearly weaker than index options; see section 3. Coverage is partial (README, STOCK_OPTIONS_COVERAGE.json).",
            spike_line,
            "- No prices were invented: gaps stay gaps; dropped rows are counted per file in section 5 and CATALOG.json.\n"]
    md[head:head] = summ
    open(OUT, "w").write("\n".join(md) + "\n")
    json.dump(R, open(OUT.replace(".md", ".json"), "w"), indent=1, default=str)
    print("wrote", OUT)
