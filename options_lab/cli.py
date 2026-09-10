"""options_lab CLI.

    PYTHONPATH=. python -m options_lab.cli ic --underlying NIFTY

`ic` is the M2 deliverable and it deliberately comes BEFORE any backtest. The
repo's own history is 56 hand-written strategies that all failed, after which
`strategy_lab/research/panel_ic.py` measured the panel directly and found the
best of 65 feature-horizon pairs worth 5.4 bp against 10.7 bp of cost - which
explained all 56 failures at once. Options friction is far higher, so the
question "is this data predictable at all, net of cost" is asked first.
"""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from options_lab import costs, ic, monitor
from options_lab.features import flow
from options_lab.harvest import manifest, store
from options_lab.strategy import expiry_put as ep
from options_lab.strategy import sizing

DEFAULT_ROOT = Path(__file__).resolve().parent / "data"
EXPIRY_CACHE = DEFAULT_ROOT / "expiry_cache"
HORIZONS = (1, 5, 15, 30)
IX = "IX"


def _session_panel(root: Path, underlying: str, day: date, horizon: int):
    """One session -> (feature, forward, controls) aligned on the index grid."""
    part = store.read_day(root, underlying, day)
    if part.empty:
        return None

    part = part.copy()
    part["ts"] = pd.to_datetime(part["ts"])

    index = part[part["right"] == IX].sort_values("ts").set_index("ts")["close"]
    chain = part[part["right"] != IX]
    if len(index) < horizon + 5 or chain.empty:
        return None

    raw = flow.signed_flow(chain).reindex(index.index).fillna(0.0)
    norm = flow.signed_flow(chain, normalise=True).reindex(index.index).fillna(0.0)

    # Forward move in INDEX POINTS, so it is directly comparable to the
    # break-even the cost model reports in index points.
    forward = index.shift(-horizon) - index
    contemporaneous = index - index.shift(horizon)   # the move already happened
    lagged = index - index.shift(1)                  # intraday mean reversion

    frame = pd.DataFrame({
        "flow": raw, "flow_norm": norm, "forward": forward,
        "index_ret": contemporaneous, "lag_ret": lagged,
    }).dropna()
    return frame if len(frame) > horizon else None


def _atm_premium(root: Path, underlying: str, day: date) -> float | None:
    """Median traded premium of the most active strikes - the cost denominator."""
    part = store.read_day(root, underlying, day)
    chain = part[(part["right"] != IX) & (part["volume"] > 0)]
    if chain.empty:
        return None
    busiest = chain.groupby("contract_id")["volume"].sum().nlargest(20).index
    return float(chain[chain["contract_id"].isin(busiest)]["close"].median())


def cmd_ic(args) -> int:
    sessions = store.harvested_days(args.root, args.underlying)
    if args.complete_chain_only:
        allowed = set(manifest.complete_chain_sessions(args.root, args.underlying))
        sessions = [d for d in sessions if d in allowed]
    if not sessions:
        print("no sessions to measure. Run options_lab.harvest.cli first.")
        return 1

    lots = {"NIFTY": 65, "BANKNIFTY": 30}[args.underlying]
    prem = [p for p in (_atm_premium(args.root, args.underlying, d) for d in sessions)
            if p]
    if not prem:
        print("no traded option premiums found; cannot express cost in index points.")
        return 1
    median_prem = float(np.median(prem))
    breakeven = costs.breakeven_index_points(
        premium=median_prem, lot_size=lots, lots=1, regime=args.regime, delta=0.5)

    print(f"{args.underlying}: {len(sessions)} sessions "
          f"({sessions[0]} .. {sessions[-1]})")
    print(f"  median active premium Rs {median_prem:.2f}, lot {lots}, "
          f"regime {args.regime!r}")
    print(f"  round-trip break-even = {breakeven:.2f} index points "
          f"(2x cost bar = {2*breakeven:.2f})\n")

    rows = []
    for horizon in HORIZONS:
        parts, labels = [], []
        for i, d in enumerate(sessions):
            f = _session_panel(args.root, args.underlying, d, horizon)
            if f is not None:
                parts.append(f)
                labels.append(pd.Series(i, index=f.index))
        if not parts:
            continue
        panel = pd.concat(parts, ignore_index=True)
        sess = pd.concat(labels, ignore_index=True)
        controls = panel[["index_ret", "lag_ret"]]

        for name in ("flow", "flow_norm"):
            rows.append(ic.panel_ic(
                feature=panel[name], forward=panel["forward"], sessions=sess,
                controls=controls, name=name, horizon=horizon,
                breakeven_pts=breakeven,
            ))

    for row in rows:
        print("  " + row.format())

    print()
    cleared = [r for r in rows if r.clears_cost and r.beats_null]
    if cleared:
        for r in cleared:
            print(f"  CLEARS 2x COST: {r.feature} h={r.horizon} "
                  f"edge_over_cost={r.edge_over_cost:.2f}x")
    else:
        print("  Nothing clears the 2x cost bar while beating its null band.")
        print("  That is a result, not a failure - it is the measurement that")
        print("  should precede writing any rule.")
    return 0


