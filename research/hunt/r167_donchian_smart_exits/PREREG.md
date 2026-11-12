# R167 pre-registration: "smart" exits for the Donchian arms, where the stop and target follow the market

Written 12 Nov 2026, **before any R167 number was computed**. This file is committed on
`claude/r167-donchian-smart-exits` before the code's first run on data. Research only: nothing in `android/**` changes.

Numbering: R166 was taken earlier today (`claude/r166-donchian-six`), so this study is R167. The siblings run today are
R161 (entries), R162 (trade structure), R163 (ML picker), R164 (order flow), R165 (delta) and R166 (six checks). R167
writes its raw p-values to `results/raw_pvalues.csv` so they can join one joint BH step with the siblings.

Boss, 12 Nov: *"make the R51/R72 smart, so if the data like volume, support and resistance or any other data changes,
the stop-loss and target should change accordingly."*

**Signal source for every rule: the index** (the CLAUDE.md default). The arms' entries are unchanged:
- R72: 5-minute Donchian 20 breaks on 4 of 5 indices;
- R51: 5-minute Donchian 20 breaks kept by Supertrend (10, 3).

Only the bought option's exit changes. The exit rules read:
- the index (levels, ATR, momentum, regime);
- the held option's own premium (rule VOLP's stop, plus every rule's "in profit" check);
- the held option's volume and OI (rule FLOW).

CLAUDE.md allows stops and targets on the premium, and option-chain data (OI, volume) as context around an index signal.
No rule's entry or direction is read from an option's chart.

## 0. Safety split (CLAUDE.md: on LIVE, automatic changes may only LOWER risk)

Every rule comes in two versions:

- **T (tighten-only).** All of today's exits stay exactly as they are. The rule can only add an earlier exit: a stop
  that only moves toward profit, an earlier sale, or a target that only sells a trade that is in profit. A T trade's exit
  row is therefore never later than today's. **Only T versions could ever go live.** Even a passing T version would start
  in shadow (logged only), behind a switch that is off by default. Boss would decide after the shadow phase.
- **F (free).** The rule may also **widen** a stop or **extend** a trade: it replaces today's -15% premium stop and/or
  the 30-minute time stop, as listed per rule below. **F versions are research / paper only** and are labelled
  "free (may widen): paper / research only" everywhere.

Nothing changes in the app from R167.

## 1. Arms, engine, B0 (R152's, unchanged)

R152's stack (`research/hunt/r152_donchian_2k/`):
- R51 Amendment A clean stores; R101 / R112 units; signals 09:20-15:10; never on the index's expiry day;
- R106's walk (one open position a side, at most 2 fresh fills a side a day). The walk is re-run for every rule,
  because a different exit frees the side at a different minute;
- R106 / R64's held-contract simulator: next-minute fill, stops on the minute low, gaps at the open;
- R17 fills (slippage from the 5-minute volume of the position);
- dated Zerodha charges on the whole quantity.

**B0 = today's exits (XL):**
- -15% premium stop;
- ladder + L25: peak +25% locks +5%; +30% locks breakeven; +60% locks +30%; +100% locks +60%;
- 30-minute time stop;
- 15:15 square-off;
- strike 1-ITM from the minute before entry.

B0 is priced at 1, 2 and 3 lots. At 1 lot it must reproduce R152's published numbers (asserted):
- R72: design 3,071 trades / Rs 2,16,929; final 1,202 / Rs 1,71,366;
- R51: design 4,208 / Rs 50,425; final 1,670 / Rs 1,34,743.

If the rebuilt data cannot reproduce them exactly, no R167 number is gated: the gap is reported and the study stops
there.

**How a rule exits.** Every rule decision is made at the **close of minute m**, using only data up to and including
minute m. The option is then sold at the **open of minute m + 1**: a market sale, with R17's normal sale slippage. This
is R106's forced-exit row `fx`. So nothing looks ahead, and no rule ever assumes a resting order filled inside a bar.
- **T version:** the simulator runs with all of B0's exits and `fx` = the rule's first exit row.
- **F version:** the simulator runs with B0's exits minus the parts the rule replaces (listed per rule). `fx` is the
  rule's own first exit row, which can come after B0's exit.

## 2. Shared measurements (fixed now)

