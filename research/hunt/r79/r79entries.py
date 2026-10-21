"""R79 experiment entries: one YAML per variant (B0 + 6 tests) from results/summary.json. 18 Oct 2026.

    python research/hunt/r79/r79entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json
import sys

import yaml

import r79rules as RU
import r79study as S

sys.path.insert(0, str(S.HERE.parent / "r64"))
sys.path.insert(0, str(S.HERE.parent / "r65"))
from r64entries import line  # noqa: E402
from r65report import rs  # noqa: E402

ENTRIES = S.R64.ROOT / "research" / "experiments" / "entries"
TITLE = "Liquidity 15+5 BANKNIFTY (index level breaks on 15/5-min index bars; 1-ITM option bought)"
SRC = {"B0": "signal: BANKNIFTY index bars (liquidity levels); exits: option premium and index levels",
       "S1": "signal: BANKNIFTY index bars; filter: Supertrend(10,3) on BANKNIFTY 5-min index bars",
       "S2": "signal: BANKNIFTY index bars; filter: Supertrend(10,3) on BANKNIFTY 15-min index bars",
       "W10a": "signal: BANKNIFTY index bars; filter and entry time: the next 10 one-minute BANKNIFTY index candles",
       "W10b": "signal: BANKNIFTY index bars; filter and entry time: the next 10 five-minute BANKNIFTY index candles",
       "X1": "signal: BANKNIFTY index bars; filter: 5-min Supertrend(10,3) of NIFTY, FINNIFTY, SENSEX index bars",
       "X2": "signal: BANKNIFTY index bars; filter: 5-min Supertrend(10,3) of NIFTY, FINNIFTY, SENSEX index bars"}
NAMES = dict(RU.NAMES, X1="take only when at least 2 of NIFTY, FINNIFTY, SENSEX have their 5-minute Supertrend(10,3) "
                          "agreeing with the side",
             X2="take only when all 3 of NIFTY, FINNIFTY, SENSEX have their 5-minute Supertrend(10,3) agreeing")


def main():
    s = json.loads((S.RESULTS / "summary.json").read_text())
    for v in S.ALL:
        r = s["res"][v]
        if v == "B0":
            verdict, extra = "paper-only", " Liquidity's plain rules on this source and window; unchanged."
        else:
            dec = r["decision"]
            verdict = "rejected"
            vd, vf, p = r["vs_b0"]["design"], r["vs_b0"]["final"], r["paired"]
            extra = (f" Against B0: design {rs(p['design']['diff'])} (bootstrap p {p['design']['p']}), walk-forward "
                     f"{rs(r['wf']['net'] - s['res']['B0']['wf']['net'])}, final {rs(p['final']['diff'])} (p "
                     f"{r['p']}, BH q {r['q']} over 6 tests); random-skip p {r['skip_p']}. Design: skipped "
                     f"{vd['skipped']} B0 trades (B0 net on them {rs(vd['skipped_net'])}), kept {vd['kept']} "
                     f"(change on them {rs(vd['kept_diff'])}). Verdict {dec['verdict']}: {'; '.join(dec['fails'])}.")
        e = dict(
            id=f"r79-liquidity-{v.lower()}", title=f"R79 Liquidity direction: {NAMES[v]}", family="liquidity",
            date="2026-10-18", doc="research/HUNT_R79_LIQUIDITY_DIRECTION.md",
            hypothesis=("Boss (18 Oct): 'should we use Supertrend or wait for 10 candles for getting accurate direction? "
                        "Or like Breadth Donchian, check all the other indices at once?' This variant: "
                        f"{NAMES[v]}. PASS only if it beats B0 in walk-forward AND final, BH q < 0.10 and random-skip "
                        "p < 0.10."),
            data_version=("GitHub release research-data: banknifty_prev_year_wide + banknifty_year_wide (BANKNIFTY "
                          "index + options), nifty_prev / nifty_year, finnifty_index, sensex_index (index minutes); "
                          "20 Nov 2024 - 23 Feb 2026 (PREREG Amendment 1: the Drive lake was not reachable)"),
            period=dict(design="20 Nov 2024 - 31 Dec 2025 (walk-forward Apr - Dec 2025)",
                        holdout="1 Jan - 23 Feb 2026 (30 trading days with trades), read once; inside the design "
                                "period of R51 / R63 / R70 / R76 (weak)"),
            instruments=["BANKNIFTY"] + (["NIFTY", "FINNIFTY", "SENSEX"] if v in ("X1", "X2") else []),
            features=[SRC[v]],
            model_or_rule=f"{TITLE}; {NAMES[v]}", settings="research/hunt/r79/r79study.py; PREREG.md Amendment 1",
            prereg="research/hunt/r79/PREREG.md", variants_tried=6,
            results=dict(summary=(f"Design: {line(r['design'])}. Walk-forward: {line(r['wf'])}. Final: "
                                  f"{line(r['final'])}.{extra}"),
                         costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap "
                               "fills at the worse open, one held contract, 1 lot"),
            holdout_read=True, verdict=verdict,
            notes="Signal on the underlying; research only, nothing changes paper or live. Liquidity BANKNIFTY's "
                  "plain-rules record for the 26 Oct decision is untouched. Partial study: one index, no Apr - Oct "
                  "2026 data.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"{len(S.ALL)} entries")


if __name__ == "__main__":
    main()
