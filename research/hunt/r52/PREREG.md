# R52 pre-registration: older rejected index-option rules, re-tested on Nov 2024 onwards only

Written 15 Oct 2026, before any rule's P&L was looked at, apart from one engine smoke test named at the end.

Boss's asks:
- 15 Oct 2026: "any more strategies we rejected, try that too".
- 15 Oct 2026: "add the Nov 2024 onwards view. Don't test any data before that".

**Scope.** Every older rejected NSE/BSE index-option rule that the parallel sessions do not already cover. R49 has the
ORB family, R50 the zero-to-hero and expiry-hero rules, and R51 the F1 factory, R44, R29, R28, R27, R41, B2, R45, M4 and
the paused app arms. The list is in `CATALOGUE.md`. Rules are tested in priority order:
1. P1: rules that came "close" the first time.
2. P2: rules Boss proposed.
3. P3: the rest.

## Data and split

- **Lake:** `gdrive:IraAlgo-data-lake` (`index_1m`, `options_rolling_1m` WEEK series, `index_daily` for VIX). It is
  laid out for `research/obuy` by `lake2obuy.py`. Nothing is committed.
- **WEEK and MONTH:** these are the same contract for BANKNIFTY, FINNIFTY and MIDCPNIFTY in this window, so only one is
  used ('near').
- **Trades:** only on sessions from **20 Nov 2024**. Sessions from Jan 2024 are loaded only so that indicators can warm
  up. A signal on them is dropped before pricing.
- **DESIGN:** 20 Nov 2024 to 31 Mar 2026.
  - Walk-forward folds are the four quarters Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025 and Jan-Mar 2026.
  - Each fold is scored with the setting picked on the design sessions before it (expanding window from 20 Nov 2024).
- **FINAL TEST:** 1 Apr to 13 Oct 2026, read ONCE.
  - `r52study.py final` writes `results/FINAL_READ` and refuses a second read.
  - Until then the harness refuses to price any final-test session.
  - The signal functions do compute indicators over the whole lake, but no option price or P&L of a final-test session
    is read before that step.
- **Honesty note:** the earlier studies already saw parts of 2025-26:
  - their holdouts ran from Oct 2025, Apr 2026 or Oct 2025 to Oct 2026, depending on the study;
  - so the final test is not unseen data for those rules' authors;
  - only live paper from 15 Oct 2026 is truly unseen.

## Measurement (never relaxed)

- **Contract:** one held contract per trade. obuy's `Chain` re-keys the rolling ATM±10 rows by strike, so the strike
  bought is the strike priced to the exit. It is never the rolling `offset == 0` series (R45).
- **Fills:** at the next minute's open after the signal bar's close (`Execution.max_delay` 2 minutes).
  - `Fills("liq")` is R17's spread model, as in R3: the app's ±5/10 bps plus a 1/2/4-tick half-spread by the
    contract's 5-minute traded lots; stops pay double.
  - A stop gapped through fills at the bar's open, the worse price.
- **Charges:** `Costs("dated")`, the rates in force on each trade date (STT 0.1% to 31 Mar 2026, 0.15% after).
- **Lot:** the contract's own lot on the day (`lot_mode` 'data', read from the OI moves). This is the lot in force:
  - NIFTY 25 until the Dec 2024 expiries, 75 after, and 65 from the Jan 2026 series;
  - BANKNIFTY 15 then 30, and 35 from Jul 2025; and so on.
- **Size:** one lot per trade.
- **Expiry days:** as the original rule. Most skip them.
- **Square-off:** each rule's own, never later than 15:15.
- **No look-ahead:** a signal uses bars up to its signal minute (each original function's own contract; obuy's tests).
- **Multi-day holds:** these use obuy `multiday.py`, the composite path on the same contract.

## Statistics and gates

- **Random-entry twins:** obuy's pool.
  - Each real candidate gets 20 alternatives: same day, index and side, a random minute in the rule's entry window,
    the same strike rule and the same exit distances.
  - 2,000 twin draws give a one-sided p (500 or more twins, as asked).
- **Benjamini-Hochberg:** run over every final-test read in this study (the original and re-tuned setting of every
  rule tested). Design q is also given over every design variant, for information only.
- **Gates (all must hold to pass):**
  1. final-test net > 0;
  2. final-test p < 0.05;
  3. final-test q ≤ 0.10;
  4. 40 or more final-test trades (fewer: "too few trades to judge");
  5. the top 5 final-test trades make less than 100% of the final net;
  6. design walk-forward net > 0.
