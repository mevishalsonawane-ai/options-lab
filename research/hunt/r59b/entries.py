"""Writes research/experiments/entries/r59b-*.yaml: the literal audit (not a test) and one entry per honest family x
variant (A / B x NS / S25 / S25L, 21 cells each: 10 stocks x 2 sides + pooled), failures included.

    python3 -I research/hunt/r59b/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import yaml  # noqa: E402

import r59bcfg as K  # noqa: E402

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R59B_STOCK_BOLLINGER_AUDIT.md"
PREREG = "research/hunt/r59b/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake: stock_options_rolling_1m stkopt_<SYM>_MONTH (rolling ATM-1..ATM+1, "
        "nearest monthly expiry, 1-minute, rebuilt per held contract), equity_1m 2024-2026, nse_bhavcopy weekly UDiFF "
        "files (expiries, lots); options to 14 Oct 2026, equity to 12 Oct 2026")
INSTR = ["F&O stocks (stock options)"]
STOCKS_NOTE = ("Stocks: RELIANCE, HDFCBANK, ICICIBANK, INFY, TCS, SBIN, AXISBANK, KOTAKBANK, ITC, TMPV (Tata Motors).")
COSTS = ("1 lot in force (bhavcopy NewBrdLotQty per stock, expiry and date); next-minute fills at the open + 5 bps + the "
         "larger of R17 liq 1/2/4 ticks and the stock's R59 design Roll half-spread (0.26-0.50%); stops and locks pay "
         "double; R47 stray-print guard and foreign-rows filter; spikes.py flags dropped; Costs('dated') NSE charges; 2x "
         "costs (spread part and charges doubled) reported; gross before costs (raw prices) reported")
FAM = {
    "A": dict(name="their Bollinger(20, 2) band on the HELD contract", family="outside-strategies",
              features=["signal source: the OPTION's own price chart (NON-DEFAULT under CLAUDE.md, labelled)",
                        "Bollinger(20, 2) over the held contract's own 20 most recent 15-minute closes (no other "
                        "contract in the window; fewer than 20 bars: no signal)"],
              rule=("at a 15-minute bar close (bars 2-24), the ATM contract closes at or under the lower band of its own "
                    "last 20 bars and >= Rs 5 (last trade in the bar's last 5 minutes); buy it at the next 1-minute "
                    "open (within 5 min); exit 60 min later or 15:25 on the same contract")),
    "B": dict(name="the stock's own Bollinger(20, 2) band", family="stocks",
              features=["signal source: the STOCK (equity_1m 15-minute closes; default-compatible)",
                        "ATM option of the side bought; stops and locks on the option premium"],
              rule=("at a 15-minute bar close (bars 2-24), the stock closes at or under its lower band (buy the ATM CE) "
                    "or at or over its upper band (buy the ATM PE), band over its last 20 bars; buy at the next "
                    "1-minute open (within 5 min); exit 60 min later or 15:25 on the same contract")),
}
VAR = {"NS": "no stop (their exit, comparison only)",
       "S25": "25% premium stop on 1-minute rows (gap fills at the open)",
       "S25L": "25% premium stop, raised to breakeven once a 1-minute high reaches +20% (profit lock)"}


def rs(x):
    return "n/a" if x is None else f"{x:+,.0f}"


def line(n, d, f):
    p = "n/a" if f.get("p") is None else f"{f['p']:.3f}"
    q = "n/a" if f.get("q") is None else f"{f['q']:.2f}"
    return (f"{n}: design {d.get('trades', 0)} trades, net Rs {rs(d.get('net'))}; final {f.get('trades', 0)} trades, "
            f"win {f.get('win', 'n/a')}%, Rs {rs(f.get('per_trade'))} a trade (gross {rs(f.get('gross_per_trade'))}), "
            f"net Rs {rs(f.get('net'))} (2x costs {rs(f.get('net2'))}), p {p}, q {q}: "
            f"{'PASS' if f.get('pass') else 'fail'}")


def main():
    L = json.loads((K.RESULTS / "literal.json").read_text())
    Dz = json.loads((K.RESULTS / "design.json").read_text())["cells"]
    Fj = json.loads((K.RESULTS / "final.json").read_text())
    F, TU = Fj["cells"], Fj["tuning"]
    p, cc = L["pooled"], L["claim_checks"]
    cat = p["by_category"]
    lit = dict(
        id="r59b-literal",
        title="Bollinger Band Mean Reversion (outside AI) reproduced on the rolling stock-option series",
        family="outside-strategies", date="2026-10-15", doc=DOC,
        hypothesis=("Their +Rs 967 a trade (7,297 trades, 73.0% win) reproduces on the lake's rolling 15-minute ATM "
                    "series, and most of it comes from the series switching strike or expiry inside the band's window "
                    "or the hold, not from held options."),
        data_version=DATA, period={"design": "4 Nov 2024 - 14 Oct 2026 (their span; a check of their claim only)"},
        instruments=INSTR, features=["signal source: the OPTION's own price chart (NON-DEFAULT, labelled)",
                                     "rolling ATM 15-minute closes (offset 0, last close in the bin), Bollinger(20, 2)"],
        model_or_rule=("their code: rolling 15-minute ATM close <= Bollinger(20, 2) lower band (pandas rolling, ddof 1) "
                       "and close >= 5, CE and PE separately; entry close[t], exit close[t+4]; Rs 10,000 notional, "
                       "fractional contracts, Rs 95; overlaps allowed"),
        settings="research/hunt/r59b/r59blit.py, research/hunt/r59b/r59bcfg.py", prereg=PREREG, variants_tried=None,
        results=dict(
            summary=(f"Reproduced exactly: {p['trades']:,} trades, {p['win']}% win, Rs {rs(p['per_trade'])} a trade "
                     f"(median {rs(p['median'])}), total Rs {rs(p['total'])}, max drawdown Rs {rs(cc['max_drawdown'])}, "
                     f"{cc['trades_per_day']} trades a day (claim 7,297, 73.0%, +967, +626, +70.5 lakh, -31,485, 15.5). "
                     f"{cat['any_switch_or_roll']['share_trades']}% of trades and "
                     f"{cat['any_switch_or_roll']['share_profit']}% of profit have a strike switch or expiry roll in the "
                     f"band window or the hold ({cat['window_mixed']['share_profit']}% of profit from trades whose band "
                     f"window mixed contracts, {cat['rec_switch']['share_profit']}% from trades whose exit was another "
                     f"strike); the {cat['one_contract']['trades']} one-contract trades average Rs "
                     f"{rs(cat['one_contract']['per_trade'])} ({cat['one_contract']['win']}% win, their Rs 95, no "
                     f"spread). Their trades valued on the contract actually held: "
                     f"{p['held_contract_diag']['trades']:,} trades, Rs {rs(p['held_contract_diag']['per_trade'])} a "
                     f"trade."),
            costs="theirs: Rs 95 a trade flat, no spread, fractional Rs 10,000 notional"),
        holdout_read=None, verdict="not-a-test",
        notes="An audit of another AI's claim (shared by Boss on 15 Oct 2026). Nothing in the app changes. " + STOCKS_NOTE)
    ENT.joinpath("r59b-literal.yaml").write_text(yaml.safe_dump(lit, sort_keys=False, allow_unicode=True, width=120))
    for fam, a in FAM.items():
        for v, vn in VAR.items():
            keys = [f"{fam}-{v}-{s}-{sd}" for s in K.STOCKS for sd in ("CE", "PE")] + [f"{fam}-{v}-ALL"]
            npass = sum(bool(F[k].get("pass")) for k in keys)
            tu = TU[f"{fam}-{v}-ALL"]
            summ = (f"{a['name']}, {vn}; 1 lot; 10 stocks x CE / PE + pooled. {npass} of {len(keys)} cells pass (BH "
                    f"over all 126 R59b cells). Pooled gross before costs: design Rs {rs(tu['design_gross'])} a trade "
                    f"(t {tu['design_t']}), final Rs {rs(tu['final_gross'])} (t {tu['final_t']}) against a median cost "
                    f"of Rs {rs(tu['final_cost_median'])} a trade: a tuning round is "
                    f"{'worth trying' if tu['worth_trying'] else 'not worth it'}. "
                    + "; ".join(line(k, Dz.get(k, {}), F[k]) for k in keys) + ".")
            ent = dict(
                id=f"r59b-{fam.lower()}-{v.lower()}", title=f"R59b {fam}: {a['name']}, {vn}",
                family=a["family"], date="2026-10-15", doc=DOC,
                hypothesis=(f"After a 15-minute close beyond the Bollinger(20, 2) band ({a['name']}), the ATM stock "
                            "option bought at the next minute and held 60 minutes on the same contract makes money "
                            "after real costs and beats random entries in the same session, per stock and side."),
                data_version=DATA,
                period={"design": "20 Nov 2024 - 31 Mar 2026",
                        "holdout": "final test: 1 Apr - 12 Oct 2026, read once (not pristine: other studies saw it)"},
                instruments=INSTR, features=a["features"],
                model_or_rule=(a["rule"] + f"; {vn}; no overlap per stock and side; no trade on the expiry session "
                               "or the 2 sessions before it, or within 5 sessions of a corporate action"),
                settings="research/hunt/r59b/r59bcfg.py, research/hunt/r59b/r59bsim.py, research/hunt/r59/r59sim.py",
                prereg=PREREG, variants_tried=len(keys), results=dict(summary=summ, costs=COSTS),
                holdout_read=True, verdict="rejected",
                notes=("Random twins: 500 sets, each trade re-entered at a random bar close of its session (same stock, "
                       "side, rules). " + ("NON-DEFAULT signal source (option chart): could only go to paper with "
                                           "Boss's explicit yes; nothing passed. " if fam == "A" else
                                           "Default-compatible comparison (signal on the stock); nothing passed. ")
                       + STOCKS_NOTE))
            ENT.joinpath(f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True,
                                                                        width=120))
    print("written")


if __name__ == "__main__":
    main()
