# R76 pre-registration: "25% - 35% of target" lock on Liquidity 15+5

Written 18 Oct 2026, **before any R76 result was computed**. Research only (no `android/**`); any app change is Boss's
decision, made in the parent session.

Boss, 18 Oct: *"What about 35-5 for liquidity"*, then the correction *"25% -35% of target"*. The trigger is the trade's
**progress toward its target**, not a gain on the option premium. Liquidity's target is an index level (the next
liquidity level), so the rule is:

> once the **index** has moved X% of the way from the entry index price to the target level, the **option's** stop rises
> to +5% above the fill price.

The first reading ("+5% once the option is +35%", premium-based) is kept as a side line (L35), because Boss first asked
"35-5". This file was written after the correction and before any number, so there is no amendment.

## 1. Arm (exactly as R70 ran it)

Liquidity 15+5, `hunt/r51/fam_liq.py make(und, books)` on R51's Amendment A clean stores (R51_CLEAN=1), exactly as R63
and R70: BANKNIFTY 15 + 5, FINNIFTY 30 + 5 (parked), MIDCPNIFTY 15 + 5 (index stop 8 points); 1-ITM; one position per
book; expiry days skipped.

- **Signal source:** index level breaks (swing + pool zones) on 15-, 30- and 5-minute index bars. Unchanged.
- **Trigger source (T variants):** the index (its 1-minute high for a call, its low for a put), toward the arm's own
  index target. **Stop source:** the option premium.
- **Own exits (B0):** -15% premium stop; 20-minute time stop unless +5%; the index exits (index stop, next-level target,
  failed break, new level); 15:10.

## 2. Variants (B0 + 5 tests)

| id | rule |
|---|---|
| **B0** | Liquidity's own exits (what it runs today) |
| **T25** | B0 + once the index has covered 25% of the way to the target, the stop rises to +5% over the fill (Boss) |
| **T35** | the same at 35% (Boss) |
| **T30** | the same at 30% (neighbour) |
| **T50** | the same at 50% (neighbour) |
| **L35** | B0 + once the option has traded +35% above the fill, the stop rises to +5% (side line, premium-based) |

Definitions (T variants):
- Entry index price `I0` = the index open of the option's fill minute (the minute the option was bought); if that
  minute has no index bar, the signal bar's close.
- Target `TG` = the trade's own next-liquidity-level target from `fam_liq` (the level whose touch sells the trade).
- Trigger level = `I0 + X * (TG - I0)`. A call triggers on the first minute (from the fill minute on) whose index high
  is >= the level; a put on the first minute whose index low is <= the level.
- Only bars at or after the fill minute and before the trade's exit are read (no look-ahead). As with every lock in
  R51 / R64 / R66 / R70: the lock is set at the end of the trigger minute and acts from the next minute; it is hit on
  a minute's option low and a gap through it fills at the worse open; locks only rise.
- A trade with no target ahead (fam_liq allows it) or with `I0` already at or past the target never triggers; it is
  priced as B0. The count is reported.

Each trade is made once with B0 (the same trades, same entry row, same index-exit row) and priced again under each
variant, so only the exit changes. Limit (as R70): an earlier exit could let a book take a later signal it skipped;
that is not modelled. The number of changed trades bounds it.

## 3. Measurement, periods, gates, decision

R51 / R64 engine (held contract, liq slippage, stops on the minute low with gap fills at the worse open, locks from the
next minute, `Costs('dated')`, 1 lot). The T variants run on `r76engine.simulate`, a copy of R64's `simulate` that also
reads a per-minute index-trigger flag. A test proves it gives R64's numbers exactly when the flag never fires, and the
study asserts that its B0 equals R64's B0 trade by trade.

- Periods: design 20 Nov 2024 - 31 Mar 2026, with the walk-forward quarters Apr 2025 - Mar 2026; final 1 Apr - 13 Oct
  2026, read once (`results/FINAL_READ`). The final period was already read by R51, R63, R70 and others, so it is
  **weak evidence**.
- Reported for each variant and period: trades, win rate, net, per trade, worst day, max drawdown, green days; trade by
  trade against B0: how many changed, Rs given up, Rs saved; two or three example trades.
- A paired day bootstrap (2,000, seed 76) of variant - B0, design and final (information only).
- Random-exit twins (500, seed 76), final period, as R70: as many random early exits as the variant changed trades, at a
  uniform minute after the fill and up to the plain exit. **BH across the 5 tests** (T25, T30, T35, T50, L35).
- **Decision (as R70):** a variant is recommended only if its net is at least B0's in design, walk-forward and final AND
  its exit-twin q <= 0.10. Otherwise Liquidity keeps its exits, and the variant is reported as "harmless" (it never made
  less than B0 in any of the three periods) or not.

Liquidity BANKNIFTY is the go-live candidate (26 Oct). Any change would be paper-only, logged and labelled, so its
plain-rules record stays separable (CLAUDE.md).
