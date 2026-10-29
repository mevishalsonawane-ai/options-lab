"""Writes research/experiments/entries/r133-*.yaml (185 variants)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r133 as T3  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"


def main():
    d = json.loads((T3.RESULTS / "design.json").read_text())["variants"]
    h = json.loads((T3.RESULTS / "holdout_seen.json").read_text())["variants"]
    for v in T3.VARIANTS:
        s, H = d[v], h[v]
        fails = [k for k, ok in s.get("gates", {}).items() if not ok] if s.get("n") else ["no trades"]
        if H.get("n", 0) < 50:
            fails.append("seen_n")
        if not H.get("avg_net", -1) > 0:
            fails.append("seen_net")
        summ = (f"design (2023 - 15 Mar 2026) {s.get('n', 0)} kept trades of {s.get('base', {}).get('n', 0)}, win "
                f"{s.get('win', 0):.0%}, avg net Rs {s.get('avg_net', 0):,.0f} (2x Rs {s.get('avg_net_x2', 0):,.0f}), "
                f"total Rs {s.get('total', 0):,.0f}, max drawdown Rs {s.get('max_dd', 0):,.0f}; p vs random subsets "
                f"{s.get('p_subsets', 1):.3f}, BH q (185) {s.get('q', 1):.3f}. Seen holdout period: {H.get('n', 0)} "
                f"kept trades, avg net Rs {H.get('avg_net', 0):,.0f}. Fails: {', '.join(fails) or 'none'}.")
        e = dict(id=f"r133-{v.lower()}", title=f"R133 {v}: {s.get('label', v)}", family="patterns-indicators",
                 date="2026-10-29", doc="research/HUNT_R133_ICT_NN_EXITS.md",
                 data_version="Drive lake copied 29 Oct 2026; R131 entries; R132 exits; R123 N1 walk-forward views",
                 period=dict(design="folds F1-F4: 2023-01-01..2026-03-15",
                             holdout="2026-04-01..2026-10-19 (already read for these entries: context only)"),
                 instruments=list(T3.K.UNDS), prereg="research/hunt/r133_ict_nn_exits/PREREG.md", holdout_read=True,
                 features=["signal source: the index spot (R131 entries); filter: R123's walk-forward network view; "
                           "exits on the index or the option premium (wick to wick)"],
                 hypothesis="R123's network filter plus a stop / profit lock makes an R131 ICT entry pay",
                 model_or_rule="research/hunt/r133_ict_nn_exits/r133.py", settings=f"PREREG.md {v}; 1 lot",
                 variants_tried=185, verdict="passed" if not fails else "rejected",
                 results=dict(summary=summ, costs="Costs('dated') + Fills('liq'), 1 lot; also 2x slippage"))
        (ENTRIES / f"r133-{v.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("wrote", len(T3.VARIANTS))


if __name__ == "__main__":
    main()