- **Order:**
  1. the ORIGINAL setting first (the original study's pre-registered or frozen variant, its own parameters);
  2. then a small re-tune on design only: the original study's own grid, capped at 24 variants a rule. The pick is the
     best design net with design walk-forward > 0. If no variant has walk-forward > 0, the original is the only one
     taken to the final test.
  Both are read once in the final test.
- **Reported for each read:** trades, win rate, net, net/day (over every session of the rule's indices in the period),
  average win and loss, worst day, max drawdown, p, q, pass or fail. The per-index split and fold nets are also given.

## Signal source

The signal source is stated for every rule:
- index (the default);
- index with option-chain context (OI, PCR, IV as a filter);
- option-chart [NON-DEFAULT]. These rules are reported separately, and only Boss could switch them on.

## Outcome rule

- **A rule that passes every gate:** may come back on PAPER only, as a candidate for Boss's decision. Per CLAUDE.md a
  passed idea may act on paper arms, logged in the Autopilot timeline. R52 changes nothing in the app.
- **Everything else:** stays off.
- **Not finished:** rules not reached are listed as "left" in the write-up.

## Smoke test before this file

One engine smoke test ran on DESIGN sessions before this file was committed. It used the obuy Camarilla fade at grid
point 0 (5-minute, R3/S3, pivot target, ATM, 15:15 square-off), to check that the harness prices held contracts on the
new lake. No final-test session was read. That rule (H4-4 / OBUY GB MR-04) is tested at its frozen variant below, like
every other rule.

## Amendment 1 (15 Oct 2026, after the first design runs; no final-test session read)

The first design runs showed a flaw in the same-side twins. A twin keeps the real trade's side, so on a day when a
breakout rule bought a call it buys a call too, and it may enter earlier in the window, before the breakout. That twin
already knows the day's direction. The R1-2 NIFTY twins averaged +Rs 1,868 a trade against the rule's +Rs 296.

- **Gate unchanged:** the gate stays exactly as pre-registered, on the same-side twin p. It is the stricter test.
- **New column, information only:** each read also gets **p_flip**, from the same twins with a coin-flip side. This is
  obuy's own default and the baseline the OBUY studies used.
- **Flagging:** a rule that fails only the same-side p but passes every other gate with p_flip < 0.05 is flagged in the
  write-up as "fails only the same-side twin test". It does not pass.
- **Rerun:** every design run so far is rerun with both pools.

## Amendment 2 (15 Oct 2026, after the one final read): foreign-rows and stray-print re-pricing

Foreign-rows and stray-print re-pricing of the passing rule and the near-misses; same rules, same spans, same gates;
the final test is re-priced, not re-selected.

Why: R47 (branch integ-1014c, `research/hunt/r47/r47data.py foreign_rows`) found rows of ANOTHER underlying in the
lake's rolling option files and added a stray-print guard; R52 applied neither. Written before any cleaned number exists.

- **Cleaning:** `research/hunt/lakeclean.py`, the same code and thresholds as R51 Amendment A (foreign rows: own spot
  more than 3% from the index close at that minute, dropped; stray prints: per contract and session, an open more than 2
  ticks and 20% from the median of the neighbouring closes drops the row, a high more than 20% above it is capped).
  Applied when the lake is laid out for obuy (`lake2obuy.py --clean`) to every option file, WEEK and MONTH.
- **What is re-priced:** the passing setting H37-10:g17. R52 had no near-miss list in its brief; H37-10:orig (same
  signal, original exits) is re-priced as information only.
- **Same rules and spans:** design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 13 Oct 2026, the frozen g17 setting (never
  re-picked). Same twins (obuy pool, 20 alternatives, 2000 draws, seed 52; p_flip as Amendment 1).
- **BH:** over the same 188 final reads, the re-priced p for H37-10:g17 (and :orig) and the original p for the rest.
  Gates unchanged; the walk-forward gate uses g17's re-priced design folds.
- **Also reported (information, no gate):** drop counts per index; how many of g17's ORIGINAL final trades touched a
  dropped or capped row; the per-index split (the original's +Rs 46k of +Rs 49k came from MIDCPNIFTY); g17 at twice
  the spread and slippage.
- **Read once:** `r52amend.py run` writes `results/amend_clean.json` and refuses if it exists or if this amendment is
  not committed unchanged.

**Note to Amendment 2 (15 Oct 2026, before the cleaned read).** obuy finds expiry days from the chain's last prices, and
the stray step changed 36 of its 3,452 index-day flags (lots unchanged), which would change which days a rule skips. As
in R51 Amendment A (labels from the rows after the foreign step, before the stray step), the cleaned run pins the expiry
flags read from a foreign-only layout (`lake2obuy.py --foreign-only`, `r52amend.py labels`); those flags equal the
original layout's on every index-day. The current lake reproduces R52's committed final test exactly.
