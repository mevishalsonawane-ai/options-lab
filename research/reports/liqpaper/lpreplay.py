"""Liquidity paper check (27 Oct 2026), research side: replay Liquidity 15+5's research rule on every October 2026
session from Upstox minutes (lpdata.py), with the research's own code, unchanged:

- signals: hunt/r51/fam_liq.py `signals` (H4's port of LiquidityRules.kt: pool-on-swing breaks on 15/30/5-minute INDEX
  bars from 10 calendar days of index minutes, entry 09:20-14:00, room >= 1 index stop to the next level, 1-ITM strike
  from the signal bar's close, index stop 30 / 15 / 8 points beyond the broken level, next-level target, failed break,
  new level);
- pricing: hunt/r51/engine.py `run_day` (the held contract's next-minute open, R17 liquidity slippage, -15% premium stop
  on the minute low, 20-minute time stop unless +5%, the index exits at the next minute's open, 15:10, dated charges);
- taking: one position per book (R101 `walk_book`, copied below), books BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5
  (r101cfg.LIQ_BOOKS), expiry days skipped.

Signal source: the index. The option is only bought; the stop and the 20-minute rule sit on its premium.

    python research/reports/liqpaper/lpreplay.py <out.json>      # every research trade of 1-26 Oct (outside git)
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import lpdata  # noqa: E402  (sets the research paths)
import r51cfg as K  # noqa: E402
import r51data  # noqa: E402

LIQ_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5)}      # r101cfg.LIQ_BOOKS
IDX_STOP_MIDCP = 8.0                                                                 # r101cfg.LIQ_IDX_STOP_MIDCP


def walk_book(cands: list, res: list) -> list:
    """One book's day (r101study.walk_book): candidates in time order; a filled one is taken when the book is free."""
    out, busy = [], -1
    for c, r in zip(cands, res):
        if r is None or c["er"] <= busy:
            continue
        busy = r["xr"]
        t = dict(r)
        t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def hhmm(row: int) -> str:
    m = K.OPEN_MIN + int(row)
    return f"{m // 60:02d}:{m % 60:02d}"


def load(stores: dict | None = None):
    """Put the Upstox stores where the research code reads them (r51data's cache), then import fam_liq / engine."""
    for und in LIQ_BOOKS:
        r51data._CACHE[und] = stores[und] if stores is not None else lpdata.build(und)
    import engine as E
    import fam_liq
    fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", IDX_STOP_MIDCP)
    fam_liq.signals.cache_clear()
    return E, fam_liq


def replay(lo: date, hi: date, stores: dict | None = None) -> tuple:
    """(trades, candidates): every research trade and every research signal (taken or not) of lo..hi."""
    E, fam_liq = load(stores)
    trades, allc = [], []
    for und, books in LIQ_BOOKS.items():
        days = r51data.load(und)
        for tf in books:
            sig = fam_liq.signals(und, tf, 1.0)
            for d in sorted(sig):
                if not lo <= d <= hi or not sig[d]:
                    continue
                D = days[d]
                cands = [dict(s, fx=fam_liq.forced_row(D, s), book=tf, und=und, sig_er=s["er"]) for s in sig[d]]
                res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands],
                                [c["er"] for c in cands], fam_liq.EX, [c["fx"] for c in cands])
                taken = walk_book(cands, res)
                for t in taken:                       # the fill minute's high (to test a paper fill against it)
                    C = D.contract(t["side"], t["strike"])
                    t["entry_high"] = float(C[1, t["er"]])
                keys = {(t["er"], t["side"]) for t in taken}
                for c, r in zip(cands, res):
                    allc.append(dict(und=und, day=d, book=tf, er=int(c["er"]), side=c["side"],
                                     strike=float(c["strike"]),
                                     filled=r is not None, taken=(c["er"], c["side"]) in keys))
                for t in taken:
                    t["book"] = tf
                    trades.append(t)
    trades.sort(key=lambda t: (t["day"], t["er"], t["und"], -t["book"]))
    return trades, allc


def row_of(t: dict) -> dict:
    """A research trade as a small row (prices per unit; % of the premium raw and after slippage and charges)."""
    qty = t["qty"]
    return dict(day=str(t["day"]), und=t["und"], book=t["book"], side=t["side"], strike=t["strike"],
                signal_bar=hhmm(t["sig_er"] - t["book"]), signal_end=hhmm(t["sig_er"]), entry=hhmm(t["er"]),
                exit=hhmm(t["xr"]), why=t["why"], qty=qty, entry_high=round(float(t["entry_high"]), 2),
                E=round(t["E"], 2), X=round(t["X"], 2), rE=round(t["rE"], 2), rX=round(t["rX"], 2),
                pct=round(100 * (t["X"] / t["E"] - 1), 2), pct_real=round(100 * (t["rX"] / t["rE"] - 1), 2),
                pct_net=round(100 * t["net"] / (t["rE"] * qty), 2), idx_stop=round(float(t["idx_stop"]), 2),
                target=None if t["target"] != t["target"] else round(float(t["target"]), 2),
                close=round(float(t["close"]), 2))


def main(argv):
    out = Path(argv[1]).resolve()
    trades, allc = replay(lpdata.FIRST, lpdata.LAST)
    rows = [row_of(t) for t in trades]
    out.write_text(json.dumps(dict(trades=rows, candidates=[dict(c, day=str(c["day"])) for c in allc]), indent=1))
    print(len(rows), "research trades ->", out)


if __name__ == "__main__":
    main(sys.argv)
