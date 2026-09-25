"""Drive IraAlgo's Python sandbox through scripted scenarios and dump fixtures.

The Kotlin port (com.optionslab.engine.sandbox) is asserted against these, so
every number in them comes from running the real Python, not from reading it:

    cd /home/user/finalproducttradingapp
    uv run python <this file> <output dir>

Each scenario runs in its own subprocess against a fresh temp SQLite database.
Three things the Python normally takes from the outside world are pinned:

* the clock - ``datetime.datetime`` is replaced (freezegun-style) and SQLite's
  ``CURRENT_TIMESTAMP`` (``func.now()``, which writes ``updated_at``) is
  compiled to a function that reads the same fake clock, in UTC as SQLite would;
* quotes - the engine's quote fetchers read a dict each step fills in;
* the symbol master - ``get_symbol_info`` answers from the scenario's
  instruments.

Order and trade ids are made sequential with the same scheme the Kotlin port
uses, so fixtures can be compared id for id. Nothing else is patched: margin,
netting, reconciliation, settlement and every refusal are the Python's own.
"""

import json
import os
import subprocess
import sys
import tempfile

REPO = "/home/user/finalproducttradingapp"

# ---------------------------------------------------------------------------
# Scenarios. Times are IST wall clock. Quotes: {"SYM|EXCH": {...}}.
# ---------------------------------------------------------------------------

EQ = [
    {"symbol": "RELIANCE", "exchange": "NSE", "instrumenttype": "EQ", "lotsize": 1},
    {"symbol": "ZEEL", "exchange": "NSE", "instrumenttype": "EQ", "lotsize": 1},
    {"symbol": "SBIN", "exchange": "BSE", "instrumenttype": "EQ", "lotsize": 1},
]
FO = [
    {"symbol": "NIFTY29SEP26FUT", "exchange": "NFO", "instrumenttype": "FUTIDX", "lotsize": 75,
     "expiry": "2026-09-29"},
    {"symbol": "NIFTY29SEP2625000CE", "exchange": "NFO", "instrumenttype": "OPTIDX", "lotsize": 75,
     "expiry": "2026-09-29", "strike": 25000},
    {"symbol": "NIFTY29SEP2625200PE", "exchange": "NFO", "instrumenttype": "OPTIDX", "lotsize": 75,
     "expiry": "2026-09-29", "strike": 25200},
    {"symbol": "NIFTY27OCT2625000CE", "exchange": "NFO", "instrumenttype": "OPTIDX", "lotsize": 75,
     "expiry": "2026-10-27", "strike": 25000},
    {"symbol": "CRUDEOIL19OCT26FUT", "exchange": "MCX", "instrumenttype": "FUTCOM", "lotsize": 100,
     "expiry": "2026-10-19"},
]


def q(ltp, bid=0, ask=0, high=0, low=0):
    # Floats, as the app's quotes are: the digits of Decimal(str(x)) show up in
    # refusal messages ("Required: ₹250000.0").
    return {"ltp": float(ltp), "bid": float(bid), "ask": float(ask), "high": float(high), "low": float(low)}


def order(symbol, exchange, action, qty, price_type="MARKET", product="MIS", price=None,
          trigger=None, strategy=""):
    o = {"symbol": symbol, "exchange": exchange, "action": action, "quantity": qty,
         "price_type": price_type, "product": product, "strategy": strategy}
    if price is not None:
        o["price"] = float(price)
    if trigger is not None:
        o["trigger_price"] = float(trigger)
    return o


def place(at, o, quote=None):
    return {"at": at, "op": "place", "order": o, "quote": quote}


def tick(at, quotes):
    return {"at": at, "op": "tick", "quotes": quotes}


def view(at, what, quotes=None):
    return {"at": at, "op": what, "quotes": quotes or {}}


MON = "2026-09-21"
TUE = "2026-09-22"
EXP = "2026-09-29"  # Tuesday, NIFTY weekly expiry

SCENARIOS = {}

SCENARIOS["market_mis_round_trip"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 100),
              q(2500, 2499.5, 2500.5, 2520, 2480)),
        view(f"{MON} 09:32:00", "funds"),
        view(f"{MON} 09:40:00", "positions", {"RELIANCE|NSE": q(2510.35)}),
        view(f"{MON} 09:40:30", "funds"),
        place(f"{MON} 09:45:00", order("RELIANCE", "NSE", "SELL", 40),
              q(2512, 2511.8, 2512.2, 2520, 2480)),
        place(f"{MON} 09:50:00", order("RELIANCE", "NSE", "SELL", 60),
              q(2490, 2489.9, 2490.1, 2520, 2480)),
        view(f"{MON} 09:51:00", "funds"),
        view(f"{MON} 09:51:30", "positions", {}),
        place(f"{MON} 09:52:00", order("ZEEL", "NSE", "SELL", 333),
              q(112.37, 0, 0, 115, 110)),
        place(f"{MON} 09:53:00", order("ZEEL", "NSE", "SELL", 1),
              q(112.4, 0, 0, 115, 110)),
        place(f"{MON} 10:10:00", order("ZEEL", "NSE", "BUY", 334),
              q(111.15, 111.1, 111.2, 115, 110)),
        place(f"{MON} 10:11:00", order("RELIANCE", "NSE", "BUY", 7),
              q(2501.35, 2501.3, 2501.4, 2520, 2480)),
        view(f"{MON} 10:12:00", "positions", {"RELIANCE|NSE": q(2507.05)}),
        view(f"{MON} 10:12:30", "funds"),
        view(f"{MON} 10:13:00", "orderbook"),
        view(f"{MON} 10:13:30", "tradebook"),
    ],
}

