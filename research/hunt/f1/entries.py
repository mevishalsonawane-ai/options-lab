"""Write the F1 experiment-log entries (research/experiments/entries/f1-*.yaml) from results/: the screen, the selection
step and one entry per rule, failures included.

    python research/hunt/f1/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_F1_FACTORY.md"
PREREG = "research/hunt/f1/PREREG.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): index_1m, options_rolling_1m opt_<UND>_WEEK (ATM+-10, nearest expiry, "
        "OI), INDIAVIX, equity_1m (58 stocks, breadth); brain cube + 201 features (research/brain/FEATURES.md); "
        "FINNIFTY/SENSEX expiries inferred (research/brain/expiry_infer.py); lake to 2026-10-12")
DESIGN = "2021-08-01..2022-12-14 and 2024-07-15..2026-04-13 (walk-forward folds 2022, 2024H2, 2025, 2026)"
HOLD = "2023-01-01..2024-06-30, all four indices, read once for every F1 rule together"
COSTS = ("app charges at today's rates (Rs 20/order, STT 0.15% sell, exchange NSE/BSE, SEBI, stamp, GST); fills at the "
         "next open +-5 bps plus a 1/2/4-tick half-spread by 5-min volume in lots (stops 10 bps, double ticks); today's "
         "lot sizes")
FAMILY = {"classic": "patterns-indicators"}


def rs(x):
    return f"Rs {x:+,.0f}"


def m_line(m):
    return (f"{m['trades']} trades on {m['days']} sessions ({m['trades_per_day']:.2f}/day), win {m['win_rate'] * 100:.0f}%, "
            f"avg win {rs(m['avg_win'])} / loss {rs(m['avg_loss'])}, net {rs(m['net'])} ({rs(m['pnl_per_day'])}/day), "
            f"green days {m['green_days_pct']:.0f}%, worst day {rs(m['worst_day'])}, max DD {rs(m['max_dd'])}, "
            f"winners cut short {m['cut_short']}, p vs twins {m['p_twin']:.3f}")


def family_of(r):
    if r["kind"] == "classic":
        return "patterns-indicators"
    f = r.get("feature", "")
    oi = ("pcr", "oi_", "bu_", "max_pain", "gex", "fut_", "iv", "straddle", "skew", "smile", "greek", "em_")
    return "flow-oi" if any(k in f for k in oi) else "patterns-indicators"


def main():
    summ = json.loads((K.RESULTS / "screen_summary.json").read_text())
    design = json.loads((K.RESULTS / "design.json").read_text())
    hold = {h["id"]: h for h in json.loads((K.RESULTS / "holdout.json").read_text())} \
        if (K.RESULTS / "holdout.json").exists() else {}
    tables = (K.RESULTS / "tables.md").read_text() if (K.RESULTS / "tables.md").exists() else ""
    base = dict(date="2026-10-14", doc=DOC, data_version=DATA, instruments=list(K.UNDS), settings=PREREG, prereg=PREREG)
    out = []
    out.append(dict(
        id="f1-screen", title="F1 screen: 201 brain features + 4 breadth features vs the next 15/30/60-min move and "
        "the ATM arms (4 indices + pooled)", family="patterns-indicators",
        hypothesis="Some point-in-time indicator (EMA, VWAP, Supertrend, RSI, MACD, ADX, squeeze, CPR, OI build-up, PCR, "
        "max pain, OI walls, IV, skew, straddle, GEX proxy, VIX, breadth, calendar) ranks the next index move or the "
        "option-buying arms' outcome out of sample.", **base,
        period=dict(design=f"fit split 2021-08-01..2022-12-14 + 2024-07-15..2024-12-31; selection split "
                    f"2025-01-01..2026-04-13; sessions with a chain {summ['days']}"),
        features=["all research/brain features", "br_adv_share", "br_above_vwap", "br_adv_share_chg15",
                  "br_bank_adv_share"],
        model_or_rule="Rank IC, direction from the fit split, day-clustered one-sided t on the selection split; "
                      "levels of few-valued features; 2-feature pairs of the 12 strongest per target; BH q 0.10 over all",
        variants_tried=int(summ["tests"]),
        results=dict(summary=f"{summ['tests']:,} tests; {summ['survivors']} survivors (BH q<=0.10, |IC|>=0.02, same "
                     f"sign). By target: {summ['by_target']}. By scope: {summ['by_scope']}. Details: {DOC}.",
                     costs=COSTS + " (arm targets)"),
        holdout_read=False, verdict="inconclusive",
        notes="The screen only chooses which rules to test; its survivors are information, not trading rules. Full "
              "table: research/hunt/f1/results/screen_by_feature.csv and Drive derived/f1_factory/screen.csv.gz."))
    for d in design:
        r = d["rule"]
        h = hold.get(r["id"])
        passed = bool(d["gate"]["pass"] and h and h["gate"]["pass"])
        summ_ = f"DESIGN (walk-forward 2022/2024H2/2025/2026): {m_line(d['design'])}; folds net " + ", ".join(
            f"{f} {rs(d['folds'][f]['net'])}" for f in K.FOLDS) + f"; design gates {'PASSED' if d['gate']['pass'] else 'FAILED'}."
        if h:
            summ_ += f" HOLDOUT: {m_line(h['holdout'])}, BH q {h['q_bh']:.3f}; {'PASSED' if h['gate']['pass'] else 'FAILED'}."
        out.append(dict(
            id=f"f1-{r['id'].lower()}", title=f"F1 rule {r['id']}: {r['name']}"[:200], family=family_of(r),
            hypothesis=("Boss's classic indicator stack picks better option-buying entries than random minutes."
                        if r["kind"] == "classic" else
                        f"The screen survivor ({r.get('screen_scope')}, {r.get('target')}, selection IC "
                        f"{r.get('ic_sel', 0):+.3f}) turns into option-buying profit after costs."), **base,
            period=dict(design=DESIGN, holdout=HOLD) if h else dict(design=DESIGN),
            features=[r.get("feature") or "c5_ema9_21_spread_pct, c5_supertrend_dir, ovwap_dist_pct, bu_writing_bias_15m"],
            model_or_rule=f"{r['name']}; 1-ITM, exit {r['exit']} (stop -15%, lock +Rs1,000 keep Rs 500 / trail Rs 500"
                          f"{', 30-min time stop unless +5%' if r['exit'] == 'x1' else ''}, out 15:10 / 15:05 expiry); "
                          f"one entry a side a day, decisions every 5 min 09:30-14:30; scope {', '.join(r['scope'])}",
            variants_tried=1, results=dict(summary=summ_, costs=COSTS), holdout_read=bool(h),
            verdict="paper-only" if passed else "rejected",
            notes="One of the F1 rules; BH over all of them on the holdout (m = the rule count)."))
    for e in out:
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"wrote {len(out)} entries")


if __name__ == "__main__":
    main()
