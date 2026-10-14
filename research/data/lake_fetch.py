#!/usr/bin/env python3
"""Data lake fetcher: pulls raw chunks from Dhan API v2 into RAW/<group>/<series>/<from>.parquet.

Resumable: every finished chunk leaves either a .parquet or a .empty marker (or .err with the
short, redacted error) and is skipped next time. Credentials come from the environment (see
dhan_client.py) and are never written anywhere.

Usage:  python -I lake_fetch.py RAW_DIR [--only-missing LAKE_DIR] [--before YYYY-MM-DD] GROUP [GROUP ...]
  --only-missing LAKE_DIR  for option groups, first turn the published files in LAKE_DIR (lake_build.py output,
                           e.g. downloaded from Drive) back into raw chunks, so only chunks with no published rows
                           are requested. Chunks whose window reaches the last published day are not seeded
                           (they are fetched again, to pick up the newer days).
  --before DATE            only request chunks whose window ends before DATE (e.g. today during market hours)
  --since DATE             only request chunks whose window ends on or after DATE (e.g. to append new days)
Groups: idx_daily idx_1m fut_daily fut_1m eq_daily opt_<UNDERLYING>_<WEEK|MONTH> mcxopt
        eq_1m stkfut_daily stkfut_1m stkopt   (constituents of NIFTY 50 + NIFTY BANK)
"""
import os, sys, csv, glob, io, json, time, datetime as dt, concurrent.futures as cf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import pandas as pd
import requests
import dhan_client as dh

TODAY = dt.date.today()
INDICES = {  # name: (security id, exchange segment)
    "NIFTY": (13, "IDX_I"), "BANKNIFTY": (25, "IDX_I"), "FINNIFTY": (27, "IDX_I"), "MIDCPNIFTY": (442, "IDX_I"),
    "SENSEX": (51, "IDX_I"), "BANKEX": (69, "IDX_I"), "INDIAVIX": (21, "IDX_I")}
OPT_UND = {"NIFTY": (13, "NSE_FNO"), "BANKNIFTY": (25, "NSE_FNO"), "FINNIFTY": (27, "NSE_FNO"),
           "MIDCPNIFTY": (442, "NSE_FNO"), "SENSEX": (51, "BSE_FNO"), "BANKEX": (69, "BSE_FNO")}
MCX_UND = {"CRUDEOIL": 294, "NATURALGAS": 401, "GOLD": 114, "GOLDM": 117, "SILVER": 115, "SILVERM": 122}
IST = "Asia/Kolkata"
RD = ["open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot"]
MASTER_URL = "https://images.dhan.co/api-data/api-scrip-master-detailed.csv"


def log(*a):
    print(dh.redact(" ".join(map(str, a))), flush=True)


def arrays_df(js):
    if not js or not js.get("timestamp"):
        return pd.DataFrame()
    cols = [k for k, v in js.items() if isinstance(v, list) and len(v) == len(js["timestamp"])]
    return pd.DataFrame({k: js[k] for k in cols})


def windows(start, end, days):
    out, a = [], start
    while a <= end:
        b = min(a + dt.timedelta(days=days - 1), end)
        out.append((a, b))
        a = b + dt.timedelta(days=1)
    return out


class Job:
    def __init__(self, path, endpoint, body, key=None):
        self.path, self.endpoint, self.body, self.key = path, endpoint, body, key

    def done(self):
        return any(os.path.exists(self.path + s) for s in (".parquet", ".empty"))

    def run(self):
        st, js, err = dh.post(self.endpoint, self.body)
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        if err:
            open(self.path + ".err", "w").write(err)
            return "err", err
        if self.key:
            js = ((js or {}).get("data") or {}).get(self.key) or {}
        df = arrays_df(js)
        if not len(df):
            open(self.path + ".empty", "w").write("")
            res = "empty", 0
        else:
            df.to_parquet(self.path + ".parquet.tmp", compression="zstd")
            os.replace(self.path + ".parquet.tmp", self.path + ".parquet")
            res = "ok", len(df)
        if os.path.exists(self.path + ".err"):  # an earlier failed try of this chunk
            os.remove(self.path + ".err")
        return res


