"""Write R160's experiment entries (research/experiments/entries/r160-*.yaml) from results/results.json.

    python3 -I -m research.hunt.r160_cas_gap.make_entries      (run from the repo root)
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

from . import cfg as K

ROOT = K.HERE.parents[2]
OUT = ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r160_cas_gap/results/report.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake (built 13-20 Oct 2026, to 19 Oct 2026): index_1m NIFTY/BANKNIFTY/SENSEX "
        "2025-2026, index_daily, fut_1m_live_index, options_rolling_1m NIFTY_WEEK/BANKNIFTY_MONTH/SENSEX_WEEK 2025-2026 "
        "(sha256 in PREREG section 2); bars to 15:29 only")
PERIOD = {"design": "none: POST 3 Aug - 16 Oct 2026 entry days (53 nights) is the test sample, read once 11 Nov 2026",
          "holdout": "none locked; control PRE 1 Jan 2025 - 31 Jul 2026 (389-392 nights); forward log per PREREG 9"}
SRC = ("signal source: the index read through its derivatives (option put-call parity over ATM-2..+2, futures as a "
       "check), late level 15:20-15:27 minus the 14:30-14:59 basis, vs the official close; no option chart for direction")


def _r(d: dict) -> str:
    return (f"n {d['n']}, slope {d['slope']}, r {d['pearson']}, rho {d['spearman']}, perm p {d['p']:.4f}, "
            f"hit {d['hit']} of {d['hit_n']}")


def entry(eid, title, family, hyp, instr, rule, variants, summary, costs, verdict, notes=None) -> dict:
    e = dict(id=eid, title=title, family=family, date="2026-11-11", doc=DOC, hypothesis=hyp, data_version=DATA,
             period=PERIOD, instruments=instr, features=[SRC], model_or_rule=rule,
             settings="research/hunt/r160_cas_gap/cfg.py (frozen in PREREG)",
             prereg="research/hunt/r160_cas_gap/PREREG.md", variants_tried=variants,
             results=dict(summary=summary, costs=costs), holdout_read=True if verdict != "not-a-test" else None,
             verdict=verdict)
    if notes:
        e["notes"] = notes
    return e


def build(res: dict) -> list[dict]:
    q = {f["test"]: f for f in res["family"]}
    m = res["m_total"]
    out = []
    for u, r in res["und"].items():
        d = r["describe"]
        lo, hi = r["H1_diff_ci95"]
        fq = q[f"H1 {u}"]
        out.append(entry(
            f"r160-h1-{u.lower()}", f"R160 H1: the next open moves back toward the late (auction-free) level: {u}",
            "market-facts",
            "Since the closing auction (3 Aug 2026), the next official open moves from the official close back toward where "
            "the index's derivatives traded at 15:20-15:27 (slope of gap on dislocation > 0).",
            [u], "OLS slope of G = ln(next open / close) on D = ln(late level / close), one-sided permutation p (100,000)", 3,
            f"POST {_r(r['H1_post'])}; BH q {fq['q_r160']} within R160 (10), {fq['q_all']} with m = {m}. "
            f"PRE control {_r(r['H1_pre'])}; slope POST - PRE 95% CI [{lo:.2f}, {hi:.2f}]. "
            f"Mean |D| POST {d['POST']['mean_abs_D']} bps vs PRE {d['PRE']['mean_abs_D']} bps (53 vs {d['PRE']['nights']} "
            f"nights). Futures-implied: {_r(r['H1_post_fut'])}. 09:20 instead of the open: {_r(r['H1_post_G20'])}. "
            "Bar: slope >= 0.25, BH q (m incl. earlier tests) <= 0.10, hit >= 55%. Not supported.",
            "none (index measure, no trade)", "rejected",
            "About half of the dislocation is corrected at the open, but the same was true before the auction "
            "(PRE slope similar); only the size of the dislocation is new."))
        fq = q[f"H2 {u}"]
        out.append(entry(
            f"r160-h2-{u.lower()}", f"R160 H2: is the move from the late level to the next open predictable: {u}",
            "market-facts",
            "An option bought at 15:2x-15:3x is priced off the late level, so a buyer gains only if R = ln(open / late "
            "level) has the sign of D (slope != 0).",
            [u], "OLS slope of R on D, two-sided permutation p (100,000); identical to H1's slope minus 1 (NOTES A3)", 3,
            f"POST {_r(r['H2_post'])}; BH q {fq['q_r160']} (R160), {fq['q_all']} (m = {m}). PRE {_r(r['H2_pre'])}. "
            f"Futures-implied POST {_r(r['H2_post_fut'])}. Negative: the open gives back about half the late move, "
            "so buying toward the correction at the late price has no edge.",
            "none (index measure, no trade)", "rejected"))
        fq = q[f"A {u}"]
        out.append(entry(
            f"r160-a-{u.lower()}", f"R160 (a): the 15:14-15:27 late move predicting the next open: {u}",
            "market-facts",
            "The derivatives' move during the frozen print (late level vs the 15:14 print) predicts the move from the late "
            "level to the next open.",
            [u], "OLS slope of R on M = ln(late level / 15:14 index), two-sided permutation p; G on M also reported", 2,
            f"POST R~M {_r(r['A_RM_post'])}; BH q {fq['q_r160']} (R160), {fq['q_all']} (m = {m}). "
            f"POST G~M {_r(r['A_GM_post'])}. PRE R~M {_r(r['A_RM_pre'])}.",
            "none (index measure, no trade)", "rejected",
            "SENSEX alone reached p < 0.01 (a reversal of the late move); not significant with earlier tests counted."
            if u == "SENSEX" else None))
    t = res["T1"]
    fq = q.get("T1 twin NIFTY", {})
    out.append(entry(
        "r160-t1", "R160 T1: buy the ATM option toward the expected auction-gap correction at 15:28, sell 09:16", "overnight",
        "Buying the side of D (call if the late level is above the official close) pays after real costs and beats a "
        "random side.",
        ["NIFTY"], "POST nights with |D| >= 5 bps, not expiry day; ATM weekly option at the 15:28 open (proxy for 15:3x), "
        "1 lot (65), sold at the 09:16 open next session", 1,
        f"{t['trades']} trades ({t['calls']} calls), Rs {t['per_trade']} a trade, win {t['win']:.0%}, total Rs {t['total']:,}, "
        f"worst Rs {t['worst']:,}, max DD Rs {t['max_dd']:,}; 1.5x spread Rs {t['per_trade_stress']} a trade; random-side "
        f"twin p {t['twin_p']:.3f} (twin mean total Rs {t['twin_mean_total']:,}); BH q {fq.get('q_all')} (m = {m}). "
        f"R146's Night (R3) NIFTY holdout: Rs 480 a trade (84 trades). Gate: H1 supported, H2 > 0, net > 0 at 1.5x "
        "spread, twin p <= 0.05, >= 20 trades. Failed.",
        "app fills (+-5 bps), dated charges (Costs('dated'): Rs 20 an order, STT 0.15%, exchange, SEBI, stamp, GST), "
        "0.16% half-spread on both legs; stress 1.5x spread", "rejected",
        "The entry is a proxy: the real entry would be after the official close is known (15:30-15:39), and those bars "
        "are not in the lake."))
    b = res["und"]["NIFTY"]["B_expiry"]
    out.append(entry(
        "r160-b-expiry", "R160 (b): expiry days under the closing auction, descriptive", "market-facts",
        "Describe how far the expiring options' freeze-time level is from the official close (the settlement proxy) on "
        "expiry days.",
        list(K.UNDS), "descriptive only (PREREG 5 (b)); no pass / fail", None,
        f"NIFTY {b['n_expiry']} expiry days: mean |D| {b['mean_abs_D_expiry']} bps vs {b['mean_abs_D_other']} on "
        f"{b['n_other']} other days; on expiry days the expiring options' level tracked the close more than futures did "
        "(corr with futures 0.59 vs 0.99 on other days; 15 Sep: +1.6 bps vs +34.7). SENSEX 11 expiry days: "
        f"{res['und']['SENSEX']['B_expiry']['mean_abs_D_expiry']} vs {res['und']['SENSEX']['B_expiry']['mean_abs_D_other']} bps.",
        "none", "not-a-test"))
    return out


def main() -> None:
    res = json.loads((K.RESULTS / "results.json").read_text())
    for e in build(res):
        e = {k: v for k, v in e.items() if v is not None or k in ("prereg", "variants_tried", "holdout_read")}
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print("wrote", e["id"])


if __name__ == "__main__":
    main()
