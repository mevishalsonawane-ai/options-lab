"""Generate Strategy Module parity fixtures by running IraAlgo's Python.

Runs the real code wherever it is callable in isolation:

* the config validator (blueprints/strategy_module.validate_strategy_config);
* the symbol resolver against a scratch SQLite master contract, so the real
  expiry_service.get_expiry_dates, option_symbol_service strike listing and
  contract lookups and symbol_resolver.lot_size_for all run unmodified;
* scheduler._planned_jobs, session.session_day;
* signals.handle_signal up to the point it needs a database run, and the
  signal leg's carry-short and quantity rules;
* the engine's locked sections: _process_tick_for_run (with the database,
  broker and emit calls around it stubbed and captured) and _apply_fill, plus
  the state module's claim functions, over seeded random sequences.

The resolver fixture depends on today's date (expired expiries are dropped),
so it records the date it was generated on and the Kotlin test replays with it.

How to regenerate (from an IraAlgo checkout, which is only read):

    export DATABASE_URL=sqlite:////tmp/scratch/master.db \
           SANDBOX_DATABASE_URL=sqlite:////tmp/scratch/sb.db \
           LOGS_DATABASE_URL=sqlite:////tmp/scratch/logs.db \
           LATENCY_DATABASE_URL=sqlite:////tmp/scratch/lat.db \
           LOG_DIR=/tmp/scratch/log API_KEY_PEPPER=$(printf '0%.0s' {1..64}) APP_KEY=x \
           PYTHONPATH=/path/to/finalproducttradingapp
    uv run --project /path/to/finalproducttradingapp python gen_strategy.py android/engine/src/test/resources/strategy

Every database URL must point at throwaway files: the generator builds its own
master contract and refuses to run against anything inside the checkout.
Output is gzipped JSON; the Kotlin tests read it with java.util.zip.
"""

import gzip
import json
import os
import random
import sys
from datetime import date, datetime, time, timedelta
from pathlib import Path
from types import SimpleNamespace

import dotenv

dotenv.load_dotenv = lambda *a, **k: False
dotenv.main.load_dotenv = dotenv.load_dotenv

for _var in ("DATABASE_URL", "SANDBOX_DATABASE_URL", "LOGS_DATABASE_URL", "LATENCY_DATABASE_URL"):
    _url = os.environ.get(_var, "")
    assert _url.startswith("sqlite:///") and "finalproducttradingapp" not in _url, f"{_var} must be a throwaway sqlite file"

import pytz  # noqa: E402

from database.symbol import Base, SymToken, db_session, engine as sym_engine  # noqa: E402

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
IST = pytz.timezone("Asia/Kolkata")
TODAY = datetime.now().date()


def dump(name, obj):
    data = json.dumps(obj, allow_nan=True, separators=(",", ":"), default=str).encode()
    (OUT / (name + ".gz")).write_bytes(gzip.compress(data, mtime=0))
    print(name, len(obj.get("cases", [])))


def mmm(d):
    return d.strftime("%b").upper()


def dashed(d, four=False):
    return f"{d.day:02d}-{mmm(d)}-{d.year if four else d.strftime('%y')}"


def symform(d):
    return f"{d.day:02d}{mmm(d)}{d.strftime('%y')}"


def strike_text(s):
    return str(int(s)) if float(s).is_integer() else str(s)


# ------------------------------------------------------------ master contract
Base.metadata.drop_all(sym_engine)
Base.metadata.create_all(sym_engine)
rows = []


def add(symbol, exchange, name=None, expiry=None, strike=None, lotsize=None, itype=None, tick=0.05):
    rows.append(
        {
            "symbol": symbol,
            "exchange": exchange,
            "name": name,
            "expiry": expiry,
            "strike": strike,
            "lotsize": lotsize,
            "instrumenttype": itype,
            "tick_size": tick,
        }
    )


def weekdays_from(start, weekday, count, step=7):
    d = start
    while d.weekday() != weekday:
        d += timedelta(days=1)
    return [d + timedelta(days=step * i) for i in range(count)]


# NIFTY: Tuesday weeklies from two weeks ago, one holiday-shifted to Monday.
nifty_exp = weekdays_from(TODAY - timedelta(days=14), 1, 14)
nifty_exp[5] = nifty_exp[5] - timedelta(days=1)
ladder = [22600.0 + 100 * i for i in range(4)] + [23000.0 + 50 * i for i in range(21)] + [24100.0 + 100 * i for i in range(4)]
for d in nifty_exp:
    for s in ladder:
        add(f"NIFTY{symform(d)}{strike_text(s)}CE", "NFO", "NIFTY", dashed(d), s, 65, "CE")
        if s != 23750.0:  # a put the chain does not list
            add(f"NIFTY{symform(d)}{strike_text(s)}PE", "NFO", "NIFTY", dashed(d), s, 65, "PE")
# NIFTY monthly futures: the last NIFTY expiry of each month.
last_of_month = {}
for d in nifty_exp:
    last_of_month[(d.year, d.month)] = d
for d in sorted(last_of_month.values()):
    add(f"NIFTY{symform(d)}FUT", "NFO", "NIFTY", dashed(d), None, 65, "FUTIDX")

