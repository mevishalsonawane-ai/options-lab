# C2: Crypto in Boss's window, 00:00-09:00 IST (BTC and ETH, design data only)

Written 13 Oct 2026. Boss: "I want crypto market should only run from morning 12am to morning 9am." Crypto is now
studied, and would only ever be acted on, from **00:00 to 09:00 IST** (18:30-03:30 UTC), every day, flat by 09:00
before NSE opens. The data stays 24x7 (a 24-hour VWAP or a funding z-score needs the other hours); decisions, targets
and trades are inside the window only (`config.WINDOW`; features `in_boss_window`, `min_to_window_end`).
Pre-registration: `research/brain/crypto/PREREG_C2_WINDOW.md`, committed (9bdbc27) before any window result.
Code: `research/brain/crypto/window.py`. Numbers: `research/brain/crypto/results/c2_window.json`.

## Verdict (read this first)

**Inside the window, as in C1, the SIZE of the move is predictable and the direction is not something to trade.**

| test | verdict (design, holdout NOT read) | effect |
|---|---|---|
| W1 range of the next window hour | **PREDICTABLE** (BTC, ETH) | error 27.8% (BTC) / 23.4% (ETH) smaller than the window-hour average |
| W3 range of the whole window (00:00-09:00 IST) | **PREDICTABLE** (BTC, ETH) | error 17.2% (BTC) / 15.2% (ETH) smaller than the last 20 windows' average |
| W2 direction of the next window hour | real reversal, **loses after costs** | AUC 0.547 (BTC) / 0.550 (ETH); net -10.45 / -10.73 bps a trade |
| W4 direction of the whole window | BTC fails costs; **ETH passes its bar, with a margin inside the cost uncertainty** | AUC 0.580 (BTC) / 0.558 (ETH); net -3.82 (BTC) / **+0.37 bps** (ETH) a trade |
| W5 mean return of each IST hour (18 tests) | nothing beats costs | best: ETH 03:00-04:00 IST +7.88 bps (q 0.008), below the 12 bps round trip |

26 tests in one BH family; 5 pass every pre-registered bar: W1 and W3 on both coins, and W4 on ETH.

**W4 on ETH: a candidate, not an edge.** By the pre-registration a pass earns one holdout read, and that is its status
(inconclusive). What it is (post-hoc checks on its own signals, descriptions only, `describe.posthoc` in the json):
- a reversal of the last day's move: the 24-hour return alone has AUC 0.418 and the VWAP-24h z 0.420 (both reversed
  ~0.58); funding, basis and OI change add nothing (0.49);
- +0.37 bps a trade net on 351 trades: gross 12.49 bps against a 12 bps round trip. At 2x slippage it is -1.63 bps;
- the same trades entered one minute later (00:01 IST) keep it (gross 12.20, net +0.08 bps), so it is not a first-minute
  artefact;
- it is not stable across years: 2024 lost (119 trades, -27.3 bps net, AUC 0.491), 2025 won (179 trades, +10.56 bps,
  AUC 0.585), Jan-Mar 2026 won (53 trades, +28.08 bps, AUC 0.618). BTC's W4 shows the same pattern but fails costs
  overall (-3.82 bps; 2024 -14.65, 2025 -1.6, 2026 +10.67).
So even if the holdout (Apr-Sep 2026) confirms it, the margin over costs is thin: a fee or slippage only slightly worse
than assumed removes it. It does not trade, on paper or live, without its holdout read and a separate decision.

All of this is design data. The locked holdout (from 1 Apr 2026) was not read. Nothing here trades.

## Data and sizes

BTCUSDT and ETHUSDT, crypto cube v2 (Binance archive, 1 Oct 2023 - 31 Mar 2026), the same data as C1. Design rows per
coin: 7,928 window hours and 880 windows (2 Nov 2023 - 31 Mar 2026); out of sample 6,308 hours and 670 windows.

Typical sizes inside the window (medians): BTC moves 0.554% in a window hour and 1.83% over the whole window (10-90%:
0.9-3.87%); ETH 0.777% and 2.64% (1.34-5.47%). The busiest IST hours are 00:00-02:00 and 06:00-07:00 (BTC 0.631% at
00:00, 0.588% at 06:00), the quietest 08:00-09:00 (0.498%). A window hour is about as busy as the average crypto hour
(BTC median 0.554% vs 0.546% over all 24 hours; ETH 0.777% vs 0.786%): the US open (14:00 UTC, 19:30 IST, the busiest
hour) is outside the window, and so are the quietest hours (about 03:00-12:00 UTC, 08:30-17:30 IST). The whole window
closed up on 52.2% (BTC) / 52.5% (ETH) of days, mean +2.8 / +5.9 bps: no tradeable drift.

## Tests

| test | n | model | baseline | skill / AUC | trades | net / trade | q |
|---|---|---|---|---|---|---|---|
| W1 BTC | 6,308 | 0.350 | 0.484 | 27.8% | | | < 0.001 |
| W1 ETH | 6,308 | 0.357 | 0.465 | 23.4% | | | < 0.001 |
| W3 BTC | 670 | 0.377 | 0.456 | 17.2% | | | < 0.001 |
| W3 ETH | 670 | 0.381 | 0.450 | 15.2% | | | < 0.001 |
| W2 BTC | 6,306 | | | AUC 0.547 | 2,287 | -10.45 bps | < 0.001 |
| W2 ETH | 6,304 | | | AUC 0.550 | 2,236 | -10.73 bps | < 0.001 |
| W4 BTC | 670 | | | AUC 0.580 | 464 | -3.82 bps | < 0.001 |
| W4 ETH | 670 | | | AUC 0.558 | 351 | +0.37 bps | 0.017 |

Costs: perpetual taker 0.05% + 1 bp slippage a side (0.12% a round trip), plus funding at the last settled rate (the
00:00 UTC settlement, 05:30 IST, is inside the window).

## What the app and Jarvis could use

- **Expected range inside the window**: "BTC is likely to move about 0.55% this hour and about 1.8% before 09:00 IST".
  Same caveats as C1: a size, never a direction; any trading use is a new pre-registered test.
- **Honest "no"** on direction inside the window for now: the hour-to-hour reversal is real but smaller than costs;
  the whole-window ETH reversal is an unproven candidate whose margin is inside the cost uncertainty and which lost
  in 2024.

## Deviations and caveats

1. Design data only; C2 reuses C1's design data on a subset of hours (a new BH family, not independent evidence).
2. Walk-forward as C1: a block is tested only with at least 200 training rows, so the whole-window tests (W3, W4)
   trained on the first 7 of 30 blocks (880 windows, 670 tested).
3. The post-hoc checks of W4 (single inputs, one-minute-later entry, by year) were added after the results, and are
   descriptions, not tests. A first draft of this doc rejected W4-ETH on a wrong check (it refitted the model on
   shifted labels instead of delaying the same trades); a review caught it, the check was redone on W4's own signals,
   and W4-ETH keeps its pre-registered status: a candidate for one holdout read.
5. W2 used the IST-date weekend flag in place of C1 B1's UTC `is_weekend` (the window's own weekend), which the
   pre-registration did not say.
6. `in_boss_window` describes a bar's start, so the 18:29 UTC decision row (known at 00:00 IST, the window's first
   decision) reads 0; every TARGET is inside the window.
4. Slippage is an assumption (no bid/ask). SOL / BNB / XRP: not run yet (same commands).
