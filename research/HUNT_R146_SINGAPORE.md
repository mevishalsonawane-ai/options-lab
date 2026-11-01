# R146: does the Singapore market tell us anything about the Indian index day? Only the opening gap, which is already in the 09:15 price

1 Nov 2026. Research only. Boss: *"Check what is the effect of Singapore market on indian market, as it closes before
indian market starts"*. Pre-registration: `research/hunt/r146_singapore/PREREG.md`, pushed before any number.

## A correction first

**Singapore does not close before India opens.**
- The Singapore Exchange trades 06:30-14:30 IST, so it is half-way through its day when India opens at 09:15.
- What is known before 09:15 is Singapore's **overnight gap** (yesterday's close to its 06:30 IST open) and its
  **early-morning move** (06:30 to 08:30 IST).
- "SGX Nifty", the NIFTY futures once traded in Singapore, moved to GIFT City in July 2023 and is now **GIFT
  Nifty**. H27 already tested it with the US and other Asian markets: it moves the opening gap, but none of 234
  option-buying variants passed.
- R146 tests **Singapore's own index, the STI**, which H27 left out.

## The answer

1. **Singapore's move before India opens lines up strongly with India's opening gap.**
   - Correlation **0.61 for NIFTY and 0.57 for BANKNIFTY** with Singapore's overnight gap (2,583 days, 2015 -
     Sep 2025).
   - **0.50 and 0.49** in the holdout (Oct 2025 - Oct 2026, 251 days, read once).
   - Simply put: when Singapore opens up, India usually opens up too, because both react to the same world news
     overnight.
2. **It tells us nothing useful about the rest of the Indian day.**
   - After the 09:15 open, Singapore's signals do not predict the first hour (09:20-10:15) or the rest of the day
     (09:20-15:10).
   - In design every correlation was between -0.06 and +0.11, and none passed the test (all q >= 0.16). No trade
     test was run, as pre-registered.
3. **One link looked real only in the holdout:** BANKNIFTY's first hour against Singapore's gap, with the US move
   removed (r 0.16, p 0.002).
   - It was zero in design (r 0.02).
   - Almost all of it came in the first half of the holdout (0.27, then 0.02).
   - That is a passing effect, not something to trade.
4. **Same lesson as H27:** by 09:15 India's price already includes what Singapore, Asia, the US and GIFT Nifty did.
   **Buying options on Singapore's direction would not make money.** The only use is knowing in advance roughly how
   India will open. GIFT Nifty, which trades on NIFTY itself, already shows that more directly.

## Every test (r = correlation; q = BH-adjusted p over the 26; holdout read once)

