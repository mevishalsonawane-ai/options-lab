"""R155 report: results/report.md (plain summary for Boss first) and results/tables.md (every pooled test), from
results/decision.json and the saved compact trades ($R155_OUT).

    python research/hunt/r155_scalp_filters/r155report.py
"""
from __future__ import annotations

import json

import numpy as np

import r155cfg as K
import r155study as S

K4 = K.K4
PNAME = {"P1": "P1 design", "P2": "P2 walk-forward", "P3": "P3 final (2nd look)", "P4": "P4 post"}


def rs(x) -> str:
    """Rupees with Indian grouping (R154's format)."""
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "-"
    from r154report import rs as rs4
    return rs4(x)


def pct(x) -> str:
    return "-" if x is None else f"{100 * float(x):.0f}%"


_T: dict = {}


def trades(entry: str, phase: str):
    if (entry, phase) not in _T:
        try:
            _T[(entry, phase)] = S._load(f"trades_{entry}_{phase}")
        except FileNotFoundError:
            _T[(entry, phase)] = None
    return _T[(entry, phase)]


def all_trades(entry: str) -> dict:
    S4 = S.mods()["S4"]
    d, f = trades(entry, "design"), trades(entry, "final")
    return {k: S4.concat([d[k], f[k]]) for k in d}


def pool(f: str, reading: str, p: str, entries=K.ENTRIES) -> dict:
    S4 = S.mods()["S4"]
    parts = []
    for e in entries:
        tr = all_trades(e) if p != "P4" else trades(e, "post")
        if tr is None:
            continue
        lo, hi = S.period_range(p, e)
        parts += [S4.in_range(tr[(f, u, r)], lo, hi) for u in K4.ENTRY_UNDS[e] for r in K.rules_of(reading)]
    return S4.stats(S4.concat(parts))


def filter_table(reading: str) -> list:
    out = [f"| filter | {' | '.join(PNAME[p] + ': trades / win / needed / a trade' for p in ('P1', 'P2', 'P3'))} | a trade vs no filter (P3) |",
           "|---|---|---|---|---|"]
    base3 = pool("F0", reading, "P3")
    for f in ("F0",) + K.FILTERS:
        cells = []
        for p in ("P1", "P2", "P3"):
            s = pool(f, reading, p)
            cells.append(f"{s['trades']:,} / {pct(s['win'])} / {pct(s['be_win'])} / {rs(s['per_trade'])}")
        s3 = pool(f, reading, "P3")
        diff = (s3["per_trade"] or 0) - (base3["per_trade"] or 0) if f != "F0" else None
        out.append(f"| {f} {K.FNAME[f]} | {' | '.join(cells)} | {rs(diff) if diff is not None else '-'} |")
    return out


