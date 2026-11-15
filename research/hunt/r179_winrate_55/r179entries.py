"""R179 experiment entries: one per variant (V1-V4), each covering R72 and R51."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
LAB = {"V1": "Rs 1,500 rule + R151 picker", "V2": "Rs 1,500 rule + breakeven lock (R162 BE10)",
       "V3": "Rs 1,500 rule + picker + breakeven lock", "V4": "today's rules + R151 picker + breakeven lock (R162 BE10)"}


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    for v in ("V1", "V2", "V3", "V4"):
        parts = []
        for a in ("R72", "R51"):
            s, f, g = r["year"][f"{v}@1"][a], r["final"][f"{v}@1"][a], d[f"{a}:{v}"]
            parts.append(f"{a}: year {s['trades']} trades win {100 * s['win']:.1f}% {rs(s['net'])} (base {rs(g['base_net_year'])}); "
                         f"final win {100 * f['win']:.1f}% {rs(f['net'])}; twin p {g['twin_p']}; failed: {'; '.join(g['fails'])}")
        e = dict(
            id=f"r179-{v.lower()}", title=f"R179 {v}: {LAB[v]} on R72 / R51 for a win rate above 55% (last year, 1 lot)",
            family="trade-management", date="2026-11-15", doc="research/hunt/r179_winrate_55/results/report.md",
            hypothesis="Boss: raise the win rate above 55% while keeping profit; " + LAB[v] + ".",
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                          "stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none fitted here; early part 2025-10-20..2026-03-31 (picker in-sample)",
                        holdout="2026-04-01..2026-10-13 final (clean for the picker; read by many studies); year to 2026-10-19"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the index (entries; R151 picker features at the decision minute); stops on the premium"],
            model_or_rule=LAB[v] + "; PREREG.md section 1", settings="research/hunt/r179_winrate_55/r179study.py",
            prereg="research/hunt/r179_winrate_55/PREREG.md", variants_tried=1,
            results=dict(summary=" | ".join(parts) + ".",
                         costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops)"),
            holdout_read=True, verdict="rejected",
            notes=(f"BH m = {d['_m']['total']}. " + ("R51 V4 passed the win (55.5% / 55.1%) and profit gates but failed BH "
                   "and the account-stop gate; vs the live picker alone it earns about Rs 43k-47k less a year. A candidate "
                   "for a paper / shadow trial, not adopted. " if v == "V4" else "") + "Nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("4 entries")




def main_a(b: bool = False):
    tag = "b" if b else "a"
    cap = "Rs 1,500" if b else "Rs 1,000"
    r = json.loads((RESULTS / f"year_{tag}.json").read_text())
    d = json.loads((RESULTS / f"decision_{tag}.json").read_text())
    parts = []
    for a in ("LIQ", "R72", "R51"):
        s, base = r["year"]["M1@1"][a], r["year"]["A@1"][a]
        parts.append(f"{a}: today {100 * base['win']:.1f}% {rs(base['net'])} -> cap {s['trades']} trades {100 * s['win']:.1f}% "
                     f"{rs(s['net'])}, avg loss {rs(-s['avg_loss'])}, biggest {rs(s['largest_loss'])}"
                     + (f"; failed: {'; '.join(d[a]['fails'])}" if a in d else ""))
    e = dict(
        id=f"r179{tag}-max-loss-{'1500' if b else '1000'}",
        title=f"R179 Amendment {tag.upper()}: {cap} maximum loss (after charges) on picker + breakeven, R72 / R51 / Liquidity",
        family="trade-management", date="2026-11-15", doc="research/hunt/r179_winrate_55/results/report.md",
        hypothesis=("Boss: 'What about 1.5k' (a Rs 1,500 a lot cap)." if b else
                    "Boss: '15% is too much for the stoploss, i want 1k max loss for all the above strategies'."),
        data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                      "stores built to 19 Oct (R106 clean_post)"),
        period=dict(design="none fitted; early part 2025-10-20..2026-03-31 (picker in-sample)",
                    holdout="2026-04-01..2026-10-13 final (read again, data-led); year to 2026-10-19"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: the index (entries; R151 picker features); stop on the premium"],
        model_or_rule=(f"{cap} net stop a lot" + ("" if b else " (and per trade at 2-3 lots)") +
                       " where tighter than -15%, on R151 picker + R162 BE10"),
        settings=f"research/hunt/r179_winrate_55/r179study.py run_a; PREREG.md Amendment {tag.upper()}",
        prereg="research/hunt/r179_winrate_55/PREREG.md", variants_tried=1 if b else 2,
        results=dict(summary=" | ".join(parts) + ".", costs="dated Zerodha charges; R17 fills (stops sold as stops)"),
        holdout_read=True, verdict="rejected",
        notes=(f"BH m = {d['_m']['total']}. " + ("Win rate about 49%, profit about half of today's; worst month at 1 lot "
               "within -Rs 13k on R72 / R51. " if b else "Win rate falls to about 41% and profit by half or more. ")
               + "Nothing in the app changes."),
    )
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"1 entry (Amendment {tag.upper()})")


if __name__ == "__main__":
    main()
    if (RESULTS / "decision_a.json").exists():
        main_a()
    if (RESULTS / "decision_b.json").exists():
        main_a(True)
