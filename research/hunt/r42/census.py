"""R42 census: share of option minutes flagged as bad prints, per book and time of day (PREREG.md).
Usage: python -I census.py LAKE_DIR OUT_DIR   (LAKE_DIR holds options_rolling_1m/; OUT_DIR gets census*.csv and
flags_<BOOK>.parquet for later steps; only the small CSVs are copied into results/)."""
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
from spikes import flag_spikes, naive_sweep  # noqa: E402

BOOKS = {"SILVERM": "MONTH", "SILVER": "MONTH", "GOLDM": "MONTH", "GOLD": "MONTH", "CRUDEOIL": "MONTH",
         "NATURALGAS": "MONTH", "NIFTY": "WEEK", "BANKNIFTY": "MONTH"}
T0, T1 = pd.Timestamp("2025-08-05", tz="Asia/Kolkata"), pd.Timestamp("2026-10-13", tz="Asia/Kolkata")
NSE = {"NIFTY", "BANKNIFTY"}


def tod_bucket(tod, nse):
    if nse:
        return np.where(tod < 10 * 60 + 45, "09:15-10:44", "10:45-15:30")
    return np.select([tod < 10 * 60 + 45, tod < 14 * 60, tod < 17 * 60], ["09:00-10:44", "10:45-13:59", "14:00-16:59"],
                     "17:00-23:30")


def load_book(lake, book):
    fs = sorted((Path(lake) / "options_rolling_1m").glob(f"opt_{book}_{BOOKS[book]}_202[56].parquet"))
    d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    d = d[(d.ts >= T0) & (d.ts < T1) & d.offset.between(-3, 3)].reset_index(drop=True)
    d["side"] = d.side.astype(str)
    return d


def main(lake, out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    rows, trows = [], []
    for book in BOOKS:
        d = load_book(lake, book)
        nse = book in NSE
        d["tod"] = d.ts.dt.hour * 60 + d.ts.dt.minute
        d["tb"] = tod_bucket(d.tod.values, nse)
        d["traded"] = d.volume > 0
        d["s0"] = naive_sweep(d)
        fa = flag_spikes(d)
        d["f1_all"], d["f2_all"] = fa.f1, fa.f2
        tr = d[d.traded]
        ft = flag_spikes(tr)
        d["f1_tr"], d["f2_tr"] = False, False
        d.loc[tr.index, "f1_tr"] = ft.f1.values
        d.loc[tr.index, "f2_tr"] = ft.f2.values
        d["judged_tr"] = False
        d.loc[tr.index, "judged_tr"] = ft.judged.values
        # how big were the naive jumps that F1 did not confirm (mostly strike rolls)
        d["day"] = d.ts.dt.tz_localize(None).dt.normalize()
        d[["ts", "offset", "side", "strike", "close", "high", "low", "volume", "s0", "f1_all", "f2_all", "f1_tr",
           "f2_tr", "judged_tr"]].to_parquet(out / f"flags_{book}.parquet")
        nt = max(int(d.traded.sum()), 1)
        r = dict(book=book, rows=len(d), days=d.day.nunique(), traded_share=d.traded.mean(),
                 s0_naive=d.s0.mean(), f1_all=d.f1_all.mean(), f2_all=d.f2_all.mean(),
                 f1_traded=d.f1_tr.sum() / nt, f2_traded=d.f2_tr.sum() / nt, judged_traded=d.judged_tr.sum() / nt,
                 n_f1_traded=int(d.f1_tr.sum()), n_f2_traded=int(d.f2_tr.sum()),
                 s0_max_jump=float((d.close / d.groupby(["offset", "side", "day"]).close.shift(1) - 1).max()))
        rows.append(r)
        for tb, x in d.groupby("tb"):
            ntb = max(int(x.traded.sum()), 1)
            trows.append(dict(book=book, tod=tb, rows=len(x), traded_share=x.traded.mean(), s0_naive=x.s0.mean(),
                              f1_all=x.f1_all.mean(), f1_traded=x.f1_tr.sum() / ntb, f2_traded=x.f2_tr.sum() / ntb,
                              n_f1_traded=int(x.f1_tr.sum())))
        print(pd.Series(r).to_string(), flush=True)
    pd.DataFrame(rows).to_csv(out / "census.csv", index=False)
    pd.DataFrame(trows).to_csv(out / "census_tod.csv", index=False)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
