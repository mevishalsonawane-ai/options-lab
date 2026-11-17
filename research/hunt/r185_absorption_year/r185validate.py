"""R185 data check (PREREG section 1): Dhan's 1-minute option closes vs the phone's own 1-second recordings.

    python -I research/hunt/r185_absorption_year/r185validate.py --rec2 <1-second export dir> --und NIFTY BANKNIFTY

For every recorded (strike, right, minute) of the near-ATM options (roles CE / PE / REC_CE / REC_PE, merged per right and
strike as R184 found them to be one token), the recording's last `last` inside the minute is compared with Dhan's close
of the same strike and right at that minute (the rolling WEEK series, expiry code 1, any offset). The recordings are
untrusted, read-only and stay outside git (read with python -I); only aggregates go to results/validation.json.
The phone records the nearest expiry, so close agreement also confirms that Dhan's expiry code 1 is the nearest expiry.
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
LAKE = Path(os.path.expanduser("~/.cache/iraalgo/lake"))
COLS = ["epoch_sec", "name", "role", "strike", "last"]
IST_S = 19800


def minute_last(df: pd.DataFrame) -> pd.DataFrame:
    """1-second rows (epoch_sec, right, strike, last) -> the last positive `last` in each IST minute."""
    x = df[np.isfinite(df["last"]) & (df["last"] > 0)].copy()
    x["mstart"] = (x.epoch_sec // 60) * 60
    x = x.sort_values("epoch_sec", kind="stable")
    return x.groupby(["right", "strike", "mstart"], as_index=False)["last"].last()


def epoch_s(ts: pd.Series) -> np.ndarray:
    """tz-aware timestamps (any unit, any IST spelling) -> epoch seconds."""
    return ((pd.to_datetime(ts, utc=True) - pd.Timestamp("1970-01-01", tz="UTC")) // pd.Timedelta(seconds=1)).to_numpy()


def dhan_minutes(opt: pd.DataFrame) -> pd.DataFrame:
    """Lake option rows -> (right CE/PE, strike, mstart epoch, close)."""
    o = opt.copy()
    o["right"] = np.where(o.side.astype(str) == "C", "CE", "PE")
    o["mstart"] = epoch_s(o.ts)
    return o.drop_duplicates(["right", "strike", "mstart"])[["right", "strike", "mstart", "close"]]


def compare(ph: pd.DataFrame, dh: pd.DataFrame) -> dict:
    j = ph.merge(dh, on=["right", "strike", "mstart"], how="left")
    have = j[j.close.notna()]
    if not len(have):
        return dict(phone_minutes=int(len(ph)), matched=0)
    ad = (have["last"] - have.close).abs()
    rel = ad / have.close.clip(lower=0.05)
    return dict(phone_minutes=int(len(ph)), matched=int(len(have)), dhan_missing=int(len(j) - len(have)),
                median_abs=round(float(ad.median()), 3), mean_abs=round(float(ad.mean()), 3),
                p95_abs=round(float(ad.quantile(0.95)), 3), within_1pct=round(float((rel <= 0.01).mean()), 4),
                within_rs1=round(float((ad <= 1.0).mean()), 4), within_5pct=round(float((rel <= 0.05).mean()), 4),
                corr=round(float(np.corrcoef(have["last"], have.close)[0, 1]), 5) if len(have) > 2 else None)


def load_dhan(und: str) -> pd.DataFrame:
    fs = [LAKE / f"opt_{und}_WEEK_2026.parquet", LAKE / f"opt_{und}_WEEK_2026_gap1113.parquet"]
    parts = [pd.read_parquet(f, columns=["side", "strike", "close", "ts"]) for f in fs if f.exists()]
    for x in parts:
        x["ts"] = pd.to_datetime(x.ts, utc=True).dt.tz_convert("Asia/Kolkata")
    o = pd.concat(parts, ignore_index=True)
    return o[o.ts >= pd.Timestamp("2026-10-15", tz="Asia/Kolkata")]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec2", required=True)
    ap.add_argument("--und", nargs="+", default=["NIFTY", "BANKNIFTY"])
    ap.add_argument("--out", default=str(HERE / "results" / "validation.json"))
    a = ap.parse_args(argv)
    days = sorted(m.group(1) for f in os.listdir(a.rec2) if (m := re.match(r"orderflow-(\d{4}-\d{2}-\d{2})\.csv\.gz$", f)))
    out = Path(a.out)
    res = json.loads(out.read_text()) if out.exists() else {}
    for und in a.und:
        dh = dhan_minutes(load_dhan(und))
        per, allp = {}, []
        for d in days:
            df = pd.read_csv(os.path.join(a.rec2, f"orderflow-{d}.csv.gz"), usecols=COLS, low_memory=False)
            df = df[(df.name == und) & df.role.isin(["CE", "PE", "REC_CE", "REC_PE"])].copy()
            if not len(df):
                continue
            df["right"] = df.role.str[-2:]
            df["strike"] = df.strike.astype(float)
            t0 = (date.fromisoformat(d) - date(1970, 1, 1)).days * 86400 - IST_S + 9 * 3600 + 15 * 60
            df = df[(df.epoch_sec >= t0) & (df.epoch_sec < t0 + 375 * 60)]
            ph = minute_last(df)
            per[d] = compare(ph, dh)
            allp.append(ph)
        ph = pd.concat(allp, ignore_index=True) if allp else pd.DataFrame(columns=["right", "strike", "mstart", "last"])
        res[und] = dict(days=len(per), overall=compare(ph, dh), per_day=per,
                        note="phone: last `last` in the minute (1-second recorder); Dhan: 1-minute close, WEEK expiry "
                             "code 1, same strike and right")
        print(und, json.dumps(res[und]["overall"]))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
