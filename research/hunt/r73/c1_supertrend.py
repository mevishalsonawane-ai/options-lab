"""R73 labelled extra, run AFTER the final read (Boss, 16 Oct: "check r72 ... find if this super trend works on that"):
Supertrend agreement (T7) on top of R72's C1 Breadth Donchian, both exit sets, design / walk-forward / final, per index,
calls vs puts, months, random-entry and random-skip twins. Not gated, no BH; information only.

    python research/hunt/r73/c1_supertrend.py      # -> results/extra_c1_T7_full.json
"""
import sys, json
from collections import defaultdict
sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parent))
import numpy as np, r73study as X
R, S = X.R, X.S
M = S.mods(); M["C"].open_final()
out = {}
per = {"design": (R.START, R.DESIGN_END), "final": (R.FINAL_START, M["K"].END)}
for x in ("P", "Q"):
    for ph, (lo, hi) in per.items():
        c1 = X.trades("base", x, lo, hi, source="c1")
        c7 = X.trades("T7", x, lo, hi, source="c1")
        r = {}
        for nm, tr in (("C1", c1), ("C1+T7", c7)):
            st = X.stats(tr, X.months(lo, hi))
            pi, ps, pm = defaultdict(float), defaultdict(float), defaultdict(float)
            for z in tr:
                pi[z["und"]] += z["net"]; ps[z["side"]] += z["net"]; pm[str(z["day"])[:7]] += z["net"]
            d = dict(stats={k: st[k] for k in ("trades", "net", "win", "worst_day", "max_dd", "green_days", "ex_top5")},
                     index={k: round(v) for k, v in pi.items()}, side={k: round(v) for k, v in ps.items()},
                     months={k: round(v) for k, v in sorted(pm.items())})
            if ph == "design":
                d["wf_net"] = X.wf_net_of(tr)
            if ph == "final":
                tw = M["A"].donchian_twins(M["E"], M["K"], M["r51data"], M["G"], tr, X.exits(x), 500, np.random.default_rng(72))
                d["twin_p"] = X.p_of(tw, st["net"])
            r[nm] = d
        sk = X.skip_twins([z["net"] for z in c1], len(c7), 500, np.random.default_rng(72))
        r["skip_p"] = X.p_of(sk, sum(z["net"] for z in c7)); r["vs"] = X.skip_compare(c1, c7)
        out[f"{x}-{ph}"] = r
        print(x, ph, r["C1"]["stats"]["net"], r["C1+T7"]["stats"]["net"], r["skip_p"], flush=True)
(R.RESULTS / "extra_c1_T7_full.json").write_text(json.dumps(out, indent=1, default=str) + "\n")
