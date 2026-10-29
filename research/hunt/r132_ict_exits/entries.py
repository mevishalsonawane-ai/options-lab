"""Writes research/experiments/entries/r132-*.yaml (40 exit variants + 20 network-filter variants)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r132 as Q  # noqa: E402
import r132nn as NN  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"


def main():
    L = lambda f: json.loads((Q.RESULTS / f).read_text())  # noqa: E731
    d, h, n, nh = L("design.json"), L("holdout_seen.json"), L("nn_design.json"), L("nn_holdout_seen.json")
    rows = [(v, d["variants"][v], h["variants"][v], "exit") for v in Q.VARIANTS]
    rows += [(v, n["variants"][v], nh["variants"][v], "nn") for v in NN.NN_VARIANTS]
    for v, s, H, kind in rows:
        fails = [k for k, ok in s["gates60"].items() if not ok]
        if H.get("n", 0) < Q.GATES["seen_n"]:
            fails.append("seen_n")
        if not H.get("avg_net", -1) > 0:
            fails.append("seen_net")
        p = s.get("p_twins", s.get("p_subsets", 1.0))
        summ = (f"design {s.get('n', 0)} trades, win {s.get('win', 0):.0%}, avg net Rs {s.get('avg_net', 0):,.0f} (2x "
                f"Rs {s.get('avg_net_x2', 0):,.0f}), total Rs {s.get('total', 0):,.0f}, max drawdown Rs "
                f"{s.get('max_dd', 0):,.0f}, worst month Rs {s.get('worst_month', 0):,.0f}; p "
                f"{'vs random entries with the same exits' if kind == 'exit' else 'vs random subsets'} {p:.3f}, "
                f"BH q (60) {s['q60']:.3f}. Seen holdout period: {H.get('n', 0)} trades, avg net Rs "
                f"{H.get('avg_net', 0):,.0f}. Fails: {', '.join(fails) if fails else 'none'}.")
        e = dict(
            id=f"r132-{v.lower()}", title=f"R132 {v}: {s['label']}", family="patterns-indicators", date="2026-10-29",
            doc="research/HUNT_R132_ICT_EXITS.md",
            data_version="Drive lake copied 29 Oct 2026; R131 entries; R123 N1 walk-forward predictions",
            period=dict(design=("2021-08-01..2026-03-15, folds 2023-2026 Q1" if kind == "exit" else
                                "folds F1-F4 only: 2023-01-01..2026-03-15"),
                        holdout="2026-04-01..2026-10-19 (already read for these entries in R131: context only)"),
            instruments=list(Q.K.UNDS), prereg="research/hunt/r132_ict_exits/PREREG.md", holdout_read=True,
            features=["signal source: the index spot (R131 entries); " + (
                "exits on the option premium (wick to wick) or the index" if kind == "exit"
                else "filter: R123's walk-forward network view at the entry")],
            hypothesis="A stop-loss / profit lock, or R123's network, makes the R131 ICT entries pay",
            model_or_rule="research/hunt/r132_ict_exits/" + ("r132.py" if kind == "exit" else "r132nn.py"),
            settings=f"PREREG.md {v}; 1 lot", variants_tried=60, verdict="passed" if not fails else "rejected",
            results=dict(summary=summ, costs="Costs('dated') + Fills('liq'), 1 lot; also 2x slippage"))
        if not fails:
            e["notes"] = "Passed on a holdout period already seen for these entries: needs a forward paper test."
        (ENTRIES / f"r132-{v.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("wrote", len(rows))


if __name__ == "__main__":
    main()
