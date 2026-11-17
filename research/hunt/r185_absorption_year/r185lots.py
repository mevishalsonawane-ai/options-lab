"""R185 amendment A (16 Nov 2026): the true index-option lot size by date, from NSE's own F&O bhavcopies.

    python -I research/hunt/r185_absorption_year/r185lots.py <dir of NSE UDiFF F&O bhavcopy zips>

The lake keeps a weekly sample of NSE's F&O bhavcopy (`gdrive:IraAlgo-data-lake/nse_bhavcopy/`). From mid-2024 each row
carries the contract's market lot (`NewBrdLotQty`). For each index (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY) every option
expiry seen gets its lot; a trading day's lot is the lot of the contract R185 trades, the nearest expiry on or after
that day (a lot belongs to the contract, so old contracts keep the old lot until they expire). SENSEX trades on BSE and
is not in NSE's file: its lot comes from Dhan's instrument master for the live contracts (listed in the output).
The bhavcopies are untrusted (read with python -I, outside git); only the lot tables go to results/lot_sizes.json, with
the dates where the repo's LOT_SCHEDULE (research/obuy/config.py) differs. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import io
import json
import sys
import zipfile
from datetime import date
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
from research.obuy.config import LOT_SCHEDULE  # noqa: E402

NSE_IDX = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
START, END = date(2024, 11, 20), date(2026, 11, 13)


def read_zip(p: Path) -> pd.DataFrame:
    with zipfile.ZipFile(p) as z:
        name = [n for n in z.namelist() if n.endswith(".csv")][0]
        head = z.open(name).readline().decode()
        if "NewBrdLotQty" not in head:
            return pd.DataFrame()
        df = pd.read_csv(io.BytesIO(z.read(name)), usecols=["TradDt", "FinInstrmTp", "TckrSymb", "XpryDt", "NewBrdLotQty"])
    return df[(df.FinInstrmTp == "IDO") & df.TckrSymb.isin(NSE_IDX)]


def file_lots(folder: Path) -> dict:
    """{file trade date: {index: {expiry iso: lot}}} - the lot of every live index option expiry as NSE printed it that
    day (NSE revises the lot of live contracts too, so a contract can show two lots over its life)."""
    out = {}
    for p in sorted(folder.glob("*.csv.zip")):
        df = read_zip(p)
        if not len(df):
            continue
        td = str(df.TradDt.iloc[0])[:10]
        out[td] = {u: {str(x)[:10]: int(g.NewBrdLotQty.mode().iloc[0]) for x, g in df[df.TckrSymb == u].groupby("XpryDt")}
                   for u in NSE_IDX}
    return out


def lot_on(files: dict, und: str, d: date):
    """The lot of the contract R185 trades on day d: the nearest expiry on or after d, as printed in the latest bhavcopy
    on or before d (the next one if that expiry was not listed yet). A change between two weekly files shows up to a
    week late."""
    ds = sorted(files)
    before = [f for f in ds if f <= d.isoformat()]
    after = [f for f in ds if f > d.isoformat()]
    for f in ([before[-1]] if before else []) + after[:1]:
        exp = files[f][und]
        later = sorted(x for x in exp if x >= d.isoformat())
        if later:
            return exp[later[0]]
    return None


def schedule_lot(und: str, d: date) -> int:
    lot = None
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return lot


def by_date(files: dict, und: str, days: list) -> list:
    """Runs of equal (true lot, schedule lot) over the trading days."""
    runs = []
    for d in days:
        t, s = lot_on(files, und, d), schedule_lot(und, d)
        if runs and runs[-1]["true"] == t and runs[-1]["schedule"] == s:
            runs[-1]["to"] = d.isoformat()
            runs[-1]["days"] += 1
        else:
            runs.append(dict(frm=d.isoformat(), to=d.isoformat(), true=t, schedule=s, days=1))
    return runs


def main(argv=None):
    folder = Path((argv or sys.argv[1:])[0])
    files = file_lots(folder)
    days = [x.date() for x in pd.bdate_range(START, END)]
    res = dict(source=f"NSE UDiFF F&O bhavcopy weekly sample ({len(files)} files, {min(files)} - {max(files)}), "
                      "NewBrdLotQty of the nearest index option expiry", indices={},
               note="after the last file (7 Oct 2026) the last file's lot of the nearest expiry is carried")
    for und in NSE_IDX:
        runs = by_date(files, und, days)
        res["indices"][und] = dict(runs=runs,
                                   wrong=[r for r in runs if r["true"] is not None and r["true"] != r["schedule"]])
    print(json.dumps({u: dict(runs=[(r["frm"], r["to"], r["true"], r["schedule"]) for r in v["runs"]])
                      for u, v in res["indices"].items()}, indent=1))
    (HERE / "results" / "lot_sizes.json").write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