- f = the trade's fill row (the simulator's fill minute); s = +1 for a call, -1 for a put.
- **ATR5(m):** Wilder ATR(14) of the index's 09:15-anchored 5-minute bars that have closed by minute m. A bar is closed
  once its last row is <= m. The ATR carries across days, warmed from 1 Sep 2024 as R51's stores are.
- **b(m) = 0.10 x ATR5(m)**, the buffer used for "just before" / "just beyond" a level.
- **Levels L(m):** R150 Amendment B's 5-minute support / resistance map (`research/hunt/r150_sr_levels/levels.py`):
  - pivots on 5-minute index bars, 6 bars each side, known 6 bars after the pivot's bar;
  - grouped within 0.15%;
  - the last 20 sessions (L20);
  - **3+ touches** only;
  - pivots confirmed by the last 5-minute bar closed by minute m.
- **Swing low / high:** a 5-minute index bar whose low is <= the lows of the 2 bars on each side (mirror for highs). It
  is known 2 bars after its own bar closes.
- **MINHOLD:** the momentum, flow and ML signals may fire only from minute m >= f + 2, so the first such exit is at
  f + 3. Stops and targets have no minimum hold.
- "In profit" = the option's close at minute m is above the raw fill price.

## 3. The seven rules (each in a T and an F version)

### SR: support / resistance aware
- **Stop S (call; mirror for puts).** At the fill, S = max(the highest level of L(f) below the index close, the latest
  known swing low below it) - b. If neither exists, S = the index close at f - 2 x ATR5(f).
  - Each minute: candidate = max(the highest level of L(m) below close(m), the latest known swing low below close(m))
    - b, and S = max(S, candidate). S only ratchets toward profit, in both versions. As the index breaks through
    levels, the stop climbs to just beyond the last broken level, or to the latest higher low.
  - Exit when close(m) < S.
- **Target R (call; mirror for puts).** R = the lowest level of L(m) above close(m). It is re-read every minute, so after
  a breakout the next level becomes the target.
  - Exit when high(m) >= R - b, AND close(m) <= R + b (it reached the level but did not close through it), AND the trade
    is in profit.
  - A close beyond R + b is a breakout. The trade stays open: R is now below the price, so the stop can ratchet up to it,
    and the next level up becomes the target. This is how "the rest trails if it breaks through" works.
- **SR-T:** S and R are added to all of today's exits.
- **SR-F:** S replaces the -15% stop, and the 30-minute time stop is removed. The ladder + L25 and 15:15 stay.

### VOLX: volatility-scaled on the index (Chandelier)
- Peak P(m) = the highest index high from f to m (call; lowest low for a put).
- Chandelier stop = P(m) - 3.0 x ATR5(m).
  - T: it ratchets (never lowered).
  - F: it is recomputed each minute, so it widens when volatility rises.
- Target: s x (close(m) - close(f)) >= 4.0 x ATR5(m), and the trade is in profit.
- Exit at the first stop or target hit (stop: close(m) beyond the Chandelier level).
- **VOLX-T:** added to today's exits.
- **VOLX-F:** replaces the -15% stop and the 30-minute time stop.

### VOLP: volatility-scaled on the option's own range (premium Chandelier)
- Q(m) = the highest option close from f to m.
- ATR1o(m) = Wilder ATR(14) of the held contract's own 1-minute bars that day up to m. It needs at least 5 bars;
  otherwise the rule does not act yet.
- Stop = Q(m) - 3.0 x ATR1o(m). T: it ratchets. F: it is recomputed each minute. Exit when the option's close(m) < stop.
- No target. A premium stop (allowed by CLAUDE.md); the signal is still the index break.
- **VOLP-T:** added to today's exits.
- **VOLP-F:** replaces the -15% stop and the 30-minute time stop.

### FLOW: volume / OI aware
- **Volume spike:** the held option's volume v(m) >= 3 x the median of v over the previous 20 minutes (at least 10 of
  those minutes have a print), and v(m) > 0.
- **Against the trade (tighten):** either of these, from m >= f + 2:
  - a spike on a minute whose index bar moved against the trade: s x (close - open) < 0;
  - the held contract's OI up >= 2% over 5 minutes while its premium fell over the same 5 minutes (a short build-up on
    the bought option).
- **The tightened trail, from the first against event:**
  - trail = the lowest index low of minutes m-2 .. m (call; highest high for a put);
  - each later minute, trail = max(trail, the lowest low of the last 3 minutes) (mirror for a put);
  - exit when a later close(m) < trail.
