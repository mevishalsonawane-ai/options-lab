"""Writes research/experiments/entries/r59-*.yaml: the literal audit (not a test) and one entry per honest family x
variant (A / B x NS / S25, 21 cells each: 10 stocks x 2 sides + pooled), failures included.

    python3 -I research/hunt/r59/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import yaml  # noqa: E402

import r59cfg as K  # noqa: E402

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R59_STOCK_DIP_AUDIT.md"
PREREG = "research/hunt/r59/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake: stock_options_rolling_1m stkopt_<SYM>_MONTH (rolling ATM-1..ATM+1, "
        "nearest monthly expiry, 1-minute, rebuilt per held contract), equity_1m 2024-2026, nse_bhavcopy weekly UDiFF "
        "files (expiries, lots); options to 14 Oct 2026, equity to 12 Oct 2026")
STOCKS = list(K.STOCKS)
COSTS = ("1 lot in force (bhavcopy NewBrdLotQty per stock, expiry and date); next-minute fills at the open + 5 bps + the "
         "larger of R17 liq 1/2/4 ticks and the stock's design Roll half-spread (0.26-0.50%); stops pay double; R47 "
         "stray-print guard and foreign-rows filter; spikes.py flags dropped; Costs('dated') NSE charges; 2x costs "
         "(spread part and charges doubled) reported")
FAM = {
    "A": dict(name="their 20% drop measured on the HELD contract", family="outside-strategies",
              features=["signal source: the OPTION's own price chart (NON-DEFAULT under CLAUDE.md, labelled)",
                        "15-minute closes of the held ATM contract (same strike + expiry at t-1 and t, same session)"],
              rule=("at a 15-minute bar close (bar 2+), the ATM contract whose OWN close fell 20%+ from the previous bar "
                    "of the same session and closes >= 5 (last trade in the bar's last 5 minutes); buy it at the next "
                    "1-minute open (within 5 min); exit 60 min later or 15:25 on the same contract")),
    "B": dict(name="the stock's own 1% 15-minute move against the option", family="stocks",
              features=["signal source: the STOCK (equity_1m 15-minute closes; default-compatible)",
                        "ATM option of the side bought; stops on the option premium"],
              rule=("at a 15-minute bar close (bar 2+), the stock fell 1.0%+ in the bar (buy the ATM CE) or rose 1.0%+ "
                    "(buy the ATM PE); buy at the next 1-minute open (within 5 min); exit 60 min later or 15:25 on the "
                    "same contract")),
}
VAR = {"NS": "no stop (their exit, comparison only)", "S25": "25% premium stop on 1-minute rows (gap fills at the open)"}


def rs(x):
    return "n/a" if x is None else f"{x:+,.0f}"


def line(n, d, f):
    p = "n/a" if f.get("p") is None else f"{f['p']:.3f}"
    q = "n/a" if f.get("q") is None else f"{f['q']:.2f}"
    return (f"{n}: design {d.get('trades', 0)} trades, net Rs {rs(d.get('net'))}; final {f.get('trades', 0)} trades, "
            f"win {f.get('win', 'n/a')}%, Rs {rs(f.get('per_trade'))} a trade, net Rs {rs(f.get('net'))} (2x costs "
            f"{rs(f.get('net2'))}), p {p}, q {q}: {'PASS' if f.get('pass') else 'fail'}")


def main():
    L = json.loads((K.RESULTS / "literal.json").read_text())
    Dz = json.loads((K.RESULTS / "design.json").read_text())["cells"]
    F = json.loads((K.RESULTS / "final.json").read_text())["cells"]
    p = L["pooled"]
    oc = p["by_category"]["one_contract"]
    sw = p["by_category"]["any_switch_or_roll"]
    lit = dict(
        id="r59-literal", title="S2 Mean Reversion Dip Buy (outside AI) reproduced on the rolling stock-option series",
        family="outside-strategies", date="2026-10-15", doc=DOC,
        hypothesis=("Their +Rs 1,695 a trade (6,945 trades, 73.4% win) reproduces on the lake's rolling 15-minute ATM "
                    "series, and most of it comes from the series switching strike or expiry, not from held options."),
        data_version=DATA, period={"design": "4 Nov 2024 - 14 Oct 2026 (their span; an audit, no split)"},
        instruments=STOCKS, features=["signal source: the OPTION's own price chart (NON-DEFAULT, labelled)",
                                      "rolling ATM 15-minute closes (offset 0, last close in the bin)"],
        model_or_rule=("their code: pct_change of the rolling 15-minute ATM close <= -20% and close >= 5, CE and PE "
                       "separately; entry close[t], exit close[t+4]; Rs 10,000 notional, fractional contracts, Rs 95"),
        settings="research/hunt/r59/r59lit.py, research/hunt/r59/r59cfg.py", prereg=PREREG, variants_tried=None,
        results=dict(
            summary=(f"Reproduced: {p['trades']:,} trades, {p['win']}% win, Rs {rs(p['per_trade'])} a trade, total Rs "
                     f"{rs(p['total'])} (claim 6,945, 73.4%, +1,695, +1.177 crore); TCS CE Rs "
                     f"{rs(L['cells']['TCS_CE']['per_trade'])} a trade (claim ~+3,900). {sw['share_trades']}% of trades "
                     f"and {sw['share_profit']}% of profit involve a strike switch or expiry roll; the {oc['trades']} "
                     f"one-contract trades average Rs {rs(oc['per_trade'])}. {p['by_category']['rec_roll']['trades']} "
                     f"exits on the next expiry made Rs {rs(p['by_category']['rec_roll']['profit'])}. Their trades "
                     f"valued on the contract actually held: {p['held_contract_diag']['trades']:,} trades, Rs "
                     f"{rs(p['held_contract_diag']['per_trade'])} a trade (still with their Rs 95, no spread)."),
            costs="theirs: Rs 95 a trade flat, no spread, fractional Rs 10,000 notional"),
        holdout_read=None, verdict="not-a-test",
        notes="An audit of another AI's claim (shared by Boss on 15 Oct 2026). Nothing in the app changes.")
    ENT.joinpath("r59-literal.yaml").write_text(yaml.safe_dump(lit, sort_keys=False, allow_unicode=True, width=120))
    for fam, a in FAM.items():
        for v, vn in VAR.items():
            keys = [f"{fam}-{v}-{s}-{sd}" for s in K.STOCKS for sd in ("CE", "PE")] + [f"{fam}-{v}-ALL"]
            npass = sum(bool(F[k].get("pass")) for k in keys)
            summ = (f"{a['name']}, {vn}; 1 lot; 10 stocks x CE / PE + pooled. {npass} of {len(keys)} cells pass (BH "
                    f"over all 84 R59 cells). " + "; ".join(line(k, Dz.get(k, {}), F[k]) for k in keys) + ".")
            ent = dict(
                id=f"r59-{fam.lower()}-{v.lower()}", title=f"R59 {fam}: {a['name']}, {vn}",
                family=a["family"], date="2026-10-15", doc=DOC,
                hypothesis=(f"After a sharp 15-minute fall ({a['name']}), the ATM stock option bought at the next "
                            "minute and held 60 minutes on the same contract makes money after real costs, beats "
                            "random entries in the same session, per stock and side."),
                data_version=DATA,
                period={"design": "20 Nov 2024 - 31 Mar 2026",
                        "holdout": "final test: 1 Apr - 12 Oct 2026, read once (not pristine: other studies saw it)"},
                instruments=STOCKS, features=a["features"],
                model_or_rule=(a["rule"] + f"; {vn}; no overlap per stock and side; no trade on the expiry session "
                               "or the 2 sessions before it, or within 5 sessions of a corporate action"),
                settings="research/hunt/r59/r59cfg.py, research/hunt/r59/r59sim.py, results/spread.json",
                prereg=PREREG, variants_tried=len(keys), results=dict(summary=summ, costs=COSTS),
                holdout_read=True, verdict="rejected",
                notes=("Random twins: 500 sets, each trade re-entered at a random bar close of its session (same stock, "
                       "side, rules). " + ("NON-DEFAULT signal source (option chart): could only go to paper with "
                                           "Boss's explicit yes; nothing passed." if fam == "A" else
                                           "Default-compatible comparison (signal on the stock); nothing passed.")))
            ENT.joinpath(f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True,
                                                                        width=120))
    print("written")


if __name__ == "__main__":
    main()
