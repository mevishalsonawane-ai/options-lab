"""Writes results/report.md and results/summary.md from results/selection_<IDX>.json, final_<IDX>.json, decision.json,
validation.json and data_manifest_<IDX>.json (every index read so far).

    python research/hunt/r185_absorption_year/r185report.py

Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
sys.path.insert(0, str(HERE))
import r185 as RN  # noqa: E402

NAME = {"ABS": "Absorption", "EXH": "Exhaustion"}
PNAME = {"early": "early (20 Oct 2025 - 31 Mar 2026)", "final": "final (1 Apr - 19 Oct 2026)",
         "six": "last 6 months (20 Apr - 19 Oct 2026)", "year": "year (20 Oct 2025 - 19 Oct 2026)"}


def rs(x):
    if x is None:
        return "-"
    return f"Rs -{abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def pct(x):
    return "-" if x is None else f"{x:.0%}"


def load(name):
    p = RES / name
    return json.loads(p.read_text()) if p.exists() else None


def main():
    dec = load("decision.json") or {}
    val = load("validation.json") or {}
    read = [u for u in RN.INDICES if (RES / f"final_{u}.json").exists()]
    selected = [u for u in RN.INDICES if (RES / f"selection_{u}.json").exists()]
    L = ["# R185 report: absorption and exhaustion on two years of 1-minute data", "",
         "*16 Nov 2026. Pre-registered in `PREREG.md` (committed and pushed before any number). Research only; nothing in "
         "the app changes.*", "",
         "**Signal source:** " + RN.SIGNAL_SOURCE + ". Dhan has no index futures minute history for the year (expired "
         "futures are not served) and **no second-level history at all** (`/charts/intraday` with interval 1s returns "
         "nothing; `/charts/rollingoption` accepts 1, 5, 15 and 60 minutes only), so this is the brief's labelled fallback: "
         "**option-volume context (OVC)**. BVC infers the side of each minute's volume from the index's own price change, "
         "so it cannot see heavy selling that does not move the price - the heart of absorption. These rules are R184's "
         "definitions on estimated flow, not true order flow.", "",
         "**Trade:** 1 lot of the ATM option (nearest expiry: weekly for NIFTY / SENSEX; for BANKNIFTY, FINNIFTY and "
         "MIDCPNIFTY the nearest is the monthly since Nov 2024), bought at the open of the minute after the signal; -15% "
         "premium stop, breakeven (fill + charges) from the minute after +10%, index stop beyond the extreme, 15 / 30-minute "
         "time stop, 15:14 (15:04 on expiry). Real dated charges, liquidity slippage (2x shown).", "",
         f"**Indices read:** {', '.join(read) or 'none yet'} (order fixed: {', '.join(RN.INDICES)}). BH m = "
         f"{RN.M_TOTAL:,} (4,512 after R184 + 320 variants + 20 tests), q ranked over the {dec.get('_m', {}).get('tests_read', 0)} "
         "tests read so far.", ""]
    S = ["# R185 summary: absorption and exhaustion on a full year (16 Nov 2026)", "",
         "Signal: the index's own 1-minute price with the ATM-2..+2 options' volume as BVC context (OVC; no futures "
         "volume history, no second-level history at Dhan). Trade: 1 lot ATM option, R184's exits. Selection 20 Nov 2024 - "
         "19 Oct 2025 (two halves must beat zero and the random twins), final 20 Oct 2025 - 19 Oct 2026 read once.", "",
         "| setup, index | chosen version | passed selection | year trades | win | net (2x slip) | a trade | twins a trade "
         "| twin p | BH q | worst month | max DD | verdict |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    L += ["## Data", ""]
    for u in selected:
        m = load(f"data_manifest_{u}.json")
        sel = load(f"selection_{u}.json")
        L.append(f"- **{u}**: {sel['data']['days_total']} trading days 20 Nov 2024 - 13 Nov 2026 with >= 300 index minutes "
                 f"(dropped: {', '.join(sel['data']['dropped']) or 'none'}); strike step {sel['data']['steps']}; "
                 f"selection halves H1 {sel['halves']['H1'][2]} days, H2 {sel['halves']['H2'][2]} days.")
        if m:
            miss = sum(p["near_atm2_missing_minutes"] for p in m["per_day"])
            L.append(f"  Gap fetch from Dhan (20 Oct - 13 Nov 2026): {m['days']} days ({m['first']} - {m['last']}; short: "
                     f"{', '.join(m['short_days']) or 'none'}), {m['option_rows']:,} option rows (ATM-10..+10, CE / PE), "
                     f"{m['index_rows']:,} index minutes, {m['empty_series_days']} empty series-days, {miss} missing "
                     f"near-ATM (+-2) option minutes. Raw files: `{m['drive']}`.")
        if u in val:
            o = val[u]["overall"]
            L.append(f"  Dhan vs the phone's 1-second recordings ({val[u]['days']} days, 15 Oct - 13 Nov 2026): "
                     f"{o['matched']:,} of {o['phone_minutes']:,} option-minutes matched; median |diff| Rs {o['median_abs']}, "
                     f"95th pct Rs {o['p95_abs']}; {pct(o['within_1pct'])} within 1%, {pct(o['within_5pct'])} within 5%, "
                     f"{pct(o['within_rs1'])} within Rs 1; correlation {o['corr']}. (The phone records the nearest expiry, "
                     "so this also confirms Dhan's expiry code 1 is the nearest.)")
    L.append("")
    for u in selected:
        sel = load(f"selection_{u}.json")
        F = load(f"final_{u}.json")
        L += [f"## {u}", "", "### Selection (20 Nov 2024 - 19 Oct 2025)", ""]
        for key, c in sel["cells"].items():
            setup = key.split()[0]
            ch = c["choice"]
            v = c["variants"]
            pos = sum(r["net"] > 0 for r in v)
            L += [f"**{NAME[setup]}**: {len(v)} versions, {pos} with net > 0, **{c['n_eligible']} eligible** (both halves "
                  f">= 30 trades, net > 0 and above the twins). Chosen: `{ch['vid']}` "
                  f"({'passed the selection' if ch['passed_selection'] else 'FALLBACK - nothing passed the selection'}): "
                  f"{ch['summary']['n']} trades, win {pct(ch['summary']['win'])}, {rs(ch['summary']['net'])} "
                  f"(H1 {ch['H1']['n']} trades {rs(ch['H1']['net'])}, mean {rs(ch['H1']['mean'])} vs twins "
                  f"{rs(ch['H1']['twin_mean'])}; H2 {ch['H2']['n']} trades {rs(ch['H2']['net'])}, mean {rs(ch['H2']['mean'])} "
                  f"vs twins {rs(ch['H2']['twin_mean'])}); descriptive twin p {ch['twins_descriptive']['p']:.3f} "
                  "(biased by the choice).", "",
                  "| version | trades | net | H1 trades / net / mean / twins | H2 trades / net / mean / twins | eligible |",
                  "|---|---|---|---|---|---|"]
            for r in sorted(v, key=lambda r: -r["net"]):
                L.append(f"| {r['vid']} | {r['n']} | {rs(r['net'])} | {r['H1']['n']} / {rs(r['H1']['net'])} / "
                         f"{rs(r['H1']['mean'])} / {rs(r['H1']['twin_mean'])} | {r['H2']['n']} / {rs(r['H2']['net'])} / "
                         f"{rs(r['H2']['mean'])} / {rs(r['H2']['twin_mean'])} | {'yes' if r['eligible'] else ''} |")
            L.append("")
        if not F:
            L += ["Final not read yet.", ""]
            continue
        L += ["### Final year (20 Oct 2025 - 19 Oct 2026, read once)", "",
              f"Read at {F['read_at']} after `FINAL_READ_{u}` ({F['final_read_flag']}); {F['final_days'][2]} days.", ""]
        for key, c in F["cells"].items():
            setup = key.split()[0]
            y, tw, pw = c["year"], c["twins"], c["power"]
            L += [f"**{NAME[setup]} `{c['vid']}`** - verdict: **{c['verdict']}**", "",
                  f"- Year: {y['n']} trades on {y.get('days_traded', 0)} days ({c['signals']} signals; unpriced "
                  f"{c['unpriced'] or 'none'}), win {pct(y['win'])}, {rs(y['net'])} (2x slippage {rs(y['net2'])}), mean "
                  f"{rs(y['mean'])} a trade (avg win {rs(y['avg_win'])}, avg loss {rs(y['avg_loss'])}), worst day "
                  f"{rs(y['worst_day'])}, worst month {rs(y['worst_month']['net'])} ({y['worst_month']['month']}), max "
                  f"drawdown {rs(y['max_dd'])}.",
                  f"- Random twins (1,000 sets, same minute / side / stop distance on other days): mean {rs(tw.get('mean'))} "
                  f"a trade (10-90%: {rs(tw.get('p10'))} to {rs(tw.get('p90'))}); p {c['p_twin']:.3f}, BH q "
                  f"{c['q_twin']:.2f}. Mean > 0 (day bootstrap): p {c['p_zero']:.3f}, q {c['q_zero']:.2f}.",
                  f"- Smallest edge detectable (80% power): {rs(pw['mde_5pct'])} a trade at 5%, {rs(pw['mde_bh'])} at the BH "
                  "level.",
                  f"- By side: calls {y['by_side'].get('L', {}).get('n', 0)} ({rs(y['by_side'].get('L', {}).get('net'))}), "
                  f"puts {y['by_side'].get('S', {}).get('n', 0)} ({rs(y['by_side'].get('S', {}).get('net'))}). By exit: "
                  + ", ".join(f"{k} {e['n']} ({rs(e['net'])})" for k, e in sorted(y["by_exit"].items())) + ".",
                  f"- Gates: {'passed' if c['gates']['passed'] else 'failed - ' + '; '.join(c['gates']['fails'])}.", "",
                  "| period | trades | win | net | net 2x | mean | twins mean | worst month | max DD |",
                  "|---|---|---|---|---|---|---|---|---|"]
            for p, x in c["periods"].items():
                L.append(f"| {PNAME[p]} | {x['n']} | {pct(x['win'])} | {rs(x['net'])} | {rs(x['net2'])} | {rs(x['mean'])} | "
                         f"{rs(x['twin_mean'])} | {rs(x['worst_month']['net'])} | {rs(x['max_dd'])} |")
            rc = c["recent"]["summary"]
            L += ["", f"- Recent weeks (20 Oct - 13 Nov 2026, {c['recent']['days']} days, descriptive): {rc['n']} trades, "
                  f"win {pct(rc['win'])}, {rs(rc['net'])}.", ""]
            S.append(f"| {NAME[setup]} {u} | {c['vid']} | {'yes' if c['passed_selection'] else 'no (fallback)'} | {y['n']} | "
                     f"{pct(y['win'])} | {rs(y['net'])} ({rs(y['net2'])}) | {rs(y['mean'])} | {rs(tw.get('mean'))} | "
                     f"{c['p_twin']:.3f} | {c['q_twin']:.2f} | {rs(y['worst_month']['net'])} | {rs(y['max_dd'])} | "
                     f"{c['verdict']} |")
    not_run = [u for u in RN.INDICES if u not in read]
    tail = ["", f"- Indices not read yet: {', '.join(not_run) or 'none'} (same frozen rules; their tests count in m = "
            f"{RN.M_TOTAL:,} either way).",
            "- Nothing in the app changes. Full write-up: `results/report.md`."]
    S += tail
    L += ["## What this does not show", "",
          "- True absorption (aggressive volume that fails to move price) needs real buy / sell volume; Dhan has neither "
          "futures minute volume for past years nor any second-level history. The phone's own recorder (R182 / R184) is the "
          "only source of tick-rule flow and covers weeks, not a year.",
          "- One lot, one index at a time; no portfolio of indices."] + tail
    (RES / "report.md").write_text("\n".join(L) + "\n")
    (RES / "summary.md").write_text("\n".join(S) + "\n")
    print("\n".join(S))


if __name__ == "__main__":
    main()
