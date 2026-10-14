# R27: hunting NIFTY's zero-to-hero with everything we have

Written 14 Oct 2026, for Boss: "take nifty data, use the brain framework, use internet, and all the resources you can, and
find how can we catch the zero to hero". Mid-way Boss added a simple "expiry day special" rule (the 14:30-14:45 range
breakout), which is tested here too.

- **Pre-registration:** `research/hunt/r27/PREREG.md`, commit `0b2b88b`, pushed before any R27 outcome was computed.
- **Code, tests and tables:** `research/hunt/r27/` (`results/report.md`, `results/final.json`). The engine is R25's / R26's
  (fills, charges); fair values and greeks come from the brain's `research/brain/bs.py`.
- **Experiment entries:** `research/experiments/entries/r27-*.yaml` (11 rules, all rejected, plus the descriptive table).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **No rule catches zero-to-hero profitably and reliably. 0 of 11 pass.** That makes 0 of 39 across R21, R25, R26 and
  R27. The smallest BH q in the 39-test family is 0.078.
- **But R27 found the first real clue in the whole hero series:** *how expensive the option is compared with how much
  the index is actually moving.*
  - We forecast the volatility left to 15:30 from the last hour's moves and from how earlier expiry afternoons behaved.
    Then we compare it with the volatility the option's price implies (`iv_rv`).
  - Over 92,289 decision rows on 271 NIFTY expiries, a cheap option priced **2× richer** than the forecast loses
    **−Rs 778 per Rs 1,000** held to 15:05. One priced **at or below** the forecast loses only **−Rs 110 to −Rs 168**.
  - At Rs 15-30, the fairly priced ones (`iv_rv` 0.8-1.0) are about break-even: −Rs 8 and +Rs 38 per Rs 1,000, on 1,182
    and 785 rows. At Rs 10-15, only the 0.9-1.0 bucket is (+Rs 24, 792 rows).
  - This is what the research literature predicts. Expiry options carry a large "volatility premium"; buyers lose most
    when they pay the most for it. Every bucket still loses or breaks even on average.
- **F1, the rule built on that clue, is the best hero rule we have ever tested, and still not good enough.**
  - What it does: the first time an option's `iv_rv` drops to θ or less, buy the cheapest one by that measure. θ is
    chosen each year from earlier years only.
  - Walk-forward 2023-2026 (197 expiries): **154 trades, +Rs 23,692 after all costs** (+Rs 92 per Rs 1,000).
  - It caught 4 real 10×+ days:
    - 7 Sep 2023, 19650 CE at Rs 6 → 17×, +Rs 23,801;
    - 22 Feb 2024, 22000 CE at Rs 15.55 → 13.5×, +Rs 18,927;
    - 8 May 2025, 24400 PE → 10.9×, +Rs 13,631;
    - 2 Sep 2025, 24700 PE → 11.6×, +Rs 8,531.
  - Its third-best trade, 12 Sep 2024 (25250 CE, +Rs 14,242), peaked at 9.5×.
  - But it is still a lottery:
    - 75% of trades lose;
    - without its best day it is −Rs 109;
    - 2026 is −Rs 9,634 on 29 trades;
    - random entries on the same days are worse on average (−Rs 1.09 lakh), but not by enough: p 0.058, BH q 0.57;
    - max drawdown −Rs 24,022, and a 14-trade losing streak.
  - It fails gates 4 (significance) and 6 (positive without the best day).