SCENARIOS["stale_quote_and_fallback"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        # LTP 1047.60 against a 1262-1345 day: the reported #1638 payload.
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 10), q(1047.6, 0, 0, 1345, 1262)),
        tick(f"{MON} 09:32:00", {"RELIANCE|NSE": q(1047.6, 0, 0, 1345, 1262)}),
        tick(f"{MON} 09:33:00", {"RELIANCE|NSE": q(1296.4, 1296.3, 1296.5, 1345, 1262)}),
        # Marketable LIMIT against a stale quote rests instead of filling.
        place(f"{MON} 09:34:00", order("RELIANCE", "NSE", "BUY", 5, "LIMIT", price=1300),
              q(1047.6, 0, 0, 1345, 1262)),
        tick(f"{MON} 09:35:00", {"RELIANCE|NSE": q(1299, 0, 0, 1345, 1262)}),
        # No quote at all: MARKET falls back to the position's last LTP for
        # margin, then waits for a quote to fill.
        place(f"{MON} 09:36:00", order("RELIANCE", "NSE", "BUY", 3), None),
        tick(f"{MON} 09:37:00", {"RELIANCE|NSE": q(1301)}),
        # No quote and no position: refused.
        place(f"{MON} 09:38:00", order("ZEEL", "NSE", "BUY", 3), None),
        place(f"{MON} 09:39:00", order("ZEEL", "NSE", "BUY", 3), q(0)),
        view(f"{MON} 09:40:00", "orderbook"),
        view(f"{MON} 09:40:30", "funds"),
    ],
}

SCENARIOS["limit_orders_and_reconcile"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 10, "LIMIT", price=2450),
              q(2500, 2499, 2501, 2520, 2480)),
        tick(f"{MON} 09:32:00", {"RELIANCE|NSE": q(2460)}),
        tick(f"{MON} 09:33:00", {"RELIANCE|NSE": q(2449.5)}),
        # A resting order while another fills: margin reconciliation after the
        # fill counts positions only, so the resting order's margin is freed.
        place(f"{MON} 09:34:00", order("ZEEL", "NSE", "BUY", 100, "LIMIT", price=100),
              q(112, 0, 0, 115, 110)),
        place(f"{MON} 09:35:00", order("RELIANCE", "NSE", "SELL", 4, "LIMIT", price=2400),
              q(2455.55, 2455.5, 2455.6, 2520, 2440)),
        view(f"{MON} 09:36:00", "funds"),
        {"at": f"{MON} 09:37:00", "op": "cancel", "ref": 1},
        view(f"{MON} 09:38:00", "funds"),
        # LIMIT sell resting above market, then the market lifts through it.
        place(f"{MON} 09:39:00", order("RELIANCE", "NSE", "SELL", 6, "LIMIT", price=2470.05),
              q(2455, 0, 0, 2520, 2440)),
        tick(f"{MON} 09:40:00", {"RELIANCE|NSE": q(2470)}),
        tick(f"{MON} 09:41:00", {"RELIANCE|NSE": q(2470.1)}),
        # LIMIT price with more than two decimals is stored rounded.
        place(f"{MON} 09:42:00", order("ZEEL", "NSE", "BUY", 7, "LIMIT", price=101.237),
              q(112, 0, 0, 115, 110)),
        view(f"{MON} 09:43:00", "funds"),
        view(f"{MON} 09:44:00", "orderbook"),
        view(f"{MON} 09:44:30", "tradebook"),
        view(f"{MON} 09:45:00", "positions", {"RELIANCE|NSE": q(2460), "ZEEL|NSE": q(112)}),
    ],
}

SCENARIOS["stop_orders"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 20), q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:32:00", order("RELIANCE", "NSE", "SELL", 20, "SL-M", trigger=2480),
              q(2500, 0, 0, 2520, 2480)),
        tick(f"{MON} 09:33:00", {"RELIANCE|NSE": q(2485)}),
        tick(f"{MON} 09:34:00", {"RELIANCE|NSE": q(2479.5, 0, 0, 2520, 2470)}),
        # SL BUY: trigger 2520, limit 2525. Triggered at 2530 but the limit is
        # not met - the order moves to the regular book ("open").
        place(f"{MON} 09:35:00", order("ZEEL", "NSE", "BUY", 50, "SL", price=115.5, trigger=115),
              q(112, 0, 0, 120, 110)),
        tick(f"{MON} 09:36:00", {"ZEEL|NSE": q(116)}),
        tick(f"{MON} 09:37:00", {"ZEEL|NSE": q(115.25)}),
        # Trigger already met at placement.
        place(f"{MON} 09:38:00", order("SBIN", "BSE", "BUY", 10, "SL-M", trigger=800),
              q(805, 0, 0, 810, 790)),
        place(f"{MON} 09:39:00", order("SBIN", "BSE", "SELL", 4, "SL", price=806, trigger=806),
              q(805, 0, 0, 810, 790)),
        place(f"{MON} 09:40:00", order("SBIN", "BSE", "SELL", 3, "SL", price=804, trigger=806),
              q(805, 0, 0, 810, 790)),
        # MARKET and SL-M carry no price; LIMIT carries no trigger.
        place(f"{MON} 09:41:00", order("SBIN", "BSE", "BUY", 1, "LIMIT", price=700, trigger=650),
              q(805, 0, 0, 810, 790)),
        view(f"{MON} 09:42:00", "orderbook"),
        view(f"{MON} 09:42:30", "funds"),
        view(f"{MON} 09:43:00", "positions", {}),
    ],
}

