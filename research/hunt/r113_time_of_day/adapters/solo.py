"""R113 adapter (26 Oct 2026): Solo 2.0 (R57 variant A2) per-trade list, from R57's frozen pipeline, unchanged.

Signal source: the underlying index (A2 scores R57's index-signal candidates S1 / S3 / S4 / C4 from index and chain
features; the option is only what is bought, at the setup's strike rule; exits are R57's EXITS on the premium plus the
setups' index exits). This is the same call r106solo.picks() makes (r57study.run_a(VARIANTS["A2"], ...) on the walk-forward
months), except that a parity gap is REPORTED instead of asserted (R106 found the final picks off by one day's pick).

    python research/hunt/r57/r57data.py build                  # R57's stores (to 14 Oct), ~/.cache/iraalgo/r57
    python research/hunt/r113_time_of_day/adapters/solo.py <out_dir>

Writes <out_dir>/SOLO.csv and SOLO.parity.json (outside git). Size: 1 lot at the lot in force, R51 engine fills
(next-minute open + R17 slippage) and R51's dated Zerodha charges, as R57 published.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
R57 = HERE.parents[1] / "r57"
sys.path.insert(0, str(R57))
import r57cfg as K57  # noqa: E402  (first: points r51cfg at R57's cache)

import pandas as pd  # noqa: E402

PUBLISHED = {"design": (166, 55910), "final": (128, 99172)}   # HUNT_R57_SOLO_2.md (A2), as r106solo.PUBLISHED
COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]
FINAL_END = date(2026, 10, 13)                                   # R101-R112 final; 14 Oct = Solo's "post" window


def hhmm(d: date, r: int) -> str:
    t = K57.OPEN_MIN + int(r)
    return f"{d.isoformat()} {t // 60:02d}:{t % 60:02d}"


def picks(phase: str) -> list:
    import r57data
    import r57setups as S
    import r57study as R
    for u in K57.UNDS:
        r57data.load(u)
    cal = R.calendar()
    a0, _ = R.load_or_build("design_data", K57.START, K57.DESIGN_END)
    v = R.VARIANTS["A2"]
    if phase == "design":
        tr, _, _ = R.run_a(v, a0, K57.WF_MONTHS, [d for d in cal if d <= K57.DESIGN_END])
    else:
        S.open_final()
        a1, _ = R.load_or_build("final_data", K57.FINAL_START, K57.END)
        tr, _, _ = R.run_a(v, a0 + a1, K57.FINAL_MONTHS, cal)
    return tr


def main(out: str) -> None:
    import r57data
    o = Path(out)
    o.mkdir(parents=True, exist_ok=True)
    rows, parity = [], {}
    for phase in ("design", "final"):
        tr = picks(phase)
        n, net = len(tr), round(sum(t["net"] for t in tr))
        parity[phase] = dict(published=list(PUBLISHED[phase]), reproduced=[n, net], match=(n, net) == PUBLISHED[phase])
        print("Solo A2", phase, n, net, "published", PUBLISHED[phase], flush=True)
        for t in tr:
            d = t["day"]
            D = r57data.load(t["und"])[d]
            per = "design" if phase == "design" else ("final" if d <= FINAL_END else "other")
            rows.append(dict(arm="SOLO", und=t["und"], day=d.isoformat(), entry_ts=hhmm(d, t["er"]),
                             exit_ts=hhmm(d, t["xr"]), side=t["side"], net_rs=round(float(t["net"]), 2), period=per,
                             expiry=int(bool(D.expiry)), setup=t["setup"], why=t.get("why")))
    df = pd.DataFrame(rows).sort_values(["day", "entry_ts"])
    extra = df[["day", "und", "setup", "why"]].copy()
    df[COLS].to_csv(o / "SOLO.csv", index=False)
    meta = dict(arm="SOLO", n=int(len(df)), net=round(float(df["net_rs"].sum()), 0), parity=parity,
                by_period={p: dict(n=int((df["period"] == p).sum()),
                                   net=round(float(df.loc[df["period"] == p, "net_rs"].sum()), 0))
                           for p in ("design", "final", "other")},
                by_und=df.groupby("und")["net_rs"].agg(["count", "sum"]).round(0).to_dict("index"),
                by_setup=extra["setup"].value_counts().to_dict(), exits=extra["why"].value_counts().to_dict(),
                expiry_trades=int(df["expiry"].sum()))
    (o / "SOLO.parity.json").write_text(json.dumps(meta, indent=1, default=str))
    print(json.dumps(meta, default=str))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else str(Path.home() / ".cache/iraalgo/r113/trades"))