- **The ML ranker did not help.** Gradient boosting on 45 features lost −Rs 73k (M1, 167 trades) and −Rs 72k (M2, with
  Boss's 2× step lock). With so few jackpots, the model learns noise.
- **Momentum, reversal and dealer-gamma rules lost** (Q1 −Rs 13k, Q2 −Rs 20k, G1 −Rs 6k; 35-48 trades each).
- **Boss's late range breakout loses heavily** on all 271 NIFTY expiries:

  | version | trades | net Rs | trades that won |
  |---|---|---|---|
  | 14:30-14:45 range, hold to 15:25 | 194 | −1,17,776 | 9.8% |
  | the same with the app's 15:05 exit | 194 | −91,160 | 14.4% |
  | 14:45-15:00 range | 226 | −2,32,804 | 2.7% |
  | 15:00-15:15 range | 104 | −1,64,852 | 1.0% |
  | all three windows with a stop | 524 | −5,06,899 | 4.8% |

  - It does catch real jackpots. 24 Nov 2022: a Rs 0.95 call ran 37× (+Rs 69.7k). 16 Nov 2023: a Rs 1.10 put ran 57×.
  - But a break after 14:45 usually buys a Rs 1-5 option with minutes left, and 90-99% of those die.
- **What to do.**
  - No app change, nothing live, and no paper arm: nothing passed.
  - One idea is worth carrying forward, **in shadow only**: log `iv_rv` (implied vs forecast realized vol) for NIFTY's
    cheap options on every expiry afternoon, and log what F1 would have bought. It is the only hero signal with a clear,
    consistent relationship to the loss across 92k rows. A forward record that is truly unseen is the only honest way
    left to test it.
  - Practical rule for Boss even without a system: on expiry afternoons, avoid buying cheap options when they are
    expensive compared with how much NIFTY is actually moving (big premium, quiet market). That is where most of the
    lottery money is lost.

## Data and method

- **NIFTY weekly expiries from the Dhan lake: 271, 5 Aug 2021 – 6 Oct 2026** (R26's day files; R25's loader).
  - Decision rows: every 5 minutes from 12:00 to 14:55, every eligible OTM option at Rs 1-30. That gives **92,289 rows**
    with outcomes. The rest had no fill.
  - Labels use R26's engine at Rs 2,000 a trade, with real charges and the spread model. Exits: hold to 15:05 (E1), or
    half at 2× plus step lock (E3).
- **No unseen holdout exists.** R25 and R26 read every NIFTY expiry. So:
  - every rule with a choice in it runs an anchored walk-forward: test years 2023, 2024, 2025 and 2026, each fitted on
    earlier expiries only (197 test expiries);
  - Boss's K rules have nothing to fit, so they run on all 271 expiries.
  - The new feature families (fair value, gamma, rest-of-day momentum) had never been looked at against outcomes before
    this run.
- **Features** (45, point-in-time; look-ahead guard tests pass):
  - R25's 28 anatomy features, plus:
    - **VOL:** realized vol (day, 60 and 15 minutes); a forecast for the time left, using the last 60 minutes × a
      seasonal ratio taken from earlier expiries only; our own Black-Scholes IV (brain `bs.py`); `iv_rv`; price ÷ fair
      value at the forecast and at VIX.
    - **GAMMA:** OI × gamma per strike, the pin strike, the distance to the pin, the call-minus-put gamma share.
    - **MOMENTUM:** first-30-minute return, return since 14:00, return since the previous close.
- **Web review** (14 Oct; a summary is in the honest notes below). It points to the same place:
  - expiry options carry the largest volatility premium;
  - buyers lose on average (SEBI FY22-24: 93% of F&O traders lost; Agarwal et al. 2025 on NSE retail: OTM buyers lose
    most);
  - the only conditional lead is to buy when the chain underprices the remaining move. That is F1, and it is the one
    rule here with a positive walk-forward.

## Results (`results/report.md` has everything)

| rule | trades | hit % | ≥ 10× % | net Rs | per Rs 1,000 | worst day | max DD | net w/o best day | 2× spread | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M1 ML ranker, hold | 167 | 13.2 | 3.0 | −73,266 | −260 | −2,059 | −1,15,475 | −97,066 | −75,694 | 0.182 | 1.00 |
| M2 ML ranker, 2× + lock | 141 | 29.1 | 3.5 | −72,304 | −296 | −2,164 | −72,304 | −77,635 | −74,801 | 0.002* | 0.078 |
| **F1 fair value** | **154** | 25.3 | 2.6 | **+23,692** | **+92** | −2,115 | −24,022 | **−109** | +20,863 | 0.058 | 0.57 |
| G1 gamma + momentum | 48 | 43.8 | 0 | −6,273 | −87 | −2,074 | −13,696 | −8,972 | −8,704 | 0.433 | 1.00 |
| Q1 momentum at 14:30 | 35 | 25.7 | 0 | −13,175 | −246 | −1,859 | −15,185 | −17,575 | −13,442 | 0.272 | 1.00 |
| Q2 reversal at 14:30 | 39 | 20.5 | 2.6 | −20,441 | −340 | −1,980 | −23,381 | −24,414 | −20,726 | 0.755 | 1.00 |
| K1 Boss 14:30-14:45 | 194 | 9.8 | 3.1 | −1,17,776 | −319 | −2,028 | −1,27,819 | −1,47,833 | −1,26,307 | 0.711 | 1.00 |
| K2 14:45-15:00 | 226 | 2.7 | 1.3 | −2,32,804 | −521 | −2,024 | −2,32,804 | −3,02,505 | −2,57,321 | 0.033* | 0.43 |
| K3 15:00-15:15 | 104 | 1.0 | 0 | −1,64,852 | −785 | −2,024 | −1,64,852 | −1,71,287 | −1,94,451 | 0.555 | 1.00 |
| K1e K1 with 15:05 exit | 194 | 14.4 | 1.5 | −91,160 | −247 | −1,948 | −95,638 | −1,31,924 | −1,06,548 | 0.954 | 1.00 |
| KS all windows + stop | 524 | 4.8 | 1.7 | −5,06,899 | −494 | −6,068 | −5,06,899 | −5,75,052 | −5,74,259 | 0.137 | 1.00 |

\* A small p with a loss means random entries on the same days lost even more. It is not an edge.

- **F1 by year** (trades, net Rs):

  | 2023 | 2024 | 2025 | 2026 |
  |---|---|---|---|
  | 28, +200 | 45, +4,412 | 52, +28,714 | 29, −9,634 |

- **F1 by price band:** Rs 1-10 had 35 trades and −Rs 4,473; Rs 10-15 had 28 trades and +Rs 6,486; Rs 15-30 had 91
  trades and +Rs 21,679.
- **F1's walk-forward choices:** θ 0.85 for 2023-24 and 1.0 for 2025-26, always holding to 15:05.
- **K1 by R25 block:** 2021-22 had 54 trades and −Rs 40,966; 2023-26 had 140 trades and −Rs 76,810.

**Every decision row: mean net per Rs 1,000 held to 15:05, by price band and `iv_rv`:**

| iv_rv | Rs 1-10 | Rs 10-15 | Rs 15-30 |
|---|---|---|---|
| ≤ 0.8 | −168 (1,654) | −219 (605) | −165 (828) |
| 0.8-0.9 | −110 (1,762) | −106 (558) | +38 (785) |
| 0.9-1.0 | −165 (2,850) | +24 (792) | −8 (1,182) |
| 1.0-1.2 | −294 (9,007) | −152 (1,940) | −81 (2,757) |
| 1.2-1.5 | −499 (15,907) | −361 (2,248) | −312 (3,134) |
| 1.5-2.0 | −654 (19,474) | −312 (1,526) | −282 (1,953) |
| > 2.0 | −778 (22,081) | −552 (510) | −405 (736) |

- All rows average −Rs 495 per Rs 1,000 (E1) and −Rs 480 (E3). Rows are counted in brackets.
- Most cheap options sit in the rich buckets: 57,462 of the Rs 1-10 rows have `iv_rv` above 1.2.
- The very cheapest by this measure (≤ 0.8) are not the best. The ratio is noisy when the last hour was unusually quiet.

## Forward test plan (shadow only; a description, no app change)

The phone already records NIFTY's ATM±10 chain every minute (`LiveRecord`) and ATM±3 every second (`TickZone`). A
shadow logger (log only, behind a switch that is off by default, never on the order or tick path) would work like this:

1. **Every 5 minutes, 12:00-14:55 on NIFTY expiry days,** compute for each OTM option at Rs 1-30:
   - realized vol over the last 60 minutes;
   - the seasonal ratio, from the research table of earlier expiries, shipped as a small constant table;
   - our Black-Scholes IV, and `iv_rv`.
2. **Log F1's pick:** the first time an option's `iv_rv` drops to 1.0 or less, buy the option with the lowest one. Log
   it with its price.
3. **Replay after the close** with R26's engine and real costs. Add each day to a forward table.
4. **Pre-registered look:** after 60 shadow trades (about a year of NIFTY weeklies), with the same gates as here. That
   record will be the first truly unseen test of the only hero signal that has shown anything.

## Honest notes

- **No virgin holdout.** The walk-forward is clean in its choices (each year fitted on earlier years). But the
  researchers had seen these expiries in R25 and R26. The new feature families had not been checked against outcomes.
  F1's p of 0.058 does not survive BH over 39 tests. So it is a lead, not a finding.
- **Lottery dependence remains.** F1's top 5 trades make +Rs 81k; the other 149 lose about −Rs 58k.
- **A fix during the run.** R25's entry routine stops working orders at 15:05. The K rules enter up to 15:21, so at first
  K3 had 0 trades and K2 only breaks before 15:04. The entry now runs to 15:25 for the K rules only. This was found from
  the trade count and fixed before the K results were read. Both runs lost.
- **The K rules' random twins** use every break signal, including 1 K1 day whose order never filled (195 signals,
  194 trades). That makes the twins very slightly more negative; it changes no verdict.
