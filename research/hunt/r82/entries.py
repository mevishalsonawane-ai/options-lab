"""Write one experiment entry per R82 test (research/experiments/entries/r82-<test>.yaml) from results/design.json and
results/final.json, plus one entry per pre-registered idea that could not be tested.

    python3 -m research.hunt.r82.entries && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

from research.hunt.r82 import r82lib as L
from research.hunt.r82 import study as S

ROOT = L.HERE.parents[2]
OUT = ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R82_MCX_PRECIOUS.md"
PREREG = "research/hunt/r82/PREREG.md"

TITLES = {
    "T1": "MCX 12-month trend (gold + silver legs) with a 3-ATR trailing stop",
    "T2": "MCX 12-month trend, long only, with a 3-ATR trailing stop",
    "T3": "MCX 12-month trend with a 3-ATR trail, opened only when gold and silver agree (breadth)",
    "T4": "MCX 12-month trend with a 3-ATR trail, skipping entries when 20-day volatility is above its 1-year 80th percentile",
    "D0": "Daily Donchian 55/20 on MCX gold and silver, 2-ATR initial stop, long and short",
    "D1": "Daily Donchian 55/20 on MCX gold and silver with a Supertrend (10, 3) agreement filter",
    "D2": "Daily Donchian 55/20 with Supertrend agreement on both metals (gold+silver breadth)",
    "G1": "Gold/silver ratio regime: long the metal the 63-day ratio favours, if its 12-month trend is up, 3-ATR trail",
    "U1": "MCX 12-month trend long only with a 3-ATR trail, new longs only when the DXY 63-day change is not up",
    "L1": "Liquidity 1h (gold arm rule) on MCX hours, GOLDM / SILVERM futures, buys only, 1-ATR stop",
    "L2": "Liquidity 1h on MCX hours, buys only, only when the other metal's 1h close is above its EMA 20",
    "L3": "Liquidity 1h on MCX hours, long and short",
    "E1": "Liquidity 1h on MCX hours, buys only, skipping US CPI / NFP / FOMC days",
    "O1": "Overnight COMEX move into the MCX day (09:30 IST), gold and silver agreeing and both >= 0.20%, 1-ATR stop",
    "U2": "First US-cash hour on COMEX: both metals the same way -> trade both futures that way, stop at the hour's extreme",
    "U3": "First US-cash hour with gold+silver agreement, on US CPI / NFP / FOMC days only",
}
NOT_TESTED = {
    "opt-vs-fut": ("MCX options versus futures for the same signal", "no MCX option history reachable in the R82 sandbox"),
    "roll-timing": ("Roll timing of the MCX 12-month trend arm", "no contract-by-contract MCX futures history reachable"),
    "real-yield": ("US real-yield direction as context for MCX gold", "FRED DFII10 unreachable; Yahoo has no real-yield series"),
    "usdinr": ("USDINR as separate context for MCX gold / silver", "not run, to keep the test count down; USDINR is inside every rupee proxy"),
    "liq-15m": ("Liquidity 15m on GOLDM / SILVERM", "gold: a logged failure (GOLD_TIMEFRAMES, M3 LIQ15); silver: no XAGUSD minutes"),
}


def rs(x):
    return "n/a" if x is None else ("-" if x < 0 else "+") + "Rs " + f"{abs(x):,.0f}"


def main():
    des = json.loads((L.RESULTS / "design.json").read_text())["tests"]
    fin = json.loads((L.RESULTS / "final.json").read_text())
    for t in S.ALL_TESTS:
        d, f, g = des[t], fin["tests"][t], fin["gates"][t]
        daily = t in S.DAILY_TESTS
        base = S.BASE[t]
        bl = (f"; baseline {base}: design {rs(des[base]['design']['net'])}, walk-forward {rs(des[base]['wf_net'])}, final "
              f"{rs(fin['tests'][base]['final']['net'])} on {fin['tests'][base]['final']['trades']} trades") if base else \
             "; baseline: no trade (a new rule)"
        failed = [k for k, v in g.items() if k != "pass" and not v]
        summ = (f"Design: {d['design']['trades']} trades, win {d['design']['win']}, net {rs(d['design']['net'])}, max DD "
                f"{rs(d['design']['max_dd'])}; walk-forward net {rs(d['wf_net'])} ({d['wf_green']}/{len(d['wf_blocks'])} blocks "
                f"green); design twin p {d['twin_p']:.3f}, BH q {d['bh_q']:.3f} (16 tests). Final: {f['final']['trades']} trades, "
                f"win {f['final']['win']}, net {rs(f['final']['net'])}, max DD {rs(f['final']['max_dd'])}, twin p "
                f"{f['twin_p']:.3f}, net without the top 5 {rs(f['top5_removed'])}{bl}. Money: Rs per 1 lot of GOLDM and 1 lot "
                f"of SILVERM (rupee proxies). Verdict: {'PASS' if g['pass'] else 'fail (' + ', '.join(failed) + ')'}.")
        e = dict(
            id=f"r82-{t.lower()}", title=f"R82 {t}: {TITLES[t]}", family="mcx-crypto", date="2026-10-18", doc=DOC,
            hypothesis=f"Boss, 18 Oct: enhance the MCX precious-metal strategies. {TITLES[t]} beats its baseline "
                       f"({base or 'no trade'}) in walk-forward and final, after the app's MCX costs.",
            data_version=("Dukascopy XAUUSD / XAGUSD daily BID (2003-2026) x Yahoo INR=X x duty factor (rupee proxy of the MCX "
                          "future); Yahoo DX-Y.NYB" if daily else
                          "Yahoo GC=F / SI=F 1-hour (20 Oct 2024 - 16 Oct 2026) x INR=X x duty factor (rupee proxy of the MCX "
                          "future). No real MCX price: the Drive lake was unreachable"),
            period=dict(design=("1 Jan 2005 - 31 Dec 2022 (walk-forward 2-year blocks 2009-2022)" if daily else
                                "21 Oct 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)"),
                        holdout=("1 Jan 2023 - 16 Oct 2026, read once (R82 final)" if daily else
                                 "1 Apr - 16 Oct 2026, read once (R82 final)")),
            instruments=["GOLDM", "SILVERM"],
            features=[f"signal source: {S.SIGNAL[t]}; trade the future (no option chart)"],
            model_or_rule=TITLES[t], settings="research/hunt/r82/PREREG.md section 4; research/hunt/r82/"
                                              + ("daily.py" if daily else "intraday.py"),
            prereg=PREREG, variants_tried=1,
            results=dict(summary=summ, costs="McxCosts futures charges, 0.03% slippage a fill, 0.10% on stops, 6%/yr carry on "
                                             "held daily futures (longs pay)" if daily else
                                             "McxCosts futures charges, 0.03% slippage a fill, 0.10% on stops; flat daily"),
            holdout_read=True, verdict="paper-only" if g["pass"] else "rejected",
            notes="Research only; nothing changes paper or live. Prices are proxies (spot / COMEX x USDINR), not MCX prints.",
        )
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    for k, (title, why) in NOT_TESTED.items():
        e = dict(id=f"r82-nt-{k}", title=f"R82 (not tested): {title}", family="mcx-crypto", date="2026-10-18", doc=DOC,
                 hypothesis=f"Pre-registered in R82 as an idea; not tested: {why}.", data_version="none", period=dict(
                     design="n/a", holdout="n/a"), instruments=["GOLDM", "SILVERM"], features=["not tested"],
                 model_or_rule=title, settings="research/hunt/r82/PREREG.md section 4", prereg=PREREG, variants_tried=0,
                 results=dict(summary=f"Not tested: {why}.", costs="n/a"), holdout_read=False, verdict="not-a-test")
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
