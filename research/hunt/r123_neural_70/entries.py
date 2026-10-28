"""Writes research/experiments/entries/r123-*.yaml from results/wf.json and results/holdout.json (one per rule and
network, plus the model tests and the uncleaned first walk-forward), failures included.

    python research/hunt/r123_neural_70/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r123cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R123_NEURAL_70.md"
NET_NAME = {"N1": "single network (TCN + MLP)", "N2": "multi-level network (encoders, specialists, committee, abstain)"}


def num(x, d=0):
    return None if x is None else round(float(x), d)


def main():
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    ho = json.loads((K.RESULTS / "holdout.json").read_text())
    common = dict(
        family="ml", date="2026-10-28", doc=DOC,
        data_version="Drive lake copied 28 Oct 2026 (index_1m 5 indices + INDIAVIX, options_rolling_1m WEEK), "
                     "lakeclean steps 1-3 (stale=True), zero-volume minutes never priced (Amendment 2)",
        period=dict(design="2021-08-01..2026-03-15 (walk-forward folds 2023, 2024, 2025, 2026-01-01..03-15)",
                    holdout="2026-04-01..2026-10-19 read once (not virgin: R106, R113, R120-R122 read the arms there)"),
        instruments=list(K.UNDS),
        prereg="research/hunt/r123_neural_70/PREREG.md",
        holdout_read=True,
    )
    n_written = 0
    for rid, h in ho["rules"].items():
        net, rule = rid.split("-")
        w = wf["rules"][rid]
        R = K.RULES[rule]
        fails = [k for k, v in h["gates"].items() if not v]
        e = dict(
            id=f"r123-{net.lower()}-{rule.lower()}",
            title=f"R123 {rid}: {R['label']} ({NET_NAME[net]})",
            **common,
            features=["signal source: the network's reading of the underlying (index minutes, India VIX, the five "
                      "indices' breadth, calendar) with the option chain (OI, PCR, IV, skew, straddle) as context; "
                      "the ATM option's premium sets the target and stop only; no rule reads the option's own chart"],
            hypothesis="Boss 28 Oct: a neural network of all the index and option data, simple rules, about 70% wins "
                       "and about Rs 1,000 a trade. Gated on net expectancy after real charges and slippage (2x too), "
                       "random-entry twins, BH q <= 0.10 over the 10 rule tests, enough trades, holdout once.",
            model_or_rule=f"research/hunt/r123_neural_70/ ({rule}, network {net})",
            settings="PREREG.md section 6 and amendments 1-2; 1 lot at today's lots; ATM; max 5 trades a day, one "
                     "open per index",
            variants_tried=10,
            # a pass is only a proposal for Boss (a paper arm is not built here), so it stays "inconclusive"
            verdict="inconclusive" if h["passes"] else "rejected",
            results=dict(
                summary=(f"Holdout {h.get('n', 0)} trades over {h.get('sessions')} sessions: win "
                         f"{(h.get('win_rate') or 0):.0%}, avg net Rs {(h.get('avg_net') or 0):,.0f} "
                         f"(2x slippage Rs {(h.get('avg_net_x2') or 0):,.0f}), Rs {(h.get('per_session') or 0):,.0f} a "
                         f"session, p vs twins {h.get('p_twins', 1):.3f}, q {h.get('q', 1):.3f}. Walk-forward "
                         f"{w.get('n', 0)} trades, avg net Rs {(w.get('avg_net') or 0):,.0f}, q {w.get('q', 1):.3f}. "
                         + ("Passes every gate." if h["passes"] else "Fails: " + ", ".join(fails) + ".")),
                walk_forward=dict(n=w.get("n"), win_rate=num(w.get("win_rate"), 3), avg_net=num(w.get("avg_net")),
                                  avg_net_x2=num(w.get("avg_net_x2")), per_session=num(w.get("per_session")),
                                  p_twins=num(w.get("p_twins"), 4), q=num(w.get("q"), 4),
                                  folds_positive=w.get("folds_positive"), passes=w["passes_wf"]),
                holdout=dict(n=h.get("n"), win_rate=num(h.get("win_rate"), 3), avg_net=num(h.get("avg_net")),
                             avg_net_x2=num(h.get("avg_net_x2")), per_session=num(h.get("per_session")),
                             max_dd=num(h.get("max_dd")), worst_day=num(h.get("worst_day")),
                             worst_month=num(h.get("worst_month")), p_twins=num(h.get("p_twins"), 4),
                             q=num(h.get("q"), 4), sessions=h.get("sessions")),
                fails=fails,
                boss_target_strict=h["boss_target_strict"], boss_target_lenient=h["boss_target_lenient"],
                costs="Costs('dated') charges + Fills('liq') slippage, 1 lot, today's lot sizes"),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        n_written += 1
    # the model tests (one entry) and the uncleaned first walk-forward (one entry)
    best = sorted(ho["models"], key=lambda m: m["p"])[:6]
    e = dict(id="r123-model-tests", title="R123 the networks' heads vs base rate and trees (hit-first, range, direction)",
             **common, features=["index minutes, VIX, breadth, calendar, option-chain context; heads (a) +X before -Y "
                                 "per cell-side, (b) next 30/60-min range, (c) direction"],
             hypothesis="A network over all the data forecasts 'this ATM option gains +X before it loses Y' better "
                        "than the base rate and gradient-boosted trees",
             model_or_rule="research/hunt/r123_neural_70/r123net.py", settings="PREREG.md section 5, amendment 1",
             # the hypothesis failed on the holdout (no "+X first" cell beat the trees; direction a coin), so
             # "rejected"; the range head's edge over trees is R41's known result, not this entry's claim
             variants_tried=len(ho["models"]), verdict="rejected",
             results=dict(summary="Holdout: no '+X first' cell of either network beat gradient-boosted trees (best "
                                  "q 0.34, Brier within +-0.002); direction log-loss equal to 'always 50%'. The range "
                                  "head beat trees again (multi-level R2 0.405 vs 0.386 for 60 min, 43,155 forecasts, "
                                  "q 0.08), as in R41.", costs="none (forecast scores)",
                          walk_forward_cells_q_le_0_05=sum(m["q"] <= 0.05 for m in wf["models"]),
                          holdout_cells_q_le_0_05=sum(m["q"] <= 0.05 for m in ho["models"]),
                          holdout_best=[dict(net=m["net"], cell=m["cell"], metric=m["metric"], n=m["n"],
                                             nn=num(m["nn"], 4), best=m["best"], best_score=num(m["best_score"], 4),
                                             q=num(m["q"], 4)) for m in best]))
    (ENTRIES / "r123-model-tests.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    un = json.loads((K.RESULTS / "wf_uncleaned.json").read_text())
    e = dict(id="r123-uncleaned-first-run", title="R123 first walk-forward on UNCLEANED lake option prices "
             "(superseded by Amendment 2)", **{**common, "holdout_read": False},
             features=["as the R123 rules"], hypothesis="record of the run that found the missing lake cleaning",
             model_or_rule="research/hunt/r123_neural_70/results/wf_uncleaned.json", settings="before Amendment 2",
             variants_tried=10, verdict="superseded",
             results=dict(summary="; ".join(f"{r} n {s.get('n')} avg Rs {num(s.get('avg_net'))}"
                                            for r, s in un["rules"].items()),
                          costs="as the rules (uncleaned prices)"),
             notes="Junk zero-volume prints (e.g. MIDCPNIFTY puts 3 Feb 2026) made fake wins and losses; the "
                   "cleaned rerun is the one that counts.")
    (ENTRIES / "r123-uncleaned-first-run.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("entries:", n_written + 2)


if __name__ == "__main__":
    main()
