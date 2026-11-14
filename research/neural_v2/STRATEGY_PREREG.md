# NIFTY confirmed breakout strategy, 14 November 2026

Register one fixed strategy before calculating its results. No parameter search.
The neural study did not establish a predictive edge, so final full-data models
are not used to manufacture historical out-of-sample signals.

At the close of a minute ending a five-minute block, from 10:15 to 13:45 IST:
- CE: NIFTY closes above the preceding 20 completed minutes' high;
  BANKNIFTY is above its session EMA20 and its 15-minute return is positive.
- PE: symmetric break below the preceding 20-minute low, with BANKNIFTY
  below EMA20 and its 15-minute return negative.
- No entry when India VIX has risen more than 3% over the preceding 15 minutes.
- Skip expiry day; accept scheduled nearest weekly expiry 1-7 calendar days away.
- Buy one lot of one-strike-ITM options at next-minute open: CE offset -1,
  PE offset +1. Lock actual strike and expiry throughout the position.

Exit rules: initial -15% premium stop, +30% target. After a prior completed bar
reached +15%, protect 60% of that peak gain from the next bar onward. At 20
minutes exit if the opening premium is below +5%; otherwise hold at most 45
minutes. Always exit by 14:50. Gap stops fill at open; if a minute touches both
stop and target, stop wins. No optimistic within-bar trailing updates.

Account: Rs100000, no leverage, one position, maximum two trades/session.
Initial estimated stop loss including costs must fit Rs2500, remaining Rs3000
day budget, Rs13000 month budget and Rs40000 peak-to-equity drawdown budget.
Check account loss gates at each traded minute open while holding. Monthly
limits reset monthly, day limits daily, drawdown halt never resets. Price gaps
can overshoot a budget; this is not a guaranteed-loss cap.

Use only same-contract observed OHLC and positive-volume fills. Missing holding
quotes delay executable fills; count them, never substitute a rolling offset.
An unpriceable terminal position aborts the run rather than hiding its loss.
Account affordability includes entry spread/slippage and buy-side charges.

Report full available year and monthly net P&L, trade count, win rate after all
costs, gross P&L, fees, execution friction, profit factor, expectancy, realized
drawdown and close-mark liquidation drawdown. Re-run the entire account under
double spread/slippage; do not merely reprice a selected trade list.

Use existing dated fee schedules and R171 assumed quoted spread law. These
are simulated OHLC executions, not historical bid/ask quotes. The dataset was
already reviewed in prior research: all results are retrospective development
evidence. A profitable result would still need genuinely fresh validation.
No orders, production integration or main-branch merge.

## Fee implementation correction

The NSE February 2026 circular states that pre-March equity-options charges
were Rs3503 exchange plus Rs50 IPFT per crore, already Rs3553 total. The
initial inherited helper omitted the Rs50 contribution before March. The
strategy now uses the inclusive Rs3553/crore on both sides throughout this
window. Initial cost results are retained separately; no signal or risk
parameters changed. Re-run both complete accounts under the corrected fees.
