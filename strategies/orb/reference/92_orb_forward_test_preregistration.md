# Pre-registration: the opening-range-break paper arm

Written 2026-09-23, before the first forward-test trade. Nothing below may be
changed after the arm starts trading. A change means a new pre-registration and
a fresh count from zero.

## What is being tested

The operator's discretionary method, reduced to the one rule family that
survived a 2,800-combination walk-forward search (final_verdict/90_every_path.py):

- **Direction from the regular index first.** BANKNIFTY 5-minute index bars;
  opening range = high/low of the bars labelled 09:15 to 10:00. A completed bar
  labelled after 10:00 and before 14:30 that closes above the range high buys
  the CE; below the range low buys the PE.
- **Then the option.** One ATM strike fixed from the 09:20 bar's close (rounded
  half-up to 100), held all day. Nearest monthly expiry that is not today.
- **Exit on the option's own price.** +40 or -40 points on the premium, decided
  by the shared risk core (services/risk), or square-off at 15:10.
- One position at a time. Re-entry allowed on a bar after the exit bar.
- 1 lot. Always the SANDBOX pipe, never the broker.

Code: services/ai_signals/orb_arm.py. Tests: test/test_orb_arm.py.
Logged to the activity table under source = "orb", apart from the AI engine's
own forward test.

## Why it is only a forward test

Backtest, 77 days (in-sample 53 / holdout 24): best cell +Rs 17,499 total,
holdout +Rs 13,251 on 80 trades, about Rs 227 per trading day on one lot. It
passed drop-best-3 (holdout still +Rs 10,056) and the up/down-day split. It
FAILED significance: t around 1 against a noise floor of 3.72 for the 2,800
cells searched. Two of five months (June, September) carried it.

## The pass rule

The forward test ends at whichever comes first: **60 closed trades** or **40
trading days**.

It PASSES only if ALL of these hold on the forward trades alone:

1. Net P&L > 0 after the sandbox's own charges.
2. Per-trade t-statistic > 2.0.
3. Net P&L > 0 on up days AND on down days (index close vs open).
4. Net P&L still > 0 after removing the three best trades.

If it passes: it earns a second, longer forward test at the same size, not
capital. If ANY clause fails: the family is closed and not re-searched.

## What this cannot be used for

It cannot be used to justify more lots, real money, or a looser rule, whatever
the first week looks like. A good first week is the most dangerous result this
test can produce, because it is the one that invites changing the rule.

## Amendment 2026-09-23 13:25 IST: execution only, the rule is unchanged

From this time the -40 stop RESTS in the sandbox as an SL-M sell placed at
entry (trigger = actual fill - 40, from the shared risk core), checked by the
sandbox engine every few seconds, instead of a once-a-minute poll by the arm.
Reason: on 2026-09-23 a polled stop let a fast fall run 9.5 points past the
level (orb_idx, -Rs 1,486.50 instead of -Rs 1,200 gross). Entries now also book
the actual fill price rather than the quote. Positions opened before this time
keep the polled stop until they close. The trade count is NOT restarted: the
pre-registered rule is still exit at -40; this makes execution match it.

## Fresh start 2026-09-23 13:27 IST

