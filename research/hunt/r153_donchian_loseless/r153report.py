"""R153 report (results/report.md: results/summary.md on top, then every table) and experiment entries.

    python research/hunt/r153_donchian_loseless/r153report.py            # report
    python research/hunt/r153_donchian_loseless/r153report.py entries    # research/experiments/entries/r153-*.yaml
"""
from __future__ import annotations

import json
import sys

import r153study as S

ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 (+ Supertrend 10, 3)"}


def rs(x) -> str:
    if x is None:
        return ""
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "" if x is None else f"{100 * x:.0f}%"


def _load(name):
    p = S.RESULTS / f"{name}.json"
    return json.loads(p.read_text()) if p.exists() else None


HEAD = ("| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | "
        "exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |\n|---|---|---|---|---|---|---|---|---|---|---|---|")


def row(name, per, s, verdict=""):
    m = s.get("mix", {})
    mix = " / ".join(str(m.get(k, 0)) for k in ("target", "stop2k", "stop", "lock", "other"))
    return (f"| {name} | {per} | {s['trades']} | {pct(s.get('win'))} | {rs(s['net'])} | {rs(s['loss_sum'])} | "
            f"{s['losing_days']} of {s['days']} | {rs(s['worst_day'])} | {rs(-s['max_dd'])} | {rs(s['worst_month'])} | "
            f"{mix} | {verdict} |")


def report() -> str:
    d = {a: _load(f"design_{a}") for a in S.ARMS}
    f = {a: _load(f"final_{a}") for a in S.ARMS}
    p = {a: _load(f"post_{a}") for a in S.ARMS}
    dec = _load("decision") or {}
    pk = _load("picker")
    summ = S.RESULTS / "summary.md"
    out = [summ.read_text().rstrip() + "\n" if summ.exists() else "# R153 report\n", "## Every table\n",
           "Net after dated Zerodha charges and R17 slippage, for the whole position. 'wf' = walk-forward window Apr 2025 - "
           "Mar 2026 (inside design). Sum of losing days = the total of every red day. Twins: final period, 2,000 sets, "
           f"on the sum of losing days. BH q with m = {S.M_TOTAL}. Signal source: the index (unchanged).\n",
           "Rules: " + "; ".join(f"**{k}** {v}" for k, v in S.LABEL.items()) + ".\n"]
    for a in S.ARMS:
        if not d[a]:
            continue
        out.append(f"### {ARM_NAME[a]}\n")
        out.append(f"B0 parity at 1 lot: design {d[a]['parity']['trades']} / {rs(d[a]['parity']['net'])}"
                   + (f", final {f[a]['parity']['trades']} / {rs(f[a]['parity']['net'])}" if f[a] else "")
                   + " (reproduced).\n")
        for n in S.LOTS:
            out.append(f"#### {n} lot{'s' if n > 1 else ''}\n")
            out.append(HEAD)
            for name in ("B0",) + tuple(S.COMBOS):
                k = f"{name}@{n}"
                g = dec.get(f"{a}:{k}")
                v = ""
                if g:
                    v = f"p {g['p']}, q {g['q_all']}; " + ("**pass**" if g["passed"] else "fail: " + "; ".join(g["fails"]))
                out.append(row(f"**{name}**", "design", d[a][k]["design"]))
                out.append(row("", "wf", d[a][k]["wf"]))
                if f[a]:
                    out.append(row("", "final", f[a][k]["final"], v))
                if p[a]:
                    out.append(row("", "post", p[a][k]["post"]))
            out.append("")
            if f[a]:
                tw = "; ".join(f"{c}: " + ", ".join(f"{part} p {x['p']:.3g} (twin mean {rs(x['twin_mean'])})"
                                                    for part, x in f[a][f"{c}@{n}"]["twin"].items())
                               for c in S.COMBOS)
                out.append(f"Twins (final, sum of losing days): {tw}.\n")
    if pk:
        out.append("## Second look: on the trades R151's app pickers take (Apr - Oct 2026)\n")
        out.append("Descriptive only, never gated (the period was read many times; the pickers failed their own gates). "
                   "A skipped break still holds its side, as in the app.\n")
        for a in S.ARMS:
            out.append(f"### {ARM_NAME[a]} + R151 picker\n")
            out.append(HEAD)
            for n in S.LOTS:
                for name in ("B0",) + tuple(S.COMBOS):
                    out.append(row(f"**{name}**", f"{n} lot{'s' if n > 1 else ''}", pk[a][f"{name}@{n}"]))
            out.append("")
    return "\n".join(out) + "\n"