# BANKNIFTY: monthlies only, 100-point ladder.
bn_exp = sorted({d for d in last_of_month.values() if d >= TODAY})
for d in bn_exp:
    add(f"BANKNIFTY{symform(d)}FUT", "NFO", "BANKNIFTY", dashed(d), None, 30, "FUTIDX")
    for i in range(15):
        s = 51000.0 + 100 * i
        add(f"BANKNIFTY{symform(d)}{strike_text(s)}CE", "NFO", "BANKNIFTY", dashed(d), s, 30, "CE")
        add(f"BANKNIFTY{symform(d)}{strike_text(s)}PE", "NFO", "BANKNIFTY", dashed(d), s, 30, "PE")

# SENSEX on BFO, Thursday weeklies.
for d in weekdays_from(TODAY - timedelta(days=3), 3, 5):
    for i in range(12):
        s = 81000.0 + 100 * i
        add(f"SENSEX{symform(d)}{strike_text(s)}CE", "BFO", "SENSEX", dashed(d), s, 20, "CE")
        add(f"SENSEX{symform(d)}{strike_text(s)}PE", "BFO", "SENSEX", dashed(d), s, 20, "PE")

# VEDL: stock options with 2.5 strikes, cash on NSE.
add("VEDL", "NSE", "VEDL", None, None, 1, "EQ")
for d in sorted(last_of_month.values())[-3:]:
    add(f"VEDL{symform(d)}FUT", "NFO", "VEDL", dashed(d), None, 1150, "FUTSTK")
    for i in range(8):
        s = 285.0 + 2.5 * i
        add(f"VEDL{symform(d)}{strike_text(s)}CE", "NFO", "VEDL", dashed(d), s, 1150, "CE")
        add(f"VEDL{symform(d)}{strike_text(s)}PE", "NFO", "VEDL", dashed(d), s, 1150, "PE")

# RELIANCE: cash and futures.
add("RELIANCE", "NSE", "RELIANCE", None, None, 1, "EQ")
add("RELIANCE", "BSE", "RELIANCE", None, None, 1, "EQ")
for d in sorted(last_of_month.values())[-3:]:
    add(f"RELIANCE{symform(d)}FUT", "NFO", "RELIANCE", dashed(d), None, 500, "FUTSTK")

# MCX: futures on the 19th, options on the 16th; GOLD vs GOLDM lot sizes.
for k in range(4):
    base = date(TODAY.year, TODAY.month, 1) + timedelta(days=32 * k)
    fut = date(base.year, base.month, 19)
    opt = date(base.year, base.month, 16)
    add(f"CRUDEOIL{symform(fut)}FUT", "MCX", "CRUDEOIL", dashed(fut), None, 100, "FUTCOM")
    for i in range(10):
        s = 5500.0 + 50 * i
        add(f"CRUDEOIL{symform(opt)}{strike_text(s)}CE", "MCX", "CRUDEOIL", dashed(opt), s, 100, "CE")
        add(f"CRUDEOIL{symform(opt)}{strike_text(s)}PE", "MCX", "CRUDEOIL", dashed(opt), s, 100, "PE")
    add(f"GOLDM{symform(fut)}FUT", "MCX", "GOLDM", dashed(fut), None, 10, "FUTCOM")
    if k >= 1:
        add(f"GOLD{symform(fut)}FUT", "MCX", "GOLD", dashed(fut), None, 1, "FUTCOM")

# Odd rows: expired-only, broken lot sizes, a four-digit-year spelling.
old = TODAY - timedelta(days=40)
add(f"OLDX{symform(old)}FUT", "NFO", "OLDX", dashed(old), None, 100, "FUTSTK")
for i, lot in enumerate([0, None]):
    d = sorted(last_of_month.values())[-1 - i]
    add(f"BADLOT{symform(d)}FUT", "NFO", "BADLOT", dashed(d), None, lot, "FUTSTK")
dd = sorted(last_of_month.values())[-1]
add(f"ODDY{symform(dd)}FUT", "NFO", "ODDY", dashed(dd, four=True), None, 250, "FUTSTK")
# A cash name whose symbol-prefix neighbour must not lend its lot size.
add(f"TATA{symform(dd)}FUT", "NFO", None, dashed(dd), None, 700, "FUTSTK")
add(f"TATAMOTORS{symform(dd)}FUT", "NFO", "TATAMOTORS", dashed(dd), None, 800, "FUTSTK")

rows = [r for r in rows if r]
for r in rows:
    db_session.add(SymToken(brsymbol=r["symbol"], brexchange=r["exchange"], token="1", **r))
db_session.commit()

from services import option_symbol_service  # noqa: E402

option_symbol_service.clear_strikes_cache()

from services.strategy_module import symbol_resolver as sr  # noqa: E402