SCENARIOS["futures_reversal"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("NIFTY29SEP26FUT", "NFO", "BUY", 50, product="NRML"),
              q(25000, 24999, 25001, 25100, 24900)),
        place(f"{MON} 09:32:00", order("NIFTY29SEP26FUT", "NFO", "BUY", 75, product="NRML"),
              q(25000, 24999, 25001, 25100, 24900)),
        place(f"{MON} 09:33:00", order("NIFTY29SEP26FUT", "NFO", "BUY", 75, product="NRML"),
              q(25010, 25009.5, 25010.5, 25100, 24900)),
        place(f"{MON} 09:34:00", order("NIFTY29SEP26FUT", "NFO", "SELL", 225, product="NRML"),
              q(25100, 25099.2, 25100.4, 25150, 24900)),
        view(f"{MON} 09:35:00", "funds"),
        place(f"{MON} 09:36:00", order("NIFTY29SEP26FUT", "NFO", "BUY", 75, product="NRML"),
              q(25050, 25049.95, 25050.05, 25150, 24900)),
        view(f"{MON} 09:37:00", "funds"),
        view(f"{MON} 09:38:00", "positions", {}),
        # Option buy vs sell margin, and an MCX future.
        place(f"{MON} 09:39:00", order("NIFTY27OCT2625000CE", "NFO", "SELL", 75, product="NRML"),
              q(310.4, 310.3, 310.5, 330, 300)),
        place(f"{MON} 09:40:00", order("CRUDEOIL19OCT26FUT", "MCX", "SELL", 100, product="MIS"),
              q(6123, 6122, 6124, 6150, 6100)),
        view(f"{MON} 09:41:00", "positions", {"NIFTY27OCT2625000CE|NFO": q(300.1),
                                               "CRUDEOIL19OCT26FUT|MCX": q(6130)}),
        view(f"{MON} 09:42:00", "funds"),
    ],
}

SCENARIOS["mis_squareoff"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 10:00:00", order("RELIANCE", "NSE", "BUY", 10), q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 10:01:00", order("NIFTY27OCT2625000CE", "NFO", "SELL", 75),
              q(300, 0, 0, 320, 290)),
        place(f"{MON} 10:02:00", order("ZEEL", "NSE", "BUY", 10, "LIMIT", price=100),
              q(112, 0, 0, 115, 110)),
        place(f"{MON} 10:03:00", order("CRUDEOIL19OCT26FUT", "MCX", "BUY", 100),
              q(6100, 0, 0, 6150, 6050)),
        place(f"{MON} 10:04:00", order("RELIANCE", "NSE", "BUY", 5, product="CNC"),
              q(2500, 0, 0, 2520, 2480)),
        {"at": f"{MON} 15:14:00", "op": "squareoff",
         "quotes": {"RELIANCE|NSE": q(2510), "NIFTY27OCT2625000CE|NFO": q(280)}},
        {"at": f"{MON} 15:16:00", "op": "squareoff",
         "quotes": {"RELIANCE|NSE": q(2512, 2511.5, 2512.5), "NIFTY27OCT2625000CE|NFO": q(281, 280.5, 281.5),
                    "CRUDEOIL19OCT26FUT|MCX": q(6110)}},
        # Blocked window: new MIS exposure refused, NRML/CNC still fine, and a
        # reducing MIS order (the still-open MCX long) is allowed.
        place(f"{MON} 15:20:00", order("RELIANCE", "NSE", "BUY", 1), q(2512)),
        place(f"{MON} 15:21:00", order("CRUDEOIL19OCT26FUT", "MCX", "SELL", 100),
              q(6111, 6110, 6112, 6150, 6050)),
        place(f"{MON} 15:22:00", order("CRUDEOIL19OCT26FUT", "MCX", "BUY", 100),
              q(6111, 6110, 6112, 6150, 6050)),
        {"at": f"{MON} 23:31:00", "op": "squareoff", "quotes": {"CRUDEOIL19OCT26FUT|MCX": q(6120)}},
        place(f"{TUE} 08:59:00", order("RELIANCE", "NSE", "BUY", 1), q(2512)),
        place(f"{TUE} 09:00:00", order("RELIANCE", "NSE", "BUY", 1), q(2512, 0, 0, 2515, 2510)),
        view(f"{TUE} 09:01:00", "funds"),
        view(f"{TUE} 09:02:00", "positions", {}),
    ],
}


def expiry_steps():
    return [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 10:00:00", order("NIFTY29SEP2625000CE", "NFO", "BUY", 75, product="NRML"),
              q(120, 119.9, 120.1, 130, 100)),
        place(f"{MON} 10:01:00", order("NIFTY29SEP2625200PE", "NFO", "SELL", 150, product="NRML"),
              q(80, 79.9, 80.1, 90, 70)),
        place(f"{MON} 10:02:00", order("NIFTY29SEP26FUT", "NFO", "SELL", 75, product="NRML"),
              q(25050, 0, 0, 25100, 25000)),
        place(f"{MON} 10:03:00", order("NIFTY29SEP2625000CE", "NFO", "BUY", 75, "LIMIT", "NRML", price=50),
              q(120, 0, 0, 130, 100)),
        # Partial close of the short put before expiry.
        place(f"{EXP} 11:00:00", order("NIFTY29SEP2625200PE", "NFO", "BUY", 75, product="NRML"),
              q(60, 0, 0, 90, 50)),
        view(f"{EXP} 15:00:00", "positions",
             {"NIFTY29SEP2625000CE|NFO": q(181.35), "NIFTY29SEP2625200PE|NFO": q(4.2),
              "NIFTY29SEP26FUT|NFO": q(25180)}),
        {"at": f"{EXP} 15:39:00", "op": "squareoff", "quotes": {}},
        {"at": f"{EXP} 15:41:00", "op": "squareoff", "quotes": {}},
        view(f"{EXP} 15:42:00", "positions", {}),
        view(f"{EXP} 15:43:00", "funds"),
        view(f"{EXP} 15:44:00", "orderbook"),
        {"at": "2026-09-30 09:00:00", "op": "settle_expiries"},
        view("2026-09-30 09:01:00", "positions", {}),
        view("2026-09-30 09:02:00", "funds"),
    ]


