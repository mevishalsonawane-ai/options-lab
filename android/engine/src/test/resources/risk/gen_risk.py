"""Generate risk parity fixtures by running IraAlgo's services/risk.

Writes JSON files the Kotlin tests replay: every case carries its inputs and
what the Python returned. Deterministic (fixed seeds).

How to regenerate (from an IraAlgo checkout, which is only read):

    export DATABASE_URL=sqlite:////tmp/scratch/master.db \
           SANDBOX_DATABASE_URL=sqlite:////tmp/scratch/sb.db \
           LOGS_DATABASE_URL=sqlite:////tmp/scratch/logs.db \
           LATENCY_DATABASE_URL=sqlite:////tmp/scratch/lat.db \
           LOG_DIR=/tmp/scratch/log API_KEY_PEPPER=$(printf '0%.0s' {1..64}) APP_KEY=x \
           PYTHONPATH=/path/to/finalproducttradingapp
    uv run --project /path/to/finalproducttradingapp python gen_risk.py android/engine/src/test/resources/risk

Every database URL must point at throwaway files: the generator builds its own
master contract and refuses to run against anything inside the checkout.
Output is gzipped JSON; the Kotlin tests read it with java.util.zip.
"""

import gzip
import itertools
import json
import math
import random
import sys
from pathlib import Path

import dotenv

dotenv.load_dotenv = lambda *a, **k: False
dotenv.main.load_dotenv = dotenv.load_dotenv

from services.risk import (  # noqa: E402
    AggregateRisk,
    PositionRisk,
    aggregate_pnl,
    evaluate_aggregate,
    evaluate_aggregate_state,
    evaluate_position,
    evaluate_position_state,
    evaluate_trail,
    position_pnl,
    side_from_quantity,
    stop_from_points,
    target_from_points,
    trail_stops_to_entry,
    validate_position,
)
from services.risk.models import format_price, normalise_side, normalise_trail_mode  # noqa: E402

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
REPO = Path("/home/user/finalproducttradingapp")


def dump(name, obj):
    data = json.dumps(obj, allow_nan=True, separators=(",", ":")).encode()
    (OUT / (name + ".gz")).write_bytes(gzip.compress(data, mtime=0))
    print(name, len(obj["cases"]) if "cases" in obj else "")


# --------------------------------------------------------------- position
position_cases = []


def add_position(state, ltp, source):
    decision = evaluate_position_state(state, ltp)
    position_cases.append(
        {
            "source": source,
            "state": state,
            "ltp": ltp,
            "expected": decision.as_dict(),
            "trail": evaluate_trail(state, ltp),
        }
    )
    return decision


vectors = json.loads((REPO / "test/risk/vectors.json").read_text())
for case in vectors["cases"]:
    add_position(case["state"], case["ltp"], "vectors:" + case["name"])

# Grid: both sides, both modes, trail on/off, trigger/step combos, stop and
# target present/absent/zero, extremes present or seeded, ltps across levels.
entries = [100.0, 0.0, None]
for side, mode, enabled, trig, step, sl, tgt, ext in itertools.product(
    ["BUY", "SELL"],
    ["continuous", "stepped"],
    [True, False],
    [0.0, 1.0, 5.0, None],
    [0.0, 2.5],
    ["none", "zero", "set"],
    ["none", "set"],
    ["none", "ahead"],
):
    long = side == "BUY"
    for entry in entries:
        state = {"side": side, "quantity": 10, "trailing_enabled": enabled, "trail_mode": mode, "trail_step": step}
        if entry is not None:
            state["entry_price"] = entry
        if trig is not None:
            state["trail_trigger"] = trig
        if sl == "zero":
            state["current_sl"] = 0.0
        elif sl == "set":
            state["initial_sl"] = 95.0 if long else 105.0
        if tgt == "set":
            state["target"] = 112.0 if long else 88.0
        if ext == "ahead":
            if long:
                state["highest_price"] = 109.0
            else:
                state["lowest_price"] = 91.0
        for ltp in ([94.0, 95.0, 100.0, 104.0, 112.0] if entry == 100.0 else [100.0, 106.0]):
            add_position(state, ltp, "grid")

# Random loose states: aliases, strings, junk, NaN, inf, bools, ints.
rng = random.Random(20260925)


