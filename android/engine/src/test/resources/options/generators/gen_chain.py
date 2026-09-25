"""Fixtures for ChainAnalytics: IraAlgo's chain services run on a synthetic chain.

Only the data fetching is replaced (get_option_chain, get_quotes, get_history,
the master-contract lookups); every computation is IraAlgo's own code.
"""
import copy
import datetime as dt
import random
import types

from opengreeks import black76 as b

from common import IST, dump, frozen_datetime, patched
import services.arbitrage_service as arb
import services.custom_straddle_service as css
import services.gamma_density_service as gds
import services.gex_service as gex
import services.iv_smile_service as ivs
import services.multi_strike_oi_service as msoi
import services.oi_profile_service as oip
import services.oi_tracker_service as oit
import services.option_greeks_service as ogs
import services.straddle_chart_service as scs
import services.synthetic_future_service as sfs
import services.vol_surface_service as vss
from services.option_chain_service import get_strikes_with_labels
from services.option_symbol_service import (
    calculate_offset_strike_from_actual,
    construct_option_symbol,
    find_atm_strike_from_actual,
)

rng = random.Random(20251118)
NOW = dt.datetime(2025, 11, 18, 10, 17, 23, 500000, tzinfo=IST)
Frozen = frozen_datetime(NOW)
EXPIRY = "25NOV25"
SPOT = 24351.4
FWD = 24392.15
LOT = 75
T = ((dt.datetime(2025, 11, 25, 15, 30, tzinfo=IST) - NOW).total_seconds() / 86400) / 365


def tick(x):
    return max(0.05, round(round(x / 0.05) * 0.05, 2))


def smile(k, f):
    m = (k - f) / f
    return 0.115 + 1.6 * m * m - 0.35 * m


def make_chain(expiry, strikes, f, t, seed):
    r = random.Random(seed)
    rows = []
    for k in strikes:
        s = smile(k, f)
        ce = {"symbol": construct_option_symbol("NIFTY", expiry, k, "CE"), "ltp": tick(b.black("c", f, k, t, 0.0, s) * r.uniform(0.97, 1.03)),
              "oi": r.randrange(0, 400) * LOT, "volume": r.randrange(0, 90000) * LOT, "lotsize": LOT, "tick_size": 0.05}
        pe = {"symbol": construct_option_symbol("NIFTY", expiry, k, "PE"), "ltp": tick(b.black("p", f, k, t, 0.0, s) * r.uniform(0.97, 1.03)),
              "oi": r.randrange(0, 400) * LOT, "volume": r.randrange(0, 90000) * LOT, "lotsize": LOT, "tick_size": 0.05}
        rows.append({"strike": float(k), "ce": ce, "pe": pe})
    return rows


STRIKES = [22000.0 + 50 * i for i in range(95)]
CHAIN = make_chain(EXPIRY, STRIKES, FWD, T, 1)
# Awkward legs: unlisted, unquoted, zero OI, and ITM legs priced at or under intrinsic vs spot.
CHAIN[3]["ce"] = None
CHAIN[90]["pe"] = None
CHAIN[40]["ce"]["ltp"] = 0
CHAIN[50]["pe"]["oi"] = 0
CHAIN[41]["ce"]["ltp"] = round(SPOT - CHAIN[41]["strike"], 2)          # no time value vs spot
CHAIN[60]["pe"]["ltp"] = round(CHAIN[60]["strike"] - SPOT + 0.004, 3)   # < 0.01 of it
CHAIN[2]["ce"]["ltp"] = 0.0
PREV_OI = {}
for row in CHAIN:
    for side in ("ce", "pe"):
        leg = row[side]
        if leg:
            PREV_OI[leg["symbol"]] = rng.choice([None, 0, rng.randrange(0, 400) * LOT])