# ------------------------------------------------------------------ resolver
rng = random.Random(4242)
resolver_cases = []
underlyings = [
    ("NIFTY", "NSE_INDEX", [23587.5, 23575.0, 23525.0, 23600.0, 22000.0, 24900.0, 0.0, -1.0]),
    ("NIFTY", "NFO", [23587.5]),
    (f"NIFTY{symform(dd)}FUT", "NSE_INDEX", [23612.0]),
    ("nifty", "NSE_INDEX", [23440.0]),
    ("BANKNIFTY", "NSE_INDEX", [51720.0, 51650.0]),
    ("SENSEX", "BSE_INDEX", [81530.0]),
    ("VEDL", "NSE", [292.3, 291.25]),
    ("RELIANCE", "NSE", [1400.0]),
    ("RELIANCE", "BSE", [1400.0]),
    ("CRUDEOIL", "MCX", [5712.0]),
    ("GOLD", "MCX", [70000.0]),
    ("GOLDM", "MCX", [70000.0]),
    ("OLDX", "NSE", [100.0]),
    ("BADLOT", "NSE", [100.0]),
    ("ODDY", "NSE", [100.0]),
    ("NIFTY", "XYZ", [23587.5]),
    ("", "NSE_INDEX", [23587.5]),
]
ranks = ["weekly", "next_week", "monthly", "next_month", "current", "next", "Next-Week", " next week ", "fortnightly", None]
listed = nifty_exp[4]
literals = [symform(listed), dashed(listed), dashed(listed, four=True), "31-FEB-27", "01JAN99", "28-XYZ-26"]
offsets = ["ATM"] + [f"ITM{i}" for i in range(1, 6)] + [f"OTM{i}" for i in range(1, 6)] + ["otm2", "ITM6", ""]
for under, exch, ltps in underlyings:
    for _ in range(260 if under.upper().startswith("NIFTY") else 70):
        seg = rng.choice(["cash", "futures", "options", "options", "options"])
        leg = {"segment": seg, "lots": rng.choice([1, 1, 2, 3, 0, -1]), "action": rng.choice(["BUY", "SELL"])}
        if seg != "cash":
            e = rng.choice(ranks + literals) if rng.random() < 0.9 else None
            if e is not None:
                leg["expiry"] = e
        if seg == "options":
            leg["option_type"] = rng.choice(["CE", "PE"])
            if rng.random() < 0.25:
                leg["strike_mode"] = "strike"
                leg["strike"] = rng.choice([23600.0, 23625.0, 23750.0, 292.5, 51500.0, 5600.0, 0.0, -5.0])
            else:
                leg["strike_mode"] = "atm"
                leg["atm_offset"] = rng.choice(offsets)
                if rng.random() < 0.2:
                    leg["strike_int"] = rng.choice([50.0, 100.0, 25.0, 2.5, 0.0, -50.0])
        ltp = rng.choice(ltps)
        result = sr.resolve_leg({k: v for k, v in leg.items() if v is not None}, under, exch, "straddle", underlying_ltp=ltp)
        resolver_cases.append({"leg": leg, "underlying": under, "exchange": exch, "ltp": ltp, "expected": result.as_dict()})

expiry_cases = []
for under, exch in [("NIFTY", "NSE_INDEX"), ("BANKNIFTY", "NFO"), ("CRUDEOIL", "MCX"), ("GOLD", "MCX"), ("OLDX", "NSE"), ("ODDY", "NSE"), ("ZZZ", "NSE")]:
    for itype in ["options", "futures", "fut", "CE", "swaps"]:
        for rank in ["weekly", "next_week", "monthly", "next_month", "current", "next", "NEXT-MONTH", "bogus"]:
            r = sr.resolve_expiry_rank(under, exch, itype, rank)
            expiry_cases.append({"underlying": under, "exchange": exch, "instrument_type": itype, "rank": rank, "expected": r.as_dict()})

lookup_cases = []
for sym in ["NIFTY", "BANKNIFTY", "GOLD", "GOLDM", "TATA", "TATAMOTORS", "VEDL", "RELIANCE", "BADLOT", "ZZZ", "nifty", f"NIFTY{symform(dd)}FUT"]:
    for exch in ["NFO", "MCX", "NSE", "BFO", "CDS"]:
        lookup_cases.append({"fn": "lot_size_for", "args": [sym, exch], "expected": sr.lot_size_for(sym, exch)})
        lookup_cases.append({"fn": "contract_exists", "args": [sym, exch], "expected": sr.contract_exists(sym, exch)})
        for value in [1, 3, 65, 130, 0, -2, "5", "x", None, 2.0]:
            for mode in ["lots", "units"]:
                q = sr.resolve_quantity(value, mode, sym, exch)
                lookup_cases.append({"fn": "resolve_quantity", "args": [value, mode, sym, exch], "expected": list(q)})
dump("resolver.json", {"today": TODAY.isoformat(), "instruments": rows, "cases": resolver_cases, "expiry": expiry_cases, "lookups": lookup_cases})

# ----------------------------------------------------------------- validator
import blueprints.strategy_module as bp  # noqa: E402


