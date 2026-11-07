"""R152 report: results/report.md from the results JSON (summary at the top from results/summary.md, written by hand
after reading the tables).

    python research/hunt/r152_donchian_2k/r152report.py
"""
from __future__ import annotations

import json

import r152cfg as K


def rs(x) -> str:
    if x is None:
        return ""
    x = round(x)
    s = f"{abs(x):,}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "" if x is None else f"{100 * x:.0f}%"


def _load(name: str):
    p = K.RESULTS / f"{name}.json"
    return json.loads(p.read_text()) if p.exists() else None


def row(name: str, per: str, s: dict, extra: str = "", first: bool = False) -> str:
    m = s.get("mix", {})
    mix = f"{m.get('target', 0)} / {m.get('stop2k', 0)} / {m.get('stop', 0)} / {m.get('other', 0)}" if m else ""
    lab = f"**{name}**" if first else ""
    return (f"| {lab} | {per} | {s['trades']} | {pct(s.get('win'))} | {pct(s.get('be_win'))} | {rs(s['net'])} | "
            f"{rs(s.get('per_session'))} | {rs(s.get('worst_day'))} | {rs(-s['max_dd'] if s.get('max_dd') else 0)} | "
            f"{rs(s.get('ex_top5'))} | {rs(s.get('largest_loss'))} | {mix} | {extra} |")


HEAD = ("| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | "
        "largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|")


def build() -> str:
    des = {a: _load(f"design_{a}") for a in K.ARMS}
    fin = {a: _load(f"final_{a}") for a in K.ARMS}
    post = {a: _load(f"post_{a}") for a in K.ARMS}
    dec = _load("decision") or {}
    pk = _load("picker")
    summ = (K.RESULTS / "summary.md")
    out = [summ.read_text().rstrip() + "\n" if summ.exists() else "# R152 report\n"]
    out.append("## Every table\n")
    out.append("Net after dated Zerodha charges and R17 slippage, for the whole position. Rs 2,000 is for the position at "
               "every lot size. 'wf' = walk-forward window Apr 2025 - Mar 2026 (inside design). Break-even win = average "
               "loss / (average win + average loss) of the rule's own trades. Exit-twin p: final period, 500 random-exit "
               f"twins. BH q counts R152's {len(K.TESTS)} tests plus {K.N_PRIOR} earlier ones (m = "
               f"{len(K.TESTS) + K.N_PRIOR}). Signal source: the index (unchanged); only the option's exit changes.\n")
    for a in K.ARMS:
        d, f, p = des[a], fin[a], post[a]
        if d is None:
            continue
        out.append(f"### {K.ARM_NAME[a]}\n")
        par = d["parity"]
        out.append(f"B0 parity at 1 lot (design): {par['trades']} trades, {rs(par['net'])}; published {par['published']}: "
                   f"{'reproduced' if par.get('reproduced') else 'NOT reproduced'}."
                   + (f" Final: {f['parity']['trades']} trades, {rs(f['parity']['net'])}; "
                      f"{'reproduced' if f['parity'].get('reproduced') else 'NOT reproduced'}." if f else ""))
        if "parity_r51plain" in d:
            pp = d["parity_r51plain"]
            out.append(f" R106's R51 before Supertrend (engine check): {pp['trades']} / {rs(pp['net'])}, "
                       f"{'reproduced' if pp.get('reproduced') else 'NOT reproduced'}.")
        out.append(f" Sessions: design {d['sessions']}, final {f['sessions'] if f else '-'}, post "
                   f"{p['sessions'] if p else '-'}.\n")
        for lots in K.LOTS:
            out.append(f"#### {lots} lot{'s' if lots > 1 else ''}\n")
            out.append(HEAD)
            for name in ("B0",) + K.RULE_IDS:
                k = f"{name}@{lots}"
                g = dec.get(f"{a}:{k}")
                verdict = ""
                if g:
                    verdict = (f"p {g['twin_p']}, q {g['q_all']} (R152 alone {g['q_r152']}); "
                               + ("**pass**" if g["passed"] else "fail: " + "; ".join(g["fails"])))
                out.append(row(name, "design", d[k]["all"], first=True))
                out.append(row("", "wf", d[k]["wf"]))
                if f:
                    out.append(row("", "final", f[k]["all"], verdict if name != "B0" else ""))
                if p:
                    out.append(row("", "post", p[k]["all"]))
            out.append("")
            qs = " ; ".join(f"{name}: " + ", ".join(rs(x) for x in d[f"{name}@{lots}"]["wf_q"])
                            for name in ("B0",) + K.RULE_IDS)
            out.append(f"Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): {qs}.\n")
            share = [f"{name} {pct(d[f'{name}@{lots}']['all'].get('rupee_stop_nearer'))}"
                     for name in ("T2000S", "S2000")]
            out.append(f"Share of design trades whose Rs 2,000 stop was nearer than -15%: {', '.join(share)}.\n")
    if pk:
        out.append("## Second look: only the trades R151's app pickers take (Apr - Oct 2026)\n")
        out.append("Descriptive only, never gated: Apr - Oct 2026 was read many times, and both pickers failed their own "
                   "gates (Boss's 6 Nov exception put them in the app). As the app does, a skipped break still holds its "
                   "side and counts in the day's caps.\n")
        for a in K.ARMS:
            x = pk.get(a)
            if not x:
                continue
            pr = x["parity_r151_walk"]
            out.append(f"### {K.ARM_NAME[a]} + R151 picker\n")
            out.append(f"Parity (R151's walk, skipped breaks removed before the walk, 1 lot): {pr['trades']} trades, "
                       f"{rs(pr['net'])}; published {pr['published']}: "
                       f"{'reproduced' if pr['reproduced'] else 'NOT reproduced'}. Candidates {x['candidates']}, "
                       f"scored below the threshold {x['scored_below']}.\n")
            out.append(HEAD)
            for lots in K.LOTS:
                for name in ("B0",) + K.RULE_IDS:
                    out.append(row(name, f"{lots} lot{'s' if lots > 1 else ''}",
                                   x[f"{name}@{lots}"], first=True))
            out.append("")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(build())
    print((K.RESULTS / "report.md").read_text()[:3000])
