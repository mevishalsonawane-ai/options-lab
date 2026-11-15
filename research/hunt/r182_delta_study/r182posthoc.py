"""R182 post-hoc check (after the one run; descriptive, not a test, disclosed in the report): which signing tracks the
same minute's price change best on the SAME minutes - the minute file's tick rule (TickZone), the 1-second file's tick rule
(OrderFlow) and the 1-second file's quote rule - on the 9 days both recorders exist, NIFTY and BANKNIFTY. Minutes with
>= 50 s in the minute file and >= 20 s in the 1-second file; each signing against its OWN file's price change.

    python -I research/hunt/r182_delta_study/r182posthoc.py --rec2 <dir> --rec1 <dir> --cache <dir>

Writes results/posthoc.json. Plain-English notes, 15 Nov 2026."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE), str(HERE.parents[2])]
import r182lib as LB  # noqa: E402
import r182load as LD  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--rec2", required=True)
    ap.add_argument("--rec1", required=True)
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    out = {}
    for u in ("NIFTY", "BANKNIFTY"):
        X = {k: [] for k in ("minute_tick", "sec_tick", "sec_quote")}
        Y = {k: [] for k in ("minute", "sec")}
        days = []
        for d in LD.flow_days(a.rec2):
            m = LD.load_min_day(a.rec1, d, a.cache)
            if m is None:
                continue
            f = LD.load_flow_day(a.rec2, d, a.cache)
            b = LB.minute_bars(f[u]["sec"], f["t0"])
            mm = m[u]
            ok = (mm["secs"] >= 50) & (b.rows >= LB.THIN_ROWS) & np.isfinite(mm["d"])
            ok[0] = False
            ok &= np.roll(ok, 1)          # the minute before is there too (for the price change)
            r1 = LB.same_minute_return(mm["c"])
            r2 = LB.same_minute_return(b.c)
            X["minute_tick"].append(np.where(ok, mm["d"], np.nan))
            X["sec_tick"].append(np.where(ok, b.d, np.nan))
            X["sec_quote"].append(np.where(ok, b.dq, np.nan))
            Y["minute"].append(np.where(ok, r1, np.nan))
            Y["sec"].append(np.where(ok, r2, np.nan))
            days.append(d)
        res = dict(days=days)
        for k, yk in (("minute_tick", "minute"), ("sec_tick", "sec"), ("sec_quote", "sec")):
            bs = LB.day_bootstrap(X[k], Y[yk], draws=1000, seed=41)
            x, y = np.concatenate(X[k]), np.concatenate(Y[yk])
            f = np.isfinite(x) & np.isfinite(y) & (x != 0) & (y != 0)
            res[k] = dict(r=bs["r"], lo=bs["lo"], hi=bs["hi"], n=bs["n"],
                          sign_agree=float(np.mean(np.sign(x[f]) == np.sign(y[f]))), n_sign=int(f.sum()))
        out[u] = res
    (HERE / "results" / "posthoc.json").write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