def base_batch(r):
    legs = []
    for i in range(r.randint(1, 4)):
        seg = r.choice(["options", "options", "futures", "cash"])
        leg = {"segment": seg, "position": r.choice(["B", "S"]), "lots": r.choice([1, 2, 5])}
        if seg == "options":
            leg["option_type"] = r.choice(["CE", "PE"])
            if r.random() < 0.3:
                leg["strike_mode"] = "strike"
                leg["strike"] = r.choice([23500, 292.5, 23600.0])
            else:
                leg["atm_offset"] = r.choice(["ATM", "OTM2", "ITM1"])
        if seg != "cash":
            leg["expiry"] = r.choice(["weekly", "monthly", "next_week"])
        if r.random() < 0.5:
            leg["sl_pts"] = r.choice([20, 12.5, 0])
        if r.random() < 0.4:
            leg["target_pts"] = r.choice([40, 7.5])
        if r.random() < 0.3:
            leg["trail"] = {"x": r.choice([5, 2.5]), "y": r.choice([0, 1])}
        if r.random() < 0.2:
            leg["risk_unit"] = r.choice(["percent", "points"])
        legs.append(leg)
    cfg = {
        "name": r.choice(["Straddle", "  Iron fly  ", "x" * 10]),
        "underlying": r.choice(["NIFTY", "reliance", "CRUDEOIL"]),
        "underlying_exchange": r.choice(["NSE_INDEX", "NSE", "MCX", "nse"]),
        "strategy_type": r.choice(["intraday", "positional"]),
        "entry_time": "09:20",
        "exit_time": "15:15",
        "legs": legs,
    }
    if r.random() < 0.5:
        cfg["product"] = r.choice(["MIS", "NRML", "CNC", "mis"])
    if r.random() < 0.4:
        cfg["overall_sl_mtm"] = r.choice([5000, 2500.5])
    if r.random() < 0.3:
        cfg["overall_target_mtm"] = r.choice([8000, 0])
    if r.random() < 0.3:
        cfg["lock_profit"] = {"mode": r.choice(["lock", "lock_and_trail"]), "if_profit_reaches": 3000, "lock_profit": 1000, "trail_step": 500}
    if r.random() < 0.3:
        cfg["scheduler"] = {"enabled": r.choice([True, False]), "days": r.sample(["MON", "TUE", "WED", "THU", "FRI"], r.randint(1, 3)),
                            "start_time": "09:20", "auto_stop_time": "15:10", "default_mode": r.choice(["sandbox", "live"])}
    if r.random() < 0.3:
        cfg["daily_loss_limit_inr"] = r.choice([10000, 7500.25])
    if r.random() < 0.2:
        cfg["trail_sl_to_entry"] = r.choice([True, False])
    if r.random() < 0.2:
        cfg["universe_tab"] = r.choice(["weekly_monthly", "monthly_only", "stocks_fno", "mcx"])
    return cfg


def base_signal(r):
    legs = []
    for i in range(r.randint(1, 3)):
        exch = r.choice(["NSE", "NFO", "MCX", "BSE"])
        seg = "futures" if exch in ("NFO", "MCX") else "cash"
        leg = {"symbol": r.choice(["RELIANCE", "VEDL", f"NIFTY{symform(dd)}FUT", "GOLDM"]), "exchange": exch, "qty": r.choice([1, 2, 65, 130, 100])}
        if r.random() < 0.7:
            leg["segment"] = seg
        if r.random() < 0.5:
            leg["side"] = r.choice(["long", "short", "both"])
        if r.random() < 0.4:
            leg["qty_mode"] = r.choice(["lots", "units"])
        if seg == "futures" and r.random() < 0.5:
            leg["expiry"] = r.choice(["current", "next"])
        if r.random() < 0.3:
            leg["sl_pts"] = 5
        legs.append(leg)
    cfg = {
        "name": "Signals",
        "strategy_kind": "signal",
        "underlying": "NIFTY",
        "underlying_exchange": "NSE",
        "strategy_type": r.choice(["intraday", "positional"]),
        "entry_time": "09:15",
        "exit_time": "15:20",
        "legs": legs,
    }
    if r.random() < 0.5:
        cfg["direction"] = r.choice(["both", "long_only", "short_only"])
    if r.random() < 0.5:
        cfg["product"] = r.choice(["MIS", "NRML"])
    return cfg


JUNK = [None, True, False, 0, -1, 1.5, "", "  ", "abc", "nan", "inf", "12", [], {}, [1], {"a": 1}, 51, 1e308, "09:65", "9:05", "24:00", "0920", -5000, "-3"]
TOP_KEYS = ["name", "strategy_kind", "direction", "universe_tab", "underlying", "underlying_exchange", "strategy_type", "entry_time",
            "exit_time", "product", "pricetype", "legs", "overall_sl_mtm", "overall_target_mtm", "lock_profit", "trail_sl_to_entry",
            "scheduler", "daily_loss_limit_inr", "webhook_ip_allowlist", "bogus_field"]
LEG_KEYS = ["id", "segment", "position", "lots", "option_type", "strike_mode", "atm_offset", "strike", "expiry", "sl_pts", "target_pts",
            "trail", "risk_unit", "symbol", "exchange", "side", "qty", "qty_mode", "strike_int"]
IPS = ["10.0.0.1", "192.168.1.5/24", "10.0.0.0/255.255.255.0", "10.0.0.0/0.0.0.255", "::1", "2001:db8::/32", "fe80::1%eth0",
       "256.1.1.1", "1.2.3", "01.2.3.4", "10.0.0.0/33", "::ffff:1.2.3.4", "2001:db8:::1", "", "  ", "host.example", 5, "1.2.3.4/024"]


