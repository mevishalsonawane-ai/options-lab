"""R167 experiment entries (CLAUDE.md: log every idea, failures included): one YAML per test in
research/experiments/entries/, plus one for Amendment A2's giveback count.

    python research/hunt/r167_donchian_smart_exits/r167entries.py

Before the study has run (no results/decision.json), every entry is `proposed`: written down, never run, not counted
as a test (variants_tried and holdout_read null). After `r167study.py decide`, the same files get the real verdict
(`rejected`, or `shadow` / `paper-only` for a pass: T = live-eligible after a shadow phase, F = paper / research only),
the walk-forward and final numbers and the twin p / BH q. 12 Nov 2026.
"""
from __future__ import annotations

import json

import yaml

import r167cfg as K

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r167_donchian_smart_exits/results/report.md"
PREREG = "research/hunt/r167_donchian_smart_exits/PREREG.md"
FEATURES = {
    "SR": "R150 5-minute 3+-touch levels (L20), 5-minute swings, index ATR5",
    "VOLX": "index ATR5 (Wilder 14 on 5-minute bars), index highs / lows since the fill",
    "VOLP": "the held option's own 1-minute ATR(14) and closes (a premium stop)",
    "FLOW": "the held option's 1-minute volume and OI, index 1-minute bars",
    "MOM": "Supertrend (10, 3) on 1-minute index bars",
    "CTX": "India VIX vs its training median, the day's efficiency ratio; the SR / VOLX / VOLP / FLOW / MOM flags",
    "ML": "16 in-trade features (premium path, index move in ATR, level distances, ROC, Supertrend, volume, OI, VIX, time)",
    "G1": "the premium whose sale nets +Rs 1,500 / Rs 0 (from the real fill and charges)",
    "G2": "the premiums netting +Rs 1,500 / +Rs 500 and a Rs 1,000 trail under the peak",
    "G3": "the premium netting +Rs 1,500, then a 3-minute index-low trail",
}


def _id(arm, rule, ver, lots):
    return f"r167-{arm.lower()}-{rule.lower()}-{ver.lower()}-{lots}lot"


def entry(arm, rule, ver, lots, res: dict | None) -> dict:
    lab = K.RULES[rule]["label"]
    vlab = K.VERSION_LABEL[ver]
    e = dict(
        id=_id(arm, rule, ver, lots),
        title=f"R167 {rule}-{ver} on {K.ARM_NAME[arm]}, {lots} lot{'s' if lots > 1 else ''}: {lab} ({vlab})",
        family="trade-management", date="2026-11-12", doc=DOC,
        hypothesis=(f"Boss (12 Nov): make the stop and target follow volume, support / resistance and other data. {lab}, "
                    f"re-evaluated every minute ({vlab}), raises the win rate >= 5 points with net not lower than today's "
                    "exits in walk-forward and final, net > 0 in every period, beats random-exit twins (BH q <= 0.10, "
                    f"m = {K.M_TOTAL}), >= 40 final trades."),
        data_version="the Drive / Dhan lake as R152 (R51 Amendment A clean stores) plus the lake's option OI and India VIX",
        period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=[f"signal source: the index (the arm's unchanged Donchian entries); exit inputs: {FEATURES[rule]}"],
        model_or_rule=f"{K.ARM_NAME[arm]} with {rule}-{ver}: {lab}; {lots} lot(s)",
        settings="research/hunt/r167_donchian_smart_exits/PREREG.md section 3 / Amendment A; r167lib.py; r167cfg.py",
        prereg=PREREG,
        variants_tried=None, holdout_read=None, verdict="proposed",
        results=dict(summary="Not run: the data lake was not in the session's container on 12 Nov 2026 (Dhan token "
                             "expired, no Drive access, no release). Code and tests are frozen; runs once the lake is in place.",
                     costs="dated Zerodha charges on the whole quantity, R17 fills (to be applied)"),
        notes=("Tighten-only: live-eligible only after a shadow phase behind an off-by-default switch." if ver == "T"
               else "Free version (may widen a stop or extend a trade): paper / research only."),
    )
    if res is not None:
        g, r = res["gate"], res["stats"]
        e.update(variants_tried=1, holdout_read=True,
                 period=dict(design=e["period"]["design"], holdout="2026-04-01..2026-10-13 read once by R167 "
                                                                    "(read by many earlier studies)"),
                 verdict=("shadow" if ver == "T" else "paper-only") if g["passed"] else "rejected",
                 results=dict(summary=r, costs="dated Zerodha charges on the whole quantity, R17 fills (stop sales -10 bps "
                                               "and 2x the half-spread, gaps at the open)"))
    return e


def _summary(t: dict, d: dict, f: dict, lots: int, key: str) -> str:
    def per(s, b):
        return (f"{s['trades']} trades, win {s['win']:.1%} (today {b['win']:.1%}), net Rs {s['net']:,} "
                f"(today Rs {b['net']:,}), worst day Rs {s['worst_day']:,}, max DD Rs {s['max_dd']:,}")
    wf, wb = d[key]["wf"], d[f"B0@{lots}"]["wf"]
    fi, fb = f[key]["final"], f[f"B0@{lots}"]["final"]
    return (f"Walk-forward: {per(wf, wb)}. Final: {per(fi, fb)}. Twin p {t['p']:.4g} (net {t['p_net']:.4g}, win "
            f"{t['p_win']:.4g}), BH q {t['q']:.3g} (m {K.M_TOTAL}). "
            + ("Passed." if t["passed"] else "Fails: " + "; ".join(t["fails"]) + "."))


def write() -> int:
    dec_p = K.RESULTS / "decision.json"
    res = {}
    if dec_p.exists():
        dec = json.loads(dec_p.read_text())
        design = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
        final = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
        for t in dec["tests"]:
            key = f"{t['rule']}-{t['version']}@{t['lots']}"
            res[(t["arm"], t["rule"], t["version"], t["lots"])] = dict(
                gate=t, stats=_summary(t, design[t["arm"]], final[t["arm"]], t["lots"], key))
    n = 0
    for arm, rule, ver, lots in K.TESTS:
        e = entry(arm, rule, ver, lots, res.get((arm, rule, ver, lots)))
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        n += 1
    a2 = dict(id="r167-a2-giveback-count", title="R167 Amendment A2: how often +Rs 1,500 open profit ends at a loss "
                                                 "(Donchian arms, today's exits, 1-3 lots)",
              family="trade-management", date="2026-11-12", doc=DOC,
              hypothesis="A measurement, not a test (Boss, 12 Nov: profit reaches Rs 1.5k-2.5k and ends at a Rs 2k-3k loss).",
              data_version="as the R167 tests", period=dict(design="2024-11-20..2026-03-31"),
              instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
              features=["B0 trades: best open profit (sale at the best high, net of charges) and final net"],
              model_or_rule="count of trades reaching >= +Rs 1,500 that end <= Rs 0 and <= -Rs 2,000",
              settings="PREREG.md Amendment A2; r167lib.giveback", prereg=PREREG, variants_tried=None,
              holdout_read=None, verdict="proposed" if not res else "not-a-test",
              results=dict(summary="Not run yet (data unavailable on 12 Nov 2026)." if not res else
                           "See results/report.md (giveback table).", costs="dated Zerodha charges, R17 fills"))
    (ENTRIES / f"{a2['id']}.yaml").write_text(yaml.safe_dump(a2, sort_keys=False, width=120, allow_unicode=True))
    return n + 1


if __name__ == "__main__":
    print(write(), "entries written")
