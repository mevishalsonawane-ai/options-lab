"""Re-verify options_lab.lots.LOT_HISTORY against NSE bhavcopy.

Not a unit test: it needs the network, and NSE is not a fixture. Run it when
extending the table or when a lot change is suspected.

    python tools/verify_lot_history.py

For each row it downloads the stated first session and the preceding one, and
asserts the nearest expiry carries the new lot on the former and a different
lot on the latter. A row that does not move the lot is not a transition.
"""
from __future__ import annotations

import datetime as dt
import io
import sys
import zipfile

import pandas as pd
import requests

from options_lab.lots import LOT_HISTORY

BHAV = ("https://nsearchives.nseindia.com/content/fo/"
        "BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip")

_session = requests.Session()
_session.headers.update({"User-Agent": "Mozilla/5.0", "Accept": "*/*"})


def nearest_expiry_lot(day: dt.date, underlying: str) -> int | None:
    """Lot on the nearest listed expiry, or None if there is no bhavcopy."""
    try:
        r = _session.get(BHAV.format(d=day), timeout=30)
    except requests.RequestException:
        return None
    if r.status_code != 200 or len(r.content) < 500:
        return None
    try:
        z = zipfile.ZipFile(io.BytesIO(r.content))
        df = pd.read_csv(z.open(z.namelist()[0]))
    except (zipfile.BadZipFile, ValueError):
        return None
    if "NewBrdLotQty" not in df.columns:
        return None
    df = df[(df["TckrSymb"] == underlying)
            & (df["OptnTp"].isin(["CE", "PE"]))].copy()
    if df.empty:
        return None
    df["XpryDt"] = pd.to_datetime(df["XpryDt"])
    near = df[df["XpryDt"] == df["XpryDt"].min()]
    return int(near["NewBrdLotQty"].mode().iloc[0])


def previous_session(day: dt.date, underlying: str, back: int = 10):
    """Walk back to the last day NSE actually published, skipping holidays."""
    for i in range(1, back + 1):
        d = day - dt.timedelta(days=i)
        lot = nearest_expiry_lot(d, underlying)
        if lot is not None:
            return d, lot
    return None, None


def main() -> int:
    failures = 0
    for underlying, entries in LOT_HISTORY.items():
        for i, (effective, lot) in enumerate(entries):
            on = nearest_expiry_lot(effective, underlying)
            if on != lot:
                print(f"MISMATCH {underlying} {effective}: table says {lot}, "
                      f"bhavcopy says {on}")
                failures += 1
                continue
            if i == 0:
                print(f"ok       {underlying} {effective}: lot {lot} "
                      f"(coverage start, no prior session to compare)")
                continue
            prev_day, prev_lot = previous_session(effective, underlying)
            if prev_lot is None:
                print(f"UNKNOWN  {underlying} {effective}: no prior bhavcopy")
                failures += 1
            elif prev_lot == lot:
                print(f"NOT A TRANSITION {underlying} {effective}: "
                      f"{prev_day} already carried {lot}")
                failures += 1
            else:
                print(f"ok       {underlying} {effective}: {prev_lot} on "
                      f"{prev_day} -> {lot}")
    print("\n" + ("all rows verified" if not failures
                  else f"{failures} row(s) did not verify"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
