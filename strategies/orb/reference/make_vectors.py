"""Reference decisions for the native Android app, from the Python engine itself.

    uv run python mobile/native/make_vectors.py

The native app reimplements the ORB arms in Kotlin, and the Python test suite
cannot see Kotlin. So the Python engine records what it decides -- every pure
rule at its boundaries, and whole trading days through the evening replay's
decision loop -- and the Kotlin tests must reproduce all of it exactly. The
same contract as test/risk/vectors.json for the TypeScript risk copy.

Writes mobile/android/app/src/test/resources/orb_vectors.json. Regenerate
whenever orb_arm, orb_shadow.replay, services/risk or sandbox.charges change.
"""

import json
import random
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from sandbox.charges import charge_for_fill  # noqa: E402
from services.ai_signals import orb_arm, orb_shadow  # noqa: E402
from services.ai_signals.orb_arm import ORB, ORB_FRESH, Bar  # noqa: E402

OUT = ROOT / "mobile/native/app/app/src/test/resources/orb_vectors.json"
ARMS = {"orb": ORB, "orb_fresh": ORB_FRESH}
DAYS = 300


def bar_json(b: Bar) -> list:
    return [b.start.strftime("%Y-%m-%dT%H:%M"), b.open, b.high, b.low, b.close]


def synthetic_day(rng: random.Random, day: date) -> tuple[list[Bar], dict[str, list[Bar]]]:
    """A plausible BANKNIFTY day and its ATM CE/PE: trends, chop, fakeouts, gaps."""
    start = datetime.combine(day, datetime.min.time()).replace(hour=9, minute=15)
    spot = rng.uniform(50000, 58000)
    drift = rng.choice([-1, 1]) * rng.choice([0, 0, 4, 8, 15])  # points per bar
    vol = rng.uniform(15, 60)
    ce_px, pe_px = rng.uniform(250, 600), rng.uniform(250, 600)
    index, ce, pe = [], [], []
    for i in range(75):  # 09:15 .. 15:25
        t = start + timedelta(minutes=5 * i)
        if i == 30 and rng.random() < 0.25:
            drift = -drift  # an afternoon reversal
        gap = rng.gauss(0, vol * 2) if rng.random() < 0.05 else 0.0
        o = spot + gap
        path = [o]
        for _ in range(4):
            path.append(path[-1] + drift / 4 + rng.gauss(0, vol / 2))
        c = path[-1]
        index.append(Bar(t, round(o, 2), round(max(path) + abs(rng.gauss(0, vol / 4)), 2),
                         round(min(path) - abs(rng.gauss(0, vol / 4)), 2), round(c, 2)))
        for legs, px, sign in ((ce, ce_px, 1), (pe, pe_px, -1)):
            po = max(5.0, px + sign * 0.5 * gap + rng.gauss(0, 3) * (gap != 0))
            moves = [po] + [max(5.0, po + sign * 0.5 * (p - o) + rng.gauss(0, vol / 8)) for p in path[1:]]
            legs.append(Bar(t, round(moves[0], 2), round(max(moves) + abs(rng.gauss(0, 2)), 2),
                            round(max(0.05, min(moves) - abs(rng.gauss(0, 2))), 2), round(moves[-1], 2)))
        ce_px, pe_px = ce[-1].close, pe[-1].close
        spot = c
    return index, {"CE": ce, "PE": pe}


