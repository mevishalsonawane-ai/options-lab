"""Writes research/experiments/entries/r43-*.yaml: one per signal family (all its variants) and one for the
no-signal baselines, failures included.

    python3 research/hunt/r43/entries.py
"""
from __future__ import annotations

import json

import yaml

import r43cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
TITLE = {
    "ema821_adx15": "EMA8 x EMA21 with ADX(14) > 15",
    "ema821_adx15_lock": "EMA8 x EMA21 with ADX(14) > 15, stepped profit lock (1b)",
    "ema921_adx20": "EMA9 x EMA21 with ADX(14) > 20",
    "ema926_adx20": "EMA9 x EMA26 with ADX(14) > 20",
    "rsi_fade": "RSI(14) fade: CE on a cross below 30, PE on a cross above 70",
    "rsi_momo": "RSI(14) momentum: CE on a cross above 55, PE on a cross below 45",
    "donchian20": "Donchian 20-bar breakout of the prior highs / lows",
    "base": "no signal: the same cheap option (CE and PE) at a fixed time every expiry day",
}


def _line(rid: str, v: dict) -> str:
    d, h, g = v["design"]["m"], v["holdout"]["m"], v["gates"]
    return (f"{rid}: design {d['trades']} trades net Rs {d['net']:+,.0f}; holdout {h['trades']} trades, win "
            f"{h['win_pct']}%, peak 5x/10x {h['peak5']}/{h['peak10']}, net Rs {h['net']:+,.0f} "
            f"({h['net_per_day']:+.0f} a day), max DD Rs {h['max_dd']:+,.0f}, p {v['holdout']['p']}, "
            f"q {v['holdout']['q']}: {'PASS' if g['passes'] else 'fail'}")


def main():
    fin = json.loads((K.RESULTS / "final.json").read_text())
    fams = list(K.SIGNALS) + ["base"]
    for fam in fams:
        rs = {rid: v for rid, v in fin["rules"].items() if v["rule"][0] == fam}
        best = max(rs.items(), key=lambda x: x[1]["holdout"]["m"]["net"])
        npass = sum(v["gates"]["passes"] for v in rs.values())
        nd = sum(fin["index_days"]["design"].values())
        nh = sum(fin["index_days"]["holdout"].values())
        summ = (f"{len(rs)} variants (strike cheap Rs 5-30 / OTM2 x window all-day / 12:30-14:45 x exit), Rs 5,000 a trade, "
                f"1 trade per index-expiry-day max, on {nd} design and {nh} holdout index-expiry-days. {npass} of {len(rs)} "
                f"pass. Best holdout variant: " + _line(best[0], best[1]) + ". All variants: "
                + "; ".join(_line(r, v) for r, v in rs.items()) + ".")
        e = dict(
            id=f"r43-{fam.replace('_', '-')}", title=f"Sweep signal as zero-to-hero expiry trade: {TITLE[fam]}",
            family="patterns-indicators", date="2026-10-14", doc="research/HUNT_R43_SWEEP_SIGNALS_HERO.md",
            hypothesis=("Boss's sweep signal, read on the 5-minute INDEX close (signal source: the index spot), buying a cheap "
                        "OTM option (Rs 5-30) or the OTM2 strike at the next 5-minute bar's open on the index's own expiry "
                        "day and holding for a big multiple with a -50% stop, catches zero-to-hero trades better than "
                        "random entries after costs.") if fam != "base" else
                       ("Buying the same cheap option (CE and PE) at the same time every expiry day, no signal, pays after "
                        "costs (the baseline the signals must beat)."),
            data_version=("Dhan lake on Drive: opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_WEEK (ATM+-10, 1-minute), "
                          "index_1m of the same; 5-minute index bars built from the 1-minute data; expiry days from the data "
                          "(R25 rule); lake ends 12 Oct 2026"),
            period={"design": "expiry days 5 Aug 2021 - 31 Dec 2024 (per-year results = walk-forward of a fixed rule)",
                    "holdout": "expiry days 1 Jan 2025 - 12 Oct 2026, read once"},
            instruments=list(K.UNDS),
            features=["5-minute index EMA / RSI / ADX / Donchian (signal on the underlying)"] if fam != "base" else ["time of day"],
            model_or_rule=TITLE[fam], settings="research/hunt/r43/r43cfg.py", prereg="research/hunt/r43/PREREG.md",
            variants_tried=len(rs),
            results=dict(summary=summ, costs="app charges at today's rates; next-open fills + 5 bps + 1/2/4-tick half-spread; "
                                             "entry limit 1.2x + 0.10 for 3 min; x2 spread stress reported"),
            holdout_read=True, verdict="paper-only" if npass else "rejected",
            notes=(f"One family of the R43 BH family of {fin['n_rules']} tests (BH over all 88 holdout p-values). Random-entry "
                   f"twins: 500 per rule, same index-day, side, window, strike rule and exit. Signal source: the index spot."))
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(fams))


if __name__ == "__main__":
    main()