def chain_response(strike_count, ltp=SPOT, chain=CHAIN):
    strikes = [r["strike"] for r in chain]
    atm = find_atm_strike_from_actual(ltp, strikes)
    keep = {x["strike"] for x in get_strikes_with_labels(strikes, atm, strike_count)}
    rows = [copy.deepcopy(r) for r in chain if r["strike"] in keep]
    return {"status": "success", "underlying": "NIFTY", "underlying_ltp": ltp, "expiry_date": EXPIRY, "atm_strike": atm, "chain": rows}


def fake_get_option_chain(**kw):
    return True, chain_response(kw.get("strike_count")), 200


out = {"now": NOW.isoformat(), "spot": SPOT, "expiry": EXPIRY, "strikes": STRIKES, "chain": CHAIN, "prev_oi": PREV_OI}
seen_chains = {}


def remember(name, strike_count):
    seen_chains[name] = strike_count


# ---- OI tracker + max pain --------------------------------------------------
with patched(oit, "get_option_chain", fake_get_option_chain), patched(oit, "_get_nearest_futures_price", lambda **kw: 24420.5):
    out["oi_data"] = oit.get_oi_data("NIFTY", "NFO", EXPIRY, "k")[1]
    out["max_pain"] = oit.calculate_max_pain("NIFTY", "NFO", EXPIRY, "k")[1]
    out["oi_window"] = 23

# ---- IV smile, GEX (calculate_greeks with a frozen clock) --------------------
with patched(ogs, "datetime", Frozen):
    with patched(ivs, "get_option_chain", fake_get_option_chain):
        out["iv_smile"] = ivs.get_iv_smile_data("NIFTY", "NSE_INDEX", EXPIRY, "k")[1]
        out["iv_smile_window"] = 25
    with patched(gex, "get_option_chain", fake_get_option_chain), patched(gex, "_get_nearest_futures_price", lambda **kw: 24420.5):
        out["gex"] = gex.get_gex_data("NIFTY", "NSE_INDEX", EXPIRY, "k")[1]
        out["gex_window"] = 45
    # ---- gamma density: with the synthetic forward, and with it unavailable
    with patched(gds, "get_option_chain", fake_get_option_chain):
        with patched(gds, "_resolve_forward_price", lambda *a, **k: FWD):
            out["gamma_density"] = gds.calculate_gamma_density("NIFTY", "NFO", EXPIRY, "k")[1]
        with patched(gds, "_resolve_forward_price", lambda *a, **k: None):
            out["gamma_density_spot"] = gds.calculate_gamma_density("NIFTY", "NFO", EXPIRY, "k", interest_rate=6.5)[1]
        out["gamma_density_window"] = 23
        y, d = ogs.calculate_time_to_expiry(gds._expiry_datetime(EXPIRY, "NFO"))
        out["gamma_density_t"] = {"years": y, "days": d}

# ---- OI profile ---------------------------------------------------------------
def fake_history(symbol=None, exchange=None, interval=None, start_date=None, end_date=None, api_key=None):
    prev = PREV_OI.get(symbol)
    if prev is None:
        return False, {"status": "error", "message": "no data"}, 500
    return True, {"data": [{"timestamp": 1, "oi": prev}, {"timestamp": 2, "oi": 12345}]}, 200


with patched(oip, "get_option_chain", fake_get_option_chain), patched(oip, "_find_futures_symbol", lambda *a: None), \
        patched(oip, "get_history", fake_history), patched(oip, "time", types.SimpleNamespace(sleep=lambda s: None)):
    out["oi_profile"] = oip.get_oi_profile_data("NIFTY", "NFO", EXPIRY, "5m", 3, "k")[1]
    out["oi_profile_window"] = 20

# ---- synthetic future (IraAlgo's ATM rule, fed our ladder and quotes) ------------
def fake_get_option_symbol(underlying, exchange, expiry_date, strike_int, offset, option_type, api_key, underlying_ltp=None):
    ltp = SPOT if underlying_ltp is None else underlying_ltp
    atm = find_atm_strike_from_actual(ltp, STRIKES)
    k = calculate_offset_strike_from_actual(atm, offset, option_type, STRIKES)
    return True, {"symbol": construct_option_symbol("NIFTY", expiry_date, k, option_type), "exchange": "NFO", "underlying_ltp": ltp}, 200