def junk_number(r, centre):
    kind = r.random()
    if kind < 0.55:
        return round(centre + r.uniform(-15, 15), r.choice([0, 1, 2, 3, 6]))
    if kind < 0.65:
        return int(round(centre + r.uniform(-15, 15)))
    if kind < 0.72:
        return str(round(centre + r.uniform(-15, 15), 2))
    return r.choice([None, 0, 0.0, -5.0, float("nan"), float("inf"), float("-inf"), True, False, "abc", "", " 101.5 ", "1_00", "1e2", "-0"])


def random_state(r):
    long = r.random() < 0.5
    state = {}
    state[r.choice(["side", "action", "position"])] = r.choice(
        ["BUY", "SELL", "B", "S", "buy", " sell ", "SHORT", "-1", -1, 1, None, "", "LONG", 0]
    ) if r.random() < 0.3 else ("BUY" if long else "SELL")
    if r.random() < 0.9:
        state[r.choice(["entry_price", "entry", "entry_avg", "average_price"])] = junk_number(r, 100) if r.random() < 0.25 else 100.0
    state[r.choice(["quantity", "qty"])] = r.choice([10, 50, -25, 0, 1.5, "20", None, 75])
    if r.random() < 0.6:
        state[r.choice(["stop_price", "current_sl", "currentSl"])] = junk_number(r, 95 if long else 105)
    if r.random() < 0.5:
        state[r.choice(["initial_stop_price", "initial_sl", "initialSl"])] = junk_number(r, 95 if long else 105)
    if r.random() < 0.5:
        state[r.choice(["target_price", "target", "targetPrice"])] = junk_number(r, 110 if long else 90)
    if r.random() < 0.25:
        state[r.choice(["sl_points", "sl_pts"])] = junk_number(r, 5)
    if r.random() < 0.25:
        state[r.choice(["target_points", "target_pts"])] = junk_number(r, 8)
    if r.random() < 0.6:
        state[r.choice(["trailing_enabled", "trailingEnabled"])] = r.choice([True, False, 1, 0, "yes", "", None])
    if r.random() < 0.7:
        state[r.choice(["trail_step", "trailing_step", "trailingStep", "trail_y"])] = junk_number(r, 3)
    if r.random() < 0.6:
        state[r.choice(["trail_trigger", "trailing_trigger", "trail_x"])] = junk_number(r, 3)
    if r.random() < 0.5:
        state[r.choice(["trail_mode", "trailMode"])] = r.choice(["stepped", "step", "staircase", "continuous", "STEPPED", None, "x"])
    if r.random() < 0.5:
        state[r.choice(["highest_price", "highestPrice"])] = junk_number(r, 104)
    if r.random() < 0.5:
        state[r.choice(["lowest_price", "lowestPrice"])] = junk_number(r, 96)
    if r.random() < 0.4:
        state[r.choice(["identifier", "id", "symbol"])] = r.choice(["leg1", 7, 3.5, "", None, "NIFTY"])
    return state


def random_ltp(r):
    if r.random() < 0.85:
        return round(r.uniform(80, 120), r.choice([0, 1, 2, 4]))
    return r.choice([None, 0, -3.0, float("nan"), float("inf"), "101.25", " 99 ", "abc", True, 100])


for _ in range(3000):
    add_position(random_state(rng), random_ltp(rng), "random")

# Ratchet sequences: write the decision back the way the scalping monitor
# does, so trails that tighten over many ticks are covered.
for seq in range(120):
    r = random.Random(1000 + seq)
    long = r.random() < 0.5
    state = {
        "side": "BUY" if long else "SELL",
        "entry_price": 100.0,
        "quantity": r.choice([1, 25, 65]),
        "initial_sl": (100.0 - r.uniform(1, 8)) if long else (100.0 + r.uniform(1, 8)),
        "trailing_enabled": True,
        "trail_step": round(r.uniform(0.5, 6), 2),
        "trail_trigger": round(r.uniform(0.0, 6), 2),
        "trail_mode": r.choice(["continuous", "stepped"]),
    }
    if r.random() < 0.5:
        state["target"] = (100.0 + r.uniform(5, 25)) if long else (100.0 - r.uniform(5, 25))
    state["current_sl"] = state["initial_sl"]
    price = 100.0
    for _ in range(60):
        price = max(1.0, price + r.gauss(0, 1.5))
        ltp = round(price, 2)
        decision = add_position(dict(state), ltp, "sequence")
        if decision.breached:
            break
        state.update(
            {k: v for k, v in decision.to_trail_state().items() if k in ("highest_price", "lowest_price", "current_sl")}
        )

