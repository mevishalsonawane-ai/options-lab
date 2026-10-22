"""R104 study (PREREG.md): 12 overnight defined-risk selling tests, design first, the holdout once.

    R103_LAKE=<lake> R103_OUT=<cache> python research/hunt/r104_overnight/r104study.py design
    ... python research/hunt/r104_overnight/r104study.py holdout      # ONE read, design passers only

Uses R103's stores (research/hunt/r103_sell/r103data.py), statistics and gates (r103study.py). Trades go to
<R103_OUT>/r104_trades_<part>.pkl (never in git); results/ holds small JSON only.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r103_sell"))
import r103data as RD  # noqa: E402
import r103study as S3  # noqa: E402
import r104engine as E4  # noqa: E402

E = E4.E
RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
DESIGN_END = date(2026, 7, 14)
HOLDOUT_START, HOLDOUT_END = date(2026, 7, 15), date(2026, 10, 16)
SEED = 104
TESTS = [(u, s, x) for u in RD.UNDS for s in E.STRUCTS for x in E4.EXITS]


def test_id(u, s, x) -> str:
    return f"{u}-{s}-{x}"


def period_of(d: date) -> str:
    if d <= DESIGN_END:
        return "design"
    if HOLDOUT_START <= d <= HOLDOUT_END:
        return "holdout"
    return "after"


def pairs(und: str, part: str) -> list:
    """(d0, d1, expiry, d1 is an expiry day) for every eligible entry day of a period."""
    days = RD.load(und)
    exp, near = S3.expiry_info(und, days)
    ds = [d for d in sorted(days) if d >= RD.START and days[d].atm is not None]
    out = []
    for d0, d1 in zip(ds[:-1], ds[1:]):
        x = near.get(d0)
        if period_of(d0) != part or x is None or x <= d0 or d1 > x:
            continue
        out.append((d0, d1, x, d1 in exp))
    return out


def _one(args):
    part, mult, tid = args
    u, s, x = tid.split("-")
    days = RD.load(u)
    out = []
    for d0, d1, ex, d1e in pairs(u, part):
        t = E4.run(days[d0], days[d1], u, d0, d1, s, x, ex, d1e, mult)
        if t is not None:
            t["expiry"] = ex
            out.append(t)
    return tid, out


def trades(part: str, mult: float = 1.0, only: set | None = None) -> dict:
    if part == "holdout" and not HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r104study.py holdout` may read it (PREREG.md)")
    import multiprocessing as mp
    ids = [test_id(*t) for t in TESTS if only is None or test_id(*t) in only]
    with mp.get_context("fork").Pool(4) as pool:
        return dict(pool.map(_one, [(part, mult, i) for i in ids]))


def bhav_check(und: str, trs: list) -> dict:
    """O2 time exits re-priced at the F&O bhavcopy close of every leg on d1 (the held contract), 1-tick fills, dated
    charges; stops keep their minute fills. Totals over trades with a bhavcopy price for all legs."""
    sys.path.insert(0, str(HERE.parent / "r102_deep"))
    import pandas as pd
    import r102data as B
    B.LAKE = RD.LAKE
    b = B.bhav(und)
    key = b.set_index(["date", "expiry", "kind", "strike"]).close
    key = key[~key.index.duplicated()]
    n, nm, nb, wm, wb = 0, 0.0, 0.0, 0, 0
    for t in trs:
        net = t["net"]
        if t["why"] == "time":
            d1, ex = pd.Timestamp(t["d1"]), pd.Timestamp(t["expiry"])
            px = [key.get((d1, ex, "CE" if s_ == "C" else "PE", float(k)), np.nan) for s_, k, _ in t["legs"]]
            if not np.all(np.isfinite(px)):
                continue
            q = t["lot"]
            exit_ch = sum(E.charges(x, q, sg < 0, t["d1"]) for (_, _, sg), x in zip(t["legs"], t["exitpx"]))
            gross, ch = 0.0, t["charges"] - exit_ch
            for (_, _, sg), f, p in zip(t["legs"], t["entry"], px):
                x = E.slip(float(p), sg < 0, E.THIN_LOTS)
                gross += sg * (x - f) * q
                ch += E.charges(x, q, sg < 0, t["d1"])
            net = gross - ch
        n += 1
        nm += t["net"]
        nb += net
        wm += t["net"] > 0
        wb += net > 0
    return dict(n=n, net_minute=round(nm), net_bhav=round(nb), win_minute=round(wm / n, 3) if n else None,
                win_bhav=round(wb / n, 3) if n else None)


def _save(name: str, obj):
    RD.OUT.mkdir(parents=True, exist_ok=True)
    with open(RD.OUT / f"{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def design() -> dict:
    rng = np.random.default_rng(SEED)
    tr, tr2 = trades("design"), trades("design", mult=2.0)
    _save("r104_trades_design", tr)
    rows = []
    for tid in [test_id(*t) for t in TESTS]:
        v = tr.get(tid, [])
        s = S3.summary(v, rng)
        fri = S3.summary([t for t in v if t["friday"]])
        oth = S3.summary([t for t in v if not t["friday"]])
        rows.append(dict(id=tid, design=s, slip2x_net=S3.summary(tr2.get(tid, [])).get("net", 0),
                         friday=dict(n=fri.get("n", 0), net=fri.get("net", 0), win=fri.get("win")),
                         other_days=dict(n=oth.get("n", 0), net=oth.get("net", 0), win=oth.get("win")),
                         mean_gross=round(float(np.mean([t["gross"] for t in v])), 1) if v else None,
                         mean_charges=round(float(np.mean([t["charges"] for t in v])), 1) if v else None,
                         bhav_check=bhav_check(tid.split("-")[0], v)))
    qs = S3.bh([r["design"].get("boot_p", 1.0) for r in rows])
    for r, q in zip(rows, qs):
        r["q_bh"] = round(q, 4)
        g = S3.gates(r["design"], dict(net=r["slip2x_net"]), q)
        g["8_bhav_check"] = (r["bhav_check"].get("net_bhav", 0) > 0) if r["id"].endswith("O2") else True
        g["pass"] = all(v for k, v in g.items() if k != "pass")
        r["gates"] = g
    res = dict(part="design", design_end=str(DESIGN_END), n_tests=len(rows), passed=[r["id"] for r in rows if r["gates"]["pass"]],
               tests=rows)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "design.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(f"design: {len(rows)} tests; passed: {res['passed']}")
    return res


def holdout() -> dict:
    if HOLDOUT_FLAG.exists():
        print("the holdout was already read once (results/HOLDOUT_READ); PREREG.md allows no second read", file=sys.stderr)
        raise SystemExit(2)
    dz = json.loads((RESULTS / "design.json").read_text())
    passed = set(dz["passed"])
    if not passed:
        print("holdout: no design passer; nothing read (the holdout stays unread)")
        return dict(read=False)
    HOLDOUT_FLAG.write_text(f"read on {date.today().isoformat()} by r104study.py holdout; passers {sorted(passed)}\n")
    tr = trades("holdout", only=passed)
    _save("r104_trades_holdout", tr)
    rows = []
    for tid in sorted(passed):
        s = S3.summary(tr.get(tid, []))
        dsg = next(r["design"] for r in dz["tests"] if r["id"] == tid)
        ok = s.get("n", 0) > 0 and s["net"] > 0 and s["worst_month"] >= S3.MONTH_STOP and abs(s["win"] - dsg["win"]) <= 0.15
        rows.append(dict(id=tid, holdout=s, confirmed=bool(ok)))
    res = dict(part="holdout", read=True, confirmed=[r["id"] for r in rows if r["confirmed"]], tests=rows)
    (RESULTS / "holdout.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    print(f"holdout: confirmed {res['confirmed']}")
    return res


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "holdout":
        holdout()
    else:
        raise SystemExit(__doc__)
