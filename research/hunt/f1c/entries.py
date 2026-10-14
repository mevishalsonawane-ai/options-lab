"""F1C experiment entries: research/experiments/entries/f1c-*.yaml from results/design.json (+ holdout.json).

    python -m research.hunt.f1c.entries

Written before the holdout read (holdout_read: false, verdict from the design gates) and rewritten once after it.
"""
from __future__ import annotations

import json

import pandas as pd
import yaml

from . import f1ccfg as C
from .rules import RULES

ENTRIES = C.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_F1_FACTORY.md"
PREREG = "research/hunt/f1c/PREREG.md"
DATA = ("Crypto brain cube v3 refreshed 14 Oct 2026 from the Drive crypto lake (Binance spot, USD-M perp, mark, index, "
        "funding, 5-min metrics; premium-index klines and SOL/BNB/XRP mark/index/metrics from data.binance.vision), "
        "Oct 2023 - Mar 2026; Deribit DVOL and option trades and Delta Exchange India 1-minute option candles from the "
        "options lake. Raw data and trade lists outside git.")
PERIOD = dict(design="2023-11-01..2025-03-31 and 2025-11-01..2026-03-31 (inside 00:00-09:00 IST only)",
              holdout="2025-04-01..2025-09-30 (clean earlier block; read once for all 20 rules)")


FEATS = {"P01": ["vwap24_z"], "P02": ["day_range_pos", "ret_60m", "atr14_1h_pct"],
         "P03": ["ema_rib15_dist_pct", "atr14_1h_pct"], "P04": ["ret_60m", "atr14_1h_pct"],
         "P05": ["perp_taker_imb_60m", "ret_60m"], "P06": ["vwap24_z"], "P07": ["pdh_dist_pct", "pdl_dist_pct"],
         "P08": ["spot minute bars (00:00-01:00 IST range)"], "P09": ["ema_rib15_width_pct", "ret_15m", "atr14_1h_pct"],
         "P10": ["ema_rib1h_order", "ema_rib15_order"], "P11": ["liq_flush_60m", "ret_60m"],
         "P12": ["day_high_dist_pct", "day_low_dist_pct"], "P13": ["oi_chg_60m_pct", "ret_60m", "atr14_1h_pct"],
         "P14": ["ema_rib15_slope_pct", "atr14_1h_pct"],
         "P15": ["vwap24_z", "day_range_pos", "ema_rib15_dist_pct", "perp_taker_imb_60m", "atr14_1h_pct"],
         "O16": ["vwap24_z"], "O17": ["ret_60m", "atr14_1h_pct"], "O18": ["pdh_dist_pct", "pdl_dist_pct"],
         "O19": ["ret_15m", "dvol"], "O20": ["spot minute bars (00:00-01:00 IST range)"]}


def _rs(x):
    return f"Rs {x:+,.0f}"


def rule_entry(rid, D, H):
    rule = RULES[rid]
    p = D["picks"][rid]
    V = pd.DataFrame(D["variants"])
    v = V[(V.rule == rid) & (V.exit_v == p["pick"])].iloc[0]
    allv = "; ".join(f"{r.exit_v}: n {int(r.trades)} net A {_rs(r.net_a)} twin p {r.twin_p:.3f}"
                     for r in V[V.rule == rid].itertuples())
    summ = (f"Pick {p['pick']} (best design net after tax A). Design: {int(v.trades)} trades ({v.trades_per_day:.2f} a "
            f"window day), win {v.win_rate:.0%}, avg win {_rs(v.avg_win)} / loss {_rs(v.avg_loss)}, net pre-tax "
            f"{_rs(v.net_pre_tax)}, after tax A {_rs(v.net_a)} ({_rs(v.per_day_a)} a day), business reading "
            f"{_rs(v.net_b)}; green days {v.green_days:.0%} of {int(v.days_with_trades)}, worst day {_rs(v.worst_day)}, "
            f"max DD {_rs(v.max_dd)}; walk-forward by quarter {_rs(p['wf_sum'])} ({p['wf_green']:.0%} green); stress "
            f"{_rs(v.stress_net_a)}; random twins p {v.twin_p:.3f} (BH q {v.twin_q:.3f}). Gates G1-G4: "
            f"{', '.join(k for k, ok in p['gates'].items() if ok) or 'none'} passed.")
    if H:
        h = H["rules"][rid]["pick_result"]
        summ += (f" Holdout (read once): {h.get('trades', 0)} trades, net A {_rs(h.get('net_a', 0))}"
                 f" (pre-tax {_rs(h.get('net_pre_tax', 0))}).")
    summ += f" All exits: {allv}."
    passed = H["rules"][rid]["passes"] if H else False
    return dict(
        id=f"f1c-{rid.lower()}", title=f"F1 crypto {rid}: {rule.title}", family="mcx-crypto",
        date="2026-10-14", doc=DOC,
        hypothesis=(f"'{rule.title}' on {'Delta India BTC/ETH options' if rule.venue == 'option' else 'perps'} "
                    f"({', '.join(c.replace('USDT', '') for c in rule.coins)}), inside 00:00-09:00 IST with a stop-loss "
                    "and a profit lock, makes money after Delta India fees + GST, slippage, funding and India's crypto "
                    "tax, beats random-entry twins after BH across 80 tests, and holds walk-forward and on the holdout."),
        data_version=DATA, period=PERIOD, instruments=["CRYPTO"],
        features=FEATS[rid] + (["atr14_1h_pct (stop size)"] if rule.venue == "perp" and "atr14_1h_pct" not in FEATS[rid]
                               else []),
        model_or_rule=f"{rule.title}; time limit {rule.max_hold_min or '09:00 IST'} min; max {rule.max_per_day} a day; "
                      f"exits: {', '.join(e.name for e in rule.exits)}",
        settings="research/hunt/f1c/rules.py, perp.py, options.py, f1ccfg.py", prereg=PREREG, variants_tried=4,
        results=dict(summary=summ, costs=("Delta India perps: 0.05% taker + 18% GST and 0.02% (BTC/ETH) / 0.05% "
                                          "slippage per side, Binance funding; options: min(0.01% notional, 3.5% "
                                          "premium) + GST, RC2 spread model, exits less 1 tick + 1%. Tax A 31.2% of "
                                          "each winning trade, no set-off; TDS 1% of sells tracked as a cash lock. "
                                          "Stress: slippage x2 (options spread x1.5, fee 0.03%).")),
        holdout_read=bool(H), verdict=("paper-only" if passed else "rejected") if H else
        ("inconclusive" if p["design_pass"] else "rejected"),
        notes="One of 20 F1C rules (80 variants, one BH family). Every trade had a stop-loss and a profit lock. "
              "Research only; nothing in the app trades crypto." + (
                  " Review: options filled at the signal minute's (possibly stale) close; with the next minute's close "
                  "and no stale prints (design, post hoc, results/strict_entry.json) every exit loses before tax."
                  if rule.venue == "option" else ""))