def _load_expiry_sessions(cache: Path):
    """(day, chain) for every cached expiry session, oldest first."""
    out = []
    for f in sorted(Path(cache).glob("*_chain.parquet")):
        day = pd.Timestamp(f.name[:10]).date()
        chain = pd.read_parquet(f)
        chain["ts"] = pd.to_datetime(chain["ts"])
        out.append((day, chain))
    return out


def _report(label: str, trades: pd.DataFrame, skipped: list, lot: int) -> None:
    if trades.empty:
        print(f"{label:<9}: no trades ({len(skipped)} skipped)")
        return
    credit = (trades.credit * lot).median()
    print(f"{label:<9}: n={len(trades):<4} "
          f"win {100*trades.won.mean():6.2f}%  "
          f"mean Rs {trades.net_pnl.mean():+8.1f}  "
          f"median Rs {trades.net_pnl.median():+8.1f}  "
          f"worst Rs {trades.net_pnl.min():+9.1f}  "
          f"total Rs {trades.net_pnl.sum():+10,.0f}")
    print(f"{'':<11}credit Rs {credit:,.0f}/lot  "
          f"cost Rs {trades.cost.median():,.1f} "
          f"({100*trades.cost.median()/credit:.1f}% of credit)"
          + (f"  skipped {len(skipped)}" if skipped else ""))


def cmd_expiry_put(args) -> int:
    """Expiry-day short put, reported on a sealed train/holdout split.

    The split is honest because the strategy has NO fitted parameter: entry
    time and strike distance came from a prior sweep, not from this code. So
    the holdout is genuinely untouched rather than merely unexamined.
    """
    sessions = _load_expiry_sessions(args.cache)
    if not sessions:
        print(f"no expiry-session chains in {args.cache}")
        return 1

    days = [d for d, _ in sessions]
    if args.holdout > 0:
        try:
            train_days, holdout_days = ep.split_sessions(days, holdout=args.holdout)
        except ValueError as exc:
            print(f"cannot split: {exc}")
            return 1
    else:
        train_days, holdout_days = days, []

    by_day = dict(sessions)
    kw = dict(lot_size=args.lot, otm_pct=args.otm_pct, regime=args.regime,
              entry_time=pd.Timestamp(args.entry).time())

    print(f"expiry-day short put  |  {args.otm_pct:.2%} OTM at {args.entry}  |  "
          f"lot {args.lot}  |  {args.regime} spread")
    print(f"sessions: {len(days)}  ({days[0]} .. {days[-1]})")
    if holdout_days:
        print(f"SEALED HOLDOUT: last {len(holdout_days)} sessions, "
              f"from {holdout_days[0]}  (train ends {train_days[-1]})")
    print()

    tr_trades, tr_skip = ep.run_backtest(
        [(d, by_day[d]) for d in train_days], **kw)
    _report("train", tr_trades, tr_skip, args.lot)

    ho_trades, ho_skip = (ep.run_backtest([(d, by_day[d]) for d in holdout_days], **kw)
                          if holdout_days else (pd.DataFrame(), []))
    if holdout_days:
        _report("holdout", ho_trades, ho_skip, args.lot)

    all_trades = pd.concat([t for t in (tr_trades, ho_trades) if not t.empty],
                           ignore_index=True)
    if all_trades.empty:
        print("no sessions produced a trade")
        return 1
    print()
    _report("combined", all_trades, tr_skip + ho_skip, args.lot)

    if holdout_days and not ho_trades.empty and not tr_trades.empty:
        drift = ho_trades.net_pnl.mean() - tr_trades.net_pnl.mean()
        print(f"\nholdout minus train: Rs {drift:+,.1f}/trade "
              f"({'held up' if drift > -50 else 'DEGRADED out of sample'})")

    losers = all_trades[~all_trades.won].sort_values("net_pnl")
    if len(losers):
        print(f"\nlosing sessions ({len(losers)} of {len(all_trades)}):")
        for _, r in losers.iterrows():
            side = "holdout" if r.session in set(holdout_days) else "train"
            print(f"   {r.session}  {side:<7} K={r.strike:.0f}  "
                  f"settle={r.settlement:,.1f}  net Rs {r.net_pnl:+,.0f}")

    plan = sizing.plan_position(args.capital, survive_move_pct=args.survive)
    print(f"\nsizing on Rs {args.capital:,.0f} surviving a "
          f"{100*args.survive:.0f}% day:")
    print(f"   {plan.lots} lot(s) at Rs {plan.capital_per_lot:,.0f} each")
    print(f"   expected Rs {plan.expected_annual_rs:,.0f}/yr "
          f"= {100*plan.expected_annual_pct:.2f}% on total capital")
    print(f"   worst case Rs {plan.worst_case_rs:,.0f} "
          f"({100*plan.worst_case_rs/args.capital:.1f}% of capital) "
          f"- UNBOUNDED beyond this move")

    if args.out:
        all_trades.to_csv(args.out, index=False)
        print(f"\nledger -> {args.out}")
    return 0


