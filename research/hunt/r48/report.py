"""Prints the R48 tables (markdown) from results/design.json and results/holdout.json.

    python3 research/hunt/r48/report.py
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def rule(c: dict) -> str:
    t, s, tm = c["exit"]
    side = "call if up, put if down" if c["dir"] == "with" else "put if up, call if down"
    return (f"{c['und']} {c['itm']}-ITM, {c['sig']} ({side}), DTE {c['dte']}, {c['time'][:2]}:{c['time'][2:]}, "
            f"+{t:.0%} / {'no stop' if s is None else f'-{s:.0%}'} / {tm if tm == 'eod' else f'{tm} min'}"
            f"{'' if c['vix'] == 'all' else ', VIX ' + c['vix']}"
            f"{'' if c['ev'] == 'all' else ', skip event days'}")


def row(x: dict) -> str:
    return (f"{x['n']} | {x['win']:.1%} | {x['net']:+,.0f} | {x['avg_win']:,.0f} / {x['avg_loss']:,.0f} | "
            f"{x['worst_trade']:,.0f} | {x['worst_day']:,.0f} | {x['max_dd']:,.0f}")


def main():
    d = json.loads((HERE / "results" / "design.json").read_text())
    hp = HERE / "results" / "holdout.json"
    h = json.loads(hp.read_text()) if hp.exists() else None
    print("| # | rule | design: trades | win | net Rs | avg win / avg loss | worst trade | worst day | max DD |")
    print("|---|---|---|---|---|---|---|---|---|")
    for k, c in enumerate(d["candidates"], 1):
        print(f"| {k} | {rule(c['cfg'])} | {row(c['full'])} |")
    if h:
        print()
        print("| # | holdout: trades | win | net Rs | avg win / avg loss | worst trade | worst day | max DD | p_twin | q | "
              "doubled ticks net | gates failed |")
        print("|---|---|---|---|---|---|---|---|---|---|---|---|")
        for k, r in enumerate(h["candidates"], 1):
            x = r["holdout"]
            dt = r["doubled_ticks"]
            failed = ", ".join(g for g, v in r["gates"].items() if not v) or "none (PASSED)"
            print(f"| {k} | {row(x)} | {r['p_twin']:.3f} | {r['q']:.3f} | "
                  f"{dt.get('net', 0):+,.0f} | {failed} |")
        print()
        print("| # | 3 Nov 2025 - 26 Apr 2026 | 27 Apr 2026 - | worst week | worst month | twin mean net | bootstrap p |")
        print("|---|---|---|---|---|---|---|")
        for k, r in enumerate(h["candidates"], 1):
            a, b = list(r["split"].values())
            x = r["holdout"]
            f = lambda s: f"{s['n']} tr, {s['net']:+,.0f}, win {s['win']:.0%}" if s["n"] else "-"  # noqa: E731
            print(f"| {k} | {f(a)} | {f(b)} | {x.get('worst_week', 0):,.0f} | {x.get('worst_month', 0):,.0f} | "
                  f"{(r['twin_mean'] or 0):+,.0f} | {r['p_zero']:.3f} |")
    w = d["walk_forward"][0]
    print(f"\nWalk-forward: picked on {w['pick']} ({w['n_eligible']} eligible, {w['picked']} picked), tested {w['test']}: "
          f"{w['trades']} trades, win {w['win']:.1%}, net Rs {w['net']:+,.0f}")
    for p in w["per"]:
        print(f"  - {rule(p['cfg'])}: picked on {p['pick_n']} tr, win {p['pick_win']:.0%}, net {p['pick_net']:+,.0f} -> "
              f"test {p['n']} tr, win {(p['win'] or 0):.0%}, net {p['net']:+,.0f}")
    print(f"\nconfigs {d['n_configs']:,}; eligible {d['n_eligible']:,}")


if __name__ == "__main__":
    main()