At the operator's request the sandbox DATA was reset (orders, trades, positions,
P&L history cleared; funds back to the configured Rs 1,00,000; settings kept --
the built-in reset was NOT used because it also forces starting capital to
Rs 1,00,00,000). All ORB arm rows before this point were removed; backups in
db/backups/*_pre_reset_20260923_132551.db. From here every trade uses the final
execution (resting SL-M stop at fill - 40, fills booked at the real price).
**The count starts at 2026-09-24 09:20**, the first complete day. Any trade the
arms take for the rest of 2026-09-23 is warm-up and excluded from the result.

## Amendment 2026-09-23 13:5x IST: the count starts now, not tomorrow

At the operator's request the count starts at **2026-09-23 13:36 IST**, the
moment the final configuration went live (two arms, resting SL-M stops, sandbox
reset to Rs 1,00,000). This supersedes "count starts 2026-09-24 09:20" above.
Written before any of today's post-13:36 trades had closed; the rules (entry,
40/40, 15:10 square-off, pass rule, head-to-head) are unchanged. 2026-09-23 is a
partial day and is reported as such in every day-level clause.
The orb position opened at 13:28 (BANKNIFTY29SEP2656300CE @ 549.75, still open
at 13:57, currently losing) COUNTS: orb's rules and execution were final from
the 13:27 reset; the 13:36 change only retired a different arm. Including it
is the conservative choice.

## Note 2026-09-23 14:35 IST: first counted trade closed by the operator, EXCLUDED

The 13:28 orb trade (30 x BANKNIFTY29SEP2656300CE @ 549.75) was closed at
14:31:59 by a manual chart-trading SELL (MIS, 570.00), and its resting SL-M was
cancelled by hand at 14:32:02. The rule chose neither exit (target 589.75, stop
509.75), so the trade says nothing about the rule: it is recorded with
close_reason "orb_operator_closed" (+Rs 607.50 gross) and EXCLUDED from every
pass-rule clause. Counting it at a hypothetical rule exit would be a guess.

## Amendment 2026-09-23 16:0x IST: execution only, the rule is unchanged

The account-wide guard (services/risk/account_guard.py) refuses new entries once
10 orders have been sent in a day, counting EVERY order that reaches a pipe --
entry, resting SL-M and exit -- across all strategies. The replay never modelled
it: in Aug-Sep it would have cut orb + orb_fresh off on 17 of 25 trading days,
including every strong trend day (2026-09-11 alone needed 53 orders), so the
paper test would have measured "rule + cap" instead of the rule. The counter is
in memory and resets on every restart, which is why it never bit on 2026-09-23.
At the operator's choice ACCOUNT_MAX_TRADES_TODAY is raised to 60 in .env for
PAPER trading only (TODO in place to remove it before live). No trade had been
counted when this was made. Entry, 40/40, 15:10 square-off, the pass rule and
the head-to-head are unchanged.

## Amendment 2026-09-23 16:1x IST: execution only, the rule is unchanged

The guard also refuses new entries once equity is 10% below a PERSISTED peak
(db/account_peak.json). Once hit it never clears by itself -- without trades
equity cannot recover -- so one ordinary losing stretch would end this test
without saying anything about the rule: in the 3-month replay
(106_account_sim_3_months.py) orb + orb_fresh on one wallet would have been
halted on 2026-08-04 and missed all of September. At the operator's choice
ACCOUNT_MAX_DRAWDOWN_PCT is raised to 30 for PAPER trading only (TODO to put it
back to 10 before live), and the stored sandbox peak -- Rs 1,01,612.35 from
before the reset -- is re-based to the post-reset balance, Rs 99,839.66. No
trade had been counted when this was made. The pass rule is unchanged and still
fails a rule that loses money.

## Amendment 2026-09-23 16:4x IST: orb leaves the paper account and runs as a nightly shadow

At the operator's choice, orb places no paper orders from 2026-09-24. In the
account view it shared one Rs 1,00,000 wallet with orb_fresh, lost Rs 6,636 in
the July replay and took the cash orb_fresh needed (final_verdict/107). Its
rule is now replayed every trading evening at 15:40 IST from the day's 5-minute
bars by services/ai_signals/orb_shadow.py -- the arm's own decision functions
and the backtest's execution (next-bar-open fill, stop at fill - 40 or the gap,
+40 target, 15:10 square-off, real charges) -- and recorded in
db/orb_shadow.jsonl. Checked on 2026-09-23 real data: it reproduces
99_all_arms_backtest exactly (4 trades, -Rs 344.81). This test's pass rule is
evaluated on those replayed trades from 2026-09-24. No orb trade was ever
counted: the only one after the count began was operator-closed and excluded.

## Data note 2026-09-23 17:0x IST: the backtest behind this plan used the wrong strikes on 32 of 75 days

final_verdict/108_contract_months.py found that the stored 29SEP26 series holds
no at-the-money strikes for most of May-July: the replay bought the nearest
STORED strike, a median 1,935 points from the index in May, 1,086 in June and
162 in July (worst 3,669), so on those days it traded deep in- or out-of-the-
money options, not the at-the-money one the live arm buys. Only 43 of 75 days
had a strike within 100 points. On those 43 days orb_fresh made +Rs 12,351
(50 trades, 66% win) and orb +Rs 4,418; on the 18 of them 0-35 days from expiry
(what the live arm trades) orb_fresh made +Rs 11,967 (27 trades, 74% win) and
on the 25 further out +Rs 385. The backtest figures quoted above rest partly on
the mis-struck days. Nothing here changes a rule or the pass rule: the forward
test, which buys the real at-the-money near-month option, is the evidence.

## Amendment 2026-09-23 17:4x IST: orb is back in the paper account

The operator switched the AI engine off (AI_SIGNALS_AUTO_TRADER_ENABLED=FALSE),
which frees the shared wallet, and chose to run both ORB arms as paper arms
again. This reverses the 16:4x shadow amendment before any orb trade happened
under it. orb places paper orders from 2026-09-24 and its pass rule is judged
on those real paper trades. The 15:40 replay (db/orb_shadow.jsonl) keeps
running for both arms as a cross-check of execution.

## Note 2026-09-24 01:xx IST: evening replay switched off, nothing else changes

At the operator's instruction the live system runs ONLY the BANKNIFTY orb and
orb_fresh paper arms. The 15:40 evening replay (orb_shadow.py) is off
(ORB_SHADOW_ENABLED=FALSE), so the replay cross-check of execution is no longer
recorded; the head-to-head and the pass rule were already on the real paper
trades and are unchanged. Minute snapshots of BANKNIFTY, NIFTY and SENSEX and
their near-the-money options are now collected for later versions
(services/market_data_collector.py); nothing in them feeds these arms.
