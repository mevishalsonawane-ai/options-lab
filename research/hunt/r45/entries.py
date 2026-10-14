"""Writes research/experiments/entries/r45-*.yaml (the reproduction + every pre-registered variant, failures included).

    python3 research/hunt/r45/entries.py
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
SIG = "SIGNAL SOURCE: the option's own price chart (not the default kind of rule; only Boss can switch it on for paper)."
WHAT = {
    "T0": "their rule as reproduced (rolling ATM call, WEEK + MONTH pooled, band across sessions, Rs 95, lot 75)",
    "P1a-fixed-contract": "T0 with each trade priced on ONE contract (the strike ATM at the entry bar)",
    "P1b-0915-contract": "T0 with signals and trades on the 09:15 ATM strike held all session",
    "P2-gap-fills": "T0 with stop/lock gap fills at the open and locks armed from the previous bar",
    "P3a-15min": "T0 with the stated 15-minute hold (time exit at the close of bar e+2)",
    "P3b-20min-checked": "T0 with bar e+3 checked for stop/lock",
    "P4-week-only": "T0 on the WEEK series only (max 2 trades a day really holds)",
    "P5-costs-slip0": "T0 with the research cost model (dated charges), no slippage",
    "P5-costs-slip0.5": "T0 with dated charges and 0.5% slippage each side",
    "P5-costs-slip1": "T0 with dated charges and 1% slippage each side",
    "P5-hist-lots": "T0 with historical NIFTY lots (50 / 25 / 75 / 65)",
    "P6-real-expiry": "T0 skipping real expiry days (lake time-value method) instead of 'last 5 closes < 1.0'",
    "F-a": "all fixes, fixed contract at entry (WEEK, P2, 15 min, dated charges + 0.5% slippage, real expiry, lot 75)",
    "F-b": "all fixes, the 09:15 contract for signals and trades",
    "F-a-slip1": "F-a at 1% slippage", "F-b-slip1": "F-b at 1% slippage",
    "F-a-hist-lots": "F-a with historical lots", "F-b-hist-lots": "F-b with historical lots",
    "BN-T0": "T0 on BANKNIFTY (lot 75 as their code)", "BN-F-a": "F-a on BANKNIFTY at its real lots",
}
BASE = dict(family="outside-strategies", date="2026-10-14", doc="research/HUNT_R45_PREMIUM_SCALP_AUDIT.md",
            data_version="Drive lake gdrive:IraAlgo-data-lake/options_rolling_1m, opt_{NIFTY,BANKNIFTY}_{WEEK,MONTH}_2021-2026 "
                         "(1-minute rolling ATM+-10, Aug 2021 - 13 Oct 2026)",
            period={"design": "sessions Aug 2021 - 13 Apr 2026 (each year its own out-of-sample block; nothing fitted)",
                    "holdout": "27 Apr - 13 Oct 2026, read once (14-26 Apr embargo)"},
            prereg="research/hunt/r45/PREREG.md", settings="research/hunt/r45/r45cfg.py")


def _yrs(m):
    return ", ".join(f"{y} {v['npt']:+.0f} (n {v['n']})" for y, v in m["years"].items())


def main():
    d = json.loads((HERE / "results" / "design.json").read_text())
    h = json.loads((HERE / "results" / "holdout.json").read_text())
    rp = json.loads((HERE / "results" / "repro.json").read_text())
    n = 0
    for name, x in d["variants"].items():
        m, hm = x["m"], h["variants"][name]["m"]
        s = (f"Design {m['trades']} trades, win {m['win']}%, Rs {m['npt']:+.1f}/trade, net Rs {m['net']:+,}, PF {m['pf']}, "
             f"green months {m['green']}, max DD Rs {m['max_dd']:,}, worst month Rs {m['worst_month']:,}; by year {_yrs(m)}; "
             f"random-entry twins Rs {m['twin_mean_npt']:+.1f}/trade, p {m['p']}, BH q {x['q']} (family {d['bh_family']}). "
             f"Holdout {hm['trades']} trades, Rs {hm['npt']:+.1f}/trade, PF {hm['pf']}. The rolling ATM strike moved during "
             f"{m['roll_change_pct']}% of the trades.")
        if name in d["gates"]:
            g = h["gates"][name]
            s += f" Gates: G1 {g['G1_design_npt_pos']}, G2 {g['G2_years_pos']}, G3 q {g['G3_q']}, G4 {g['G4_holdout_npt']}, G5 {g['G5_slip1_pos']} -> " \
                 + ("SURVIVES." if g["survives"] else "FAILS.")
        single = re.match(r"P\d", name) is not None
        e = dict(id=f"r45-{name.lower().replace('.', '')}", title=f"Premium scalp audit {name}: {WHAT[name]}",
                 hypothesis="The claimed NIFTY premium mean-reversion scalp (+Rs 174/trade, 3,726 trades) survives this "
                            "pre-registered fix." if name != "T0" else "Their rule, reproduced on the lake, makes money.",
                 instruments=["BANKNIFTY" if name.startswith("BN") else "NIFTY"],
                 features=["5-minute ATM call premium", "Bollinger(20, 2) lower band"], model_or_rule=WHAT[name],
                 variants_tried=None if single else 1, results=dict(summary=s, costs="Rs 95/trade (T0) or research/obuy Costs('dated') with 0 / 0.5 / 1% slippage a side, as named"),
                 holdout_read=True, verdict="not-a-test" if single else "rejected",
                 notes=SIG + (" A measured fix (effect vs T0), not a strategy." if single else ""), **BASE)
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    b = rp["runs"][rp["best"]]["m"]
    s = (f"12 pre-registered combinations (WEEK / MONTH / both x band per session / across sessions x population / sample SD) "
         f"on design sessions. Closest: {rp['best']}: {b['trades']} trades ({b['per_day']} a trade day; claim scaled to the design "
         f"share {rp['claimed_trades_scaled']}), Rs {b['npt']:+.1f}/trade vs the claimed +174, win {b['win']}% vs 53.4%, PF {b['pf']} vs 1.45. "
         f"Their code was not attached; reproduced from the rule text. The positive result is reproduced in kind (larger, not smaller).")
    e = dict(id="r45-reproduction", title="Premium scalp audit: reproducing the claim on the lake (step 0)",
             hypothesis="Their numbers come from the lake's rolling ATM call series.", instruments=["NIFTY"],
             features=["5-minute ATM call premium"], model_or_rule="their rule, 12 series / band combinations", variants_tried=None,
             results=dict(summary=s, costs="Rs 95/trade, lot 75 (theirs)"), holdout_read=False, verdict="not-a-test", notes=SIG, **BASE)
    (ENT / "r45-reproduction.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"wrote {n + 1}")


if __name__ == "__main__":
    main()
