"""h42: write prereg.json + PREREG.md mechanically from answers_raw.csv and prehold_rules.csv (pre-holdout only)."""
from __future__ import annotations

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import HERE, OUT  # noqa: E402
import pandas as pd  # noqa: E402

A = pd.read_csv(os.path.join(HERE, "answers_raw.csv"))
Q = pd.read_csv(os.path.join(HERE, "questions.csv"), dtype=str, keep_default_na=False)
X = pd.read_csv(os.path.join(OUT, "exits_pre.csv.gz"))
L = A[A.candidate_loose].merge(Q, on=["id", "und", "category", "question"])
rules = [dict(id=r.id, und=r.und, question=r.question, table=r.table, universe=r.universe, cond=r.cond, entry=r.entry,
              side_used=r.side_used, horizon=r.horizon, exit=r.best_exit, pre_q=float(r.q), pre_best_net=float(r.best_net),
              pre_best_p=float(r.best_p), pre_edge=float(r.best_edge)) for _, r in L.iterrows()]
json.dump(dict(written="2026-10-08 (run 2, after the look-ahead fix), before the holdout was re-run",
               rule="q<0.05 (BH over all questions) & stable halves & best-exit net>0 & raw one-sided p<0.05 & beats same-minute random",
               rules=rules), open(os.path.join(HERE, "prereg.json"), "w"), indent=1)
if rules:
    subprocess.run([sys.executable, "-I", os.path.join(HERE, "holdout.py"), "pre"], check=True)
    R = pd.read_csv(os.path.join(HERE, "prehold_rules.csv"))
    R = R[R.prereg == True].set_index("id")  # noqa: E712
nb = len(rules)
lines = ["# h42 PRE-REGISTRATION", "",
         "Run 2, written 2026-10-08 by `make_prereg.py` from `answers_raw.csv` (1,296 questions answered on data before 2025-10-01),",
         "before the holdout was re-run. Run 1 was voided: post-holdout diagnostics found a look-ahead bug (15-minute changes at",
         "09:20/09:25 read the END of the day through a negative array index). The bug was fixed, a perturbation audit",
         "(`audit.py`) now passes, and the whole chain (tables -> answers -> candidates -> this file -> holdout) was re-run with",
         "the SAME pre-written questions and the SAME candidate rule. Run 1 files are kept in scratchpad/hunt/h42/run1_buggy/.", "",
         "## Candidate definitions (unchanged from run 1)", "",
         f"- **Strict:** question BH q < 0.05 AND best option exit net > 0 AND that option test BH q < 0.05 over all {len(X):,} "
         f"question x exit option tests. **Result: {int(A.candidate_strict.sum())} strict candidates** (lowest option q = {X.q_net.min():.2f}).",
         "- **Loose (run in the holdout):** question BH q < 0.05 AND same-sign effect in both halves of the pre-holdout period AND "
         "best-of-38-exit net > 0 per trade after app charges + h24 real spread AND raw one-sided p < 0.05 AND better than a "
         f"same-index / same-minute / same-side random entry. **{nb} rules.** Their exit was picked as the best of 38 on "
         "pre-holdout data, so their pre-holdout numbers are optimistic by construction.", ""]
if nb:
    lines += ["## Rules (1-ITM nearest expiry, bought at the open after the decision bar; one position at a time per rule; 1 lot at today's lot)", "",
              "| id | index | condition | option | pre-registered exit | pre trades | pre Rs/day net | gross | per trade net | p vs random (pre) |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rules:
        x = R.loc[r["id"]]
        lines.append(f"| {r['id']} | {r['und']} | {r['question']} [`{r['table']}: {r['cond']}`{'; universe `' + r['universe'] + '`' if r['universe'] else ''}; entry `{r['entry']}`] "
                     f"| {r['side_used']} | {r['exit']} | {int(x.n)} | {x.rs_day_net:.0f} | {x.rs_day_gross:.0f} | {x.per_trade_net:.0f} | {x.p_vs_random:.3f} |")
lines += ["", "Exit codes: TxSyHz = +x premium-point target on the option HIGH, -y stop on the option LOW (stop first if both in one",
          "minute), time stop z minutes; liq = Liquidity-arm exit (-15% resting stop, out after 20 min unless the premium is >= +5%,",
          "square-off 15:10); time = the question's own horizon (15:10 for whole-day questions). Gap-throughs fill at the bar open.",
          "Fills: app +-5 bps (stops 10 bps) plus the h24 real half-spread on both sides (NIFTY/BN 0.16%, MIDCP 0.21%, FIN 0.42%,",
          "SENSEX 0.20%); app charges (STT 0.15%). Lots: NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20.", "",
          "## Holdout protocol (`python3 -I research/hunt/h42/holdout.py hold`)", "",
          "- Period 2025-10-01 .. latest. Same code, thresholds, side and exit. No re-selection.",
          "- All 38 exits reported for information; the headline is the pre-registered exit.",
          "- Random baseline: each holdout trade vs a random holdout day of the same index, same minute, same side and exit (B = 2000).",
          f"- **PASS** only if holdout net Rs/day > 0 at the pre-registered exit AND p vs random < 0.05/{max(nb, 1)} (Bonferroni)."]
open(os.path.join(HERE, "PREREG.md"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
