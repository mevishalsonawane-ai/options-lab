# R145 pre-registration: the "demand zone trap + put premium divergence + OI" video setup, as stated

Written 2 Nov 2026, before any zone, setup, trade or statistic was computed on the data. Research only: nothing in the
app changes, nothing trades. Boss, 2 Nov: *"yes run the backtest"* (after the check of the video's claims in chat).

The video (Bank Nifty, then Nifty, on Kite with Sensibull's OI profile) gives four factors for a call buy:
1. **Demand zone:** a sharp rally (about 600 BANKNIFTY points) from a support level after a drop. The big buyer there
   will defend it.
2. **The trap ("lollipop"):** price drifts back to that support and breaks below it, tempting put buyers.
3. **Premium divergence:** the index breaks down, but put premiums do not rise. That marks a false breakdown: stay out
   of puts and prepare a call trade.
4. **OI confirmation:** put OI (Sensibull's green bars) builds at the support.
Then the market reverses sharply up. Claimed: Rs 10,000-15,000 is "easy" on such moves; Rs 1 to Rs 52 (a 50x
expiry option) is possible but rarely held. The video gives no stop, target, time window or sample.

## 0. What is new here (earlier work)

| study | what it tested | result | how R145 differs |
|---|---|---|---|
| R91 | sweep of a 1h swing + strong 5-min candle | lost (-Rs 40,340, 135 trades) | no rally-built zone; no option-premium or OI condition |
| R130 | sweep -> structure shift -> FVG pullback | 0 of 6, all lost | same |
| R133 | CISD / Turtle Soup sweeps + R123's network | best kept trades too few in the holdout | the filter was a network, not the put premium or OI |
| R61b | trap and fake-breakout features as Solo filters | no gain | features on Solo's candidates, not this entry |
| R118 | every expiry option (descriptive) | cheap options overpriced; Rs 1-3 options reach 10x 2.3% of the time | descriptive; R145 tests no lottery ticket (see section 5) |

No earlier study used "the index makes a new low while the ATM put does not make a new high" or "put OI rising at the
support" as an entry condition.

## 1. Definitions (every choice the video leaves open is fixed here)

**Signal source: the index spot (the underlying), with option-chain context as filters.** Per CLAUDE.md, the zone,
the breakdown, the reclaim, the stop and the target are read on the index's minute bars. The put premium (factor 3) and
the put OI (factor 4) are option-chain context around that index signal, which CLAUDE.md allows as filters. The option
bought is the ATM call (the strike rule). V5's profit lock reads the held option's premium, which CLAUDE.md allows.

**Bars.** 5-minute bars of each index aligned to 09:15 (R130's `make_bars`), across sessions in time order. A bar is
used only after its last minute has closed.

**Swing low (the support).** A 5-minute bar i whose low is strictly below the lows of the 3 bars before it and at or
below the lows of the 3 bars after it (R130's rule, k = 3, wicks). Known only once bar i + 3 has closed.

**Demand zone.** A swing low L (bar i) from today or the previous 4 sessions, followed by a rally: some bar after i
reaches a high >= L x (1 + 1.0%) before any bar's low goes below L. (1.0% is about 550 BANKNIFTY points and 250 NIFTY
points at today's levels; the video's example was about 600 BANKNIFTY points.) A zone whose low is taken out before the
rally qualifies is dropped. Each zone is used at most once.

**The breakdown (the trap).** The first 5-minute bar t, after the zone's rally qualified, that **closes** below
L - 0.05 (one tick), in today's session. The bar must also make a new low for the last 30 minutes: its low is below the
lows of the 5 bars before it (same session). The breakdown bar must end at or after 09:44 (so the 30-minute window is
inside the session) and at or before 14:59. A breakdown outside these is counted and dropped. The zone is used up at
its first breakdown either way.

**Factor 3, the put did not rise (divergence).** At the breakdown bar's close (minute e = its last minute):
- the put strike is the ATM strike (half-up rounding to the strike step) of the index's close at minute e - 30;
- "before" = the highest **traded** 1-minute close of that put over minutes e - 29 .. e - 5 (the 5 bars before);
- "now" = the highest traded 1-minute close of that put over minutes e - 4 .. e (the breakdown bar);
- divergence when now <= before (the index made a new low; the put did not make a new high).
- At least 10 traded minutes of the put in e - 29 .. e - 5 and at least 1 in the breakdown bar, else "no data"
  (counted; no trade in variants that need factor 3).

**Factor 4, put OI building at the support.** The put at the strike nearest L (half-up rounding to the step):
its OI at minute e (the last OI at or before e) is at least 2% above its OI at minute e - 29 (the first OI at or after
it). Missing either: "no data" (counted; no trade in variants that need factor 4).

**Option data.** The contract series the trade would buy (the rule below) is also the one read for factors 3 and 4,
from the lake after lakeclean steps 1-3 (stale = True), as R123-R133. Prices: traded minutes only (volume > 0); OI: as
the lake has it each minute.

**Entry.** Buy the ATM call (half-up rounding of the index's last close before the entry minute) at the open of the
entry minute, 1 lot at today's lots (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20). Entry minute at
or before 15:00.
- V1, V3, V4, V5, V6: the minute after the breakdown bar (e + 1).
- V2 (reclaim): wait up to 6 bars after the breakdown bar for a 5-minute bar that closes above L; enter the minute after
  it. If a bar's low reaches the stop first, or no reclaim comes in 6 bars (same session), there is no trade.

**Stop (index).** The lowest low from the breakdown bar to the bar before entry, minus 0.02% of L.

**Target (index).** 2R: entry reference + 2 x (entry reference - stop), with the entry reference = the index's open in
the entry minute. No room (entry reference <= stop): no trade.

**Exits (index wicks, minute by minute from the entry minute; R130's `exit_on`):** stop on a low at or below the stop;
target on a high at or above the target; both in one minute: the stop; otherwise out at 15:20.
**V5's exit ("ride it", the video's 1-to-50 story):** no index target; the index stop as above; a profit lock on the
call's premium: once the call's traded close is Rs 1,000 or more above the buy fill (per lot, before charges), it is
sold at the first traded close at or below the buy fill + 60% of the best gain so far (R132 E3's lock); otherwise out at
15:20.

**The option series (R130's rule).** The nearest expiry that does not expire that day: the lake's `opt_<UND>_WEEK`
series, or `opt_<UND>_MONTH` on the index's weekly expiry day; no trade when both expire that day. Expiry days from the
data (R25's rule, as R123-R133). Sold at the last traded close at or before the exit minute. Liquidity-aware slippage
(Fills "liq"), also at 2x; dated charges (R123's `charge`).

**One trade at a time per index.** A setup whose entry comes before the open trade's exit minute on that index is
skipped (counted). Zones are found independently of trades.

**Bearish mirror (V6 only).** Everything mirrored (a supply zone below a 1.0% fall, a breakout close above it, the
**call** not making a new high, call OI at the resistance up 2%, buy the ATM put). R130's mirroring makes one code path
serve both. If both sides enter in the same minute on one index, neither is taken.

## 2. Variants (6, one BH family)

| variant | zone + breakdown | factor 3 (put did not rise) | factor 4 (put OI up) | entry | exit | sides |
|---|---|---|---|---|---|---|
| **V1** the setup as stated | yes | yes | yes | after the breakdown bar | index stop, 2R | calls |
| V2 | yes | yes | yes | **after a close back above L** | index stop, 2R | calls |
| V3 | yes | yes | **no** | after the breakdown bar | index stop, 2R | calls |
| V4 (baseline) | yes | **no** | **no** | after the breakdown bar | index stop, 2R | calls |
| V5 | yes | yes | yes | after the breakdown bar | **index stop + 60% premium lock after Rs 1,000** | calls |
| V6 | yes | yes | yes | after the breakdown bar | index stop, 2R | **both (mirror)** |

## 3. Data, periods and checks

- **Data:** the Drive lake (rclone remote `gdrive`, from env vars), copied 2 Nov 2026: `index_1m` for NIFTY,
  BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX; `options_rolling_1m` WEEK and MONTH files. Aug 2021 (MIDCPNIFTY / SENSEX
  later) to 19 Oct 2026. Nothing raw goes to git.
- **Periods (R123's):** design 2021-08-01 .. 2026-03-15, folds F1 2023, F2 2024, F3 2025, F4 2026-01-01 .. 03-15;
  embargo 2026-03-16 .. 03-31; **holdout 2026-04-01 .. 2026-10-19, read ONCE** for all 6 variants after the design
  results are committed, with a flag file that refuses a second read. Earlier studies have read the holdout months
  for other rules; none with this rule.
- **No fitting.** Every number above is fixed by this file.
- **Random-entry twins (200 per trade):** the same index, day and side; a random entry minute 09:20-15:00; the same
  stop distance (and, for 2R, the same target distance) in index points; the same exit rule and option rule.
  p = (1 + twin totals >= the rule's total) / 201 (R130's `twin_p`).
- **The video's claims, checked with n (design and holdout; descriptive):** over every breakdown of a qualified zone
  (calls side, in the entry window), a 1:1 race on index wicks from the breakdown bar's close: does the index rise
  0.25% before it falls 0.25%, by 15:20?
  - C1 "a put that does not rise marks a false breakdown": up-first share with factor 3 vs without (two-proportion z);
  - C2 "put OI building at support confirms it": up-first share with factor 4 vs without;
  - C3 "Rs 10,000-15,000 is easy on such moves": the share of V1 / V5 trades netting Rs 10,000 or more;
  - C4 "both together reverse it": up-first share with both factors vs with neither.
- **Gates (R130's, unchanged).** A variant passes only if all hold:
  - design: >= 300 trades; average net > 0 at 1x and 2x slippage; positive in >= 3 of the 4 folds; BH q <= 0.10 over
    the 6 variants vs twins;
  - holdout (read once): >= 50 trades; average net > 0; win rate >= 65% (Boss's 65% rule, 28 Oct);
  - account (design + holdout, 1 lot): max drawdown above -Rs 40,000 and no calendar month below -Rs 13,000.
- **If one passes,** a paper arm is proposed (not built). Otherwise the write-up says plainly that it fails.

## 4. Outputs

- Code and tests: `research/hunt/r145_trap_divergence/` (`r145.py`, `test_r145.py`, with a look-ahead guard: a setup
  found on full data is found identically when every minute after its entry minute is removed, and factors 3 and 4 read
  no option minute after e).
- Results: `results/design.json`, then `results/holdout.json` (the one read).
- Write-up: `research/HUNT_R145_TRAP_DIVERGENCE.md`; entries `research/experiments/entries/r145-*.yaml` (6).

## 5. Not tested here

The "Rs 1 to Rs 52" lottery option. R117 and R118 already measured it on every expiry option of 2021-26 (cheap options
are overpriced; Rs 1-3 options reach 10x 2.3% of the time and 88% expire near zero; no profit lock beat holding in all
three periods). R145 tests the trading rule, not the lottery ticket.