SCENARIOS["option_expiry_ltp"] = {"config": {}, "instruments": FO, "steps": expiry_steps()}
SCENARIOS["option_expiry_zero_next_day"] = {
    "config": {"option_expiry_settlement": "zero", "expiry_settlement_timing": "next_day"},
    "instruments": FO,
    "steps": expiry_steps(),
}

SCENARIOS["insufficient_margin"] = {
    "config": {"starting_capital": "100000.00"},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 100, product="CNC"),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:32:00", order("RELIANCE", "NSE", "BUY", 100), q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:33:00", order("RELIANCE", "NSE", "SELL", 100, "LIMIT", price=2600),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:34:00", order("RELIANCE", "NSE", "SELL", 101, "LIMIT", price=2600),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:35:00", order("NIFTY27OCT2625000CE", "NFO", "SELL", 150, product="NRML"),
              q(300, 0, 0, 320, 290)),
        place(f"{MON} 09:36:00", order("NIFTY27OCT2625000CE", "NFO", "BUY", 75, product="NRML"),
              q(300, 0, 0, 320, 290)),
        place(f"{MON} 09:37:00", order("RELIANCE", "NSE", "BUY", 12, "SL-M", trigger=2600),
              q(2500, 0, 0, 2520, 2480)),
        view(f"{MON} 09:38:00", "funds"),
        view(f"{MON} 09:39:00", "orderbook"),
    ],
}

SCENARIOS["modify_cancel"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 10, "LIMIT", price=2400),
              q(2500, 0, 0, 2520, 2480)),
        {"at": f"{MON} 09:32:00", "op": "modify", "ref": 0, "data": {"price": 2410.5, "quantity": 20}},
        {"at": f"{MON} 09:33:00", "op": "modify", "ref": 0, "data": {"trigger_price": 2300}},
        {"at": f"{MON} 09:33:30", "op": "modify", "ref": 0, "data": {"price": 0}},
        tick(f"{MON} 09:34:00", {"RELIANCE|NSE": q(2410)}),
        view(f"{MON} 09:35:00", "funds"),
        {"at": f"{MON} 09:36:00", "op": "cancel", "ref": 0},
        {"at": f"{MON} 09:36:30", "op": "modify", "ref": 0, "data": {"price": 2000}},
        place(f"{MON} 09:37:00", order("ZEEL", "NSE", "SELL", 30, "SL-M", trigger=105),
              q(112, 0, 0, 115, 110)),
        {"at": f"{MON} 09:38:00", "op": "modify", "ref": 1, "data": {"trigger_price": 106.5}},
        {"at": f"{MON} 09:38:30", "op": "modify", "ref": 1, "data": {"price": 106}},
        {"at": f"{MON} 09:39:00", "op": "cancel", "ref": 1},
        {"at": f"{MON} 09:39:30", "op": "cancel_id", "orderid": "260921999"},
        {"at": f"{MON} 09:39:40", "op": "modify_id", "orderid": "260921999", "data": {"price": 1}},
        place(f"{MON} 09:40:00", order("NIFTY27OCT2625000CE", "NFO", "BUY", 75, "LIMIT", "NRML", price=250),
              q(300, 0, 0, 320, 290)),
        {"at": f"{MON} 09:41:00", "op": "modify", "ref": 2, "data": {"quantity": 100}},
        {"at": f"{MON} 09:41:30", "op": "modify", "ref": 2, "data": {"quantity": 150}},
        {"at": f"{MON} 09:42:00", "op": "cancel", "ref": 2},
        place(f"{MON} 09:43:00", order("RELIANCE", "NSE", "BUY", 1), q(2500, 0, 0, 2520, 2480)),
        {"at": f"{MON} 09:44:00", "op": "cancel", "ref": 3},
        {"at": f"{MON} 09:44:30", "op": "modify", "ref": 3, "data": {"quantity": 2}},
        view(f"{MON} 09:45:00", "funds"),
        view(f"{MON} 09:46:00", "orderbook"),
    ],
}

SCENARIOS["cnc_t1_holdings"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 10:00:00", order("RELIANCE", "NSE", "SELL", 5, product="CNC"),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 10:01:00", order("RELIANCE", "NSE", "BUY", 100, product="CNC"),
              q(2500, 2499.5, 2500.5, 2520, 2480)),
        place(f"{MON} 10:02:00", order("RELIANCE", "NSE", "SELL", 150, product="CNC"),
              q(2510, 0, 0, 2520, 2480)),
        place(f"{MON} 10:03:00", order("RELIANCE", "NSE", "SELL", 20, product="CNC"),
              q(2510, 2509.5, 2510.5, 2520, 2480)),
        view(f"{MON} 10:04:00", "funds"),
        {"at": f"{MON} 23:59:00", "op": "t1"},
        {"at": f"{TUE} 00:00:00", "op": "t1"},
        view(f"{TUE} 00:01:00", "funds"),
        view(f"{TUE} 09:30:00", "holdings", {"RELIANCE|NSE": q(2530)}),
        place(f"{TUE} 10:00:00", order("RELIANCE", "NSE", "SELL", 30, product="CNC"),
              q(2540, 2539.5, 2540.5, 2550, 2520)),
        place(f"{TUE} 10:01:00", order("RELIANCE", "NSE", "SELL", 51, product="CNC"),
              q(2540, 0, 0, 2550, 2520)),
        place(f"{TUE} 10:02:00", order("RELIANCE", "NSE", "BUY", 40, product="CNC"),
              q(2450, 2449.5, 2450.5, 2550, 2440)),
        place(f"{TUE} 10:03:00", order("ZEEL", "NSE", "BUY", 100, product="CNC"),
              q(112.35, 0, 0, 115, 110)),
        view(f"{TUE} 10:04:00", "positions", {"RELIANCE|NSE": q(2460), "ZEEL|NSE": q(113)}),
        view(f"{TUE} 10:05:00", "funds"),
        {"at": "2026-09-23 00:00:30", "op": "t1"},
        view("2026-09-23 09:30:00", "holdings", {"RELIANCE|NSE": q(2470.4), "ZEEL|NSE": q(110.1)}),
        view("2026-09-23 09:31:00", "funds"),
        view("2026-09-23 09:32:00", "positions", {}),
    ],
}