- **Confirming (F only):** a spike on a minute whose index bar moved with the trade. Each confirming spike since the fill
  extends the time stop by 15 minutes, to at most 60 minutes. The time exit is a sale at the open of the minute after
  the extended time stop's last minute.
- **FLOW-T:** the tighten part is added to today's exits.
- **FLOW-F:** the tighten part, plus the extended time stop in place of the 30-minute one. The -15% stop stays.
- OI comes from the lake's option minutes (`oi`). A minute without OI cannot raise an OI flag.

### MOM: momentum fade
- Supertrend (10, 3) on the index's 1-minute bars (Wilder ATR 10, carried across days).
- Exit at the first minute m >= f + 2 at which its direction is against the trade.
- **MOM-T:** added to today's exits.
- **MOM-F:** the 30-minute time stop is removed (the trade is held while momentum agrees). The -15% stop, ladder + L25
  and 15:15 stay.

### CTX: context switch (exit settings by regime, chosen on design only)
- **Regime at minute m:** VIX high / low × trend / range day, which gives 4 regimes.
  - VIX: India VIX's 1-minute close at m >= the median of India VIX daily closes over the training days.
  - Trend: the Kaufman efficiency ratio of the index's 1-minute closes from 09:15 to m is >= 0.30. Before 15 minutes of
    the day have printed, the day counts as "range".
- **Choice per regime (training trades only, at 1 lot, assigned by their fill regime):** the menu item with the highest
  training net among those whose training win rate is >= B0's on the same trades. Ties, or no item qualifying, go to B0.
  - T menu: {B0, SR-T, VOLX-T, VOLP-T, FLOW-T, MOM-T}.
  - F menu: {B0, SR-F, VOLX-F, VOLP-F, FLOW-F, MOM-F}.
- **CTX-T:** the regime is updated every minute. The trade exits at the first minute at which the rule chosen for the
  current regime fires. Each menu rule's state (ratchets, peaks) runs from the fill, whichever regime is active.
- **CTX-F:** the regime is read at the fill only (the F menu items replace different parts of today's exits, so they
  cannot be switched mid-trade). The trade is priced by the chosen item.
- **Training windows:**
  - each walk-forward quarter is chosen on the design days before it (the first quarter on 20 Nov 2024 - 31 Mar 2025);
  - the final period is chosen on all design days;
  - CTX's design-period numbers are in-sample and labelled so.
- The same choice is used at 2 and 3 lots.

### ML: exit model (walk-forward trained)
- **Features at minute m (all known at m):**
  - side; minutes held;
  - the option's return since fill, its peak return, and its drawdown from the peak;
  - the signed index move since fill in ATR5 units;
  - distance to the nearest level ahead and behind in ATR5 units (capped at 5; 5 if none);
  - ATR5(m) as bps of the index;
  - the signed 3-minute index rate of change;
  - the signed 1-minute Supertrend direction;
  - log(1 + v(m) / its 20-minute median);
  - the held contract's 5-minute OI change in %;
  - the India VIX level, and its change since 09:15;
  - minute of day.
  - A missing feature is set to its training median.
- **Label:** from m + 1 to the 15:15 square-off, does the index move >= 1.0 x ATR5(m) in the trade's favour before it
  moves >= 1.0 x ATR5(m) against it? This uses 1-minute highs and lows; if both happen in the same minute, the adverse
  move counts as first. Neither = 0. Labels are used only for training rows.
- **Model:** logistic regression (scikit-learn, L2, C = 1.0, standardised features).
- **Training rows:** every minute m in [f + 2, min(f + 60, 15:14)] of every B0 trade (1 lot) on the training days.
- **Threshold θ:** the 25th percentile of the in-sample predicted probabilities on the training rows.
- **ML-T:** sell at the first m >= f + 2 with P(m) < θ, added to today's exits.
- **ML-F:** the same exit, and the 30-minute time stop is removed. The -15% stop, ladder + L25 and 15:15 stay.
- **Training windows:** as CTX (refit for each walk-forward quarter on the design days before it; the final model on all
  design days). Design-period numbers are in-sample and labelled so. One model per arm.

**Tests:** 7 rules x 2 versions x 2 arms x 3 lots = **84**.

