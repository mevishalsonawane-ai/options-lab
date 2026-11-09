"""R154 report: results/report.md (plain summary for Boss first, then the tables) and results/tables.md (every index x
entry x exit rule), from results/{design,final,post,decision}.json and the saved compact trades ($R154_OUT).

    python research/hunt/r154_scalp_10_15/r154report.py
"""
from __future__ import annotations

import json
from datetime import date

import numpy as np

import r154cfg as K
import r154study as S

PER = {"P1": (K.EARLY_START, K.P1_END), "P2": (K.P2_START, K.P2_END), "P3": (K.FINAL_START, K.FINAL_END),
       "P4": (K.POST_START, K.POST_END)}
PNAME = {"P1": "P1 design (to Mar 2025)", "P2": "P2 walk-forward (Apr 2025 - Mar 2026)",
         "P3": "P3 final (Apr - 13 Oct 2026, read once)", "P4": "P4 post (14-19 Oct 2026)", "ALL": "P1-P3 together"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    s = f"{abs(x):,.0f}"
    if abs(x) >= 1e5:                                   # Indian grouping: 1,23,456
        whole = f"{abs(x):.0f}"
        head, tail = whole[:-3], whole[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts + [tail])
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * float(x):.0f}%"


def _load(name):
    try:
        return S._load(name)
    except FileNotFoundError:
        return None


def trades_of(entry: str, und: str, rule: str, lots: int, phases=("design", "final")) -> dict:
    parts = []
    for ph in phases:
        x = _load(f"{entry}_{und}_{ph}")
        if x is not None and (rule, lots) in x:
            parts.append(x[(rule, lots)])
    return S.concat(parts)


def pooled(entries, unds_of, rules, lots: int, period: str, phases=("design", "final")) -> dict:
    parts = []
    for e in entries:
        for u in unds_of(e):
            for r in rules:
                parts.append(trades_of(e, u, r, lots, phases))
    a = S.concat(parts)
    if period == "ALL":
        a = S.in_range(a, K.EARLY_START, K.FINAL_END)
    else:
        a = S.in_range(a, *PER[period])
    return S.stats(a)


def rules_of(reading: str | None = None) -> list:
    return [r for r in K.RULE_IDS if reading is None or K.RULES[r]["reading"] == reading]


def exits_txt(ex: dict) -> str:
    if not ex:
        return "-"
    return f"{ex.get('target', 0)} / {ex.get('stop', 0)} / {ex.get('time', 0)} / {ex.get('squareoff', 0) + ex.get('missing', 0)}"


def line(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']:,} trades, win {pct(s['win'])} (break-even {pct(s['be_win'])}), net {rs(s['net'])} "
            f"({rs(s['per_trade'])} a trade)")


# ================================================================================================ tables
def charges_table() -> list:
    """Average round-trip charges per trade and the premium points they eat, per index and lots (rule A10s10t15, every
    entry, P1-P3)."""
    out = ["| index | lots | trades | avg charges a trade | premium points eaten | qty (avg) |", "|---|---|---|---|---|---|"]
    for u in K.UNDS:
        for n in K.LOTS:
            parts = []
            qtys = []
            for e in K.ENTRIES:
                if u not in K.ENTRY_UNDS[e]:
                    continue
                parts.append(trades_of(e, u, "A10s10t15", n))
            a = S.concat(parts)
            if not len(a["net"]):
                continue
            ch = float(a["charges"].mean())
            # quantity: lots x the dated lot size; recovered per trade from the saved lot schedule
            R = S.mods()["r51cfg"]
            q = np.array([R.lot(u, date.fromordinal(int(d))) * n for d in a["day"]], float)
            qtys.append(q.mean())
            out.append(f"| {u} | {n} | {len(a['net']):,} | {rs(ch)} | {float((a['charges'] / q).mean()):.1f} | "
                       f"{float(q.mean()):.0f} |")
    return out


def pooled_table(lots: int = 1) -> list:
    out = [f"| entry | reading | {' | '.join(PNAME[p] for p in ('P1', 'P2', 'P3'))} | P1-P3 win / break-even |",
           "|---|---|---|---|---|---|"]
    for e in K.ENTRIES:
        for rd in ("A", "B"):
            cells, all_ = [], pooled([e], lambda x: K.ENTRY_UNDS[x], rules_of(rd), lots, "ALL")
            for p in ("P1", "P2", "P3"):
                s = pooled([e], lambda x: K.ENTRY_UNDS[x], rules_of(rd), lots, p)
                cells.append(f"{s['trades']:,} tr, {pct(s['win'])} win, {rs(s['net'])}")
            out.append(f"| {K.ENTRY_NAME[e]} | {rd} | {' | '.join(cells)} | {pct(all_['win'])} / {pct(all_['be_win'])} |")
    return out