def entries():
    import yaml
    dec = _load("decision")
    ent = S.K2.ROOT / "research" / "experiments" / "entries"
    src = {"R72": "index 5-min bars of all five indices (Donchian 20, 4 of 5)",
           "R51": "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"}
    n = 0
    for a in S.ARMS:
        d, f = _load(f"design_{a}"), _load(f"final_{a}")
        for c in S.COMBOS:
            for lots in S.LOTS:
                k, b = f"{c}@{lots}", f"B0@{lots}"
                g = dec[f"{a}:{k}"]

                def ln(s):
                    return (f"{s['trades']} trades, net {rs(s['net'])}, losing days {rs(s['loss_sum'])}, worst day "
                            f"{rs(s['worst_day'])}, max DD {rs(s['max_dd'])}, worst month {rs(s['worst_month'])}")
                e = dict(
                    id=f"r153-{a.lower()}-{c.lower()}-{lots}lot",
                    title=f"R153 {c} on {ARM_NAME[a]}, {lots} lot{'s' if lots > 1 else ''}: {S.LABEL[c]}",
                    family="trade-management", date="2026-11-07", doc="research/hunt/r153_donchian_loseless/results/report.md",
                    hypothesis=(f"Boss (7 Nov): \"try some combination for less loss\". {S.LABEL[c]} (whole position, net of "
                                "charges) makes the arm lose less than today's rules: smaller sum of losing days, drawdown, "
                                "worst day and worst month in design, walk-forward and final, still net > 0; beats random "
                                f"twins (p < 0.05), BH q <= 0.10 with m = {S.M_TOTAL}; >= 40 final trades, >= 30 differ."),
                    data_version="Drive lake gdrive:IraAlgo-data-lake copied 7 Nov 2026; R51 Amendment A clean stores",
                    period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                                holdout="2026-04-01..2026-10-13 read once by R153 (read by many earlier studies); post 14-19 Oct reported"),
                    instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                    features=[f"signal source: {src[a]} (unchanged); the option's exit and the day's entries change"],
                    model_or_rule=f"{ARM_NAME[a]} with {S.LABEL[c]}, {lots} lot{'s' if lots > 1 else ''}",
                    settings="research/hunt/r153_donchian_loseless/r153study.py COMBOS; r153lib.py; PREREG.md section 2",
                    prereg="research/hunt/r153_donchian_loseless/PREREG.md", variants_tried=len(S.TESTS),
                    results=dict(summary=(f"Design: {ln(d[k]['design'])} (today {ln(d[b]['design'])}). Walk-forward: "
                                          f"{ln(d[k]['wf'])} (today {ln(d[b]['wf'])}). Final: {ln(f[k]['final'])} (today "
                                          f"{ln(f[b]['final'])}); twin p {g['p']}, BH q {g['q_all']}. "
                                          + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                                 costs=f"dated Zerodha charges on the whole {lots}-lot quantity, R17 fills (stop and lock sales "
                                       "-10 bps and 2x the half-spread, gaps at the open)"),
                    holdout_read=True, verdict="paper-only" if g["passed"] else "rejected",
                    notes="Signal on the underlying; nothing changes live.")
                (ent / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    print(n, "entries")


if __name__ == "__main__":
    if sys.argv[1:] == ["entries"]:
        entries()
    else:
        (S.RESULTS / "report.md").write_text(report())
