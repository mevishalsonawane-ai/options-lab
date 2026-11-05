"""Writes research/experiments/entries/r150-c-*.yaml (Amendment C, R72) from results/c_*.json."""
import json
from pathlib import Path

import yaml

R = Path(__file__).resolve().parent / "results"
ENT = Path(__file__).resolve().parents[2] / "experiments" / "entries"
d, f, u, dec = (json.loads((R / n).read_text()) for n in ("c_design.json", "c_final.json", "c_unseen.json", "c_decision.json"))
for t, v in dec.items():
    s = (f"design {d[t]['design']['trades']} trades net Rs {d[t]['design']['net']:,} win {d[t]['design']['win']:.3f} (today {d['today']['design']['trades']} / "
         f"Rs {d['today']['design']['net']:,} / {d['today']['design']['win']:.3f}); final (2nd look) net Rs {f[t]['final']['net']:,} vs Rs {f['today']['final']['net']:,}, "
         f"twin p {f[t]['twin']['p']:.3f}; unseen {u[t]['unseen']['trades']} trades net Rs {u[t]['unseen']['net']:,} vs Rs {u['today']['unseen']['net']:,}, "
         f"twin p {v['p']:.3f}, BH q {v['q']:.3f}. Fails: {'; '.join(v['fails'])}")
    e = {"id": f"r150-c-{t.lower()}", "title": f"R150 C R72 Breadth Donchian + 5-min S/R wall-ahead skip {t}: {v['rule']}",
         "family": "patterns-indicators", "date": "2026-11-05", "doc": "research/HUNT_R150_SR_LEVELS.md",
         "hypothesis": f"{v['rule']} makes R72 lose less (R140 gates).",
         "data_version": "Drive lake copied 5 Nov 2026; R51 clean stores + R143's old store (Dec 2022 - Dec 2024)",
         "period": {"design": "20 Nov 2024 - 31 Mar 2026", "holdout": "1 Dec 2022 - 19 Nov 2024 (unseen, read once 5 Nov 2026); Apr - Oct 2026 second look"},
         "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
         "features": ["signal source: the index (cross-index breadth Donchian); S/R levels from 5-min index pivots"],
         "model_or_rule": v["rule"], "settings": "research/hunt/r150_sr_levels/study_c.py", "prereg": "research/hunt/r150_sr_levels/PREREG.md",
         "variants_tried": 1, "results": {"summary": s, "costs": "R101 / R106 engine: app fills, dated charges (as R140 / R143)"},
         "holdout_read": True, "verdict": "rejected"}
    (ENT / f"r150-c-{t.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
