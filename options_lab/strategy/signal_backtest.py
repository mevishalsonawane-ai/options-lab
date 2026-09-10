"""Backtest an underlying-signal strategy that trades options.

Buy signal -> buy an ATM CALL. Sell signal -> buy an ATM PUT. Exit on the
opposite signal or at the session close, whichever comes first.

Four properties are structural rather than conventional, each because the
opposite has already produced a fake result somewhere in this project:

  NEXT-BAR ENTRY. A signal computed from bar i's close cannot be filled at that
  close - you only know it once the bar has ended. Entry is bar i+1's open. A
  signal on the last bar is dropped, not filled.

  ONE CONTRACT PER TRADE. The contract chosen at entry is the one marked and
  exited. Re-picking "the ATM" at exit would silently swap instruments and
  manufacture P&L out of the strike ladder.

  COSTS ALWAYS. net_pnl is computed from gross minus charges; there is no
  cost-free path. A flat option price must therefore produce a LOSS.

  NO INVENTED PRICES. If the required strike is not in the chain at that
  minute, the trade is skipped. It is never priced from a model.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from options_lab import costs

class AmbiguousChain(ValueError):
    """The chain carries more than one expiry.

    Strike selection used to read `chain['expiry'].iloc[0]`, so the traded
    expiry was whichever happened to sort first in the frame - chosen by
    accident, with no way for the caller to know which.
    """


TRADE_COLUMNS = [
    "entry_ts", "exit_ts", "direction", "right", "strike", "contract_id",
    "exit_contract_id", "entry_px", "exit_px", "underlying_entry",
    "underlying_exit", "gross_pnl", "cost", "net_pnl", "bars_held", "exit_reason",
]


def _empty() -> pd.DataFrame:
    return pd.DataFrame({c: pd.Series(dtype="object") for c in TRADE_COLUMNS})


def run(
    bars: pd.DataFrame,
    chain: pd.DataFrame,
    buys: np.ndarray,
    sells: np.ndarray,
    *,
    lot_size: int,
    lots: int = 1,
    regime: str = "quoted",
    max_strike_distance_pct: float = 0.02,
) -> pd.DataFrame:
    """One session. `bars` is the underlying at the signal timeframe."""
    if not (len(buys) == len(sells) == len(bars)):
        raise ValueError("signal arrays must align with bars")

    expiries = sorted(set(chain["expiry"])) if len(chain) else []
    if len(expiries) > 1:
        raise AmbiguousChain(
            f"chain carries {len(expiries)} expiries {expiries[:3]}; select one "
            f"before backtesting so the traded contract is chosen, not stumbled into"
        )

    # Price lookup: (contract_id, minute) -> close. Options print every minute,
    # so entries are snapped to the nearest available minute at or before entry.
    px = (chain.assign(ts=pd.to_datetime(chain["ts"]))
               .set_index(["contract_id", "ts"])["close"].sort_index())
    strikes = np.sort(chain["strike"].unique())

    trades: list[dict] = []
    open_pos: dict | None = None

    def quote(cid, ts):
        try:
            s = px.loc[cid]
        except KeyError:
            return None
        s = s[s.index <= ts]
        return float(s.iloc[-1]) if len(s) else None

    def close_out(pos, ts, spot, reason, i):
        exit_px = quote(pos["contract_id"], ts)
        if exit_px is None:
            return
        qty = lot_size * lots
        gross = (exit_px - pos["entry_px"]) * qty
        rt = costs.round_trip(premium=pos["entry_px"], lot_size=lot_size,
                              lots=lots, regime=regime)
        trades.append({
            "entry_ts": pos["entry_ts"], "exit_ts": ts,
            "direction": pos["direction"], "right": pos["right"],
            "strike": pos["strike"], "contract_id": pos["contract_id"],
            "exit_contract_id": pos["contract_id"],
            "entry_px": pos["entry_px"], "exit_px": exit_px,
            "underlying_entry": pos["spot"], "underlying_exit": spot,
            "gross_pnl": gross, "cost": rt.total, "net_pnl": gross - rt.total,
            "bars_held": i - pos["bar"], "exit_reason": reason,
        })

    n = len(bars)
    for i in range(n):
        ts = bars.index[i]
        spot = float(bars["Close"].iloc[i])

        # Exit first: an opposite signal on bar i-1 closes at bar i's open.
        if open_pos is not None and i > 0:
            opp = sells[i - 1] if open_pos["direction"] == "long_call" else buys[i - 1]
            if opp:
                close_out(open_pos, ts, spot, "opposite_signal", i)
                open_pos = None

        if i == 0 or open_pos is not None:
            continue

        want_call, want_put = bool(buys[i - 1]), bool(sells[i - 1])
        if not (want_call or want_put):
            continue

        right = "CE" if want_call else "PE"
        entry_spot = float(bars["Open"].iloc[i])
        k = float(strikes[np.abs(strikes - entry_spot).argmin()])
        if abs(k - entry_spot) / entry_spot > max_strike_distance_pct:
            continue

        cid = f"{chain['contract_id'].iloc[0].split('|')[0]}|" \
              f"{chain['expiry'].iloc[0]:%Y-%m-%d}|{k:g}|{right}"
        entry_px = quote(cid, ts)
        if entry_px is None or entry_px <= 0:
            continue

        open_pos = {
            "entry_ts": ts, "direction": "long_call" if want_call else "long_put",
            "right": right, "strike": k, "contract_id": cid,
            "entry_px": entry_px, "spot": entry_spot, "bar": i,
        }

    if open_pos is not None:
        close_out(open_pos, bars.index[-1], float(bars["Close"].iloc[-1]),
                  "session_close", n - 1)

    return pd.DataFrame(trades, columns=TRADE_COLUMNS) if trades else _empty()
