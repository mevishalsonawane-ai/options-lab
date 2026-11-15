"""R180 experiment entries: one YAML per gated test (Book 1, Book 2, combined) from results/results.json.

    python research/hunt/r180_dual_book_check/r180entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

import r180cfg as K
from r180report import ln, rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
RULES = {
    "B1": ("r180-manual-book1-r72x1", "Book 1 \"R72-X1 Breakout\"",
           "5-min Donchian 20 (prior bars) breaks on >= 4 of 5 indices on one bar, initial break of that index only, "
           "|C - edge| / ATR14 >= 0.5, ATM straddle above its 09:20 value; ATM option at the next minute's close; -30% "
           "premium stop, after +20% keep 50% of the peak gain, out at 60 min or 15:20; one position at a time",
           ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]),
    "B2": ("r180-manual-book2-ob-pullback", "Book 2 \"R132 S3-V1-E3 Order-Block Pullback\" (the manual's rule, not the "
           "repo's R132 S3-V1-E3)",
           "5-min fractal swings (k = 2), a displacement bar (body > 1.5 x the 20-bar median) breaking the last swing, "
           "its order block (last opposite candle within 5 bars, valid 60 min); 1-min pullback into the zone closing in "
           "the direction 12:15-14:45 with the ATM straddle < -5% vs 09:20; index stop past the far edge + 0.1 ATR, 60% "
           "rupee lock after +Rs 1,000, else 15:19; one position at a time",
           ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"]),
    "COMB": ("r180-manual-dual-book-combined", "the manual's combined one-position account",
             "Book 1 + Book 2 sharing one position (Book 1 first at the same minute), paused for the rest of a month at "
             "-Rs 11,000", ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]),
}


def main():
    r = json.loads((K.RESULTS / "results.json").read_text())
    dg = json.loads((K.RESULTS / "diag.json").read_text())
    for n, (eid, title, rule, unds) in RULES.items():
        x = r["runs"][n]
        s = x["split"]
        c = K.CLAIMS[n]
        o = r["runs"][n + "_open"]["split"]
        extra = ""
        if n == "B1":
            d1 = dg["B1_nolock"]
            extra = (f" Post-hoc (not gated): without the undefined initial-breakout lock {ln(d1['all'])} (design "
                     f"{ln(d1['design'])}, holdout {ln(d1['hold'])}), twin p {dg['twin_p']:.2f}: the count matches the "
                     f"manual's 361, the win rate does not.")
        if n == "COMB":
            ns = r["runs"]["COMB_NOSTOP"]["split"]
            extra = f" Without the month pause: {ln(ns['all'])} (manual 458 / 62.7% / Rs 74,907)."
        e = dict(
            id=eid,
            title=f"R180 outside build manual, {title}: rebuilt exactly as written on real option prices",
            family="patterns-indicators",
            date="2026-11-15",
            doc="research/hunt/r180_dual_book_check/results/report.md",
            hypothesis=(f"An outside PDF build manual sent to Boss claims {title} made {c['all'][0]} trades, "
                        f"{c['all'][1]}% wins, {rs(c['all'][2])} at 1 lot (design {c['design'][0]} / {c['design'][1]}% / "
                        f"{rs(c['design'][2])}; holdout {c['hold'][0]} / {c['hold'][1]}% / {rs(c['hold'][2])}) on our "
                        "real 1-minute option prices. Does it reproduce, and does it pass the repo's gates?"),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), ending 19 Oct 2026; "
                          "R51 Amendment A clean stores rebuilt for R180 from 1 Nov 2024 (clean_r180)"),
            period=dict(design="2024-11-04..2026-03-31 (the manual's design)",
                        holdout="2026-04-01..2026-10-19 (the manual's holdout; read by R51-R176 before, not clean)"),
            instruments=unds,
            features=["signal source: the index's 5-minute / 1-minute bars (underlying); ATM straddle vs 09:20 as an "
                      "option-chain filter; no option-chart signal"],
            model_or_rule=rule,
            settings="research/hunt/r180_dual_book_check/r180cfg.py; PREREG.md section 2 (every ambiguity and choice)",
            prereg="research/hunt/r180_dual_book_check/PREREG.md",
            variants_tried=2,
            results=dict(
                summary=(f"Ours: {ln(s['all'])} (manual {c['all'][0]} / {c['all'][1]}% / {rs(c['all'][2])}); design "
                         f"{ln(s['design'])}; holdout {ln(s['hold'])}; 2x slippage {rs(s['all']['net2'])}; avg win "
                         f"{rs(s['all']['avg_win'])} / loss {rs(s['all']['avg_loss'])}; worst month "
                         f"{rs(s['all']['worst_month'])}; max DD {rs(s['all']['max_dd'])}. Repo fill (next-minute open): "
                         f"{ln(o['all'])}. Random-entry twin p {x['twin_p']:.3f} (twin mean {rs(x['twin_mean'])}), BH q "
                         f"{x['bh_q']:.2f} (m = {r['m_total']:,}). Does not reproduce; fails: "
                         f"{', '.join(x['gates']['fails'])}.{extra}"),
                costs="Costs('dated') on both orders, R17 liq fills (stop sales x0.999 - 2x half-spread, gaps at the "
                      "open), 2x slippage = SLIP2; 1 lot by the lot size in force"),
            holdout_read=True,
            verdict="rejected",
            notes=("An outside document's claim, rebuilt as written; not reproduced. The manual reuses repo study names "
                   "(R72 X1, R132 S3-V1-E3) for different rules, and its Book 2 per-index rows add to 425 trades, not "
                   "271. Nothing in the app changes."),
        )
        (ENTRIES / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(RULES), "entries")


if __name__ == "__main__":
    main()
