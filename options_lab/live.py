"""Turn a live chain into an order you could place, and keep a paper record.

Everything else in this repo measures the past. Nothing turned a live chain
into a concrete instruction, and nothing accumulated a forward track record -
so the strategy's only evidence is a backtest of itself, which is the weakest
kind there is.

TWO DELIBERATE LIMITS.

A TICKET IS NOT A TRADE. This computes and records an intention. It sends
nothing, holds no credentials, and contains no code path that could place an
order. Every ledger row carries `paper: true`, so a record can never later be
mistaken for a fill that happened.

THE LEDGER IS THE SCHEMA `monitor` ALREADY READS. A separate notion of a paper
trade would be a second, unvalidated definition of P&L. Settled rows go through
`expiry_put.settle_trade` - the same function the backtest uses - so the health
checks apply to live results exactly as they apply to measured ones.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import date, time
from pathlib import Path

import pandas as pd

from options_lab.strategy import expiry_put as ep
from options_lab.strategy import sizing

LEDGER_GLOB = "*_ticket.json"


class TooEarly(Exception):
    """The chain has no bars at or before the entry time yet."""


class AlreadyRecorded(FileExistsError):
    """A ticket for this session exists; a second is not a second trade."""


class NotRecorded(FileNotFoundError):
    """No open ticket for this session to settle."""


@dataclass(frozen=True)
class Ticket:
    session: date
    underlying: str
    expiry: date
    side: str
    right: str
    strike: float
    lot_size: int
    lots: int
    qty: int
    credit: float
    forward: float
    breakeven: float
    margin: float
    max_loss: float | None
    wing_strike: float | None
    wing_debit: float | None

    def format(self) -> str:
        head = (
            f"expiry {self.expiry}  forward {self.forward:,.1f}\n"
            f"  {self.side}  {self.underlying} {self.strike:g} {self.right}"
            f"  x{self.lots} lot ({self.lot_size})\n"
            f"  credit    Rs {self.credit:.2f}/unit  =  "
            f"Rs {self.credit * self.qty:,.0f}\n"
            f"  margin    Rs {self.margin:,.0f}  (estimate, broker SPAN varies)\n"
            f"  breakeven {self.breakeven:,.1f}   "
            f"(settle above this to keep the credit)\n"
        )
        if self.wing_strike is None:
            # Naming it is the point. A number here would be false, and an
            # empty field would be read as zero.
            return head + "  max loss  UNBOUNDED  - naked short put\n"
        return (head
                + f"  BUY       {self.underlying} {self.wing_strike:g} "
                  f"{self.right}  x{self.lots} lot   "
                  f"debit Rs {self.wing_debit:.2f}/unit\n"
                + f"  max loss  Rs {self.max_loss:,.0f}  (bounded)\n")


def _snapshot(chain: pd.DataFrame, entry_time: time) -> pd.DataFrame:
    ts = pd.to_datetime(chain["ts"])

    # The session must actually have REACHED the entry time. Taking the latest
    # bar at or before 11:00 is right in a backtest, where the day is complete,
    # but live it would quietly price a 10:00 quote and label it an 11:00
    # ticket. The difference is an hour of unpriced movement.
    if ts.max().time() < entry_time:
        raise TooEarly(
            f"latest bar is {ts.max().time()}, entry is {entry_time}; the "
            f"signal does not exist yet - a ticket now would be priced off "
            f"stale quotes and dated as though it were not"
        )

    before = chain[ts.dt.time <= entry_time]
    if before.empty:
        raise TooEarly(
            f"no bars at or before {entry_time}; the signal does not exist yet"
        )
    return (before.assign(ts=pd.to_datetime(before["ts"]))
                  .sort_values("ts")
                  .groupby(["strike", "right"], as_index=False).last())


def _leg(snapshot: pd.DataFrame, strike: float) -> float:
    leg = snapshot[(snapshot["strike"] == strike) & (snapshot["right"] == "PE")]
    if leg.empty or float(leg["close"].iloc[0]) <= 0:
        raise ep.SessionSkipped(f"no PE quote at strike {strike:g}")
    return float(leg["close"].iloc[0])


def build_ticket(chain: pd.DataFrame, *, session: date, underlying: str,
                 lot_size: int, expiry: date | None = None,
                 otm_pct: float = ep.DEFAULT_OTM_PCT,
                 entry_time: time = ep.DEFAULT_ENTRY, lots: int = 1,
                 wing_pct: float | None = None) -> Ticket:
    """The order this strategy implies for `session`, priced off the live chain."""
    snapshot = _snapshot(chain, entry_time)
    forward = ep.parity_forward(snapshot)
    strike = ep.select_strike(snapshot["strike"].unique(), forward=forward,
                              otm_pct=otm_pct)
    credit = _leg(snapshot, strike)

    wing_strike = wing_debit = max_loss = None
    if wing_pct is not None:
        wing_strike = ep.select_strike(snapshot["strike"].unique(),
                                       forward=forward,
                                       otm_pct=otm_pct + wing_pct)
        if wing_strike >= strike:
            raise ep.SessionSkipped(
                f"wing at {wing_strike:g} is not below {strike:g}"
            )
        wing_debit = _leg(snapshot, wing_strike)

    qty = lot_size * lots
    net_credit = credit - (wing_debit or 0.0)
    if wing_strike is not None:
        max_loss = (strike - wing_strike - net_credit) * qty
        # A defined-risk spread blocks roughly its own maximum loss rather than
        # SPAN on a naked leg.
        margin = max_loss
    else:
        # Measured exchange margin was Rs 107,264 for one lot of 65. Scaled by
        # quantity because it tracks notional. A broker's own SPAN will differ,
        # so this is reported as an estimate and never as a guarantee.
        margin = sizing.EXCHANGE_MARGIN_RS * (qty / sizing.LOT_SIZE)

    return Ticket(
        session=session, underlying=underlying,
        expiry=expiry or session, side="SELL", right="PE",
        strike=strike, lot_size=lot_size, lots=lots, qty=qty,
        credit=net_credit, forward=forward,
        breakeven=strike - net_credit, margin=margin, max_loss=max_loss,
        wing_strike=wing_strike, wing_debit=wing_debit,
    )


def _path(ledger: Path, session: date) -> Path:
    return Path(ledger) / f"{session:%Y-%m-%d}_ticket.json"


def record_ticket(ledger: Path, ticket: Ticket) -> Path:
    """Write the ticket as an OPEN paper row. Nothing is sent anywhere."""
    ledger = Path(ledger)
    ledger.mkdir(parents=True, exist_ok=True)
    path = _path(ledger, ticket.session)
    if path.exists():
        raise AlreadyRecorded(
            f"{path.name} already exists; recording it twice would invent a "
            f"second trade that was never placed"
        )
    row = {k: (str(v) if isinstance(v, date) else v)
           for k, v in asdict(ticket).items()}
    row.update(status="open", paper=True)
    path.write_text(json.dumps(row, indent=2, default=str))
    return path


def settle_ticket(ledger: Path, session: date, *, settlement: float,
                  regime: str = "quoted") -> dict:
    """Complete an open row at cash settlement, via the backtest's own P&L."""
    path = _path(Path(ledger), session)
    if not path.exists():
        raise NotRecorded(
            f"no open ticket for {session}; there is nothing to settle"
        )
    row = json.loads(path.read_text())
    trade = ep.settle_trade(
        strike=row["strike"], credit=row["credit"], settlement=settlement,
        lot_size=row["lot_size"], lots=row["lots"], regime=regime,
        wing_strike=row.get("wing_strike"),
        wing_debit=row.get("wing_debit") or 0.0,
    )
    forward = row["forward"]
    row.update(trade)
    row.update(status="settled", paper=True, session=str(session),
               forward=forward,
               otm_realised=(forward - row["strike"]) / forward)
    path.write_text(json.dumps(row, indent=2, default=str))
    return row


def read_ledger(ledger: Path) -> pd.DataFrame:
    """Every SETTLED paper row, in the schema `monitor.run_checks` reads.

    Open tickets are excluded: an intention with no settlement has no P&L, and
    counting it as a flat trade would quietly dilute every health check.
    """
    rows = []
    for path in sorted(Path(ledger).glob(LEDGER_GLOB)):
        row = json.loads(path.read_text())
        if row.get("status") == "settled":
            row["session"] = pd.Timestamp(row["session"]).date()
            rows.append(row)
    return pd.DataFrame(rows)
