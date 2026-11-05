import json, yaml
from pathlib import Path
R=Path("research/hunt/r150_sr_levels/results"); d=json.loads((R/"design.json").read_text()); f=json.loads((R/"final.json").read_text()); dec=json.loads((R/"decision.json").read_text())
for t,v in dec.items():
    s=(f"design {d[t]['design']['trades']} trades net Rs {d[t]['design']['net']:,} (today {d['today']['design']['trades']} / Rs {d['today']['design']['net']:,}); "
       f"wf net Rs {d[t]['wf']['net']:,}; final {f[t]['final']['trades']} trades net Rs {f[t]['final']['net']:,}, losing days Rs {f[t]['final']['loss_sum']:,} "
       f"(today Rs {f['today']['final']['loss_sum']:,}), max DD Rs {f[t]['final']['max_dd']:,} (today Rs {f['today']['final']['max_dd']:,}); twin p {v['p']:.3f}, BH q {v['q']:.3f}. "
       f"Fails: {'; '.join(v['fails'][:4])}")
    e={"id":f"r150-{t.lower()}","title":f"R150 R51 Donchian + 3-month S/R levels {t}: {v['rule']}","family":"patterns-indicators","date":"2026-11-05",
       "doc":"research/HUNT_R150_SR_LEVELS.md","hypothesis":f"{v['rule']} makes R51 lose less (R140 gates).",
       "data_version":"Drive lake copied 5 Nov 2026; R51 clean stores (Amendment A), to 13 Oct 2026",
       "period":{"design":"20 Nov 2024 - 31 Mar 2026","holdout":"1 Apr - 13 Oct 2026, read once 5 Nov 2026"},
       "instruments":["NIFTY","BANKNIFTY","FINNIFTY","MIDCPNIFTY","SENSEX"],
       "features":["signal source: the index (5-min Donchian 20 + Supertrend); S/R levels from 15-min index pivots over 63 sessions"],
       "model_or_rule":v["rule"],"settings":"research/hunt/r150_sr_levels/study.py","prereg":"research/hunt/r150_sr_levels/PREREG.md",
       "variants_tried":1,"results":{"summary":s,"costs":"R101 / R106 engine: app fills, dated charges, R51's clean stores (as R140)"},
       "holdout_read":True,"verdict":"rejected"}
    Path(f"research/experiments/entries/r150-{t.lower()}.yaml").write_text(yaml.safe_dump(e,sort_keys=False,width=120))
