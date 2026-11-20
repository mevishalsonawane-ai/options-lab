"""R209 report: results/report.md and the 8 experiment log entries. Plain-English notes, 20 Nov 2026.

    python3 research/hunt/r209_direction_brain/r209report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r209cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
NAME = {"LOG": "logistic regression", "GBM": "gradient-boosted trees", "NET": "neural network (64, 32)"}


def pc(x):
    return "n/a" if x is None else f"{x * 100:.1f}%"


def rs(x):
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def load():
    return tuple(json.loads((K.RESULTS / f).read_text()) for f in ("wf.json", "holdout.json", "arms.json", "decision.json"))


def report() -> str:
    w, h, a, d = load()
    out = ["# R209 results: the direction brain", "",
           "Pre-registered in `PREREG.md` (with Amendment A) before any number. Index direction 15 minutes ahead at every "
           "5-minute point of five indices, inputs from the five indices, India VIX and the option chain. Walk-forward "
           "2023 Q1 - 2026 Q1; holdout 1 Apr - 5 Oct 2026 read once "
           f"({K.HOLDOUT_FLAG.read_text().strip() if K.HOLDOUT_FLAG.exists() else 'n/a'} UTC).", "",
           f"**{len(d['passes'])} of {len(K.TESTS)} tests pass.** Chosen model (best walk-forward AUC): {NAME[d['chosen']]}.", "",
           "## Walk-forward (284,000 points, 13 quarters)", "",
           "| model / inputs | AUC (95% day-block interval) | right overall | most confident 20% right | quarters with top 20% >= 55% |",
           "|---|---|---|---|---|"]
    for k, v in w["pooled"].items():
        m, g = k.split(":")
        q = sum(x["top20"] >= K.TOP_ACC for x in w["per_quarter"][k])
        out.append(f"| {NAME[m]}, inputs {g} | {v['auc']:.4f} ({v['boot']['lo']:.4f} - {v['boot']['hi']:.4f}) | {pc(v['acc'])} | "
                   f"{pc(v['top20'])} | {q} of 13 |")
    out += ["", "Inputs: P = own index, X = the other indices, V = India VIX, ALL = also the option chain.", "",
            "## Holdout, read once", "", "| model | points | AUC (95% interval) | p | right overall | most confident 20% right | verdict |",
            "|---|---|---|---|---|---|---|"]
    for m in K.MODELS:
        v = h["models"][m]
        g = d["tests"][f"T1-{m}"]
        out.append(f"| {NAME[m]} | {v['n']:,} | {v['auc']:.4f} ({v['boot']['lo']:.4f} - {v['boot']['hi']:.4f}) | {v['boot']['p']:.4f} | "
                   f"{pc(v['acc'])} | {pc(v['top20'])} | {'pass' if g['passes'] else 'fail'} |")
    out += ["", "Calibration of the chosen model in the holdout (predicted P(up) -> share up, ten equal bins): "
            + "; ".join(f"{c['mean_p']:.3f} -> {c['up']:.3f}" for c in h["calibration"]) + ".", "",
            "## T2: does it make the arms' own calls more reliable? (holdout, R206's signals)", "",
            "| signals | favoured by the model: right (signals) | not favoured: right (signals) | difference | p | verdict |", "|---|---|---|---|---|---|"]
    for s in ("R72", "R51", "LIQ"):
        r, g = a["t2"][s], d["tests"][f"T2-{s}"]
        out.append(f"| {s} | {pc(r['rate_a'])} ({r['n_a']:,}) | {pc(r['rate_b'])} ({r['n_b']:,}) | {r['d'] * 100:+.1f} points | "
                   f"{r['p']:.3f} | {'pass' if g['passes'] else 'fail'} |")
    out += ["", "## T3: as a filter on today's arms (1 lot, real costs)", "",
            "| arm | threshold (chosen on design) | window | trades removed | net with the filter | net today | verdict |", "|---|---|---|---|---|---|---|"]
    for s in ("R72", "LIQ"):
        r, g = a["t3"][s], d["tests"][f"T3-{s}"]
        for win in ("design", "holdout"):
            out.append(f"| {s} | {r['t']} | {win} | {r['changed'][win]} | {rs(r['windows'][win]['net'])} | {rs(r['base'][win]['net'])} | "
                       f"{('pass' if g['passes'] else 'fail') if win == 'holdout' else '-'} |")
    out += ["", "Design nets by threshold: " + "; ".join(f"{s}: " + ", ".join(f"t {t} {rs(v)}" for t, v in w["t3"][s]["nets"].items())
                                                    + f" (today {rs(w['t3'][s]['base'])})" for s in ("R72", "LIQ")) + ".",
            "", "## Why each test fails", ""]
    for k, g in d["tests"].items():
        out.append(f"- **{k}**: " + ("every item holds" if g["passes"] else "; ".join(g["fails"])) + f" (q within these 8: {g['q_family']:.3g}).")
    return "\n".join(out) + "\n"


def entries():
    import yaml
    w, h, a, d = load()
    com = dict(date="2026-11-20", doc="research/hunt/r209_direction_brain/results/report.md",
               data_version="the dhan-data branch of options-lab (Dhan v2, fetched 6 Oct 2026): 1-minute index and India VIX candles "
                            "and the nearest expiry's rolling option chain, 8 Oct 2021 - 5 Oct 2026 (r209data.py)",
               period=dict(design="2022-01-01..2026-03-31 (walk-forward quarters 2023 Q1 - 2026 Q1)",
                           holdout="2026-04-01..2026-10-05, read once (results/HOLDOUT_READ)"),
               instruments=list(K.UNDS),
               features=["signal source: the index (its direction is predicted); option-chain data only as context inputs"],
               settings="research/hunt/r209_direction_brain/r209cfg.py; PREREG.md sections 3 - 5 and Amendment A",
               prereg="research/hunt/r209_direction_brain/PREREG.md", variants_tried=1, holdout_read=True)

    def write(e):
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    for m in K.MODELS:
        v, p, g = h["models"][m], w["pooled"][f"{m}:ALL"], d["tests"][f"T1-{m}"]
        write(dict(id=f"r209-t1-{m.lower()}", title=f"R209 T1: does a {NAME[m]} predict the index's 15-minute direction usefully?",
                   family="ml", **{k: com[k] for k in ("date", "doc", "data_version", "period", "instruments", "features")},
                   hypothesis="holdout AUC > 0.5 (p < 0.05) and >= 0.530, most confident 20% right >= 55%, and >= 55% in 9 of 13 walk-forward quarters",
                   model_or_rule=f"{NAME[m]} over P + X + V + O inputs (r209study.fit_predict)",
                   settings=com["settings"], prereg=com["prereg"], variants_tried=1,
                   results=dict(summary=(f"Walk-forward {p['n']} points: AUC {p['auc']:.4f}, right {p['acc'] * 100:.1f}%, top 20% right "
                                         f"{p['top20'] * 100:.1f}%. Holdout {v['n']} points: AUC {v['auc']:.4f} (p {v['boot']['p']:.4f}), right "
                                         f"{v['acc'] * 100:.1f}%, top 20% right {v['top20'] * 100:.1f}%. "
                                         + ("Passes." if g["passes"] else "Does not pass: " + "; ".join(g["fails"]))),
                                costs="none (index direction only)"),
                   holdout_read=True, verdict="inconclusive" if g["passes"] else "rejected", notes="Research only; nothing in the app changes."))
    for s in ("R72", "R51", "LIQ"):
        r, g = a["t2"][s], d["tests"][f"T2-{s}"]
        write(dict(id=f"r209-t2-{s.lower()}", title=f"R209 T2: are {s} signals the model favours right more often?", family="ml",
                   **{k: com[k] for k in ("date", "doc", "data_version", "period", "instruments", "features")},
                   hypothesis="in the holdout, signals whose side the chosen model favours are right more often (p < 0.05) and >= 3 points above the source's own",
                   model_or_rule=f"chosen model ({NAME[d['chosen']]}) P(side) >= 0.5 on R206's {s} signals", settings=com["settings"],
                   prereg=com["prereg"], variants_tried=1,
                   results=dict(summary=(f"Favoured: right {r['rate_a'] * 100:.1f}% of {r['n_a']}; not favoured: {r['rate_b'] * 100:.1f}% of {r['n_b']}; "
                                         f"difference {r['d'] * 100:+.1f} points, p {r['p']:.4f}. "
                                         + ("Passes." if g["passes"] else "Does not pass: " + "; ".join(g["fails"]))),
                                costs="none (index direction only)"),
                   holdout_read=True, verdict="inconclusive" if g["passes"] else "rejected", notes="Research only."))
    for s in ("R72", "LIQ"):
        r, g = a["t3"][s], d["tests"][f"T3-{s}"]
        write(dict(id=f"r209-t3-{s.lower()}", title=f"R209 T3: the direction model as a skip filter on today's {s}", family="ml",
                   **{k: com[k] for k in ("date", "doc", "data_version", "instruments")},
                   period=dict(design="2024-11-21..2026-03-31 (walk-forward predictions)", holdout="2026-04-01..2026-10-05, read once"),
                   features=com["features"] + ["buy the option; today's rules (V5); 1 lot"],
                   hypothesis=f"skipping {s} trades whose side the model gives P < t (t chosen on design) beats today's rule (R202's gate)",
                   model_or_rule=f"skip when P(side) < {r['t']}", settings=com["settings"], prereg=com["prereg"], variants_tried=4,
                   results=dict(summary=(f"design: {r['windows']['design']['trades']} tr, Rs {r['windows']['design']['net']:,} (today Rs "
                                         f"{r['base']['design']['net']:,}), {r['changed']['design']} removed; holdout: {r['windows']['holdout']['trades']} tr, "
                                         f"Rs {r['windows']['holdout']['net']:,} (today Rs {r['base']['holdout']['net']:,}), {r['changed']['holdout']} removed. "
                                         + ("Passes." if g["passes"] else "Does not pass: " + "; ".join(g["fails"]))),
                                costs="dated Zerodha charges, R17 fills, 1 lot"),
                   holdout_read=True, verdict="inconclusive" if g["passes"] else "rejected", notes="Research only; nothing in the app changes."))


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(report())
    entries()
    print("report.md and entries written")
