# Retest entries vs immediate breakout —14November2026, before outcomes

Continue65%-net-win plus positive-after-cost research. No claim that retests are
new to finance or absent from all prior studies. This is an explicitly specified
paired-rule comparison, not threshold selection to manufacture65%.

## Levels and entry events
Four underlying level definitions: preceding20minute range; first15minute
opening range (09:15–09:29); previous full observed session high/low; contraction
breakout (preceding20minute range <=half preceding60minute range). All rolling
levels exclude the current bar. Require contiguous lookback minutes. Opening
range requires all15 exact minutes; prior levels require>=300observed bars in
the earlier session. Decision window09:45–13:45IST, every completed minute.

Breakout: close exceeds high+0.10ATR14 (CE) or low−0.10ATR14 (PE). ATR from
completed current data. Freeze the raw broken level and tolerance at the event.
Each side latches after an event until price has returned inside its current
level and no pending event exists. Session boundaries/gaps clear pending states.

Two entries: immediate at next option open after the breakout close; retest only
on a later1–5minute candle whose extreme touches within ±0.10ATR of the frozen
level and closes back beyond it, with a candle body in the trade direction.
A long low below level−tolerance (short high above level+tolerance) invalidates
the pending retest; so does timeout or an observed minute gap. No same-bar
break/retest. Do not retrospectively use the successful retest as an immediate
entry filter. Signals on the index, option bought at actual next-minute open.

Three contexts: plain; completed5minute trend (EMA9>EMA21 and close>EMA9, mirrored
for PE); trend plus reward/cost context. Five-minute EMA carries earlier complete
session bars, requires21bars, and uses ONLY completed higher bars less than5min
old from the same session. Cost context: reported-IV modeled absdelta0.45–0.85,
valid positive IV, and prior15minute realized20minute expected delta move >=3x
estimated round-trip costs using the current selected option close. This is a
proxy, not a forecast or a guarantee. Never use next-open/future prices in gates.

Two exits, same within immediate/retest comparison: quick=15%initial stop,
+10%target,20minute max; runner=15%initial stop, no target/lock, exit after20min
unless at least+5%, max60min. Gap/stop-first OHLC,14:50square-off and shared cash/
risk gates. One lot one-strike ITM NIFTY weekly,1–7days away; skip expiry day.
Selected strike is fixed at each entry, not held as a rolling offset.

4leveltypes x2entries x3contexts x2exits=48fixed primary rules. Sensitivity is
fixed IN ADVANCE to range20/retest/plain/runner: wait4/5/6minutes x tolerance
0.08/0.10/0.12ATR; center already included,8extra unique rules =>56rules.
Also replay the fixed center with1minute additional execution delay, retaining
the original chosen strike. No best-outer-rule sensitivity or latency selection.
Normal/doubled friction each independent account:112full endpoints +2delay
endpoints =114jointBH tests (five-day-block mean-net null). Prior unknown search
multiplicity remains uncorrected; all development history was already inspected.

## Account and robustness
Latest12months,100000capital, max2trades/day,2500initial risk,3000daily,
13000monthly and40000drawdown budgets. Account controls can stop trading early.
Report all full ledgers, costs, realized/close-mark DD, missing holding quotes,
trade counts, Wilson intervals and1000trade permutations +1000five-day blocks.
Trade shuffles preserve total fixed-rupee P&L and do not rerun cash gates.

Chronological diagnostic windows: throughMar2026,Apr–Jun,Jul–Oct; each restarts
100000 and is NOT a concatenated portfolio. Separately do WF selection among
48original rules using ONLY preceding sessions: >=100trades,win>=65%,positive
normal/stress net,PF>1 and risk gates, ranked by stressed net. None =>NO_TRADE.
Carry one actual WF account across the two later windows. Report chosen rules
and preceding scores; do not claim this restores a pristine final holdout.

A research lead requires>=100executed trades,>=65%win,positive normal/stress,
registered limits, stable neighbors and no unsupported significant-edge claim.
A dependable65%probability claim additionally needs lower95%confidence>=65%
and genuinely new forward data. No fresh proof here; no production change,
paid compute, real order or approval to merge main. No new neural training:
Payoff20's prior low-AUC results are not erased or silently retuned.
