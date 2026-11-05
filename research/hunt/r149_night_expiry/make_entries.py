import json, yaml
from pathlib import Path
R=Path("research/hunt/r149_night_expiry/results/results.json"); r=json.loads(R.read_text())
txt={"E1":"hold the expiring option into expiry morning","E2":"monthly option the night before a weekly expiry","E3":"monthly option on a weekly expiry day"}
for v in ("E1","E2","E3"):
    d,c=r["design"][v]["pooled"],r["check"][v]["pooled"]
    s=(f"design {d['trades']} trades, Rs {d['net_per_trade']:.0f} a trade (stress {d['stress_net_per_trade']:.0f}), by year {d['by_year']}, "
       f"twin p {d['twin_p']:.3f}, BH q {d['bh_q']:.3f}; check {c['trades']} trades, Rs {c['net_per_trade']:.0f} a trade (stress "
       f"{c['stress_net_per_trade']:.0f}), twin p {c['twin_p']:.3f}. Pass {r['design'][v]['pass']}.")
    e={"id":f"r149-{v.lower()}","title":f"R149 Night on expiry nights {v}: {txt[v]}","family":"overnight","date":"2026-11-05",
       "doc":"research/HUNT_R149_NIGHT_EXPIRY.md","hypothesis":f"Night's signal on its skipped expiry nights pays when traded as: {txt[v]}.",
       "data_version":"Drive lake copied 5 Nov 2026 (WEEK + MONTH option files); to 16 Oct 2026",
       "period":{"design":"2022-2024","holdout":"Jan 2025 - Oct 2026 (a second look; R147's holdout)"},
       "instruments":["NIFTY","BANKNIFTY","FINNIFTY","SENSEX"],"features":["signal source: the index (Night's rule); contract choice by the expiry calendar"],
       "model_or_rule":txt[v],"settings":"research/hunt/r149_night_expiry/study.py","prereg":"research/hunt/r149_night_expiry/PREREG.md",
       "variants_tried":1,"results":{"summary":s,"costs":"app fills, app charges (BSE for SENSEX), h28 half-spreads; stress 1.5x"},
       "holdout_read":True,"verdict":"rejected"}
    Path(f"research/experiments/entries/r149-{v.lower()}.yaml").write_text(yaml.safe_dump(e,sort_keys=False,width=120))
