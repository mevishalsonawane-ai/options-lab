"""R19c post-hoc descriptions (after the one holdout read; NOT gated, NOT evidence): points per lot, the day lock on top of
the ORB family switched off (P5 against P1), and how much of a red day its 3 worst trades make.

    PYTHONPATH=. python3 -m research.hunt.r19c.posthoc      # writes results/posthoc.md
"""
from __future__ import annotations

from . import run as R

LOTS = [("BANKNIFTY", 15, "Feb - Nov 2024"), ("BANKNIFTY", 30, "Nov 2024 - Jun 2025 (and the app's lot today)"),
        ("BANKNIFTY", 35, "Jun 2025 - Feb 2026 in the release"), ("NIFTY", 25, "Apr - Nov 2024"), ("NIFTY", 75, "Nov 2024 - Dec 2025"),
        ("NIFTY", 65, "from 31 Dec 2025"), ("MIDCPNIFTY", 120, "today's trade")]


def worst3(trades):
    """Red days of the trades: how many, and the share of the day's loss its 3 worst trades made (median)."""
    by: dict = {}
    for t in trades:
        by.setdefault(t["day"], []).append(t["net"])
    red = {d: v for d, v in by.items() if sum(v) < 0}
    shares = sorted(min(1.0, -sum(sorted(v)[:3]) / -sum(v)) if sum(v) < 0 else 0 for v in red.values())
    big = sum(1 for v in red.values() if sum(sorted(v)[:3]) <= sum(v))
    return len(by), len(red), (shares[len(shares) // 2] if shares else None), big


def main():
    D = R.Data()
    ctrl = {a: R.run_arm(D, a, None) for a in R.ARMS}
    L = ["# R19c post-hoc (after the holdout read; descriptive only, not gated)", "",
         "## Rs 1,000 a lot in option points", "", "| index | lot | when | Rs 1,000 | Rs 500 | Rs 300 | Rs 700 |", "|---|---|---|---|---|---|---|"]
    for u, lot, when in LOTS:
        L.append(f"| {u} | {lot} | {when} | {1000 / lot:.2f} | {500 / lot:.2f} | {300 / lot:.2f} | {700 / lot:.2f} |")
    L += ["", "## The day lock on top of the ORB family switched off (P5 against P1)", "",
          "| period | rule | days | green | net | worst day | max DD |", "|---|---|---|---|---|---|---|"]
    p1, _ = R.portfolio(ctrl, "P1")
    p5, _ = R.portfolio(ctrl, "P5")
    for per in ("design", "holdout"):
        for name, tr in (("P1", p1), ("P5", p5)):
            s = R.stats(R.in_period(tr, per))
            L.append(f"| {per} | {name} | {s['days']} | {R.pct(s['green'])} | {R.rs(s['net'])} | {R.rs(s['worst_day'])} | {R.rs(s['max_dd'])} |")
        up, down, p = R.sign_test(R.daily(R.in_period(p1, per)), R.daily(R.in_period(p5, per)))
        L.append(f"| {per} | P5 vs P1 sign test | {up} up / {down} down | p {p:.3f} (post hoc) | | | |")
    L += ["", "## Red days and their 3 worst trades (plain rules)", "",
          "| portfolio | period | traded days | red days | median share of the red day's loss from its 3 worst trades | red days the 3 worst alone exceed |",
          "|---|---|---|---|---|---|"]
    p0, _ = R.portfolio(ctrl, "P0")
    for name, tr in (("P0 everything", p0), ("P1 ORB family off", p1)):
        for per in ("design", "holdout"):
            n, red, share, big = worst3(R.in_period(tr, per))
            L.append(f"| {name} | {per} | {n} | {red} | {R.pct(share)} | {big} |")
    (R.RESULTS / "posthoc.md").write_text("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
