# R165: delta on the Donchian arms (option delta and order-flow delta)

12 Nov 2026. Research only: nothing in the app changes. Pre-registered in `../PREREG.md` (commit 0228377c6, pushed
before any number). Code and tests are in `research/hunt/r165_donchian_delta/`. Full tables are in `tables.md`, and the
raw p-values for one joint BH with R161–R164 are in `pvalues.csv`.

## For Boss, in short

- **No delta rule raised the win rate by 5 points with no loss of profit. All 78 tests failed.** Every one failed
  first on the walk-forward data (Apr 2025 – Mar 2026), so the verdict does not depend on the much-read Apr – Oct 2026
  period.
- **Today's 1-ITM option already has a delta of about 0.55** (half of all breaks fall between 0.50 and 0.57). It is close
  to the best choice:
  - Buying deeper in the money (delta 0.70 or 0.80) wins LESS (36–45%) and makes less, or loses money. These options are pricier, wider
    and often thinly traded.
  - Buying at delta 0.50 makes a little more money on R72 in the final period, but the win rate does not rise (46.4% vs
    45.8%). On the walk-forward data it made less.
- **Taking only "high-delta" breaks (0.65–0.80) won 54–57% on R72, but they are rare (39 trades in a year of
  walk-forward data, 100 in the final period) and made far less money.** On R51 they won 43% on the walk-forward data and
  52% in the final. It is not a usable rule.
- **A stop set in index points through delta (instead of −15% on the premium) changed almost nothing** (win rate within
  about half a point).
- **Adding delta, gamma, theta and IV to R151's picker did not help.** Kept to the same number of trades, it won the same
  or a little less than R151's picker on the walk-forward data, and made less.
- **Order-flow delta could not really be tested.** No buyer/seller (aggressor) history exists for Indian index options or
  futures, and the phone's recordings (5 days, 13–19 Oct) are sealed with your passphrase. I did not try to open them.
  - A rough stand-in (the option's minute volume, counted as buying when the price ticked up and selling when it ticked
    down) did nothing useful. It is weaker evidence.
  - Section 6 proposes a shadow log for the app to collect the real thing.
- **Nothing changes in the app.**

## 1. What was tested

Signal source for every rule: **the index** (R72: Breadth Donchian 20, 4 of 5 indices; R51: Donchian 20 kept by
Supertrend (10, 3), on index 5-minute bars). Delta only filters, picks the strike, sets the exit or feeds the picker.

| family | rules | what changes |
|---|---|---|
| A. delta band filter | DB1 0.45–0.55, DB2 0.55–0.65, DB3 0.65–0.80 | a break is taken only if the 1-ITM option's delta is in the band |
| B. strike by delta | SD50, SD60, SD70, SD80 | buy the strike nearest that delta instead of 1-ITM |
| C. delta-scaled exit | XI1 (replaces −15%), XI2 (added to −15%) | stop at the index move that costs 15% of the premium at the entry delta (0.15 × premium / delta points) |
| D. Greeks in the picker | PKD | R151's logistic picker + delta, gamma, theta, IV, IV vs the ATM option's IV, at R151's keep share |
| E. order-flow PROXY | OF1 (3-min), OF2 (15-min divergence), OF3 (in-trade exit) | the bought option's tick-rule signed volume (option data used as a filter / exit around the index signal) |

