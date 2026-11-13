"""R168 report: results/report.md (a short plain summary for Boss first, then the tables) and results/tables.md (every
arm x index x exit rule x lots), from results/{design,final,decision}.json and the saved compact trades ($R168_OUT).

    python research/hunt/r168_orb_10_15/r168report.py
"""
from __future__ import annotations

import json

import numpy as np

import r168cfg as K
import r168study as S
from r154report import pct, rs

PNAME = {"P1": "P1 design (Aug 2021 - Mar 2025)", "P2": "P2 walk-forward (Apr 2025 - Mar 2026)",
         "P3": "P3 final (Apr - 13 Oct 2026, read once)"}
PER = {"P1": (K.EARLY_START, K.P1_END), "P2": (K.P2_START, K.P2_END), "P3": (K.FINAL_START, K.FINAL_END)}
UNAME = {u: (u + " (the app's index)" if u == K.APP_UND else u + " (same rules, not an app arm)") for u in K.UNDS}


def J(name):
    return json.loads((K.RESULTS / f"{name}.json").read_text())


def per(d, f, a, u, key, p):
    return (f if p == "P3" else d)[a][u][key][p]


def trades(a: str, u: str, key: str) -> dict:
    """All periods' compact trades of one arm x index x exit@lots."""
    return S.concat([S._load(f"{ph}_{u}")[a][key] for ph in ("design", "final")])


def pooled(a: str, u: str, reading: str | None, lots: int, p: str | None) -> dict:
    """Every rule of a reading (None: B0) pooled, in period p (None: P1-P3)."""
    keys = [f"{r}@{lots}" for r in K.RULE_IDS if K.RULES[r]["reading"] == reading] if reading else [f"B0@{lots}"]
    x = S.concat([trades(a, u, k) for k in keys])
    if p:
        x = S.in_range(x, *PER[p])
    st = S.stats(x)
    if p:                                   # per session: one rule's average (the pooled net / the rules / the sessions)
        n = len(S.sessions(u, *PER[p]))
        st["per_session"] = round(st["net"] / len(keys) / n) if n else None
    return st


def line(st: dict) -> str:
    return (f"{st['trades']} | {pct(st['win'])} | {pct(st['be_win'])} | {rs(st['net'])} | {rs(st['per_session'])} | "
            f"{rs(st['worst_day'])} | {rs(-st['max_dd']) if st['max_dd'] else 'Rs 0'} | {rs(st['ex_top5'])}")


HEAD = "| trades | win | break-even win | net | per session | worst day | max DD | without 5 best |"