def mutate(r, cfg):
    kind = r.random()
    if kind < 0.35:
        cfg[r.choice(TOP_KEYS)] = r.choice(JUNK + ["NIFTY", "live", "lock", "MON"])
    elif kind < 0.7 and isinstance(cfg.get("legs"), list) and cfg["legs"]:
        leg = r.choice(cfg["legs"])
        leg[r.choice(LEG_KEYS)] = r.choice(JUNK + ["CE", "PE", "B", "S", "atm", "strike", "OTM6", "otm3", "weekly", "percent", "lots", "long", "NFO", 101, 20])
    elif kind < 0.8:
        cfg.pop(r.choice(TOP_KEYS), None)
    elif kind < 0.87:
        cfg["webhook_ip_allowlist"] = r.sample(IPS, r.randint(1, 3))
    elif kind < 0.93:
        cfg["scheduler"] = {"enabled": r.choice([True, False, None, 1]), "days": r.choice([["MON", "mon"], ["FUNDAY"], [], "MON", ["SUN", "MON"]]),
                            "start_time": r.choice(["09:30", "15:30", None, "9:5"]), "auto_stop_time": r.choice(["15:00", "09:00", None]),
                            r.choice(["default_mode", "extra"]): r.choice(["live", "paper", None])}
    else:
        cfg["lock_profit"] = {"mode": r.choice(["lock", "lock_and_trail", "trail"]), "if_profit_reaches": r.choice([3000, 0, -1, "2000"]),
                              "lock_profit": r.choice([1000, 5000, 0]), r.choice(["trail_step", "step"]): r.choice([None, 0, 250])}
    return cfg


validator_cases = []
lot_lookup = SimpleNamespace()
for i in range(6000):
    r = random.Random(900000 + i)
    cfg = base_batch(r) if r.random() < 0.65 else base_signal(r)
    for _ in range(r.choice([0, 0, 1, 1, 1, 2, 3])):
        cfg = mutate(r, cfg)
    if r.random() < 0.02:
        cfg = r.choice([None, [], "x", 5])
    snapshot = json.loads(json.dumps(cfg))
    out, err = bp.validate_strategy_config(cfg)
    if out is not None:
        for k in ("entry_time", "exit_time"):
            if out.get(k) is not None:
                out[k] = out[k].strftime("%H:%M")
    validator_cases.append({"payload": snapshot, "expected": out, "error": err})
dump("validator.json", {"cases": validator_cases})

# ----------------------------------------------------------------- scheduler
from services.strategy_module import scheduler as sch  # noqa: E402
from services.strategy_module import session  # noqa: E402

sched_cases = []
for i in range(2500):
    r = random.Random(700000 + i)
    config = None
    if r.random() < 0.85:
        config = {}
        for key, choices in [
            ("enabled", [True, True, False, None, 1, 0, "yes"]),
            ("days", [["MON", "WED"], ["mon", "fri", "MON"], ["SUN"], [], ["MON", "FUNDAY"], "MON", None, ["TUE", "THU", "SAT"], [" wed "]]),
            ("start_time", ["09:20", "9:05", "24:00", "09:60", "0915", None, 915, "  09:15 ", "23:59"]),
            ("auto_stop_time", ["15:15", "15:20", None, "3pm", "00:00", "12:5"]),
        ]:
            if r.random() < 0.85:
                config[key] = r.choice(choices)
    if r.random() < 0.05:
        config = r.choice(["junk", [], 5])
    exit_time = r.choice([None, "15:15", "15:25", "09:00"])
    row = SimpleNamespace(id=7, scheduler=config, exit_time=time.fromisoformat(exit_time) if exit_time else None)
    jobs = [
        {"job_id": j["job_id"], "kind": "start" if j["func"] is sch.run_scheduled_start else "stop", "name": j["name"],
         "day_of_week": j["day_of_week"], "hour": j["hour"], "minute": j["minute"]}
        for j in sch._planned_jobs(row)
    ]
    sched_cases.append({"scheduler": config, "exit_time": exit_time, "expected": jobs})

session_cases = []
start = IST.localize(datetime(2026, 3, 1, 0, 0))
for i in range(1500):
    r = random.Random(600000 + i)
    moment = start + timedelta(minutes=r.randint(0, 60 * 24 * 60))
    if r.random() < 0.3:
        moment = IST.localize(datetime(2026, r.randint(1, 12), r.randint(1, 28), r.choice([2, 3]), r.choice([0, 59, 1])))
    session_cases.append({"moment": moment.isoformat(), "expected": session.session_day(moment).isoformat(),
                          "started": session.session_started_at(moment).isoformat()})
dump("scheduler.json", {"cases": sched_cases, "session": session_cases})

# ------------------------------------------------------------------- signals
from services.strategy_module import signals  # noqa: E402