- **Delta:** Black-Scholes with **the option's own implied volatility, solved from its price** at the decision minute
  (the index close, the option's close, calendar time to 15:30 on expiry, r = 6.5%).
  - Checked against the lake's own IV column (Dhan) on about 400 trades per arm and period: correlation 0.975–0.988,
    median gap 0.8–1.0 vol points.
- **Costs:** dated Zerodha charges on the whole quantity and R17 slippage from the order's size, at 1, 2 and 3 lots.
- **Baseline (B0):** today's rules, which reproduce the published numbers exactly: R72 3,071 trades / Rs 2,16,929
  (design) and 1,202 / Rs 1,71,366 (final); R51 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743.
- **Second-look baseline:** R151's app picker (a skipped break still holds its side), final, which matches R152's
  app-style walk exactly: R72 603 trades / Rs 1,69,674, R51 653 / Rs 1,42,884 at 1 lot.

## 2. Results, 1 lot (all lots in `tables.md`)

Walk-forward = Apr 2025 – Mar 2026 (design data, quarterly folds). Final = 1 Apr – 13 Oct 2026, read once by R165 (read
by many earlier studies, so weak evidence). Gate: win rate +5 points in both, and net not lower.

**R72 Breadth Donchian** (B0: WF 2,238 trades, 49.0%, Rs 3,35,882; final 1,202, 45.8%, Rs 1,71,366)

| rule | WF trades | WF win | WF net | final trades | final win | final net | raw p | verdict |
|---|---|---|---|---|---|---|---|---|
| DB1 0.45–0.55 | 1,192 | 49.5% | Rs 1,86,179 | 790 | 45.7% | Rs 1,00,728 | 0.45 | fail |
| DB2 0.55–0.65 | 1,200 | 48.7% | Rs 2,02,847 | 612 | 47.2% | Rs 1,86,311 | 0.10 | fail |
| DB3 0.65–0.80 | 39 | 53.8% | Rs 11,187 | 100 | 57.0% | Rs 85,748 | 0.0005 | fail (WF +4.9, net far lower, q 0.12) |
| SD50 | 2,252 | 48.1% | Rs 3,19,579 | 1,226 | 46.4% | Rs 2,46,333 | 0.23 | fail |
| SD60 | 2,226 | 48.3% | Rs 3,51,039 | 1,220 | 45.2% | Rs 1,86,845 | 0.85 | fail |
| SD70 | 2,129 | 44.8% | Rs 2,20,716 | 1,165 | 41.6% | Rs 1,36,450 | 1.0 | fail |
| SD80 | 1,989 | 37.9% | -Rs 1,65,476 | 1,095 | 38.2% | Rs 28,077 | 1.0 | fail |
| XI1 | 2,236 | 49.1% | Rs 3,01,792 | 1,201 | 46.0% | Rs 1,75,122 | 0.98 | fail |
| XI2 | 2,238 | 48.5% | Rs 3,11,588 | 1,202 | 45.3% | Rs 1,62,256 | 1.0 | fail |
| PKD | 630 | 51.9% | Rs 1,71,401 | 650 | 49.7% | Rs 1,70,915 | 0.0005 | fail (WF +2.9, net lower, q 0.12) |
| OF1 proxy | 1,943 | 48.9% | Rs 2,47,229 | 1,033 | 45.0% | Rs 1,09,089 | 0.82 | fail |
| OF2 proxy | 2,081 | 48.7% | Rs 2,78,692 | 1,086 | 45.7% | Rs 1,19,932 | 0.56 | fail |
| OF3 proxy | 2,243 | 48.3% | Rs 3,27,107 | 1,203 | 44.7% | Rs 1,61,833 | 1.0 | fail |

**R51 Donchian 20 + Supertrend** (B0: WF 3,044 trades, 45.9%, Rs 2,13,748; final 1,670, 44.9%, Rs 1,34,743)

| rule | WF trades | WF win | WF net | final trades | final win | final net | raw p | verdict |
|---|---|---|---|---|---|---|---|---|
| DB1 0.45–0.55 | 1,713 | 46.4% | Rs 1,17,801 | 1,072 | 46.5% | Rs 1,47,405 | 0.0085 | fail |
| DB2 0.55–0.65 | 1,737 | 45.2% | Rs 1,24,894 | 949 | 45.3% | Rs 1,23,293 | 0.17 | fail |
| DB3 0.65–0.80 | 75 | 42.7% | -Rs 1,318 | 131 | 51.9% | Rs 77,896 | 0.0005 | fail (WF lower) |
| SD50 | 3,054 | 45.4% | Rs 1,77,371 | 1,689 | 45.5% | Rs 2,53,301 | 0.08 | fail |
| SD60 | 3,036 | 44.8% | Rs 1,37,513 | 1,686 | 43.4% | Rs 1,50,989 | 0.98 | fail |
| SD70 | 2,965 | 41.5% | -Rs 77,846 | 1,650 | 39.4% | Rs 57,397 | 1.0 | fail |
| SD80 | 2,874 | 36.5% | -Rs 5,14,509 | 1,599 | 36.2% | -Rs 80,218 | 1.0 | fail |
| XI1 | 3,043 | 46.1% | Rs 2,04,835 | 1,670 | 44.8% | Rs 1,25,959 | 1.0 | fail |
| XI2 | 3,044 | 45.5% | Rs 1,89,722 | 1,670 | 44.4% | Rs 1,28,422 | 1.0 | fail |
| PKD | 871 | 49.1% | Rs 1,98,701 | 643 | 51.5% | Rs 1,51,884 | 0.0005 | fail (WF +3.3, net lower, q 0.12) |
| OF1 proxy | 2,774 | 46.3% | Rs 2,35,951 | 1,520 | 44.9% | Rs 85,422 | 0.35 | fail |
| OF2 proxy | 2,899 | 46.0% | Rs 2,24,606 | 1,577 | 44.7% | Rs 78,543 | 0.54 | fail |
| OF3 proxy | 3,053 | 44.3% | Rs 1,82,837 | 1,673 | 43.8% | Rs 1,44,191 | 1.0 | fail |

- **2 and 3 lots tell the same story.** Win rates rise by about 1 point with lots, because the fixed brokerage is spread
  over more quantity, and they rise the same way for today's rules.
- **No rule passes at any lot size.** The R51 PKD at 3 lots is the nearest: in the final it won 52.7% vs 46.0% and made
  Rs 5,14,480 vs Rs 5,57,695 (R151's picker: Rs 4,88,424). On the walk-forward data it gained only 3.5 points and made
  less, so it fails there.

## 3. What delta says about today's trades (descriptive, 1 lot)

| R72, walk-forward | trades | win | net |
|---|---|---|---|
| delta 0.45–0.55 | 1,087 | 50.1% | Rs 1,69,856 |
| delta 0.55–0.65 | 1,096 | 47.9% | Rs 1,61,260 |
| delta 0.65–0.80 | 28 | 46.4% | -Rs 622 |

- 1-ITM almost always lands at delta 0.47–0.59. The two big buckets make about the same money a trade, so delta at
  entry carries little information about the outcome.
- The final period showed the opposite tilt (0.55–0.65 better on R72), another sign that it is noise.

## 4. Greeks in the picker vs R151's picker (walk-forward, same folds, same size, 1 lot)

| arm | R151 picker | + Greeks (PKD) |
|---|---|---|
| R72 | 634 trades, 52.5%, Rs 1,91,363 | 630 trades, 51.9%, Rs 1,71,401 |
| R51 | 852 trades, 49.1%, Rs 2,49,385 | 871 trades, 49.1%, Rs 1,98,701 |

The Greeks add nothing that R151's index and context features do not already carry. (PKD drops breaks before the walk,
as R151's research did; R151's app picker lets a skipped break hold its side. In the final, at 1 lot, PKD made Rs 1,70,915
on R72 and Rs 1,51,884 on R51 vs the app picker's Rs 1,69,674 and Rs 1,42,884, but PKD had already failed on the
walk-forward data.)

## 5. Statistics

- **Tests:** 13 rules × 2 arms × 3 lots = 78.
- **Twins:** 2,000 each (seed 165), on the final win rate:
  - filters: random skips of the same size;
  - strike rules: random strikes with delta 0.40–0.85;
  - exits: random exit minutes (R140).
- **BH:** m = 78 + 2,164 earlier (R160's m) = 2,242; earlier tests taken as p = 1.
  - The smallest raw p is 0.0005, the floor with 2,000 twins. It came from DB3 (both arms) and PKD (both arms).
  - BH q is 0.124 for each of those, above 0.10. Within R165 alone they would be q 0.004, but every one of them had
    already failed the walk-forward gates.
- `pvalues.csv` has every raw p, for one joint BH with R161–R164.

## 6. Order-flow delta: what exists, and a shadow log to collect it

- **Real aggressor data:** none in the lake.
  - The phone's recordings in the private IraAlgo repository are five days (13–19 Oct 2026), sealed with Boss's backup
    passphrase (AES-256-GCM). R165 stopped there.
  - Five days is also far too few: about 100 Donchian trades.
- **Index futures minutes:** only from 29 Jul 2026, so they cannot be designed on. On 29 Jul – 13 Oct, the futures'
  3-minute tick-rule delta in the trade's direction did not help (descriptive only):
  - R72: with the flow 220 trades, 45.5% won; against it 64 trades, 46.9%.
  - R51: with the flow 299 trades, 45.1%; against it 94 trades, 43.6%.
- **Proposed forward SHADOW log** (pre-registered here; nothing changes now, and it needs Boss's go-ahead to build). It
  logs only and never skips a trade.
  - **At every R72 / R51 break** (taken or skipped by the picker), for the index future (near month) and for the option
    the arm would buy, from Kite full-mode ticks:
    - buy and sell volume by the tick rule and by the quote rule (trade at the ask = buy, at the bid = sell, using the
      last best bid / ask);
    - cumulative delta over 1, 3 and 5 minutes;
    - best bid / ask, their sizes and the 5-level total bid / ask quantity;
    - the option's delta and IV (as R165 computes them);
    - whether the picker took the break.
  - **Every 5 seconds while the trade is open:** cumulative delta since entry (future and option), the option's bid /
    ask and LTP, and the premium's P&L.
  - **The outcome:** the arm's own exit and net.
  - **Off the tick path:** the values are summed in memory and written by the recorder's own thread, never on the order or
    tick path (OrderPathRound2Test stays 0). The position watch keeps priority (CLAUDE.md).
  - **Fixed count before any decision: 1,500 logged breaks** with complete records, about 3–4 months at today's rate.
    Reason: a filter that keeps half the breaks needs about 1,500 to see a 5-point win-rate gain at 80% power.
  - **One pre-registered test:** "skip a break when the 3-minute aggressor delta of the future AND of the option is
    against the trade". It must give win +5 points with net not lower than the picker, beat random skips, and pass BH
    with every earlier test counted.

## 7. Limits, honestly

- **Apr – Oct 2026 has been read by many studies (R51 – R160)** on these arms, so the final is a repeated look.
- **Some final breaks had no delta.** In the final, delta could not be computed for 285 of 2,367 R72 breaks and 487 of
  4,846 R51 breaks:
  - 199 / 359: the last days (30 Sep – 13 Oct), whose next expiry is after the lake's end. The store did not know the
    exchange calendar there.
  - 47 / 71: the strike was missing from the store.
  - 39 / 57: the price was below intrinsic value.

  The rules treated these breaks as today's rules, which dilutes them toward B0. Design had only 30 / 47 such breaks,
  and every rule failed on the walk-forward data anyway. A future delta study should use the exchange's expiry calendar.
- **Deep-ITM strikes are thinly printed in the lake.** 496 of R72's 2,674 SD80 design trades ended on a missing minute.
  This is part of why SD70 / SD80 lose. It is also what a real order would face.
- **The order-flow rules are a proxy** (tick rule on 1-minute option volume), not aggressor data. They are weaker
  evidence and reported separately.
- **R151's published picker numbers (652 / 693 trades) come from its research walk**, which removes skipped breaks
  before the walk. The app-style walk, where a skipped break holds its side, gives 603 / 653. R165 gates against the
  app-style one.
  - `final.json`'s `reproduced: false` flag compared against the research numbers by mistake. The code now checks
    against R152's app-style walk, which it matches exactly.

## 8. Files

- `../PREREG.md`: the pre-registration.
- `../r165lib.py`: Greeks, IV, rules, gates.
- `../r165study.py`: the phases.
- `../test_r165.py`: 43 tests, including the look-ahead guard.
- `design.json`, `design_r151.json`, `final.json`, `decision.json`, `tables.md`, `pvalues.csv`.
- `FINAL_READ`: the one final read.
- `research/experiments/entries/r165-*.yaml`: 78 entries, all rejected.