def by_size_table(lots: int = 1) -> list:
    """Every entry and index pooled, per exit rule: P1-P3 together and the final."""
    out = ["| exit rule | P1-P3 trades | win | break-even win | net | a trade | avg charges | final trades | final win | "
           "final net | exits target / stop / time / sq-off (P1-P3) |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in K.RULE_IDS:
        a = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], [r], lots, "ALL")
        f = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], [r], lots, "P3")
        out.append(f"| {r} | {a['trades']:,} | {pct(a['win'])} | {pct(a['be_win'])} | {rs(a['net'])} | {rs(a['per_trade'])} | "
                   f"{rs(a['avg_charges'])} | {f['trades']:,} | {pct(f['win'])} | {rs(f['net'])} | {exits_txt(a['exits'])} |")
    return out


def lots_table() -> list:
    out = ["| reading | lots | P1-P3 trades | win | break-even win | net | a trade | avg charges | final net |",
           "|---|---|---|---|---|---|---|---|---|"]
    for rd in ("A", "B"):
        for n in K.LOTS:
            a = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rules_of(rd), n, "ALL")
            f = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rules_of(rd), n, "P3")
            out.append(f"| {rd} | {n} | {a['trades']:,} | {pct(a['win'])} | {pct(a['be_win'])} | {rs(a['net'])} | "
                       f"{rs(a['per_trade'])} | {rs(a['avg_charges'])} | {rs(f['net'])} |")
    return out


def baseline_table() -> list:
    out = ["| reading | random entries P1-P3 | signal entries (E1-E4) P1-P3 | random final | E1-E4 final |",
           "|---|---|---|---|---|"]
    for rd in ("A", "B"):
        cells = []
        for p in ("ALL", "P3"):
            r = pooled(["RND"], lambda x: K.UNDS, rules_of(rd), 1, p)
            s = pooled(list(K.PLAIN), lambda x: K.UNDS, rules_of(rd), 1, p)
            cells += [f"{r['trades']:,} tr, win {pct(r['win'])}, {rs(r['net'])} ({rs(r['per_trade'])} a trade)",
                      f"{s['trades']:,} tr, win {pct(s['win'])}, {rs(s['net'])} ({rs(s['per_trade'])} a trade)"]
        out.append(f"| {rd} | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} |")
    return out


def arms_table(d: dict, f: dict) -> list:
    out = ["| arm | index | today's exits (B0) P2 / P3 | best scalp P2 (rule: net) | that rule in P3 | scalps above B0 in P2 and P3 |",
           "|---|---|---|---|---|---|"]
    for e in K.ARMS:
        for u in K.ENTRY_UNDS[e]:
            b2, b3 = d[e][u]["B0@1"]["P2"]["net"], f[e][u]["B0@1"]["P3"]["net"]
            best = max(K.RULE_IDS, key=lambda r: d[e][u][f"{r}@1"]["P2"]["net"])
            n_above = sum(1 for r in K.RULE_IDS if d[e][u][f"{r}@1"]["P2"]["net"] > b2 and f[e][u][f"{r}@1"]["P3"]["net"] > b3)
            out.append(f"| {e} | {u} | {rs(b2)} / {rs(b3)} | {best}: {rs(d[e][u][f'{best}@1']['P2']['net'])} | "
                       f"{rs(f[e][u][f'{best}@1']['P3']['net'])} | {n_above} of 24 |")
    return out


def wf_table(dec: dict) -> list:
    out = ["| entry | index | P2 picks (quarterly) | P2 net | final pick | P3 net |", "|---|---|---|---|---|---|"]
    for k, v in dec.get("_wf", {}).items():
        e, u = k.split(":")
        picks = ", ".join(w["rule"] or "-" for w in v["P2"]["windows"])
        out.append(f"| {e} | {u} | {picks} | {rs(v['P2']['net'])} | {v['P3']['windows'][0]['rule']} | {rs(v['P3']['net'])} |")
    return out


