"""R87 data download (Dhan API v2), resumable, into $R87_RAW (default ~/.cache/iraalgo/r87/raw). Never committed.

Reuses research/data/lake_fetch.py's Job (one Parquet chunk per request, .empty/.err markers, resumable) and
dhan_client.py (token only from the env vars DHAN_ACCESS_TOKEN / DHAN_CLIENT_ID, sent as headers, redacted from logs).

    python -I research/hunt/r87_candle_anatomy/fetch.py idx        # NIFTY, BANKNIFTY, SENSEX, INDIAVIX 1-minute spot
    python -I research/hunt/r87_candle_anatomy/fetch.py opt        # rolling options ATM-1..ATM+1, nearest expiry
    python -I research/hunt/r87_candle_anatomy/fetch.py fut        # 1-minute index futures, contracts live today

Why these and not the Drive lake: the lake (gdrive:IraAlgo-data-lake) is not reachable from this session and its
GitHub release is not published (404), so the same Dhan endpoints the lake was built from are called directly.
Dhan serves no expired futures minutes, so futures volume/OI minutes exist only for live contracts (stated in the
write-up). Index spot has no volume; the options' own volume (ATM-1..ATM+1) is used as the activity proxy.
"""
from __future__ import annotations

import datetime as dt
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "data"))
import lake_fetch as LF  # noqa: E402

RAW = Path(os.environ.get("R87_RAW", Path.home() / ".cache" / "iraalgo" / "r87" / "raw")).expanduser()
END = dt.date(2026, 10, 18)
IDX = {"NIFTY": (13, "IDX_I"), "BANKNIFTY": (25, "IDX_I"), "SENSEX": (51, "IDX_I"), "INDIAVIX": (21, "IDX_I")}
# nearest expiry series per underlying and period: BANKNIFTY weeklies stopped after 20 Nov 2024 (NSE change)
OPT = (("NIFTY", "WEEK", dt.date(2021, 8, 1), END), ("SENSEX", "WEEK", dt.date(2021, 8, 1), END),
       ("BANKNIFTY", "WEEK", dt.date(2021, 8, 1), dt.date(2024, 11, 30)),
       ("BANKNIFTY", "MONTH", dt.date(2024, 11, 1), END))
OFFS = range(-1, 2)          # ATM-1..+1 (PREREG amendment A0: Dhan rate limits, ~8 chunks a minute)
OFF1_FROM = dt.date(2024, 11, 1)
ONLY_RULES = os.environ.get("R87_ONLY_RULES", "0") == "1"   # R87B: -1 C, +1 P and the ATM pair from Nov 2024 only   # offsets -1 / +1 only from here (the option-rules window); ATM for all years


def jobs(group: str) -> list:
    J = []
    if group == "idx":
        for n, (sid, seg) in IDX.items():
            for a, b in LF.windows(dt.date(2021, 8, 1), END, 85):
                J.append(LF.Job(f"{RAW}/idx/{n}/{a}", "/charts/intraday", dict(
                    securityId=str(sid), exchangeSegment=seg, instrument="INDEX", interval="1", oi=False,
                    fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
            J.append(LF.Job(f"{RAW}/idx_daily/{n}/all", "/charts/historical", dict(
                securityId=str(sid), exchangeSegment=seg, instrument="INDEX", expiryCode=0, oi=False,
                fromDate="2015-01-01", toDate=(END + dt.timedelta(days=1)).isoformat())))
    elif group == "opt":
        for und, flag, a0, b0 in OPT:
            sid, seg = LF.OPT_UND[und]
            for off in OFFS:
                strike = "ATM" if off == 0 else f"ATM{off:+d}"
                for side, key in (("CALL", "ce"), ("PUT", "pe")):
                    for a, b in LF.windows(a0, b0, 30):
                        if off != 0 and b < OFF1_FROM:           # +-1 only for the rules window (A0)
                            continue
                        if ONLY_RULES and (b < OFF1_FROM or (off, side) in (( -1, "PUT"), (1, "CALL"))):
                            continue                             # R87B: the traded contracts' window only
                        J.append(LF.Job(f"{RAW}/opt/{und}/{flag}/{off:+03d}{side[0]}/{a}", "/charts/rollingoption",
                                        dict(securityId=sid, exchangeSegment=seg, instrument="OPTIDX", expiryFlag=flag,
                                             expiryCode=1, strike=strike, drvOptionType=side, requiredData=LF.RD,
                                             fromDate=a.isoformat(), toDate=b.isoformat(), interval="1"), key=key))
        # R87B: the option-rules window (Nov 2024 on, offsets -1/0/+1) first, newest first; then the older ATM history
        J.sort(key=lambda j: j.path.split("/")[-1], reverse=True)
        J.sort(key=lambda j: (j.path.split("/")[-1] < OFF1_FROM.isoformat(), abs(int(j.path.split("/")[-2][:-1]))))
    elif group == "fut":
        rows = LF.master(str(RAW))
        want = {"FUTIDX": {"NIFTY", "BANKNIFTY", "SENSEX"}}   # MCX candles are R85's (reused, not re-fetched)
        live = sorted((r["UNDERLYING_SYMBOL"], r["SM_EXPIRY_DATE"], r["SECURITY_ID"], r["INSTRUMENT"], r["EXCH_ID"])
                      for r in rows if r["INSTRUMENT"] in want and r["UNDERLYING_SYMBOL"] in want[r["INSTRUMENT"]]
                      and r["SEGMENT"] in ("D", "M"))
        seg = lambda ex: "MCX_COMM" if ex == "MCX" else ("BSE_FNO" if ex == "BSE" else "NSE_FNO")
        for s, e, sid, ins, ex in live:
            for a, b in LF.windows(END - dt.timedelta(days=400), END, 85 if ex != "MCX" else 10):
                J.append(LF.Job(f"{RAW}/fut/{s}/{e}_{sid}/{a}", "/charts/intraday", dict(
                    securityId=sid, exchangeSegment=seg(ex), instrument=ins, interval="1", oi=True,
                    fromDate=f"{a} 00:00:00", toDate=f"{b} 23:59:59")))
    else:
        raise SystemExit(f"unknown group {group}")
    return J


def main() -> None:
    for g in sys.argv[1:]:
        J = [j for j in jobs(g) if not j.done()]
        LF.log(g, len(J), "chunks to fetch")
        st = LF.fetch(str(RAW), g, J, int(os.environ.get("R87_THREADS", "4")))
        LF.log(g, st)


if __name__ == "__main__":
    main()
