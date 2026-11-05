"""Writes research/experiments/entries/r150-b-*.yaml (Amendment B) from results/b_*.json."""
import json
from pathlib import Path

import yaml

R = Path(__file__).resolve().parent / "results"
ENT = Path(__file__).resolve().parents[2] / "experiments" / "entries"
d, f, u, dec = (json.loads((R / n).read_text()) for n in ("b_design.json", "b_final.json", "b_unseen.json", "b_decision.json"))
for t, v in dec.items():
    s = (f"design {d[t]['design']['trades']} trades net Rs {d[t]['design']['net']:,} win {d[t]['design']['win']:.3f} (today {d['today']['design']['trades']} / "
         f"Rs {d['today']['design']['net']:,}); final (2nd look) net Rs {f[t]['final']['net']:,} vs Rs {f['today']['final']['net']:,}, twin p {f[t]['twin']['p']:.3f}; "
         f"unseen {u[t]['unseen']['trades']} trades net Rs {u[t]['unseen']['net']:,} vs Rs {u['today']['unseen']['net']:,}, losing days Rs {u[t]['unseen']['loss_sum']:,} "
         f"vs Rs {u['today']['unseen']['loss_sum']:,}, twin p {v['p']:.3f}, BH q {v['q']:.3f}. Fails: {'; '.join(v['fails'])}")
    e = {"id": f"r150-b-{t.lower()}", "title": f"R150 B R51 Donchian + 5-min S/R levels {t}: {v['rule']}", "family": "patterns-indicators",
         "date": "2026-11-05", "doc": "research/HUNT_R150_SR_LEVELS.md", "hypothesis": f"{v['rule']} makes R51 lose less (R140 gates).",
         "data_version": "Drive lake copied 5 Nov 2026; R51 clean stores + R143's old store (Dec 2022 - Dec 2024)",
         "period": {"design": "20 Nov 2024 - 31 Mar 2026", "holdout": "1 Dec 2022 - 19 Nov 2024 (unseen, read once 5 Nov 2026); Apr - Oct 2026 second look"},
         "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
         "features": ["signal source: the index (5-min Donchian 20 + Supertrend); S/R levels from 5-min index pivots"],
         "model_or_rule": v["rule"], "settings": "research/hunt/r150_sr_levels/study_b.py", "prereg": "research/hunt/r150_sr_levels/PREREG.md",
         "variants_tried": 1, "results": {"summary": s, "costs": "R101 / R106 engine: app fills, dated charges (as R140 / R143)"},
         "holdout_read": True, "verdict": "rejected",
         "notes": ("Strong lead: better than random skips in the unseen years (p < 0.001) and Apr - Oct 2026 (p 0.011); fails only because R51 "
                   "itself lost money in the unseen years. Paper only as Boss's exception; shadow log suggested.") if t == "W20" else "Failed the gates."}
    (ENT / f"r150-b-{t.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
