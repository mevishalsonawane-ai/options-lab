"""R172 report: results/report.md from results/r172.json and results/check.json, with the plain-English summary
(results/summary.md, written by hand after reading the tables) on top.

    python research/hunt/r172_2k1k_6months/r172report.py
"""
from __future__ import annotations

import json

import r172cfg as K

MONTH_NAME = {"2026-04": "Apr*", "2026-05": "May", "2026-06": "Jun", "2026-07": "Jul", "2026-08": "Aug", "2026-09": "Sep",
              "2026-10": "Oct*"}
ARMS_ALL = K.ARMS + ("ALL",)
NAME = dict(K.ARM_NAME, ALL="All three arms")


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def stat_row(arm: str, v: str, s: dict) -> str:
    m = s["mix"]
    wm = s["worst_month"]
    return (f"| {NAME[arm]} | {v} | {s['trades']} | {pct(s['win'])} | **{rs(s['net'])}** | {rs(s['avg_win'])} / "
            f"{rs(-s['avg_loss'] if s['avg_loss'] else 0)} | {m['target2k']} / {m['stop1k']} / {m['other']} "
            f"({m['own_stop']} at -15%) | {rs(s['largest_loss'])} | {rs(s['worst_day'])} | "
            f"{MONTH_NAME[wm['month']]} {rs(wm['net'])} | {rs(-s['max_dd'] if s['max_dd'] else 0)} |")


HEAD = ("| arm | variant | trades | win | net | avg win / avg loss | exits: Rs 2k target / Rs 1k stop / other | largest loss | "
        "worst day | worst month | max drawdown |\n|---|---|---|---|---|---|---|---|---|---|---|")


def month_table(r: dict, lots: int) -> list:
    out = ["| arm | variant | " + " | ".join(MONTH_NAME[m] for m in K.MONTHS) + " | total |",
           "|---|---|" + "---|" * (len(K.MONTHS) + 1)]
    for a in ARMS_ALL:
        for v in K.VARIANTS:
            if a == "LIQ" and v.startswith("B"):
                continue
            s = r[f"{v}@{lots}"][a]
            out.append(f"| {NAME[a]} | {v} | " + " | ".join(rs(s["months"][m]) for m in K.MONTHS) + f" | {rs(s['net'])} |")
    return out


def build() -> str:
    r = json.loads((K.RESULTS / "r172.json").read_text())
    chk = json.loads((K.RESULTS / "check.json").read_text())
    meta = r["_meta"]
    summ = K.RESULTS / "summary.md"
    out = [summ.read_text().rstrip() + "\n" if summ.exists() else "# R172 report\n"]
    out.append("## Window, data and checks\n")
    out.append(f"- Window: **{meta['first_session']} to {meta['last_session']}**, {meta['sessions']} sessions (by month: "
               + ", ".join(f"{MONTH_NAME[k]} {v}" for k, v in sorted(meta['sessions_by_month'].items()))
               + "). The lake (copied 14 Nov 2026) ends on 19 Oct 2026; * = part month.")
    out.append(f"- R151 picker: R72 {meta['R72_candidates']} candidate breaks, {meta['R72_scored_below']} scored below 0.4656; "
               f"R51 {meta['R51_candidates']} candidates, {meta['R51_scored_below']} below 0.4469 (skipped, but each still holds "
               "its side and counts in the day's caps, as in the app).")
    lines = []
    for a in K.ARMS:
        c = chk[a]
        t = f"{K.ARM_NAME[a]} {c['trades']} / {rs(c['net'])} (published {c['published'][0]} / {rs(c['published'][1])})"
        if "picker" in c:
            p = c["picker"]
            t += f", picker {p['trades']} / {rs(p['net'])} (published {p['published'][0]} / {rs(p['published'][1])})"
        lines.append(t)
    out.append("- Engine check, today's rules at 1 lot, 1 Apr - 13 Oct 2026, on the stores built to 19 Oct: " + "; ".join(lines)
               + ". On the stores built to 13 Oct every published figure reproduces exactly. " + chk["_difference"])
    out.append("- Signal source: the index for every arm (Liquidity: the index's liquidity levels; R72: Donchian 20 breaks on "
               "4 of the 5 indices' 5-minute bars; R51: Donchian 20 on the index's 5-minute bars, kept when Supertrend (10, 3) "
               "agrees). Only the option's exit changes (a rupee target / stop on the premium).")
    out.append("- Costs: dated Zerodha charges on the whole quantity, R17 slippage (from the 5-minute volume per position; stops "
               "-10 bps and twice the half-spread; a gap through a stop fills at the minute's open); limits fill at their price.")
    out.append("- Not a gate test: this window overlaps the final period (1 Apr - 13 Oct 2026) that R51-R171 read many times, and "
               "R106 / R152 already read rupee targets and stops on these arms there. No twin, no Benjamini-Hochberg: nothing "
               "here is being selected. Variants: A = today's rules; B = A + R151 picker (R72 / R51 only; Liquidity has none, "
               "so its B = A); +2k1k = with Boss's +Rs 2,000 / -Rs 1,000 (before charges, whole position).\n")
    for lots in K.LOTS:
        out.append(f"## {lots} lot{'s' if lots > 1 else ''}\n")
        out.append("Net is after charges and slippage. avg loss is the average losing trade (net <= 0). 'other' exits: the "
                   "arm's own exits (-15% stop shown in brackets, ladder / L25 locks, time stops, index exits, square-off).\n")
        out.append(HEAD)
        for a in ARMS_ALL:
            for v in K.VARIANTS:
                if a == "LIQ" and v.startswith("B"):
                    continue
                out.append(stat_row(a, v, r[f"{v}@{lots}"][a]))
        out.append("")
        out.append(f"Month by month ({lots} lot{'s' if lots > 1 else ''}):\n")
        out += month_table(r, lots)
        out.append("")
        sh = r[f"A+2k1k@{lots}"]
        out.append("How often the Rs 1,000 stop was the tighter one (so it replaced -15%): "
                   + ", ".join(f"{K.ARM_NAME[a]} {pct(sh[a]['rupee_stop_used'])}" for a in K.ARMS)
                   + ". Average net of one exit (after charges and slippage): "
                   + ", ".join(f"{K.ARM_NAME[a]}: Rs 2k target {rs(sh[a]['target2k_avg_net'])}, Rs 1k stop "
                               f"{rs(sh[a]['stop1k_avg_net'])}" for a in K.ARMS)
                   + ". Rs 1,000-stop exits that lost more than Rs 1,000 before charges (stop slippage or a gap): "
                   + ", ".join(f"{K.ARM_NAME[a]} {sh[a]['stop1k_over_1k']} of {sh[a]['mix']['stop1k']} (worst net "
                               f"{rs(sh[a]['stop1k_worst_net'])})" for a in K.ARMS) + ".\n")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(build())
    print((K.RESULTS / "report.md").read_text()[:2500])
