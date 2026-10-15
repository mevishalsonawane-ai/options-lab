"""Write research/experiments/entries/r44-*.yaml from results/holdout.json (one per strategy id + the diagnostic).

    python3 research/hunt/r44/entries.py
"""
from __future__ import annotations

import json

import yaml

import r44cfg as K
from report import totals

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R44_SWEEP_RETEST.md"
DATA = ("Dhan lake on Drive gdrive:IraAlgo-data-lake (index_1m 2021-2026; options_rolling_1m nearest-expiry WEEK series "
        "for NSE/BSE, CRUDEOIL MONTH Aug 2025 - Oct 2026), to 12 Oct 2026")
TITLES = {
    "ema821_adx15": "EMA8/21 cross + ADX(14) > 15 on the 5-minute index, buy the ATM option (Boss's sweep re-test)",
    "ema821_adx15_plock": "EMA8/21 + ADX15 with a stepped profit lock (+25/50/75/100% peak -> lock 0/25/50/75%)",
    "ema821_adx15_plock_nt": "EMA8/21 + ADX15 stepped lock without the +50% target (sensitivity 1c)",
    "ema921_adx20": "EMA9/21 cross + ADX(14) > 20 on the 5-minute index, buy the ATM option",
    "ema926_adx20": "EMA9/26 cross + ADX(14) > 20 on the 5-minute index, buy the ATM option",
    "rsi_fade": "RSI(14) fade: CE when RSI crosses below 30, PE above 70 (5-minute index)",
    "rsi_momo": "RSI(14) momentum: CE when RSI crosses above 55, PE below 45 (5-minute index)",
    "donchian20": "Donchian 20-bar breakout on the 5-minute index close, buy the ATM option",
}


def rs(x):
    return f"Rs {round(x) + 0:,}"            # + 0: no "-0"


def main():
    hz = json.loads((K.RESULTS / "holdout.json").read_text())
    C = hz["cells"]
    for st in K.STRATS:
        parts = []
        for per, lab in (("IS", "in-sample 2021-23"), ("OOS_design", "OOS design 1 Jan 2024 - 13 Apr 2026"),
                         ("holdout", "holdout 27 Apr - 12 Oct 2026"), ("OOS_boss", "Boss's OOS 2024-26")):
            t = totals(C, per, strats={st}, mcx=False)
            parts.append(f"{lab}: {t['trades']:,} trades, real {rs(t['net'])}, Boss costs {rs(t['boss'])}, "
                         f"{t['positive']} of {t['cells']} index-side cells positive")
        tc = totals(C, "OOS_boss", strats={st}, mcx=True)
        cells = {k: v for k, v in C.items() if k.startswith(st + "|")}
        qs = [v["main:OOS_design"].get("q") for v in cells.values() if v["main:OOS_design"].get("q") is not None]
        npass = sum(v["gates"]["pass"] for v in cells.values())
        summary = ("NSE/BSE (5 indices x CE/PE, expiry days skipped, current lots): " + "; ".join(parts) +
                   f". CRUDEOIL (morning + full, Aug 2025 - Oct 2026): {tc['trades']:,} trades, real {rs(tc['net'])}, "
                   f"{tc['positive']} of {tc['cells']} cells positive. Lowest OOS-design BH q {min(qs):.3f} "
                   f"(family 112). {npass} of {len(cells)} cells pass the gates.")
        e = dict(
            id=f"r44-{st.replace('_', '-')}",
            title=TITLES[st],
            family="patterns-indicators",
            date="2026-10-14",
            doc=DOC,
            hypothesis=("Boss's sweep found this rule loses after costs in 2021-23 and 2024-26; an independent re-test "
                        "with our code and data agrees (and no cell passes the gates)."),
            data_version=DATA,
            period=dict(design="IS 2021-2023, OOS design 1 Jan 2024 - 13 Apr 2026 (CRUDEOIL Aug 2025 - 13 Apr 2026)",
                        holdout="27 Apr - 12 Oct 2026 (read once)"),
            # 14 Oct: the knowledge pack's instrument names (CRUDEOIL is "CRUDE" there; KnowledgeTest checks them).
            instruments=["CRUDE" if u == "CRUDEOIL" else u for u in K.UNDS],
            features=["5-minute underlying OHLC (index spot / CRUDEOIL future)", "signal on the underlying; ATM option bought"],
            model_or_rule=f"{st} (research/hunt/r44/r44cfg.py STRATS, ind.py signals, sim.py exits)",
            settings="research/hunt/r44/PREREG.md (Boss's spec: stop -25%, target +50%, 60-min exit, 15:15 square-off, "
                     "max 2 a day a side)",
            prereg="research/hunt/r44/PREREG.md",
            variants_tried=len(cells),
            results=dict(summary=summary,
                         costs="Boss Rs 95 (NSE) / Rs 200 (MCX) a round trip on raw fills; real: Fills('liq') slippage + "
                               "dated charges (Zerodha MCX charges for CRUDEOIL)"),
            holdout_read=True,
            verdict="rejected",
            notes=("Signal on the underlying. One of 8 R44 strategy ids; BH across all 112 R44 cells "
                   "(strategy x index x side x session); 2,000 random-entry twins per cell."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    d = json.loads((K.RESULTS / "diag_series.json").read_text())
    summ = "; ".join(f"{r['cell']}: Boss {r['boss_per_session']}/session vs ours WEEK {r['week']['per_session']}, "
                     f"WEEK+MONTH incl. expiry {r['week+month_incl_expiry_per_session']}" for r in d)
    e = dict(
        id="r44-diag-series", title="Why Boss's sweep has about 2x our trades: WEEK + MONTH series counted twice?",
        family="diagnosis", date="2026-10-14", doc=DOC,
        hypothesis="Boss's per-session trade counts match ours only when both the WEEK and MONTH series are traded.",
        data_version=DATA + "; MONTH files of NIFTY, BANKNIFTY, FINNIFTY for the diagnostic",
        period=dict(design="OOS design 1 Jan 2024 - 13 Apr 2026 only (the holdout was not read again)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY"], features=["trade counts"],
        model_or_rule="research/hunt/r44/diag_series.py", settings="Boss's comparable cells, both series, +-expiry days",
        prereg=None, variants_tried=None,
        results=dict(summary=summ + ". Consistent within 7-17% on 3 of 4 cells; FINNIFTY 'weekly' differs.",
                     costs="Boss Rs 95 and real costs (as R44)"),
        holdout_read=False, verdict="not-a-test", notes="Explains trade counts only; changes no verdict.")
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))


if __name__ == "__main__":
    main()