LTPS = {leg["symbol"]: leg["ltp"] for r in CHAIN for leg in (r["ce"], r["pe"]) if leg}


def fake_multiquotes(symbols=None, api_key=None):
    return True, {"results": [{"symbol": s["symbol"], "data": {"ltp": LTPS[s["symbol"]]}} for s in symbols if s["symbol"] in LTPS]}, 200


with patched(sfs, "get_option_symbol", fake_get_option_symbol), patched(sfs, "get_multiquotes", fake_multiquotes):
    out["synthetic_future"] = sfs.calculate_synthetic_future("NIFTY", "NFO", EXPIRY, "k")[1]

# ---- vol surface over three expiries with different ladders -------------------
EXPIRIES = {
    "25NOV25": (CHAIN, T),
    "02DEC25": (make_chain("02DEC25", [22000.0 + 50 * i for i in range(95)], FWD + 12, T + 7 / 365, 2), T + 7 / 365),
    "30DEC25": (make_chain("30DEC25", [21000.0 + 100 * i for i in range(60)], FWD + 60, T + 35 / 365, 3), T + 35 / 365),
}
for rows, _ in EXPIRIES.values():
    for r in rows:
        for side in ("ce", "pe"):
            if r[side]:
                LTPS[r[side]["symbol"]] = r[side]["ltp"]
surface_cases = []
for exps, count in ((["25NOV25", "02DEC25", "30DEC25"], 10), (["25NOV25", "02DEC25"], 6), (["30DEC25", "25NOV25"], 2)):
    with patched(ogs, "datetime", Frozen), patched(dt, "datetime", Frozen), \
            patched(vss, "get_quotes", lambda **kw: (True, {"data": {"ltp": SPOT}}, 200)), \
            patched(vss, "get_available_strikes", lambda base, exp, t, ex: [r["strike"] for r in EXPIRIES[exp][0]]), \
            patched(vss, "get_multiquotes", fake_multiquotes):
        res = vss.get_vol_surface_data("NIFTY", "NSE_INDEX", exps, count, "k")[1]
    surface_cases.append({"expiries": exps, "strike_count": count, "result": res})
out["vol_surface"] = surface_cases
out["surface_chains"] = {k: v[0] for k, v in EXPIRIES.items()}

# ---- dynamic straddle and the custom straddle simulation ------------------------
SESSIONS = [dt.date(2025, 11, 13), dt.date(2025, 11, 14), dt.date(2025, 11, 17)]
candles = []
px = 24180.0
for day in SESSIONS:
    t0 = dt.datetime(day.year, day.month, day.day, 9, 15, tzinfo=IST)
    for i in range(75):
        px += rng.gauss(0, 18) + (6 if day == SESSIONS[1] else -2)
        candles.append({"timestamp": int((t0 + dt.timedelta(minutes=5 * i)).timestamp()), "close": round(px, 2)})
opt_hist = {}
for c in candles:
    atm = find_atm_strike_from_actual(c["close"], STRIKES)
    tt = (dt.datetime(2025, 11, 25, 15, 30, tzinfo=IST).timestamp() - c["timestamp"]) / 86400 / 365
    for k in (atm - 100, atm - 50, atm, atm + 50, atm + 100):
        for flag, typ in (("c", "CE"), ("p", "PE")):
            if rng.random() < 0.03:
                continue  # a missing print
            sym = construct_option_symbol("NIFTY", EXPIRY, k, typ)
            opt_hist.setdefault(sym, {})[c["timestamp"]] = round(b.black(flag, c["close"] + 30, k, tt, 0.0, smile(k, c["close"])), 2)


def hist(symbol=None, exchange=None, interval=None, start_date=None, end_date=None, api_key=None):
    if symbol == "NIFTY":
        return True, {"data": candles}, 200
    series = opt_hist.get(symbol)
    if not series:
        return False, {"message": "none"}, 404
    return True, {"data": [{"timestamp": t, "close": v} for t, v in sorted(series.items())]}, 200


