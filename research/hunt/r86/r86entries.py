"""Write research/experiments/entries/r86-*.yaml from results/design.json (one entry per idea, failures included).

    python -m research.hunt.r86.r86entries
"""
from __future__ import annotations

from pathlib import Path

import json
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENTRIES = ROOT / "research/experiments/entries"
DOC = "research/HUNT_R86_BIG_PLAYERS.md"
PREREG = "research/hunt/r86/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake (participants/, index_daily, index_1m, equity_1m, derived/r28_gex, "
        "fut_daily_continuous; files of 13-15 Oct 2026); CFTC Disaggregated Futures-Only history files downloaded 19 Oct 2026")
FUT = ("NSE index futures as the index + Rs 40 brokerage, dated STT on the sell leg, exchange, SEBI, stamp, GST, 0.02% "
       "slippage a side (0.04% on stops), 5.5%/yr carry (longs pay, shorts receive); 1 lot in force")
MCX = "McxCosts charges both legs, 0.03% slippage a fill, 0.10% on stops; roll-adjusted continuous future, 1 mini lot"

IDEAS = {
    "p1": dict(tests=["P1"], fam="flow-oi", title="FII index-futures long share at a 250-day extreme -> NIFTY next 5 days",
               feats=["NSE participant OI: FII long / (long + short) index futures, percentile over 250 days"],
               rule="NIFTY future 5 days, side = walk-forward Spearman sign x (pct - 0.5), trigger pct <= 0.10 or >= 0.90, stop 1.5 ATR14",
               inst=["NIFTY"], period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=FUT),
    "p2": dict(tests=["P2"], fam="flow-oi", title="Sharp 5-day shift in FII long share -> NIFTY next 5 days",
               feats=["NSE participant OI: 5-day change of FII long share, z over 250 days"],
               rule="NIFTY future 5 days when abs z >= 1.5, walk-forward sign, stop 1.5 ATR14", inst=["NIFTY"],
               period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=FUT),
    "p3": dict(tests=["P3"], fam="flow-oi", title="FII index-option stance at a 250-day extreme -> NIFTY next 5 days",
               feats=["NSE participant OI: FII (callL - callS - putL + putS) / sum, percentile over 250 days"],
               rule="NIFTY future 5 days at pct extremes, walk-forward sign, stop 1.5 ATR14", inst=["NIFTY"],
               period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=FUT),
    "p4": dict(tests=["P4"], fam="flow-oi", title="Client net index-option writing as a NIFTY next-5-day range input",
               feats=["NSE participant OI: Client (calls + puts written - bought) / sum, percentile over 250 days",
                      "baseline: log VIX, log 5-day and 1-day range"],
               rule="OLS log range forecast, walk-forward by year, Diebold-Mariano vs the VIX + recent-range baseline",
               inst=["NIFTY", "INDIAVIX"], period=("2012 - 2022 (walk-forward years 2015-2022)",), costs="none (forecast test)"),
    "p5": dict(tests=["P5"], fam="flow-oi", title="FII crowding (long share far from its median) as a NIFTY 5-day range input",
               feats=["abs(pct(FII long share) - 0.5)", "baseline: log VIX, log 5-day and 1-day range"],
               rule="OLS log range forecast, walk-forward by year, Diebold-Mariano", inst=["NIFTY", "INDIAVIX"],
               period=("2012 - 2022 (walk-forward years 2015-2022)",), costs="none (forecast test)"),
    "c1": dict(tests=["C1-GOLD", "C1-SILVER", "C1-CRUDE"], fam="mcx-crypto",
               title="CFTC managed-money positioning extremes (COT index) -> MCX gold, silver, crude next 4 weeks",
               feats=["CFTC Disaggregated managed money net / OI, percentile over 156 reports; used from report + 7 days"],
               rule="MCX mini future 20 sessions at pct <= 0.10 or >= 0.90, walk-forward sign, stop 2 ATR14",
               inst=["GOLD", "SILVER", "CRUDE"], period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=MCX),
    "c2": dict(tests=["C2-GOLD", "C2-SILVER", "C2-CRUDE"], fam="mcx-crypto",
               title="CFTC managed-money one-week change (z) -> MCX gold, silver, crude next week",
               feats=["CFTC managed money net / OI, weekly change z over 156 reports; used from report + 7 days"],
               rule="MCX mini future 5 sessions at abs z >= 1.5, walk-forward sign, stop 1.5 ATR14",
               inst=["GOLD", "SILVER", "CRUDE"], period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=MCX),
    "s1": dict(tests=["S1-NIFTY", "S1-BANKNIFTY"], fam="liquidity",
               title="Stop run at the previous day's high/low (poke beyond, close back within 10 min) -> fade",
               feats=["index 1-minute bars: previous-day high/low, a poke >= 0.03% beyond, a close back inside within 10 minutes"],
               rule="fade at the next minute, stop beyond the poke extreme + 0.02%, target 2R, 60 minutes or 15:15",
               inst=["NIFTY", "BANKNIFTY"], period=("data start - 2024 (folds 2022, 2023, 2024)",), costs=FUT),
    "s2": dict(tests=["S2-NIFTY", "S2-BANKNIFTY"], fam="liquidity",
               title="Stop run at round numbers (NIFTY 100s, BANKNIFTY 500s) -> fade",
               feats=["index 1-minute bars: round levels, failed poke as S1"], rule="as S1",
               inst=["NIFTY", "BANKNIFTY"], period=("data start - 2024 (folds 2022, 2023, 2024)",), costs=FUT),
    "s3": dict(tests=["S3-NIFTY", "S3-BANKNIFTY"], fam="liquidity",
               title="Stop run at the 09:15-09:29 opening-range edge -> fade",
               feats=["index 1-minute bars: opening-range high/low, failed poke as S1"], rule="as S1",
               inst=["NIFTY", "BANKNIFTY"], period=("data start - 2024 (folds 2022, 2023, 2024)",), costs=FUT),
    "v1": dict(tests=["V1"], fam="patterns-indicators",
               title="VWAP-algo footprint: midday fade to VWAP on high-early-volume NIFTY days",
               feats=["turnover-weighted NIFTY VWAP (59 current constituents' rupee turnover)",
                      "early volume ratio 09:15-10:14 vs 20 sessions; HIGH = top third of 250 sessions"],
               rule="12:00 stretch >= 0.25% from VWAP: fade, target VWAP, 1:1 stop, out 15:15",
               inst=["NIFTY"], period=("2019 - 2024 (folds 2022, 2023, 2024)",), costs=FUT),
    "g1": dict(tests=["G1-NIFTY", "G1-BANKNIFTY"], fam="option-structure",
               title="Dealer GEX at 09:59 as an INCREMENTAL rest-of-day range input (beyond VIX and the range so far)",
               feats=["R28 GEX near_net_cr sign and near_norm at 09:59",
                      "baseline: log VIX 09:59, log 09:15-09:59 range, log 20-session rest-of-day range, dte 0/1"],
               rule="OLS log range forecast, walk-forward, Diebold-Mariano vs baseline", inst=["NIFTY", "BANKNIFTY"],
               period=("Aug 2021 - 2023 (folds 2022, 2023)",), costs="none (forecast test)"),
    "e1": dict(tests=["E1"], fam="calendar-events", title="NIFTY futures rollover at monthly expiry -> next month's direction",
               feats=["NSE bhavcopy NIFTY futures OI: (next + far) / all on the session before expiry, z vs 12 expiries"],
               rule="NIFTY future from expiry-day open to the next monthly expiry close when abs z >= 1, walk-forward sign, stop 2 ATR14",
               inst=["NIFTY"], period=("2012 - 2022 (walk-forward years 2015-2022)",), costs=FUT),
    "e2": dict(tests=["E2-GOLD", "E2-SILVER"], fam="mcx-crypto",
               title="MCX roll window (5 sessions before a contract roll) is busier: a SIZE fact for GOLDM, SILVERM",
               feats=["roll days from the continuous future's OI jump (>= 1.5x)", "abs roll-adjusted daily return"],
               rule="mean abs return inside / outside the window vs random windows (permutation)",
               inst=["GOLD", "SILVER"], period=("2012 - 2022",), costs="none (size fact)"),
}