def full_tables(d: dict, f: dict, dec: dict) -> list:
    out = ["# R154 every test (1 lot): index x entry x exit rule", "",
           "P1 / P2 = design / walk-forward; P3 = the final, read once. Exits = target / stop / time / square-off. "
           "Twin p: 500 random-exit twins (P3). q: BH with m = 1,682 (890 earlier + 792 R154). Rs net after dated "
           "charges and R17 slippage.", ""]
    for e in K.ENTRIES:
        for u in K.ENTRY_UNDS[e]:
            out += [f"## {K.ENTRY_NAME[e]}: {u}", "", f"Signal source: {K.SIGNAL_SRC[e]}.", ""]
            if e in K.ARMS:
                b = d[e][u]["B0@1"]
                out.append(f"Today's exits (B0): P1 {rs(b['P1']['net'])}, P2 {rs(b['P2']['net'])}, P3 "
                           f"{rs(f[e][u]['B0@1']['P3']['net'])} ({f[e][u]['B0@1']['P3']['trades']} trades).")
                out.append("")
            out += ["| rule | P1 trades / win / net | P2 trades / win / net | P3 trades | win | break-even | net | per session | "
                    "worst day | max DD | without 5 best | exits | avg charges | twin p | q | verdict |",
                    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for r in K.RULE_IDS:
                p1, p2, p3 = d[e][u][f"{r}@1"]["P1"], d[e][u][f"{r}@1"]["P2"], f[e][u][f"{r}@1"]["P3"]
                g = dec.get(f"{e}:{u}:{r}", {})
                verdict = "PASS" if g.get("passed") else "fail: " + "; ".join(g.get("fails", []))
                out.append(f"| {r} | {p1['trades']} / {pct(p1['win'])} / {rs(p1['net'])} | {p2['trades']} / {pct(p2['win'])} / "
                           f"{rs(p2['net'])} | {p3['trades']} | {pct(p3['win'])} | {pct(p3['be_win'])} | {rs(p3['net'])} | "
                           f"{rs(p3['per_session'])} | {rs(p3['worst_day'])} | {rs(p3['max_dd'])} | {rs(p3['ex_top5'])} | "
                           f"{exits_txt(p3['exits'])} | {rs(p3['avg_charges'])} | {g.get('twin_p', '-')} | {g.get('q', '-')} | {verdict} |")
            out.append("")
    return out


def post_table() -> list:
    out = ["| reading | lots | post trades | win | net |", "|---|---|---|---|---|"]
    for rd in ("A", "B"):
        for n in (1, 3):
            s = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rules_of(rd), n, "P4", phases=("post",))
            out.append(f"| {rd} | {n} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} |")
    return out


