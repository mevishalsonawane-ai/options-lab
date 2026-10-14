# HUNT IV1: option-market signals (IV skew, straddle, PCR, OI build-up), 14 Oct 2026

Boss said "ok" to the follow-up of R30: build a study around the option-chain inputs R30's models leaned on.
- Code: `research/hunt/iv1/`.
- Pre-registration: `research/hunt/iv1/PREREG.md`, commit ff5f951, written before any IV1 run.
- Data and trades are R30's: 5 indices, every 5-minute bar close 09:30–14:45, ATM CE / PE, Boss's +Rs 1,000-keep-Rs 500
  lock, real charges and spread. The test periods are Jul–Dec 2024, 2025 and Jan–Oct 2026, each traded with a model
  trained only on earlier days.

## Verdict for Boss

**No. The option-market signals on their own do not make money after costs. None was positive in every test period, so
none goes to the forward test. R30's NIFTY model stays the only forward candidate.**

- **O1, a model on option-market inputs only:** lost on all five indices. The worst were BANKNIFTY at Rs −224 a trade
  (646 trades) and MIDCPNIFTY at Rs −256 (856).
- **O2, the best option-market pairs from earlier years:**
  - SENSEX made +Rs 35 a trade on 915 trades (p 0.013, q 0.097), but it lost Rs 40 a trade in 2025.
  - NIFTY made +Rs 6 a trade, which is break-even.
  - The other three indices lost.
- **O3, a fixed "smart money" rule:** buy CE when puts are being written and calls covered near the money, PCR is
  rising and the straddle is expanding; buy PE on the mirror. It lost on all five indices: Rs −24 (NIFTY) to Rs −245
  (MIDCPNIFTY) a trade, 7,339 trades in all.
- **Why R30's NIFTY model did better.** It used everything together, including where price sits against the day's
  levels and the time of day. With the option chain alone, the edge disappears. The option chain helps as part of the
  picture, not as a signal by itself.
- **Caveat:** these inputs were chosen after seeing R30's test years, so this walk-forward is descriptive. It can rule
  ideas out, which it did. It cannot rule them in.

## Results (out of sample, Rs per lot per trade; trades in brackets; per period Jul–Dec 2024 / 2025 / 2026)

| strategy | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX |
|---|---|---|---|---|---|
| O1 option-only model | −73 (227): −175 / +14 / −121 | −224 (646) | −28 (336): +72 / −53 / +119 | −256 (856) | −16 (239): −90 / +33 / −66 |
| O2 option-only pairs | +6 (888): −17 / +29 / −30 | −64 (1,060) | −42 (927) | −171 (1,103) | **+35 (915): +117 / −40 / +130** |
| O3 fixed OI / PCR / straddle rule | −24 (1,571) | −162 (1,558) | −219 (1,350) | −245 (1,371) | −40 (1,489) |

`results/walkforward.json` has, for each strategy and index:
- win rate (48–61%), worst day, max drawdown, P&L a day;
- p against random entries and BH q over the 15 tests.

O2 FINNIFTY reached q 0.030 against random entries while losing Rs 42 a trade. Random entries lose even more here, so
beating them is not enough.

The O1 model leans on the straddle price above all, then days to expiry, IV skew and the straddle's change since 09:20.

## Files

- `research/hunt/iv1/study.py`, `PREREG.md`, `results/walkforward.json`.
- Experiment entries: `research/experiments/entries/iv1-o*.yaml`.
