"""Writes research/experiments/entries/r21-*.yaml: one per variant plus the step-1 anatomy (failures included).

    python3 research/hunt/r21/entries.py            # from results/summary.json if it exists, else "pending"
"""
from __future__ import annotations

import json

import yaml

import config as K

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R21_HERO.md"
PREREG = "research/hunt/r21/PREREG.md"
DATA = ("research-data release: expiry-day chains from expiry_days.parquet (NIFTY 53 weeklies 2025-04-09..2026-04-13, "
        "BANKNIFTY 11 monthlies 2025-02-27..2026-01-27), index from nifty_prev/nifty_year and banknifty_*_wide, VIX "
        "from extra_series; the Dhan lake is not published yet")
PERIOD = dict(design="NIFTY expiries < 2025-10-13 (27), BANKNIFTY < 2025-08-25 (6)",
              holdout="NIFTY expiries 2025-10-13..2026-04-13 (26), BANKNIFTY 2025-08-25..2026-01-27 (5)")
VARIANTS = {
    "v0": ("Hero baseline: the app's rule (straddle >= 15% + 0.25% momentum, 13:30-14:45, Rs 1-5, app exits)",
           "The plain Hero rule is the baseline every R21 variant must beat."),
    "v1": ("Hero (a) one-sided trigger: the option's own close >= 2.5x its low since 12:00 + momentum agreeing",
           "A one-sided premium trigger catches one-way afternoons the straddle trigger misses, after costs."),
    "v2": ("Hero (b) straddle >= 10% at any minute in the last 15 + momentum",
           "A lower, non-coincident straddle condition catches more big days after costs."),
    "v3": ("Hero (a)+(c) one-sided trigger, window to 15:00", "A later window catches late moves (13 Oct started ~14:30)."),
    "v4": ("Hero (a)+(c)+(d) one-sided, window to 15:00, band Rs 1-15",
           "Letting strikes that already started moving in (to Rs 15) catches more of the move."),
    "v5": ("Hero V4 + lock: once 4x, a close <= 2x sells the rest", "Locking 2x keeps spikes that are given back by 15:05."),
    "v6": ("Hero V4 + give-back trail: once a close >= 3x, a close <= 50% of the best close sells",
           "A give-back trail keeps more of spikes than the plain targets."),
    "v7": ("Hero V4 with the plain 15:05 exit (no targets, no stop)", "The exits matter versus a plain 15:05 exit."),
    "v8": ("Hero (f) blind cheap buy at 13:30 on the side of the index move since 12:00",
           "Control: a blind cheap buy loses; a real trigger must beat it."),
}


def write(summary: dict | None) -> None:
    ENT.mkdir(parents=True, exist_ok=True)
    s = (summary or {}).get("variants", {})
    for vid, (title, hyp) in VARIANTS.items():
        r = s.get(vid.upper())
        e = dict(id=f"r21-{vid}", title=title, family="calendar-events", date="2026-10-13", doc=DOC, hypothesis=hyp,
                 data_version=DATA, period=PERIOD, instruments=["NIFTY", "BANKNIFTY"],
                 features=["ATM straddle vs its low since 12:00", "15-minute index move", "the option's own low since 12:00",
                           "option price band"],
                 model_or_rule=title, settings=PREREG, prereg=PREREG, variants_tried=1,
                 results=dict(summary=r["text"] if r else "pending: pre-registered, not yet run",
                              costs="app charges (current rates), liq fills: ask = open + 5 bps + 1-4 tick half-spread; "
                                    "stress x2 spread"),
                 holdout_read=bool(r), verdict=(r["verdict"] if r else "inconclusive"),
                 notes="One of 9 R21 variants; BH across all 9. 13 Oct 2026 is a forward example only (not in the data).")
        (ENT / f"r21-{vid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    a = dict(id="r21-anatomy", title="Hero R21 step 1: anatomy of expiry-day zero-to-hero moves (design days only)",
             family="market-facts", date="2026-10-13", doc=DOC,
             hypothesis="Describe when cheap expiry options run 5x/10x/20x and what precedes it; choose k for variant (a).",
             data_version=DATA, period=dict(design=PERIOD["design"]), instruments=["NIFTY", "BANKNIFTY"],
             features=["best multiple of any Rs 1-10 option", "trend", "gap", "max pain", "OI walls", "VIX",
                       "ATM call vs put change", "cheap-strike volume surge"],
             model_or_rule="descriptive", settings="research/hunt/r21/anatomy.py", prereg=None, variants_tried=None,
             results=dict(summary=(summary or {}).get("anatomy", "see research/hunt/r21/results/anatomy.md"),
                          costs="none (descriptive); the k-scan uses app charges and liq fills"),
             holdout_read=None, verdict="not-a-test")
    (ENT / "r21-anatomy.yaml").write_text(yaml.safe_dump(a, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    p = K.RESULTS / "summary.json"
    write(json.loads(p.read_text()) if p.exists() else None)