def headline() -> dict:
    h = {}
    for rd in ("A", "B"):
        for n in (1, 3):
            h[f"{rd}@{n}"] = dict(all=pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rules_of(rd), n, "ALL"),
                                  final=pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rules_of(rd), n, "P3"))
    for s in K.SIZES:
        rs_ = [r for r in K.RULE_IDS if K.RULES[r]["target"] == s]
        h[f"size{s:g}"] = pooled(K.ENTRIES, lambda x: K.ENTRY_UNDS[x], rs_, 1, "ALL")
    h["random"] = pooled(["RND"], lambda x: K.UNDS, list(K.RULE_IDS), 1, "ALL")
    return h


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    h = headline()
    tests = [k for k in dec if not k.startswith("_")]
    passed = [k for k in tests if dec[k]["passed"]]
    pos3 = sum(1 for e, u, r in K.TESTS if d[e][u][f"{r}@1"]["P1"]["net"] > 0 and d[e][u][f"{r}@1"]["P2"]["net"] > 0
               and f[e][u][f"{r}@1"]["P3"]["net"] > 0)
    pos_final = sum(1 for e, u, r in K.TESTS if f[e][u][f"{r}@1"]["P3"]["net"] > 0)
    minq = min(dec[k]["q"] for k in tests)
    minp = min(dec[k]["twin_p"] for k in tests)
    (K.RESULTS / "summary.json").write_text(json.dumps(dict(headline=h, passed=passed, positive_all_three=pos3,
                                                            positive_final=pos_final, min_q=minq, min_p=minp,
                                                            m=dec["_m"]), indent=1, default=str))
    A1, B1, A3, B3 = h["A@1"], h["B@1"], h["A@3"], h["B@3"]
    rnd_a = pooled(["RND"], lambda x: K.UNDS, rules_of("A"), 1, "ALL")
    rnd_b = pooled(["RND"], lambda x: K.UNDS, rules_of("B"), 1, "ALL")
    sig_a = pooled(list(K.PLAIN), lambda x: K.UNDS, rules_of("A"), 1, "ALL")
    sig_b = pooled(list(K.PLAIN), lambda x: K.UNDS, rules_of("B"), 1, "ALL")

    def one(e, u, r):
        p2, p3 = d[e][u][f"{r}@1"]["P2"], f[e][u][f"{r}@1"]["P3"]
        return (f"{p2['trades']} trades, win {pct(p2['win'])} (needed {pct(p2['be_win'])}), {rs(p2['net'])} in Apr 2025 - "
                f"Mar 2026; {p3['trades']} trades, win {pct(p3['win'])}, {rs(p3['net'])} in Apr - Oct 2026")
    best = max(K.TESTS, key=lambda t: d[t[0]][t[1]][f"{t[2]}@1"]["P1"]["net"] + d[t[0]][t[1]][f"{t[2]}@1"]["P2"]["net"]
               + f[t[0]][t[1]][f"{t[2]}@1"]["P3"]["net"])
    bp = [d[best[0]][best[1]][f"{best[2]}@1"]["P1"]["net"], d[best[0]][best[1]][f"{best[2]}@1"]["P2"]["net"],
          f[best[0]][best[1]][f"{best[2]}@1"]["P3"]["net"]]
    L = ["# R154: 10-15 point scalping on the index options", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'some versions pass' if passed else 'no version of 10-15 point scalping works'}.** "
         f"{len(passed)} of {len(tests)} versions passed the gates (7 entries x 5 indices x 24 exit rules, Aug 2021 - Oct 2026).",
         f"- **Win rate vs the win rate needed (1 lot, all versions, Aug 2021 - Oct 2026):** points on the option's price: "
         f"win {pct(A1['all']['win'])}, needed {pct(A1['all']['be_win'])}; points on the index: win {pct(B1['all']['win'])}, "
         f"needed {pct(B1['all']['be_win'])}. After charges and slippage a 10-15 point loss is bigger than a 10-15 point win, "
         "so even a 50% win rate loses.",
         f"- **Profit / loss a trade (1 lot):** option points {rs(A1['all']['per_trade'])} a trade "
         f"(final period {rs(A1['final']['per_trade'])}); index points {rs(B1['all']['per_trade'])} "
         f"(final {rs(B1['final']['per_trade'])}). Charges alone are about {rs(A1['all']['avg_charges'])} a trade.",
         f"- **At 3 lots** the loss per lot is smaller (brokerage is per order) but still a loss: option points "
         f"{rs(A3['all']['per_trade'] / 3)} a lot a trade, index points {rs(B3['all']['per_trade'] / 3)}.",
         f"- **One example** (opening-range break on NIFTY, 10 option points, stop 10, 15 minutes): {one('E1', 'NIFTY', 'A10s10t15')}. "
         f"The same with 10 index points: {one('E1', 'NIFTY', 'B10s10t15')}.",
         f"- **Entries vs coin flips:** the four scalping entries lose a little less than random entries with the same exits "
         f"({rs(sig_a['per_trade'])} vs {rs(rnd_a['per_trade'])} a trade on option points, {rs(sig_b['per_trade'])} vs "
         f"{rs(rnd_b['per_trade'])} on index points), but they still lose.",
         f"- **Closest call:** {best[0]} on {best[1]} with {best[2]} made {rs(bp[0])} / {rs(bp[1])} / {rs(bp[2])} in the three "
         f"periods. {pos_final} of 792 versions made money in the final period, {pos3} in all three periods; none beat its "
         f"random-exit twins after the multiple-testing correction (smallest twin p {minp}, smallest q {minq}).",
         "- **The arms:** replacing the R72, R51 and Liquidity exits with 10-15 point scalps made less than their own exits on "
         "every index (0 of 312). Small targets cut the few big winners these arms live on, as R106 and R152 found.",
         "- **Same answer as before:** SCALP17 (+15-20 option points) and R45 also found scalps lose after costs. Scalping is "
         "not different here.",
         "- Nothing changes in the app. Every version is logged in `research/experiments/entries/r154-*.yaml`.", "",
         "Note on totals: the tables below add up every exit rule of a reading, which are alternative versions of the same "
         "trades. Read them for win rates and per-trade figures; a rupee total over several rules is not one account's P&L.", "",
         "Signal source: the index for every entry (1- and 5-minute index bars; the arms' own index signals); the 1-ITM "
         "nearest-expiry option is bought. Reading A puts the target / stop on the option premium, reading B on the index.", "",
         "## How it was tested", "",
         "Pre-registered in `PREREG.md` (committed before any number). 7 entries (R72, R51 + Supertrend, Liquidity 15+5, "
         "and four plain scalping entries: opening-range break, VWAP stand-in cross, 9/21 EMA cross, previous-bar break with "
         "trend) x the 5 indices (Liquidity: 3) x 24 exits (10 / 12 / 15 points, stop 1:1 or 1:1.5, time stop 15 or 30 "
         "minutes, 15:15 square-off, 15:05 on expiry days) = 792 tests at 1 lot; 2 and 3 lots shown. Every trade is priced "
         "on the one contract bought with R51's clean stores, R17 fills, dated Zerodha charges and dated lot sizes. "
         "If a stop and a target fall in the same minute, the stop counts. Periods: P1 design (Aug 2021 - Mar 2025; the arms "
         "from 20 Nov 2024), P2 walk-forward (Apr 2025 - Mar 2026), P3 final (1 Apr - 13 Oct 2026, read once: **already read "
         "by R51-R153 and by SCALP17, so weak evidence**), P4 post (14-19 Oct). Gates: net > 0 in P1, P2 and P3; beats "
         f"500 random-exit twins with BH q <= 0.10 (m = {dec['_m']['total']:,}: 890 earlier tests + 792); >= 40 final "
         "trades; the arms also above their own exits in P2 and P3.", "",
         "Environment, stated: this machine has Python 3.13; R154 ran on numpy 2.5 / pandas 3.0. All three arms reproduce "
         "their published B0 exactly (design and final), and the tests and the brain checks pass under the pinned versions "
         "(numpy 2.1.3, pandas 2.2.3, pyarrow 18.1 for 3.13). The committed results JSON are slimmed (full ones stay in "
         "$R154_OUT).", "",
         "## Charges per trade (dated Zerodha charges, R17 slippage not included)", ""] + charges_table() + [
         "", "Premium points eaten = charges / quantity: the points a trade must make just to pay its charges. Slippage "
         "(R17) comes on top in the fills.", "",
         "## Every entry pooled (all its indices and 12 exit rules of a reading), 1 lot", ""] + pooled_table(1) + [
         "", "## Every exit rule pooled (all entries and indices), 1 lot", ""] + by_size_table(1) + [
         "", "## Lots (all entries, indices and exit rules of a reading)", ""] + lots_table() + [
         "", "## Do the entries beat random entries? (same exits, 1 lot)", ""] + baseline_table() + [
         "", "## The arms: scalp exits against today's exits (1 lot)", ""] + arms_table(d, f) + [
         "", "## Walk-forward pick (descriptive): the best exit on all earlier data, run on the next quarter", ""] + wf_table(dec) + [
         "", "## Post window (14-19 Oct 2026, 4 sessions; never gated)", ""] + post_table() + [
         "", "## Earlier findings, honestly", "",
         "- R106 (26 Oct): Rs 500 / 1,000 / 1,500 targets on the Liquidity, R72, R51 and OOV arms: 0 of 36 passed; small targets "
         "cut the arms' few big winners. R152 (7 Nov): +/- Rs 2,000 on R72 / R51 at 1-3 lots: 0 of 18 passed.",
         "- SCALP17: +15-20 premium points, 1,100,700 combos screened, 0 passed BH; every shortlisted rule lost net in 2025 - Oct 2026.",
         "- R154 adds the index-point reading, 10-12 point sizes, the arms' own entries and four common scalping entries, on every "
         "index and all the option data (Aug 2021 - Oct 2026). The answer does not change: after charges and slippage a fixed "
         "10-15 point scalp needs a win rate it does not reach.", "",
         "Full table of all 792 tests: `results/tables.md`. Gate decisions: `results/decision.json`."]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    (K.RESULTS / "tables.md").write_text("\n".join(full_tables(d, f, dec)) + "\n")
    print("report written;", len(passed), "passed")


if __name__ == "__main__":
    main()