def decision_table(dec: dict) -> list:
    out = ["| filter | entry | reading | P1 net | P2 net | P3 trades | P3 win / needed | P3 a trade | no filter P3 a trade | "
           "random-skip p | q | verdict |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for f, e, rd in K.TESTS:
        g = dec[f"{f}:{e}:{rd}"]
        s, b = g["stats"], g["f0"]
        verdict = "PASS" if g["passed"] else "fail: " + "; ".join(g["fails"])
        out.append(f"| {f} | {e} | {rd} | {rs(s['P1']['net'])} | {rs(s['P2']['net'])} | {s['P3']['trades']:,} | "
                   f"{pct(s['P3']['win'])} / {pct(s['P3']['be_win'])} | {rs(s['P3']['per_trade'])} | {rs(b['P3']['per_trade'])} | "
                   f"{g['twin_p']} | {g['q']} | {verdict} |")
    return out


def cells() -> tuple:
    """Every filter x entry x index x rule cell: count positive in P1, P2 and P3, and the best 10 by the smallest of the
    three nets (data-mined, descriptive)."""
    S4 = S.mods()["S4"]
    pos = {f: 0 for f in ("F0",) + K.FILTERS}
    best = []
    for e in K.ENTRIES:
        tr = all_trades(e)
        for (f, u, r), a in tr.items():
            nets = []
            for p in ("P1", "P2", "P3"):
                lo, hi = S.period_range(p, e)
                nets.append(float(S4.in_range(a, lo, hi)["net"].sum()))
            if min(nets) > 0:
                pos[f] += 1
                n3 = int(len(S4.in_range(a, K4.FINAL_START, K4.FINAL_END)["net"]))
                best.append((min(nets), f, e, u, r, nets, n3))
    best.sort(reverse=True)
    return pos, best[:10]


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    tests = [k for k in dec if not k.startswith("_")]
    passed = [k for k in tests if dec[k]["passed"]]
    pos, best = cells()
    base = {rd: {p: pool("F0", rd, p) for p in ("P2", "P3")} for rd in ("A", "B")}
    # the best filters in P3 per reading (by net a trade), and how many beat no-filter in both P2 and P3
    ranks = {}
    for rd in ("A", "B"):
        rows = []
        for f in K.FILTERS:
            s2, s3 = pool(f, rd, "P2"), pool(f, rd, "P3")
            rows.append((s3["per_trade"], f, s2, s3))
        rows.sort(reverse=True)
        ranks[rd] = rows
    better_both = {rd: [f for _, f, s2, s3 in ranks[rd] if s2["per_trade"] > base[rd]["P2"]["per_trade"]
                        and s3["per_trade"] > base[rd]["P3"]["per_trade"]] for rd in ("A", "B")}
    pos_final_keys = [k for k in tests if dec[k]["stats"]["P3"]["net"] > 0]
    pos_final = len(pos_final_keys)
    n_beat = sum(1 for k in tests if dec[k]["q"] <= K.GATE_Q)
    minp = min(dec[k]["twin_p"] for k in tests)
    minq = min(dec[k]["q"] for k in tests)
    fam_best = {}
    for fam in ("S/R", "trend", "OI", "wick", "all"):
        fs = [f for f in K.FILTERS if K.FAMILY[f] == fam]
        fam_best[fam] = max(fs, key=lambda f: pool(f, "A", "P3")["per_trade"] + pool(f, "B", "P3")["per_trade"])

    def ln(f, rd):
        s2, s3 = pool(f, rd, "P2"), pool(f, rd, "P3")
        return (f"win {pct(s3['win'])} (needed {pct(s3['be_win'])}), {rs(s3['per_trade'])} a trade in the final "
                f"({rs(s2['per_trade'])} in the walk-forward year)")
    A0, B0 = base["A"]["P3"], base["B"]["P3"]
    L = ["# R155: every Donchian filter, OI and candle wicks on the 10-15 point scalps", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'some combinations pass' if passed else 'nothing fixes 10-15 point scalping'}.** {len(passed)} of "
         f"{len(tests)} filter x entry x reading tests passed the gates.",
         f"- **Without filters (R154):** option points win {pct(A0['win'])} (needed {pct(A0['be_win'])}), {rs(A0['per_trade'])} "
         f"a trade in Apr - Oct 2026; index points win {pct(B0['win'])} (needed {pct(B0['be_win'])}), {rs(B0['per_trade'])}.",
         f"- **Best filter on option points:** {ranks['A'][0][1]} {K.FNAME[ranks['A'][0][1]]}: {ln(ranks['A'][0][1], 'A')}.",
         f"- **Best filter on index points:** {ranks['B'][0][1]} {K.FNAME[ranks['B'][0][1]]}: {ln(ranks['B'][0][1], 'B')}.",
         "- **By family (best on the final):** " + "; ".join(f"{fam}: {f} ({K.FNAME[f].split(':')[0]})" for fam, f in fam_best.items()) + ".",
         f"- **Filters that lost less than no filter in BOTH the walk-forward year and the final:** option points "
         f"{len(better_both['A'])} of 20 ({', '.join(better_both['A']) or 'none'}); index points {len(better_both['B'])} of 20 "
         f"({', '.join(better_both['B']) or 'none'}). Losing less is not making money: the win rate stays below the rate needed.",
         f"- **Better than random, still losing:** {n_beat} of {len(tests)} tests pick better trades than random skips of the "
         f"same size (BH q <= 0.10; smallest q {minq}), i.e. they lose less per trade, but every one of them still loses "
         "money, so none passes.",
         f"- **Made money in the final period:** {pos_final} of {len(tests)} tests, all the picker: "
         + "; ".join(f"{k.replace(':', ' ')} {rs(dec[k]['stats']['P3']['net'])} on {dec[k]['stats']['P3']['trades']} trades "
                     f"(P1 {rs(dec[k]['stats']['P1']['net'])}, P2 {rs(dec[k]['stats']['P2']['net'])})" for k in pos_final_keys)
         + ". Each lost money in an earlier period, so none is a result.",
         f"- **Single cells** (one filter, one entry, one index, one exit; 16,632 tried): "
         f"{sum(v for k, v in pos.items() if k != 'F0')} made money in all three periods. With that many tries a few always "
         "do by luck; they are listed at the end as data-mined, not as findings.",
         "- Nothing changes in the app. Every test is logged in `research/experiments/entries/r155-*.yaml`.", "",
         "Signal source: the index for every entry; the filters read the index's own bars (levels, pivots, EMA, Supertrend, "
         "average price, candle wicks) or the option chain as context (OI build-up, PCR, the bought option's price and OI).", "",
         "## How it was tested", "",
         "Pre-registered in `PREREG.md` before any number. R154's 7 entries x indices x 24 exits (10 / 12 / 15 points on the "
         "option or the index, stop 1:1 or 1:1.5, 15 / 30 minutes, 15:15 square-off), with 20 filters taken from the Donchian "
         "studies: support / resistance lines (W5, W20, W50, 15-min 3-month majors, floor pivots, all lines at once, crossed a "
         "level), trend (EMA20, Supertrend, session average price), OI (build-up, PCR change, R144c's price-and-OI fall) and "
         "candle wicks (wick against on 5-min and 1-min bars, rejection wick, close location, strong body), a 5-of-7 vote and "
         "a walk-forward logistic picker on every feature. A filter skips the signal before the walk, so the freed slot can "
         "take the next signal (as R101). The unfiltered run reproduces R154 exactly for every entry, index, exit and period "
         "(asserted). Gated unit: filter x entry x reading (280), pooled over the indices and 12 exits; gates: net > 0 in P1, "
         "P2 and P3, better per trade than no filter in P2 and P3, beats 2,000 random skips with BH q <= 0.10 (m = "
         f"{dec['_m']['total']:,}), >= 40 final trades per exit, the arms above their own exits. P3 (Apr - 13 Oct 2026) was "
         "read for these scalps by R154: this is a second look. Implementation note: the random-skip subsets (uniform, "
         "without replacement) were first drawn one by one, which was too slow; they are drawn in batches as the n "
         "smallest of uniform keys, the same distribution. The switch was made before any gate result was seen.", "",
         "## Every filter pooled over all entries, option points (reading A), 1 lot", ""] + filter_table("A") + [
         "", "## Every filter pooled over all entries, index points (reading B), 1 lot", ""] + filter_table("B") + [
         "", "## Cells positive in all three periods (descriptive, data-mined)", "",
         "| filter | cells (of " + str(sum(len(K4.ENTRY_UNDS[e]) for e in K.ENTRIES) * 24) + ") |", "|---|---|"] + [
         f"| {f} | {pos[f]} |" for f in ("F0",) + K.FILTERS] + [
         "", "Best 10 cells by their weakest period (data-mined; each is one of 16,632 tries):", "",
         "| filter | entry | index | exit | P1 / P2 / P3 net | P3 trades |", "|---|---|---|---|---|---|"] + [
         f"| {f} | {e} | {u} | {r} | {' / '.join(rs(x) for x in nets)} | {n3} |" for _, f, e, u, r, nets, n3 in best] + [
         "", "Full gate table (280 tests): `results/tables.md`."]
    if all(trades(e, "post") is not None for e in K.ENTRIES):
        L += ["", "## Post window (14-19 Oct 2026, 4 sessions, never gated)", "", "| filter | reading | trades | win | net |",
              "|---|---|---|---|---|"]
        for f in dict.fromkeys(("F0", ranks["A"][0][1], ranks["B"][0][1])):
            for rd in ("A", "B"):
                s = pool(f, rd, "P4")
                L.append(f"| {f} | {rd} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} |")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    (K.RESULTS / "tables.md").write_text("\n".join(["# R155 gate table (1 lot, pooled over indices and the reading's 12 exits)",
                                                     ""] + decision_table(dec)) + "\n")
    summ = dict(passed=passed, m=dec["_m"], min_p=minp, min_q=minq, positive_final=pos_final, cells_positive_all=pos,
                best=dict(A=[(f, s3["per_trade"], s3["win"], s3["be_win"]) for _, f, _, s3 in ranks["A"][:5]],
                          B=[(f, s3["per_trade"], s3["win"], s3["be_win"]) for _, f, _, s3 in ranks["B"][:5]]),
                better_both=better_both, base=dict(A=A0, B=B0))
    (K.RESULTS / "summary.json").write_text(json.dumps(summ, indent=1, default=str))
    print("report written;", len(passed), "passed")


if __name__ == "__main__":
    main()
