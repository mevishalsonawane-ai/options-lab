# R73: a trend check, or a wait of 1-minute candles, before Donchian buys the option

16 Oct 2026. Research only: nothing in the app changes (no `android/**`). Paper or not is Boss's call; live is not
touched. Pre-registered in `research/hunt/r73/PREREG.md` (committed before any result, 4e06957); code and design picks
frozen before the final read (5cd2a8b). Full tables: `research/hunt/r73/results/report.md`.

Boss, 16 Oct: *"Can we add trend find for Donchian, or wait for 5 candles before buying any options, which will help us
get direction; it could help us with less loss."* (the candles: *"1 min 5 candles"*).

**Signal source, every rule: the index.** Donchian 20 on the index's 5-minute bars gives the signal; every trend check
and every wait reads only the index's own 5-minute or 1-minute candles, and only candles that have closed by the time
of entry (proved by the look-ahead test in `research/hunt/r73/test_r73.py`). Then the 1-ITM option is bought, as on
paper today.

## Disclosure first

This study was first started as "R72" (branch `claude/r72-donchian-trend-wait`, pre-registration commit ad55e171). That
first run computed the final period once, but its session was stopped and its results were lost before anyone recorded
them. This rerun (renumbered R73, because R72 went to the cross-index study) uses the same pre-registered plan, copied
word for word. **Its final read is therefore the second computation of the same frozen rules.** Nobody saw the first
run's numbers, so they could not steer this one, but Boss should know the final period was computed twice. The code was
written again in this session (the first run's code was lost too); the choices the plan left open are listed under
"Honest limits".

## The answer

- **Waiting for 5 one-minute candles does not reduce losses in a way we can trust, and it costs a lot of profit.** It
  looked good in the final period (Apr - Oct 2026) but lost badly in the 16 months before (Nov 2024 - Mar 2026):
  "wait 5 candles and the break holds" made Rs 1,63,945 in the final but **lost Rs 1,26,378 in design**, where plain
  Donchian made Rs 9,998. The strict version (break holds *and* price kept going) lost Rs 1,90,929 in design. Every
  wait (2, 3 or 5 candles, three ways each) lost money in design, so all nine fail.
- **Why waiting fails:** the wait does pick the better breakouts, but by the time you know, the move is gone. On the
  breakouts where the 5 candles held, Donchian's normal entry made **Rs 9,55,316** in design (2,737 trades); the same
  breakouts entered 5 minutes later made **-Rs 66,989**. The option price is only about 0.1% higher after the wait; the
  loss comes from entering after the burst, so the 30-minute exit and the stops catch the pull-back instead of the
  move. Waiting also drops many winners: in the final, the 5-candle wait (strict) skipped 737 losers (Rs 9,38,252) but
  also 374 winners (Rs 5,31,945, 20 of them big +30% winners worth Rs 96,700).
- **One trend check works: Supertrend (10, 3) on the same 5-minute index bars (T7).** Take a Donchian call only if
  Supertrend is up, a put only if it is down. It passed every pre-registered gate and beat Donchian in design,
  walk-forward **and** final, under both exit sets:
  - final: **Rs 1,34,743 vs Rs 81,845** (1,670 vs 1,781 trades), max drawdown Rs 81,683 vs Rs 91,626, worst day
    -Rs 21,578 vs -Rs 23,012;
  - design: Rs 50,425 vs Rs 9,998 (4,208 vs 4,498 trades), worst day -Rs 49,738 vs -Rs 56,647;
  - it beats skipping the same number of Donchian trades at random (skip p 0.006, BH q 0.018), and beats random entry
    times on the same days (p 0.002, q 0.013).
  - **What it costs:** in the final it skipped 273 Donchian trades: 162 losers (Rs 1,70,175 saved) and 111 winners
    (Rs 1,61,923 missed, 5 of them big +30% winners worth Rs 23,400). The freed slots took 162 other Donchian
    signals that made Rs 44,646. So it is not "fewer losses for free": it trades losers for winners almost one for
    one, slightly in our favour, and the rest of the gain comes from the freed slots.
- **On top of Breadth Donchian (R72) it adds nothing we can trust.** R72's Breadth Donchian (trade a break only when
  4 of the 5 indices break on the same bar) is the stronger filter (final Rs 1,71,366, design Rs 2,16,929). Adding
  Supertrend to it helped in design (Rs 2,56,207) but not in the final (Rs 1,65,758, random-skip p 0.46). So if Boss
  moves the Donchian arm to Breadth Donchian, leave Supertrend out; if Donchian stays as it is, Supertrend is a small,
  honest improvement.