SCENARIOS["validation"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", {"symbol": "RELIANCE", "exchange": "NSE", "action": "BUY",
                                  "quantity": 1, "product": "MIS"}, q(2500)),
        place(f"{MON} 09:31:01", order("RELIANCE", "NSE", "HOLD", 1), q(2500)),
        place(f"{MON} 09:31:02", order("RELIANCE", "NSE", "BUY", 1, "STOP"), q(2500)),
        place(f"{MON} 09:31:03", order("RELIANCE", "NSE", "BUY", 1, product="BO"), q(2500)),
        place(f"{MON} 09:31:04", order("RELIANCE", "NSE", "BUY", 1, product="NRML"), q(2500)),
        place(f"{MON} 09:31:05", order("NIFTY29SEP26FUT", "NFO", "BUY", 75, product="CNC"), q(25000)),
        place(f"{MON} 09:31:06", order("RELIANCE", "NSE", "BUY", -5), q(2500)),
        place(f"{MON} 09:31:07", order("RELIANCE", "NSE", "BUY", 5, "LIMIT"), q(2500)),
        place(f"{MON} 09:31:08", order("RELIANCE", "NSE", "BUY", 5, "LIMIT", price=-1), q(2500)),
        place(f"{MON} 09:31:09", order("RELIANCE", "NSE", "BUY", 5, "SL", price=2500), q(2500)),
        place(f"{MON} 09:31:10", order("RELIANCE", "NSE", "BUY", 5, "SL-M", trigger=-3), q(2500)),
        place(f"{MON} 09:31:11", order("RELIANCE", "NYSE", "BUY", 5), q(2500)),
        place(f"{MON} 09:31:12", order("TCS", "NSE", "BUY", 5), q(2500)),
        place(f"{MON} 09:31:13", order("NIFTY29SEP26FUT", "NFO", "BUY", 70, product="NRML"), q(25000)),
        place(f"{MON} 09:31:14", order("reliance", "nse", "buy", 2, "market", "mis"),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:31:15", order("RELIANCE", "NSE", "BUY", 2, "MARKET", "MIS"),
              q(2500, 0, 0, 2520, 2480)),
        view(f"{MON} 09:32:00", "orderbook"),
    ],
}

SCENARIOS["catch_up_and_daily_reset"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 10:00:00", order("RELIANCE", "NSE", "BUY", 10), q(2500, 0, 0, 2520, 2480)),
        view(f"{MON} 10:00:30", "positions", {"RELIANCE|NSE": q(2520.4)}),
        place(f"{MON} 10:01:00", order("ZEEL", "NSE", "SELL", 100), q(112, 0, 0, 115, 110)),
        place(f"{MON} 10:02:00", order("NIFTY27OCT2625000CE", "NFO", "BUY", 150, product="NRML"),
              q(300, 0, 0, 320, 290)),
        place(f"{MON} 10:03:00", order("NIFTY27OCT2625000CE", "NFO", "SELL", 75, product="NRML"),
              q(320, 0, 0, 330, 290)),
        view(f"{MON} 10:04:00", "funds"),
        # The app is down through the 15:15 square-off and the 03:00 reset.
        view(f"{TUE} 02:59:00", "positions", {}),
        {"at": f"{TUE} 09:10:00", "op": "catch_up"},
        view(f"{TUE} 09:11:00", "funds"),
        view(f"{TUE} 09:12:00", "positions", {}),
        place(f"{TUE} 09:20:00", order("NIFTY27OCT2625000CE", "NFO", "SELL", 75, product="NRML"),
              q(290, 0, 0, 330, 280)),
        view(f"{TUE} 09:21:00", "funds"),
        {"at": "2026-09-23 03:00:00", "op": "daily_pnl_reset"},
        view("2026-09-23 03:01:00", "funds"),
        view("2026-09-23 03:02:00", "positions", {}),
        view("2026-09-23 03:03:00", "orderbook"),
        view("2026-09-23 03:04:00", "tradebook"),
    ],
}

SCENARIOS["weekly_reset"] = {
    "config": {"reset_day": "Sunday", "reset_time": "00:30"},
    "instruments": EQ,
    "steps": [
        {"at": "2026-09-26 09:30:00", "op": "init"},
        place("2026-09-26 10:00:00", order("RELIANCE", "NSE", "BUY", 10, product="CNC"),
              q(2500, 0, 0, 2520, 2480)),
        view("2026-09-26 23:00:00", "funds"),
        view("2026-09-27 00:29:00", "funds"),
        view("2026-09-27 00:31:00", "funds"),
        view("2026-09-27 12:00:00", "funds"),
        view("2026-09-27 12:01:00", "positions", {}),
    ],
}

