"""R156 experiment entries: one YAML per trading rule (51), failures included.

    python research/hunt/r156_scalp_network/r156entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r156cfg as K
from r156report import line, rs

ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"


def rule_text(t: str) -> str:
    if t.startswith("EV-"):
        return f"exit {t[3:]}: take the side with the higher forecast net if it is > 0"
    if t.startswith("TOP-"):
        return f"exit {t[4:]}: take the side with the higher forecast win chance if it is in the top tenth of the model's training forecasts"
    return {"BEST-A": "the best (exit, side) by forecast net among the 12 option-point exits, if > 0",
            "BEST-B": "the best (exit, side) by forecast net among the 12 index-point exits, if > 0",
            "BEST": "the best (exit, side) by forecast net among all 24 exits, if > 0"}[t]


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    for t in K.TESTS:
        g = dec[t]
        e = dict(
            id="r156-" + t.lower().replace(".", "p"),
            title=f"R156 scalp network, rule {t}: {rule_text(t)}",
            family="ml",
            date="2026-11-10",
            doc="research/hunt/r156_scalp_network/results/report.md",
            hypothesis=("Boss (10 Nov): \"create a neuro network, to understand scalping\". A convolutional network trained "
                        "walk-forward on the after-cost result of every 5-minute scalp moment (both sides, 5 indices, 24 "
                        f"exits) can pick profitable 10-15 point scalps. Rule: {rule_text(t)}; one open position per index, 5 "
                        "trades per index a day. Gates: net > 0 in O1, P2, P3; beats random-entry twins with BH q <= 0.10 "
                        f"(m {dec['_m']['total']}); >= 40 final trades."),
            data_version=("Drive lake copied 9 Nov 2026 (index_1m incl. INDIAVIX, options_rolling_1m WEEK incl. OI); R154's "
                          "stores; R155's OI store; R156 examples (733,260)"),
            period=dict(design="training from Aug 2021, expanding; scored 2023 - Mar 2025 (O1) and Apr 2025 - Mar 2026 (P2), "
                               "each by models trained on earlier sessions only",
                        holdout="2026-04-01..2026-10-13 read once by R156 (third look for scalps after R154 / R155); post "
                                "14-19 Oct reported"),
            instruments=list(K.UNDS),
            features=["signal source: the index (last 60 one-minute bars, levels, trend, wicks, time); option chain OI / PCR "
                      "and India VIX as context; buys the 1-ITM option",
                      "model: Conv1d x3 over the sequence + a 43-number table branch; 24 x (win logit, expected net); 3 seeds"],
            model_or_rule=f"R156 network, rule {t}",
            settings="research/hunt/r156_scalp_network/r156cfg.py; PREREG.md sections 3-4",
            prereg="research/hunt/r156_scalp_network/PREREG.md",
            variants_tried=len(K.TESTS),
            results=dict(summary=(f"1 lot, 5 indices pooled. O1: {line(g['O1'])}. P2: {line(g['P2'])}. P3: {line(g['P3'])} "
                                  f"(random-entry twins' mean {rs(g['twin_mean'])}; p {g['twin_p']}, BH q {g['q']}). "
                                  + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                         costs="dated Zerodha charges, R17 fills, limit targets at their price, 1 lot"),
            holdout_read=True,
            verdict="paper-only" if g["passed"] else "rejected",
            notes="Signal on the underlying; nothing changes live.",
        )
        (ENTRIES_DIR / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(K.TESTS), "entries")


if __name__ == "__main__":
    main()