## The table (1 lot per trade, net after all charges and slippage; exits P = paper today)

Final = 1 Apr - 13 Oct 2026 (133 sessions); design = 20 Nov 2024 - 31 Mar 2026 (337 trading days); walk-forward = the
four design quarters Apr 2025 - Mar 2026.

| rule (signal on the index) | final trades / net | win | avg win / loss | worst day | max DD | green days | design net | WF net | random-entry p (q) | random-skip p (q) | final: losers avoided / winners missed (big +30%) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Donchian 20 (paper today)** | 1,781 / Rs 81,845 | 44% | Rs 1,609 / -Rs 1,183 | -Rs 23,012 | Rs 91,626 | 47% | Rs 9,998 | Rs 1,75,789 | | | | baseline |
| **T7 Supertrend (10, 3) agrees** | 1,670 / **Rs 1,34,743** | 45% | Rs 1,651 / -Rs 1,197 | -Rs 21,578 | Rs 81,683 | 46% | Rs 50,425 | Rs 2,13,748 | 0.002 (0.013) | 0.006 (0.018) | 162 / Rs 1,70,175 ; 111 / Rs 1,61,923 (5 / Rs 23,400) | **PASS, recommended (paper)** |
| T2 close beyond EMA 50 | 1,691 / Rs 94,768 | 44% | Rs 1,642 / -Rs 1,190 | -Rs 20,307 | Rs 97,928 | 47% | Rs 79,607 | Rs 2,43,221 | 0.004 (0.013) | 0.14 (0.25) | 114 / Rs 1,30,357 ; 97 / Rs 1,42,473 (7 / Rs 30,049) | gates pass, but no better than skipping at random |
| T1 close beyond EMA 20 | 1,781 / Rs 79,224 | 44% | | -Rs 23,012 | Rs 91,626 | 47% | Rs 10,019 | Rs 1,75,810 | 0.004 (0.013) | 1.0 | 1 / Rs 907 ; 1 / Rs 162 | changes 1 trade: it is Donchian itself |
| T8 beyond the day's average price | 1,667 / Rs 28,458 | 44% | | -Rs 21,178 | Rs 83,640 | 44% | Rs 38,372 | Rs 2,09,841 | 0.10 (0.15) | 0.996 | 153 / Rs 1,88,616 ; 128 / Rs 2,55,423 | fail |
| T5 ADX above 20 | 1,496 / Rs 41,716 | 45% | | -Rs 36,006 | Rs 93,384 | 45% | -Rs 87,955 | Rs 63,545 | 0.09 (0.14) | 0.83 | 306 / Rs 3,27,012 ; 259 / Rs 4,18,853 | fail |
| T6 ADX above 25 | 1,167 / -Rs 31,371 | 43% | | -Rs 24,693 | Rs 91,392 | 40% | -Rs 1,74,950 | -Rs 7,946 | 0.79 | 0.996 | | fail |
| T3 15-min EMA 20 rising | 1,505 / -Rs 44,549 | 43% | | -Rs 19,790 | Rs 1,52,997 | 41% | -Rs 1,07,467 | Rs 59,381 | 0.58 | 1.0 | 327 / Rs 3,79,425 ; 309 / Rs 5,18,671 | fail |
| T4 1-hour EMA 20 rising | 1,064 / -Rs 1,01,478 | 40% | | -Rs 20,318 | Rs 1,58,117 | 37% | -Rs 57,001 | Rs 50,749 | 0.94 | 1.0 | 514 / Rs 6,03,036 ; 448 / Rs 7,74,056 | fail |
| W2H wait 2 candles, break holds | 1,562 / Rs 1,24,449 | 45% | | -Rs 20,892 | Rs 88,689 | 47% | **-Rs 24,689** | Rs 70,338 | 0.008 (0.018) | 0.02 (0.045) | 353 / Rs 4,24,607 ; 149 / Rs 2,08,071 | fail (lost in design) |
| W3H wait 3, break holds | 1,515 / Rs 1,59,749 | 47% | | -Rs 20,873 | Rs 67,585 | 44% | **-Rs 64,912** | Rs 68,266 | 0.002 (0.013) | 0.002 (0.014) | 415 / Rs 5,08,676 ; 177 / Rs 2,36,632 | fail (lost in design) |
| W3HD wait 3, holds + kept going | 1,197 / Rs 1,73,909 | 46% | | -Rs 20,482 | Rs 36,968 | 49% | **-Rs 73,544** | -Rs 22,014 | 0.004 (0.013) | 0.002 (0.014) | 693 / Rs 8,76,033 ; 342 / Rs 4,69,488 | fail (lost in design and WF) |
| **W5H wait 5 candles, break holds** (Boss) | 1,406 / Rs 1,63,945 | 45% | Rs 1,632 / -Rs 1,142 | -Rs 22,419 | Rs 79,808 | 47% | **-Rs 1,26,378** | Rs 75,559 | 0.004 (0.013) | 0.004 (0.018) | 519 / Rs 6,40,039 ; 218 / Rs 2,82,431 (9 / Rs 38,306) | fail (lost in design) |
| **W5D wait 5, price kept going** (Boss) | 1,191 / Rs 26,965 | 44% | | -Rs 18,236 | Rs 69,222 | 44% | **-Rs 3,17,522** | -Rs 84,090 | 0.56 | 0.81 | 682 / Rs 8,76,129 ; 313 / Rs 4,40,578 | fail |
| **W5HD wait 5, both** (Boss) | 1,097 / Rs 53,618 | 44% | Rs 1,547 / -Rs 1,139 | -Rs 20,295 | Rs 44,962 | 45% | **-Rs 1,90,929** | -Rs 48,804 | 0.48 | 0.53 | 737 / Rs 9,38,252 ; 374 / Rs 5,31,945 (20 / Rs 96,700) | fail |
| C = T2 + W2H (best of each on design) | 1,493 / Rs 1,16,431 | 44% | | -Rs 20,892 | Rs 1,01,112 | 45% | -Rs 29,805 | -Rs 99,953 | 0.008 (0.018) | 0.056 (0.11) | 407 / Rs 4,83,572 ; 218 / Rs 2,96,787 | fail |

