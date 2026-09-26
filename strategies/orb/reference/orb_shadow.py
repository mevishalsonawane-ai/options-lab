"""Evening replay of the ORB rules on the day's real bars. No orders, no cash.

Both ORB arms trade the paper account; after the close both rules are also
replayed from the day's 5-minute bars with the live decision code. The real
paper trades decide each arm's pass rule and the head-to-head; the replay is the
cross-check -- where the two disagree, execution rather than the rule is the
likely cause. (orb was briefly replay-only on 2026-09-23, 16:3x-17:4x, which is
why this module exists.)

The replay is final_verdict/99_all_arms_backtest.py's, on the arm's own
functions: decide on a completed index bar, fill at the option's next bar open,
the stop at fill - 40 fills at the stop or at a gap's open, +40 target, 15:10
square-off. Bars are kept only where the index and both legs have one, as the
backtest's merge does. Charges per leg from sandbox.charges, as the paper
account debits them.

One JSON line per arm per day goes to ``LEDGER``; re-running a day replaces
that day's lines, so a manual re-run after a data hiccup is safe.

NIFTY orb_fresh is replayed too, as evidence only (final_verdict/109: thin and
far from proven): its own monthly contract, the at-the-money strike on NIFTY's
50-point step, +/-17 points, NIFTY's lot. A NIFTY failure never blocks the
BANKNIFTY record.
"""

from __future__ import annotations

import json
import os
from datetime import date, datetime
from pathlib import Path

from services.ai_signals import orb_arm
from services.ai_signals.orb_arm import Arm, Bar
from utils.logging import get_logger

logger = get_logger(__name__)

LEDGER_ENV = "ORB_SHADOW_LEDGER"
DEFAULT_LOT = 30
SHADOW_ARMS = (orb_arm.ORB, orb_arm.ORB_FRESH)
NIFTY_ARM = Arm("nifty_orb_fresh", fresh_only=True)
NIFTY, NIFTY_STEP, NIFTY_POINTS, NIFTY_LOT = "NIFTY", 50, 17.0, 65


def ledger_path() -> Path:
    return Path(os.getenv(LEDGER_ENV, "db/orb_shadow.jsonl"))


def align(index: list[Bar], ce: list[Bar], pe: list[Bar]) -> tuple[list[Bar], dict[str, list[Bar]]]:
    """Keep only the bar starts all three series have, in time order."""
    by_ce = {b.start: b for b in ce}
    by_pe = {b.start: b for b in pe}
    keep = [b for b in index if b.start in by_ce and b.start in by_pe]
    return keep, {"CE": [by_ce[b.start] for b in keep], "PE": [by_pe[b.start] for b in keep]}


def _charges(symbol: str, buy: float, sell: float, qty: int) -> float:
    from sandbox.charges import charge_for_fill

    kw = {"symbol": symbol, "exchange": "NFO", "product": "MIS", "quantity": qty}
    return float(charge_for_fill(action="BUY", price=buy, **kw)) + float(
        charge_for_fill(action="SELL", price=sell, **kw))


def replay(arm: Arm, index: list[Bar], legs: dict[str, list[Bar]], symbols: dict[str, str],
           lot: int = DEFAULT_LOT, points: float = orb_arm.TARGET_POINTS) -> list[dict]:
    """One arm's trades on one day. ``index`` and each leg are aligned bar for bar."""
    rng = orb_arm.opening_range(index)
    if rng is None:
        return []
    trades, k, last_exit, n = [], 0, None, len(index)
    while k < n - 1:
        direction, _ = orb_arm.entry_signal(index[:k + 1], rng, arm, last_exit)
        if direction == 0:
            k += 1
            continue
        right = "CE" if direction > 0 else "PE"
        leg = legs[right]
        entry = leg[k + 1].open
        if not entry > 0:
            k += 1
            continue
        exit_px, x, why = leg[n - 1].close, n - 1, "last_bar"
        for j in range(k + 1, n):
            t = index[j].start
            if t.time() >= orb_arm.SQUARE_OFF:
                exit_px, x, why = leg[j].open, j, "session_end"
                break
            if orb_arm.exit_reason(entry, leg[j].low, t, points) == "stop":
                exit_px, x, why = min(entry - points, leg[j].open), j, "stop"
                break
            if orb_arm.exit_reason(entry, leg[j].high, t, points) == "target":
                exit_px, x, why = entry + points, j, "target"
                break
        gross = (exit_px - entry) * lot
        charges = _charges(symbols[right], entry, exit_px, lot)
        trades.append({
            "signal_bar": index[k].start.strftime("%H:%M"), "exit_bar": index[x].start.strftime("%H:%M"),
            "symbol": symbols[right], "entry": round(entry, 2), "exit": round(exit_px, 2), "why": why,
            "gross": round(gross, 2), "charges": round(charges, 2), "net": round(gross - charges, 2),
        })
        last_exit = index[x].start
        k = x + 1
    return trades


