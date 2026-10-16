"""R70 experiment entries: one YAML per arm x variant (4) from results/summary.json.

    python research/hunt/r70/r70entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys

import yaml

import r70study as S

sys.path.insert(0, str(S.HERE.parent / "r64"))
from r64entries import line  # noqa: E402
from r65report import rs  # noqa: E402

ENTRIES = S.R64.ROOT / "research" / "experiments" / "entries"
TXT = {"Liquidity": ("Liquidity 15+5 (index level breaks on 15/5-min bars BANKNIFTY and MIDCPNIFTY, 30/5 FINNIFTY; 1-ITM)",
                     ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"], "index bars (liquidity levels)"),
       "SoloMidday": ("Solo midday (R57 S1: 12:00 index move >= 0.5 ATR, the strongest index, 4-ITM, 1 a day)",
                      ["NIFTY", "BANKNIFTY", "FINNIFTY"], "index (12:00 move vs ATR)")}
LABEL = {"B0": "own exits (today)", "L25": "own exits + stop to +5% once +25% (Boss's selected lock)"}


def main():
    s = json.loads((S.RESULTS / "summary.json").read_text())
    for arm in S.ARMS:
        A = s["arms"][arm]
        title, unds, src = TXT[arm]
        for v in S.VARIANTS:
            r = A[v]
            if v == "B0":
                verdict, extra = "paper-only", " The arm's own exits re-measured (it runs on paper); unchanged."
            else:
                verdict = "rejected" if not A["decision"]["harmless"] else "inconclusive"
                t, p = r["trail"], r["paired"]
                extra = (f" L25 changed {t['design']['changed']} design / {t['final']['changed']} final trades; versus B0: "
                         f"design {rs(p['design']['diff'])} (bootstrap p {p['design']['p']}), final "
                         f"{rs(p['final']['diff'])} (p {p['final']['p']}); exit-twin p {r['exit_p']} (q {r['exit_q']}). "
                         "Not at least B0's net in every period, so not recommended and not 'harmless' on this arm.")
            e = dict(
                id=f"r70-{arm.lower()}-{v.lower()}", title=f"R70 {arm}: {LABEL[v]}", family="trade-management",
                date="2026-10-16", doc="research/HUNT_R70_LOCK_OTHER_ARMS.md",
                hypothesis=("Boss (16 Oct): the day's best lock (R66 L25, stop to +5% once the option has been +25%) "
                            f"also helps the other arms. This variant: {LABEL[v]}; recommended only if its net is at "
                            "least B0's in design, walk-forward and final and it beats random early exits (q <= 0.10)."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                              "clean stores (Liquidity) / R57 stores (Solo midday), 20 Nov 2024 - 13/14 Oct 2026"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                            holdout="1 Apr - 13/14 Oct 2026, read once; already read by R51 / R57 / R63 (weak)"),
                instruments=unds, features=[f"signal source: {src} (unchanged); only the option exit differs"],
                model_or_rule=f"{title}; exits: {LABEL[v]}", settings="research/hunt/r70/r70study.py; PREREG.md",
                prereg="research/hunt/r70/PREREG.md", variants_tried=4,
                results=dict(summary=(f"Design: {line(r['design'])}. Walk-forward: {line(r['wf'])}. Final: "
                                      f"{line(r['final'])}.{extra}"),
                             costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap "
                                   "fills at the worse open, locks from the next minute, one held contract, 1 lot"),
                holdout_read=True, verdict=verdict,
                notes="Signal on the underlying; research only, nothing changes paper or live. Liquidity BANKNIFTY's "
                      "plain-rules record for the 26 Oct decision is untouched.")
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("4 entries")


if __name__ == "__main__":
    main()
