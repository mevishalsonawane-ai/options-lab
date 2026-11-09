"""R155 experiment entries: one YAML per filter x entry x reading (its 12 exit rules), failures included.

    python research/hunt/r155_scalp_filters/r155entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r155cfg as K
from r155report import pct, rs

K4 = K.K4
ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"
FAMILY = {"S/R": "patterns-indicators", "trend": "patterns-indicators", "OI": "flow-oi", "wick": "patterns-indicators",
          "all": "ml"}


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for f, e, rd in K.TESTS:
        g = dec[f"{f}:{e}:{rd}"]
        s, b = g["stats"], g["f0"]
        summ = (f"Pooled over {', '.join(K4.ENTRY_UNDS[e])} and the 12 {'premium' if rd == 'A' else 'index'}-point exits, 1 lot. "
                f"P1 {s['P1']['trades']} trades, {rs(s['P1']['net'])}; P2 {s['P2']['trades']}, {rs(s['P2']['net'])}; P3 "
                f"{s['P3']['trades']}, win {pct(s['P3']['win'])} (break-even {pct(s['P3']['be_win'])}), {rs(s['P3']['net'])} "
                f"({rs(s['P3']['per_trade'])} a trade vs {rs(b['P3']['per_trade'])} unfiltered). Random-skip p {g['twin_p']}, "
                f"BH q {g['q']} (m {dec['_m']['total']}). " + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + "."))
        entry = dict(
            id=f"r155-{f.lower()}-{e.lower()}-{rd.lower()}",
            title=f"R155 {f} ({K.FNAME[f]}) on R154's {K4.ENTRY_NAME[e]} 10-15 point scalps, reading {rd}",
            family=FAMILY[K.FAMILY[f]],
            date="2026-11-09",
            doc="research/hunt/r155_scalp_filters/results/report.md",
            hypothesis=(f"Boss (9 Nov): use the Donchian filters, OI and candle wicks on the scalps. Taking only the scalp "
                        f"signals that pass {f} ({K.FNAME[f]}) makes the 10-15 point scalp profitable: net > 0 in P1, P2, "
                        "P3; better per trade than unfiltered in P2 and P3; beats random skips with BH q <= 0.10; >= 40 "
                        "final trades per exit" + ("; above the arm's own exits." if e in K4.ARMS else ".")),
            data_version=("Drive lake (index_1m, options_rolling_1m WEEK incl. OI) copied 9 Nov 2026; R154's stores (R51 "
                          "Amendment A clean from 20 Nov 2024, R154 early store before); R155 OI store"),
            period=dict(design="P1 to 2025-03-31 (plain entries from Aug 2021; arms from 2024-11-20); P2 2025-04-01..2026-03-31",
                        holdout="2026-04-01..2026-10-13, second look (read for these scalps by R154); post 14-19 Oct reported"),
            instruments=list(K4.ENTRY_UNDS[e]),
            features=[f"signal source: {K4.SIGNAL_SRC[e]}; buys the 1-ITM option", f"filter: {K.FNAME[f]} ({K.FAMILY[f]})"],
            model_or_rule=f"{K4.ENTRY_NAME[e]} + {f}; exits {', '.join(K.rules_of(rd))}",
            settings="research/hunt/r155_scalp_filters/r155cfg.py; PREREG.md section 2",
            prereg="research/hunt/r155_scalp_filters/PREREG.md",
            variants_tried=12,
            results=dict(summary=summ, costs="dated Zerodha charges, R17 fills, limit targets at their price, 1 lot"),
            holdout_read=True,
            verdict="paper-only" if g["passed"] else "rejected",
            notes="Signal on the underlying; filters on the index or the option chain as context; nothing changes live.",
        )
        (ENTRIES_DIR / f"{entry['id']}.yaml").write_text(yaml.safe_dump(entry, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