SCENARIOS["session_views"] = {
    "config": {},
    "instruments": EQ + FO,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 09:31:00", order("RELIANCE", "NSE", "BUY", 10), q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 09:32:00", order("RELIANCE", "NSE", "SELL", 10), q(2525, 0, 0, 2530, 2480)),
        place(f"{MON} 09:33:00", order("NIFTY29SEP26FUT", "NFO", "BUY", 75, product="NRML"),
              q(25000, 0, 0, 25100, 24900)),
        place(f"{MON} 09:34:00", order("ZEEL", "NSE", "BUY", 10, product="CNC"), q(112, 0, 0, 115, 110)),
        view(f"{TUE} 02:59:00", "orderbook"),
        view(f"{TUE} 02:59:10", "tradebook"),
        view(f"{TUE} 02:59:20", "positions", {}),
        view(f"{TUE} 03:00:00", "orderbook"),
        view(f"{TUE} 03:00:10", "tradebook"),
        view(f"{TUE} 03:00:20", "positions", {"NIFTY29SEP26FUT|NFO": q(25100)}),
        view(f"{TUE} 03:00:30", "funds"),
    ],
}

SCENARIOS["cancel_reducing_orders"] = {
    "config": {},
    "instruments": EQ,
    "steps": [
        {"at": f"{MON} 09:30:00", "op": "init"},
        place(f"{MON} 10:00:00", order("RELIANCE", "NSE", "BUY", 100), q(2500, 0, 0, 2520, 2480)),
        # A reducing order blocks nothing; cancelling it still releases a
        # recomputed margin (the "old orders" fallback in cancel_order).
        place(f"{MON} 10:01:00", order("RELIANCE", "NSE", "SELL", 4, "LIMIT", price=2600),
              q(2500, 0, 0, 2520, 2480)),
        {"at": f"{MON} 10:02:00", "op": "cancel", "ref": 1},
        view(f"{MON} 10:03:00", "funds"),
        # A protective SL-M has no price: the fallback prices it from a quote,
        # which the square-off run has.
        place(f"{MON} 10:04:00", order("RELIANCE", "NSE", "SELL", 20, "SL-M", trigger=2400),
              q(2500, 0, 0, 2520, 2480)),
        place(f"{MON} 10:05:00", order("ZEEL", "NSE", "BUY", 10, product="CNC"), q(112, 0, 0, 115, 110)),
        place(f"{MON} 10:06:00", order("ZEEL", "NSE", "SELL", 10, "LIMIT", "CNC", price=150),
              q(112, 0, 0, 115, 110)),
        {"at": f"{MON} 10:07:00", "op": "cancel", "ref": 4},
        {"at": f"{MON} 15:16:00", "op": "squareoff",
         "quotes": {"RELIANCE|NSE": q(2450, 2449.5, 2450.5, 2520, 2440)}},
        view(f"{MON} 15:17:00", "funds"),
        view(f"{MON} 15:18:00", "positions", {}),
        view(f"{MON} 15:19:00", "orderbook"),
    ],
}

# ---------------------------------------------------------------------------
# Runner (one subprocess per scenario).
# ---------------------------------------------------------------------------