def master(raw):
    fn = f"{raw}/master/api-scrip-master-detailed.csv"
    if not os.path.exists(fn):
        os.makedirs(os.path.dirname(fn), exist_ok=True)
        r = requests.get(MASTER_URL, timeout=120)
        r.raise_for_status()
        open(fn, "wb").write(r.content)
    return list(csv.DictReader(open(fn)))


def constituents(raw):
    """Current NIFTY 50 + NIFTY BANK members (niftyindices.com lists) -> NSE EQ security id."""
    rows = master(raw)
    eq = {r["UNDERLYING_SYMBOL"]: r["SECURITY_ID"] for r in rows
          if r["EXCH_ID"] == "NSE" and r["SEGMENT"] == "E" and r["SERIES"] == "EQ"}
    syms = set()
    for f in ("ind_nifty50list.csv", "ind_niftybanklist.csv"):
        fn = f"{raw}/master/{f}"
        if not os.path.exists(fn):
            r = requests.get(f"https://www.niftyindices.com/IndexConstituent/{f}", timeout=60,
                             headers={"User-Agent": "Mozilla/5.0"})
            r.raise_for_status()
            open(fn, "wb").write(r.content)
        syms |= {r["Symbol"] for r in csv.DictReader(io.StringIO(open(fn).read()))}
    for s in sorted(syms - set(eq)):
        log("constituents: no NSE EQ id for", s)
    return {s: eq[s] for s in sorted(syms) if s in eq}


