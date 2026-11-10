"""R157 experiment entries: one YAML per trading rule (114), failures included.

    python research/hunt/r157_scalp_network_plus/r157entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r157cfg as K
from r156report import line, rs

ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"


def rule_text(t: str) -> str:
    name, lots = t.rsplit("@", 1)
    if name == "BEST":
        return f"{lots} lot(s): the (exit, side) with the highest forecast net among the 28 exits, if > 0"
    kind, r = name.split("-", 1)
    ex = (f"run exit {r} (stop {K.RUN[r]['p']:g} premium points, then a {K.RUN[r]['p']:g}-point trail once that far up, "
          f"{K.RUN[r]['tmax']} minutes)" if r in K.RUN else f"exit {r}")
    how = "the side with the higher forecast net if > 0" if kind == "EV" else \
        "the side with the higher forecast win chance if in the top 1% of the model's training forecasts"
    return f"{lots} lot(s), {ex}: {how}"


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    for t in K.TESTS:
        g = dec[t]
        e = dict(
            id="r157-" + t.lower().replace(".", "p").replace("@", "-lots"),
            title=f"R157 enhanced scalp network, rule {t}: {rule_text(t)}",
            family="ml",
            date="2026-11-10",
            doc="research/hunt/r157_scalp_network_plus/results/report.md",
            hypothesis=("Boss (10 Nov): enhance the scalp network for more win rate and profit. R156's network with the other "
                        "four indices' 1-minute bars as input, labels at 1 and 3 lots, four run exits and a top-1% rule picks "
                        f"profitable scalps. Rule: {rule_text(t)}; one open position per index, 5 trades per index a day. "
                        f"Gates: net > 0 in O1, P2, P3; beats random-entry twins with BH q <= 0.10 (m {dec['_m']['total']}); "
                        ">= 40 final trades."),
            data_version="as R156 (Drive lake copied 9 Nov 2026; R154 stores; R155 OI store) plus R157's cross-index sequences and 56 label columns",
            period=dict(design="training from Aug 2021, expanding; scored 2023 - Mar 2025 (O1) and Apr 2025 - Mar 2026 (P2) "
                               "by models trained on earlier sessions only",
                        holdout="2026-04-01..2026-10-13 read once by R157 (fourth look for scalps after R154-R156); post 14-19 Oct reported"),
            instruments=list(K.UNDS),
            features=["signal source: the index and the other four indices (last 60 one-minute bars each), levels, trend, "
                      "wicks, time; option chain OI / PCR and India VIX as context; buys the 1-ITM option",
                      "model: Conv1d x3 over 13 sequence channels + a 43-number table branch; 56 x (win logit, expected net); 3 seeds"],
            model_or_rule=f"R157 network, rule {t}",
            settings="research/hunt/r157_scalp_network_plus/r157cfg.py; PREREG.md sections 0-1",
            prereg="research/hunt/r157_scalp_network_plus/PREREG.md",
            variants_tried=len(K.TESTS),
            results=dict(summary=(f"5 indices pooled, rupees for the position. O1: {line(g['O1'])}. P2: {line(g['P2'])}. P3: "
                                  f"{line(g['P3'])} (random-entry twins' mean {rs(g['twin_mean'])}; p {g['twin_p']}, BH q "
                                  f"{g['q']}). " + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                         costs="dated Zerodha charges and R17 fills on the whole quantity, limit targets at their price"),
            holdout_read=True,
            verdict="paper-only" if g["passed"] else "rejected",
            notes="Signal on the underlying; nothing changes live.",
        )
        (ENTRIES_DIR / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(K.TESTS), "entries")


if __name__ == "__main__":
    main()
