"""R182 report: SUMMARY (the plain-English part, written after the one run) followed by the tables built
from results/results.json. python research/hunt/r182_delta_study/r182report.py. Plain-English notes, 15 Nov 2026."""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
ARMS = ("Liquidity", "R51", "R72", "all three (descriptive)")
RULES = (("P", "primary: 5-minute delta opposes"), ("V1", "V1: strong opposition (beyond the day's typical)"),
         ("V2", "V2: 15-minute delta opposes"), ("V3", "V3: absorption against the side, last 5 minutes"))


def rs(x):
    if x is None:
        return "-"
    s = f"{abs(x):,.0f}"
    return ("Rs -" if x < 0 else "Rs ") + s


def pc(x):
    return "-" if x is None else f"{x:.0f}%"


def f3(x):
    return "-" if x is None or x != x else f"{x:+.3f}"


def summ(s):
    return f"{s['n']} | {pc(s['win'])} | {rs(s['net'])} | {rs(s['avg'])}"


def cell(s):
    return f"{s['n']}, {pc(s['win'])}, {rs(s['net'])}, {rs(s['avg'])}"


def build(r: dict) -> str:
    L = []
    L.append("## Tables (from results/results.json)\n")
    L.append(f"Run {r['run_at']}; PREREG.md SHA-256 {r['prereg_sha256'][:16]}...; {len(r['days'])} order-flow days "
             f"({r['days'][0]} - {r['days'][-1]}); BH m = {r['m_total']:,}.\n")
    L.append("### Pre-registered tests\n")
    L.append("| test | what | p | BH q (m = %s) | q within R182 |" % f"{r['m_total']:,}")
    L.append("|---|---|---|---|---|")
    for t in r["tests"]:
        p = "-" if t["p"] is None else f"{t['p']:.4f}"
        L.append(f"| {t['id']} | {t['what']} | {p} | {t['q']:.3f} | {t['q_within']:.3f} |")
    L.append("")
    L.append("### Part A: delta and the index future's price (1-second file, 20 days, minute closes 09:30-15:14)\n")
    L.append("| index | same-minute rho (95% CI) | quote rule same-minute rho | primary: D5 -> next 15 min rho (95% CI) | "
             "p (day bootstrap) | p (circular shift) | minutes | next-15-min return after D5 > 0 / < 0 (bp, n) |")
    L.append("|---|---|---|---|---|---|---|---|")
    for u, a in r["part_a"].items():
        sm, q, p = a["same_minute"], a["same_minute_quote_rule"], a["primary"]
        sr = a["sign_rule"]
        L.append(f"| {u} | {f3(sm['r'])} ({f3(sm['lo'])}, {f3(sm['hi'])}) | {f3(q['r'])} | {f3(p['r'])} ({f3(p['lo'])}, "
                 f"{f3(p['hi'])}) | {p['p']:.3f} | {p['p_shift']:.3f} | {p['n']:,} | {sr['pos_mean_bp']:+.2f} ({sr['pos_n']:,}) / "
                 f"{sr['neg_mean_bp']:+.2f} ({sr['neg_n']:,}) |")
    L.append("")
    L.append("Minute-file (TickZone) cross-check on its overlap days, same statistics:\n")
    L.append("| index | days | same-minute rho (95% CI) | D5 -> next 15 min rho (95% CI) | minutes |")
    L.append("|---|---|---|---|---|")
    for u, a in r["part_a"].items():
        m = a.get("minute_file")
        if m:
            L.append(f"| {u} | {m['days']} | {f3(m['same_minute']['r'])} ({f3(m['same_minute']['lo'])}, {f3(m['same_minute']['hi'])}) | "
                     f"{f3(m['primary']['r'])} ({f3(m['primary']['lo'])}, {f3(m['primary']['hi'])}) | {m['primary']['n']:,} |")
    L.append("")
    L.append("Primary by time of day (rho, 95% CI, minutes):\n")
    L.append("| index | " + " | ".join(next(iter(r["part_a"].values()))["time_of_day"].keys()) + " |")
    L.append("|---|---|---|---|")
    for u, a in r["part_a"].items():
        L.append(f"| {u} | " + " | ".join(f"{f3(v['r'])} ({f3(v['lo'])}, {f3(v['hi'])}), {v['n']:,}" for v in a["time_of_day"].values()) + " |")
    L.append("")
    L.append("Descriptive grid: rank correlation of each feature at the minute close with the next h minutes' futures "
             "return (95% day-bootstrap interval; * = interval excludes 0). Not tests.\n")
    for u, a in r["part_a"].items():
        L.append(f"**{u}**\n")
        L.append("| feature | 1 min | 5 min | 15 min | 30 min |")
        L.append("|---|---|---|---|---|")
        feats = sorted({k.split("->")[0] for k in a["grid"]}, key=lambda k: list(a["grid"]).index(k + "->1m"))
        for f in feats:
            cells = []
            for h in (1, 5, 15, 30):
                g = a["grid"][f"{f}->{h}m"]
                star = "*" if (g["lo"] > 0 or g["hi"] < 0) else ""
                cells.append(f"{f3(g['r'])}{star} ({f3(g['lo'])}, {f3(g['hi'])})")
            L.append(f"| {f} | " + " | ".join(cells) + " |")
        L.append("")
    L.append("### Part B: the arms' paper trades (net = rupees after dated charges, 1 trade as the arm sized it)\n")
    for g in ARMS:
        b = r["part_b"][g]
        L.append(f"**{g}**: all {cell(b['all'])} (n, win, net, average); labelled {b['labelled']['n']}, "
                 f"no label {b['unlabelled']['n']}; points {b['points_all']:+,.1f}; average {b['pct_avg_all']:+.1f}% of the premium.\n"
                 if b["all"]["n"] else f"**{g}**: no trades.\n")
        L.append("| rule | eligible (labelled) | kept, all: n, win, net, avg | skipped: n, win, net, avg | kept net of the eligible: "
                 "rule / random twins mean (95%) | twin p |")
        L.append("|---|---|---|---|---|---|")
        for k, name in RULES:
            x = b[k]
            tw = x["twins"]
            twc = "-" if tw.get("twin_kept_mean") is None else (f"{rs(tw['kept_obs'])} / {rs(tw['twin_kept_mean'])} "
                                                                f"({rs(tw['twin_kept_lo'])}, {rs(tw['twin_kept_hi'])})")
            p = "-" if tw.get("p") is None else f"{tw['p']:.3f}"
            L.append(f"| {name} | {x['eligible']} | {cell(x['kept'])} | {cell(x['skipped'])} | {twc} | {p} |")
        L.append("")
        L.append("| delta at entry | n | win | net | average |")
        L.append("|---|---|---|---|---|")
        for k, s in b["groups"].items():
            L.append(f"| {k} | {summ(s)} |")
        L.append("")
    L.append("### Trades per arm per day (rebuilt from the paper orders)\n")
    days = sorted({d for a in r["counts"].values() for d in a["per_day"]})
    L.append("| arm | " + " | ".join(d[5:] for d in days) + " | total | with futures flow | labelled |")
    L.append("|---|" + "---|" * (len(days) + 3))
    for arm, c in r["counts"].items():
        L.append(f"| {arm} | " + " | ".join(str(c["per_day"].get(d, 0)) for d in days) +
                 f" | {c['trades']} | {c['with_futures_flow']} | {c['labelled']} |")
    L.append("")
    L.append("By underlying: " + "; ".join(f"{arm} {c['per_und']}" for arm, c in r["counts"].items()) + ". Same option bought "
             "in the same second by R51 and R72: " + ", ".join(f"{arm} {c['same_entry_as_other_arm']}" for arm, c in r["counts"].items()) + ".\n")
    L.append("### Data quality (futures, 1-second file)\n")
    L.append("| index | day | seconds | minutes recorded | thin (<20 s) | missing | gaps >= 5 s | longest gap (s) | seconds with a trade | absorption minutes |")
    L.append("|---|---|---|---|---|---|---|---|---|---|")
    for u, rows in r["quality"].items():
        for q in rows:
            L.append(f"| {u} | {q['day']} | {q['seconds']:,} | {q['minutes']} | {q['thin']} | {q['missing']} | {q['gap5']} | "
                     f"{q['max_gap_s']:,} | {q['print_secs']:,} | {q['absorb']} |")
    L.append("")
    L.append("### The two recorders on the same minutes (minutes with >= 50 s in the minute file and >= 20 s in the 1-second file)\n")
    L.append("| index | day | minutes | 1-second volume / minute volume | delta equal | delta sign agrees | corr (minute vs 1-second delta) | corr quote rule vs minute | corr quote rule vs 1-second |")
    L.append("|---|---|---|---|---|---|---|---|---|")
    for u, rows in r["crosscheck"].items():
        for x in rows:
            L.append(f"| {u} | {x['day']} | {x['minutes']} | {x['vol_ratio_1s_over_1m']:.2f} | {x['delta_equal']*100:.1f}% | "
                     f"{x['sign_agree']*100:.0f}% | {x['corr']:+.2f} | {x['corr_quote_vs_1m']:+.2f} | {x['corr_quote_vs_1s']:+.2f} |")
    L.append("")
    ph = RES / "posthoc.json"
    if ph.exists():
        P = json.loads(ph.read_text())
        L.append("### Post hoc (after the run, descriptive): the three signings on the same minutes\n")
        L.append("Each signing's minute delta against its own file's same-minute price change, the 9 overlap days, minutes "
                 "with >= 50 s in the minute file and >= 20 s in the 1-second file (r182posthoc.py).\n")
        L.append("| index | minutes | minute file, tick rule: rho (95% CI), sign agrees | 1-second file, tick rule | 1-second file, quote rule |")
        L.append("|---|---|---|---|---|")
        for u, x in P.items():
            sc = lambda k: f"{f3(x[k]['r'])} ({f3(x[k]['lo'])}, {f3(x[k]['hi'])}), {x[k]['sign_agree']*100:.0f}%"  # noqa: E731
            L.append(f"| {u} | {x['minute_tick']['n']:,} | {sc('minute_tick')} | {sc('sec_tick')} | {sc('sec_quote')} |")
        L.append("")
    return "\n".join(L)