dump("position.json", {"cases": position_cases})

# --------------------------------------------------------------- validate
validate_cases = []
for _ in range(1500):
    state = random_state(rng)
    ltp = random_ltp(rng) if rng.random() < 0.7 else None
    validate_cases.append(
        {"state": state, "ltp": ltp, "expected": list(validate_position(PositionRisk.from_state(state), ltp))}
    )
dump("validate.json", {"cases": validate_cases})

# --------------------------------------------------------------- helpers
helper_cases = []
values = [None, 0, 0.0, -1, 5, 5.5, "5", " 7.25 ", "x", True, False, float("nan"), float("inf"), 100.0, 3, 120.0, "1e1", "-2"]
sides = ["BUY", "SELL", "S", "B", "short", "-1", -1, None, "", "sell ", True, 0, "LONG"]
for side in sides:
    for entry in [100.0, 0.0, None, "100", 3.0, float("nan")]:
        for pts in values:
            helper_cases.append({"fn": "stop_from_points", "args": [side, entry, pts], "expected": stop_from_points(side, entry, pts)})
            helper_cases.append({"fn": "target_from_points", "args": [side, entry, pts], "expected": target_from_points(side, entry, pts)})
    helper_cases.append({"fn": "normalise_side", "args": [side], "expected": str(normalise_side(side))})
for q in values + [-3, -0.5, "-4", "abc"]:
    helper_cases.append({"fn": "side_from_quantity", "args": [q], "expected": str(side_from_quantity(q))})
for m in ["stepped", "STEP", " staircase ", "continuous", None, "", 0, "x", True]:
    helper_cases.append({"fn": "normalise_trail_mode", "args": [m], "expected": normalise_trail_mode(m).value})
fmt_values = [0.0, -0.0, 1.00005, 1.03125, 2.5, 100, 99.99995, -0.00001, -0.00005, 1e-7, 123456789.123456, -5000, 0.1 + 0.2, 1e20, -1234.56785, 7.00004999]
for _ in range(600):
    fmt_values.append(round(rng.uniform(-100000, 100000), rng.choice([2, 3, 4, 5, 6, 8])))
for v in fmt_values:
    helper_cases.append({"fn": "format_price", "args": [v], "expected": format_price(v)})
for side in ["BUY", "SELL", "S", None]:
    for entry in [100.0, 0.0, None, "100"]:
        for qty in [0, 10, -10, 2.5]:
            for ltp in [110.0, 0, None, "95", float("nan")]:
                helper_cases.append({"fn": "position_pnl", "args": [side, entry, qty, ltp], "expected": position_pnl(side, entry, qty, ltp)})
dump("helpers.json", {"cases": helper_cases})

# --------------------------------------------------------------- aggregate
agg_cases = []


def add_agg(state, realized, unrealized, source):
    decision = evaluate_aggregate_state(state, realized, unrealized)
    agg_cases.append({"source": source, "state": state, "realized": realized, "unrealized": unrealized, "expected": decision.as_dict()})
    return decision


# Grid over every rule and boundary.
for sl, tgt, lock_at, floor, step, armed, lock_floor, bypass in itertools.product(
    [None, 5000, -5000],
    [None, 3000],
    [None, 1000, 2000],
    [None, 0, 500, 2500],
    [None, 300],
    [False, True],
    [None, 900],
    [False, True],
):
    state = {
        "combined_stoploss": sl,
        "combined_target": tgt,
        "lock_profit_at": lock_at,
        "lock_profit_floor": floor,
        "lock_trail_step": step,
        "lock_armed": armed,
        "lock_floor": lock_floor,
        "peak_pnl": 1500.0,
        "trough_pnl": -200.0,
        "stop_bypassed": bypass,
    }
    for total in [-5000.0, 400.0, 1000.0, 1200.0, 2000.0, 3000.0]:
        add_agg(state, total * 0.25, total * 0.75, "grid")