def write(D, H):
    ENTRIES.mkdir(parents=True, exist_ok=True)
    for rid in RULES:
        e = rule_entry(rid, D, H)
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    S = json.loads((C.RESULTS / "screen.json").read_text())
    scr = dict(
        id="f1c-screen", title="F1 crypto screen: 78 brain features vs the next 15/60/240-minute move, 5 coins",
        family="mcx-crypto", date="2026-10-14", doc=DOC,
        hypothesis="Some crypto brain features carry information about the direction or size of the next 15, 60 or "
                   "240 minutes (BTC, ETH, SOL, BNB, XRP), confirmed on a separate selection split after BH.",
        data_version=DATA, period=dict(design=PERIOD["design"].replace(" (inside 00:00-09:00 IST only)", "")),
        instruments=["CRYPTO"], features=["all 78 crypto brain features (research/brain/crypto/FEATURES.md)"],
        model_or_rule="Spearman rank correlation on non-overlapping rows; screen split Nov 2023-Sep 2024, selection "
                      "split Oct 2024-Mar 2025 and Nov 2025-Mar 2026; BH per family (direction, size) at q < 0.05",
        settings="research/hunt/f1c/screen.py", prereg=PREREG, variants_tried=2 * S["direction"]["tests"],
        results=dict(summary=(f"Direction: {S['direction']['tests']} tests, {S['direction']['bh_pass']} pass BH, "
                              f"{S['direction']['confirmed']} confirmed on the selection split (89% reversals: price "
                              "stretched from VWAP, the day's range or the EMA ribbon, or after one-sided taker flow, "
                              "comes back a little), median edge 0.7 bps against a 15.8-21.8 bps round trip; "
                              f"{S['direction']['pays']} pay on paper (XRP, 240 minutes). Funding, premium, long/short "
                              "ratios, DVOL, option skew, put/call, ATM IV, option flow and the calendar: noise for "
                              "direction; basis (SOL) and OI change (BTC, XRP) confirmed on one coin only. "
                              f"Size: {S['size']['tests']} tests, {S['size']['bh_pass']} pass BH, "
                              f"{S['size']['confirmed']} confirmed (realised vol and ranges). Rows per coin and test (15 / 60 / "
                              "240 min): screen " + " / ".join(f"{int(S['direction']['rows_screen'][h]):,}" for h in
                                                               ("15", "60", "240")) +
                              ", selection " + " / ".join(f"{int(S['direction']['rows_select'][h]):,}" for h in
                                                          ("15", "60", "240")) + "."),
                     costs="Round trip used for 'pays': 2 x (0.05% x 1.18 + slippage 0.02% BTC/ETH, 0.05% others)"),
        holdout_read=False, verdict="not-a-test",
        notes="A screen, not a trading test: its job was to choose the rules (PREREG part B).")
    scr["variants_tried"] = None
    (ENTRIES / "f1c-screen.yaml").write_text(yaml.safe_dump(scr, sort_keys=False, allow_unicode=True, width=120))
    ref = dict(
        id="f1c-brain-refresh", title="Crypto brain refresh for F1 (lake layout, ETH DVOL, option columns, EMA ribbons)",
        family="mcx-crypto", date="2026-10-14", doc="research/brain/crypto/README.md",
        hypothesis="Not a test: the crypto brain rebuilt on the Drive lake for five coins with new point-in-time features.",
        data_version=DATA, period=dict(design="2023-10-01..2026-03-31 (built); Apr 2026 on not built (the brain's lock)"),
        instruments=["CRYPTO"], features=["78 crypto features: 64 earlier + 7 EMA ribbon + 7 option market"],
        model_or_rule="cube v3, research/brain/crypto", settings="research/brain/crypto/config.py", prereg=None,
        variants_tried=None, results=dict(summary="913 cube days per coin (BTC, ETH, SOL, BNB, XRP), no gap minutes; "
                                                  "look-ahead guard passes on the synthetic tests and on the real cube "
                                                  "(BTC, ETH, SOL, 40 days, 6 cuts x 2 modes).", costs="none"),
        holdout_read=None, verdict="not-a-test")
    (ENTRIES / "f1c-brain-refresh.yaml").write_text(yaml.safe_dump(ref, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    D = json.loads((C.RESULTS / "design.json").read_text())
    H = json.loads((C.RESULTS / "holdout.json").read_text()) if (C.RESULTS / "holdout.json").exists() else None
    write(D, H)
    print("entries written")
