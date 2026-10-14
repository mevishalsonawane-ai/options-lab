# R37: the gamma-flip "gamma squeeze" hero trigger, on NIFTY and SENSEX expiries

Renumbered 14 Oct from R30 to R37 (the number was taken).

Written 14 Oct 2026, for Boss's gamma-exposure blueprint.
- **Measure:** net GEX = Σ (call OI − put OI) × Black-Scholes gamma, at 13:45. The gamma flip is the spot level where
  it changes sign.
- **Entry:** 14:00-14:45, spot breaking the flip in that minute, the index on the right side of its TWAP, the option's
  volume at least 2× its 10-bar EMA, and HDFC Bank, Reliance and ICICI Bank all green (calls) or all red (puts).
- **Option and size:** the nearest OTM option, Rs 2,000 in whole lots, skipping the trade below 1 lot.
- **Exit:** breakeven lock at 2×, then a lock at 50% of each doubling level, else 15:05.

- **Pre-registration:** `research/hunt/r37/PREREG.md`, commit `8ee385a`, pushed before any trigger or outcome was
  computed.
- **Code, tests and tables:** `research/hunt/r37/` (`results/results.json`).
- **Experiment entries:** `research/experiments/entries/r37-*.yaml` (4 rules, all rejected).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **The gamma-flip trigger loses, and timing it at the flip is worse than random. 0 of 4 pass.** That makes 0 of 52
  hero rules across R21-R26 and R34-R37.
  - **The rule as specified (G1):** 42 trades on 446 expiries, **−Rs 40,003** after all costs (−Rs 550 per Rs 1,000).
    - Only 9.5% of trades won. 7.1% reached 4× and none reached 10×.
    - Its worst losing run was 26 trades in a row. Its max drawdown, −Rs 41,806, is past Boss's −Rs 40,000 stop.
  - **Random entry minutes** on the same days, same direction and same exit average −Rs 16,571. G1 is worse than 99.4%
    of them.
  - **Only crossing into negative gamma (G2):** 34 trades, −Rs 34,878.
  - **Held to 15:05 (G3):** −Rs 45,921.
  - **Without the heavyweights (G4):** 65 trades, −Rs 37,924. Its best day (+Rs 18,522) is the only reason it isn't
    worse.
- **Why it fails.** The flip almost always sits close to spot: call OI dominates above the money and put OI below. So
  spot crosses it on most afternoons, 158 of 271 NIFTY expiries in the window. A crossing is an ordinary event, not a
  "squeeze". By the time the volume spike and the green heavyweight candles line up in the same minute, the option has
  already jumped. That is the same chase cost as R35's Supertrends.
- **What to do:** no app change, nothing on paper, nothing live. Across R26 and R34-R37 the lesson is the same: minute-level
  triggers (walls, Supertrends, flips, breakouts) buy after the burst. The only lead left is R34's "when not to buy":
  options priced dear against the move lose the most.

## Method (PREREG.md has every detail)

- **Net GEX** uses the ATM implied vol of 13:45 for every strike's gamma (R36's code). The flip is the zero crossing
  nearest spot, on spot ±3% in 0.05% steps, and it stays fixed for the afternoon.
- **US sign convention** (calls positive, puts negative), as Boss's formula. Under the Indian reading, where writers
  are short both sides, net GEX never changes sign and there is no flip to test.
- **Flip found:**
  - NIFTY: on all 271 expiries;
  - SENSEX: on all 130 expiries that have OI (the lake has no SENSEX OI on 45 expiries of 2023-24).
- **The crossing:** the bar's close beyond the flip after the previous close was not.
- **TWAP, volume and heavyweights:** R35's code, NSE tickers.
- **Skips:** 1 lot above Rs 2,000 skipped 2 trades in G1.

## Results (446 expiries; nothing is fitted, so all of them are out-of-sample)

| rule | trades (CE / PE) | hit % | ≥ 2× / 4× / 10× % | net Rs | per Rs 1,000 | worst day | max DD | losing streak | w/o best day | 2× spread | twin mean | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| G1 | 42 (8 / 34) | 9.5 | 21.4 / 7.1 / 0 | **−40,003** | −550 | −1,944 | −41,806 | 26 | −45,118 | −40,500 | −16,571 | 0.994 | 1.00 |
| G2 negative side | 34 (0 / 34) | 5.9 | 23.5 / 5.9 / 0 | −34,878 | −590 | −1,944 | −36,681 | 26 | −39,812 | −35,513 | −18,225 | 0.992 | 1.00 |
| G3 hold 15:05 | 42 (8 / 34) | 9.5 | 21.4 / 7.1 / 0 | −45,921 | −631 | −1,944 | −46,327 | 26 | −51,375 | −46,418 | −32,423 | 0.929 | 1.00 |
| G4 no heavyweights | 65 (15 / 50) | 16.9 | 24.6 / 10.8 / 3.1 | −37,924 | −331 | −1,979 | −43,030 | 17 | −56,445 | −40,756 | −13,979 | 0.959 | 1.00 |

- **G1 by index:** NIFTY 34 trades, −Rs 30,825; SENSEX 8 trades, −Rs 9,178.
- **G1 by R25 block:** 2021-22 had 5 trades and +Rs 150; 2023-26 had 37 trades and −Rs 40,153.
- **G1 by year** (trades / net Rs):

  | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
  |---|---|---|---|---|---|
  | 2 / −1,961 | 3 / +2,111 | 9 / −3,493 | 8 / −11,485 | 13 / −17,868 | 7 / −7,307 |

- **G1's exits:** 33 held to 15:05 and 9 hit the lock. Its best trade was 7 Sep 2023 (NIFTY 19700 CE at Rs 8.80,
  peak 7×, +Rs 5,115).
- **Gates:** all 4 fail gates 2, 3 (the 2023-26 block is negative), 4, 5 and 6. G1, G3 and G4 also fail the drawdown
  limit.
- **BH family: 52.** The smallest q is 0.104; every R37 q is 1.00.
- **Puts dominate** in G1 (34 of 42). G1 has no gamma-side filter, so this is just how the crossings fell. Only G2,
  which requires the destination side to be negative gamma, is all puts (34 of 34).

## Honest notes

- **The real market-maker inventory is unknown.** This GEX is a proxy from open interest, with one implied vol for
  every strike. India's writers may not hedge like US dealers.
- **The flip is read once, at 13:45.** A flip refreshed every minute was not tested.
- **TWAP stands in for VWAP**, because the index has no volume.
- **The 15:05 exit is the app's square-off.** The blueprint's claim of an "IV crush after 15:05" is not supported by
  the data: options trade normally to 15:30, with closing-auction settlement since Aug 2026.

## Checks run

- `python -m pytest research/hunt/r37/test_r37.py -q` passes 8 tests. They cover:
  - the flip sitting between the put and call OI, and no flip when net GEX keeps one sign;
  - the flip reading no future data;
  - the trigger needing the break in that same bar;
  - the trail (breakeven at 2×, half of each doubling, else 15:05);
  - the 1-lot skip and the window;
  - the look-ahead guard on real NIFTY and SENSEX days.
- R26 and R34-R36 tests, the brain tests and the catalog check still pass. `python research/experiments/validate.py`, and
  `python3 research/knowledge/build_pack.py` then `--check`.
