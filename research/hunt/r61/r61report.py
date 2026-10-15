"""R61: each concept's measured worth, from results/design.json and results/final.json, in plain English.

    python research/hunt/r61/r61report.py      # writes results/concept_results.json, then CONCEPTS.md / concepts.json

"Noise" = every measured change of walk-forward AUC (removing the concept's inputs from the all-inputs logistic V2, or
adding one input alone to A2's 29) is smaller than 0.01 in both the design months and the final test. An AUC change
that small cannot be told apart from chance on ~5,800 design / ~2,500 final candidate rows.
"""
from __future__ import annotations

import json

import r61cfg as K
import r61concepts as CC

NOISE = 0.01
GROUP_OF = {   # concept id -> ablation group(s) and single inputs measured
    "oi": (["oi_level_change"], ["oi_near_log", "oi_near_chg_pct", "writer_tilt30"]),
    "buildup": (["buildup"], ["buildup"]),
    "pcr": (["pcr"], ["pcr_oi_chg30", "pcr_vol", "pcr_rank5", "pcr_rank20"]),
    "max_pain": (["max_pain"], ["maxpain_dist_pct"]),
    "oi_walls": (["oi_walls"], ["wall_ahead_pct", "wall_behind_pct"]),
    "iv": (["iv_level_rank"], ["iv_chg_day"]),
    "iv_rank": (["iv_level_rank"], ["iv_rank60"]),
    "skew": (["skew_smile_term"], ["smile_curv"]),
    "term": (["skew_smile_term"], ["term_slope"]),
    "vix": (["vix_rank"], ["vix_rank60"]),
    "delta": (["greeks"], ["delta_abs"]),
    "gamma": (["greeks"], ["gamma_1pct"]),
    "theta": (["greeks"], ["theta_pct_day"]),
    "vega": (["greeks"], ["vega_pct"]),
    "gex": (["gex_flip"], ["gex_norm", "gex_sign", "flip_dist_pct"]),
    "value_area": (["value_area"], ["va_pos"]),
    "participants": (["participants"], ["fii_fut_long_share", "fii_fut_long_chg"]),
    "basis": (["basis"], ["synth_basis_bps"]),
    "events": (["events", "other_expiry"], ["event_rbi_budget", "post_us_event", "other_expiry_today"]),
}
IN_A2 = {
    "vwap": "Not a new R61 input (A2 already has the typical-price proxy `dist_tpavg_pct`); R61 did not test it again.",
    "atr": "Not a new R61 input (A2 already has `atr14d_pct`); R61 did not test it again.",
    "orb": "Not a new R61 input (A2 has `or30_state`; S4 is a candidate setup). In R61's final test the 15-minute ORB "
           "setup was almost never taken (0-2 trades per variant).",
    "regime": "Not a new R61 input (A2 already has `er_day`, `rv_30m`, `day_range_vs_adr10`); not tested again.",
    "dealers": "No R61 input: positioning is not observable intraday; participant OI (its proxy) is measured under "
               "'FII / DII flows and participant-wise OI'.",
    "rollover": "No R61 input: not available on the phone.",
    "news": "R61-N (pre-registered in NEWS_PREREG.md): see the R61-N row under 'What our research found'. Verdict: no help "
            "beyond Solo 2.0 out of sample.",
}
# R61b (15 Oct 2026) concepts: not R61 inputs; research/hunt/r61b/r61breport.py writes their measured results to
# r61b/results/concept_results.json, which replaces these placeholders when it exists
R61B = ("fake_breakout", "traps", "stop_hunt", "absorption", "delta_cvd", "ofi", "depth_imbalance", "volume_profile",
        "order_blocks", "fvg", "bos_choch")
R61B_RESULTS = K.HERE.parent / "r61b" / "results" / "concept_results.json"
LATER = {c: "Not an R61 input; added by R61b (research/HUNT_R61B_TRAPS_ORDERFLOW.md)." for c in R61B}


def _f(x):
    return "n/a" if x is None else f"{x:+.4f}"


def _rs(x):
    return f"Rs {x:+,.0f}"


def concept_results(design: dict, final: dict) -> dict:
    da, fa = design["ablation"], final["ablation"]
    out = {}
    for cid, (groups, feats) in GROUP_OF.items():
        parts, deltas = [], []
        for g in groups:
            d, f = da["drop"][g], fa["drop"][g]
            deltas += [d["d_auc"], f["d_auc"]]
            parts.append(f"removing the '{g.replace('_', ' ')}' inputs from the all-inputs model changed walk-forward "
                         f"AUC by {_f(d['d_auc'])} (design) / {_f(f['d_auc'])} (final) and net by {_rs(d['d_net'])} / "
                         f"{_rs(f['d_net'])}")
        adds = []
        for n in feats:
            d, f = da["add"][n], fa["add"][n]
            deltas += [d["d_auc"], f["d_auc"]]
            adds.append(f"`{n}` {_f(d['d_auc'])} / {_f(f['d_auc'])}")
        noise = all(x is None or abs(x) < NOISE for x in deltas)
        verdict = ("Verdict: no measurable help out of sample (every AUC change under 0.01; the net changes go both "
                   "ways and are within what one or two trades swing)." if noise else
                   "Verdict: a change of 0.01 AUC or more somewhere; see HUNT_R61_SOLO_3.md before trusting it.")
        out[cid] = (f"R61 (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on {design['rows']:,} candidate rows, "
                    f"final 1 Apr - 14 Oct 2026, not fresh): " + "; ".join(parts) +
                    f". Each input added alone to A2's 29 changed AUC (design / final): " + ", ".join(adds) + ". " +
                    verdict)
    out.update(IN_A2)
    out.update(LATER)
    if R61B_RESULTS.exists():
        out.update(json.loads(R61B_RESULTS.read_text()))
    return out


def main():
    design = json.loads((K.RESULTS / "design.json").read_text())
    final = json.loads((K.RESULTS / "final.json").read_text())
    res = concept_results(design, final)
    (K.RESULTS / "concept_results.json").write_text(json.dumps(res, indent=1) + "\n")
    CC.write(res)
    return res


if __name__ == "__main__":
    for k, v in main().items():
        print(k, "|", v[-120:])
