"""R53: write one experiment-log entry per re-tested rule (research/experiments/entries/r53-*.yaml) from
results/r53_gates.json and the design files. Run after aggregate.py.

    python -m research.hunt.r53.entries
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import yaml

from research.hunt.r53 import common as C

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R53_MCX_CRYPTO_RECENT.md"
PREREG = "research/hunt/r53/PREREG.md"

# id: (title, instruments, rule, signal source)
RULES = {
    "MA1": ("app NG evening breakout (M3 EVE) as the app runs it", ["NATURALGAS"],
            "17:00-19:00 close range, first close beyond it 19:00-22:00, 1-ITM option, -15% stop, mid stop, edge + width, 120 min",
            "NATURALGAS near future"),
    "MA1b": ("M3 EVE on the GOLDM future", ["GOLD"], "EVE rule, GOLDM future 1 lot", "GOLDM near future"),
    "MA1c": ("M3 EVE on the CRUDEOIL option", ["CRUDE"], "EVE rule, CRUDEOIL 1-ITM option", "CRUDEOIL near future"),
    "MA2": ("app US-night silver (R2 C2) as the app runs it", ["SILVER"],
            "sign(SI=F + INR=X overnight move) at 09:05, SILVERMIC future, 3% stop, out 10 min before close",
            "SI=F and INR=X (Yahoo 1h)"),
    "MA3_fut": ("R2 C1 NG 09:05 gap follow, NATGASMINI future", ["NATURALGAS"], "sign of the 09:05 gap, out 14:00",
                "NATURALGAS near future"),
    "MA3_opt": ("R2 C1 NG 09:05 gap follow, NG 1-ITM option", ["NATURALGAS"], "sign of the 09:05 gap, out 14:00",
                "NATURALGAS near future"),
    "MA4_crudeoilm": ("R2 C3 gap-residual fade, CRUDEOILM future", ["CRUDE"], "fade res beyond 12 bp, out 14:00",
                      "CRUDEOIL future vs CL=F + INR=X"),
    "MA4_ng_opt": ("R2 C3 gap-residual fade, NG option", ["NATURALGAS"], "fade res beyond 22 bp, out 14:00",
                   "NATURALGAS future vs NG=F + INR=X"),
    "MA4_goldten": ("R2 C3 gap-residual fade, GOLDTEN future", ["GOLD"], "fade res beyond 11 bp, out 14:00",
                    "GOLDM future vs GC=F + INR=X"),
    "MA5": ("R2 C4 XAUUSD lead on GOLDM options (as run: first signal a day only)", ["GOLD"],
            "5-min XAU - GOLDM catch-up > 23.8 bp, 1-ITM option, 15 min", "XAUUSD and the GOLDM future"),
    "MA5u": ("R2 C4 XAUUSD lead on GOLDM options (pre-registered: no daily cap; read after the lock)", ["GOLD"],
             "5-min XAU - GOLDM catch-up > 23.8 bp, 1-ITM option, 15 min, one at a time", "XAUUSD and the GOLDM future"),
    "MA6": ("R3 GOLDM EMA8/21 + ADX15 morning option rule (outside report)", ["GOLD"],
            "5m EMA8/21 cross, ADX14 > 15, 09:00-14:55, ATM option, -25%/+50%, 60 min", "GOLDM near future"),
    "MA7": ("R6 Gann swing chart 15m on SILVERMIC (R6's closest)", ["SILVER"], "gann_swing 15m, longs, NAT exit",
            "SILVERM near future"),
    "MA8": ("M3 India-open range break on the GOLDM option", ["GOLD"], "09:00-09:30 range, break to 12:00, 1-ITM, 60 min",
            "GOLDM near future"),
    "MA9": ("M3 crude EIA release break", ["CRUDE"], "15-min pre-release range, break within 30 min, 1-ITM, 30 min",
            "CRUDEOIL near future"),
    "MA10": ("app MCX 12-month trend (M2 TSM252) on four legs", ["CRUDE", "NATURALGAS", "SILVER", "GOLD"],
             "monthly sign of the 252-day return, long/short 1 lot per leg", "continuous daily futures"),
    "CC1": ("RC2 R8 previous-day high/low break, Delta daily options", ["CRYPTO"], "pdl_break, ATM, -40% stop + step lock",
            "BTC/ETH perp"),
    "CC2": ("RC2 R4 volatility burst, Delta options", ["CRYPTO"], "vol_burst (DVOL), ATM, -40% stop + trail", "BTC/ETH perp + DVOL"),
    "CC3": ("RC2 R6 hero cheap OTM option", ["CRYPTO"], "hero, -70% stop + step lock", "BTC/ETH perp + DVOL"),
    "CC4": ("F1C P07 day-high/low break on perps in the 00-09 IST window", ["CRYPTO"], "1.5 ATR stop + trail lock, 180 min",
            "BTC/ETH perp"),
    "CC5": ("F1C P13 OI-falling big-hour fade on perps", ["CRYPTO"], "fade, ATR stop + step lock, 120 min",
            "BTC/ETH perp + open interest"),
    "CC6": ("F1C O19 volatility burst in the window, Delta options", ["CRYPTO"], "ATM option, -25% stop + step lock, 120 min",
            "BTC/ETH perp + DVOL"),
    "CC7": ("F1C O18 day-high/low break in the window, Delta options", ["CRYPTO"], "ATM option, -40% stop + trail, 180 min",
            "BTC/ETH perp"),
    "CC8": ("RC1 g 4h breakout on perps", ["CRYPTO"], "20-bar high break, 2 ATR stop, 10-bar-low trailing exit",
            "BTC/ETH perp"),
    "GB1": ("Boss's gold Option 1 (EMA9/21 + RSI, M15, H1 bias, 2R)", ["GOLD"], "gold_session_strats Option 1", "XAUUSD"),
    "GB2": ("Boss's gold Option 2 (Asian-range sweep)", ["GOLD"], "gold_session_strats Option 2", "XAUUSD"),
    "GB3": ("TAS 15m ATR 19 (Boss's own setting)", ["GOLD"], "gold_tas 15m with targets", "XAUUSD"),
    "GB4": ("TAS on 4h (targets) and 30m (no targets)", ["GOLD"], "gold_tas 4h / 30m", "XAUUSD"),
    "GB5": ("AI's Opinion V5 Robust on 1h and 4h (Boss's TradingView script)", ["GOLD"], "gold_ai_opinion Robust", "XAUUSD"),
    "GB6": ("gold reopen drift (reopen +5 to +120 min)", ["GOLD"], "gold_reopen", "XAUUSD"),
    "GB7": ("Liquidity India hours 1h+15m, buys", ["GOLD"], "liquidity_gold_1h India 1h+15m", "XAUUSD"),
    "GB8": ("Liquidity 24x5 on 2h candles", ["GOLD"], "gold_timeframes 2h", "XAUUSD"),
    "GB9": ("Liquidity 1h arm with pool confirmation 20", ["GOLD"], "gold_1h_plus conf 20", "XAUUSD"),
    "GB10": ("Liquidity India 15+5, buys", ["GOLD"], "liquidity_gold India 15+5", "XAUUSD"),
    "GB11": ("1h hammer, buys, 2R", ["GOLD"], "gold_candles hammer 1h", "XAUUSD"),
    "GB12": ("Dip arm entry with keep-half at 2 ATR, 8 h", ["GOLD"], "gold_mtf_lock keep half", "XAUUSD"),
    "GB13": ("Supertrend 4h with a 3-ATR giveback lock", ["GOLD"], "gold_trend_lock giveback 3", "XAUUSD"),
}


def _n_variants(book: str, rid: str) -> int | None:
    name = {"mcx": "mcx_design.json", "gold": "gold_design.json", "crypto": "crypto_design.json"}[book]
    if rid == "MA5u":
        name = "mcx_design_ma5u.json"
    p = C.RESULTS / name
    if not p.exists():
        return None
    js = json.loads(p.read_text())
    r = (js["rules"] if "rules" in js else js).get(rid)
    return len(r["variants"]) if r else None


def _fmt(v, nd=0):
    return "n/a" if v is None else (f"{v:,.{nd}f}" if isinstance(v, float) else str(v))


def entry(rid: str, rows: list[dict]) -> dict:
    book = rows[0]["book"]
    base = rid if rid in RULES else re.split(r"[_-]", rid)[0]
    title, inst, rule, src = RULES.get(base, (rid, ["GOLD"], rid, "XAUUSD"))
    if base != rid and base.startswith("GB"):
        title = f"{title} [{rid}]"
    money = "Rs" if book != "gold" else "USD per standard lot"
    parts = []
    for x in rows:
        f, d = x["final"], x["design"]
        net = f"{_fmt(f['net'])}" + (f" before tax, {_fmt(f['net_after_tax'])} after tax (Delta)" if book == "crypto" else "")
        parts.append(f"{x['variant']}: design {_fmt(d.get('trades'))} trades net {_fmt(d.get('net'))}, walk-forward "
                     f"{_fmt(x['wf_net'])}; final {f['trades']} trades, win {_fmt(100 * (f['win_rate'] or 0))}%, net {net}, "
                     f"p {_fmt(x['p'], 3)}, q {_fmt(x['q'], 3)} -> {x['verdict']}")
    passed = any(x["verdict"] == "PASS" for x in rows)
    mcx_min = book == "mcx" and rid != "MA10"
    e = {
        "id": f"r53-{rid.lower().replace('_', '-')}",
        "title": f"R53: {title}, re-tested on Nov 2024+ data",
        "family": "mcx-crypto",
        "date": "2026-10-15",
        "doc": DOC,
        "hypothesis": f"The rejected rule ({rule}) earns after real costs on the recent market only (Boss, 15 Oct).",
        "data_version": {"mcx": "Drive lake options_rolling_1m (MCX, from 5 Aug 2025), fut_daily_continuous, Yahoo 1h, "
                                "Dukascopy XAUUSD 1m",
                         "gold": "Dukascopy XAUUSD 1m bid (release research-data + tail to 12 Oct 2026)",
                         "crypto": "Binance USD-M 1m perps, Deribit DVOL, Binance 5m OI, Delta India 1m option marks"}[book],
        "period": {"design": ("2025-08-05" if mcx_min else "2024-11-20") + "..2026-03-31",
                   "holdout": "2026-04-01..2026-10-12 (final test, read once)"},
        "instruments": inst,
        "features": [f"signal source: {src} (underlying)"],
        "model_or_rule": rule,
        "settings": PREREG,
        "prereg": PREREG,
        "variants_tried": _n_variants(book, rid),
        "results": {"summary": f"{money}. " + "; ".join(parts),
                    "costs": {"mcx": "Zerodha MCX charges + half spread per side (x2 before 17:00 for options)",
                              "gold": "ask = bid + 0.30, $7/lot, $40/lot/night swap where held",
                              "crypto": "Delta India fees + 18% GST, RC2 spread model, fills 1 min later; CoinSwitch PRO "
                                        "with/without GST; 31.2% tax per winning trade, TDS as cash flow"}[book]},
        "holdout_read": True,
        "verdict": "paper-only" if passed else (
            "inconclusive" if all(x["verdict"] == "too few trades to judge" for x in rows) else "rejected"),
    }
    if rid == "GB9":
        e["notes"] = ("One final entry (7 Apr 2026 21:00 UTC) sat in the daily break at a stale close; at the next real "
                      "price the final net is +25,490, not +26,813 (gold/NOTES.md). Too few trades either way.")
    if rid == "GB9":
        e["notes"] = ("One final entry (7 Apr 2026 21:00 UTC) sat in the daily break at a stale close; at the next real "
                      "price the final net is +25,490, not +26,813 (gold/NOTES.md). Too few trades either way.")
    if rid == "MA5u":
        e["notes"] = "Post-lock correction: the capped MA5's final test had been seen first, so this cannot be a clean pass."
    return e


def main():
    g = json.loads((C.RESULTS / "r53_gates.json").read_text())["rows"]
    by = {}
    for x in g:
        by.setdefault(x["rule"], []).append(x)
    for rid, rows in by.items():
        e = entry(rid, rows)
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(by), "entries")


if __name__ == "__main__":
    main()
