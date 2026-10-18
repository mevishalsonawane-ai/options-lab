"""Write one experiment-log entry per R83 idea (research/experiments/entries/r83-*.yaml) from results/summary.json.

    python -m research.hunt.r83.entries
"""
import json
from pathlib import Path

import yaml

from research.hunt.r83 import common as C

ROOT = Path(__file__).resolve().parents[3]
INFO = {
    "E1": ("EIA crude release: fade the first 15-minute spike", "calendar-events", ["CRUDE"], "k x ATR14d spike after the Wednesday EIA release, faded; stop beyond the spike extreme, target the pre-release price, 120 min", 2),
    "E2": ("EIA crude release: follow the break of the post-release range", "calendar-events", ["CRUDE"], "post-release range of 15/30 min, first close beyond it within 120 min followed; stop range middle, target edge + width, 90 min", 2),
    "E3": ("NG storage release: fade the first 15-minute spike", "calendar-events", ["NATURALGAS"], "as E1 on the Thursday EIA storage release", 2),
    "E4": ("NG storage release: follow the break of the post-release range", "calendar-events", ["NATURALGAS"], "as E2 on the Thursday EIA storage release", 2),
    "E5": ("OPEC+ meeting: follow the first post-meeting day's move", "calendar-events", ["CRUDE"], "sign of the first MCX daily move after the meeting, held 3/5 days, 2 x ATR stop", 2),
    "E6_CRUDE": ("US-open (NYMEX 09:00 ET) ORB on crude with trend / breadth agreement", "orb", ["CRUDE"], "09:00-09:15 ET range break by 11:00 ET, M3 USORB exits; taken only if the daily SMA20 trend (or natural gas's move since the open) agrees", 2),
    "E6_NG": ("US-open (NYMEX 09:00 ET) ORB on natural gas with trend / breadth agreement", "orb", ["NATURALGAS"], "as E6_CRUDE on Henry Hub; breadth = crude's move since the open agrees", 2),
    "E7": ("App Liquidity book on crude, 1h and 15m bars", "liquidity", ["CRUDE"], "swing_zones(20) + pool_zones(2, 5, 10), liquidity_gold exits, both sides, plus a 1 x ATR(14) hard stop", 2),
    "E8": ("Daily Donchian-20 with Supertrend / cross-market breadth agreement", "patterns-indicators", ["CRUDE", "NATURALGAS", "COPPER"], "close beyond the 20-day channel, 2 x ATR initial stop, 3 x ATR chandelier; Supertrend(10,3) or the other market's 50-day trend must agree", 6),
    "E9": ("Natural-gas month-of-year seasonality", "calendar-events", ["NATURALGAS"], "walk-forward Henry Hub month-of-year mean sign (|t| >= 1.5, or all months), monthly hold, 2.5 x ATR stop", 2),
    "E10": ("ATR-percentile regime filter on the app's NG evening breakout (EVE)", "mcx-crypto", ["NATURALGAS"], "McxEveRules unchanged, taken only when the previous day's ATR14 percentile over 250 days is < 50 (calm) / >= 50 (busy)", 3),
    "E11": ("App 12-month trend arm on energy legs: chandelier stop or weekly re-check", "mcx-crypto", ["CRUDE", "NATURALGAS"], "TSM252 on CRUDEOILM + NATGASMINI with a 3 x ATR chandelier, or a weekly sign check", 2),
    "E12": ("Rollover week: hold the near future over the last 5 days before expiry", "calendar-events", ["CRUDE", "NATURALGAS"], "long or short over the 5 MCX days before the roll, 2 x ATR stop", 4),
}


def main():
    s = json.loads((C.RESULTS / "summary.json").read_text())
    m = s["n_variants_bh"]
    for r in s["rows"]:
        idea = r["idea"]
        title, fam, inst, rule, nv = INFO[idea]
        daily = idea in ("E5", "E8", "E9", "E11", "E12")
        f = r["final"]
        real = r.get("mcx_real") or {}
        real_txt = "; ".join(f"{k}: {n} trades, Rs {v:+,.0f}" for k, (n, v) in real.items()) or "none (daily rule)"
        summ = (f"Frozen {r['frozen']}. Final 2023-01-01..2026-10-13: {f['trades']} trades, net Rs {f['net']:+,.0f} "
                f"(baseline Rs {r['base_final_net']:+,.0f}); walk-forward 2019-22 Rs {r['wf_net']:+,.0f} (baseline "
                f"Rs {r['base_wf_net']:+,.0f}); twin p {r['twin_p']:.3f}; BH q {r['bh_q']:.2f} across {m} variants; "
                f"net without the top 5 trades Rs {r['top5_removed']:+,.0f}. Real MCX replay Aug 2025 - Oct 2026: {real_txt}. "
                f"Gates failed: {', '.join(k for k, v in r['gates'].items() if not v)}.")
        e = {
            "id": f"r83-{idea.lower().replace('_', '-')}",
            "title": f"R83: {title}",
            "family": fam,
            "date": "2026-10-18",
            "doc": "research/HUNT_R83_MCX_ENERGY.md",
            "hypothesis": f"{title} earns after strict MCX costs on 1 mini lot, beating its baseline in walk-forward and final.",
            "data_version": ("Drive lake fut_daily_continuous (MCX, 2012-2026), Yahoo daily CL/NG/HG/INR" if daily else
                             "Dukascopy 1-min BID WTI/Henry Hub proxies 2016-2026 (/dev/shm, not committed); Drive lake options_rolling_1m MCX for the real replay"),
            "period": {"design": "2012-01-01..2022-12-31" if daily else "2016-01-01..2022-12-31",
                       "holdout": "2023-01-01..2026-10-13 (final, read once)"},
            "instruments": inst,
            "features": [f"signal source: {r.get('signal') or ''}".strip()] if False else
                        ["signal source: " + ("MCX daily future (copper: COMEX x USD/INR proxy)" if daily else "US benchmark proxy future (WTI / Henry Hub), labelled proxy")],
            "model_or_rule": rule,
            "settings": "research/hunt/r83/PREREG.md section 4",
            "prereg": "research/hunt/r83/PREREG.md",
            "variants_tried": nv,
            "results": {"summary": summ,
                        "costs": "McxCosts.kt charges; futures half-spread CRUDEOILM 0.03%, NATGASMINI 0.06%, COPPER 0.05% each side, +0.02% on stops; options CRUDEOIL 0.30/0.60%, NATURALGAS 0.45/0.90% half-spread"},
            "holdout_read": True,
            "verdict": "rejected",
        }
        if f["trades"] < 30:
            e["notes"] = "Too few final trades to judge (< 30); design and walk-forward were also negative or below baseline."
        p = ROOT / "research" / "experiments" / "entries" / f"{e['id']}.yaml"
        p.write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print(p.name)


if __name__ == "__main__":
    main()
