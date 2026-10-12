#!/usr/bin/env python3
"""Data lake fetcher: pulls raw chunks from Dhan API v2 into RAW/<group>/<series>/<from>.parquet.

Resumable: every finished chunk leaves either a .parquet or a .empty marker (or .err with the
short, redacted error) and is skipped next time. Credentials come from the environment (see
dhan_client.py) and are never written anywhere.

Usage:  python -I lake_fetch.py RAW_DIR GROUP [GROUP ...]
Groups: idx_daily idx_1m fut_daily fut_1m eq_daily opt_<UNDERLYING>_<WEEK|MONTH> mcxopt
"""
import os, sys, csv, io, json, time, datetime as dt, concurrent.futures as cf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
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
            return "empty", 0
        df.to_parquet(self.path + ".parquet.tmp", compression="zstd")
        os.replace(self.path + ".parquet.tmp", self.path + ".parquet")
        if os.path.exists(self.path + ".err"):
            os.remove(self.path + ".err")
        return "ok", len(df)


def master(raw):
    fn = f"{raw}/master/api-scrip-master-detailed.csv"
    if not os.path.exists(fn):
        os.makedirs(os.path.dirname(fn), exist_ok=True)
        r = requests.get(MASTER_URL, timeout=120)
        r.raise_for_status()
        open(fn, "wb").write(r.content)
    return list(csv.DictReader(open(fn)))


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
    elif group == "eq_daily":
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
        for s in sorted(syms):
            if s not in eq:
                log("eq_daily: no NSE EQ id for", s)
                continue
            J.append(Job(f"{raw}/eq_daily/{s}/all", "/charts/historical", dict(
                securityId=eq[s], exchangeSegment="NSE_EQ", instrument="EQUITY", expiryCode=0, oi=False,
                fromDate="1995-01-01", toDate=(TODAY + dt.timedelta(days=1)).isoformat())))
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
                    for a, b in windows(dt.date(2025, 6, 1), TODAY, 30):
                        J.append(Job(f"{raw}/opt/{und}/MONTH/c1/{off:+03d}{side[0]}/{a}", "/charts/rollingoption",
                            dict(securityId=sid, exchangeSegment="MCX_COMM", instrument="OPTFUT", expiryFlag="MONTH",
                                 expiryCode=1, strike=strike, drvOptionType=side, requiredData=RD,
                                 fromDate=a.isoformat(), toDate=b.isoformat(), interval="1"), key=key))
    else:
        raise SystemExit(f"unknown group {group}")
    return J


def main():
    raw, groups = sys.argv[1], sys.argv[2:]
    threads = int(os.environ.get("LAKE_THREADS", "5"))
    for g in groups:
        J = [j for j in jobs_for(g, raw) if not j.done()]
        log(f"[{g}] {len(J)} chunks to fetch")
        stats, t0 = {"ok": 0, "empty": 0, "err": 0}, time.time()
        with cf.ThreadPoolExecutor(threads) as ex:
            futs = {ex.submit(j.run): j for j in J}
            for i, f in enumerate(cf.as_completed(futs), 1):
                try:
                    k, v = f.result()
                except Exception as e:
                    k, v = "err", f"{type(e).__name__}: {e}"
                    open(futs[f].path + ".err", "w").write(dh.redact(v)[:300])
                stats[k] += 1
                if k == "err":
                    log("ERR", futs[f].path.split(raw)[-1], v)
                if i % 200 == 0 or i == len(J):
                    log(f"[{g}] {i}/{len(J)} {stats} {time.time() - t0:.0f}s")
        json.dump(stats, open(f"{raw}/_{g}.stats.json", "w"))


if __name__ == "__main__":
    main()