# Alias and junk states.
for _ in range(1500):
    r = rng
    state = {}
    for names, centre in [
        (["combined_stoploss", "overall_sl_mtm", "max_loss"], 3000),
        (["combined_target", "overall_target_mtm", "max_profit"], 3000),
        (["lock_profit_at", "if_profit_reaches"], 1500),
        (["lock_profit_floor", "lock_profit"], 500),
        (["lock_trail_step", "trail_step"], 300),
        (["lock_floor"], 700),
        (["peak_pnl", "pnl_peak"], 1500),
        (["trough_pnl", "pnl_trough"], -800),
    ]:
        if r.random() < 0.6:
            state[r.choice(names)] = junk_number(r, centre) if r.random() < 0.3 else round(r.uniform(-centre, 2 * centre), 2)
    if r.random() < 0.5:
        state["lock_armed"] = r.choice([True, False, 1, 0, "x", None])
    if r.random() < 0.3:
        state[r.choice(["stop_bypassed", "trail_to_entry_active"])] = r.choice([True, False])
    add_agg(state, round(r.uniform(-4000, 4000), 2), round(r.uniform(-4000, 4000), 2), "random")

# Ratchet sequences: carry peak, trough, armed and floor forward.
for seq in range(60):
    r = random.Random(5000 + seq)
    config = {
        "combined_stoploss": r.choice([None, round(r.uniform(500, 5000))]),
        "combined_target": r.choice([None, round(r.uniform(1000, 8000))]),
        "lock_profit_at": r.choice([None, round(r.uniform(200, 3000))]),
        "lock_profit_floor": r.choice([None, 0, round(r.uniform(0, 1500))]),
        "lock_trail_step": r.choice([None, round(r.uniform(50, 800))]),
    }
    carry = {"lock_armed": False, "lock_floor": None, "peak_pnl": 0.0, "trough_pnl": 0.0}
    total = 0.0
    for _ in range(80):
        total += r.gauss(0, 250)
        realized = round(r.uniform(-500, 500), 2)
        state = {**config, **carry}
        decision = add_agg(state, realized, round(total - realized, 2), "sequence")
        if decision.breached:
            break
        carry = {
            "lock_armed": decision.lock_armed,
            "lock_floor": decision.lock_floor,
            "peak_pnl": decision.peak_pnl,
            "trough_pnl": decision.trough_pnl,
        }
dump("aggregate.json", {"cases": agg_cases})

# --------------------------------------------------------------- pnl sum
pnl_cases = []
for _ in range(1500):
    r = rng
    positions = []
    for i in range(r.randint(0, 6)):
        p = {}
        if r.random() < 0.8:
            p[r.choice(["identifier", "symbol"])] = r.choice([f"L{i}", i, "", None])
        if r.random() < 0.8:
            p[r.choice(["side", "action"])] = r.choice(["BUY", "SELL", "S", "", None, "short"])
        p[r.choice(["quantity", "qty"])] = r.choice([10, -10, 0, 2.5, "5", None, -65])
        p[r.choice(["entry_price", "average_price"])] = junk_number(r, 100)
        p[r.choice(["last_price", "ltp"])] = junk_number(r, 100)
        if r.random() < 0.3:
            p["closed"] = r.choice([True, False, 1, 0])
        if r.random() < 0.3:
            p["status"] = r.choice(["closed", "CLOSED", "open", "", None, "configured"])
        if r.random() < 0.5:
            p[r.choice(["realized_pnl", "realized"])] = junk_number(r, 0)
        positions.append(p)
    pnl_cases.append({"positions": positions, "expected": aggregate_pnl(positions).as_dict()})
dump("pnl.json", {"cases": pnl_cases})

# --------------------------------------------------------------- trail to entry
tte_cases = []
for _ in range(1500):
    r = rng
    positions = []
    prices = {}
    for i in range(r.randint(0, 6)):
        state = random_state(r)
        state["identifier"] = f"L{i}"
        positions.append(state)
        if r.random() < 0.6:
            prices[f"L{i}"] = random_ltp(r)
    exclude = [f"L{i}" for i in range(len(positions)) if r.random() < 0.2]
    use_prices = r.random() < 0.8
    decision = trail_stops_to_entry(positions, exclude=exclude, last_prices=prices if use_prices else None)
    tte_cases.append(
        {"positions": positions, "exclude": exclude, "last_prices": prices if use_prices else None, "expected": decision.as_dict()}
    )
dump("trail_to_entry.json", {"cases": tte_cases})
