"""R80 experiment entries: one YAML per entry filter (V1-V10) from results/design.json, final.json, decision.json.
18 Oct 2026.

    python research/hunt/r80/r80entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENTRIES = ROOT / "research" / "experiments" / "entries"
RES = HERE / "results"
VARIANTS = tuple(f"V{i}" for i in range(1, 11))
LABEL = {"V1": "only pools that formed on an earlier session (older liquidity)",
         "V2": "only levels with >= 3 same-side zones stacked on them",
         "V3": "only breaks that cross a round number (500s BANKNIFTY / SENSEX, 100s NIFTY)",
         "V4": "only clean breaks: the pool was not swept (wick through, close back) before the break",
         "V5": "only breaks whose close is beyond the previous day's high (calls) / low (puts)",
         "V6": "no entries before 10:15",
         "V7": "from 10:15, only on days whose first-hour range >= its 20-day median",
         "V8": "only displacement breaks: breaking bar's body >= 1.5x the mean body of the 20 bars before",
         "V9": "at most 2 trades a day (both books)",
         "V10": "stop for the day after the first losing trade"}


def rs(x):
    return f"Rs {x:,.0f}" if x >= 0 else f"-Rs {-x:,.0f}"


def line(s):
    return (f"{s['trades']} trades, win {s['win'] * 100:.0f}%, net {rs(s['net'])}, worst day {rs(s['worst_day'])}, "
            f"max DD {rs(s['max_dd'])}")


def main():
    dz, fz = (json.loads((RES / f"{p}.json").read_text()) for p in ("design", "final"))
    dec = json.loads((RES / "decision.json").read_text())["BANKNIFTY"]
    for v in VARIANTS:
        d, f = dz["BANKNIFTY"][v], fz["BANKNIFTY"][v]
        cross = "; ".join(f"{u}: design {rs(dz[u][v]['diff'])}, final {rs(fz[u][v]['diff'])} vs plain"
                          for u in ("NIFTY", "SENSEX"))
        summ = (f"BANKNIFTY design: {line(d['all'])} ({rs(d['diff'])} vs B0 {rs(dz['BANKNIFTY']['B0']['all']['net'])} "
                f"on {dz['BANKNIFTY']['B0']['all']['trades']}; walk-forward Apr 2025 - Mar 2026 {rs(d['wf_diff'])} vs B0; "
                f"design twin p {d['twin_p']}). Final (read once): {line(f['all'])} ({rs(f['diff'])} vs B0 "
                f"{rs(fz['BANKNIFTY']['B0']['all']['net'])} on {fz['BANKNIFTY']['B0']['all']['trades']}); random-skip "
                f"twin p {f['twin_p']}, BH q {dec[v]['q']} over 10; top-5 check {rs(f['top5_diff'])}. "
                f"Fails: {', '.join(dec[v]['fails'])}. Information only, {cross}.")
        e = dict(
            id=f"r80-liq-entry-{v.lower()}", title=f"R80 Liquidity 15+5 entry filter {v}: {LABEL[v]}",
            family="liquidity", date="2026-10-18", doc="research/HUNT_R80_LIQUIDITY_ENTRIES.md",
            hypothesis=(f"Filtering Liquidity 15+5's signals ({LABEL[v]}) beats the plain rules in the walk-forward "
                        "window and the final period, beats random skipping of as many signals (twin p < 0.10, BH q < "
                        "0.10 over 10 filters) and is not carried by its top 5 trades."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                          "clean stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="1 Apr - 13 Oct 2026, read once; already read by R51 / R63 / R70 / R76 (weak)"),
            instruments=["BANKNIFTY", "NIFTY", "SENSEX"],
            features=["signal: index bars (Liquidity level breaks, 15/5-min); filter: index bars only; option bought "
                      "(1-ITM)"],
            model_or_rule=f"Liquidity 15+5 (fam_liq, unchanged exits) + {LABEL[v]}",
            settings="research/hunt/r80/r80rules.py, r80study.py; PREREG.md", prereg="research/hunt/r80/PREREG.md",
            variants_tried=10,
            results=dict(summary=summ, costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low "
                                             "with gap fills at the worse open, one held contract, 1 lot"),
            holdout_read=True, verdict="adopted" if dec[v]["passed"] else "rejected",
            notes="Signal on the underlying; research only, nothing changes paper or live. Liquidity BANKNIFTY's "
                  "plain-rules record for the 26 Oct decision is untouched.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"{len(VARIANTS)} entries")


if __name__ == "__main__":
    main()
