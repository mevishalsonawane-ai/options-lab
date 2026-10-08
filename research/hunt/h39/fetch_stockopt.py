"""h39 Stage B: fetch expired STOCK options of the index heavyweights from Dhan (POST /v2/charts/rollingoption).

OPTSTK, MONTH, expiryCode 1 (nearest monthly on each date), ATM-3..ATM+3, CALL+PUT, 1-minute, 30-day windows.
Reuses scratchpad/dhan/fetch.py (Creds/Client/Pacer/redact). Credentials are read at runtime from
scratchpad/secrets/dhan.env by fetch.Creds and are never printed; every message goes through fetch.redact().
Responses are parsed in memory and written only as compact float32 zstd parquet, one file per stock-year:
    scratchpad/hunt/h39/data/<SYM>/<YEAR>.parquet
columns: ts (int32 epoch minutes), off (int8 -3..3), side (int8 +1 CALL / -1 PUT), c strike spot (float32), iv (int16 = IV x 20, -1 missing),
v oi (float32, units). Hard cap 700 MB on the data folder. Resumable: data/done.json lists finished windows.

    python3 -I research/hunt/h39/fetch_stockopt.py --plan
    python3 -I research/hunt/h39/fetch_stockopt.py --dry-run          # MockSession, writes to data_dryrun/
    nohup python3 -I research/hunt/h39/fetch_stockopt.py > <scratch>/hunt/h39/fetch.log 2>&1 &
Stops immediately on HTTP 401/403 (token) and says so.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import datetime as dt
import json
import time
import os
import sys
from pathlib import Path

sys.path.append("/root/.local/lib/python3.11/site-packages")
SCRATCH = Path(os.environ.get("OBUY_SCRATCH", "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"))
sys.path.insert(0, str(SCRATCH / "dhan"))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import fetch as F  # noqa: E402

STOCKS = {"HDFCBANK": 1333, "ICICIBANK": 4963, "SBIN": 3045, "KOTAKBANK": 1922, "AXISBANK": 5900,
          "RELIANCE": 2885, "INFY": 1594, "TCS": 11536, "BHARTIARTL": 10604, "ITC": 1660, "LT": 11483}
OFFS = range(-3, 4)
SIDES = ("CALL", "PUT")
FIELDS = ["close", "iv", "volume", "strike", "oi", "spot"]   # o/h/l dropped: features use closes only (disk)
CAP_BYTES = 700 * 1024 ** 2
START = dt.date(2021, 1, 1)


def windows(start, end, days=30):
    out, w = [], start
    while w <= end:
        out.append((w, min(end, w + dt.timedelta(days=days - 1))))
        w += dt.timedelta(days=days)
    return out


def dir_bytes(p: Path):
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())


def payload(sid, off, side, w0, w1):
    return {"securityId": str(sid), "exchangeSegment": "NSE_FNO", "instrument": "OPTSTK", "expiryFlag": "MONTH",
            "expiryCode": 1, "strike": F.strike_label(off), "drvOptionType": side, "requiredData": FIELDS,
            "fromDate": w0.isoformat(), "toDate": (w1 + dt.timedelta(days=1)).isoformat(), "interval": 1}


def to_compact(df, off, side):
    if df.empty:
        return None
    mins = (df["ts"] - pd.Timestamp("1970-01-01", tz="UTC")).dt.total_seconds() // 60
    out = pd.DataFrame({"ts": mins.astype(np.int32),
                        "off": np.int8(off), "side": np.int8(1 if side == "CALL" else -1)})
    for src, dst in (("close", "c"), ("iv", "iv"), ("strike", "strike"),
                     ("spot", "spot"), ("volume", "v"), ("oi", "oi")):
        out[dst] = pd.to_numeric(df[src], errors="coerce").astype(np.float32) if src in df else np.float32(np.nan)
    out["iv"] = ivq(out["iv"])
    return out


def ivq(iv):
    """IV quantised to 0.05 vol points as int16 (IV x 20; -1 = missing): IV was ~half the bytes as raw float32."""
    x = np.round(np.nan_to_num(np.asarray(iv, dtype=np.float64), nan=-0.05) * 20)
    return np.clip(x, -1, 32000).astype(np.int16)


class Fatal(Exception):
    pass


def get(client, pacer, pay, side, outer=5):
    for attempt in range(outer + 1):             # Dhan's gateway returns bursts of 504s: back off a minute and retry
        try:
            js = client.post("/charts/rollingoption", pay, pacer)
            return F.parse_rolling(js, side), None
        except F.ApiError as e:
            if e.status in (401, 403):
                raise Fatal(f"HTTP {e.status}: {F.redact(e.body)[:200]}")
            if "DH-907" in str(e.body) or "DH-905" in str(e.body):   # no data / invalid strike for this window
                return pd.DataFrame(), str(e.body)[:80]
            if attempt == outer or (e.status is not None and e.status not in F.Client.RETRY):
                raise
            print(f"outer retry {attempt + 1} after HTTP {e.status}; sleeping 60 s", flush=True)
            time.sleep(60)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rate", type=float, default=3.0)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--only", action="append")
    ap.add_argument("--end", default=None)
    a = ap.parse_args()
    out = SCRATCH / "hunt" / "h39" / ("data_dryrun" if a.dry_run else "data")
    out.mkdir(parents=True, exist_ok=True)
    end = dt.date.fromisoformat(a.end) if a.end else dt.date.today() - dt.timedelta(days=1)
    W = windows(START, end)
    syms = [s for s in STOCKS if not a.only or s in a.only]
    if a.plan:
        n = len(syms) * len(OFFS) * len(SIDES) * len(W)
        print(f"{len(syms)} stocks x {len(OFFS)} strikes x 2 sides x {len(W)} windows = {n} calls; "
              f"at {a.rate}/s = {n / a.rate / 3600:.1f} h (upper bound; pre-listing windows are skipped)")
        return 0
    F.DATA = out                                     # Pacer call-count file lives here, not in dhan/data
    if a.dry_run:
        client = F.Client({"access-token": "x", "client-id": "x"}, "x", session=F.MockSession, max_retries=0)
    else:
        creds = F.Creds()
        exp = creds.expiry()
        if exp and exp < dt.datetime.now(dt.timezone.utc):
            print(f"STOP: access token expired at {exp:%Y-%m-%d %H:%M} UTC; refresh scratchpad/secrets/dhan.env")
            return 3
        client = F.Client(creds.headers(), creds.client_id, max_retries=4)
    pacer = F.Pacer(1.0 / a.rate, 90_000, "h39")
    donef = out / "done.json"
    done = set(json.loads(donef.read_text())) if donef.exists() else set()
    for s in syms:
        sid = STOCKS[s]
        empty_run = 0
        buf: dict[int, list] = {}
        for w0, w1 in reversed(W):                   # newest first; stop after 3 empty ATM windows in a row
            key = f"{s}|{w0}"
            if key in done:
                continue
            frames = []
            try:
                atm, err = get(client, pacer, payload(sid, 0, "CALL", w0, w1), "CALL")
                if atm.empty:
                    empty_run += 1
                    print(f"{s} {w0}: ATM CALL empty ({err or 'no bars'})", flush=True)
                    if empty_run >= 3:
                        print(f"{s}: 3 empty windows in a row -> first served window reached", flush=True)
                        break
                    done.add(key)
                    continue
                empty_run = 0
                frames.append(to_compact(atm, 0, "CALL"))
                jobs = [(off, side) for off in OFFS for side in SIDES if not (off == 0 and side == "CALL")]
                with cf.ThreadPoolExecutor(a.workers) as ex:   # latency-bound (~4.6 s/call); pacer still caps req/s
                    futs = [ex.submit(get, client, pacer, payload(sid, off, side, w0, w1), side) for off, side in jobs]
                    for (off, side), fu in zip(jobs, futs):
                        frames.append(to_compact(fu.result()[0], off, side))
            except Fatal as e:
                print(f"STOP at {s} {w0}: {e}", flush=True)
                flush(out, s, buf)
                donef.write_text(json.dumps(sorted(done)))
                return 2
            frames = [f for f in frames if f is not None]
            if frames:
                buf.setdefault(w0.year, []).append(pd.concat(frames, ignore_index=True))
            done.add(key)
            print(f"{s} {w0}..{w1}: {sum(len(f) for f in frames)} rows", flush=True)
            if len(buf) > 1:                         # a year is complete once we moved past it (newest-first)
                for y in sorted(buf)[1:]:
                    flush(out, s, {y: buf.pop(y)})
                donef.write_text(json.dumps(sorted(done)))
                if dir_bytes(out) > CAP_BYTES:
                    print(f"STOP: {out} exceeds 700 MB", flush=True)
                    return 4
        flush(out, s, buf)
        donef.write_text(json.dumps(sorted(done)))
    print(f"done; data {dir_bytes(out) / 1e6:.0f} MB", flush=True)
    return 0


def flush(out: Path, s, buf):
    for y, parts in buf.items():
        if not parts:
            continue
        f = out / s / f"{y}.parquet"
        f.parent.mkdir(parents=True, exist_ok=True)
        df = pd.concat(parts + ([pd.read_parquet(f)] if f.exists() else []), ignore_index=True)
        df = df.drop_duplicates(["ts", "off", "side"]).sort_values(["ts", "side", "off"])
        df.to_parquet(f, compression="zstd", compression_level=9, index=False)
    buf.clear()


if __name__ == "__main__":
    sys.exit(main())
