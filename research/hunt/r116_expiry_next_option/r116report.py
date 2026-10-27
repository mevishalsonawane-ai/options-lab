"""R116 report: the tables of HUNT_R116_EXPIRY_NEXT_OPTION.md (results/tables.md) and the experiment entries.

    python research/hunt/r116_expiry_next_option/r116report.py
"""
from __future__ import annotations

import json

import yaml

import r116cfg as K

WNAME = {"S": "same-day option", "N": "NEXT expiry's option", "M": "later monthly (stand-in)",
         "S_on_N_days": "same-day option, on N's days", "N_monthly_days": "next expiry, monthly-expiry days",
         "N_weekly_days": "next expiry, weekly-only expiry days"}


def rs(x) -> str:
    return "-" if x is None else f"Rs {x:,.0f}"


def cell(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return f"{s['trades']} / {s['days']} d / {s['win']:.0%} / **{rs(s['net'])}** / {rs(s['worst_day'])}"


def load():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    m = json.loads((K.RESULTS / "datamap.json").read_text())
    return d, f, dec, m


def tables() -> str:
    d, f, dec, _ = load()
    L = ["Cells: trades / trading days / win rate / **net** / worst day. 1 lot, after charges and slippage. Skipping "
         "(today) is 0 trades, Rs 0 in every period.", ""]
    L += ["### Each arm on its index's expiry days", "",
          "| arm | what is bought | design | walk-forward (Apr 25 - Mar 26) | final (Apr - 13 Oct 26) | final twin p |",
          "|---|---|---|---|---|---|"]
    for a in K.ARMS:
        for w in ("N", "S", "S_on_N_days", "N_monthly_days", "N_weekly_days"):
            tw = f"{f['arms'][a][w]['twin']['p']:.3f}" if w in ("N", "S") else ""
            L.append(f"| {K.ARM_NAME[a]} | {WNAME[w]} | {cell(d['arms'][a][w]['design'])} | "
                     f"{cell(d['arms'][a][w]['wf'])} | {cell(f['arms'][a][w]['final'])} | {tw} |")
    L += ["", "### The next expiry's option, by index", "", "| arm | index | design | walk-forward | final |",
          "|---|---|---|---|---|"]
    for a in K.ARMS:
        for u in K.UNDS:
            ss = [d["arms"][a]["N"]["design"]["by_index"].get(u), d["arms"][a]["N"]["wf"]["by_index"].get(u),
                  f["arms"][a]["N"]["final"]["by_index"].get(u)]
            if any(ss):
                L.append(f"| {a} | {u} | " + " | ".join(cell(s) if s else "0 trades" for s in ss) + " |")
    L += ["", "### What one lot costs (median entry premium x lot, design) and the 2% risk cap", "",
          "| arm | index | same-day option | next expiry's option | next: risk to a 15% stop | account for 1 lot at 2% |",
          "|---|---|---|---|---|---|"]
    for a in K.ARMS:
        sd, nd = d["arms"][a]["S"]["design"], d["arms"][a]["N"]["design"]
        for u in (nd.get("premium_by_index") or {}):
            p = nd["premium_by_index"][u]
            risk = p * K.STOP_SHARE + K.CHARGES_PER_LOT
            L.append(f"| {a} | {u} | {rs((sd.get('premium_by_index') or {}).get(u))} | {rs(p)} | {rs(risk)} | "
                     f"{rs(risk / K.RISK_PCT)} |")
    L += ["", "### Reported only: the lake's later monthly (13-56 days out) on NIFTY / SENSEX weekly expiry days", "",
          "| arm | design | walk-forward | final | same-day option on those days, final |", "|---|---|---|---|---|"]
    for a in K.ARMS:
        L.append(f"| {K.ARM_NAME[a]} | {cell(d['arms'][a]['M']['design'])} | {cell(d['arms'][a]['M']['wf'])} | "
                 f"{cell(f['arms'][a]['M']['final'])} | {cell(f['arms'][a]['S_on_M_days']['final'])} |")
    L += ["", "### Gate (PREREG section 5)", "", f"BH over {dec['_m']['m_all']:,} tests (every earlier logged test + R116's "
          f"{dec['_m']['r116']}).", "", "| test | passed | final twin p | q (all tests) | q (R116 alone) | why not |",
          "|---|---|---|---|---|---|"]
    for t in K.TESTS:
        g = dec[t]
        L.append(f"| {t} | {'yes' if g['passed'] else 'no'} | {g['p']:.3f} | {g['q_all']:.3g} | {g['q_own']:.3g} | "
                 f"{'; '.join(g['fails'])} |")
    return "\n".join(L) + "\n"


def entries():
    d, f, dec, _ = load()
    for t, (a, w) in K.TESTS.items():
        g = dec[t]
        s = {p: (d if p != "final" else f)["arms"][a][w][p] for p in ("design", "wf", "final")}
        what = "the next expiry's 1-ITM option" if w == "N" else "the same-day (expiring) 1-ITM option"
        summ = (f"{K.ARM_NAME[a]} on its index's expiry days buying {what}, against skipping (Rs 0). "
                + "; ".join(f"{p} {s[p]['trades']} trades, win {s[p]['win'] or 0:.0%}, net Rs {s[p]['net']:,}, worst day "
                            f"Rs {s[p]['worst_day']:,}" for p in ("design", "wf", "final"))
                + f". Final random-entry twin p {g['p']:.3f}; BH q {g['q_all']:.3g} over {dec['_m']['m_all']:,} tests "
                  f"(q {g['q_own']:.3g} within R116). Fails: {'; '.join(g['fails'])}.")
        e = dict(
            id=f"r116-{t.lower()}", title=f"R116 {t}: {K.ARM_NAME[a]}, expiry days, {what}", family="calendar-events",
            date="2026-10-27", doc="research/HUNT_R116_EXPIRY_NEXT_OPTION.md",
            hypothesis=(f"On its index's own expiry day (skipped today), {K.ARM_NAME[a]}'s unchanged index signal buying "
                        f"{what} with today's exits makes money: net > 0 in design, walk-forward and final, >= 40 final "
                        f"trades, beats a random-entry twin at BH q <= 0.10 over every logged test."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK + MONTH) "
                          "copied 27 Oct 2026; research-data release nifty_prev / nifty_year / banknifty_*_year_wide "
                          "(next expiry); R51 Amendment A clean stores to 13 Oct 2026"),
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-13 read once by R116 (expiry days never read by the arms' studies)"),
            instruments=list(K.UNDS),
            features=["signal source: the index's own bars (R72 / R51 5-minute, R52 the 09:15-09:19 bar against "
                      "yesterday's value area, Liquidity its book bars); only the option bought changes, stops / locks on "
                      "its premium"],
            model_or_rule=f"{t}: {K.ARM_NAME[a]} trades its expiry days, buying {what}",
            settings="research/hunt/r116_expiry_next_option/r116cfg.py TESTS",
            prereg="research/hunt/r116_expiry_next_option/PREREG.md", variants_tried=1,
            results=dict(summary=summ, costs="dated Zerodha / exchange charges, R17 fills, 1 lot"),
            holdout_read=True, verdict="rejected",
            notes="Signal on the underlying. Nothing changes live or on paper; expiry days stay skipped.")
        (K.ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    m = {p: (d if p != "final" else f)["arms"] for p in ("design", "wf", "final")}
    summ = "; ".join(f"{a} " + ", ".join(f"{p} {m[p][a]['M'][p]['trades']} / Rs {m[p][a]['M'][p]['net']:,}"
                                         for p in ("design", "wf", "final")) for a in K.ARMS)
    e = dict(id="r116-standin", title="R116 reported only: NIFTY / SENSEX expiry days buying the later monthly (stand-in)",
             family="calendar-events", date="2026-10-27", doc="research/HUNT_R116_EXPIRY_NEXT_OPTION.md",
             hypothesis="Descriptive only (PREREG section 3, world M): not the contract Boss asked about, never gated.",
             data_version="Drive lake options_rolling_1m MONTH copied 27 Oct 2026; R51 Amendment A clean stores",
             period=dict(design="2024-11-20..2026-03-31", holdout="2026-04-01..2026-10-13 read once by R116"),
             instruments=["NIFTY", "SENSEX"],
             features=["signal source: the index's own bars; the monthly 1-ITM option (13-56 days out) is bought"],
             model_or_rule="world M of r116study.py", settings="research/hunt/r116_expiry_next_option/r116cfg.py WORLDS",
             prereg="research/hunt/r116_expiry_next_option/PREREG.md", variants_tried=None,
             results=dict(summary="Trades / net: " + summ + ".", costs="dated Zerodha / exchange charges, R17 fills, 1 lot"),
             holdout_read=True, verdict="not-a-test", notes="Reported only; nothing changes.")
    (K.ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    t = tables()
    (K.RESULTS / "tables.md").write_text(t)
    entries()
    print(t)