def jobs_for(group, raw):
    J = []
    if group == "idx_daily":
        for n, (sid, seg) in INDICES.items():
            J.append(Job(f"{raw}/idx_daily/{n}/all", "/charts/historical", dict(
                securityId=str(sid), exchangeSegment=seg, instrument="INDEX", expiryCode=0, oi=False,
                fromDate="1990-01-01", toDate=(TODAY + dt.timedelta(days=1)).isoformat())))
    elif group == "idx_1m":
        for n, (sid, seg) in INDICES.items():
            for a, b in windows(dt.date(2015, 1, 1), TODAY, 85):
                J.append(Job(f"{raw}/idx_1m/{n}/{a}", "/charts/intraday", dict(
                    securityId=str(sid), exchangeSegment=seg, instrument="INDEX", interval="1", oi=False,
                    fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
    elif group in ("fut_daily", "fut_1m"):
        rows = master(raw)
        want = {"FUTIDX": set(OPT_UND), "FUTCOM": set(MCX_UND)}
        live = sorted((r["UNDERLYING_SYMBOL"], r["SM_EXPIRY_DATE"], r["SECURITY_ID"], r["INSTRUMENT"], r["EXCH_ID"])
                      for r in rows if r["INSTRUMENT"] in want and r["UNDERLYING_SYMBOL"] in want[r["INSTRUMENT"]]
                      and r["SEGMENT"] in ("D", "M"))
        seg = lambda ex, ins: "MCX_COMM" if ex == "MCX" else ("BSE_FNO" if ex == "BSE" else "NSE_FNO")
        if group == "fut_daily":  # continuous series by expiry code (0 = near, 1 = next, 2 = far)
            first = {}
            for s, e, sid, ins, ex in live:
                first.setdefault(s, (sid, ins, ex))
            for s, (sid, ins, ex) in first.items():
                for code in (0, 1, 2):
                    J.append(Job(f"{raw}/fut_daily/{s}/c{code}", "/charts/historical", dict(
                        securityId=sid, exchangeSegment=seg(ex, ins), instrument=ins, expiryCode=code, oi=True,
                        fromDate="2000-01-01", toDate=(TODAY + dt.timedelta(days=1)).isoformat())))
        else:  # live contracts only (Dhan does not serve expired futures)
            for s, e, sid, ins, ex in live:
                for a, b in windows(TODAY - dt.timedelta(days=400), TODAY, 85):
                    J.append(Job(f"{raw}/fut_1m/{s}/{e}_{sid}/{a}", "/charts/intraday", dict(
                        securityId=sid, exchangeSegment=seg(ex, ins), instrument=ins, interval="1", oi=True,
                        fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
    elif group in ("eq_daily", "eq_1m"):
        for s, sid in constituents(raw).items():
            if group == "eq_daily":
                J.append(Job(f"{raw}/eq_daily/{s}/all", "/charts/historical", dict(
                    securityId=sid, exchangeSegment="NSE_EQ", instrument="EQUITY", expiryCode=0, oi=False,
                    fromDate="1995-01-01", toDate=(TODAY + dt.timedelta(days=1)).isoformat())))
            else:
                for a, b in windows(dt.date(2015, 1, 1), TODAY, 85):
                    J.append(Job(f"{raw}/eq_1m/{s}/{a}", "/charts/intraday", dict(
                        securityId=sid, exchangeSegment="NSE_EQ", instrument="EQUITY", interval="1", oi=False,
                        fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
    elif group in ("stkfut_daily", "stkfut_1m"):
        cons = constituents(raw)
        live = sorted((r["UNDERLYING_SYMBOL"], r["SM_EXPIRY_DATE"], r["SECURITY_ID"]) for r in master(raw)
                      if r["EXCH_ID"] == "NSE" and r["INSTRUMENT"] == "FUTSTK" and r["UNDERLYING_SYMBOL"] in cons)
        if group == "stkfut_daily":
            first = {}
            for s, e, sid in live:
                first.setdefault(s, sid)
            for s, sid in first.items():
                for code in (0, 1, 2):
                    J.append(Job(f"{raw}/stkfut_daily/{s}/c{code}", "/charts/historical", dict(
                        securityId=sid, exchangeSegment="NSE_FNO", instrument="FUTSTK", expiryCode=code, oi=True,
                        fromDate="2000-01-01", toDate=(TODAY + dt.timedelta(days=1)).isoformat())))
        else:
            for s, e, sid in live:
                for a, b in windows(TODAY - dt.timedelta(days=400), TODAY, 85):
                    J.append(Job(f"{raw}/stkfut_1m/{s}/{e}_{sid}/{a}", "/charts/intraday", dict(
                        securityId=sid, exchangeSegment="NSE_FNO", instrument="FUTSTK", interval="1", oi=True,
                        fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
    elif group == "stkopt":
        fno = {r["UNDERLYING_SYMBOL"] for r in master(raw) if r["EXCH_ID"] == "NSE" and r["INSTRUMENT"] == "OPTSTK"}
        for s, sid in constituents(raw).items():
            if s not in fno:
                log("stkopt: no listed stock options for", s)
                continue
            for off in range(-5, 6):
                strike = "ATM" if off == 0 else f"ATM{off:+d}"
                for side, key in (("CALL", "ce"), ("PUT", "pe")):
                    for a, b in windows(dt.date(2021, 8, 1), TODAY, 30):
                        J.append(Job(f"{raw}/stkopt/{s}/MONTH/c1/{off:+03d}{side[0]}/{a}", "/charts/rollingoption",
                            dict(securityId=int(sid), exchangeSegment="NSE_FNO", instrument="OPTSTK", expiryFlag="MONTH",
                                 expiryCode=1, strike=strike, drvOptionType=side, requiredData=RD,
                                 fromDate=a.isoformat(), toDate=b.isoformat(), interval="1"), key=key))
    elif group.startswith("opt_"):
        _, und, flag = group.split("_")
        sid, seg = OPT_UND[und]
        for off in range(-10, 11):
            strike = "ATM" if off == 0 else f"ATM{off:+d}"
            for side, key in (("CALL", "ce"), ("PUT", "pe")):
                for a, b in windows(dt.date(2021, 8, 1), TODAY, 30):
                    J.append(Job(f"{raw}/opt/{und}/{flag}/c1/{off:+03d}{side[0]}/{a}", "/charts/rollingoption", dict(
                        securityId=sid, exchangeSegment=seg, instrument="OPTIDX", expiryFlag=flag, expiryCode=1,
                        strike=strike, drvOptionType=side, requiredData=RD, fromDate=a.isoformat(),
                        toDate=b.isoformat(), interval="1"), key=key))
    elif group == "mcxopt":
        for und, sid in MCX_UND.items():
            for off in range(-5, 6):
                strike = "ATM" if off == 0 else f"ATM{off:+d}"
                for side, key in (("CALL", "ce"), ("PUT", "pe")):
                    for a, b in windows(dt.date(2025, 6, 1), TODAY, 10):  # 30-day MCX windows often time out (504)
                        J.append(Job(f"{raw}/opt/{und}/MONTH/c1/{off:+03d}{side[0]}/{a}", "/charts/rollingoption",
                            dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH",
                                 expiryCode=1, strike=strike, drvOptionType=side, requiredData=RD,
                                 fromDate=a.isoformat(), toDate=b.isoformat(), interval="1"), key=key))
    else:
        raise SystemExit(f"unknown group {group}")
    if group == "stkopt":  # nearest strikes first for every stock, so a partial run is still a usable near-ATM set
        J.sort(key=lambda j: abs(int(j.path.split("/")[-2][:-1])))
    return J


CAP_N = 50  # this many answers in a row with one of these = Dhan refuses all data calls (daily cap, expired token): stop
FATAL = ("DH-905", "DH-901", "HTTP 401")


def fetch(raw, g, J, threads):
    """Runs the jobs; returns the stats, with stats["capped"] = True when the run stopped because of FATAL errors."""
    stats, t0, nfatal = {"ok": 0, "empty": 0, "err": 0}, time.time(), 0
    with cf.ThreadPoolExecutor(threads) as ex:
        futs = {ex.submit(j.run): j for j in J}
        for i, f in enumerate(cf.as_completed(futs), 1):
            if f.cancelled():
                continue
            try:
                k, v = f.result()
            except Exception as e:
                k, v = "err", f"{type(e).__name__}: {e}"
                open(futs[f].path + ".err", "w").write(dh.redact(v)[:300])
            stats[k] += 1
            nfatal = nfatal + 1 if k == "err" and any(c in str(v) for c in FATAL) else 0
            if k == "err":
                log("ERR", futs[f].path.split(raw)[-1], v)
            if nfatal >= CAP_N and not stats.get("capped"):
                stats["capped"] = True
                log(f"[{g}] {CAP_N} errors {FATAL} in a row: Dhan refuses data calls (daily cap or expired token); stopping")
                for x in futs:
                    x.cancel()
            if i % 200 == 0 or i == len(J):
                log(f"[{g}] {i}/{len(J)} {stats} {time.time() - t0:.0f}s")
    return stats


def window(j):
    """(first, last) date of a job's request window."""
    return (dt.date.fromisoformat(str(j.body["fromDate"])[:10]), dt.date.fromisoformat(str(j.body["toDate"])[:10]))


def published_files(lake, sub, und, flag):
    if sub == "stkopt":
        return [f for f in [f"{lake}/stkopt_{und}_{flag}.parquet"] if os.path.exists(f)]
    return sorted(f for f in glob.glob(f"{lake}/opt_{und}_{flag}_*.parquet") if f[:-8].rsplit("_", 1)[-1].isdigit())


def seed(raw, lake, group, J=None):
    """Write raw chunks for jobs of an option group whose window is covered by the published files in LAKE.

    A chunk is seeded when the published file has rows of its series (offset, side) inside its window and the
    window ends before the last published day. Windows with no published rows stay unfetched (they may have
    been empty or never fetched; asking again is cheap). The published files' QA counts of dropped rows are
    kept in RAW/<sub>/<und>/<flag>/seed_qa.json, so lake_build.py can still report them. Returns
    {"seeded": n, "missing": m} (m = jobs still to fetch)."""
    J = jobs_for(group, raw) if J is None else J
    series = {}
    for j in J:
        series.setdefault(os.path.dirname(os.path.dirname(j.path)), []).append(j)
    st = {"seeded": 0, "missing": 0}
    for c1, jobs in series.items():
        und, flag = c1.split("/")[-3:-1]
        sub = c1.split("/")[-4]
        fs = published_files(lake, sub, und, flag)
        df = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True) if fs else pd.DataFrame()
        if len(df):
            last = df.ts.max().date()
            sec = (df.ts - pd.Timestamp(0, tz="UTC")) // pd.Timedelta(seconds=1)
            df = df.assign(timestamp=sec.astype("int64")).sort_values("timestamp")
            grp = {k: (g.reset_index(drop=True), g.timestamp.to_numpy())
                   for k, g in df.groupby(["offset", "side"], observed=True)}
            qa = {"rows_dropped": {}, "duplicates_dropped": {}}
            for f in fs:
                m = f"{lake}/_qa/{os.path.basename(f)}.json"
                if os.path.exists(m):
                    q = json.load(open(m))["qa"]
                    qa = {"rows_dropped": q.get("rows_dropped_series_total") or {},
                          "duplicates_dropped": q.get("duplicates_dropped_series_total") or {}}
            os.makedirs(os.path.dirname(c1), exist_ok=True)
            json.dump(dict(qa, files=[os.path.basename(f) for f in fs]), open(f"{os.path.dirname(c1)}/seed_qa.json", "w"))
        for j in jobs:
            if j.done():
                continue
            tag = os.path.basename(os.path.dirname(j.path))
            a, b = window(j)
            g = grp.get((int(tag[:-1]), tag[-1])) if len(df) else None
            rows = None
            if g is not None and b < last:  # rows from 00:00 IST on day a to the end of day b
                lo, hi = (int(pd.Timestamp(d, tz=IST).timestamp()) for d in (a, b + dt.timedelta(days=1)))
                rows = g[0].iloc[np.searchsorted(g[1], lo):np.searchsorted(g[1], hi)]
            if rows is None or not len(rows):
                st["missing"] += 1
                continue
            os.makedirs(os.path.dirname(j.path), exist_ok=True)
            rows[["timestamp", *[c for c in RD if c in rows]]].reset_index(drop=True).to_parquet(
                j.path + ".parquet.tmp", compression="zstd")
            os.replace(j.path + ".parquet.tmp", j.path + ".parquet")
            st["seeded"] += 1
    return st


def todo(J, before=None, since=None):
    """Jobs not finished yet whose window ends before `before` and on or after `since` (each if given)."""
    return [j for j in J if not j.done() and (before is None or window(j)[1] < before)
            and (since is None or window(j)[1] >= since)]


def main():
    args, raw, lake, before, since = sys.argv[2:], sys.argv[1], None, None, None
    while args and args[0].startswith("--"):
        opt, val, args = args[0], args[1], args[2:]
        if opt == "--only-missing":
            lake = val
        elif opt == "--before":
            before = dt.date.fromisoformat(val)
        elif opt == "--since":
            since = dt.date.fromisoformat(val)
        else:
            raise SystemExit(f"unknown option {opt}")
    groups = args
    threads = int(os.environ.get("LAKE_THREADS", "5"))
    for g in groups:
        J = jobs_for(g, raw)
        if lake and (g in ("stkopt", "mcxopt") or g.startswith("opt_")):
            log(f"[{g}] seeded from {lake}:", seed(raw, lake, g, J))
        J = todo(J, before, since)
        log(f"[{g}] {len(J)} chunks to fetch")
        stats = fetch(raw, g, J, threads)
        json.dump(stats, open(f"{raw}/_{g}.stats.json", "w"))
        if stats.get("capped"):
            sys.exit(3)


if __name__ == "__main__":
    main()
