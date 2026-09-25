# ORB and ORB Fresh: the strategy, exactly

Source of truth: the desktop app `D:\New Trading app`, `services/ai_signals/orb_arm.py`
(copied to `reference/orb_arm.py`). This file restates it so the phone engine can
reproduce it without the desktop code. Where this file and the code disagree, the
code wins; report the difference.

Status: a **paper forward test**, not a validated strategy (t about 1 against a 3.72
noise floor in the backtest). It runs on the sandbox only. Nothing here justifies
real money or more lots. See "Pass rule".

## Two arms, one rule

| Arm | Difference |
|---|---|
| `orb` | The pre-registered rule. |
| `orb_fresh` | Same rule, but enters only on a FRESH break: the previous completed bar did not already close outside the range on the same side. |

Both arms share every other rule, the same strike and the same contracts for the day.
Each arm holds at most one position and keeps its own set of decided bars.
A third arm, `orb_idx` (index stop 50 points), was retired on 2026-09-23; its
`idx_stop` capability stays in the engine, off.

## Instrument

- Underlying: **BANKNIFTY** index (`NSE_INDEX`), 5-minute bars.
- Options: `NFO`, contract = the **nearest listed option expiry strictly after today**
  (BANKNIFTY lists monthlies only, so in practice the near monthly; never today's expiry).
- Symbol: `BANKNIFTY{DD}{MON}{YY}{strike}{CE|PE}`, e.g. `BANKNIFTY29SEP2656300CE`.
- Size: **1 lot** (lot size from the instrument master; refuse if unknown).
- Product `MIS`, entry `MARKET` BUY.

## Timeline (IST)

| Time | What |
|---|---|
| 09:15-10:00 | Opening range: high/low of the 5m bars **labelled** 09:15 .. 10:00 (10 bars). The range exists only once the 10:00 bar has completed. |
| 09:20 bar | Strike: ATM from the close of the first completed bar at or after 09:20, rounded to 100 **half-up** (`floor(spot/100 + 0.5) * 100`, not banker's rounding). Fixed for the whole day, for both arms. |
| bars labelled after 10:00 and before 14:30 | Decision bars (10:05 .. 14:25). |
| 15:10 | Square-off: any open position is sold (`session_end`). |
| 15:12 | The arm stops running for the day. |
| 15:15 | Backstop square-off (sandbox MIS auto square-off). |

Runs every minute from 09:20 to 15:12, Monday to Friday, skipping NSE holidays.

## Entry (per arm, once per completed bar)

On each minute, take only **completed** bars (`bar.start + 5 min <= now`; the forming
bar is never a signal). Let `last` be the latest completed bar.

1. If this arm already decided `last`, do nothing (idempotent within a bar).
   Mark `last` as decided **before** deciding.
2. `last` must be a decision bar (label after 10:00, before 14:30), else `no_decision_bar`.
3. Cooling down: if the arm exited today and `last.start <= start of the 5m bar the
   exit happened in`, skip (`cooling_down_after_exit`). Re-entry is allowed from the
   bar after the exit bar.
4. Direction: `last.close > range_high` buys the **CE** (+1); `last.close < range_low`
   buys the **PE** (-1); otherwise `inside_range`.
5. `orb_fresh` only: if the previous completed bar broke the **same** side, skip
   (`not_a_fresh_break`).
6. Refuse without a lot size or a quote (never enter blind).
7. Refuse if the expected premium is **<= Rs 40**: a 40-point stop would have no
   level (the Python arm crashes here after the buy; the phone port refuses first).
8. Honour the operator's Stop for today if it was pressed mid-decision.
9. The account guard (see `risk_config.json`) checks every entry.
10. Send the MARKET BUY. Book the **actual fill price** as the entry, not the quote.

## Exits

- **Stop: entry fill - 40 premium points**, as a **resting SL-M SELL** placed right
  after the entry fills, trigger rounded to the 0.05 tick. The resting order owns the
  stop: the arm never sends a second sell for a stop the book will fill (that is how
  one position becomes two orders).
- **Target: entry + 40 premium points**, checked on price polls; exit with MARKET SELL.
- **15:10 session end**, MARKET SELL.
- **Operator stop**: MARKET SELL, same path.
- Exit order: first take the resting stop out of the book (cancel), then sell. If the
  cancel fails and the stop already filled, record the stop fill instead. If both the
  stop and the sell filled (race), immediately BUY back to flatten.
- A stop order found `cancelled`/`rejected` is treated as gone, not retried.
- Stop and target come from the shared risk core (`services/risk`,
  `evaluate_trail` with `side=BUY, sl_points=40, target_points=40`), never
  reimplemented per consumer.

## State that must survive a restart

Per open position: arm, symbol, qty, entry fill, entry time, the decision bar,
`idx_ref` (index close at decision), `dir`, the range, `stop_order_id`, `stop_trigger`.
Per arm per day: the set of decided bars, the last exit time. Per day: strike and the
CE/PE symbols. The operator stop: a per-day record (a record from an earlier day is
ignored, so the arms resume at 09:20 next session with no action).

## Operator controls

- **Stop for today**: no new entries; open positions of both arms are closed on the
  next cycle (never from the button's thread). **Start again** undoes it for today.
- The whole feature is gated by a flag (`ORB_ARM_ENABLED` on the desktop).

## Pipe

Always the **sandbox/paper** pipe, decided in the arm itself, never from a global
live/paper switch: an operator flipping the platform to live while a paper position is
open must not send its exit to the broker.

## Pass rule (pre-registered, `reference/92_orb_forward_test_preregistration.md`)

The forward test ends at 60 closed trades or 40 trading days, whichever first. It
passes only if all hold on the forward trades alone: net P&L > 0 after charges;
per-trade t > 2.0; net P&L > 0 on up days AND on down days; net P&L > 0 after
removing the three best trades. A pass earns a longer forward test at the same size,
not capital. Count started 2026-09-23 13:36 IST (desktop). Operator-closed trades
are excluded.

## Known traps (each has bitten already)

- Timestamps: bars are labelled by their **start** in IST; a bar is usable only
  after it closes.
- Fill rule in any replay: act on a bar's close, fill on the **next** bar's open.
  Filling on the signal bar was a look-ahead bug that faked a +23,727 result.
- Strike data: a historical store without the ATM strike makes a replay trade deep
  ITM/OTM options; filter `|strike - spot| <= 100`.
- The account guard counts every order (entry, resting stop, exit) toward the daily
  order cap: two arms on a trend day need far more than 10.
- A polled stop let a fast fall run 9.5 points past -40; hence the resting SL-M.
- One arm's resting stop must only ever fill that arm's own order
  (`checkStop(orderId)`, not by symbol): both arms can hold the same contract.

## Reference implementations

`reference/` holds the desktop Python arm, the phone Kotlin port with its parity
tests' vector generator, the cost model and the account guard. The Kotlin port
(`OrbRules.kt`, `Replay.kt`, `ArmRunner.kt`, `Costs.kt`, `AccountGuard.kt`) was
checked against the Python: 6 rule tests, a 300-day replay (about 3,020 trades) and
222 account-guard vectors, all equal.