(W2D, W2HD, W3D: final Rs 1.44 - 1.52 lakh but design -Rs 43k to -Rs 95k: fail. Every row of all 36 tests, the second
exit set and the design tables are in `results/report.md`.)

Under exits Q (the same without L25) the picture is the same: Donchian Rs 78,777, **T7 Rs 1,34,308** (recommended),
every wait fails on its design loss.

### What waiting costs (final, breakouts taken both ways, exits P)

| wait | breakouts (final) | index already moved in the break's direction (mean / median points) | option fill vs Donchian's (median, per lot) | the same breakouts: Donchian's entry vs after the wait (design) |
|---|---|---|---|---|
| 2 candles, holds | 1,279 | 7 / 4 | Rs 0 | Rs 5,68,622 vs Rs 63,165 |
| 3 candles, holds | 1,189 | 8 / 4 | Rs 2 | Rs 6,94,960 vs -Rs 10,730 |
| 5 candles, holds | 1,044 | 10 / 6 | Rs 21 | Rs 9,55,316 vs -Rs 66,989 |
| 5 candles, both | 670 | 28 / 16 | Rs 124 | Rs 13,54,104 vs -Rs 28,061 |

The fill price hardly changes (on 26-37% of these breakouts the strike also moves one step with the index, which keeps the
premium near the same), so the loss is not the entry price: it is that the profitable part of a Donchian break is the first few minutes,
and waiting gives exactly that part away.

