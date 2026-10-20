# R89 result (20 Oct 2026): Boss's previous-day high / low 1-minute break

**Verdict: it does not pay after costs. 0 of 180 pre-registered cells (20 variants x 9 instruments) passed; the locked
holdout (1 Apr - 19 Oct 2026, read once) lost on every index and every MCX book. Nothing goes to paper; no app change.**

- Five indices, base rule: design -Rs 3.07 lakh (2,925 option trades), holdout -Rs 1.28 lakh (1,210); random-entry twins
  did as badly, so the break-and-candle timing adds nothing. The index moves +0.4 to +3.5 points a trade at the levels;
  the 1-ITM option round trip costs 1.1-3.7 points.
- False breaks (stopped within 3 candles) are about a third of trades; the 15m + daily trend filter does not reduce them.
- Best lead FINNIFTY 3m: +Rs 56,447 design (q 0.12, fail) -> -Rs 6,921 holdout. MCX loses everywhere (thin GOLDM /
  SILVERM option prints).
- Different from, not better than, the Breadth Donchian / Liquidity / ORB arms.

Write-up: `research/HUNT_R89_PDHL_BREAK.md`. Code and tests: `research/hunt/r89_pdhl_break/`. Signal source of every
rule: the underlying.
