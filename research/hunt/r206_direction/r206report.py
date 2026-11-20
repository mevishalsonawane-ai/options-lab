"""R206 report (PREREG.md section 7): results/report.md and the experiment log entries, from results/recent.json, old.json
and decision.json. Plain-English notes, 20 Nov 2026.

    python3 research/hunt/r206_direction/r206report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r206cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DATA = ("Index-only (no option file read): the dhan-data branch's 1-minute index bars, 8 Oct 2021 - 5 Oct 2026. \"Right\" = "
        "the index's close 15 minutes after the signal is beyond its close at the signal, the signal's way.")


def pc(x) -> str:
    return "n/a" if x is None else f"{x * 100:.1f}%"


def pts(x) -> str:
    return "n/a" if x is None else f"{x * 100:+.1f}"


def rate(r: dict) -> str:
    return "n/a" if r["rate"] is None else f"{pc(r['rate'])} ({pc(r['lo'])} - {pc(r['hi'])}; {r['n']:,} signals, {r['days']} days)"


def part1_rows(o: dict, r: dict) -> list:
    out = ["| source | era | signals | right after 15 min (95% interval) | right after 30 min | average move its way at 15 min |",
           "|---|---|---|---|---|---|"]
    for src in K.SOURCES:
        for era, x in (("old", o), ("recent", r)):
            v = x["part1"][src]
            out.append(f"| {K.SRC_NAME[src]} | {K.ERA_NAME[era]} | {v['signals']:,} | {pc(v['right15']['rate'])} "
                       f"({pc(v['right15']['lo'])} - {pc(v['right15']['hi'])}) | {pc(v['right30']['rate'])} | "
                       f"{v['avg_move15_bp']} hundredths of a percent |")
    return out


def split_rows(o: dict, r: dict, field: str, title: str, keys: list) -> list:
    out = [f"| source | era | " + " | ".join(str(k) for k in keys) + " |", "|---|---|" + "---|" * len(keys)]
    for src in K.SOURCES:
        for era, x in (("old", o), ("recent", r)):
            v = x["part1"][src][field]
            cells = []
            for k in keys:
                c = v.get(str(k))
                if c is None:
                    cells.append("-")
                elif "right15" in c:
                    cells.append(f"{pc(c['right15']['rate'])} ({c['right15']['n']:,})")
                else:
                    cells.append(f"{pc(c['rate'])} ({c['n']:,})")
            out.append(f"| {K.SRC_NAME[src]} | {era.upper()} | " + " | ".join(cells) + " |")
    return out


def report() -> str:
    o = json.loads((K.RESULTS / "old.json").read_text())
    r = json.loads((K.RESULTS / "recent.json").read_text())
    d = json.loads((K.RESULTS / "decision.json").read_text())
    n = len(d["more_reliable"])
    out = ["# R206 results: how reliable is the arms' direction call, and is a call other signals agree with more reliable?", "",
           f"Pre-registered in `PREREG.md` before any number. {DATA} OLD era read once "
           f"({K.OLD_FLAG.read_text().strip() if K.OLD_FLAG.exists() else 'n/a'} UTC).", "",
           f"**{n} of {len(K.TESTS)} conditions make the call more reliable**" + (": " + ", ".join(d["more_reliable"]) if n else "") + ".",
           "", "These are signals, not trades (no picker, no one-position rule, no day caps, expiry days included), so the counts "
           "are larger than the arms' trade counts.", "", "## Part 1: how reliable is the call today", ""]
    out += part1_rows(o, r)
    out += ["", "### By year (right after 15 minutes; signals)", ""]
    for src in K.SOURCES:
        ys = {**o["part1"][src]["years"], **r["part1"][src]["years"]}
        out.append(f"- {K.SRC_NAME[src]}: " + "; ".join(f"{y} {pc(v['right15']['rate'])} ({v['right15']['n']:,})" for y, v in ys.items()) + ".")
    out += ["", "### By index (right after 15 minutes; signals)", ""] + split_rows(o, r, "by_index", "index", list(K.UNDS))
    out += ["", "### By entry hour (right after 15 minutes; signals)", ""] + split_rows(o, r, "by_hour", "hour", list(range(9, 16)))
    out += ["", "### By side (right after 15 minutes; signals)", ""] + split_rows(o, r, "by_side", "side", ["C", "P"])
    out += ["", "## Part 2: the eight conditions (A against B, share right after 15 minutes)", "",
            "| id | signals | A | OLD: A right (signals) | OLD: B right (signals) | OLD difference (points) | bootstrap p | OLD years "
            "2022 / 2023 / 2024 | RECENT difference | 30-min difference OLD / RECENT | verdict |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for h, (src, _, what) in K.TESTS.items():
        a, b, g = o["part2"][h], r["part2"][h], d["tests"][h]
        ys = " / ".join(pts(v["d"]) for v in a["years"].values())
        out.append(f"| {h} | {K.SRC_NAME[src]} | {what} | {pc(a['at15']['rate_a'])} ({a['at15']['n_a']:,}) | "
                   f"{pc(a['at15']['rate_b'])} ({a['at15']['n_b']:,}) | {pts(a['at15']['d'])} | {a['at15']['p']:.3f} | {ys} | "
                   f"{pts(b['at15']['d'])} | {pts(a['at30']['d'])} / {pts(b['at30']['d'])} | "
                   f"{'**more reliable**' if g['more_reliable'] else 'not more reliable'} |")
    out += ["", "Why each one fails or passes:", ""]
    for h in K.TESTS:
        g = d["tests"][h]
        out.append(f"- **{h}** ({'more reliable' if g['more_reliable'] else 'not more reliable'}): "
                   f"{'every item holds' if g['more_reliable'] else '; '.join(g['fails'])}. BH q {g['q']:.3g} (m = {d['m_total']:,}; "
                   f"within these 8: {g['q_family']:.3g}).")
    return "\n".join(out) + "\n"


def entries():
    import yaml
    o = json.loads((K.RESULTS / "old.json").read_text())
    r = json.loads((K.RESULTS / "recent.json").read_text())
    d = json.loads((K.RESULTS / "decision.json").read_text())
    common = dict(family="patterns-indicators", date="2026-11-20", doc="research/hunt/r206_direction/results/report.md",
                  data_version="the dhan-data branch of options-lab (Dhan v2, fetched 6 Oct 2026): 1-minute index candles, "
                               "8 Oct 2021 - 5 Oct 2026 (r206data.py); no option data",
                  instruments=list(K.UNDS),
                  features=["signal source: the index only (the arms' own signals on 1-minute index bars; no option file read)"],
                  settings="research/hunt/r206_direction/r206cfg.py; PREREG.md sections 2 - 6",
                  prereg="research/hunt/r206_direction/PREREG.md")
    for h, (src, _, what) in K.TESTS.items():
        a, b, g = o["part2"][h]["at15"], r["part2"][h]["at15"], d["tests"][h]
        e = dict(id=f"r206-{h.lower()}", title=f"R206 {h}: are {K.SRC_NAME[src]} signals right more often with {what}?",
                 **{k: common[k] for k in ("family", "date", "doc")},
                 hypothesis=(f"{K.SRC_NAME[src]} signals with '{what}' see the index go their way after 15 minutes more often than "
                             "the others, on 1 Apr 2022 - 19 Nov 2024 (a lead from the Nov 2024 - Oct 2026 diagnosis for H1 - H4)"),
                 data_version=common["data_version"],
                 period=dict(design="2024-11-21..2026-10-05 (RECENT: where the lead was seen; must agree in sign)",
                             holdout="2022-04-01..2024-11-19 (OLD: the test, read once, results/OLD_READ)"),
                 instruments=common["instruments"], features=common["features"],
                 model_or_rule=f"{h}: A = {what}; B = the rest; right = index move the signal's way after 15 minutes above 0",
                 settings=common["settings"], prereg=common["prereg"], variants_tried=1,
                 results=dict(summary=(f"OLD: A right {a['rate_a'] * 100:.1f}% of {a['n_a']} signals, B {a['rate_b'] * 100:.1f}% of "
                                       f"{a['n_b']}; difference {a['d'] * 100:+.1f} points, day-block bootstrap p {a['p']:.4f}, BH q "
                                       f"{g['q']:.3g} (m = {d['m_total']:,}). RECENT: A {b['rate_a'] * 100:.1f}% of {b['n_a']}, B "
                                       f"{b['rate_b'] * 100:.1f}% of {b['n_b']}; difference {b['d'] * 100:+.1f} points. "
                                       + ("More reliable." if g["more_reliable"] else "Not more reliable: " + "; ".join(g["fails"]))),
                              costs="none (index direction only; no trade is priced)"),
                 holdout_read=True, verdict="inconclusive" if g["more_reliable"] else "rejected",
                 notes="Research only; nothing in the app changes. A direction condition is not a trading rule.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    p = "; ".join(f"{K.SRC_NAME[s]}: OLD {o['part1'][s]['right15']['rate'] * 100:.1f}% of {o['part1'][s]['signals']} signals, RECENT "
                  f"{r['part1'][s]['right15']['rate'] * 100:.1f}% of {r['part1'][s]['signals']}" for s in K.SOURCES)
    e = dict(id="r206-direction-today", title="R206 Part 1: how often the index goes the way the arms' signals say (15 minutes)",
             **{k: common[k] for k in ("family", "date", "doc")},
             hypothesis="none (descriptive: the share of R72, R51 and Liquidity signals after which the index moved their way)",
             data_version=common["data_version"], period=dict(design="2022-04-01..2026-10-05 (two eras; not a test)"),
             instruments=common["instruments"], features=common["features"],
             model_or_rule="r206lib.move: last close before the entry minute against the close 15 (30) minutes later",
             settings=common["settings"], prereg=common["prereg"], variants_tried=None,
             results=dict(summary="Right after 15 minutes - " + p + ".", costs="none (index direction only)"),
             holdout_read=None, verdict="not-a-test", notes="Research only. Signals, not trades; expiry days included.")
    e["family"] = "market-facts"
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(report())
    entries()
    print("report.md and entries written")