straddle_cases = []
with patched(scs, "datetime", Frozen), patched(scs, "get_history", hist), patched(scs, "get_available_strikes", lambda *a: STRIKES), \
        patched(scs, "get_quotes", lambda **kw: (True, {"data": {"ltp": SPOT}}, 200)):
    for days in (2, 5):
        straddle_cases.append({"days": days, "result": scs.get_straddle_chart_data("NIFTY", "NSE_INDEX", EXPIRY, "5m", "k", days=days)[1]})
custom_cases = []
with patched(scs, "datetime", Frozen), patched(css, "get_history", hist), patched(css, "get_available_strikes", lambda *a: STRIKES), \
        patched(css, "get_quotes", lambda **kw: (True, {"data": {"ltp": SPOT}}, 200)):
    for days, adj, lot, lots in ((1, 50, 65, 1), (2, 50, 75, 2), (3, 100, 75, 1), (5, 150, 30, 3)):
        custom_cases.append({"days": days, "adjustment_points": adj, "lot_size": lot, "lots": lots,
                             "result": css.get_custom_straddle_simulation("NIFTY", "NSE_INDEX", EXPIRY, "5m", "k", days=days,
                                                                         adjustment_points=adj, lot_size=lot, lots=lots)[1]})
out["straddle_input"] = {"underlying": candles, "options": {s: [[t, v] for t, v in sorted(h.items())] for s, h in opt_hist.items()}}
out["straddle"] = straddle_cases
out["custom_straddle"] = custom_cases

# ---- calendar arbitrage universe -----------------------------------------------
FUTS = {
    "NFO": [
        {"symbol": "NIFTY25NOV25FUT", "name": "NIFTY", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 75, "tick_size": 0.1},
        {"symbol": "NIFTY30DEC25FUT", "name": "NIFTY", "expiry": "30-DEC-25", "exchange": "NFO", "lotsize": 75, "tick_size": 0.1},
        {"symbol": "NIFTY27JAN26FUT", "name": "NIFTY", "expiry": "27-JAN-26", "exchange": "NFO", "lotsize": 75, "tick_size": 0.1},
        {"symbol": "NIFTY24FEB26FUT", "name": "NIFTY", "expiry": "24-FEB-26", "exchange": "NFO", "lotsize": 65, "tick_size": 0.1},
        {"symbol": "RELIANCE30DEC25FUT", "name": "RELIANCE", "expiry": "30-DEC-2025", "exchange": "NFO", "lotsize": 500, "tick_size": 0.1},
        {"symbol": "RELIANCE25NOV25FUT", "name": "RELIANCE", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 500, "tick_size": 0.1},
        {"symbol": "RELIANCE25NOV25FUT", "name": "RELIANCE", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 505, "tick_size": 0.1},
        {"symbol": "TCS25NOV25FUT", "name": "TCS", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 175, "tick_size": 0.1},
        {"symbol": "ODDX25NOV25FUT", "name": "ODD", "expiry": "garbage", "exchange": "NFO", "lotsize": 1, "tick_size": 0.1},
        {"symbol": "ODDX30DEC25FUT", "name": "ODD", "expiry": "30-DEC-25", "exchange": "NFO", "lotsize": 1, "tick_size": 0.1},
        {"symbol": "NONAME25NOV25FUT", "name": "", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 1, "tick_size": 0.1},
        {"symbol": "NIFTY25NOV2524000CE", "name": "NIFTY", "expiry": "25-NOV-25", "exchange": "NFO", "lotsize": 75, "tick_size": 0.05},
    ],
    "MCX": [
        {"symbol": "GOLD05DEC25FUT", "name": "GOLD", "expiry": "05-DEC-25", "exchange": "MCX", "lotsize": 1, "tick_size": 1.0},
        {"symbol": "GOLD05FEB26FUT", "name": "GOLD", "expiry": "05-FEB-26", "exchange": "MCX", "lotsize": 1, "tick_size": 1.0},
        {"symbol": "CRUDEOIL16DEC25FUT", "name": "CRUDEOIL", "expiry": "16-DEC-25", "exchange": "MCX", "lotsize": 100, "tick_size": 1.0},
        {"symbol": "CRUDEOIL19JAN26FUT", "name": "CRUDEOIL", "expiry": "19-JAN-26", "exchange": "MCX", "lotsize": 100, "tick_size": 1.0},
        {"symbol": "CRUDEOIL17FEB26FUT", "name": "CRUDEOIL", "expiry": "17-FEB-26", "exchange": "MCX", "lotsize": 100, "tick_size": 1.0},
    ],
}
arb_cases = []
with patched(arb, "fno_search_symbols", lambda exchange=None, instrumenttype=None, limit=None: FUTS.get(exchange, [])):
    for exs in (["NFO", "MCX"], ["mcx", "BSE", "NFO"], ["BSE"]):
        ok, res, code = arb.get_arbitrage_universe(exs)
        if ok:
            res["data"].pop("generated_at")
        arb_cases.append({"exchanges": exs, "ok": ok, "result": res})
