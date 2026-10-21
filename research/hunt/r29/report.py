"""R29 report tables (results/report.md) and the experiment entries (research/experiments/entries/r29-*.yaml).

    python3 research/hunt/r29/report.py
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import yaml

import r29cfg as K
import rules as RU

PERIODS = ("discovery", "selection", "wf2025", "wf2026", "holdout")
ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R29_EMA_RIBBON.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): index_1m NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY/SENSEX (2017/2021-12 Oct "
        "2026), options_rolling_1m opt_<UND>_WEEK (nearest expiry, ATM+-10; options from Aug 2021, MIDCPNIFTY Jan 2022, "
        "SENSEX May 2023); Data Brain ribbon features (research/brain/features/ribbon.py)")
PERIOD = {"design": "discovery 2021-08..2022-12, selection 2024", "walk_forward": "2025, 2026 (to 12 Oct)",
          "holdout": "1 Jan - 31 Dec 2023 (locked, read once)"}
COSTS = ("app charges at today's rates (Rs 20/order, STT 0.15% sell, exchange NSE/BSE, SEBI, stamp, GST); fills at the "
         "next minute's open + 5 bps + a 1/2/4-tick half-spread by 5-min volume (stops 10 bps, 2x ticks); stress 2x spread")


def _load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def _f(x, nd=0, sign=False):
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "–"
    s = f"{x:+,.{nd}f}" if sign else f"{x:,.{nd}f}"
    return s.replace("-", "−")


def rule_rows(res: dict, period: str) -> list[dict]:
    out = []
    for key, r in res.items():
        rid, und, part = key.split("|")
        if part != period:
            continue
        out.append(dict(rule=rid, und=und, **r))
    return out


def rules_table(all_res: dict, period: str) -> str:
    rows = rule_rows(all_res, period)
    if not rows:
        return "(not run)\n"
    h = ("| rule | index | trades (days) | trades/day | win | avg win / loss | P&L/day | green days | worst day | max DD "
         "| cut short | per trade | twins | p | BH q | 2× spread/trade |\n|" + "---|" * 16 + "\n")
    lines = []
    for r in sorted(rows, key=lambda r: (int(r["rule"][1:]) if r["rule"][1:].isdigit() else 99, r["und"])):
        if not r.get("trades"):
            lines.append(f"| {r['rule']} | {r['und']} | 0 ({r.get('days', 0)}) |" + " – |" * 13)
            continue
        lines.append(
            f"| {r['rule']} | {r['und']} | {r['trades']} ({r['days']}) | {r['per_day']:.2f} | {r['win']:.0%} | "
            f"{_f(r['avg_win'])} / {_f(r['avg_loss'])} | {_f(r['pnl_day'], sign=True)} | {r['green_days']:.0%} | "
            f"{_f(r['worst_day'])} | {_f(r['max_dd'])} | {r['cut_short']} ({r['cut_share']:.0%}) | "
            f"{_f(r['per_trade'], sign=True)} | {_f(r['twin_per_trade'], sign=True)} | {r['p_twins']:.3f} | "
            f"{_f(r.get('bh_q'), 3) if r.get('bh_q') is not None else '–'} | {_f(r['per_trade_2x'], sign=True)} |")
    return h + "\n".join(lines) + "\n"


def pooled(all_res: dict, period: str) -> pd.DataFrame:
    rows = rule_rows(all_res, period)
    if not rows:
        return pd.DataFrame()
    D = pd.DataFrame(rows)
    D = D[D.trades > 0]
    g = D.groupby("rule")
    out = pd.DataFrame(dict(trades=g.trades.sum(), net=g.net.sum(), per_trade=g.net.sum() / g.trades.sum(),
                            twin=(D.twin_per_trade * D.trades).groupby(D.rule).sum() / g.trades.sum(),
                            any_dir=(D.rand_any_per_trade * D.trades).groupby(D.rule).sum() / g.trades.sum(),
                            idx_pos=(D.net > 0).groupby(D.rule).sum(), n_idx=g.size()))
    return out


def exits_table(all_res: dict, period: str) -> str:
    rows = rule_rows(all_res, period)
    if not rows:
        return "(not run)\n"
    D = pd.DataFrame([r for r in rows if r.get("trades")])
    cols = [f"{o}_{x}_pnl_day" for o in ("ATM", "ITM1") for x in ("X1", "X2", "X3", "X4")]
    S = D.groupby("rule")[cols].sum()
    S = S.loc[sorted(S.index, key=lambda r: int(r[1:]) if r[1:].isdigit() else 99)]
    h = "| rule | " + " | ".join(c.replace("_pnl_day", "") for c in cols) + " |\n|" + "---|" * (len(cols) + 1) + "\n"
    return h + "\n".join(f"| {i} | " + " | ".join(_f(v, sign=True) for v in r) + " |" for i, r in S.iterrows()) + "\n"


def mining_tables() -> str:
    R = pd.read_csv(K.RESULTS / "mining.csv")
    R["verdict"] = np.where(R.survivor, "survivor (beats twins; loses on its own)" ,
                            np.where((R.discovery_exp > 0) & (R.selection_exp > 0), "positive but tiny n: noise",
                                     "noise"))
    R.loc[R.survivor & (R.selection_exp > 0), "verdict"] = "survivor, positive"
    c = ["pattern", "sign", "mode", "discovery_n", "discovery_exp", "discovery_adj", "selection_n", "selection_exp",
         "selection_adj", "selection_lift30", "selection_mfe60", "selection_mae60", "selection_q", "verdict"]
    h = ("| pattern | sign | traded | disc n | disc net | disc adj | sel n | sel net | sel adj | sel lift30 (bps) | "
         "sel MFE60 / MAE60 (bps) | sel q | verdict |\n|" + "---|" * 13 + "\n")
    R = R.sort_values(["pattern", "sign", "mode"])
    lines = [f"| {r.pattern} | {r.sign} | {r.mode} | {r.discovery_n} | {_f(r.discovery_exp, sign=True)} | "
             f"{_f(r.discovery_adj, sign=True)} | {r.selection_n} | {_f(r.selection_exp, sign=True)} | "
             f"{_f(r.selection_adj, sign=True)} | {_f(r.selection_lift30, 1, True)} | "
             f"{_f(r.selection_mfe60, 0)} / {_f(r.selection_mae60, 0)} | {r.selection_q:.2g} | {r.verdict} |"
             for r in R[c].itertuples()]
    return h + "\n".join(lines) + "\n"


def build():
    mj = _load("mining.json")
    design, wf, ho = _load("design.json") or {}, _load("wf.json") or {}, _load("holdout.json")
    hres = (ho or {}).get("results", {})
    allres = {**design, **wf, **hres}
    md = ["# R29 tables (generated by report.py; do not edit)", ""]
    md += ["## Mining (Steps 1-2): all 140 candidates, ATM, exit X1, Rs per lot per trade", "",
           f"Random-minute trade (twins' mean): discovery {_f(mj['random_baseline']['discovery']['twin_net_mean'], sign=True)}"
           f", selection {_f(mj['random_baseline']['selection']['twin_net_mean'], sign=True)} per trade. "
           f"Trades: discovery {mj['trades']['discovery']:,}, selection {mj['trades']['selection']:,}.", "",
           mining_tables()]
    for per in PERIODS:
        md += [f"## Rules: {per}", "", rules_table(allres, per), "",
               f"Exits and 1-ITM side by side ({per}, P&L per day summed over the five indices):", "",
               exits_table(allres, per), ""]
        P = pooled(allres, per)
        if len(P):
            md += [f"Pooled over indices ({per}): trades, net Rs, net per trade, twins per trade, random-any-direction "
                   "per trade, indices with net > 0:", "",
                   "| rule | trades | net | per trade | twins | random any dir | indices > 0 |", "|---|---|---|---|---|---|---|"]
            for i, r in P.iterrows():
                md.append(f"| {i} | {int(r.trades):,} | {_f(r.net, sign=True)} | {_f(r.per_trade, sign=True)} | "
                          f"{_f(r.twin, sign=True)} | {_f(r.any_dir, sign=True)} | {int(r.idx_pos)} of {int(r.n_idx)} |")
            md.append("")
    if ho:
        md += [f"Holdout family size: {ho['family']['family_size']}.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(md))
    entries(mj, design, wf, hres)


def _rule_summary(rid, design, wf, hres):
    def agg(res, part):
        rows = [r for r in rule_rows(res, part) if r.get("trades")]
        n = sum(r["trades"] for r in rows)
        net = sum(r["net"] for r in rows)
        return n, net, [r for r in rule_rows(res, part)]
    bits = []
    for res, part, lab in ((design, "discovery", "Discovery"), (design, "selection", "Selection"),
                           (wf, "wf2025", "WF 2025"), (wf, "wf2026", "WF 2026"), (hres, "holdout", "Holdout")):
        sub = {k: v for k, v in res.items() if k.startswith(rid + "|")}
        n, net, rows = agg(sub, part)
        if not rows:
            continue
        pos = sum(1 for r in rows if r.get("trades") and r["net"] > 0)
        bits.append(f"{lab} {n} trades, net Rs {net:+,.0f} ({net / max(n, 1):+,.0f}/trade; {pos} of "
                    f"{sum(1 for r in rows if r.get('trades'))} indices > 0)")
    hp = [r for r in rule_rows({k: v for k, v in hres.items() if k.startswith(rid + "|")}, "holdout") if r.get("trades")]
    if hp:
        best = min(hp, key=lambda r: r.get("bh_q", 1))
        bits.append(f"best holdout index {best['und']}: p {best['p_twins']:.3f}, BH q {best.get('bh_q', float('nan')):.3f}")
    return "; ".join(bits)


def _passes(rid, wf, hres):
    out = []
    for und in K.UNDS:
        h = hres.get(f"{rid}|{und}|holdout", {})
        w5, w6 = wf.get(f"{rid}|{und}|wf2025", {}), wf.get(f"{rid}|{und}|wf2026", {})
        ok = (h.get("trades") and h["net"] > 0 and h["p_twins"] < 0.05 and h.get("bh_q", 1) <= 0.10
              and w5.get("trades") and w5["net"] > 0 and w6.get("trades") and w6["net"] > 0 and h["net_2x"] > 0)
        if ok:
            out.append(und)
    return out


def entries(mj, design, wf, hres):
    base = dict(date="2026-10-14", doc=DOC, data_version=DATA, instruments=list(K.UNDS),
                settings="research/hunt/r29/PREREG.md", prereg="research/hunt/r29/PREREG.md")
    E = []
    E.append(dict(id="r29-ribbon-features", title="R29: EMA ribbon features in the Data Brain L4 (77, 1/3/5/15-minute bars)",
                  family="patterns-indicators",
                  hypothesis="Describe the shape of many EMAs at once, point in time (no trading claim).",
                  period=dict(design="all lake index minutes"),
                  features=["EMA ribbon 5/8/13/21/34/55/89/200", "EMA 9/21/50/200", "stack", "order", "width/ATR",
                            "width change", "slopes", "position", "twist", "pullback", "compression-expansion",
                            "MTF agreement"],
                  model_or_rule="feature definitions (research/brain/features/ribbon.py)", variants_tried=None,
                  results=dict(summary="77 features added (154 -> 231); look-ahead guard and warm-up tests pass "
                               "(research/brain/tests/test_ribbon.py).", costs="n/a"),
                  holdout_read=None, verdict="not-a-test", prereg=None))
    R = pd.read_csv(K.RESULTS / "mining.csv")
    E.append(dict(id="r29-mining", title="R29 Steps 1-2: 140 multi-EMA pattern candidates mined on 2021-22, ranked on 2024",
                  family="patterns-indicators",
                  hypothesis="Multi-EMA patterns (ribbon state + trigger, compression-expansion, pullback in a stacked "
                             "trend, MTF agreement) carry an edge after costs that single EMA signals (H25) did not.",
                  period=dict(design=PERIOD["design"]),
                  features=["ribbon events P1-P12 on 1/3/5/15-minute bars"],
                  model_or_rule="35 events x bull/bear x follow/fade; ATM, exit X1 (Boss's lock), 1 lot; twins 20/trade",
                  variants_tried=140,
                  results=dict(summary=(f"{mj['trades']['discovery']:,} discovery / {mj['trades']['selection']:,} "
                                        f"selection trades. No candidate made money after costs in both splits "
                                        f"(one exception with 2 and 9 trades). {mj['survivors']} passed the twin gate "
                                        "(BH q <= 0.10); all are fades that still lose on their own (selection Rs -10 to "
                                        "-203 a trade). Random-minute trade: Rs "
                                        f"{mj['random_baseline']['selection']['twin_net_mean']:+,.0f} (selection)."),
                               costs=COSTS),
                  holdout_read=False, verdict="rejected",
                  notes="BH family: 140 mining + 55 confirmatory = 195 R29 tests, plus F1's count."))
    named = [("r29-named-stack-pullback", "Boss's idea: 15-min fully stacked + 5-min pullback to the 21/34 band, follow",
              ["P9_5"]),
             ("r29-named-squeeze-fanout", "Boss's idea: ribbon compression then fan-out breakout, follow",
              ["P5_1", "P5_3", "P5_5", "P5_15", "P11_5"]),
             ("r29-named-mtf-align", "Boss's idea: multi-timeframe (1/5/15-min) ribbon alignment entry, follow", ["P8_1"])]
    for eid, title, pats in named:
        sub = R[R.pattern.isin(pats) & (R["mode"] == "follow")]
        s = "; ".join(f"{r.pattern} {r.sign}: disc {r.discovery_n} trades {r.discovery_exp:+,.0f}/trade, sel "
                      f"{r.selection_n} trades {r.selection_exp:+,.0f}/trade (adj {r.selection_adj:+,.0f}, q "
                      f"{r.selection_q:.2f})" for r in sub.itertuples())
        E.append(dict(id=eid, title=title, family="patterns-indicators",
                      hypothesis="Boss's named multi-EMA entry makes money after costs on unseen data.",
                      period=dict(design=PERIOD["design"]), features=["EMA ribbon 5..200"],
                      model_or_rule=title + "; ATM, exit X1, 1 lot",
                      variants_tried=2 * len(pats),
                      results=dict(summary="Failed the mining gate (stays a note): " + s, costs=COSTS),
                      holdout_read=False, verdict="rejected",
                      notes="Mined as a candidate in r29-mining; not a survivor, so not run on the holdout."))
    for rule in RU.RULES + [RU.B0]:
        rid = rule["id"]
        passes = _passes(rid, wf, hres)
        summ = _rule_summary(rid, design, wf, hres)
        E.append(dict(id=f"r29-{rid.lower()}", title=f"R29 {rid}: {rule['name']}", family="patterns-indicators",
                      hypothesis=("The plain single-EMA cross (baseline) makes money after costs." if rid == "B0" else
                                  "A Step-2 survivor (beats its random twins on 2021-22 and 2024) makes money after "
                                  "costs on unseen years."),
                      period=dict(design=PERIOD["design"], walk_forward=PERIOD["walk_forward"],
                                  holdout=PERIOD["holdout"]),
                      features=["EMA ribbon 5..200" if rid != "B0" else "EMA 9/21"],
                      model_or_rule=f"{rule['name']}; {rule['opt']}, exit {rule['exit']}, 1 lot, one at a time, <= 3/day",
                      variants_tried=1,
                      results=dict(summary=(summ + (f". PASSED on {passes}" if passes else ". FAILED the gates")),
                                   costs=COSTS),
                      holdout_read=bool(hres), verdict=("paper-only" if passes else "rejected"),
                      notes="Confirmatory family 55 (11 rules x 5 indices); R29 total 195 with mining, plus F1's."))
    for e in E:
        e = {**base, **e}
        if e.get("prereg") is None and e["id"] != "r29-ribbon-features":
            e["prereg"] = base["prereg"]
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(E), "entries written")


if __name__ == "__main__":
    build()
