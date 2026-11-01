"""Run from the repo root: python research/hunt/r144b_loss_orb_liq/diag/solo_diag.py {unseen,design,final}.
Descriptive diagnostics for Solo 2.0 (not a filter test): model picks vs random same-day picks, setups unfiltered."""
import sys, json, collections
import numpy as np
from datetime import date
sys.path.insert(0, "research/hunt/r144b_loss_orb_liq")
import r144b as R
per = sys.argv[1]
lo, hi = R.PERIODS[per]
K, _, Ev, St, Sy = R._r57(per == "unseen")
model = json.loads((R.HUNT / "r57" / "results" / "models" / "A2.json").read_text())
rows = [r for r in Sy.a_rows(St.candidates(lo, hi)) if r["setup"] != "C4"]
sc = np.array([Ev.score(model, r["f"]) for r in rows])
thr = model["threshold"]
picks = Sy.select_a(rows, sc, np.full(len(rows), thr), "logit")
pk = sum(t["net"] for t in picks)
byday = collections.defaultdict(list)
for i, r in enumerate(rows):
    byday[r["day"]].append(i)
pdays = [p["day"] for p in picks]
rng = np.random.default_rng(57)
tw = np.array([sum(rows[rng.choice(byday[d])]["net"] for d in pdays) for _ in range(2000)])
print(per, "candidates", len(rows), "model picks", len(picks), "net", round(pk), "win", round(np.mean([t["net"] > 0 for t in picks]), 3))
print("  random same-day pick: mean net", round(tw.mean()), "p(random >= model)", round((1 + (tw >= pk).sum()) / 2001, 3))
# score above vs below threshold, all candidates (first-per-day ignored)
hiS = [r["net"] for r, s in zip(rows, sc) if s >= thr]; loS = [r["net"] for r, s in zip(rows, sc) if s < thr]
print("  all candidates scoring >= thr:", len(hiS), "avg", round(np.mean(hiS)), "| below:", len(loS), "avg", round(np.mean(loS)))
for s in ("S1", "S2", "S3", "S4"):
    v = [r["net"] for r in rows if r["setup"] == s]
    if v: print("  setup", s, "unfiltered", len(v), "win", round(np.mean(np.array(v) > 0), 3), "avg", round(np.mean(v)), "net", round(sum(v)))