out["arbitrage_contracts"] = FUTS
out["arbitrage"] = arb_cases

# ---- multi-strike OI ------------------------------------------------------------
legs = [
    {"symbol": "NIFTY25NOV2524300CE", "exchange": "NFO", "side": "SELL", "strike": 24300, "optionType": "CE", "expiry": "25NOV25"},
    {"symbol": "NIFTY25NOV2524300PE", "exchange": "nfo", "side": "sell", "strike": 24300, "optionType": "PE", "expiry": "25NOV25"},
    {"symbol": "NIFTY25NOV2525000CE", "exchange": "NFO", "side": "BUY", "strike": 25000, "optionType": "CE", "expiry": "25NOV25"},
    {"symbol": "NIFTY25NOV25FUT", "exchange": "NFO", "side": "BUY", "segment": "FUTURE"},
    {"symbol": "NIFTY25NOV2524000PE", "exchange": "NFO", "side": "BUY", "active": False},
    {"symbol": "NIFTY25NOV2524300CE", "exchange": "NFO", "side": "BUY", "strike": 24300, "optionType": "CE", "expiry": "25NOV25"},
]
oi_days = [dt.date(2025, 11, 12)] + SESSIONS
oi_hist = {}
for sym, zero in (("NIFTY25NOV2524300CE", False), ("NIFTY25NOV2524300PE", False), ("NIFTY25NOV2525000CE", True)):
    pts = []
    for day in oi_days:
        t0 = dt.datetime(day.year, day.month, day.day, 9, 15, tzinfo=IST)
        for i in range(0, 75, 5):
            pts.append({"timestamp": int((t0 + dt.timedelta(minutes=5 * i)).timestamp()), "close": 100.0, "oi": 0 if zero else rng.randrange(1, 900) * LOT})
    oi_hist[sym] = pts


def ms_hist(symbol=None, exchange=None, interval=None, start_date=None, end_date=None, api_key=None):
    if symbol == "NIFTY":
        pts = [{"timestamp": p["timestamp"], "close": 24000 + j * 1.234} for j, p in enumerate(oi_hist["NIFTY25NOV2524300CE"])]
        return True, {"data": pts}, 200
    return True, {"data": oi_hist[symbol]}, 200


with patched(msoi, "get_history", ms_hist), patched(msoi, "resolve_strategy_builder_reference", lambda *a: ("NIFTY", "NSE_INDEX")), \
        patched(msoi, "get_quotes", lambda **kw: (True, {"data": {"ltp": SPOT}}, 200)):
    out["multi_strike_oi"] = {"legs": legs, "history": oi_hist, "days": 3,
                              "result": msoi.get_multi_strike_oi_data("NIFTY", "NSE_INDEX", legs, "5m", "k", days=3)[1]}

dump("chain.json", out)
