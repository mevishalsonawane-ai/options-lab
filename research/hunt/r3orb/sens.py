#!/usr/bin/env python3
"""Post-hoc data-quality sensitivity (not a gate; written after the holdout read, 14 Oct 2026): every rule's net when
trades whose entry premium is above 3% of the strike are dropped (an ATM / 1-ITM weekly or monthly option is almost
never worth that much; such prints are lake mislabels, e.g. FINNIFTY 16 Feb 2022, Rs 796 at a 17,600 strike).

    python3 research/hunt/r3orb/sens.py      # reads <OUT>/design_trades.json and holdout_trades.json -> results/sens.json
"""
import json

import r3cfg as K

CAP = 0.03


def bad(t):
    return t["entry"] > CAP * t["k"]


def main():
    out = {}
    for part in ("design", "holdout"):
        tr = json.loads((K.OUT / f"{part}_trades.json").read_text())
        out[part] = {vid: dict(net=round(sum(t["net"] for t in ts)), clean_net=round(sum(t["net"] for t in ts if not bad(t))),
                               dropped=sum(bad(t) for t in ts), trades=len(ts),
                               clean_by_und={u: round(sum(t["net"] for t in ts if t["und"] == u and not bad(t)))
                                             for u in sorted({t["und"] for t in ts})})
                     for vid, ts in tr.items()}
    (K.RESULTS / "sens.json").write_text(json.dumps(out, indent=1))
    for part, r in out.items():
        for vid, x in r.items():
            print(part, vid, x["net"], "->", x["clean_net"], "dropped", x["dropped"], x["clean_by_und"])


if __name__ == "__main__":
    main()