def main() -> int:
    rng = random.Random(20260924)
    v: dict = {"generated_from": "services/ai_signals/orb_arm.py, orb_shadow.replay, services/risk, sandbox.charges"}

    v["atm_strike"] = [[s, orb_arm.atm_strike(s)] for s in
                       (54049.99, 54050.0, 54050.01, 54150.0, 54099.95, 55000.0, 53951.5, 54000.0)]
    v["option_symbol"] = [[d, k, r, orb_arm.option_symbol(date.fromisoformat(d), k, r)] for d, k, r in
                          (("2026-09-29", 55700, "PE"), ("2026-10-27", 54000, "CE"), ("2027-01-05", 60100, "CE"))]
    v["bar_of"] = [[m, orb_arm.bar_of(datetime.fromisoformat(m)).isoformat(timespec="minutes")] for m in
                   ("2026-09-24T10:04:59", "2026-09-24T10:05:00", "2026-09-24T14:29:30", "2026-09-24T09:15:01")]

    entries = []
    for p in (100.0, 452.2, 37.15, 999.95):
        for delta in (-40.05, -40.0, -39.95, 0.0, 39.95, 40.0, 40.05):
            ltp = round(p + delta, 2)
            entries.append([p, ltp, "2026-09-24T12:00", orb_arm.exit_reason(p, ltp, datetime(2026, 9, 24, 12))])
    for t in ("15:09", "15:10", "15:11"):
        now = datetime.fromisoformat(f"2026-09-24T{t}")
        entries.append([100.0, 100.0, now.isoformat(timespec="minutes"), orb_arm.exit_reason(100.0, 100.0, now)])
    for pts in (17.0,):
        for delta in (-17.0, -16.95, 17.0, 16.95):
            entries.append([200.0, 200.0 + delta, "2026-09-24T12:00", orb_arm.exit_reason(200.0, 200.0 + delta,
                            datetime(2026, 9, 24, 12), pts), pts])
    v["exit_reason"] = entries
    def trigger(e: float):
        # At or below 40 the stop would sit at or under zero: the risk core
        # returns none and the Python arm crashes after its buy has filled
        # (reported 2026-09-24). The native arm must refuse such an entry.
        try:
            return orb_arm.stop_trigger(e)
        except TypeError:
            return None

    v["stop_trigger"] = [[e, trigger(e)] for e in (452.2, 100.0, 40.02, 40.0, 39.95, 37.17, 999.99)]

    v["charges"] = [[a, px, q, float(charge_for_fill(action=a, price=px, symbol="BANKNIFTY29SEP2655700PE",
                                                     exchange="NFO", product="MIS", quantity=q))]
                    for a in ("BUY", "SELL") for px, q in ((452.2, 30), (510.15, 30), (37.05, 60), (1200.0, 30))]

    from decimal import Decimal
    from sandbox.slippage import fill_price
    v["fill_price"] = [[str(p), a, pt, q, str(fill_price(price=Decimal(str(p)), action=a, price_type=pt, used_quote_book=q))]
                       for p in (452.2, 37.05, 0.05, 1234.56, 100.125, 10.0, 50.0)  # 10.005 and 50.025 sit exactly on a half paisa
                       for a in ("BUY", "SELL") for pt in ("MARKET", "SL-M") for q in (False, True)]

    # The account guard every arm order passes (services/risk/account_guard.py).
    from decimal import Decimal as D
    from services.risk.account_guard import AccountRiskLimits, AccountState, OrderIntent, check_account_order
    grng = random.Random(7)
    ce, pe, eq = "BANKNIFTY29SEP2655700CE", "BANKNIFTY29SEP2655700PE", "SBIN"
    guard = []

    def case(sym, action, qty, price, pos, trades, pnl, cap, mins, equity, peak, lim, kill=False):
        intent = OrderIntent(sym, "NFO" if sym != eq else "NSE", action, qty, "MIS", None if price is None else D(str(price)))
        state = AccountState(open_positions={f"{k}:{'NFO' if k != eq else 'NSE'}": v for k, v in pos.items()},
                             trades_today=trades, realised_pnl_today=D(str(pnl)), starting_capital=D(str(cap)),
                             minutes_to_square_off=mins, equity=D(str(equity)), peak_equity=D(str(peak)))
        limits = AccountRiskLimits(kill_switch_engaged=kill, max_concurrent_positions=lim[0], max_trades_today=lim[1],
                                   max_daily_loss=D(str(lim[2])), max_drawdown_pct=D(str(lim[3])),
                                   max_order_value=D(str(lim[4])), max_symbol_exposure=D(str(lim[5])),
                                   entry_cutoff_minutes=lim[6])
        v = check_account_order(intent=intent, state=state, limits=limits)
        guard.append({"symbol": sym, "action": action, "qty": qty, "price": price, "positions": pos, "trades": trades,
                      "pnl": pnl, "capital": cap, "minutes": mins, "equity": equity, "peak": peak, "limits": lim,
                      "kill": kill, "allowed": v.allowed, "reason": v.reason})

    lim = (3, 60, 6000, 30, 200000, 200000, 20)
    base = dict(sym=ce, action="BUY", qty=30, price=452.2, pos={}, trades=5, pnl=0, cap=100000, mins=180,
                equity=100000, peak=100000, lim=lim)
    case(**base)
    case(**{**base, "kill": True})
    case(**{**base, "pnl": -6000}); case(**{**base, "pnl": -5999.99})                     # daily loss boundary
    case(**{**base, "lim": (3, 60, 0, 30, 200000, 200000, 20), "pnl": -30000})           # drawdown from capital, exactly 30%
    case(**{**base, "lim": (3, 60, 0, 30, 200000, 200000, 20), "pnl": -29999.99})
    case(**{**base, "equity": 70000, "peak": 100000}); case(**{**base, "equity": 70000.01, "peak": 100000})  # from peak
    case(**{**base, "pos": {pe: 30, eq: 10, "NIFTY29SEP2626000CE": 65}})                  # 3 held, new instrument
    case(**{**base, "pos": {ce: 30, pe: 30, eq: 10}})                                     # 3 held, adds to one held
    case(**{**base, "trades": 60}); case(**{**base, "trades": 59})
    case(**{**base, "price": None})
    case(**{**base, "qty": 443}); case(**{**base, "qty": 442})                            # order value 200331 / 199873
    case(**{**base, "pos": {ce: 300}, "qty": 150})                                        # symbol exposure
    case(**{**base, "mins": 20}); case(**{**base, "mins": 21}); case(**{**base, "mins": None})
    case(**{**base, "action": "SELL", "pos": {ce: 30}, "pnl": -9000, "trades": 99})       # an exit passes every limit
    case(**{**base, "action": "SELL", "pos": {ce: 30}, "qty": 60})                        # 30 exit + 30 naked short
    case(**{**base, "action": "SELL", "pos": {}})                                         # naked short
    case(**{**base, "sym": eq, "action": "SELL", "pos": {}})                              # equity short: not an option
    for _ in range(200):
        held = {s: grng.choice([-30, 30, 60]) for s in grng.sample([ce, pe, eq], grng.randint(0, 3))}
        case(sym=grng.choice([ce, pe, eq]), action=grng.choice(["BUY", "SELL"]), qty=grng.choice([30, 60, 150]),
             price=grng.choice([None, 37.05, 452.2, 1500.0]), pos=held, trades=grng.randint(0, 70),
             pnl=round(grng.uniform(-40000, 8000), 2), cap=grng.choice([0, 100000]), mins=grng.choice([None, 5, 20, 21, 200]),
             equity=grng.choice([0, 68000, 95000, 100000]), peak=grng.choice([0, 100000, 110000]),
             lim=grng.choice([lim, (1, 3, 0, 0, 0, 0, 0), (3, 60, 2000, 10, 50000, 60000, 15)]),
             kill=grng.random() < 0.05)
    v["account_guard"] = guard

    days, d = [], date(2026, 1, 5)
    while len(days) < DAYS:
        if d.weekday() < 5:
            index, legs = synthetic_day(rng, d)
            symbols = {"CE": "BNCE", "PE": "BNPE"}
            rng_or = orb_arm.opening_range(index)
            signals = {name: [list(orb_arm.entry_signal(index[:k + 1], rng_or, arm)) for k in range(len(index))]
                       for name, arm in ARMS.items()} if rng_or else {}
            trades = {name: [{k: t[k] for k in ("signal_bar", "exit_bar", "entry", "exit", "why")}
                             for t in orb_shadow.replay(arm, index, legs, symbols)]
                      for name, arm in ARMS.items()}
            days.append({"day": str(d), "index": [bar_json(b) for b in index],
                         "CE": [bar_json(b) for b in legs["CE"]], "PE": [bar_json(b) for b in legs["PE"]],
                         "range": list(rng_or) if rng_or else None, "signals": signals, "trades": trades})
        d += timedelta(days=1)
    v["days"] = days

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(v), encoding="utf-8")
    n = {name: sum(len(x["trades"][name]) for x in days) for name in ARMS}
    whys = {}
    for x in days:
        for name in ARMS:
            for t in x["trades"][name]:
                whys[t["why"]] = whys.get(t["why"], 0) + 1
    print(f"{len(days)} days; trades {n}; exits by reason {whys}; {OUT.stat().st_size / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
