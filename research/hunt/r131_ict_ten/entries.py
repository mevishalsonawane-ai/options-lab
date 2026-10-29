"""Writes research/experiments/entries/r131-*.yaml (one per variant) from results/design.json, holdout.json and
combined.json. Run after tables.py."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r131 as R  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"


def fails(v: str, d: dict, h: dict | None, c: dict) -> list:
    g = R.GATES
    out = [k for k, ok in d.get("design_gates", {}).items() if not ok] if d.get("n") else ["no trades"]
    if h is not None:
        if h.get("n", 0) < g["holdout_n"]:
            out.append("holdout_n")
        if not h.get("avg_net", -1) > 0:
            out.append("holdout_net")
        # the 65% win-rate rule is for Jarvis's own strategies only (Boss, 29 Oct; PREREG N3)
    if c.get("n") and (c["max_dd"] <= g["dd"] or c["worst_month"] <= g["month"]):
        out.append("account")
    return out


def main():
    des = json.loads((R.RESULTS / "design.json").read_text())
    hp = R.RESULTS / "holdout.json"
    hol = json.loads(hp.read_text()) if hp.exists() else None
    comb = json.loads((R.RESULTS / "combined.json").read_text())
    for v, cfg in R.VARIANTS.items():
        d, c = des["variants"][v], comb[v]
        h = hol["variants"][v] if hol else None
        f = fails(v, d, h, c)
        summ = (f"design {d.get('n', 0)} trades, win {d.get('win', 0):.0%}, avg net Rs {d.get('avg_net', 0):,.0f} "
                f"(2x slippage Rs {d.get('avg_net_x2', 0):,.0f}), total Rs {d.get('total', 0):,.0f}, max drawdown "
                f"Rs {d.get('max_dd', 0):,.0f}; p vs random twins {d.get('p_twins', 1):.3f}, BH q (30 variants) "
                f"{d.get('q', 1):.3f}; index points {d.get('avg_pts', 0):+.1f} a trade.")
        if h is not None:
            summ += (f" Holdout (read once) {h.get('n', 0)} trades, win {h.get('win', 0):.0%}, avg net Rs "
                     f"{h.get('avg_net', 0):,.0f}, total Rs {h.get('total', 0):,.0f}.")
        summ += f" Years positive 2022-26: {c.get('years_pos', 0)} of 5."
        summ += (" Fails: " + ", ".join(f) + ".") if f else " Passes every gate."
        e = dict(
            id=f"r131-{v.lower()}",
            title=f"R131 {R.STRATS[cfg['s']]} {v}: {cfg['label']}",
            family="patterns-indicators",
            date="2026-10-29",
            doc="research/HUNT_R131_ICT_TEN.md",
            data_version="Drive lake copied 29 Oct 2026 (index_1m, options_rolling_1m WEEK + MONTH), lakeclean steps 1-3",
            period=dict(design="2021-08-01..2026-03-15 (walk-forward folds 2023, 2024, 2025, 2026 Q1; no fitting)",
                        holdout="2026-04-01..2026-10-19 (read once)"),
            instruments=list(R.K.UNDS),
            prereg="research/hunt/r131_ict_ten/PREREG.md",
            holdout_read="2026-10-29" if hol else None,
            features=["signal source: the index spot (wick-based swings, sweeps, CHoCH, FVG, CISD, order blocks, "
                      "fib levels); buys the ATM option of the nearest non-expiry-day expiry; stops and targets on "
                      "index wicks"],
            hypothesis=f"Boss's ICT video (29 Oct): {R.STRATS[cfg['s']]} as stated makes money on index options",
            model_or_rule="research/hunt/r131_ict_ten/r131.py",
            settings=f"PREREG.md {v}; 1 lot at today's lots; one trade at a time per index",
            variants_tried=30,
            verdict="passed" if not f else "rejected",
            results=dict(summary=summ, costs="Costs('dated') + Fills('liq'), 1 lot; also 2x slippage"),
        )
        (ENTRIES / f"r131-{v.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120,
                                                                         allow_unicode=True))
    print("wrote", len(R.VARIANTS), "entries")


if __name__ == "__main__":
    main()
