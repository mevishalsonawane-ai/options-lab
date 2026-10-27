# R123 pre-registration: expiry-day short straddle with a stop on the INDEX (research loop, round 3)

Written 27 Oct 2026, **before any R123 number was computed** (pushed alone on `claude/r117-zero-hero-lock` before the
code's first run). Boss, 27 Oct: *"... find the best strategy you can with max winrate."* Research only; nothing in
`android/**` changes; nothing goes to paper or live.

## Why

- R119 / R120 / R122: on expiry days the short ATM premium is real before costs (R119's 09:30 iron fly: +Rs 231 a
  trade before costs), and the cost of 8 orders (Rs 184-200) eats it. The wings are half of those orders.
- The out-of-the-box swap: drop the wings, and cap the risk with a stop on the UNDERLYING instead. If the index moves
  k straddles from the entry, both legs are closed at the next minute's open. 4 orders a trade (6 when stopped), not 8.
- The probability behind it (R118): from 09:30 the index's best one-way move to the close exceeds 1 straddle on about
  half of expiry days and 2 straddles on 19%. So k decides the trade-off between win rate and how often the stop hits.
- Not tested before: naked straddles in the log are non-expiry, premium-stopped (book-fmmusiela-03) or delta-hedged
  (book-fmbjork-01); nothing is stopped on the index.
- **Honest note:** the idea leans on R118-R122 descriptive and test findings on the same 2021-26 expiry days; no period
  is pristine for the idea.

**Signal source:** the stop reads the underlying (the index's minute closes), the CLAUDE.md default; the entry is the
clock; strikes from the index's ATM strike.

## Data

R98's expiry-day records (lake copied 27 Oct, to 19 Oct 2026): NIFTY 273, BANKNIFTY 195, FINNIFTY 167, MIDCPNIFTY
100, SENSEX 176 expiry days. Periods: DESIGN to 31 Dec 2023; WALK-FORWARD 2024-25; FINAL 1 Jan - 19 Oct 2026, read
once.

## The rule (two stop widths = two tests)

- **Entry:** decision on the 09:30 bar's close. K0 = the index close rounded to the strike step; S = the ATM straddle
  (last closes within 3 minutes); s0 = the index close. Sell 1 lot C(K0) and P(K0); fills at the 09:31 open (or the
  next of 3 bars); a leg that cannot fill skips the day.
- **Stop:** on each 1-minute index close from 09:31 to the 15:04 bar, if |index - s0| >= k x S, buy back both legs at
  the next bar's open (or the next of 3 bars; a leg with no print there is priced by Black-76 at its last IV, the
  index then and the time left).
- **Otherwise:** buy back both at the 15:05 open (the app's expiry square-off).
- **V1:** k = 1.0. **V2:** k = 1.5.

## Pricing

R119's engine pieces: R25 'liq' slippage x R54's late multiplier, R50 / obuy dated charges per order (BSE rates for
SENSEX), dated lots. Margin: R103's estimate (2.5% of the short notional + 2% expiry-day ELM from 20 Nov 2024),
reported; a naked intraday straddle's real margin is higher.

## Gates (all needed; never relaxed)

1. Mean net per trade > 0 in DESIGN, WALK-FORWARD and FINAL.
2. FINAL > 0 without its 3 best days, and FINAL mean > 0 at 2x slippage.
3. Random twin, FINAL, 2,000 twins (seed 123): each trade replaced by the same rule entered at a uniformly random bar
   close 09:30-14:30 that day (stop measured from that entry, out at 15:05). p < 0.05.
4. Benjamini-Hochberg q <= 0.10, m = every logged family to 27 Oct 2026 + R123's 2.
5. At least 40 FINAL trades.
6. Boss's limits at 1 lot, FINAL: no month at or below -Rs 13,000; max drawdown < Rs 40,000.

Reported: trades, WIN RATE, mean, total, gross and charges per trade, share of trades stopped, worst day, max drawdown,
months at the stop, by index.

## What a pass would mean

PAPER only, as a separate labelled arm, with Boss's explicit decision (it sells naked options); logged, undoable in one
tap, never live from R123. Entries: `research/experiments/entries/r123-*.yaml`.