def build():
    d, f, dec = J("design"), J("final"), J("decision")
    par = {**d.get("_parity", {}), **f.get("_parity", {})}
    m = dec["_m"]
    B = K.APP_UND
    L = []
    # ------------------------------------------------------------------ Boss summary
    orbA, orbB = pooled("ORB", B, "A", 1, None), pooled("ORB", B, "B", 1, None)
    orbA3, orbB3 = pooled("ORB", B, "A", 1, "P3"), pooled("ORB", B, "B", 1, "P3")
    b0 = pooled("ORB", B, None, 1, None)
    ex = "A10s10t15"
    e2, e3 = per(d, f, "ORB", B, f"{ex}@1", "P2"), per(d, f, "ORB", B, f"{ex}@1", "P3")
    exB = "B10s10t15"
    b2, b3 = per(d, f, "ORB", B, f"{exB}@1", "P2"), per(d, f, "ORB", B, f"{exB}@1", "P3")
    pos3 = [k for k, v in dec.items() if not k.startswith("_") and f[k.split(":")[0]][k.split(":")[1]][k.split(":")[2] + "@1"]["P3"]["net"] > 0]
    all3 = [k for k, v in dec.items() if not k.startswith("_") and all(
        per(d, f, k.split(":")[0], k.split(":")[1], k.split(":")[2] + "@1", p)["net"] > 0 for p in ("P1", "P2", "P3"))]
    nbeat = sum(1 for k, v in dec.items() if not k.startswith("_") and "not above the arm's own exits in P2 and P3" not in v["fails"])
    best = min((v["twin_p"], k) for k, v in dec.items() if not k.startswith("_"))
    sw = pooled("SWEEP", B, "A", 1, None)
    swb = pooled("SWEEP", B, "B", 1, None)
    L += ["# R168: the app's own ORB arms with 10-15 point exits", "", "## For Boss (short)", ""]
    L += [f"- **Verdict: ORB with 10-15 points does not work. {m['passed']} of {m['r168']} versions passed the gates** "
          f"(ORB, ORB Fresh and ORB Sweep x 5 indices x 24 exits, Aug 2021 - Oct 2026).",
          f"- **ORB on BANKNIFTY (the app's arm), 10-15 points on the option price, all 12 exit versions together, 1 lot:** "
          f"{orbA['trades']:,} trades, win {pct(orbA['win'])}, needed {pct(orbA['be_win'])}, {rs(orbA['per_trade'])} a trade. "
          f"Final period alone: win {pct(orbA3['win'])} (needed {pct(orbA3['be_win'])}), {rs(orbA3['per_trade'])} a trade.",
          f"- **The same with 10-15 points on the index:** {orbB['trades']:,} trades, win {pct(orbB['win'])}, needed "
          f"{pct(orbB['be_win'])}, {rs(orbB['per_trade'])} a trade (final: win {pct(orbB3['win'])}, {rs(orbB3['per_trade'])} a trade).",
          f"- **Against ORB's own exits (-40 / +40 with the lock), same entries, 1 lot:** own exits {b0['trades']:,} trades, win "
          f"{pct(b0['win'])}, {rs(b0['per_trade'])} a trade ({rs(b0['net'])} in all). Option-point scalps lose MORE "
          f"({rs(orbA['per_trade'])} a trade); index-point scalps lose LESS ({rs(orbB['per_trade'])} a trade) but still lose.",
          f"- **{nbeat} of {m['r168']} versions beat their arm's own exits in both P2 and P3** (mostly index-point exits, and "
          f"on ORB Sweep, whose own +80 exits lose the most). That is a smaller loss, not a profit.",
          f"- **One example** (ORB on BANKNIFTY, 10 option points, stop 10, out after 15 minutes): {e2['trades']} trades, win "
          f"{pct(e2['win'])} (needed {pct(e2['be_win'])}), {rs(e2['net'])} in Apr 2025 - Mar 2026; {e3['trades']} trades, win "
          f"{pct(e3['win'])}, {rs(e3['net'])} in Apr - Oct 2026. With 10 index points: {b2['trades']} trades, win "
          f"{pct(b2['win'])} (needed {pct(b2['be_win'])}), {rs(b2['net'])}; then {b3['trades']} trades, win {pct(b3['win'])}, "
          f"{rs(b3['net'])}.",
          f"- **ORB Sweep on BANKNIFTY with 10-15 points:** option points win {pct(sw['win'])} (needed {pct(sw['be_win'])}), "
          f"{rs(sw['per_trade'])} a trade; index points win {pct(swb['win'])} (needed {pct(swb['be_win'])}), "
          f"{rs(swb['per_trade'])} a trade. ORB Fresh takes almost the same trades as ORB (1 entry a side a day).",
          f"- **Why it fails:** a BANKNIFTY ORB trade pays about {rs(pooled('ORB', B, 'A', 1, None)['avg_charges'])} of charges "
          f"at 1 lot, plus slippage on both sides, so a 10-point win nets much less than a 10-point loss costs. The win rate "
          f"needed is above the win rate the entries give, on every index.",
          f"- **{'None' if not all3 else len(all3)} of the {m['r168']} versions made money in all three periods; {len(pos3)} made "
          f"money in the final period alone.** None beat its random-exit twins after the multiple-testing correction (smallest twin p {best[0]} "
          f"for {best[1]}; m = {m['total']:,}).",
          "- **Honest limits:** the final window (Apr - Oct 2026) was already read by R14 / H20 (the ORB arms' holdout) and "
          "many other studies, so it is weak evidence; the ORB family is parked in the app since 13 Oct anyway. All three "
          "arms use one 50-minute opening range (09:15 - 10:05); the app has no 5-, 15- or 30-minute ORB arm "
          "(R154 already tested the 15-minute break, E1, and it lost).",
          "- Nothing changes in the app. Every version is logged in `research/experiments/entries/r168-*.yaml`.", ""]
    L += ["Note on totals: the pooled lines add up every exit rule of a reading, which are alternative versions of the same "
          "entries. Read them for win rates and per-trade figures, not as one account's P&L.", "",
          "Signal source: the index for every arm (5-minute index bars built from 1-minute bars; one 09:15 - 10:00 opening "
          "range). The ATM option of that index (strike from the 09:20 bar's close, same-day expiry on an expiry day) is "
          "bought. Reading A puts the target / stop on the option premium, reading B on the index.", ""]
    # ------------------------------------------------------------------ how
    L += ["## How it was tested", "",
          "Pre-registered in `PREREG.md` before any number (hashes and times in `results/PREREG_HASH`; Amendment 1, "
          "before any number, only says the -40 / lock / target levels are measured from the real fill, as h20 / R14 and "
          "the app). The arms' entries are the app's `OrbRules` / `SweepRules` as h20 / R14 ported them: one opening "
          "range from the 5-minute bars 09:15 - 10:00; ORB and ORB Fresh decide on the 10:05 - 13:55 bars (Fresh only on "
          "a fresh break), at most 1 entry a side a day (R14's cap, the paper default since 13 Oct); ORB Sweep decides on "
          "10:05 - 14:25 (a wick beyond an edge that closes back inside, reversed), at most 2 entries a day; one position "
          "at a time and the next decision only on a bar starting after the exit's bar; a real fill of Rs 40 or less "
          "refused. Only the exits change: R154's 24 (10 / 12 / 15 points on the premium (A) or the index (B), stop 1:1 "
          "or 1:1.5, out after 15 or 30 minutes, 15:15 square-off, 15:05 on expiry days). Each arm's own exits (B0: -40 "
          "stop, the profit lock ladder, +40 target, Sweep +80; 15:10 square-off) are the baseline.",
          "",
          "Priced on the one contract bought with R154's stores (R51's clean stores from 20 Nov 2024, the early store "
          "before), R17 fills (buy +5 bps + half-spread ticks; stop sales -10 bps and twice the ticks), dated Zerodha "
          "charges and dated lot sizes. Periods: P1 design (Aug 2021 - Mar 2025; MIDCPNIFTY from Jan 2023, SENSEX from "
          "May 2023), P2 walk-forward (Apr 2025 - Mar 2026), P3 final (1 Apr - 13 Oct 2026, read once). Gates (1 lot): "
          "net > 0 in P1, P2 and P3; beats 500 random-exit twins with BH q <= 0.10 (m = "
          f"{m['total']:,}: {m['prior']:,} earlier tests at p = 1 + R168's {m['r168']}); >= 40 final trades; above the "
          "arm's own exits in P2 and P3. P4 (14 - 19 Oct, 4 sessions) was not run, as pre-registered.", "",
          "Environment, stated: Python 3.13 with the pinned numpy 2.1.3 / pandas 2.2.3 / scipy 1.14.1 / scikit-learn 1.5.2 "
          "and pyarrow 18.1 (17.0 has no 3.13 wheel); numba 0.68. The committed results JSON are slimmed (the priced "
          "trades stay in $R168_OUT). Exact figures for ORB and ORB Fresh on BANKNIFTY coincide because, with 1 entry a "
          "side a day, the first break on a side is always a fresh one.", ""]
    # ------------------------------------------------------------------ parity
    L += ["## Parity with the published figures (B0 = the arm's own exits, BANKNIFTY, 1 lot)", "",
          "| arm | window | published (R14 / H20) | R168, parity mode (app charges at today's rates) | trades within 5%? | net within 15%? | R168, own mode (dated charges) |",
          "|---|---|---|---|---|---|---|"]
    for a in K.ARMS:
        for part, wname in (("pre", "4 Aug 2021 - 30 Sep 2025"), ("ho", "1 Oct 2025 - 5 Oct 2026")):
            p, q = par.get(f"{a}:{part}:parity"), par.get(f"{a}:{part}:dated")
            if not p:
                continue
            L.append(f"| {K.ARM_NAME[a]} | {wname} | {p['published'][0]:,} tr, {rs(p['published'][1])} | {p['trades']:,} tr, "
                     f"{rs(p['net'])} | {'yes' if p['trades_ok'] else 'NO'} | {'yes' if p['net_ok'] else 'NO'} | "
                     f"{q['trades']:,} tr, {rs(q['net'])} |")
    L += ["", "PARITY_NOTE", ""]
    # ------------------------------------------------------------------ charges
    L += ["## Charges per trade and the break-even win rate", "",
          "Dated Zerodha charges of a round trip, averaged over every R168 trade on the index (all arms and exits, P1-P3); "
          "premium points eaten = charges / quantity. R17 slippage comes on top. Break-even win = the win rate the "
          "pooled scalp trades needed (their own average win and loss).", "",
          "| index | lots | trades | avg charges a trade | premium points eaten | win (A) / needed | win (B) / needed |",
          "|---|---|---|---|---|---|---|"]
    for u in K.UNDS:
        for n in K.LOTS:
            xs = S.concat([trades(a, u, f"{r}@{n}") for a in K.ARMS for r in K.RULE_IDS])
            if not len(xs["net"]):
                continue
            pa = S.stats(S.concat([trades(a, u, f"{r}@{n}") for a in K.ARMS for r in K.RULE_IDS if K.RULES[r]["reading"] == "A"]))
            pb = S.stats(S.concat([trades(a, u, f"{r}@{n}") for a in K.ARMS for r in K.RULE_IDS if K.RULES[r]["reading"] == "B"]))
            L.append(f"| {u} | {n} | {len(xs['net']):,} | {rs(float(xs['charges'].mean()))} | PTS_{u}_{n} | "
                     f"{pct(pa['win'])} / {pct(pa['be_win'])} | {pct(pb['win'])} / {pct(pb['be_win'])} |")
    L += [""]
    # ------------------------------------------------------------------ arm x index pooled
    L += ["## Each arm x index: own exits vs the scalps (1 lot; the scalps pooled over the 12 rules of a reading)", "",
          "Trades, net, worst day, max DD and the net without the 5 best add up the 12 rules (alternative versions of the "
          "same entries); per session is one rule's average.", "",
          "| arm | index | exits | period | " + HEAD[2:], "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for a in K.ARMS:
        for u in K.UNDS:
            for lab, rd in (("own (B0)", None), ("A: premium pts", "A"), ("B: index pts", "B")):
                for p in ("P1", "P2", "P3"):
                    L.append(f"| {K.ARM_NAME[a]} | {u} | {lab} | {p} | " + line(pooled(a, u, rd, 1, p)) + " |")
    L += [""]
    # ------------------------------------------------------------------ BANKNIFTY per rule
    for a in K.ARMS:
        L += [f"## {K.ARM_NAME[a]} on BANKNIFTY (the app's arm): every exit rule, 1 lot", "",
              "| exit | P1: trades / win / BE win / net | P2: trades / win / BE win / net | P3: trades / win / BE win / net | "
              "P3 per session | P3 worst day | P3 max DD | P3 without 5 best | P3 avg charges | twin p | q | verdict |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for key in ["B0"] + list(K.RULE_IDS):
            s = [per(d, f, a, B, f"{key}@1", p) for p in ("P1", "P2", "P3")]
            cells = " | ".join(f"{x['trades']} / {pct(x['win'])} / {pct(x['be_win'])} / {rs(x['net'])}" for x in s)
            g = dec.get(f"{a}:{B}:{key}")
            tp = f"{g['twin_p']}" if g else "-"
            qv = f"{g['q']}" if g else "-"
            verdict = "baseline" if not g else ("PASS" if g["passed"] else "fail: " + "; ".join(g["fails"]))
            s3 = s[2]
            L.append(f"| {key if key != 'B0' else 'own exits (B0)'} | {cells} | {rs(s3['per_session'])} | {rs(s3['worst_day'])} | "
                     f"{rs(-s3['max_dd']) if s3['max_dd'] else 'Rs 0'} | {rs(s3['ex_top5'])} | {rs(s3['avg_charges'])} | {tp} | {qv} | {verdict} |")
        L += [""]
    # ------------------------------------------------------------------ lots
    L += ["## 1, 2 and 3 lots (ORB on BANKNIFTY; net a lot a trade, all periods)", "",
          "| exits | 1 lot: win / needed / net a trade | 2 lots: per lot a trade | 3 lots: per lot a trade |", "|---|---|---|---|"]
    for key in ["B0"] + list(K.RULE_IDS):
        c = []
        for n in K.LOTS:
            st = S.stats(trades("ORB", B, f"{key}@{n}"))
            c.append(f"{pct(st['win'])} / {pct(st['be_win'])} / {rs((st['per_trade'] or 0) / n)}" if n == 1 else rs((st["per_trade"] or 0) / n))
        L.append(f"| {key} | " + " | ".join(c) + " |")
    L += [""]
    # ------------------------------------------------------------------ walk-forward pick
    L += ["## Walk-forward pick (descriptive, never gated)", "",
          "Each quarter of P2 and the final take the exit with the best 1-lot net on all earlier data.", "",
          "| arm | index | P2 picks | P2 net | P3 pick | P3 net |", "|---|---|---|---|---|---|"]
    for a in K.ARMS:
        for u in K.UNDS:
            w = dec["_wf"][f"{a}:{u}"]
            L.append(f"| {K.ARM_NAME[a]} | {u} | {', '.join(str(x['rule']) for x in w['P2']['windows'])} | {rs(w['P2']['net'])} | "
                     f"{w['P3']['windows'][0]['rule']} | {rs(w['P3']['net'])} |")
    qmin = min(v["q_r168"] for k, v in dec.items() if not k.startswith("_"))
    fails = {}
    for k, v in dec.items():
        if not k.startswith("_"):
            for x in v["fails"]:
                x = x.split(" q ")[0]
                fails[x] = fails.get(x, 0) + 1
    L += ["", "## Gates", "", f"Tests: {m['r168']}; m = {m['total']:,}; passed: {m['passed']}. Failures by gate: "
          + "; ".join(f"{k}: {v}" for k, v in fails.items()) + f". Even counting R168's own {m['r168']} tests alone, the "
          f"smallest BH q is {qmin}. Every arm x index x exit with its gate result is in `results/tables.md`, "
          "`results/decision.json` and `results/raw_pvalues.csv`.", ""]
    return L


def pts_eaten(text: str) -> str:
    """Fill the premium-points column: charges / quantity from the trades' lots (dated lot sizes)."""
    import r168engine as X
    R = X.mods()["r51cfg"]
    for u in K.UNDS:
        for n in K.LOTS:
            tag = f"PTS_{u}_{n}"
            if tag not in text:
                continue
            xs = S.concat([trades(a, u, f"{r}@{n}") for a in K.ARMS for r in K.RULE_IDS])
            from datetime import date as _d
            q = np.array([R.lot(u, _d.fromordinal(int(x))) * n for x in xs["day"]], float)
            text = text.replace(tag, f"{float(np.mean(xs['charges'] / q)):.1f}")
    return text


def tables() -> list:
    d, f, dec = J("design"), J("final"), J("decision")
    L = ["# R168 tables: every arm x index x exit rule", "", "1 lot unless the column says so. Win / BE = win rate / "
         "break-even win rate. Nets in rupees after charges and R17 slippage.", ""]
    for a in K.ARMS:
        for u in K.UNDS:
            L += [f"## {K.ARM_NAME[a]} - {UNAME[u]}", "",
                  "| exit | P1 tr / win / BE / net | P2 tr / win / BE / net | P3 tr / win / BE / net | P3 per session | worst day | max DD | w/o 5 best | 2 lots P2 / P3 | 3 lots P2 / P3 | twin p | q | verdict |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for key in ["B0"] + list(K.RULE_IDS):
                s = [per(d, f, a, u, f"{key}@1", p) for p in ("P1", "P2", "P3")]
                cells = " | ".join(f"{x['trades']} / {pct(x['win'])} / {pct(x['be_win'])} / {rs(x['net'])}" for x in s)
                l2 = f"{rs(per(d, f, a, u, f'{key}@2', 'P2')['net'])} / {rs(per(d, f, a, u, f'{key}@2', 'P3')['net'])}"
                l3 = f"{rs(per(d, f, a, u, f'{key}@3', 'P2')['net'])} / {rs(per(d, f, a, u, f'{key}@3', 'P3')['net'])}"
                g = dec.get(f"{a}:{u}:{key}")
                verdict = "baseline" if not g else ("PASS" if g["passed"] else "fail: " + "; ".join(g["fails"]))
                s3 = s[2]
                L.append(f"| {key} | {cells} | {rs(s3['per_session'])} | {rs(s3['worst_day'])} | "
                         f"{rs(-s3['max_dd']) if s3['max_dd'] else 'Rs 0'} | {rs(s3['ex_top5'])} | {l2} | {l3} | "
                         f"{g['twin_p'] if g else '-'} | {g['q'] if g else '-'} | {verdict} |")
            L += [""]
    return L


if __name__ == "__main__":
    import sys
    nf = K.RESULTS / "parity_note.txt"
    note = sys.argv[1] if len(sys.argv) > 1 else (nf.read_text().strip() if nf.exists() else "")
    text = "\n".join(build()) + "\n"
    text = pts_eaten(text).replace("PARITY_NOTE", note or "(see the note below the table)")
    (K.RESULTS / "report.md").write_text(text)
    (K.RESULTS / "tables.md").write_text("\n".join(tables()) + "\n")
    print("report written")
