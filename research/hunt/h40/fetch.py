"""h40 polite fetcher for NSE public archive files (one source per process).

    python3 -I fetch.py <source> [start YYYY-MM-DD] [end]
sources: fo (F&O bhavcopy -> compact rows), cm (sec_bhavdata_full -> EQ rows), fii (fii_stats xls, raw kept: ~9 KB),
         pvol (participant-wise volume csv, raw kept), poi (participant-wise OI, gap fill only; reuses h27 raw)
Plain research User-Agent, one request at a time, SLEEP seconds between requests, no cookies. A refusal is logged.
Downloads are untrusted: zips are read in memory with zipfile (never extracted to disk) and parsed as CSV text only.
"""
import datetime as dt
import io
import os
import subprocess
import sys
import time
import zipfile

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
H = f"{S}/hunt/h40"
DATA = f"{H}/data"
RAW = f"{H}/raw"
UA = "options-lab-research/1.0 (personal academic backtest; polite, ~1 request per 3 s)"
SLEEP = float(os.environ.get("H40_SLEEP", "3"))
IDX = {"NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50"}


def days(a, b):
    x = pd.read_parquet(f"{S}/dhan/repo/dhan-data/candles/daily/IDX_I/NIFTY.parquet")
    d = sorted({t.date() for t in x.ts})
    d += [dt.date(2026, 10, 6), dt.date(2026, 10, 7)]
    return [z for z in d if a <= z <= b]


def get(url, out=None):
    """-> (http_code, bytes or None)"""
    tmp = f"{RAW}/_dl_{os.getpid()}"
    r = subprocess.run(["curl", "-sS", "-m", "60", "-A", UA, "-o", tmp, "-w", "%{http_code}", url],
                       capture_output=True, text=True)
    code = r.stdout.strip() or "ERR"
    data = None
    if code == "200" and os.path.exists(tmp):
        data = open(tmp, "rb").read()
    if os.path.exists(tmp):
        os.remove(tmp)
    return code, data, r.stderr.strip()[:100]


def unzip_csv(b):
    z = zipfile.ZipFile(io.BytesIO(b))
    n = [m for m in z.namelist() if m.lower().endswith(".csv")][0]
    return pd.read_csv(io.BytesIO(z.read(n)), low_memory=False)


def fo_compact(d, df):
    """normalise old (fo*bhav) and UDiFF formats -> futures rows (index + stock) and reduced index-option rows,
    plus per (symbol, expiry, type) option aggregates over ALL strikes."""
    if "INSTRUMENT" in df.columns:
        df.columns = [c.strip() for c in df.columns]
        t = pd.DataFrame(dict(
            inst=df.INSTRUMENT.str.strip().map({"FUTIDX": "IDF", "FUTSTK": "STF", "OPTIDX": "IDO", "OPTSTK": "STO"}),
            sym=df.SYMBOL.str.strip(), exp=pd.to_datetime(df.EXPIRY_DT, format="%d-%b-%Y").dt.date,
            k=df.STRIKE_PR.astype(float), typ=df.OPTION_TYP.str.strip(), o=df.OPEN, h=df.HIGH, l=df.LOW, c=df.CLOSE,
            settle=df.SETTLE_PR, contracts=df.CONTRACTS.astype(float), val_lakh=df.VAL_INLAKH.astype(float),
            oi=df.OPEN_INT.astype(float), doi=df.CHG_IN_OI.astype(float), und=np.nan, lot=np.nan, fmt=0))
    else:
        lot = df.NewBrdLotQty.astype(float)
        t = pd.DataFrame(dict(
            inst=df.FinInstrmTp.str.strip(), sym=df.TckrSymb.str.strip(), exp=pd.to_datetime(df.XpryDt).dt.date,
            k=df.StrkPric.fillna(0).astype(float), typ=df.OptnTp.fillna("XX").str.strip(), o=df.OpnPric,
            h=df.HghPric, l=df.LwPric, c=df.ClsPric, settle=df.SttlmPric,
            contracts=df.TtlTradgVol.astype(float), val_lakh=df.TtlTrfVal.astype(float) / 1e5,
            oi=df.OpnIntrst.astype(float), doi=df.ChngInOpnIntrst.astype(float), und=df.UndrlygPric.astype(float),
            lot=lot, fmt=1))
    t.insert(0, "day", d)
    fut = t[t.inst.isin(["IDF", "STF"])]
    opt = t[(t.inst == "IDO") & t.sym.isin(IDX)].copy()
    agg = opt.groupby(["day", "sym", "exp", "typ"]).agg(oi=("oi", "sum"), doi=("doi", "sum"),
                                                       contracts=("contracts", "sum"),
                                                       val_lakh=("val_lakh", "sum")).reset_index()
    # reduced chain: nearest 3 expiries, strikes within +-8% of the nearest future close
    keep = []
    for s, g in opt.groupby("sym"):
        f = fut[(fut.sym == s) & (fut.inst == "IDF")].sort_values("exp")
        if not len(f):
            continue
        px = float(f.c.iloc[0])
        ex = sorted(g.exp.unique())[:3]
        keep.append(g[g.exp.isin(ex) & (g.k.between(px * 0.92, px * 1.08))])
    red = pd.concat(keep) if keep else opt.iloc[:0]
    sto = t[t.inst == "STO"].groupby(["day", "sym", "typ"]).agg(oi=("oi", "sum"), doi=("doi", "sum"),
                                                                 contracts=("contracts", "sum")).reset_index()
    return fut, red, agg, sto


