"""R176 report (Amendment A): results/report.md from results/year.json and results/decision.json, with the plain-English
summary (results/summary.md, written by hand after reading the tables) on top.

    python research/hunt/r176_step_trail_500/r176report.py
"""
from __future__ import annotations

import json

import r176cfg as K

ARMS_ALL = K.ARMS + ("ALL",)
NAME = dict(K.ARM_NAME, ALL="All three arms")
MON = {"01": "Jan", "02": "Feb", "03": "Mar", "04": "Apr", "05": "May", "06": "Jun", "07": "Jul", "08": "Aug",
       "09": "Sep", "10": "Oct", "11": "Nov", "12": "Dec"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def mname(m: str) -> str:
    return f"{MON[m[5:]]} {m[2:4]}"


def variants(arm: str) -> tuple:
    return ("A", "S") if arm == "LIQ" else K.VARIANTS


HEAD = ("| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | "
        "worst day | worst month | max drawdown |\n|---|---|---|---|---|---|---|---|---|---|")


def stat_row(arm: str, v: str, s: dict) -> str:
    m, wm = s["mix"], s["worst_month"]
    return (f"| {NAME[arm]} | {v} | {s['trades']} | {pct(s['win'])} | **{rs(s['net'])}** | {rs(s['avg_win'])} / "
            f"{rs(-s['avg_loss'] if s['avg_loss'] else 0)} | {m['stop500']} / {m['step']} / {m['other']} ({m['own_stop']}) | "
            f"{rs(s['worst_day'])} | {mname(wm['month'])} {rs(wm['net'])} | {rs(-s['max_dd'] if s['max_dd'] else 0)} |")


def month_table(per: dict, lots: int, months: tuple) -> list:
    out = ["| arm | variant | " + " | ".join(mname(m) for m in months) + " | total |",
           "|---|---|" + "---|" * (len(months) + 1)]
    for a in ARMS_ALL:
        for v in variants(a):
            s = per[f"{v}@{lots}"][a]
            out.append(f"| {NAME[a]} | {v} | " + " | ".join(rs(s["months"][m]) for m in months) + f" | {rs(s['net'])} |")
    return out


def build(b: bool = False) -> str:
    """b=False: Amendment A (Rs 500 entry stop) -> report.md with the summary on top; b=True: Amendment B (Rs 1,000
    entry stop) -> report_b.md."""
    r = json.loads((K.RESULTS / ("yearb.json" if b else "year.json")).read_text())
    dfile = K.RESULTS / ("decision_b.json" if b else "decision.json")
    dec = json.loads(dfile.read_text()) if dfile.exists() else {}
    summ = K.RESULTS / "summary.md"
    if b:
        out = ["# R176 Amendment B: Rs 1,000 a lot initial stop, step trail unchanged (tables)\n",
               "The plain-English summary for both rules is at the top of report.md. 'S' below = the Rs 1,000 entry "
               "stop + the step trail; the entry-stop exit column is this rule's Rs 1,000 stop.\n"]
    else:
        out = [summ.read_text().rstrip() + "\n" if summ.exists() else "# R176 report\n"]
    out.append("## Window, data and checks\n")
    out.append(f"- Window (Amendment A, Boss 15 Nov: \"Test on only last year of data\"): **20 Oct 2025 - 19 Oct 2026**, "
               f"{r['year']['sessions']} sessions; early part {r['early']['sessions']}, final {r['final']['sessions']}, "
               f"last 6 months {r['six']['sessions']}. The lake ends on 19 Oct 2026.")
    pl = []
    for a in K.ARMS:
        c = r["parity"][a]
        t = f"{K.ARM_NAME[a]} {c['trades']} / {rs(c['net'])} (R172 {c['published'][0]} / {rs(c['published'][1])})"
        if "picker" in c:
            p = c["picker"]
            t += f", picker {p['trades']} / {rs(p['net'])} (R172 {p['published'][0]} / {rs(p['published'][1])})"
        pl.append(t)
    out.append("- Parity, today's rules at 1 lot, 1 Apr - 13 Oct 2026: " + "; ".join(pl) + ". The engine with the rule "
               "off priced every candidate exactly as R101.")
    out.append("- Signal source: the index for every arm (Liquidity: the index's liquidity levels; R72: Donchian 20 breaks "
               "on 4 of the 5 indices' 5-minute bars; R51: Donchian 20 on the index's 5-minute bars, kept when Supertrend "
               "(10, 3) agrees). Only the option's stop changes.")
    out.append("- Costs: dated Zerodha charges on the whole quantity; R17 slippage (from the 5-minute volume per position). "
               "Every stop of the rule is sold as a stop (x0.999 minus twice the half-spread; a gap fills at the minute's "
               "open), not at its level.")
    out.append("- Honesty: Apr - Oct 2026 has been read by many studies (R51 - R173); the early part (Oct 2025 - Mar 2026) "
               "lies inside the years R51's design used. One year is a short sample. The pickers were fitted on trades up "
               "to Mar 2026, so picker numbers in the early part are in-sample.")
    out.append("- Variants: A = today's rules; S = + Rs 500 step trail (per lot); B = A + R151 picker; B+S = picker + step "
               "trail (Liquidity has no picker).\n")
    if dec:
        out.append("## Gates (Amendment A; S vs A at the same lots)\n")
        out.append(f"BH counts m = {dec['_m']['total']} tests ({dec['_m']['prior']} earlier + {dec['_m']['r176']} R176).\n")
        out.append(f"| arm @ lots | passed | twin p | BH q (m = {dec['_m']['total']:,}) | q within R176 | failed gates |"
                   "\n|---|---|---|---|---|---|")
        for a, n in K.TESTS:
            g = dec[f"{a}@{n}"]
            out.append(f"| {K.ARM_NAME[a]} @ {n} | {'yes' if g['passed'] else '**no**'} | {g['twin_p']} | {g['q_all']} | "
                       f"{g['q_r176']} | {'; '.join(g['fails']) or '-'} |")
        tw = r.get("twins", {})
        out.append("\nTwins changed (trades the rule closed earlier than A): " + ", ".join(
            f"{a}@{n} {tw[f'{a}@{n}']['changed']} (twin mean {rs(tw[f'{a}@{n}']['mean'])})" for a, n in K.TESTS) + ".\n")
    months = K.months_between(*K.span_of("year"))
    for lots in K.LOTS:
        out.append(f"## {lots} lot{'s' if lots > 1 else ''}\n")
        for p in K.YEAR_PERIODS:
            out.append(f"### {K.PERIOD_NAME[p]}\n")
            out.append(HEAD)
            for a in ARMS_ALL:
                for v in variants(a):
                    out.append(stat_row(a, v, r[p][f"{v}@{lots}"][a]))
            out.append("")
        out.append(f"### Month by month, the whole year ({lots} lot{'s' if lots > 1 else ''})\n")
        out += month_table(r["year"], lots, months)
        out.append("")
        sh = r["year"][f"S@{lots}"]
        out.append("Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): "
                   + ", ".join(f"{K.ARM_NAME[a]} {pct(sh[a]['rupee_stop_share'])}" for a in K.ARMS) + ".\n")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    if (K.RESULTS / "yearb.json").exists():
        (K.RESULTS / "report_b.md").write_text(build(True))
    (K.RESULTS / "report.md").write_text(build())
    print((K.RESULTS / "report.md").read_text()[:3000])
