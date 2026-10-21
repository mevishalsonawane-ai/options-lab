# R95 result: the "Mo3ty CCI Signals" indicator (21 Oct 2026)

**The indicator as given does not make money after costs, and its BUY / SELL signals do not predict direction.**
Of 328 pre-registered cells, 2 survived the design and the one holdout read: "MO" (buy WITH the acceleration) on
FINNIFTY 5m and 15m. They are thin, so they are paper candidates for Boss to decide on. Nothing went to paper or live.
Signal source: the underlying in every rule (index spot / MCX near future); the 1-ITM option is bought on real prints,
after real charges and slippage, 1 lot.

- **What CCI measures:** how far the candle's typical price is from its 25-candle average, in units of its average
  distance. +200 = about 2.4 standard deviations, about 3 ATR (NIFTY 5m: about 64 points). A steady trend sits at +128,
  so the SELL / BUY cross back inside means "the acceleration stopped", not "price turned".
- **Connection with actual price:** after a signal, price goes the signal's way 38-54% of the time over the next 15
  candles, the same as after any random candle (48-52%).
- **Win rate of the signals as given (design 20 Nov 2024 - 31 Mar 2026 | holdout 1 Apr - 19 Oct 2026):**
  - underlying, before costs: 33-44% (pooled), average win about 2x the average loss, so roughly break-even;
  - option, after costs: 28-47% on the indices, 11-39% on MCX (single cells), payoff 1.1-2.1 (index books) and
    0.6-1.3 (MCX);
  - pooled index books: 3m -Rs 7.01 lakh on 4,072 trades | -Rs 2.01 lakh on 1,618; 5m -Rs 4.73 lakh on 2,766 |
    -Rs 1.40 lakh on 1,122; 15m -Rs 71,491 on 1,156 | +Rs 66,740 on 477; 60m +Rs 61,524 on 244 | -Rs 20,858 on 86.
    MCX lost on every timeframe in both periods. None of the 8 fade variants passes (0 of 288).
- **Repaint:** the divergence labels are drawn 5 candles before they are known. Entered at the label (what the chart
  shows) the index trades made +Rs 12.0 lakh (1,687 trades, 60% win); entered when known, -Rs 3.4 lakh (1,778, 43%).
- **MO (amendment A1):** FINNIFTY 5m (design 407 trades +Rs 59,212 | holdout 158 trades +Rs 23,387, win 32%, payoff
  2.98, p 0.002) and 15m (216 trades +Rs 1,13,428 | 76 trades +Rs 14,980, win 39%, payoff 2.00, p 0.018) pass every
  gate. But both lose without their 5 best holdout trades, and they are one instrument. BANKNIFTY 60m B / XT passed
  the design on 48 trades and failed the holdout.
- **EX (amendment A1, the cross as an early exit):** it lowered the Liquidity and Breadth Donchian arms' profit in all
  4 cells (for example Liquidity 5m -Rs 70,223 over 171 changed trades). Rejected.
- **Next, only if Boss wants it:** an APP_SPEC for a PAPER-only FINNIFTY MO arm behind a switch that is off by default,
  logged in the Autopilot change timeline and undoable, with its own forward record. Never live by itself.

Files: `research/HUNT_R95_CCI_SIGNALS.md` (plain-English write-up with every table),
`research/hunt/r95_cci_signals/NOTES.md` (N3-N5: data, design read, holdout read), `results/` (JSON, each under 1 MB),
`research/experiments/entries/r95-*.yaml` (10 entries), `research/FINDINGS.md`.

How it ran: the lake was copied from `gdrive:IraAlgo-data-lake` with plain `rclone copy` (only the files the loaders
read). The disk had about 1.1 GB free, so each store was built with the unchanged code and kept gzip-compressed, read
back through a small runner outside git; raw files were deleted after use. Checks: R95 tests 50 passed, brain tests 183
passed, feature catalog up to date, experiments validator OK.
