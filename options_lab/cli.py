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

from options_lab import costs, ic
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


def cmd_expiry_put(args) -> int:
    """Run the expiry-day short put over every cached expiry session."""
    files = sorted(Path(args.cache).glob("*_chain.parquet"))
    if not files:
        print(f"no expiry-session chains in {args.cache}")
        return 1

    entry = pd.Timestamp(args.entry).time()
    trades, skipped = [], []

    for f in files:
        day = pd.Timestamp(f.name[:10]).date()
        ch = pd.read_parquet(f)
        ch["ts"] = pd.to_datetime(ch["ts"])
        try:
            at = ch[ch["ts"].dt.time <= entry]
            if at.empty:
                skipped.append((day, "no bars before entry")); continue
            snap = (at.sort_values("ts")
                      .groupby(["strike", "right"], as_index=False).last())
            fwd = ep.parity_forward(snap)
            k = ep.select_strike(snap["strike"].unique(), forward=fwd,
                                 otm_pct=args.otm_pct)
            leg = snap[(snap["strike"] == k) & (snap["right"] == "PE")]
            if leg.empty or float(leg["close"].iloc[0]) <= 0:
                skipped.append((day, "no PE quote at strike")); continue
            credit = float(leg["close"].iloc[0])

            win = ch[(ch["ts"].dt.time >= ep.SETTLE_FROM)
                     & (ch["ts"].dt.time < ep.SETTLE_TO)]
            fwds = []
            for _, g in win.groupby("ts"):
                try:
                    fwds.append(ep.parity_forward(g))
                except ep.ThinChain:
                    pass
            if not fwds:
                skipped.append((day, "settlement window too thin")); continue

            t = ep.settle_trade(strike=k, credit=credit,
                                settlement=float(np.mean(fwds)),
                                lot_size=args.lot, lots=1, regime=args.regime)
            t.update(session=day, forward=fwd, otm_realised=(fwd - k) / fwd)
            trades.append(t)
        except (ep.ThinChain, ep.NotASnapshot) as exc:
            skipped.append((day, str(exc)[:60]))

    T = pd.DataFrame(trades)
    if T.empty:
        print("no sessions produced a trade"); return 1

    print(f"expiry-day short put  |  {args.otm_pct:.2%} OTM at {args.entry}  |  "
          f"lot {args.lot}  |  {args.regime} spread")
    print(f"sessions traded : {len(T)}  (skipped {len(skipped)})")
    print(f"win rate        : {100*T.won.mean():.2f}%  ({int(T.won.sum())}/{len(T)})")
    print(f"net per trade   : mean Rs {T.net_pnl.mean():+,.1f}  "
          f"median Rs {T.net_pnl.median():+,.1f}")
    print(f"                  worst Rs {T.net_pnl.min():+,.1f}  "
          f"best Rs {T.net_pnl.max():+,.1f}")
    print(f"credit          : median Rs {(T.credit*args.lot).median():,.1f}/lot")
    print(f"cost            : median Rs {T.cost.median():,.1f} "
          f"({100*T.cost.median()/(T.credit*args.lot).median():.1f}% of credit)")
    print(f"total           : Rs {T.net_pnl.sum():+,.0f}")

    losers = T[~T.won].sort_values("net_pnl")
    if len(losers):
        print(f"\nlosing sessions ({len(losers)}):")
        for _, r in losers.iterrows():
            print(f"   {r.session}  K={r.strike:.0f}  settle={r.settlement:,.1f}  "
                  f"net Rs {r.net_pnl:+,.0f}")

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
        T.to_csv(args.out, index=False)
        print(f"\nledger -> {args.out}")
    return 0


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
    e.add_argument("--out", type=Path, default=None)
    e.set_defaults(func=cmd_expiry_put)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