def fmt(k, r):
    if "ratio" in r:
        return (f"{k}: ratio {r['ratio']} (inside {r['n_inside']} / outside {r['n_outside']} sessions), random windows "
                f"{r['twin_mean']}, p {r['p']:.3f}, BH q {r['bh_q']:.3f}, without top 5 {r['ratio_wo_top5']}, "
                f"{r['years_same_side']} of {r['n_years']} years above 1")
    if "gain" in r:
        return (f"{k}: {r['n']} forecasts, MSE change {r['gain_pct_of_mse']:+.2f}% of the baseline's (positive = "
                f"better), DM t {r['dm_t']}, twin p {r['twin_p']:.3f}, p {r['p']:.3f}, BH q {r['bh_q']:.3f}")
    return (f"{k}: {r['n']} trades, net Rs {r['net']:,.0f}, win {r['win']}, without top 5 Rs {r['net_wo_top5']:,.0f}, "
            f"random twins mean Rs {r['twin_mean']:,.0f}, twin p {r['p']:.3f}, BH q {r['bh_q']:.3f}")


def main():
    res = json.loads((HERE / "results/design.json").read_text())
    for key, it in IDEAS.items():
        parts = [fmt(t, res[t]) for t in it["tests"]]
        passed = [t for t in it["tests"] if res[t]["passes"]]
        verdict_txt = "PASS" if passed else "FAIL (no test passed the design gates; final window not read)"
        e = {
            "id": f"r86-{key}", "title": f"R86 {key.upper()}: {it['title']}", "family": it["fam"], "date": "2026-10-19",
            "doc": DOC,
            "hypothesis": (f"Pre-registered ({PREREG}): {it['title']} beats random twins in walk-forward design "
                           "(net > 0 and > twins, twin p < 0.10, BH q < 0.10 over R86's 23 tests, not carried by the top 5), "
                           "then in the final window read once."),
            "data_version": DATA,
            "period": {"design": it["period"][0],
                       "holdout": "final window NOT read: no R86 test passed design (PREREG section 2)"},
            "instruments": it["inst"],
            "features": ["signal source: the underlying (index / MCX future) or positioning data about it; no option chart"]
                        + it["feats"],
            "model_or_rule": it["rule"],
            "settings": "research/hunt/r86/r86study.py, r86lib.py; PREREG.md",
            "prereg": PREREG,
            "variants_tried": len(it["tests"]),
            "results": {"summary": "Design walk-forward. " + "; ".join(parts) + f". Verdict: {verdict_txt}.",
                        "costs": it["costs"]},
            "holdout_read": False,
            "verdict": "adopted" if passed else "rejected",
            "notes": "Understanding the big players' footprints (Boss, 19 Oct). Research only; nothing changes paper or live.",
        }
        (ENTRIES / f"r86-{key}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    t = {
        "id": "r86-ticks", "title": "R86 idea 7: large prints and absorption on the phone's own tick recordings - not run",
        "family": "flow-oi", "date": "2026-10-19", "doc": DOC,
        "hypothesis": "Large prints / absorption in the phone's tick recordings precede moves (idea 7 of the R86 brief).",
        "data_version": "none: the private data repo mevishalsonawane-ai/IraAlgo was not reachable from this session",
        "period": {"design": "none"}, "instruments": ["NIFTY", "BANKNIFTY"], "features": ["tick recordings (none read)"],
        "model_or_rule": "none", "settings": PREREG + " section 6", "prereg": PREREG, "variants_tried": None,
        "results": {"summary": "Not run: no recordings reachable (OF1 found 0 releases on 14 Oct). R8's one 34-minute MCX "
                               "evening is an anecdote, not a test.", "costs": "none"},
        "holdout_read": None, "verdict": "inconclusive",
        "notes": "Re-open when the phone has uploaded a few weeks of NSE recordings; hunt/of1/footprints.py is ready.",
    }
    (ENTRIES / "r86-ticks.yaml").write_text(yaml.safe_dump(t, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