def cmd_regime(args) -> int:
    """Are the conditions this strategy depends on still true?

    Not a learner. It adapts nothing. It checks the documented kill conditions
    against the most recent sessions, because the question is whether the
    strategy is healthy NOW, not on average since 2023.
    """
    if args.ledger:
        trades = pd.read_csv(args.ledger, parse_dates=["session"])
        trades["session"] = trades["session"].dt.date
    else:
        sessions = _load_expiry_sessions(args.cache)
        if not sessions:
            print(f"no expiry-session chains in {args.cache}")
            return 1
        trades, _ = ep.run_backtest(
            sessions, lot_size=args.lot, otm_pct=args.otm_pct,
            regime=args.regime, entry_time=pd.Timestamp(args.entry).time())

    if trades.empty:
        print("no trades to check")
        return 1

    trades = trades.sort_values("session")
    recent = trades.tail(args.last) if args.last > 0 else trades

    print("expiry put health check")
    print(f"  window: last {len(recent)} of {len(trades)} sessions "
          f"({recent.session.iloc[0]} .. {recent.session.iloc[-1]})")
    print()

    checks = monitor.run_checks(recent, otm_pct=args.otm_pct, lot_size=args.lot)
    for c in checks:
        print("  " + c.format())

    print()
    for c in checks:
        if c.status != "pass":
            print(f"  {c.name}: {c.why}")

    overall = monitor.verdict(checks)
    print()
    print(f"  VERDICT: {overall.upper()}"
          + ("  - every precondition still holds" if overall == "pass"
             else "  - see the notes above; the verdict is the WORST check, "
                  "never an average"))
    return 0 if overall != "fail" else 2


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    q = sub.add_parser("ic", help="panel IC against the cost bar (M2)")
    q.add_argument("--underlying", default="NIFTY", choices=["NIFTY", "BANKNIFTY"])
    q.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    q.add_argument("--regime", default="quoted", choices=list(costs.REGIMES))
    q.add_argument("--complete-chain-only", action="store_true",
                   help="restrict to same-day sessions (backfilled ones hold a "
                        "partial chain, so chain aggregates are a different "
                        "quantity wearing the same name)")
    q.set_defaults(func=cmd_ic)

    e = sub.add_parser("expiry-put", help="expiry-day short put over cached sessions")
    e.add_argument("--cache", type=Path, default=EXPIRY_CACHE)
    e.add_argument("--otm-pct", type=float, default=ep.DEFAULT_OTM_PCT)
    e.add_argument("--entry", default="11:00")
    e.add_argument("--regime", default="quoted", choices=list(costs.REGIMES))
    e.add_argument("--lot", type=int, default=65)
    e.add_argument("--capital", type=float, default=400_000.0)
    e.add_argument("--survive", type=float, default=sizing.DEFAULT_SURVIVE_MOVE_PCT)
    e.add_argument("--holdout", type=int, default=ep.DEFAULT_HOLDOUT_SESSIONS,
                   help="sessions sealed from the tail; 0 disables the split")
    e.add_argument("--out", type=Path, default=None)
    e.set_defaults(func=cmd_expiry_put)

    r = sub.add_parser("regime", help="check the strategy's kill conditions")
    r.add_argument("--cache", type=Path, default=EXPIRY_CACHE)
    r.add_argument("--ledger", type=Path, default=None,
                   help="check a live trade CSV instead of the backtest")
    r.add_argument("--last", type=int, default=30,
                   help="sessions to check; 0 checks all")
    r.add_argument("--otm-pct", type=float, default=ep.DEFAULT_OTM_PCT)
    r.add_argument("--entry", default="11:00")
    r.add_argument("--regime", default="quoted", choices=list(costs.REGIMES))
    r.add_argument("--lot", type=int, default=65)
    r.set_defaults(func=cmd_regime)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