def record(day: date, arm: Arm, trades: list[dict]) -> dict:
    """Write one arm's day to the ledger, replacing any earlier line for it."""
    path = ledger_path()
    row = {"day": str(day), "arm": arm.source, "trades": trades,
           "net": round(sum(t["net"] for t in trades), 2),
           "recorded_at": datetime.now(orb_arm.IST).replace(tzinfo=None).isoformat(timespec="seconds")}
    kept = []
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                old = json.loads(line)
            except ValueError:
                kept.append(line)  # never drop a line we cannot read
                continue
            if not (old.get("day") == row["day"] and old.get("arm") == row["arm"]):
                kept.append(line)
    kept.append(json.dumps(row))
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text("\n".join(kept) + "\n", encoding="utf-8")
    tmp.replace(path)
    return row


def run_evening(day: date | None = None, api_key: str | None = None) -> dict:
    """Replay every shadow arm for ``day`` (default today) and record it."""
    day = day or orb_arm._now().date()
    if api_key is None:
        from database.auth_db import get_first_available_api_key

        api_key = get_first_available_api_key()
    if not api_key:
        return {"status": "no_api_key"}
    index = orb_arm._index_bars(day, api_key)
    if not index:
        return {"status": "no_bars"}
    st = orb_arm._day_state(day, index, api_key)
    if st is None:
        return {"status": "no_contract"}
    symbols = {"CE": st["CE"], "PE": st["PE"]}
    kept, legs = align(index, *(orb_arm._bars(symbols[r], "NFO", day, api_key) for r in ("CE", "PE")))
    if orb_arm.opening_range(kept) is None:
        return {"status": "no_opening_range", "bars": len(kept)}
    lot = orb_arm._lot(symbols["CE"]) or DEFAULT_LOT
    out = {}
    for arm in SHADOW_ARMS:
        row = record(day, arm, replay(arm, kept, legs, symbols, lot))
        out[arm.source] = {"trades": len(row["trades"]), "net": row["net"]}
    try:
        out[NIFTY_ARM.source] = _nifty_evening(day, api_key)
    except Exception:
        logger.exception("orb shadow: NIFTY replay failed; BANKNIFTY is recorded")
        out[NIFTY_ARM.source] = {"status": "error"}
    return {"status": "recorded", "day": str(day), **out}


def _nifty_contract(day: date, index: list[Bar], api_key: str) -> dict | None:
    """NIFTY's monthly (the last listed expiry of each month; weeklies are
    skipped) and the at-the-money strike at the 09:20 bar."""
    monthly: dict[tuple[int, int], date] = {}
    for d in orb_arm._expiry_dates(NIFTY, api_key):
        key = (d.year, d.month)
        monthly[key] = max(monthly.get(key, d), d)
    later = sorted(d for d in monthly.values() if d > day)
    ref = next((b for b in index if b.start.time() >= orb_arm.STRIKE_BAR), None)
    if not later or ref is None:
        return None
    strike = orb_arm.atm_strike(ref.close, NIFTY_STEP)
    return {r: orb_arm.option_symbol(later[0], strike, r, NIFTY) for r in ("CE", "PE")}


def _nifty_evening(day: date, api_key: str) -> dict:
    index = orb_arm._bars(NIFTY, "NSE_INDEX", day, api_key)
    if not index:
        return {"status": "no_bars"}
    symbols = _nifty_contract(day, index, api_key)
    if symbols is None:
        return {"status": "no_contract"}
    kept, legs = align(index, *(orb_arm._bars(symbols[r], "NFO", day, api_key) for r in ("CE", "PE")))
    if orb_arm.opening_range(kept) is None:
        return {"status": "no_opening_range", "bars": len(kept)}
    lot = orb_arm._lot(symbols["CE"]) or NIFTY_LOT
    row = record(day, NIFTY_ARM, replay(NIFTY_ARM, kept, legs, symbols, lot, NIFTY_POINTS))
    return {"trades": len(row["trades"]), "net": row["net"]}