SUMMARY = """# R182: order-flow delta - what it is, what it did on the phone's recordings, and a delta skip rule on the three arms

*15 Nov 2026. Research only; nothing in the app changes. Pre-registered (PREREG.md, committed and pushed before any
forward return or trade outcome was computed). Run once (see "How it was run" below).*

## For Boss, in plain words

**What delta is.** Every trade has a buyer and a seller. "Delta" tries to say which side was in a hurry: volume that
traded when the price ticked UP is counted as aggressive buying, volume when it ticked DOWN as aggressive selling, and
delta = buying minus selling in a minute (or 5, or 15). CVD is the running total through the day. Zerodha does not say
who the aggressor was, so the phone ESTIMATES it from the price ticks (the "tick rule"). This is order-flow delta, not the
option's delta (the Greek).

**Which delta to trust.** The phone records delta twice, made by two different pieces of the app:
- the minute file (`orderflow_1m`, TickZone) counts ALL the volume and signs each second by its price change;
- the 1-second file (`orderflow-*.csv.gz`, the live order-flow read the app shows and FlowGate uses) signs each tick
  packet, but deliberately DROPS big prints that did not move the price (over 5x the usual packet size) and re-sent old
  packets. It holds only 65-83% of the volume, which is why the two disagree on about 97% of minutes (any missing
  print changes the number). The footprint is made from the same packets as the 1-second file, so it matches it.
- Neither is the truth (no aggressor flag). The pre-registered test of a signing is how tightly it tracks the same
  minute's price change. On the same minutes (9 days; 3,210 NIFTY and 3,212 BANKNIFTY minutes) the **minute file's delta
  tracks price best (rank correlation +0.45 / +0.48)**, the 1-second file's quote-rule delta next (+0.42 / +0.40), and the
  **1-second file's tick-rule delta - the one the app's live read uses - worst (+0.27 / +0.27)**. So the minute file is
  the more trustworthy delta; the 1-second one is noisier. The study had to use the 1-second file (pre-registered)
  because the minute file stops on 28 Oct, before most of the arms' trades.

**What delta did on this data (Part A, 20 days, about 6,100-6,400 minutes per index).**
- **It moves WITH price in the same minute** - as expected, partly by construction: rank correlation +0.24 NIFTY,
  +0.28 BANKNIFTY, +0.13 FINNIFTY, +0.18 MIDCPNIFTY (all intervals well above 0); +0.44 / +0.49 in the minute file.
- **It does NOT tell where price goes next.** The pre-registered test (5-minute delta -> the next 15 minutes' futures
  move) gave NIFTY -0.007 (95% interval -0.055 to +0.034, p 0.76), BANKNIFTY -0.028 (-0.075 to +0.020, p 0.24),
  FINNIFTY -0.064 (-0.121 to -0.005, p 0.031), MIDCPNIFTY -0.006 (-0.067 to +0.048, p 0.84). BH q = 1.0 for all (m =
  4,073). If anything the sign is slightly NEGATIVE (after buying, price drifted a little lower), the opposite of the
  usual reading, and FINNIFTY's futures hardly trade (its delta is zero most minutes).
- In numbers you can feel: after a positive 5-minute NIFTY delta the next 15 minutes moved +0.73 basis points on average
  (3,329 minutes), after a negative one +0.93 bp (3,007) - both tiny, both up (the rally), no edge.
- Every other version (1, 5, 15 minute delta, 15-minute CVD slope, CVD divergence, book imbalance, absorption) against
  1, 5, 15 and 30 minutes ahead: all between -0.07 and +0.05; 6 of 104 cells have an interval excluding 0, about what
  chance alone gives. By time of day the first hour leans slightly positive (+0.07 / +0.08, intervals include 0) and the
  afternoon slightly negative (BANKNIFTY 13:30-15:15 -0.10).
- The minute file on its 9 days says the same: NIFTY +0.016, BANKNIFTY -0.100 (-0.185 to -0.041).

**The three arms (Part B): their real paper trades, 21 Oct - 13 Nov 2026 (16 trading days with trades).**
179 trades rebuilt from the paper orders (every buy matched to its sells; none left open, none orphaned); all fall on
order-flow days; 149 are on an index whose future is recorded (the 30 SENSEX trades have no flow); 129 had a non-zero
5-minute futures delta before entry. The pre-registered rule: **skip the entry when the underlying future's 5-minute
delta is against the trade** (CE wants buying, PE wants selling). Net = rupees after real dated charges on the app's own
paper fills.

| arm | trades | win | net | rule skips | skipped: win, net | rule keeps | kept: win, net | random-skip twins p | BH q |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | 9 | 44% | Rs -3,635 | 4 | 25%, Rs -3,842 | 5 | 60%, Rs 207 | 0.219 | 1.0 |
| R51 Donchian 20 | 94 | 53% | Rs -38,448 | 14 | 64%, Rs 2,596 | 80 | 51%, Rs -41,044 | 0.523 | 1.0 |
| R72 Breadth Donchian | 76 | 49% | Rs -29,597 | 9 | 67%, Rs -1,149 | 67 | 46%, Rs -28,447 | 0.420 | 1.0 |
| all three (descriptive) | 179 | 51% | Rs -71,680 | 27 | 59%, Rs -2,395 | 152 | 49%, Rs -69,284 | 0.421 | - |

- **Delta agrees vs disagrees at entry** (all three arms): agrees 102 trades, win 58%, average Rs -71; disagrees 27,
  win 59%, average Rs -89. No real difference. For R51 the "against delta" trades were actually the better ones (64% won,
  +Rs 185 a trade vs -Rs 141). Liquidity went the hoped-for way (the 4 skipped lost Rs 3,842) but 4 trades is nothing.
- The secondary versions (only strong opposition; the 15-minute delta; absorption against the side) were no better;
  absorption happened before only 2 of 179 entries.
- The arms are breakout systems, so most entries already go WITH the delta (102 agree, 27 disagree): a delta filter
  would rarely act.
- The trades with NO usable delta did worst (zero delta, mostly thin FINNIFTY futures: 11 trades, average Rs -2,123; no
  label, mostly SENSEX: 39 trades, average Rs -993). That is the instruments, not delta; descriptive only.

**This cannot pass the repo's gates** (>= 40 holdout trades, every period positive, BH): 179 trades over 16 days, R51 and
R72 sharing 24 identical entries. Read everything here as "no sign of a delta edge yet", not as a proof.

**Data problems.**
- The two delta recorders disagree by design (above); the app's live read uses the noisier one.
- The 1-second file drops 17-35% of the futures volume (big prints at an unchanged price).
- One print can dominate a day: BANKNIFTY 21 Oct, 29,010 units in one second signed "buy" set the day's CVD.
- FINNIFTY futures barely trade (prints in 53-362 seconds a day); MIDCPNIFTY is thin and gappy from 3 Nov (up to 262 thin
  minutes on 12 Nov).
- Missing futures minutes: 9 Nov 82, 6 Nov 56, 4 Nov 37, 11 Nov 35-36, 13 Nov 29-30, 2 Nov 24, 23 Oct 18-19; longest gap
  32 minutes (4 Nov). No recording on 20 Oct or 10 Nov.
- The footprint (absorption) exists only for NIFTY and BANKNIFTY; absorption was rare (0-3 minutes a day).
- The minute file (the better delta) was exported only to 28 Oct.

**Verdict.** No. Delta, as the phone records it, moves with price but did not predict the next 1-30 minutes on any of
the four index futures, and skipping the arms' entries against the 5-minute delta did not beat skipping at random (p 0.22
/ 0.52 / 0.42, q = 1.0). By the pre-registered recommendation rule (an arm's primary "helps", or Part A positive on 2+
indices) **a new shadow delta label on these arms is NOT worth adding now**. Liquidity's entries are already logged with
the live flow read by FlowGate's shadow log (52 Liquidity signals in the exported log), so its forward record grows
anyway.

**Next steps.**
1. App side (separate task, not done here): look at the 1-second recorder's tick-rule delta, which tracks price worst of
   the three signings - its big-print filter drops a fifth to a third of the volume, and the live order-flow read (and
   any CONFIRM) rests on it.
2. Keep exporting the minute file (`orderflow_1m`) so a re-run can use the better delta.
3. Re-run R182 unchanged (the code takes any number of days) at about 60 trading days (roughly 600 arm trades).

## How it was run

- PREREG.md committed and pushed first (60703f992). The first run attempt stopped with an error in Part A's
  descriptive grid (absorption is constant on FINNIFTY and MIDCPNIFTY, which have no footprint, so a bootstrap had no
  values) before anything was written or printed; the guard was added with a test and the run done once more. All numbers
  are from that completed run.
- One post-hoc, descriptive check was added after the run: the three signings compared on the SAME minutes
  (`r182posthoc.py`, `results/posthoc.json`); it is the "which delta to trust" comparison above.
- The pre-registered "helps" bar compares the skipped trades' average with the kept trades'. For R72 the skipped average
  (Rs -128) is above all kept trades' (Rs -425) but below the labelled kept trades' (Rs +18); either way its twin p (0.42)
  misses the 0.20 bar, so no arm "helps".
- Per-trade rows stay out of git; `results/results.json` holds aggregates only.
- Signal source: the index near future's order flow (1-second tick-rule delta); the arms' own entries, exits and option
  fills are unchanged.
"""


def main():
    r = json.loads((RES / "results.json").read_text())
    (RES / "report.md").write_text(SUMMARY.rstrip() + "\n\n" + build(r))


if __name__ == "__main__":
    main()
