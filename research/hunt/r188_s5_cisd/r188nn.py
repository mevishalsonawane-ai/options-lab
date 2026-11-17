"""R188: R123's network views for the new sessions (21 Oct - 13 Nov 2026), with R134's frozen pipeline.
Plain-English notes, 17 Nov 2026.

    python research/hunt/r188_s5_cisd/r188nn.py check   # 1 - 19 Oct 2026 rebuilt vs R123's saved holdout predictions
    python research/hunt/r188_s5_cisd/r188nn.py new     # 21 Oct - 13 Nov 2026 -> <cache>/r188/r123/new_slim.parquet

Nothing is re-fitted: R134's nnlive.load_model (R123 N1's holdout weights, scalers refitted from R123's design rows as
R123 did) and R134's build_rows (R123's decision-row inputs). One change, for the gap files only: R123's
LakeOptions.day reads the first file of a year; here it reads every file of that year (the 2026 file and the gap file),
the earlier file winning a duplicate minute. On days the gap file does not cover this is the same frame.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r188data as RD  # noqa: E402
import forward as FW  # noqa: E402  (R134)
import nnlive  # noqa: E402  (R134)

D3 = RD.D3


def _day(self, d, closes=None, log=None):
    """R123's LakeOptions.day reading every file of the year (see the module note)."""
    if self.year != d.year:
        self.by_day = RD.year_frame(self.files, d.year, self.last_day)
        self.year = d.year
    g = self.by_day.get(d)
    if g is None or g.empty:
        return None
    if closes is not None:
        w = g.rename(columns={"d": "day", "m": "r"})
        kept, _, _ = D3.lakeclean.clean(w, {d: closes}, stale=True)
        g = kept.rename(columns={"day": "d", "r": "m"})
        if g.empty:
            return None
    return D3.DayOptions(g, C.STEP[self.und])


D3.LakeOptions.day = _day
SLIM = ["und", "day", "row", "close", "pdir30", "atm_iv", "vix", "vix_chg", "is_expiry", "to_expiry"]


def views(first: date, last: date) -> pd.DataFrame:
    F, S = FW.build_rows(first, last)
    m, mn = nnlive.load_model()
    pn = m.predict(F, nnlive.M.SeqStore(S))
    return nnlive.RR.attach(F, pn, mn).reset_index(drop=True)


def check() -> dict:
    A = views(date(2026, 10, 1), date(2026, 10, 19))
    ref = pd.read_parquet(C.R123 / "holdout_slim.parquet")
    ref["day"] = pd.to_datetime(ref.day).dt.date
    ref = ref[ref.day >= date(2026, 10, 1)]
    M = A.merge(ref, on=["und", "day", "row"], suffixes=("", "_ref"))
    ev = M[[f"ev_{j}" for j in range(16)]].values
    er = M[[f"ev_{j}_ref" for j in range(16)]].values
    pw = float(np.nanmax(np.abs(M.pdir30.values - M.pdir30_ref.values)))
    out = dict(rows_rebuilt=len(A), rows_saved=len(ref), matched=len(M), max_ev_diff=float(np.nanmax(np.abs(ev - er))),
               ev_sign_agree=float(np.mean(np.sign(ev) == np.sign(er))), max_pdir30_diff=pw)
    out["ok"] = bool(len(M) == len(ref) and out["ev_sign_agree"] >= 0.99 and pw < 1e-3)
    C.RESULTS.mkdir(parents=True, exist_ok=True)
    (C.RESULTS / "nn_check.json").write_text(json.dumps(out, indent=1))
    return out


def new() -> pd.DataFrame:
    A = views(*C.NEW)
    A = A[[c for c in SLIM if c in A] + [f"ev_{j}" for j in range(16)]].copy()
    A["fold"] = "new"
    A.to_parquet(C.R123 / "new_slim.parquet")
    return A


if __name__ == "__main__":
    if sys.argv[1] == "check":
        print(check())
    else:
        A = new()
        print("new rows", len(A), A.day.min(), A.day.max(), A.groupby("und").day.nunique().to_dict())
