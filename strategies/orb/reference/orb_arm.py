"""Opening-range-break paper arm: the operator's own method, forward-tested.

Why it exists. The 2,800-combination search of his discretionary method
(final_verdict/90_every_path.py) found nothing as a whole, but every cell that
survived both halves was one family -- the index breaking its 09:15-10:00
opening range, trading that side's leg on ONE strike held all day, with a
40-point target and 40-point stop. That family also reproduced his 22 Sept
trades (4 of his 5 put entries, and the opposite of his one losing call entry),
and it is the first lead to pass both drop-best-3 and the up/down-day split.
It still sits at t ~ 1 against a 3.72 floor. So it is not a strategy; it is a
forward test. Pre-registration: final_verdict/92_orb_forward_test_preregistration.md.

The rule, exactly as backtested (best cell of the surviving family):
  * Strike: ATM from the 09:20 bar's BANKNIFTY close, rounded to 100, held all
    day. Contract: the nearest monthly expiry that is not today.
  * Opening range: high/low of the index 5m bars labelled 09:15 .. 10:00.
  * Decision on each COMPLETED 5m index bar labelled after 10:00 and before
    14:30: close above the range high buys the CE, below the low buys the PE.
  * One position at a time. Exit when the premium moves +40 or -40 from entry
    (decided by the shared risk core, services/risk), or at 15:10.
  * Re-entry allowed on a later bar after an exit.

Every order goes through dispatch_order with mode="sandbox", never
dispatch_mode(). The pipe is decided here, once: this arm is paper by
construction and cannot reach the broker even if analyze mode is switched off
while it holds a position (CLAUDE.md, "an order path ... never on a global
switch").
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from utils.logging import get_logger

logger = get_logger(__name__)

IST = ZoneInfo("Asia/Kolkata")
SOURCE = "orb"
UNDERLYING = "BANKNIFTY"
STRIKE_STEP = 100
TARGET_POINTS = 40.0
STOP_POINTS = 40.0
STRIKE_BAR = time(9, 20)
OR_START = time(9, 15)
OR_END = time(10, 0)
LAST_ENTRY_BAR = time(14, 30)
SQUARE_OFF = time(15, 10)
ACTIVE_FROM = time(9, 20)
ACTIVE_UNTIL = time(15, 12)
BAR = timedelta(minutes=5)
PIPE = "sandbox"
TICK = 0.05
MONTHS = ("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")


@dataclass(frozen=True)
class Bar:
    start: datetime
    open: float
    high: float
    low: float
    close: float


@dataclass(frozen=True)
class Arm:
    """One paper arm. Both arms share every rule; they differ only here."""

    source: str
    #: Index points AGAINST the entry at which a completed index bar's close
    #: closes the option trade. None = the running rule, option +40/-40 only.
    idx_stop: float | None = None
    #: Enter only on a FRESH break: the previous completed bar did not already
    #: close outside the range on the same side. A bar merely still outside
    #: the range an hour later carries no new information.
    fresh_only: bool = False


#: "orb" is the pre-registered running rule. "orb_idx" adds the index stop the
#: operator proposed ("check the regular index; if it passes a limit, close the
#: option"), backtested in final_verdict/93_index_exits.py and pre-registered
#: separately in final_verdict/94_orb_idx_preregistration.md. They trade the
#: same days head to head so the forward data decides whether the stop earns
#: its place. "orb_fresh" enters only on fresh breaks, pre-registered in
#: final_verdict/97_orb_fresh_preregistration.md after the backtest in
#: final_verdict/96_fresh_breaks.py.
#: orb_idx (Arm("orb_idx", 50.0)) was retired 2026-09-23 13:3x: all arms and
#: the AI engine share one Rs 1,00,000 sandbox wallet, and at high premiums
#: three lots do not fit. The operator chose to keep Rs 1L and run fewer arms.
#: The index-stop capability stays in the engine, tested, for a later test.
#: orb left the paper account at 16:3x on 2026-09-23 (shared wallet, its July
#: losses) and came back at 17:4x the same evening, before either had traded
#: again, when the operator switched the AI engine off and freed the wallet.
#: Both are also replayed every evening, no orders, by orb_shadow.py.
ORB = Arm("orb")
ORB_FRESH = Arm("orb_fresh", fresh_only=True)
ARMS = (ORB, ORB_FRESH)


# ---------------------------------------------------------------- pure rules
def index_stop_hit(bars: list[Bar], ref: float, direction: int, stop: float) -> bool:
    """Has any completed index bar since entry CLOSED `stop` points against it?"""
    return any(direction * (b.close - ref) <= -stop for b in bars)


def completed(bars: list[Bar], now: datetime) -> list[Bar]:
    """Only bars that have closed. The forming bar is never a signal."""
    return [b for b in bars if b.start + BAR <= now]


def opening_range(bars: list[Bar]) -> tuple[float, float] | None:
    """High and low of the bars labelled 09:15..10:00, once all of them exist."""
    window = [b for b in bars if OR_START <= b.start.time() <= OR_END]
    if not window or window[-1].start.time() != OR_END:
        return None
    return max(b.high for b in window), min(b.low for b in window)


def break_direction(bar: Bar, orh: float, orl: float) -> int:
    """+1 buys the CE, -1 buys the PE, 0 does nothing."""
    if bar.close > orh:
        return 1
    if bar.close < orl:
        return -1
    return 0


def may_decide(bar: Bar) -> bool:
    """Only bars after the range is complete and before the last entry bar."""
    return OR_END < bar.start.time() < LAST_ENTRY_BAR


def entry_signal(bars: list[Bar], rng: tuple[float, float], arm: Arm,
                 last_exit: datetime | None = None) -> tuple[int, str]:
    """The entry decision on the LAST completed bar: (direction, why).

    The live loop and the historical replay both call this, so what trades in
    the sandbox is provably what was backtested.
    """
    last = bars[-1]
    if not may_decide(last):
        return 0, "no_decision_bar"
    if last_exit is not None and last.start <= bar_of(last_exit):
        # The backtest resumes on the bar AFTER the one the exit happened in.
        return 0, "cooling_down_after_exit"
    direction = break_direction(last, *rng)
    if direction == 0:
        return 0, "inside_range"
    if arm.fresh_only and len(bars) > 1 and break_direction(bars[-2], *rng) == direction:
        return 0, "not_a_fresh_break"
    return direction, "break"


def bar_of(moment: datetime) -> datetime:
    """The start of the 5-minute bar a moment falls in."""
    return moment.replace(minute=moment.minute - moment.minute % 5, second=0, microsecond=0)


def atm_strike(spot: float, step: int = STRIKE_STEP) -> int:
    """Nearest strike, halves rounded UP. Python's round() is banker's rounding,
    which sends a spot exactly between strikes to whichever is even."""
    return int(math.floor(spot / step + 0.5) * step)


def option_symbol(expiry: date, strike: int, right: str, underlying: str = UNDERLYING) -> str:
    return f"{underlying}{expiry.day:02d}{MONTHS[expiry.month - 1]}{expiry.year % 100:02d}{strike}{right}"


def exit_reason(entry: float, ltp: float, now: datetime, points: float | None = None) -> str | None:
    """Why the open position should close now, or None to keep holding.

    The stop and target are decided by the shared risk core, never here: the
    rule is a fixed 40/40 on the premium of a bought option, which is exactly
    what services/risk evaluates for every other consumer.
    """
    if now.time() >= SQUARE_OFF:
        return "session_end"
    from services.risk.adapters import evaluate_trail

    verdict = evaluate_trail(
        {"entry_price": entry, "side": "BUY",
         "sl_points": STOP_POINTS if points is None else points,
         "target_points": TARGET_POINTS if points is None else points},
        ltp,
    )
    if not verdict.get("breached"):
        return None
    return {"sl": "stop", "target": "target"}.get(verdict.get("reason"), verdict.get("reason"))


def stop_trigger(entry: float) -> float:
    """The resting stop's trigger: the risk core's own stop level for this
    entry, on the exchange tick. The rule lives in services/risk; the SL-M order
    only implements it."""
    from services.risk.adapters import evaluate_trail

    level = evaluate_trail(
        {"entry_price": entry, "side": "BUY", "sl_points": STOP_POINTS,
         "target_points": TARGET_POINTS},
        entry,
    )["current_sl"]
    return round(round(float(level) / TICK) * TICK, 2)


# ---------------------------------------------------------------- live glue
_state: dict[date, dict] = {}


def _now() -> datetime:
    return datetime.now(IST).replace(tzinfo=None)


def in_window(now: datetime) -> bool:
    if now.weekday() >= 5 or not (ACTIVE_FROM <= now.time() <= ACTIVE_UNTIL):
        return False
    try:
        from database.market_calendar_db import is_market_holiday

        return not is_market_holiday(now.date(), "NSE")
    except Exception:
        logger.warning("orb_arm: holiday calendar unreadable; running anyway", exc_info=True)
        return True


def _index_bars(day: date, api_key: str) -> list[Bar]:
    return _bars(UNDERLYING, "NSE_INDEX", day, api_key)


def _bars(symbol: str, exchange: str, day: date, api_key: str) -> list[Bar]:
    """One day's 5-minute bars for any symbol, oldest first; [] when unavailable."""
    from services.history_service import get_history

    ok, resp, _ = get_history(symbol, exchange, "5m", str(day), str(day), api_key=api_key)
    if not ok:
        return []
    rows = resp.get("data") if isinstance(resp, dict) else resp
    out = []
    for r in rows or []:
        start = datetime.fromtimestamp(int(r["timestamp"]), IST).replace(tzinfo=None)
        out.append(Bar(start, float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"])))
    return sorted(out, key=lambda b: b.start)


def _expiry(day: date, api_key: str) -> date | None:
    later = sorted(d for d in _expiry_dates(UNDERLYING, api_key) if d > day)
    return later[0] if later else None


def _expiry_dates(underlying: str, api_key: str) -> list[date]:
    """Every listed option expiry for ``underlying``; [] when unavailable."""
    from services.expiry_service import get_expiry_dates

    ok, resp, _ = get_expiry_dates(underlying, "NFO", "options", api_key=api_key)
    if not ok:
        return []
    dates = []
    for item in resp.get("data") or []:
        raw = item.get("expiry") if isinstance(item, dict) else item
        try:
            dates.append(datetime.strptime(str(raw).upper(), "%d-%b-%y").date())
        except ValueError:
            continue
    return dates


def _ltp(symbol: str, api_key: str) -> float | None:
    from services.ai_signals.activity_actions import _current_price

    return _current_price(symbol, "NFO", api_key)


def _lot(symbol: str) -> int | None:
    try:
        from database.token_db import get_symbol_info

        info = get_symbol_info(symbol, "NFO")
        return int(info.lotsize) if info and info.lotsize else None
    except Exception:
        logger.exception("orb_arm: lot size lookup failed for %s", symbol)
        return None


def _day_state(day: date, bars: list[Bar], api_key: str) -> dict | None:
    """The day's contract, shared by every arm; each arm keeps its own spent bars."""
    st = _state.get(day)
    if st is None:
        ref = next((b for b in bars if b.start.time() >= STRIKE_BAR), None)
        expiry = _expiry(day, api_key)
        if ref is None or expiry is None:
            return None
        strike = atm_strike(ref.close)
        st = {"strike": strike, "CE": option_symbol(expiry, strike, "CE"),
              "PE": option_symbol(expiry, strike, "PE"), "decided": {}}
        _state.clear()
        _state[day] = st
        logger.info("orb_arm: %s strike %s (%s / %s)", day, strike, st["CE"], st["PE"])
    return st


#: The operator's "stop the arms for today" switch (Positions and Dashboard).
#: A small JSON file naming the session day it applies to -- not a database
#: column, because it lives for one day and needs no migration. A file from an
#: earlier day is simply ignored, which is what makes the arms start again at
#: 09:20 the next session without anyone pressing anything.
PAUSE_FILE_ENV = "ORB_ARMS_PAUSE_FILE"


def _pause_path():
    import os
    from pathlib import Path

    return Path(os.getenv(PAUSE_FILE_ENV, "db/orb_arms_pause.json"))


def paused(now: datetime | None = None) -> dict | None:
    """The stop record if the arms are stopped for ``now``'s day, else None."""
    try:
        data = json.loads(_pause_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    day = (now or _now()).date().isoformat()
    return data if isinstance(data, dict) and data.get("day") == day else None


def stop_for_today(now: datetime | None = None, by: str = "operator") -> dict:
    """Stop both arms for the rest of today. Their open positions are closed by
    the next arm cycle (the scheduler job, which runs one instance at a time),
    never from the caller's thread, so a stop can never race an arm's own exit."""
    now = now or _now()
    record = {"day": now.date().isoformat(), "stopped_at": now.isoformat(timespec="seconds"), "by": by}
    import os

    path = _pause_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(record), encoding="utf-8")
    os.replace(tmp, path)  # a reader never sees a half-written switch
    logger.info("orb_arm: stopped for %s by %s", record["day"], by)
    return record


def start_again(now: datetime | None = None) -> None:
    """Undo today's stop; the arms resume on their next decision bar."""
    if paused(now) is not None:
        _pause_path().unlink(missing_ok=True)
        logger.info("orb_arm: started again for %s", (now or _now()).date())


def arms_state(now: datetime | None = None) -> dict:
    """What the Stop/Start button shows."""
    from database import signal_db

    now = now or _now()
    since = datetime.combine(now.date(), time(0, 0))
    open_rows = [r for arm in ARMS for r in signal_db.ai_signal_activity_since(arm.source, since)
                 if r.status == signal_db.ACTIVITY_OPEN]
    from services.ai_signals import auto_trader_schedule

    stop = paused(now)
    return {"stopped": stop is not None, "stopped_at": stop.get("stopped_at") if stop else None,
            "scheduled": auto_trader_schedule.orb_scheduled(),
            "open_positions": len(open_rows), "arms": [a.source for a in ARMS],
            "resumes": "next session at 09:20" if stop else None}


def _flatten_stopped(now: datetime, arm: Arm) -> dict:
    """Stopped for today: no entries; close this arm's open position if any."""
    from database import signal_db
    from database.auth_db import get_first_available_api_key

    since = datetime.combine(now.date(), time(0, 0))
    open_rows = [r for r in signal_db.ai_signal_activity_since(arm.source, since)
                 if r.status == signal_db.ACTIVITY_OPEN]
    if not open_rows or now.time() > ACTIVE_UNTIL:
        # After 15:12 the sandbox square-off owns flattening; retrying a
        # forced exit every minute until midnight would only be noise.
        return {"status": "stopped_for_today"}
    api_key = get_first_available_api_key()
    if not api_key:
        return {"status": "no_api_key"}
    return _manage_open(open_rows[0], now, api_key, arm, force="operator_stop")


def run_all(now: datetime | None = None) -> dict:
    """One minute for every arm."""
    now = now or _now()
    if paused(now):
        return {arm.source: _flatten_stopped(now, arm) for arm in ARMS}
    return {arm.source: run_orb_cycle(now, arm) for arm in ARMS}


def run_orb_cycle(now: datetime | None = None, arm: Arm = ORB) -> dict:
    """One minute of one paper arm. Idempotent within a bar."""
    now = now or _now()
    if not in_window(now):
        return {"status": "outside_window"}

    from database import signal_db
    from database.auth_db import get_first_available_api_key

    api_key = get_first_available_api_key()
    if not api_key:
        return {"status": "no_api_key"}

    day = now.date()
    since = datetime.combine(day, time(0, 0))
    todays = signal_db.ai_signal_activity_since(arm.source, since)
    open_rows = [r for r in todays if r.status == signal_db.ACTIVITY_OPEN]

    if open_rows:
        return _manage_open(open_rows[0], now, api_key, arm)

    if now.time() >= SQUARE_OFF:
        return {"status": "flat_after_square_off"}

    bars = completed(_index_bars(day, api_key), now)
    rng = opening_range(bars)
    if rng is None:
        return {"status": "waiting_for_opening_range"}
    st = _day_state(day, bars, api_key)
    if st is None:
        return {"status": "no_contract"}

    last = bars[-1]
    spent = st["decided"].setdefault(arm.source, set())
    if last.start in spent:
        return {"status": "no_decision_bar"}
    spent.add(last.start)

    exits = [r.exit_time for r in todays if r.exit_time is not None]
    direction, why = entry_signal(bars, rng, arm, max(exits) if exits else None)
    if direction == 0:
        return {"status": why}
    return _enter(direction, last, rng, st, day, api_key, arm)


def _order_status(order_id: str, api_key: str) -> dict:
    from services.sandbox_service import sandbox_get_order_status

    try:
        ok, resp, _ = sandbox_get_order_status({"orderid": order_id}, api_key, {})
    except Exception:
        logger.exception("orb_arm: order status failed for %s", order_id)
        return {}
    return (resp or {}).get("data") or {} if ok else {}


def _cancel(order_id: str, api_key: str) -> bool:
    from services.sandbox_service import sandbox_cancel_order

    try:
        ok, _, _ = sandbox_cancel_order({"orderid": order_id}, api_key, {})
    except Exception:
        logger.exception("orb_arm: cancel failed for %s", order_id)
        return False
    return bool(ok)


def _meta(row) -> dict:
    try:
        return json.loads(row.request_summary or "{}")
    except ValueError:
        return {}


def _enter(direction: int, bar: Bar, rng: tuple[float, float], st: dict, day: date,
           api_key: str, arm: Arm) -> dict:
    from database import signal_db
    from services.strategy_module.order_dispatch import dispatch_order

    right = "CE" if direction > 0 else "PE"
    symbol = st[right]
    lot = _lot(symbol)
    ltp = _ltp(symbol, api_key)
    orh, orl = rng
    why = (f"ORB 10:00 break {'up' if direction > 0 else 'down'}: {UNDERLYING} "
           f"{bar.close:.2f} vs range {orl:.2f}-{orh:.2f} on the {bar.start:%H:%M} bar"
           + (f"; index stop {arm.idx_stop:g} pts" if arm.idx_stop else ""))
    # What survives a restart: where the index was at the decision, which way
    # the trade points, and which order is holding its stop.
    meta = {"idx_ref": bar.close, "dir": direction, "range": [orl, orh],
            "bar": bar.start.isoformat(timespec="minutes")}
    common = {"source": arm.source, "symbol": symbol, "exchange": "NFO",
              "signal_id": f"{arm.source}-{day}", "analysed_symbol": UNDERLYING,
              "direction": "LONG", "rationale": why}
    if lot is None or ltp is None:
        signal_db.record_ai_signal_activity(
            **common, request_summary=json.dumps(meta),
            ignored_reason="no lot size or no quote; refusing to enter blind")
        return {"status": "refused", "reason": "no lot or quote"}

    order = {"symbol": symbol, "exchange": "NFO", "action": "BUY", "quantity": str(lot),
             "product": "MIS", "pricetype": "MARKET", "price": "0", "trigger_price": "0",
             "strategy": arm.source}
    if paused():
        # Stop was pressed while this cycle was already deciding: honour it
        # before the order, not a minute later with a round trip.
        return {"status": "stopped_for_today"}
    result = dispatch_order(mode=PIPE, api_key=api_key, order=order)
    entry = ltp
    if result.ok:
        # Stops and P&L run off what was actually paid, not the quote read.
        fill = _order_status(result.broker_order_id, api_key).get("average_price")
        entry = float(fill) if fill else ltp
        trigger = stop_trigger(entry)
        # The stop rests in the sandbox's Stop-Loss book, checked every few
        # seconds by its engine, instead of a once-a-minute poll here. A poll
        # let a fast fall run 9.5 points past the stop on 2026-09-23.
        stop = dispatch_order(mode=PIPE, api_key=api_key, order={
            "symbol": symbol, "exchange": "NFO", "action": "SELL", "quantity": str(lot),
            "product": "MIS", "pricetype": "SL-M", "price": "0", "trigger_price": str(trigger),
            "strategy": arm.source})
        if stop.ok:
            meta["stop_order_id"] = stop.broker_order_id
            meta["stop_trigger"] = trigger
        else:
            logger.error("orb_arm[%s]: resting stop refused (%s); the polled stop still applies",
                         arm.source, stop.error)
    signal_db.record_ai_signal_activity(
        **common, request_summary=json.dumps(meta), entry_price=entry,
        stop_loss=stop_trigger(entry), targets=[round(entry + TARGET_POINTS, 2)],
        order_placed=result.ok, order_id=result.broker_order_id if result.ok else None,
        ignored_reason=None if result.ok else f"sandbox order refused: {result.error}",
        quantity=lot, product="MIS")
    logger.info("orb_arm[%s]: entered %s x%s at %.2f, resting stop %s (%s)", arm.source,
                symbol, lot, entry, meta.get("stop_order_id"), "ok" if result.ok else result.error)
    return {"status": "entered" if result.ok else "order_refused", "symbol": symbol}


def _settle_from_stop(row, stop_status: dict, arm: Arm) -> dict:
    """The resting stop filled on its own: record it. No order is sent."""
    from database import signal_db

    exit_px = float(stop_status.get("average_price") or 0.0)
    pnl = round((exit_px - float(row.entry_price or 0)) * (row.quantity or 0), 2)
    signal_db.settle_ai_signal_activity(row.id, exit_price=exit_px, pnl=pnl,
                                        close_reason=f"{arm.source}_stop")
    logger.info("orb_arm[%s]: resting stop filled for %s at %.2f, P&L %.2f", arm.source,
                row.symbol, exit_px, pnl)
    return {"status": "exited", "reason": "stop", "exit_price": exit_px, "pnl": pnl}


def _flatten_after_race(row, api_key: str, arm: Arm) -> None:
    """The stop filled AFTER its cancel was accepted, and the market exit also
    went through: the sandbox is now net SHORT one lot. Buy it back at once.
    CLAUDE.md: two decisions on one position must never stay two orders."""
    from services.strategy_module.order_dispatch import dispatch_order

    logger.error("orb_arm[%s]: stop and exit BOTH filled for %s; buying back %s to flatten",
                 arm.source, row.symbol, row.quantity)
    dispatch_order(mode=PIPE, api_key=api_key, order={
        "symbol": row.symbol, "exchange": "NFO", "action": "BUY",
        "quantity": str(row.quantity or 0), "product": "MIS", "pricetype": "MARKET",
        "price": "0", "trigger_price": "0", "strategy": arm.source})


def _manage_open(row, now: datetime, api_key: str, arm: Arm = ORB, force: str | None = None) -> dict:
    """Hold or exit one open position. ``force`` names an exit the operator
    ordered (the Stop button): it skips the stop/target checks and takes the
    same exit path as every other exit -- stop out of the book first, then sell."""
    from services.ai_signals.activity_actions import close_activity

    meta = _meta(row)
    stop_id = meta.get("stop_order_id")
    if stop_id:
        status = _order_status(stop_id, api_key)
        if status.get("order_status") == "complete":
            return _settle_from_stop(row, status, arm)
        if status.get("order_status") in ("cancelled", "rejected"):
            # Already out of the book: nothing to cancel. Treating it as live
            # made every later exit try to cancel it again and defer forever.
            stop_id = None

    if force:
        if _ltp(row.symbol, api_key) is None:
            # Same pre-check as the normal path: without a quote the sell
            # would be refused, and the resting stop must not be pulled for it.
            return {"status": "holding_no_quote"}
        reason = force
    else:
        ltp = _ltp(row.symbol, api_key)
        if ltp is None and now.time() < SQUARE_OFF:
            return {"status": "holding_no_quote"}
        # Same order as the backtest: the option's own stop/target first, then
        # the index close.
        reason = exit_reason(float(row.entry_price or 0), ltp or 0.0, now)
        if reason == "stop" and stop_id:
            # The resting order owns the stop. Racing it with a second sell is
            # how one position becomes two orders; wait for it to fill instead.
            reason = None
        if reason is None and arm.idx_stop:
            ref, direction = meta.get("idx_ref"), meta.get("dir")
            if ref is not None and direction:
                since_entry = [b for b in completed(_index_bars(now.date(), api_key), now)
                               if b.start >= bar_of(row.created_at)]
                if index_stop_hit(since_entry, float(ref), int(direction), arm.idx_stop):
                    reason = "index_stop"
        if reason is None:
            return {"status": "holding", "ltp": ltp}

    # The arm is exiting itself: take the resting stop out of the book first.
    if stop_id and not _cancel(stop_id, api_key):
        status = _order_status(stop_id, api_key)
        if status.get("order_status") == "complete":
            return _settle_from_stop(row, status, arm)
        return {"status": "exit_deferred", "reason": "resting stop could not be cancelled"}

    res = close_activity(row.id, api_key, close_reason=f"{arm.source}_{reason}", mode=PIPE,
                         strategy=arm.source)
    if stop_id and _order_status(stop_id, api_key).get("order_status") == "complete":
        _flatten_after_race(row, api_key, arm)
    logger.info("orb_arm[%s]: exit %s on %s: %s", arm.source, row.symbol, reason, res)
    return {"status": "exited" if res.get("ok") else "exit_refused", "reason": reason, **res}
