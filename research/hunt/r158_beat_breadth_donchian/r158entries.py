"""R158 experiment entries: one YAML per rule (5), failures included."""
from __future__ import annotations

import json

import yaml

import r158cfg as K
from r156report import line, rs
from r158report import NAME

ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    b0 = dec["_b0"]
    for t in K.TESTS:
        g = dec[t]
        e = dict(
            id="r158-" + t.lower(),
            title=f"R158 {t}: {NAME[t]} (network trained on Breadth Donchian's exit at every 5-minute moment)",
            family="ml",
            date="2026-11-10",
            doc="research/hunt/r158_beat_breadth_donchian/results/report.md",
            hypothesis=("Boss (10 Nov): find anything that beats Breadth Donchian on win rate and profit. A network trained "
                        "walk-forward on the result of R72's exit XL at every 5-minute moment (5 indices' last hour of bars + "
                        f"43 numbers) gives rule {t}: {NAME[t]}. Gates: net > 0 in P2 and P3 (standalone also O1); a higher win "
                        f"rate AND net than R72 in P2 and P3; twins BH q <= 0.10 (m {dec['_m']['total']}); >= 40 final trades."),
            data_version="as R157 (Drive lake copied 9 Nov 2026; R154 stores; R155 OI store; R156 / R157 examples) plus R158's XL labels",
            period=dict(design="training from Aug 2021, expanding; scored 2023 - Mar 2025 (O1) and Apr 2025 - Mar 2026 (P2)",
                        holdout="2026-04-01..2026-10-13 read once by R158 (R72's trades there read by R72-R153); post 14-19 Oct reported"),
            instruments=list(K.UNDS),
            features=["signal source: the index and the other four indices (last 60 one-minute bars), levels, trend, wicks, "
                      "time; option chain OI / PCR and India VIX as context; buys the 1-ITM option; exits XL on the premium"],
            model_or_rule=f"R158 network, rule {t}",
            settings="research/hunt/r158_beat_breadth_donchian/r158cfg.py; PREREG.md section 2",
            prereg="research/hunt/r158_beat_breadth_donchian/PREREG.md",
            variants_tried=len(K.TESTS),
            results=dict(summary=((f"O1: {line(g['O1'])}. " if "O1" in g else "") + f"P2: {line(g['P2'])} (R72: {line(b0['P2'])}). "
                                  f"P3: {line(g['P3'])} (R72: {line(b0['P3'])}); twins' mean {rs(g['twin_mean'])}, p {g['twin_p']}, "
                                  f"BH q {g['q']}. " + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                         costs="dated Zerodha charges, R17 fills, 1 lot"),
            holdout_read=True,
            verdict="paper-only" if g["passed"] else "rejected",
            notes="Signal on the underlying; nothing changes live.",
        )
        (ENTRIES_DIR / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(K.TESTS), "entries")


if __name__ == "__main__":
    main()