Nothing is fitted by hand. The constants are fixed now from common practice and earlier studies: 0.10 ATR buffer;
3.0 / 4.0 ATR (Chandelier's 3 ATR); 3x volume spike; 2% OI; 15 / 60 minutes; Supertrend (10, 3) (the arms' own);
VIX median; efficiency ratio 0.30; the 25th percentile; 1 ATR; R150's level settings. Only CTX's menu pick and ML's
model / threshold are learned, and only on design days before each scored window.

## 4. Periods

As R152:
- **Design:** 20 Nov 2024 - 31 Mar 2026, with the walk-forward window Apr 2025 - Mar 2026 and its four quarters.
- **Final:** 1 Apr - 13 Oct 2026, read ONCE by R167 (`results/FINAL_READ`, written before the read; a second read is
  refused).

**Honesty note:** Apr - Oct 2026 has already been read by many studies (R51 - R166), including R150's levels and R162's
exits on these same arms. It is weak evidence, not a clean holdout. The post window (14 - 19 Oct) is not used.

## 5. What is reported (every arm x rule x version x lots, B0 alongside, per period)

- trades; win %; net and its change vs B0;
- average win and loss; worst day; max drawdown (daily); exit mix;
- the share of trades whose exit moved, and in which direction (earlier / later);
- twin p (net and win); BH q.

## 6. Twins (final period, 500 per test, seed 167; R162's design)

For every trade whose exit row differs from B0's at the same lots, the twin sells at the open of a uniformly random
minute:
- **rule exit earlier:** a minute strictly between the fill and B0's exit, with B0's exits active before it;
- **rule exit later (F only):** a minute strictly between B0's exit and the rule's exit, with the rule's exits active
  before it.

Two p values, each p = (1 + twins >= the rule) / 501:
- one on total net;
- one on win rate.

**The test's p is the larger of the two** (the rule must beat random exits on both: intersection-union).

## 7. Gates (all must hold; Boss's success test, 12 Nov)

1. Win rate up **>= 5 points** over B0 at the same lots, in the walk-forward window AND the final.
2. Net **not lower** than B0's at the same lots, in the walk-forward window AND the final.
3. Net > 0 in design, the walk-forward window and the final.
4. **Twin p's BH q <= 0.10** with **m = 2,646**: R167's 84 plus every earlier test taken as p = 1. The earlier tests are
   R160's m 2,164 + R161 168 + R162 102 + R163 8 + R164 18 + R165 78 + R166 24. q within R167 alone is also shown. Raw
   p-values go to `results/raw_pvalues.csv`.
5. At least 40 final trades.

Worst day and max drawdown are reported. Lower than B0's is a plus, but it is not a gate.

A passing **T** rule is labelled "live-eligible after a shadow phase". A passing **F** rule is labelled "paper /
research only".

**Second look (descriptive, never gated): R151's pickers.** On Apr - Oct 2026, every rule is priced at 1-3 lots on the
trades the app's pickers take. This uses R152's `r152picker.py`: a skipped break still holds its side and counts in the
caps. "Win up 5 points and net not lower than the picker's B0" is shown.

## 8. Data

The same lake as R152:
- `index_1m` for the five indices and India VIX;
- `index_daily`;
- `options_rolling_1m` WEEK, ATM-10..+10, with `oi`;
- the R51 Amendment A clean stores built from it.

OI is read from the same option files, for the held contract only. No raw data goes into git.

**At the time of writing, the lake is not in this session's container:**
- the Drive copy needs rclone credentials this container does not have;
- the GitHub data release was never published;
- the Dhan access token in the environment has expired (HTTP 401 DH-901).

The code and tests are written now. The study runs once the lake is in place, and B0 parity (section 1) decides whether
a rebuilt lake is usable. If no data can be had, R167 is reported with no numbers and every test is logged as
`not-run (data unavailable)`.

## 9. Tests (pytest) and the look-ahead guard

Every pure function has a unit test with edge cases: empty days, missing minutes, no levels, a gap through a stop, an
expiry day, a trade at the square-off, stale (NaN) prints, ATR warm-up, OI missing.

**Look-ahead guard:** for every rule, its decision at minute m is unchanged when every array is cut after minute m. A
synthetic day is run whole and cut at every minute; the exit rows must agree. A T version's exit row is also checked to
be <= B0's on random synthetic days.
