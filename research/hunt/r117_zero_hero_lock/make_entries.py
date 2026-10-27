"""Writes research/experiments/entries/r117-<family>-<rule>.yaml: one per R117 test (12), failures included.

    python3 research/hunt/r117_zero_hero_lock/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r117cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
ENTRY = {"FT3": ("FT3 (frozen app version, research/hunt/r98/FORWARD_TEST.md): first 5-minute mark 11:00-12:55 with an OTM "
                 "option priced Rs 6-10, Black-76 IV <= 1.0333 x ATM IV, within 1.5466 ATM straddles; LIMIT capped at "
                 "price x 1.2 + 0.10 for 3 bars; Rs 5,000; all five indices' expiry days"),
         "HERO": ("Hero Rs 1,000 ticket (R55 / R54): NIFTY and SENSEX own expiry days, the bar closing 12:30, 1 lot CE + "
                  "1 lot PE, nearest eligible OTM priced Rs 5 - min(30, 1,000 / lot)")}
BASE_NAME = {"FT3": "hold to 15:05 (FT3's frozen exit)", "HERO": "the 25% trail from the fill (R55 T0, the arm's exit)"}


def inr(x: float) -> str:
    return f"{x:+,.0f}".replace("+-", "-")


def per(m: dict) -> str:
    return (f"{m['tickets']} tickets, win {m['win']:.1f}%, mean Rs {inr(m['mean'])}, total Rs {inr(m['total'])}, max DD "
            f"Rs {m['max_dd']:,.0f}, without the 3 best days Rs {inr(m['ex3'])}")


def main():
    res = json.loads((K.RESULTS / "results.json").read_text())
    n = 0
    for t in res["tests"]:
        f, x = t["family"], t["rule"]
        fm = res["families"][f]
        b, r = fm[K.FAMILY_BASE[f]], fm[x]
        summ = (f"{K.RULES[x]['name']} on the existing {f} tickets (entries unchanged), against {BASE_NAME[f]}. "
                + "; ".join(f"{p}: rule {per(r[p])} vs today's mean Rs {inr(b[p]['mean'])}" for p in K.PERIODS)
                + f". Reached 3x / 5x / 10x (all periods): {r['all']['r3']:.1f}% / {r['all']['r5']:.1f}% / "
                f"{r['all']['r10']:.1f}%; sold at >= 3x {r['all']['sold3']:.1f}%. Final twin p {t['twin']['p']:.4f} "
                f"({t['changed_final']} final tickets changed), BH q {t['q_all']:.3g} (m {res['m_all']:,}), zero-hero "
                f"families q {t['q_zero_hero']:.3g}. {'PASS' if t['gates']['passed'] else 'fail: ' + '; '.join(t['gates']['fails'])}.")
        ent = dict(
            id=f"r117-{f.lower()}-{x.lower()}", title=f"{f} ticket with a profit lock: {K.RULES[x]['name']}",
            family="calendar-events", date="2026-10-27", doc="research/HUNT_R117_ZERO_HERO_LOCK.md",
            hypothesis=(f"On expiry days, the existing {f} entry with the lock '{K.RULES[x]['name']}' makes more per ticket "
                        f"than {BASE_NAME[f]} in design, walk-forward and final, after real costs, and beats random "
                        "exit minutes."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 27 Oct 2026: options_rolling_1m opt_<UND>_WEEK "
                          "2021-2026 (rolling ATM+-10 1-minute), index_1m 2021-2026; lake to 19 Oct 2026 (27 Oct "
                          "not in it)"),
            period={"design": "expiry days to 31 Dec 2023", "walk_forward": "1 Jan 2024 - 31 Dec 2025 (nothing fitted)",
                    "holdout": ("final: 1 Jan - 19 Oct 2026, read once (entries not pristine: R98 / ft3_app and "
                                "R50-R55 read them; the locks were never run on them)")},
            instruments=list(K.R98_UNDS if f == "FT3" else ("NIFTY", "SENSEX")),
            features=[f"signal source: {K.SIGNAL_SOURCE}", "lock on the option premium"],
            model_or_rule=f"entry: {ENTRY[f]}; exit: {K.RULES[x]['name']}; the rest sells at 15:05",
            settings="research/hunt/r117_zero_hero_lock/r117cfg.py, research/hunt/r117_zero_hero_lock/r117lib.py",
            prereg="research/hunt/r117_zero_hero_lock/PREREG.md", variants_tried=1,
            results=dict(summary=summ, costs=("FT3: R98 charges (Rs 20 an order + GST, STT 0.15% on the sell, exchange, "
                                               "stamp), 1-tick sell spread, fills at the next open; Hero: R54 / R55 "
                                               "pricing (dated charges, R17 liquidity spread with the late multiplier, "
                                               "stray-print guard)")),
            holdout_read=True, verdict="passed" if t["gates"]["passed"] else "rejected",
            notes=("Random twins: 2,000 (seed 117); tickets whose exit differs from today's are sold whole at a random "
                   "bar open after the fill. FT3 is a frozen forward test: it never changes; a passer could only run "
                   "as a separate labelled paper variant. Nothing live."))
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print("wrote", n)


if __name__ == "__main__":
    main()
