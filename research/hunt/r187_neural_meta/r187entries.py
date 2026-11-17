"""R187 experiment entries: one per use (U1, U2, U3: each covers the six arms; U4: R72 and R51) and one for the network
itself, written from results/year.json and results/decision.json.

    python research/hunt/r187_neural_meta/r187entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r187cfg as K  # noqa: E402

ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
LAB = {"U1": "network skip: skip a trade the shared network scores below a validation-chosen P(win)",
       "U2": "network exit choice: per trade, the studied exit the network expects to net the most",
       "U3": "network skip + exit choice (U2 then U1)",
       "U4": "network picker in place of R151's picker (R72 / R51)"}
DATA = ("Drive lake gdrive:IraAlgo-data-lake (index 1-minute 2017/2021 - 13 Nov 2026, rolling index options Aug 2021 - "
        "13 Nov 2026); R51 clean stores to 19 Oct 2026 and R186's extended copy to 13 Nov; R3 BANKNIFTY store rebuilt to 13 Nov")
PERIOD = dict(design="training from Aug 2021 (ORB) / 20 Nov 2024 (R arms); walk-forward folds Apr 2025 - Mar 2026",
              holdout="final 1 Apr - 13 Oct 2026 (read again: contaminated); year 20 Oct 2025 - 19 Oct 2026; "
                      "new month 21 Oct - 13 Nov 2026 descriptive")


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((K.RESULTS / "year.json").read_text())
    d = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for u, lab in LAB.items():
        arms = [a for a, uu in K.TESTS if uu == u]
        parts = []
        for a in arms:
            t, g = r["tables"][a], d[f"{a}:{u}"]
            y, b, f, bf = t[u]["year"], t["BASE"]["year"], t[u]["final"], t["BASE"]["final"]
            parts.append(f"{K.ARM_NAME[a]}: year {y['trades']} trades win {100 * (y['win'] or 0):.1f}% {rs(y['net'])} "
                         f"(base {b['trades']} / {100 * (b['win'] or 0):.1f}% / {rs(b['net'])}); final {f['trades']} "
                         f"{100 * (f['win'] or 0):.1f}% {rs(f['net'])} (base {rs(bf['net'])}); twin p {g['twin_p']:.3f}; "
                         + ("passed" if g["passed"] else "failed: " + "; ".join(g["fails"])))
        e = dict(
            id=f"r187-{u.lower()}", title=f"R187 {u}: {lab}", family="ml", date="2026-11-16",
            doc="research/hunt/r187_neural_meta/results/summary.md",
            hypothesis=("Boss: a multi-layer network over all the lake data and the earlier studies makes the ORB family and "
                        "the other arms more profitable, with a higher win rate and smaller losses."),
            data_version=DATA, period=PERIOD, instruments=list(K.UNDS),
            features=["signal source: the index (entries unchanged)",
                      "network inputs: traded-index and cross-index minute features, India VIX, option-chain context (OI, "
                      "IV, PCR, ATM premium / OI changes), R151's 28 features + picker score, R169's 11 ORB features, R183 "
                      "sideways measures, arm / underlying / exit identity"],
            model_or_rule=lab + "; sklearn MLP classifier + regressor (2 settings x 2 alphas, 3 hidden layers), walk-forward "
                                "quarterly then frozen at 31 Mar 2026; PREREG.md section 3",
            settings="research/hunt/r187_neural_meta/r187study.py", prereg="research/hunt/r187_neural_meta/PREREG.md",
            variants_tried=len(arms), results=dict(summary=" | ".join(parts) + ".",
                                                   costs="dated Zerodha charges; R17 fills (R arms: R186 engine; ORB: "
                                                         "R169's B0 engine)"),
            holdout_read=True, verdict="rejected" if not any(d[f"{a}:{u}"]["passed"] for a in arms) else "inconclusive",
            notes=f"BH m = {d['_m']['total']:,}. A pass would be a second look on a contaminated final (shadow only). "
                  "Nothing in the app changes.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(f"{n} entries")


if __name__ == "__main__":
    main()
