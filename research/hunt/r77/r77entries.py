"""Writes research/experiments/entries/r77-*.yaml: the 8 pre-registered R77 tests, failures included.

    python research/hunt/r77/r77entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENT = ROOT / "research" / "experiments" / "entries"
DET = {"A": "volume >= 95th pct, range <= 40th pct", "B": "volume >= 90th pct, range <= 40th pct",
       "C": "volume >= 95th pct, range <= 25th pct", "D": "volume >= 90th pct, range <= 25th pct"}
EXI = {"X1": "index stop beyond the absorption bar, next-swing index target, -25% premium stop, 30 min, 15:15",
       "X2": "-15% premium stop, +30% premium target (2R), 30 min, 15:15"}


def rs(x):
    return "Rs 0" if not x else (f"-Rs {-x:,.0f}" if x < 0 else f"Rs {x:,.0f}")


def summ(s: dict) -> str:
    if not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {100 * s['win']:.0f}%, net {rs(s['net'])} ({rs(s['per_trade'])}/trade), worst day "
            f"{rs(s['worst_day'])}, max DD {rs(s['max_dd'])}")


def main():
    D = json.loads((HERE / "results" / "design.json").read_text())["tests"]
    F = json.loads((HERE / "results" / "final.json").read_text())["tests"]
    for old in ENT.glob("r77-*.yaml"):
        old.unlink()
    for test, d in D.items():
        f = F[test]
        det, xk = test.split("-")
        e = dict(
            id=f"r77-absorption-{test.lower()}",
            title=f"R77 absorption-bubble proxy {test}: {DET[det]}; exits {xk}",
            family="flow-oi", date="2026-10-18", doc="research/HUNT_R77_ABSORPTION_PROXY.md",
            hypothesis="Boss (18 Oct): a 1-minute index bar with high volume (nearest-expiry option volume, a proxy) and a "
                       "small range at a new 20-bar extreme, confirmed by the next bar, marks absorption; buying the 1-ITM "
                       "option for the reversal makes money after costs (net > 0 in design, walk-forward and final, >= "
                       "100 design trades, beats same-time-of-day random-entry twins with BH q <= 0.10 over 8 tests).",
            data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK; fut_1m_live_index for "
                         "the proxy check only), R51 Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026",
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (events from about 19 Dec 2024 after the 20-session warm-up; "
                               "walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout="1 Apr - 13 Oct 2026, read once; already read by R51 / R63 / R70 / R71 / R76 (weak)"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal: index 1-minute bars (range, 20-bar swing, confirmation bar); volume condition: the index's "
                      "nearest-expiry option volume (proxy, no futures history); trade: the 1-ITM option"],
            model_or_rule=f"detector {det} ({DET[det]} of the same minute over the previous 20 sessions), new 20-bar "
                          f"low / high, next bar confirms; long CE after a downside event, PE after an upside one; exits "
                          f"{xk}: {EXI[xk]}",
            settings="research/hunt/r77/r77rules.py, r77study.py",
            prereg="research/hunt/r77/PREREG.md", variants_tried=8,
            results=dict(summary=f"Design: {summ(d['design'])}. Walk-forward quarters: {summ(d['wf'])}. Final: "
                                 f"{summ(f['final'])}; twin p {f['p']}, BH q {f['q']}: {f['verdict']}"
                                 f"{' (' + '; '.join(f['fails']) + ')' if f['fails'] else ''}.",
                         costs="dated charges (STT 0.15% from 1 Apr 2026), R17 liq fills, next-minute fills, stops on "
                               "the minute low with gap fills at the worse open, one held contract, 1 lot"),
            holdout_read=True, verdict="paper-only" if f["verdict"] == "PASS" else "rejected",
            notes="A proxy: true absorption needs tick and book data (the phone's recordings, R10 section 4.2 test 4). "
                  "Research only; nothing changes paper or live.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=False, width=120))
    print(len(D), "entries")


if __name__ == "__main__":
    main()