def main():
    src = sys.argv[1]
    a = dt.date.fromisoformat(sys.argv[2]) if len(sys.argv) > 2 else dt.date(2020, 1, 1)
    b = dt.date.fromisoformat(sys.argv[3]) if len(sys.argv) > 3 else dt.date(2026, 10, 7)
    od = f"{DATA}/{src}"
    os.makedirs(od, exist_ok=True)
    os.makedirs(RAW, exist_ok=True)
    log = open(f"{H}/fetch_{src}.status.csv", "a")
    for d in days(a, b):
        tag = f"{d:%Y%m%d}"
        if src == "fo":
            if os.path.exists(f"{od}/fut_{tag}.csv.gz"):
                continue
            old = f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d:%Y}/{d:%b}".upper() + \
                  f"/fo{d:%d}{d:%b}".upper()[:7].replace("FO", "fo") + f"{d:%Y}bhav.csv.zip"
            old = (f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d:%Y}/{d.strftime('%b').upper()}/"
                   f"fo{d:%d}{d.strftime('%b').upper()}{d:%Y}bhav.csv.zip")
            new = f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{tag}_F_0000.csv.zip"
            urls = [old, new] if d < dt.date(2024, 7, 8) else [new, old]
            ok = False
            for u in urls:
                code, b_, err = get(u)
                time.sleep(SLEEP)
                if b_:
                    try:
                        fut, red, agg, sto = fo_compact(d, unzip_csv(b_))
                    except Exception as e:  # noqa: BLE001
                        print(d, "parse", e, flush=True)
                        log.write(f"{d},{u},parse_err\n")
                        continue
                    red.to_csv(f"{od}/opt_{tag}.csv.gz", index=False)
                    agg.to_csv(f"{od}/oagg_{tag}.csv.gz", index=False)
                    sto.to_csv(f"{od}/sto_{tag}.csv.gz", index=False)
                    fut.to_csv(f"{od}/fut_{tag}.csv.gz", index=False)
                    log.write(f"{d},{u},200\n")
                    ok = True
                    break
                log.write(f"{d},{u},{code} {err}\n")
            if not ok:
                print(d, "FAILED", flush=True)
        elif src == "cm":
            f = f"{od}/cm_{tag}.csv.gz"
            if os.path.exists(f):
                continue
            u = f"https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_{d:%d%m%Y}.csv"
            code, b_, err = get(u)
            time.sleep(SLEEP)
            if b_:
                try:
                    x = pd.read_csv(io.BytesIO(b_), skipinitialspace=True)
                except Exception as e:  # noqa: BLE001
                    log.write(f"{d},{u},parse_err {str(e)[:60]}\n")
                    log.flush()
                    continue
                x.columns = [c.strip() for c in x.columns]
                x = x[x.SERIES.str.strip().isin(["EQ", "BE", "BZ"])]
                x = x.drop(columns=[c for c in ("DATE1", "LAST_PRICE") if c in x.columns])
                x.insert(0, "day", d)
                x.to_csv(f, index=False)
            log.write(f"{d},{u},{code} {err}\n")
        elif src in ("fii", "pvol", "poi"):
            ext = {"fii": "xls", "pvol": "csv", "poi": "csv"}[src]
            f = f"{od}/{tag}.{ext}"
            if os.path.exists(f) and os.path.getsize(f) > 300:
                continue
            if src == "poi":
                h27 = f"{S}/hunt/h27/raw/poi/{tag}.csv"
                if os.path.exists(h27) and os.path.getsize(h27) > 300:
                    continue
            u = {"fii": f"https://nsearchives.nseindia.com/content/fo/fii_stats_{d:%d}-{d:%b}-{d:%Y}.xls",
                 "pvol": f"https://nsearchives.nseindia.com/content/nsccl/fao_participant_vol_{d:%d%m%Y}.csv",
                 "poi": f"https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_{d:%d%m%Y}.csv"}[src]
            code, b_, err = get(u)
            time.sleep(SLEEP)
            if b_ and len(b_) > 300:
                open(f, "wb").write(b_)
            log.write(f"{d},{u},{code} {err}\n")
        log.flush()
    print("done", src, flush=True)


if __name__ == "__main__":
    main()
