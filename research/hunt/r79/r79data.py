"""R79 data (PREREG Amendment 1): an R51-format BANKNIFTY day store, plus index minutes for the breadth indices, from
the repo's `research-data` release. 18 Oct 2026.

    python research/hunt/r79/r79data.py fetch      # downloads the release files into $R79_RAW (default /dev/shm/r79/raw)
    python research/hunt/r79/r79data.py build      # writes $R79_STORE (default /dev/shm/r79/store)

Store (never in git):
  day_BANKNIFTY.pkl   {date: r51data.Day}: idx (4, 375) index O H L C on the 09:15 grid; con {(side, strike): (5, 375)
                      O H L C V of that one contract}; atm = the index close rounded to 100 per minute; expiry = the day
                      is an expiry date of the contracts seen in the data (Jan 2025's 29 -> 30 Jan move applied)
  idx_<UND>.pkl       {date: (4, 375)} index minutes for BANKNIFTY, NIFTY, FINNIFTY, SENSEX (Supertrend inputs)

Source files: banknifty_prev_year_wide + banknifty_year_wide (13 Feb 2024 - 23 Feb 2026: index rows + the nearest
expiry after the day at strikes within 1,000 points of the 09:20 index), nifty_prev + nifty_year (IX rows),
finnifty_index, sensex_index. Timestamps are naive IST bar starts. A duplicated minute keeps its first row (r51data).
"""
from __future__ import annotations

import os
import pickle
import subprocess
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RAW = Path(os.environ.get("R79_RAW", "/dev/shm/r79/raw"))
STORE = Path(os.environ.get("R79_STORE", "/dev/shm/r79/store"))
REPO, TAG = "mevishalsonawane-ai/options-lab", "research-data"
NROWS, OPEN_MIN = 375, 9 * 60 + 15
START = date(2024, 11, 20)                     # first tradable day (options kept from here)
WARM_START = date(2024, 9, 1)                  # index minutes kept from here (warm-up)
STEP = 100.0
EXPIRY_MOVED = {date(2025, 1, 29): date(2025, 1, 30)}           # research/brain/adapters/release.py
FILES = {"BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"],
         "NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"],
         "FINNIFTY": ["finnifty_index.parquet"], "SENSEX": ["sensex_index.parquet"]}


# ================================================================================================ pure
def rows_of(df: pd.DataFrame) -> pd.DataFrame:
    """Adds `d` (date) and `r` (row on the 09:15 grid) from naive-IST `ts`; drops minutes outside 09:15-15:29."""
    ts = pd.to_datetime(df["ts"])
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    out = df.assign(d=ts.dt.date, r=(ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(np.int64))
    return out[(out.r >= 0) & (out.r < NROWS)]


def index_days(df: pd.DataFrame, lo: date = WARM_START) -> dict:
    """{date: (4, 375) O H L C} from the IX rows of a frame (all rows when there is no `right` column). NaN where a
    minute is missing; a duplicated minute keeps its first row; rows with close <= 0 dropped."""
    x = df[df["right"] == "IX"] if "right" in df.columns else df
    x = rows_of(x)
    x = x[(x.d >= lo) & (x.close > 0)].drop_duplicates(["d", "r"], keep="first")
    out = {}
    for d, g in x.groupby("d", sort=True):
        a = np.full((4, NROWS), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.values] = g[c].values
        out[d] = a
    return out


def contracts(df: pd.DataFrame, lo: date = START) -> dict:
    """{date: {(side, strike): (5, 375) O H L C V}} from the CE / PE rows (side 'C' / 'P'). A duplicated minute keeps
    its first row."""
    x = rows_of(df[df["right"].isin(["CE", "PE"])])
    x = x[x.d >= lo].drop_duplicates(["d", "right", "strike", "r"], keep="first")
    out: dict = {}
    for (d, rt, k), g in x.groupby(["d", "right", "strike"], sort=True):
        c = np.full((5, NROWS), np.nan, dtype=np.float32)
        for i, col in enumerate(("open", "high", "low", "close", "volume")):
            c[i, g.r.values] = g[col].values
        out.setdefault(d, {})[(rt[0], float(k))] = c
    return out


def expiry_dates(df: pd.DataFrame) -> set:
    """Every contract expiry date in the frame, with the moved Jan 2025 monthly mapped to its real date."""
    e = pd.to_datetime(df.loc[df["right"].isin(["CE", "PE"]), "expiry"].dropna().unique())
    return {EXPIRY_MOVED.get(x.date(), x.date()) for x in e}


def atm_row(idx: np.ndarray, step: float = STEP) -> np.ndarray:
    """The index close rounded to the strike step, per minute (NaN where there is no close)."""
    return np.round(idx[3] / step) * step


# ================================================================================================ steps
def fetch():
    """Download the release files with `gh api` (the session's own GitHub access; no token is read or printed)."""
    RAW.mkdir(parents=True, exist_ok=True)
    names = sorted({f for v in FILES.values() for f in v})
    q = '.assets[] | "\\(.name) \\(.id)"'
    lst = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{TAG}", "--jq", q], check=True,
                         capture_output=True, text=True).stdout.split("\n")
    ids = dict(x.split() for x in lst if x.strip())
    for n in names:
        p = RAW / n
        if p.exists() and p.stat().st_size > 0:
            continue
        with open(p, "wb") as f:
            subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                            f"repos/{REPO}/releases/assets/{ids[n]}"], check=True, stdout=f)
        print("fetched", n, p.stat().st_size, flush=True)


def build() -> dict:
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51data                       # the Day class (pickled under r51data, as R51's stores)

    STORE.mkdir(parents=True, exist_ok=True)
    out = {}
    for und, files in FILES.items():
        df = pd.concat([pd.read_parquet(RAW / f) for f in files], ignore_index=True)
        ix = index_days(df)
        (STORE / f"idx_{und}.pkl").write_bytes(pickle.dumps(ix, protocol=pickle.HIGHEST_PROTOCOL))
        out[und] = dict(index_days=len(ix), first=str(min(ix)), last=str(max(ix)))
        if und == "BANKNIFTY":
            con, exp = contracts(df), expiry_dates(df)
            days = {}
            for d, a in ix.items():
                D = r51data.Day(d, a)
                if d in con:
                    D.atm, D.con = atm_row(a), con[d]
                D.expiry = d in exp
                days[d] = D
            (STORE / "day_BANKNIFTY.pkl").write_bytes(pickle.dumps(days, protocol=pickle.HIGHEST_PROTOCOL))
            out[und].update(option_days=len(con), expiry_days=sum(1 for d in con if d in exp),
                            first_option=str(min(con)), last_option=str(max(con)))
        print(und, out[und], flush=True)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "fetch":
        fetch()
    elif cmd == "build":
        build()
    else:
        print(__doc__)