signal_cases = []
for i in range(4000):
    r = random.Random(500000 + i)
    legs = []
    for j in range(r.randint(1, 3)):
        leg = {"id": j + 1, "symbol": r.choice(["RELIANCE", "VEDL", "GOLDM25DEC25FUT", "INFY"]), "exchange": r.choice(["NSE", "NFO", "MCX", "BSE"]),
               "side": r.choice(["long", "short", "both"]), "qty": r.choice([1, 10, 65]), "segment": "cash"}
        legs.append(leg)
    stype = r.choice(["intraday", "positional"])
    entry = r.choice([None, "09:20"])
    exit_ = r.choice([None, "15:15"])
    strategy = SimpleNamespace(
        id=1, user_id="u", current_run_id=None, live_enabled=False, strategy_type=stype,
        entry_time=time.fromisoformat(entry) if entry else None, exit_time=time.fromisoformat(exit_) if exit_ else None,
        legs=legs, direction=r.choice(["both", "long_only", "short_only"]), product=r.choice(["MIS", "NRML", "CNC"]), name="s", pricetype="MARKET",
    )
    now = IST.localize(datetime(2026, 5, 4, r.choice([8, 9, 12, 15, 16]), r.choice([0, 14, 15, 19, 20, 21, 59])))
    signals._now_ist = lambda now=now: now
    signals._day_run = lambda s: (None, "__gate_passed__")
    action = r.choice(list(signals.SIGNAL_ACTIONS) + ["long_entry", "short_exit", "buy"])
    by = r.random()
    leg_id = r.choice([1, 2, 3, 9]) if by < 0.6 else None
    symbol = r.choice(["RELIANCE", "vedl", "INFY", "NOPE"]) if by >= 0.6 and r.random() < 0.9 else None
    exchange = r.choice([None, "NSE", "nfo", "MCX"])
    res = signals.handle_signal(strategy, action, leg_id=leg_id, symbol=symbol, exchange=exchange)
    case = {"strategy": {"strategy_type": stype, "entry_time": entry, "exit_time": exit_, "legs": legs, "direction": strategy.direction,
                         "product": strategy.product},
            "now": now.isoformat(), "action": action, "leg_id": leg_id, "symbol": symbol, "exchange": exchange,
            "expected": {"ok": res.ok, "note": res.note, "error": res.error, "leg_id": res.leg_id}}
    # The pure rules that follow the gate.
    side = r.choice(["long", "short"])
    target = r.choice(legs)
    case["short_leg"] = target
    case["short_side"] = side
    case["short_expected"] = signals._reject_uncarryable_short(strategy, target, side)
    qleg = dict(target)
    qleg["symbol"] = r.choice(["RELIANCE", "VEDL", f"NIFTY{symform(dd)}FUT", f"GOLDM{symform(date(TODAY.year, TODAY.month, 19))}FUT", "NOTREAL", "NIFTY", ""])
    qleg["exchange"] = r.choice(["NSE", "NFO", "MCX", "CDS"])
    qleg["qty_mode"] = r.choice(["lots", "units", None])
    qleg["qty"] = r.choice([1, 2, 65, 130, 0])
    spec, err = signals._resolve_signal_leg(qleg, side)
    case["resolve_leg"] = qleg
    case["resolve_expected"] = {"spec": spec, "error": err}
    signal_cases.append(case)
dump("signals.json", {"cases": signal_cases})

# -------------------------------------------------------------------- engine
from database import strategy_module_db as store  # noqa: E402
from services.strategy_module import engine, state  # noqa: E402

captured = {}
store.get_run = lambda run_id: SimpleNamespace(stopped_at=None, strategy_id=1, mode="sandbox", stop_requested_reason=None)
store.get_strategy_unscoped = lambda sid: SimpleNamespace(user_id="u", strategy_kind="batch")
store.strategy_to_dict = lambda row: captured["strategy"]
store.realized_pnl_since = lambda *a, **k: captured["banked"]
engine._push_delta = lambda *a, **k: None
engine._emit = lambda sid, uid, kind, message, **f: captured["events"].append(
    {"kind": kind, "message": message, "severity": f.get("severity", "info"), "leg_id": f.get("leg_id"), "payload": f.get("payload")})
engine.stop_run = lambda run_id, user_id, reason="manual": captured.__setitem__("stop", reason) or {"ok": True}
engine._api_key_for = lambda u: "k"
engine._exit_legs = lambda run_id, strategy, leg_ids, kind, mode, api_key, user_id: captured["exits"].extend([[i, kind] for i in leg_ids]) or []
engine._note_actionable_again = lambda *a: None

TOK = "<tok>"


def normal(run):
    """Tokens are UUIDs in Python and counters in Kotlin; compare their presence."""
    run = json.loads(json.dumps(run))
    for leg in run["legs"].values():
        for k in ("exit_claim_token", "position_ref"):
            if leg.get(k) is not None:
                leg[k] = TOK
        if leg.get("superseded"):
            for k in ("exit_claim_token", "position_ref"):
                if leg["superseded"].get(k) is not None:
                    leg["superseded"][k] = TOK
    run["signal_entry_claims"] = sorted(run.get("signal_entry_claims", {}))
    return run


