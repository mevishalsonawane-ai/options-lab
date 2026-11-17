"""R183 section 6 (descriptive, never a test): what the chosen filter would have done to R51 / R72's paper trades on
9 - 13 Nov 2026, from the phone's diagnostics (paper orders) and market recordings. Inputs are untrusted, read-only and
stay OUTSIDE git; run with python -I and pass their folders:

    python -I research/hunt/r183_sideways_filter/r183recent.py <diag_dir> <orderflow_csv_dir>   # -> results/recent.json

Index bars: NIFTY and BANKNIFTY from the recorded index level (role INDEX), FINNIFTY and MIDCPNIFTY from their recorded
near future (role FUTURE; a labelled proxy: the index itself is not recorded), SENSEX not recorded (not computable).
Earlier sessions: the recorded days, then the store's index days (to 19 Oct) to make up 20. Plain-English notes, 15 Nov 2026.
"""
from __future__ import annotations

import glob
import json
import os
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (str(ROOT), str(HERE.parent / "r182_delta_study"), str(HERE.parent / "r179_winrate_55"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
os.environ.setdefault("R176_POST", "1")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import r183lib as LB  # noqa: E402

DAYS = ("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13")
ROLE = {"NIFTY": "INDEX", "BANKNIFTY": "INDEX", "FINNIFTY": "FUTURE", "MIDCPNIFTY": "FUTURE"}
IST = timezone(timedelta(hours=5, minutes=30))


def t0_of(d: date) -> int:
    return int(datetime(d.year, d.month, d.day, 9, 15, tzinfo=IST).timestamp())


def minute_bars(epoch: np.ndarray, last: np.ndarray, t0: int) -> np.ndarray:
    """(4, 375) O H L C per minute row from per-second prices (rows with no price stay NaN)."""
    a = np.full((4, 375), np.nan)
    r = (np.asarray(epoch, np.int64) - t0) // 60
    ok = (r >= 0) & (r < 375) & np.isfinite(last) & (last > 0)
    r, x = r[ok], np.asarray(last, float)[ok]
    order = np.argsort(r, kind="stable")
    r, x = r[order], x[order]
    for row in np.unique(r):
        v = x[r == row]
        a[:, row] = (v[0], v.max(), v.min(), v[-1])
    return a


def recorded(flow_dir: str) -> dict:
    """{und: {date: (4, 375)}} from every orderflow-YYYY-MM-DD.csv.gz."""
    out = {u: {} for u in ROLE}
    for p in sorted(glob.glob(os.path.join(flow_dir, "orderflow-20??-??-??.csv.gz"))):
        d = date.fromisoformat(os.path.basename(p)[10:20])
        df = pd.read_csv(p, usecols=["epoch_sec", "name", "role", "last"], low_memory=False)
        for u, role in ROLE.items():
            g = df[(df.name == u) & (df.role == role)].drop_duplicates("epoch_sec", keep="last")
            if len(g):
                out[u][d] = minute_bars(g.epoch_sec.values, pd.to_numeric(g["last"], errors="coerce").values, t0_of(d))
    return out


def history(und: str, rec: dict, d: date) -> list:
    """Earlier sessions (oldest first, up to 20): recorded days before d, then the store's days before the first one."""
    mine = [k for k in sorted(rec) if k < d]
    if len(mine) < LB.N_ADR:
        import r179study as R9
        D = R9.R7.mods()["r51data"].load(und)
        first = mine[0] if mine else d
        older = [k for k in sorted(D) if k < first][-(LB.N_ADR - len(mine)):]
        return [D[k].idx for k in older] + [rec[k] for k in mine]
    return [rec[k] for k in mine[-LB.N_ADR:]]


def main(diag_dir: str, flow_dir: str) -> dict:
    import r182lib as RL
    import r182load as RLD
    trades, st = RL.rebuild_trades(RLD.load_orders(diag_dir))
    tr = [t for t in trades if t["arm"] in LB.ARMS and t["day"] in DAYS]
    rec = recorded(flow_dir)
    sel = json.loads((HERE / "results" / "selection.json").read_text())
    chosen = {a: sel[a]["chosen"] for a in LB.ARMS}
    rows = []
    for t in tr:
        d = date.fromisoformat(t["day"])
        hh, mm, _ = (int(x) for x in t["entry_at"].split(":"))
        er = hh * 60 + mm - (9 * 60 + 15)                         # the entry minute; signal bar er - 1
        r = dict(arm=t["arm"], day=t["day"], und=t["und"], at=t["entry_at"], net=round(t["net"]), win=bool(t["win"]))
        if t["und"] not in ROLE or d not in rec[t["und"]]:
            r["source"] = "not recorded"
        else:
            f = LB.features(history(t["und"], rec[t["und"]], d), rec[t["und"]][d], er - 1)
            r["source"] = "index" if ROLE[t["und"]] == "INDEX" else "near future (proxy)"
            r["feats"] = {k: (None if isinstance(v, float) and not np.isfinite(v) else round(float(v), 3)) for k, v in f.items()}
            r["skip"] = {v: bool(LB.skip(v, f)) for v in LB.VARIANTS}
        rows.append(r)
    wd = [date(2026, 10, 15) + timedelta(i) for i in range(30)]
    missing = [str(x) for x in wd if x.weekday() < 5 and x <= date(2026, 11, 13)
               and x not in rec["NIFTY"]]
    out = dict(trades=len(rows), wins=sum(r["win"] for r in rows), net=sum(r["net"] for r in rows), chosen=chosen,
               rebuild=st, recorded_days=[str(k) for k in sorted(rec["NIFTY"])], weekdays_not_recorded=missing,
               by_source={s_: sum(r["source"] == s_ for r in rows) for s_ in ("index", "near future (proxy)", "not recorded")})
    summ = {}
    for v in LB.VARIANTS:
        for a in LB.ARMS:
            comp = [r for r in rows if r["arm"] == a and "skip" in r]
            sk = [r for r in comp if r["skip"][v]]
            kp = [r for r in comp if not r["skip"][v]]
            summ[f"{a}:{v}"] = dict(computable=len(comp), skipped=len(sk), skipped_wins=sum(r["win"] for r in sk),
                                    skipped_net=sum(r["net"] for r in sk), kept=len(kp),
                                    kept_wins=sum(r["win"] for r in kp), kept_net=sum(r["net"] for r in kp))
    out["by_variant"] = summ
    (HERE / "results" / "recent.json").write_text(json.dumps(out, indent=1))      # aggregates only (no trade rows)
    cache = Path(os.environ.get("R183_CACHE", Path.home() / ".cache" / "iraalgo" / "r183")).expanduser()
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "recent_rows.json").write_text(json.dumps(rows, indent=1))           # per trade, outside git
    return dict(out, rows=rows)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    o = main(sys.argv[1], sys.argv[2])
    print(json.dumps({k: o[k] for k in ("trades", "wins", "net", "chosen", "weekdays_not_recorded")}, indent=1))
    for a, v in o["chosen"].items():
        if v:
            print(a, v, o["by_variant"][f"{a}:{v}"])
