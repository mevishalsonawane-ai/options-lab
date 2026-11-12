"""R163: one experiment entry per test (4 models x 2 arms) from results/final.json, wf.json and pvalues.csv.

    python research/hunt/r163_donchian_picker/make_entries.py
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENTRIES = ROOT / "research" / "experiments" / "entries"
MODEL = {"G1": "LightGBM classifier P(win) (300 trees, lr 0.03, 15 leaves, min 50 a leaf, subsample 0.8, L2 1)",
         "G2": "LightGBM Huber regressor of net per trade (alpha Rs 1,000), same settings",
         "N1": "MLP 32-16 (sklearn, L2 0.01, early stop on the last 20% in time order), mean of 5 seeds",
         "E1": "calibrated ensemble: G1 + N1 + R151's logistic, each Platt-calibrated on the last 25% of training, averaged"}
ARM = {"R72": "R72 Breadth Donchian (Donchian 20 on 5-min index bars, 4 of 5 indices)",
       "R51": "R51 Donchian 20 + Supertrend (10, 3) on 5-min index bars"}


def main() -> None:
    f = json.loads((HERE / "results" / "final.json").read_text())
    w = json.loads((HERE / "results" / "wf.json").read_text())
    pv = {r["test"]: r for r in csv.DictReader((HERE / "results" / "pvalues.csv").open())}
    for arm in ("R72", "R51"):
        for mth in ("G1", "G2", "N1", "E1"):
            tid = f"r163-{arm.lower()}-{mth.lower()}"
            r, wm = f[arm]["methods"][mth], w[arm]["methods"][mth]
            m, b, pl = r["final"], f[arm]["P151"]["final"], f[arm]["plain"]
            pk = wm["pick"]
            wf_txt = (f"WF pick keep {pk['keep']:.0%}: {pk['trades']} trades, win {pk['win']:.1%}, Rs {pk['net']:,.0f} vs P151 "
                      f"{w[arm]['P151']['wf']['trades']} trades, {w[arm]['P151']['wf']['win']:.1%}, Rs {w[arm]['P151']['wf']['net']:,.0f}"
                      if pk else "WF: no threshold reached P151's net")
            e = {
                "id": tid,
                "title": f"R163 {mth} trade picker on {arm} (skip breaks scoring under a WF-chosen threshold, app walk)",
                "family": "ml",
                "date": "2026-11-12",
                "doc": "research/hunt/r163_donchian_picker/results/report.md",
                "hypothesis": f"A stronger picker ({mth}) on richer decision-minute features lifts {arm}'s win rate >= 5 points above "
                              "R151's live logistic picker without lower net, in walk-forward and final.",
                "data_version": "Drive lake gdrive:IraAlgo-data-lake (built 13-20 Oct 2026, to 19 Oct 2026): index_1m (5 indices + "
                                "INDIAVIX) 2024-2026, options_rolling_1m WEEK 2024-2026; R51 Amendment A clean stores",
                "period": {"design": "train from 20 Nov 2024; WF out-of-fold quarters 1 Apr 2025 - 31 Mar 2026 (expanding window)",
                           "holdout": "1 Apr - 13 Oct 2026, frozen model, read once 12 Nov 2026 (a second look: read by many earlier "
                                      "studies of these arms incl. R151 C / E)"},
                "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                "features": ["signal source: the index (the arm's own Donchian break); option-chain OI / PCR / IV / skew and the "
                             "bought contract's OI, volume and range as context only; no option-chart input",
                             "R151's 28 features + 36 new (index 15-60 min trend and vol, breadth, VIX change and percentile, "
                             "chain context, S/R counts, expiry and gap context), all at the decision minute"],
                "model_or_rule": f"{ARM[arm]}; 1-ITM, R101 XL exits; {MODEL[mth]}; threshold {r['thr']:.4g}"
                                 + ("" if r["wf_picked"] else " (report only: WF found none)"),
                "settings": "research/hunt/r163_donchian_picker/models.py (frozen in PREREG)",
                "prereg": "research/hunt/r163_donchian_picker/PREREG.md",
                "variants_tried": 15,
                "results": {
                    "summary": (f"FINAL (app walk, 1 lot): {m['trades']} trades, win {m['win']:.1%}, Rs {m['net']:,.0f} "
                                f"(2 lots Rs {m['net_2lots']:,.0f}, 3 lots Rs {m['net_3lots']:,.0f}); P151 {b['trades']} trades, "
                                f"{b['win']:.1%}, Rs {b['net']:,.0f}; plain {pl['trades']} trades, {pl['win']:.1%}, Rs {pl['net']:,.0f}. "
                                f"{wf_txt}. Exact random-skip twin p {float(pv[tid]['p_raw']):.3g}; BH q {float(pv[tid]['q_all']):.3g} "
                                f"(m = {f['_meta']['n_prior'] + 8}). Gate: win >= P151 + 5 pts and net >= P151 in WF and final, "
                                "q <= 0.10, >= 40 final trades, positive every period. Failed: " + "; ".join(r["fails"])),
                    "costs": "dated Zerodha charges (Rs 20 an order, STT, exchange, SEBI, stamp, GST) and R17 slippage (x1.0005 "
                             "buy, x0.9995 sell, x0.999 stop, one-tick half-spread); 2 / 3 lots from each trade's own fills",
                },
                "holdout_read": True,
                "verdict": "accepted" if r["pass"] else "rejected",
                "notes": "Random-skip twin is exact (hypergeometric): in the app walk the kept trades are a subset of the arm's.",
            }
            (ENTRIES / f"{tid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
            print("wrote", tid)


if __name__ == "__main__":
    main()
