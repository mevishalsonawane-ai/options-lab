"""R61b: each new concept's measured worth, from results/design.json and results/final.json, in plain English.

    python research/hunt/r61b/r61breport.py     # writes results/concept_results.json, then R61's CONCEPTS.md /
                                                # concepts.json (via r61report, which merges this file)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r61bcfg as K  # noqa: E402

NOISE = 0.01
MEASURED = {   # concept id -> (TR groups, single inputs, skip filters)
    "fake_breakout": (["traps", "break_bar", "break_volume", "oi_at_break"],
                      ["trap_same_or", "trap_same_pd", "brk_wick", "brk_clv", "vol_brk_rel", "oi_chg15_pct", "bu15"],
                      ["F1", "F2", "F3"]),
    "traps": (["traps"], ["trap_same_or", "trap_opp_or", "trap_same_pd", "trap_opp_pd"], ["F1"]),
    "stop_hunt": (["sweeps"], ["sweep_same30", "sweep_opp30"], ["F4"]),
    "absorption": (["absorption"], ["absorb15"], []),
    "delta_cvd": (["delta_proxies"], ["delta30", "cdelta_day"], []),
    "ofi": (["optvol_imbalance"], ["optvol_imb15"], []),
}
FILTER_TEXT = {"F1": "skip after a failed same-side break", "F2": "skip a signal bar with a rejection wick of half its "
               "range or more", "F3": "skip a break on below-normal option volume", "F4": "skip on any price-only trap "
               "sign (F1, F2 or a same-side sweep)"}
NOT_INPUT = {
    "depth_imbalance": "Not measurable in R61b: the lake has no order book. The phone's TickZoneRecorder stores 5-level "
                       "depth each second only from 12 Oct 2026 (a few sessions, none uploaded or analysed yet).",
    "volume_profile": "Not an R61b input: R62 (branch claude/r62-volume-profile-smc) tests the volume-profile strategy "
                      "separately. In Solo it appears only through S3 (open outside the previous day's TPO value area).",
    "order_blocks": "Not an R61b input: R62 tests it separately.",
    "fvg": "Not an R61b input: R62 tests it separately.",
    "bos_choch": "Not an R61b input: R62 tests it separately.",
}


def _f(x):
    return "n/a" if x is None else f"{x:+.4f}"


def _rs(x):
    return f"Rs {x:+,.0f}"


def concept_results(design: dict, final: dict) -> dict:
    da, fa = design["ablation"], final["ablation"]
    dv, fv = design["variants"], final["variants"]
    v1d, v1f = dv["V1"]["wf"]["net"], fv["V1"]["test"]["net"]
    out = {}
    for cid, (groups, feats, filters) in MEASURED.items():
        deltas, parts = [], []
        for g in groups:
            d, f = da["drop"][g], fa["drop"][g]
            deltas += [d["d_auc"], f["d_auc"]]
            parts.append(f"removing the '{g.replace('_', ' ')}' inputs from M1 (A2 + all 15 R61b inputs) changed "
                         f"walk-forward AUC by {_f(d['d_auc'])} (design) / {_f(f['d_auc'])} (final) and net by "
                         f"{_rs(d['d_net'])} / {_rs(f['d_net'])}")
        adds = []
        for n in feats:
            d, f = da["add"][n], fa["add"][n]
            deltas += [d["d_auc"], f["d_auc"]]
            adds.append(f"`{n}` {_f(d['d_auc'])} / {_f(f['d_auc'])}")
        fl = []
        for r in filters:
            d, f = dv[r], fv[r]
            fl.append(f"{r} ({FILTER_TEXT[r]}): design {d['wf']['trades']} trades {_rs(d['wf']['net'])} vs V1 "
                      f"{_rs(v1d)}; final {f['test']['trades']} trades {_rs(f['test']['net'])} vs V1 {_rs(v1f)}, "
                      f"gates {f['verdict'].split(':')[0]}")
        noise = all(x is None or abs(x) < NOISE for x in deltas)
        better_final = [r for r in filters if fv[r]["test"]["net"] > v1f]
        verdict = ("Verdict: no measurable help out of sample (every AUC change under 0.01"
                   + ("; no skip filter made more than Solo 2.0 on the final days" if filters and not better_final
                      else "") + ")." if noise and not better_final else
                   "Verdict: a change of 0.01 AUC or more, or a filter above Solo 2.0, somewhere; see "
                   "HUNT_R61B_TRAPS_ORDERFLOW.md before trusting it.")
        txt = (f"R61b (15 Oct 2026; design walk-forward Jul 2025 - Mar 2026 on {design['rows']:,} candidate rows, "
               f"final 1 Apr - 14 Oct 2026 on {final.get('final_rows', 0):,}, not fresh; signals from the index, these "
               "inputs are minute-bar PROXIES, not tick order flow): " + "; ".join(parts) +
               ". Each input added alone to A2's 29 changed AUC (design / final): " + ", ".join(adds) + ". ")
        if fl:
            txt += "Skip filters on Solo 2.0: " + "; ".join(fl) + ". "
        out[cid] = txt + verdict
    out.update(NOT_INPUT)
    return out


def main():
    design = json.loads((K.RESULTS / "design.json").read_text())
    final = json.loads((K.RESULTS / "final.json").read_text())
    res = concept_results(design, final)
    (K.RESULTS / "concept_results.json").write_text(json.dumps(res, indent=1) + "\n")
    import r61report
    r61report.main()
    return res


if __name__ == "__main__":
    for k, v in main().items():
        print(k, "|", v[-160:])
