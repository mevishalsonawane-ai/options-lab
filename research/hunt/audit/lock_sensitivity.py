"""Exit audit, part 2 (14 Oct 2026): every study's lock-based trades re-simulated (1) exactly as published (must reproduce
the stored nets) and (2) with the step / trail locks raised only by CONFIRMED prices (a bar's high counts only up to
max(its close, the next open)), so a single freak print cannot arm a lock.

    python3 research/hunt/audit/lock_sensitivity.py      # -> research/hunt/audit/lock_sensitivity.json
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r34", ROOT / "research" / "hunt" / "r35", ROOT / "research" / "hunt" / "r37"):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r34cfg  # noqa: E402,F401
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
import r37flip as X  # noqa: E402
from r25data import C  # noqa: E402

DAYS: dict = {}


def day(und, d):
    if und not in DAYS:
        DAYS[und] = {x.day: x for x in D.load(und, "all")}
    return DAYS[und][date.fromisoformat(d)]


def row_of(hm: str) -> int:
    h, m = map(int, hm.split(":"))
    return R.close_row(h, m)


def run(name, trades, sim) -> dict:
    stored = recomputed = confirmed = 0.0
    changed = 0
    bad = []
    for t in trades:
        a, b = sim(t, "high"), sim(t, "confirmed")
        na, nb = (a.net if a else 0.0), (b.net if b else 0.0)
        stored += t["net"]
        recomputed += na
        confirmed += nb
        if abs(round(na) - t["net"]) > 1:
            bad.append((t["day"], t["net"], round(na)))
        if a and b and [x[:3] for x in a.exits] != [x[:3] for x in b.exits]:
            changed += 1
    out = {"trades": len(trades), "stored_net": round(stored), "recomputed_net": round(recomputed),
           "reproduces": not bad, "confirmed_lock_net": round(confirmed), "trades_whose_exit_changed": changed,
           "mismatches": bad[:5]}
    print(name, out, flush=True)
    return out


def main():
    res = {}
    r26 = json.loads((ROOT / "research/hunt/r26/results/results.json").read_text())["rules"]
    res["R26-A6 (E3)"] = run("R26-A6", r26["A6"]["trades_list"], lambda t, arm: L26.simulate(
        day(t["und"], t["day"]), t["strike"], 1 if t["side"] == "CE" else -1, row_of(t["signal"]),
        R.ltp(day(t["und"], t["day"]).opts[(t["strike"], "C" if t["side"] == "CE" else "P")], row_of(t["signal"]))[0],
        "E3", 2000.0, arm=arm))
    r34 = json.loads((ROOT / "research/hunt/r34/results/final.json").read_text())["rules"]
    m2 = [dict(t, und="NIFTY") for t in r34["M2"]["trades_list"]]
    res["R34-M2 (E3)"] = run("R34-M2", m2, lambda t, arm: L26.simulate(
        day("NIFTY", t["day"]), t["strike"], 1 if t["side"] == "C" else -1, t["t"], t["price"], "E3", 2000.0, arm=arm))
    r35 = json.loads((ROOT / "research/hunt/r35/results/results.json").read_text())["rules"]
    for rid in ("S1", "S3", "S4", "S5"):
        res[f"R35-{rid} (E3)"] = run(f"R35-{rid}", r35[rid]["trades_list"], lambda t, arm: L26.simulate(
            day(t["und"], t["day"]), t["strike"], 1 if t["side"] == "CE" else -1, t["sig"],
            float(day(t["und"], t["day"]).opts[(t["strike"], "C" if t["side"] == "CE" else "P")][t["sig"], C]), "E3", 2000.0, arm=arm))
    r37 = json.loads((ROOT / "research/hunt/r37/results/results.json").read_text())["rules"]
    for rid in ("G1", "G2", "G4"):
        res[f"R37-{rid} (T2)"] = run(f"R37-{rid}", r37[rid]["trades_list"], lambda t, arm: X.simulate(
            day(t["und"], t["day"]), t["strike"], 1 if t["side"] == "CE" else -1, t["sig"],
            float(day(t["und"], t["day"]).opts[(t["strike"], "C" if t["side"] == "CE" else "P")][t["sig"], C]), "T2", arm=arm)[0])
    (HERE / "lock_sensitivity.json").write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