### Breadth Donchian and Supertrend together (labelled extras, not gated)

| book (exits P) | design trades / net | final trades / net | final max DD | final worst day |
|---|---|---|---|---|
| C1 Breadth Donchian (R72) | 3,071 / Rs 2,16,929 | 1,202 / Rs 1,71,366 | Rs 61,884 | -Rs 22,571 |
| C1 + T2 (the extra fixed on design: the best design variant) | 2,994 / Rs 2,22,607 | 1,158 / Rs 1,60,479 | Rs 73,023 | -Rs 22,571 |
| C1 + T7 (looked at after the final read, because T7 is the one recommended) | 2,965 / Rs 2,56,207 | 1,163 / Rs 1,65,758 | Rs 68,021 | -Rs 22,571 |

Neither filter adds to Breadth Donchian in the final (random-skip p 0.65 and 0.46).

**Fuller check, 16 Oct (Boss: "check r72 ... find if this super trend works on that"; after the final read,
information only, `research/hunt/r73/c1_supertrend.py`, `results/extra_c1_T7_full.json`).** The R72 app session has
built the Breadth Donchian paper arm (`claude/breadth-donchian-arm`, waiting on its CI). Supertrend on top of it:

| C1 Breadth Donchian vs C1 + Supertrend | design net (trades) | walk-forward net | final net (trades) | final max DD | final worst day | final green days |
|---|---|---|---|---|---|---|
| exits P, C1 alone | Rs 2,16,929 (3,071) | Rs 3,35,882 | Rs 1,71,366 (1,202) | Rs 61,884 | -Rs 22,571 | 52% |
| exits P, C1 + Supertrend | Rs 2,56,207 (2,965) | Rs 3,67,589 | Rs 1,65,758 (1,163) | Rs 68,021 | -Rs 22,571 | 51% |
| exits Q, C1 alone | Rs 2,16,882 (3,070) | Rs 3,33,538 | Rs 1,68,944 (1,202) | Rs 63,444 | -Rs 22,571 | 52% |
| exits Q, C1 + Supertrend | Rs 2,55,473 (2,964) | Rs 3,64,028 | Rs 1,64,912 (1,163) | Rs 67,740 | -Rs 22,571 | 51% |

- **Design:** it helped (+Rs 39k, better in 12 of 17 months, design worst day -Rs 51,838 vs -Rs 58,613; random-skip p
  0.016).
- **Final:** it did not (-Rs 5.6k; better in only 2 of 7 months; drawdown Rs 6k larger; random-skip p 0.46). It
  skipped 79 C1 trades: 41 losers (Rs 52,634 saved) and 38 winners (Rs 67,365 missed, 3 of them +30% winners).
- **Why so little:** the two filters remove mostly the same trades. Of the 273 Donchian trades Supertrend skips in
  the final, Breadth Donchian already drops 212 (78%; design 504 of 659). Once breadth has filtered the breaks, little
  is left for Supertrend to remove. On calls it cut too much (final calls Rs 1,06,076 vs Rs 1,27,371); on puts it
  helped (Rs 59,682 vs Rs 43,995).
- **Verdict:** do not add Supertrend to the Breadth Donchian arm. Use one filter on Donchian, not both. Breadth is the
  stronger one (final Rs 1,71,366 vs Supertrend-on-Donchian Rs 1,34,743).

## The rule, if Boss wants it on paper (Boss decides; live unchanged)

**Donchian 20 with Supertrend agreement (T7):** on the index's 5-minute bars (09:15-anchored, one continuous series
across days, as the Donchian signal itself), compute Supertrend (ATR 10, Wilder smoothing; bands (high + low) / 2 ± 3 ×
ATR; the usual flip rule, as `research/hunt/r73/r73rules.py supertrend_dir`). When Donchian's 5-minute close breaks
above the 20-bar high, buy the call **only if Supertrend at that same bar's close is up**; when it breaks below the
20-bar low, buy the put **only if Supertrend is down**. Everything else stays exactly as today: 1-ITM of the nearest
weekly expiry, entry at the next minute's open, entries 09:20 - 15:10, one open a side, at most 2 a side a day, no
trades on the index's expiry day, stop -15%, the A4 ladder + L25, out after 30 minutes, square-off 15:15.