| index | Singapore signal | India target | design: days, r (q) | holdout: days, r | verdict |
|---|---|---|---|---|---|
| NIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | India's opening gap (09:15) | 2583, +0.61 (0.00) | 251, +0.50 | **confirmed** (the gap only) |
| NIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | first hour (09:20 to 10:15) | 1003, -0.01 (0.81) | 250, +0.13 | no link |
| NIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | rest of the day (09:20 to 15:10) | 1003, -0.05 (0.28) | 250, +0.07 | no link |
| NIFTY | Singapore morning move (06:30 to 08:30 IST) | India's opening gap (09:15) | 439, +0.09 (0.28) | 249, +0.14 | no link |
| NIFTY | Singapore morning move (06:30 to 08:30 IST) | first hour (09:20 to 10:15) | 438, +0.05 (0.70) | 248, -0.00 | no link |
| NIFTY | Singapore morning move (06:30 to 08:30 IST) | rest of the day (09:20 to 15:10) | 438, +0.08 (0.27) | 248, -0.04 | no link |
| NIFTY | Singapore total move before India opens (to 08:30 IST) | India's opening gap (09:15) | 439, +0.48 (0.00) | 249, +0.45 | **confirmed** (the gap only) |
| NIFTY | Singapore total move before India opens (to 08:30 IST) | first hour (09:20 to 10:15) | 438, -0.01 (0.87) | 248, +0.09 | no link |
| NIFTY | Singapore total move before India opens (to 08:30 IST) | rest of the day (09:20 to 15:10) | 438, +0.01 (0.87) | 248, +0.02 | no link |
| NIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) (US move removed) | first hour (09:20 to 10:15) | 1003, -0.00 (0.99) | 250, +0.12 | no link |
| NIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) (US move removed) | rest of the day (09:20 to 15:10) | 1003, -0.02 (0.71) | 250, +0.08 | no link |
| NIFTY | Singapore total move before India opens (to 08:30 IST) (US move removed) | first hour (09:20 to 10:15) | 438, +0.04 (0.70) | 248, +0.08 | no link |
| NIFTY | Singapore total move before India opens (to 08:30 IST) (US move removed) | rest of the day (09:20 to 15:10) | 438, +0.08 (0.27) | 248, +0.01 | no link |
| BANKNIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | India's opening gap (09:15) | 2583, +0.57 (0.00) | 251, +0.49 | **confirmed** (the gap only) |
| BANKNIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | first hour (09:20 to 10:15) | 972, -0.01 (0.87) | 250, +0.15 | no link |
| BANKNIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) | rest of the day (09:20 to 15:10) | 973, -0.06 (0.16) | 250, +0.13 | no link |
| BANKNIFTY | Singapore morning move (06:30 to 08:30 IST) | India's opening gap (09:15) | 439, +0.11 (0.19) | 249, +0.14 | no link |
| BANKNIFTY | Singapore morning move (06:30 to 08:30 IST) | first hour (09:20 to 10:15) | 438, +0.08 (0.43) | 248, -0.01 | no link |
| BANKNIFTY | Singapore morning move (06:30 to 08:30 IST) | rest of the day (09:20 to 15:10) | 438, +0.08 (0.28) | 248, -0.03 | no link |
| BANKNIFTY | Singapore total move before India opens (to 08:30 IST) | India's opening gap (09:15) | 439, +0.46 (0.00) | 249, +0.44 | **confirmed** (the gap only) |
| BANKNIFTY | Singapore total move before India opens (to 08:30 IST) | first hour (09:20 to 10:15) | 438, +0.07 (0.43) | 248, +0.10 | no link |
| BANKNIFTY | Singapore total move before India opens (to 08:30 IST) | rest of the day (09:20 to 15:10) | 438, +0.04 (0.53) | 248, +0.07 | no link |
| BANKNIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) (US move removed) | first hour (09:20 to 10:15) | 972, +0.02 (0.70) | 250, +0.16 | no link |
| BANKNIFTY | Singapore overnight gap (yesterday close to 06:30 IST open) (US move removed) | rest of the day (09:20 to 15:10) | 973, +0.00 (0.99) | 250, +0.14 | no link |
| BANKNIFTY | Singapore total move before India opens (to 08:30 IST) (US move removed) | first hour (09:20 to 10:15) | 438, +0.11 (0.27) | 248, +0.10 | no link |
| BANKNIFTY | Singapore total move before India opens (to 08:30 IST) (US move removed) | rest of the day (09:20 to 15:10) | 438, +0.10 (0.18) | 248, +0.07 | no link |

## Method

- **Data:**
  - Singapore STI and the S&P 500 from Yahoo's chart API (H27's method), downloaded 1 Nov 2026: STI daily
    2015 - Oct 2026; STI hourly Dec 2023 - Oct 2026 (Yahoo's limit). Raw files outside git, parsed only with
    `python -I`.
  - NIFTY / BANKNIFTY from the Drive lake: the daily file for the gap, minutes (2021+) for the day.
- **Signals and cut-off:** known before 09:15 IST. The last Singapore hourly bar used closes at 08:30 IST.
- **"US move removed":** both series are regressed on the S&P 500's last session before India's day (partial
  correlation).
- **p:** a moving-block bootstrap (10-day blocks, 2,000 draws), two-sided; BH over the 26.
- **Files:**
  - code: `r146.py`, `fetch.sh`;
  - tests: `test_r146.py`;
  - results: `results/design.json` (committed before the holdout read), `results/holdout.json`;
  - entry: `research/experiments/entries/r146-singapore.yaml`.