def run_scenario(name, out_dir):
    tmp = tempfile.mkdtemp(prefix="sbxfix_")
    os.environ["API_KEY_PEPPER"] = "0" * 64
    os.environ["APP_KEY"] = "fixture-generator-app-key-0123456789"
    os.environ["DATABASE_URL"] = f"sqlite:///{tmp}/main.db"
    os.environ["SANDBOX_DATABASE_URL"] = f"sqlite:///{tmp}/sandbox.db"
    os.environ["LOGS_DATABASE_URL"] = f"sqlite:///{tmp}/logs.db"
    os.environ["LATENCY_DATABASE_URL"] = f"sqlite:///{tmp}/latency.db"
    os.environ["LOG_DIR"] = f"{tmp}/log"
    os.environ["SESSION_EXPIRY_TIME"] = "03:00"
    os.environ["TZ"] = "Asia/Kolkata"
    import dotenv

    dotenv.load_dotenv = lambda *a, **k: False
    dotenv.main.load_dotenv = dotenv.load_dotenv
    sys.path.insert(0, REPO)
    os.chdir(REPO)

    import datetime as dtmod
    import sqlite3
    import time as timemod
    from decimal import Decimal
    from types import SimpleNamespace

    import pytz
    from sqlalchemy import event
    from sqlalchemy.ext.compiler import compiles
    from sqlalchemy.sql.functions import now as sa_now

    IST = pytz.timezone("Asia/Kolkata")
    REAL = dtmod.datetime
    CLOCK = {"now": None}

    @compiles(sa_now, "sqlite")
    def _fake_now(element, compiler, **kw):
        return "fake_now()"

    from database import sandbox_db

    @event.listens_for(sandbox_db.engine, "connect")
    def _register(dbapi_conn, rec):
        dbapi_conn.create_function(
            "fake_now", 0,
            lambda: CLOCK["now"].astimezone(pytz.utc).strftime("%Y-%m-%d %H:%M:%S"),
        )

    # Import everything before swapping datetime, then swap it (freezegun-style).
    from sandbox import (catch_up_processor, execution_engine, fund_manager, holdings_manager,
                         order_manager, position_manager, squareoff_manager)
    import sandbox.websocket_execution_engine as wse
    import database.auth_db as auth_db
    import database.token_db as token_db
    from utils.event_bus import bus

    class _Meta(type):
        def __instancecheck__(cls, obj):
            return isinstance(obj, REAL)

        def __subclasscheck__(cls, sub):
            return issubclass(sub, REAL)

    class FakeDatetime(REAL, metaclass=_Meta):
        @classmethod
        def now(cls, tz=None):
            n = CLOCK["now"]
            if tz is None:
                return n.replace(tzinfo=None)  # host runs in IST
            return n.astimezone(tz)

        @classmethod
        def utcnow(cls):
            return CLOCK["now"].astimezone(pytz.utc).replace(tzinfo=None)

        # Constructors must hand back the real type: sqlite3 adapts parameters
        # by exact type, so a FakeDatetime bound into a raw UPDATE (the expiry
        # "hide" of updated_at) would fail where production succeeds.
        @classmethod
        def combine(cls, *a, **k):
            return REAL.combine(*a, **k)

        @classmethod
        def strptime(cls, *a, **k):
            return REAL.strptime(*a, **k)

        @classmethod
        def fromisoformat(cls, *a, **k):
            return REAL.fromisoformat(*a, **k)

    dtmod.datetime = FakeDatetime
    for m in (catch_up_processor, execution_engine, fund_manager, holdings_manager, order_manager,
              position_manager, squareoff_manager):
        if getattr(m, "datetime", None) is REAL:
            m.datetime = FakeDatetime
    timemod.sleep = lambda s: None

    spec = SCENARIOS[name]
    instruments = {(i["symbol"], i["exchange"]): i for i in spec["instruments"]}

    def symbol_info(symbol, exchange):
        i = instruments.get((symbol, exchange))
        if not i:
            return None
        return SimpleNamespace(symbol=symbol, exchange=exchange, lotsize=i.get("lotsize", 1),
                               contract_value=i.get("contract_value", 1.0), tick_size=0.05,
                               instrumenttype=i.get("instrumenttype", ""), expiry=i.get("expiry"))

    for m in (fund_manager, order_manager, execution_engine, position_manager, token_db):
        m.get_symbol_info = symbol_info

    def expiry_from_db(symbol, exchange):
        i = instruments.get((symbol, exchange))
        if i and i.get("expiry"):
            return REAL.strptime(i["expiry"], "%Y-%m-%d").date()
        return None

    position_manager.get_expiry_from_database = expiry_from_db

    QUOTES = {}

    def fetch_one(self, symbol, exchange):
        return QUOTES.get((symbol, exchange))

    def fetch_batch(self, symbols):
        return {k: QUOTES[k] for k in symbols if k in QUOTES}

    execution_engine.ExecutionEngine._fetch_quote = fetch_one
    execution_engine.ExecutionEngine._fetch_quotes_batch = fetch_batch
    position_manager.PositionManager._fetch_quote = fetch_one
    position_manager.PositionManager._fetch_quotes_batch = fetch_batch
    position_manager.PositionManager._fetch_quotes_from_websocket = lambda self, s: {}

    class _Keys:
        class query:
            @staticmethod
            def first():
                return SimpleNamespace(api_key_encrypted="x")

    auth_db.ApiKeys = _Keys
    auth_db.decrypt_token = lambda x: "k"

    def multiquotes(symbols, api_key):
        return True, {"results": [
            {"symbol": s["symbol"], "exchange": s["exchange"],
             "data": QUOTES.get((s["symbol"], s["exchange"]))} for s in symbols]}, 200

    holdings_manager.get_multiquotes = multiquotes
    wse.get_websocket_execution_engine = lambda: None
    wse.is_websocket_execution_engine_running = lambda: False
    bus.publish = lambda *a, **k: None

    SEQ = {"order": 0, "trade": 0}

    def gen_order_id(self):
        SEQ["order"] += 1
        return f"{CLOCK['now']:%y%m%d}{SEQ['order']:08d}"

    def gen_trade_id(self):
        SEQ["trade"] += 1
        return f"TRADE-{CLOCK['now']:%Y%m%d-%H%M%S}-{SEQ['trade']:08x}"

    order_manager.OrderManager._generate_order_id = gen_order_id
    execution_engine.ExecutionEngine._generate_trade_id = gen_trade_id

    CLOCK["now"] = IST.localize(REAL(2026, 1, 1))
    sandbox_db.init_db()
    for k, v in spec["config"].items():
        sandbox_db.set_config(k, v)

    USER = "sandbox-user"
    db_path = f"{tmp}/sandbox.db"

    def parse_quotes(d):
        return {tuple(k.split("|")): v for k, v in (d or {}).items()}

    def utc_to_ist(s):
        if s is None:
            return None
        t = REAL.strptime(s[:19], "%Y-%m-%d %H:%M:%S")
        return (pytz.utc.localize(t).astimezone(IST)).strftime("%Y-%m-%d %H:%M:%S")

    def wall(s):
        return None if s is None else s[:19]

    def money(v, places=2):
        return None if v is None else f"%.{places}f" % v

    def snapshot():
        con = sqlite3.connect(db_path)
        con.row_factory = sqlite3.Row
        out = {}
        f = con.execute("select * from sandbox_funds").fetchone()
        out["funds"] = None if f is None else {
            "total_capital": money(f["total_capital"]),
            "available_balance": money(f["available_balance"]),
            "used_margin": money(f["used_margin"]),
            "realized_pnl": money(f["realized_pnl"]),
            "today_realized_pnl": money(f["today_realized_pnl"]),
            "unrealized_pnl": money(f["unrealized_pnl"]),
            "total_pnl": money(f["total_pnl"]),
            "last_reset_date": wall(f["last_reset_date"]),
            "reset_count": f["reset_count"],
            "updated_at": utc_to_ist(f["updated_at"]),
        }
        out["orders"] = [{
            "orderid": r["orderid"], "strategy": r["strategy"], "symbol": r["symbol"],
            "exchange": r["exchange"], "action": r["action"], "quantity": r["quantity"],
            "price": money(r["price"]), "trigger_price": money(r["trigger_price"]),
            "price_type": r["price_type"], "product": r["product"], "order_status": r["order_status"],
            "average_price": money(r["average_price"]), "filled_quantity": r["filled_quantity"],
            "pending_quantity": r["pending_quantity"], "rejection_reason": r["rejection_reason"],
            "margin_blocked": money(r["margin_blocked"]), "order_timestamp": wall(r["order_timestamp"]),
        } for r in con.execute("select * from sandbox_orders order by id")]
        out["trades"] = [{
            "tradeid": r["tradeid"], "orderid": r["orderid"], "symbol": r["symbol"],
            "exchange": r["exchange"], "action": r["action"], "quantity": r["quantity"],
            "price": money(r["price"]), "product": r["product"], "strategy": r["strategy"],
            "trade_timestamp": wall(r["trade_timestamp"]),
        } for r in con.execute("select * from sandbox_trades order by id")]
        out["positions"] = [{
            "symbol": r["symbol"], "exchange": r["exchange"], "product": r["product"],
            "quantity": r["quantity"], "average_price": money(r["average_price"]),
            "ltp": money(r["ltp"]), "pnl": money(r["pnl"]), "pnl_percent": money(r["pnl_percent"], 4),
            "accumulated_realized_pnl": money(r["accumulated_realized_pnl"]),
            "today_realized_pnl": money(r["today_realized_pnl"]),
            "margin_blocked": money(r["margin_blocked"]),
            "created_at": wall(r["created_at"]), "updated_at": utc_to_ist(r["updated_at"]),
        } for r in con.execute("select * from sandbox_positions order by id")]
        out["holdings"] = [{
            "symbol": r["symbol"], "exchange": r["exchange"], "quantity": r["quantity"],
            "average_price": money(r["average_price"]), "ltp": money(r["ltp"]), "pnl": money(r["pnl"]),
            "pnl_percent": money(r["pnl_percent"], 4), "settlement_date": r["settlement_date"],
        } for r in con.execute("select * from sandbox_holdings order by id")]
        con.close()
        return out

    order_ids = []
    steps_out = []
    for step in spec["steps"]:
        CLOCK["now"] = IST.localize(REAL.strptime(step["at"], "%Y-%m-%d %H:%M:%S"))
        QUOTES.clear()
        QUOTES.update(parse_quotes(step.get("quotes")))
        sandbox_db.db_session.remove()
        op = step["op"]
        result = None
        if op == "init":
            ok, msg = fund_manager.FundManager(USER).initialize_funds()
            result = {"ok": ok}
        elif op == "place":
            before = SEQ["order"]
            ok, body, code = order_manager.OrderManager(USER).place_order(
                dict(step["order"]), prefetched_quote=step.get("quote"))
            if SEQ["order"] > before:
                order_ids.append(f"{CLOCK['now']:%y%m%d}{SEQ['order']:08d}")
            result = {"ok": ok, "code": code, "body": body}
        elif op in ("modify", "modify_id"):
            oid = order_ids[step["ref"]] if op == "modify" else step["orderid"]
            ok, body, code = order_manager.OrderManager(USER).modify_order(oid, dict(step["data"]))
            result = {"ok": ok, "code": code, "body": body}
        elif op in ("cancel", "cancel_id"):
            oid = order_ids[step["ref"]] if op == "cancel" else step["orderid"]
            ok, body, code = order_manager.OrderManager(USER).cancel_order(oid)
            result = {"ok": ok, "code": code, "body": body}
        elif op == "tick":
            execution_engine.ExecutionEngine().check_and_execute_pending_orders()
        elif op == "squareoff":
            squareoff_manager.SquareOffManager().check_and_square_off()
        elif op == "settle_expiries":
            position_manager.cleanup_expired_contracts()
        elif op == "t1":
            ok, msg = holdings_manager.HoldingsManager(USER).process_t1_settlement()
            result = {"ok": ok, "message": msg}
        elif op == "catch_up":
            catch_up_processor.catch_up_mis_squareoff()
            catch_up_processor.catch_up_t1_settlement()
            catch_up_processor.catch_up_daily_pnl_reset()
        elif op == "daily_pnl_reset":
            from database.sandbox_db import SandboxFunds, SandboxPositions
            SandboxFunds.query.update({"today_realized_pnl": Decimal("0.00")})
            SandboxPositions.query.update({"today_realized_pnl": Decimal("0.00")})
            sandbox_db.db_session.commit()
        elif op == "funds":
            result = fund_manager.FundManager(USER).get_funds()
        elif op == "positions":
            ok, body, code = position_manager.PositionManager(USER).get_open_positions(update_mtm=True)
            result = body
        elif op == "orderbook":
            ok, body, code = order_manager.OrderManager(USER).get_orderbook()
            result = body
        elif op == "tradebook":
            ok, body, code = position_manager.PositionManager(USER).get_tradebook()
            result = body
        elif op == "holdings":
            ok, body, code = holdings_manager.HoldingsManager(USER).get_holdings(update_mtm=True)
            result = body
        else:
            raise ValueError(op)
        sandbox_db.db_session.remove()
        out = dict(step)
        out["result"] = result
        out["state"] = snapshot()
        steps_out.append(out)

    doc = {"name": name, "config": spec["config"], "instruments": spec["instruments"],
           "steps": steps_out}
    with open(os.path.join(out_dir, f"{name}.json"), "w") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False, default=str)
        fh.write("\n")


if __name__ == "__main__":
    out_dir = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(__file__))
    if len(sys.argv) > 2:
        run_scenario(sys.argv[2], out_dir)
    else:
        for name in SCENARIOS:
            r = subprocess.run([sys.executable, os.path.abspath(__file__), out_dir, name],
                               capture_output=True, text=True)
            status = "ok" if r.returncode == 0 else "FAILED"
            print(f"{name}: {status}")
            if r.returncode != 0:
                print(r.stdout[-3000:], r.stderr[-3000:])