def random_strategy(r):
    s = {"id": 1, "name": "s", "underlying": "NIFTY", "underlying_exchange": "NSE_INDEX", "legs": [],
         "trail_sl_to_entry": r.random() < 0.4,
         "overall_sl_mtm": r.choice([None, None, 600, 2000]),
         "overall_target_mtm": r.choice([None, None, 250, 900]),
         "daily_loss_limit_inr": r.choice([None, None, 3000]),
         "lock_profit": None}
    if r.random() < 0.4:
        reach = r.choice([800, 1500])
        s["lock_profit"] = {"mode": r.choice(["lock", "lock_and_trail"]), "if_profit_reaches": reach,
                            "lock_profit": r.choice([0, 300, reach]), "trail_step": r.choice([None, 200, 400])}
    return s


tick_cases = []
for scen in range(400):
    r = random.Random(300000 + scen)
    symbols = ["S1", "S2", "S3"][: r.randint(1, 3)]
    legs = {}
    for j in range(r.randint(1, 4)):
        lid = j + 1
        pos = r.choice(["B", "S"])
        status = r.choice(["open"] * 6 + ["closed", "configured"])
        unit = r.choice(["points", "points", "percent"])
        leg = state._new_leg_state({"leg_id": lid, "position": pos, "symbol": r.choice(symbols), "exchange": "NFO", "lots": 1,
                                    "quantity": r.choice([25, 50, 65]), "risk_unit": unit,
                                    "sl_pts": r.choice([None, 3, 8, 12.5]) if unit == "points" else r.choice([None, 2, 5]),
                                    "target_pts": r.choice([None, 6, 15]) if unit == "points" else r.choice([None, 4, 10]),
                                    "trail": r.choice([{}, {"x": 4, "y": 0}, {"x": 3, "y": 1.5}, {"x": 2.5, "y": 0}])})
        leg["status"] = status
        leg["entry_status"] = "complete" if status != "configured" else "pending"
        leg["entry_avg"] = round(r.uniform(90, 110), 2) if status != "configured" else 0.0
        if status == "closed":
            leg["realized_pnl"] = round(r.uniform(-800, 800), 2)
            leg["qty"] = 0
        elif r.random() < 0.2:
            leg["realized_pnl"] = round(r.uniform(-300, 300), 2)
        legs[str(lid)] = leg
    run = {"run_id": 1, "strategy_id": 1, "pnl_realized": 0.0, "pnl_unrealized": 0.0, "pnl_total": 0.0, "pnl_peak": 0.0,
           "pnl_trough": 0.0, "lock_armed": False, "lock_floor": None, "trail_to_entry_active": False, "tick_source_degraded": False,
           "stopping": False, "signal_entry_claims": {}, "legs": legs}
    strategy = random_strategy(r)
    prices = {s: {lg["symbol"]: lg["entry_avg"] for lg in legs.values() if lg["entry_avg"]}.get(s, 100.0) for s in symbols}
    steps = []
    state.hydrate_run_state(1, run)
    for step in range(45):
        sym = r.choice(symbols)
        prices[sym] = max(1.0, prices[sym] + r.gauss(0, 1.8))
        ltp = round(prices[sym], 2) if r.random() > 0.03 else r.choice([0.0, -1.0])
        banked = round(r.uniform(-2500, 500), 2)
        before = state.get_run_state(1)
        captured.update({"strategy": strategy, "banked": banked, "events": [], "exits": [], "stop": None})
        engine._process_tick_for_run(1, sym, "NFO", ltp)
        after = state.get_run_state(1)
        steps.append({"before": normal(before), "symbol": sym, "ltp": ltp, "banked": banked, "after": normal(after),
                      "events": captured["events"], "exits": captured["exits"], "stop": captured["stop"]})
    state.clear_run_state(1)
    tick_cases.append({"strategy": strategy, "steps": steps})
dump("ticks.json", {"cases": tick_cases})

# Fills and claims: seeded op sequences over state + engine._apply_fill.
engine.reconcile_pending_stop = lambda run_id: None
store.get_run = lambda run_id: SimpleNamespace(stopped_at=datetime.now(), strategy_id=1, mode="sandbox", stop_requested_reason=None)


def token_of(run_id, leg_id, which):
    run = state.get_run_state(run_id)
    leg = run["legs"].get(str(leg_id)) if run else None
    if leg is None:
        return None
    if which == "live":
        return leg.get("exit_claim_token")
    if which == "superseded":
        return (leg.get("superseded") or {}).get("claim_token") or (leg.get("superseded") or {}).get("exit_claim_token")
    return None


def ref_of(run_id, leg_id, which):
    run = state.get_run_state(run_id)
    leg = run["legs"].get(str(leg_id)) if run else None
    if which == "live" and leg:
        return leg.get("position_ref")
    if which == "superseded" and leg and leg.get("superseded"):
        return leg["superseded"].get("position_ref")
    if which == "wrong":
        return "not-a-ref"
    return None