If switched on, it is a paper change: logged in the Autopilot change timeline, undoable in one tap, labelled so its
record stays separate from plain Donchian's for the 26 Oct live decision. It is not proposed together with Breadth
Donchian (see above): pick one of the two filters for the Donchian arm.

## Why we can believe T7 a little, and why only a little

- It passed every R51 gate (final net > 0, beats random entries, BH q 0.013 across the 36 tests, 1,670 trades, still
  +Rs 76,711 without its 5 best trades, design and walk-forward positive) and the extra random-skip test (q 0.018), and
  beat Donchian in design, walk-forward and final. At **twice the slippage** it still made Rs 86,825 in the final.
- It was better on all 5 indices in the final except MIDCPNIFTY (NIFTY +Rs 1,350 vs -Rs 16,160, BANKNIFTY Rs 41,043 vs
  Rs 17,913, FINNIFTY Rs 53,357 vs Rs 42,925, SENSEX Rs 34,341 vs Rs 30,054, MIDCPNIFTY Rs 4,652 vs Rs 7,114). Most of
  the gain is on puts (Rs 47,251 vs -Rs 6,034): it mostly stops Donchian buying puts while the 5-minute trend is up.
- It beat Donchian in 5 of the 7 final months but only 9 of the 17 design months; June 2026 was -Rs 54,587 for both.
  In design it still loses money without its 5 best trades (-Rs 17,892; Donchian -Rs 57,344), and its design drawdown
  is still Rs 2,14,239 (Donchian Rs 2,24,592). It is a modest improvement on a thin strategy, not a cure.

## Honest limits

- **The final period has been read many times** (R51, R52, R57, R64 - R66, R70 - R72 for Donchian, and twice for this
  exact plan; see the disclosure). Donchian itself was chosen with it in view. Only paper trading after 13 Oct is truly
  unseen data.
- Paper so far (not in the data): the Donchian paper arm is at 9 closed trades, -Rs 5,073; nothing here says how T7
  would have done on those.
- 36 tests were run; Benjamini-Hochberg was applied across all 36 (both the random-entry and the random-skip tests).
- Choices the plan left open, made on design rules only, before the final read: every indicator runs on the continuous
  5-minute series with warm-up from 1 Sep 2024 (as R44 / R71); a 15-minute / 1-hour bar counts as closed once its last
  5-minute bar has closed (or a later bar has appeared, if that one is missing), and an unchanged EMA is not "rising";
  T8 averages the 1-minute candles that have high, low and close; "above" is strict everywhere; the waits compare with
  the 20-bar channel edge that was broken and with the 5-minute signal close; C's picks under exits Q are the ones made
  under exits P (as the plan says); walk-forward picks for C have no minimum trade count (the plan sets none); the
  random-skip p uses R51's (1 + count) / 501; a "big winner" closed at +30% or more on the option's raw price; losers
  and winners are matched on index, day, side and signal bar. Added AFTER the final read, as description only (no new
  test): the "same breakouts" table above and the C1 + T7 extra.
- When a rule takes as many trades as Donchian (T1), the random-skip test cannot say anything (it skips 0 trades).
- The index has no volume, so T8 is a time-weighted stand-in for VWAP, not VWAP.
- Supertrend's numbers depend on the bars before today; the app must use the same continuous 5-minute series
  (yesterday's bars included) or its signals will differ.

## Files

`research/hunt/r73/` (PREREG.md, r73rules.py, r73study.py, r73report.py, r73entries.py, test_r73.py, results/*.json,
results/report.md); 37 entries in `research/experiments/entries/r73-*.yaml` (the 36 tests, failures included, and the
C1 extra).
