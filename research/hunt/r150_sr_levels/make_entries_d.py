"""Writes research/experiments/entries/r150-d-*.yaml (Amendment D) from results/d_*.json."""
import json
from pathlib import Path

import yaml

R = Path(__file__).resolve().parent / "results"
ENT = Path(__file__).resolve().parents[2] / "experiments" / "entries"
d, f, u, dec = (json.loads((R / n).read_text()) for n in ("d_design.json", "d_final.json", "d_unseen.json", "d_decision.json"))
RULES = {"D1": "previous day's floor pivots (P, R1, S1, R2, S2) as extra walls on top of R51 + W20",
         "D2": "skip unless the 5-min close is on the trade's side of the 20-bar EMA, on top of R51 + W20",
         "D3": "skip a call below the near future's session VWAP (put above), on top of R51 + W20 (52 sessions, descriptive)",
         "D4": "previous session's volume-profile POC / value-area edges as extra walls, on top of R51 + W20 (52 sessions, descriptive)"}
for t, rule in RULES.items():
    if t in dec:
        v = dec[t]
        s = (f"design net Rs {d[t]['design']['net']:,} vs base Rs {d['base_r51_w20']['design']['net']:,}; Apr - Oct 2026 net Rs {f[t]['final']['net']:,} vs "
             f"Rs {f['base_r51_w20']['final']['net']:,} (twin p {f[t]['twin']['p']:.3f}); Dec 2022 - Nov 2024 net Rs {u[t]['unseen']['net']:,} vs "
             f"Rs {u['base_r51_w20']['unseen']['net']:,}, twin p {v['p']:.3f}, BH q {v['q']:.3f}. Fails: {'; '.join(v['fails'])}")
        ho, verdict = True, "rejected"
    else:
        s = (f"descriptive on {f[t]['sessions_with_futures']} sessions: R51 + W20 {f[t]['base']['trades']} trades Rs {f[t]['base']['net']:,} -> "
             f"{f[t]['layer']['trades']} trades Rs {f[t]['layer']['net']:,}")
        ho, verdict = True, "rejected"
    e = {"id": f"r150-d-{t.lower()}", "title": f"R150 D on top of R51 + W20 {t}: {rule}", "family": "patterns-indicators",
         "date": "2026-11-05", "doc": "research/HUNT_R150_SR_LEVELS.md", "hypothesis": f"{rule} makes R51 + W20 lose less.",
         "data_version": "Drive lake copied 5 Nov 2026; R51 clean stores + R143's old store; fut_1m_live_index (29 Jul - 19 Oct 2026)",
         "period": {"design": "20 Nov 2024 - 31 Mar 2026", "holdout": "Dec 2022 - Nov 2024 (a second look) and Apr - Oct 2026 (a second look)"},
         "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
         "features": ["signal source: the index (R51's break); pivots / EMA from index prices; VWAP / volume profile from index futures volume"],
         "model_or_rule": rule, "settings": "research/hunt/r150_sr_levels/study_d.py", "prereg": "research/hunt/r150_sr_levels/PREREG.md",
         "variants_tried": 1, "results": {"summary": s, "costs": "R101 / R106 engine: app fills, dated charges (as R140 / R143)"},
         "holdout_read": ho, "verdict": verdict}
    (ENT / f"r150-d-{t.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