fill_cases = []
for scen in range(700):
    r = random.Random(100000 + scen)
    signal_mode = r.random() < 0.35
    n = r.randint(1, 3)
    if signal_mode:
        state.init_run_state(1, 1, [])
    else:
        state.init_run_state(1, 1, [{"leg_id": i + 1, "position": r.choice(["B", "S"]), "symbol": f"S{i}", "exchange": "NFO",
                                     "quantity": r.choice([50, 65, 130]), "sl_pts": 5, "position_ref": f"ref{i + 1}"} for i in range(n)])
        for i in range(n):
            with state.run_state(1) as run:
                run["legs"][str(i + 1)]["entry_order_id"] = 100 + i + 1
                run["legs"][str(i + 1)]["entry_status"] = "open"
                run["legs"][str(i + 1)]["status"] = "open"
    initial = state.get_run_state(1)
    ops = []
    next_row = 500
    for _ in range(r.randint(4, 14)):
        leg_id = r.randint(1, n)
        run = state.get_run_state(1)
        leg = run["legs"].get(str(leg_id))
        choice = r.random()
        op = None
        if signal_mode and choice < 0.3:
            pos = r.choice(["B", "S"])
            claim = state.claim_signal_entry(1, leg_id, pos)
            op = {"op": "signal_entry", "leg_id": leg_id, "position": pos, "note": (claim or {}).get("note")}
            if claim and not claim.get("note"):
                held = claim.get("held_position")
                exit_row = None
                if held is not None:
                    snap = state.claim_leg_exit(1, leg_id, "exit_signal")
                    if snap is not None:
                        next_row += 1
                        exit_row = next_row
                        state.bind_live_exit(1, leg_id, snap["exit_claim_token"], exit_row, snap.get("position_ref"))
                next_row += 1
                entry_row = next_row
                new_leg = {"leg_id": leg_id, "position": pos, "symbol": "SIG", "exchange": "NSE", "quantity": r.choice([10, 20]),
                           "position_ref": claim["position_ref"], "sl_pts": 2}
                installed = state.add_leg(1, new_leg, claim["claim_token"], claim.get("expected_position_ref"), entry_row)
                accepted = r.random() < 0.85
                if installed is not None:
                    state.finish_signal_entry(1, leg_id, claim["position_ref"], claim["claim_token"], accepted)
                else:
                    state.release_signal_entry_claim(1, leg_id, claim["claim_token"])
                op.update({"quantity": new_leg["quantity"], "exit_row": exit_row, "entry_row": entry_row, "installed": installed is not None,
                           "accepted": accepted})
        elif choice < 0.45 and leg is not None:
            kind = r.choice(["exit_sl", "exit_close_all"])
            claimed, unfilled = state.claim_legs_for_exit(1, [leg_id], kind)
            op = {"op": "claim_exit", "leg_id": leg_id, "kind": kind, "claimed": len(claimed), "unfilled": len(unfilled)}
            if claimed and r.random() < 0.9:
                next_row += 1
                ok = state.bind_live_exit(1, leg_id, claimed[0]["exit_claim_token"], next_row, claimed[0].get("position_ref"))
                op.update({"row": next_row, "bound": ok})
        elif choice < 0.52 and leg is not None and leg.get("superseded"):
            claim = state.claim_superseded_exit(1, leg_id, leg["superseded"].get("position"))
            op = {"op": "claim_superseded", "leg_id": leg_id, "claimed": claim is not None}
            if claim:
                next_row += 1
                op["row"] = next_row
                op["bound"] = state.bind_superseded_exit(1, leg_id, claim["claim_token"], next_row)
        elif choice < 0.57 and leg is not None:
            which = r.choice(["live", "superseded"])
            row = leg.get("exit_order_id") if which == "live" else (leg.get("superseded") or {}).get("exit_order_id")
            fn = state.release_leg_exit if which == "live" else state.release_superseded_exit
            op = {"op": "release", "leg_id": leg_id, "which": which, "row": row, "result": fn(1, leg_id, row) if row else False}
        elif leg is not None:
            is_entry = r.random() < 0.45
            if is_entry:
                row = leg.get("entry_order_id")
            else:
                row = r.choice([leg.get("exit_order_id"), (leg.get("superseded") or {}).get("exit_order_id"), 999, None])
            if r.random() < 0.1:
                row = 777
            ref_kind = r.choice(["live", "live", "superseded", "wrong", None])
            ref = ref_of(1, leg_id, ref_kind)
            qty_now = leg.get("qty") or 0
            filled = r.choice([None, qty_now, max(1, qty_now // 2), qty_now + 5, 0])
            cumulative = r.choice([None, None, filled])
            terminal = r.random() < 0.75
            allow = r.random() < 0.1
            price = r.choice([None, round(r.uniform(95, 105), 2)])
            flat = engine.apply_fill(1, leg_id, price, is_entry, filled, row, ref, cumulative, terminal, allow)
            op = {"op": "fill", "leg_id": leg_id, "is_entry": is_entry, "row": row, "ref": ref_kind if ref is not None else None,
                  "price": price, "filled": filled, "cumulative": cumulative, "terminal": terminal, "allow": allow, "flat": flat}
        if op is not None:
            op["after"] = normal(state.get_run_state(1))
            ops.append(op)
    state.clear_run_state(1)
    fill_cases.append({"signal": signal_mode, "legs": n, "initial": initial, "ops": ops})
dump("fills.json", {"cases": fill_cases})