- **The K rules' 15:25 exit is research only.** The app squares off expiry positions at 15:05. Since 3 Aug 2026 the
  closing auction settles index options; the data has about 10 such expiries.
- **The `iv_rv` forecast is simple:** the last hour's variance × an expiry-afternoon seasonal factor. A better forecast
  (HAR, event days) could sharpen it. That would be a new pre-registered study.
- **The spread model** is R25's (no bid / ask in the data). F1 stays positive at 2× spread (+Rs 20,863).
- **Web sources** (from the review; some only from abstracts):
  - SEBI F&O study (Sep 2024);
  - Agarwal, Ghosh, Prabhala, Zhao, "Animal Spirits on Steroids" (SSRN 5430635);
  - Vilkov, "0DTE Trading Rules" (SSRN 4641356);
  - Dim, Eraker, Vilkov (SSRN 4692190);
  - Baltussen, Da, Lammers, Martens, JFE 2021 (intraday momentum);
  - Gao, Han, Li, Zhou, JFE 2018;
  - Barbon & Buraschi, "Gamma Fragility";
  - an unreviewed Indian expiry-effects replication (github.com/vicky123411/india-options-expiry-effects).

## Checks run

- `python -m pytest research/hunt/r27/test_r27.py -q` passes 13 tests. They cover:
  - the look-ahead guard for every feature (synthetic, plus 2 real NIFTY days with the future set to absurd values);
  - the seasonal ratio reading earlier days only, with a fallback for flat stretches;
  - the fair-value, gamma-pin and momentum features;
  - empty and closed days;
  - the rule picker (first qualifying minute, best score) and the 14:30 momentum and reversal rules;
  - the fallback to the strictest choice;
  - Boss's range, the first break, no future read, window 3 exiting after the fill, and the 15:05 version;
  - the K rules leaving R25's 15:05 entry limit untouched (found by the independent review; fixed).
- R26's and R25's tests still pass. Brain checks: `python -m pytest research/brain/tests -q` and
  `python -m research.brain.features catalog --check`.
- `python research/experiments/validate.py`, and `python3 research/knowledge/build_pack.py` then `--check`.
