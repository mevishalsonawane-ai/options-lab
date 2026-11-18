# R196: enhancing the option-selling rules (side study, 18 Nov 2026)

Boss, after R195: "Enhance it more try internet, find new ways to get more profit and less loss and more winrate".
**Research only. The app stays buy-only; nothing goes to paper or live.**

- **Pre-registration:** `research/hunt/r196_selling_plus/PREREG.md` (14 candidates; it was written and hash-stamped in
  `results/PREREG_STAMP.txt` before any R196 number).
- **Code:** `r196engine.py` (the two-leg minute loop; it reproduces R195's S1 exactly: 219 of 219 real trades
  checked, plus a hand-made test), `r196study.py`, `r196report.py`; 6 tests in `test_r196.py`. Full tables:
  `results/report.md`, `results/final.json`.
- **Where the ideas came from:** practitioner pages found on 18 Nov 2026 (Fyers "move SL to cost", AlgoTest
  break-even trail, MarketCalls on the 09:20 straddle being stop-hunted and a suggested 10:30 entry, broker notes on
  avoiding event days), an academic abstract on Nifty's volatility premium (Bhat, "The asymmetry in day and night
  option returns"), and the put-skew idea for the one structure Rs 56k can carry. These are vendor and blog claims,
  not tests; R196 tests them.
- **Honest limit:** 2026 had already been read once by R195, so it is a "second look" here. The clean holdout is the
  forward window from 16 Nov 2026, not yet available.

## Verdict

**None of the 14 enhancements passes. None improved all three of Boss's aims (more profit, higher win rate, smaller
drawdown) over the base rule.** Every candidate breaks the account stop at 1 lot (worst month below -Rs 13k, or a
drawdown above Rs 40k, over 2021-2026). All BH q values are 1.00 (m = 4,821).

What each idea did, against the base R195 straddle with a 25% stop per leg (NIFTY design 2021-2025: 1,092 trades, 60%
wins, +Rs 1,97,065, drawdown Rs 35,628):

| idea | design 2021-2025 | 2026 (second look) | what it changed |
|---|---|---|---|
| A1 stop to cost after the first stop | 1,092 / 52% / +Rs 1,42,460 / DD Rs 44,022 | 214 / 51% / +Rs 32,591 / DD Rs 46,765 | Lower win rate and more drawdown in design (-Rs 54.6k vs base): the surviving leg is often stopped at cost and then the market reverses. It was the best NIFTY variant in 2026. |
| A2 one combined 20% stop | 1,092 / 54% / +Rs 1,61,748 / DD Rs 43,241 | 214 / 56% / -Rs 9,429 | Worse on all three; worst month -Rs 21.7k. |
| A3 profit lock (+30% arms, +15% out) | 1,092 / 61% / +Rs 1,95,679 / DD Rs 35,476 | 214 / 58% / -Rs 2,388 | Win rate +1 point and drawdown slightly lower, but Rs 1.4k less profit; it lost in 2026. |
| A4 enter at 10:30 | 1,091 / 58% / +Rs 70,211 / DD Rs 86,640 | 214 / 57% / -Rs 24,689 | Much worse: the morning decay is the edge. |
| A5 strangle 2 steps out, 30% stops | 1,092 / 60% / +Rs 1,39,942 / DD Rs 30,750 | 214 / 57% / -Rs 19,211 | Smaller drawdown, the same win rate, Rs 57k less profit; it lost in 2026. |
| A6 skip shock days | 821 / 62% / +Rs 2,00,823 / DD Rs 37,402 | 142 / 59% / +Rs 4,446 | Skipping 271 days saved +Rs 3.8k in design (paired p 0.47, noise); worst month -Rs 24.6k. |
| A7 combination (stop to cost + lock + VIX filter), NIFTY | 421 / 54% / +Rs 79,996 / DD Rs 23,448 | 152 / 53% / +Rs 30,401 | The smallest drawdown, but half the profit of the VIX-filtered base (S2: +Rs 1.56 lakh) and a lower win rate. |
| A7 on BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX | +Rs 1.68 lakh / +Rs 86k / +Rs 1.84 lakh / +Rs 1.53 lakh (303-421 trades) | +Rs 3.8k / +Rs 86.2k / +Rs 18.0k / +Rs 1.9k (98-152) | Lower drawdown on 3 of 4, always less profit and a lower win rate than the base on the same days. FINNIFTY's 2026 again leans on its thin monthly prints (R195 Amendment 1). |
| PS1 bull put spread, NIFTY (put skew; fits Rs 56k) | 861 / 60% / -Rs 57,025 / DD Rs 78,236 | 169 / 53% / -Rs 27,049 | Better than R195's trend spread on all three aims, but still a loss. Its side twin (the same trade as a call spread) did about as well, so the put skew did not pay after costs. |
| PS2 put spread on high-VIX days | 338 / 58% / -Rs 34,448 | 120 / 55% / -Rs 11,201 | A smaller loss; still a loss. |
| PS3 put spread, BANKNIFTY | 880 / 57% / -Rs 68,391 | 204 / 46% / -Rs 53,584 | A loss. |

(Cells: trades / win / net / max drawdown, 1 lot at today's lot, after slippage and dated charges.)

## What this says, in plain English

- **The stop tweaks trade one aim for another.** A higher win rate (the lock) or a smaller drawdown (the strangle,
  the combination) each cost profit. None gave more of all three. The plain 25% stop per leg, entered at 09:20, stays
  the best version over 2021-2025.
- **The internet's favourite fixes did not help on this data.** Moving the stop to cost cut the win rate from 60% to
  52%. A later entry halved the profit. A combined stop lost money in 2026.
- **Selling put spreads (the only thing Rs 56k can carry) loses money** on NIFTY and BANKNIFTY after costs, with or
  without a VIX filter. The put skew is real in prices, but it does not survive four orders of charges and slippage
  intraday.
- **2026 is weak for every straddle version** (the best, A1 and A7 on NIFTY, made about Rs 30k on 150-214 trades,
  with 2026 drawdowns of Rs 40-47k), which is what R195 already saw.
- Together, R195 and R196 tested 32 option-selling rules and 5 naked baselines on five indices. **None is reliable,
  and none that made money fits a Rs 56k account.** The forward window (from 16 Nov 2026) can be read when 40+ trades
  exist, but nothing here earned a forward test by the pre-registered gates.

## Caveats

R195's caveats all apply: modelled slippage (no bid / ask), a margin model, today's lot for every year, and stale
monthly prints in FINNIFTY / MIDCPNIFTY. A per-leg stop in the market can also be spiked far past its level, as on
20 Aug 2021 in MarketCalls' example. The engine's doubled stop ticks do not model that, so real per-leg stop results
would be worse, not better.
