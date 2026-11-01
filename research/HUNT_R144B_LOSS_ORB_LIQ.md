# R144b: why Liquidity 15+5, the ORB family and Solo 2.0 lose money, and which trades to skip

1 Nov 2026. Research only: nothing in the app changes. One of four parallel studies (R144a-d). Pre-registration:
`research/hunt/r144b_loss_orb_liq/PREREG.md` (commit dc86985, pushed before any code or number).

## Outcome first: NOT RUN YET. No new numbers; the method is ready and tested

- **Blocked on data.** This study needs the Dhan data lake (option and index minutes, Dec 2022 - Oct 2026). The cloud
  session that wrote R144b did not have it:
  - no rclone and no copy of `gdrive:IraAlgo-data-lake`;
  - no R3, R51, R143 or R57 stores in the cache;
  - the GitHub `research-data` release holds only older single-index files, not the lake.
  - `r144b.py status` records this in `results/status.json`.
- **So not one trade of these six arms was rebuilt, and no filter was chosen or tested.** I have not guessed any.
  `results/pvalues.json` lists no tests, so the coordinator's BH across R144a-d has nothing to add from R144b.
- **What is done:**
  - the pre-registration (arms, engines, periods, every feature, the anatomy, the fixed filter-choice rule, the
    gates);
  - the code: `r144b_lib.py` (pure) and `r144b.py` (engine adapters and runs);
  - 11 tests, all passing:
    - the look-ahead guard: scrambling every minute from entry on changes no feature;
    - an end-to-end run on synthetic trades with a planted losing pattern: it is found, frozen on design and passes
      on the test periods;
    - the final period can be read only once, and test periods cannot be priced before the filters are frozen.
- **To finish R144b,** run in a session that has the lake (as R140-R143 did):

  ```
  IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r144b_loss_orb_liq/r144b.py collect {orb|liq|solo} design
  python research/hunt/r144b_loss_orb_liq/r144b.py design          # anatomy, filters frozen
  IRAALGO_LAKE=... python .../r144b.py collect {orb|liq|solo} final ; ... collect {orb|liq|solo} unseen
  python research/hunt/r144b_loss_orb_liq/r144b.py test            # read once; writes test.json and pvalues.json
  ```

  - Run one `collect` per process, because R57's settings re-point the shared R51 settings.
  - The first run must pass the parity checks (PREREG section 1) before any table is trusted. The adapters have never
    run against the real stores.

## What we already know about these arms' losses (earlier studies, not new R144b numbers)

### Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5)

From R141 (design 881 trades, final 357; 1 lot, after charges):
- **Loser rate:** 56% in design, 63% in the final.
- **Shape of the losses:** many small ones (about -Rs 1,000 each). Exits within 15 minutes lose 63-69% of the time.
  The money comes from the trades held 1-3 hours and longer.
- **Losses come in bunches:** 67-69% of the losers fall on index-days where every Liquidity trade lost.
- **One steady sign at entry:** a gap against the trade. R140 tested it as a filter and it failed in the final
  (p 0.48).
- **Earlier exit studies:** R81 found a no-progress exit the worst idea for Liquidity, because its slow starters
  include its runners.

**Still open for R144b:**
- the unseen years 2022-24 (R143's store; R143 rebuilt only R72, R51 and R52 there);
- the volume, OI, IV, skew and order-flow-proxy features, which R141 did not have;
- the per-index split.

### ORB, ORB Fresh, ORB Sweep, Range Fade (BANKNIFTY, the app's index)

From R3 (2021-2026 lake):
- **All four lost money in every year, 0 of 6 years in profit.** Design BANKNIFTY + NIFTY:
  - ORB: -Rs 2.23 lakh on 2,489 trades;
  - ORB Fresh: -Rs 2.24 lakh on 2,489;
  - ORB Sweep: -Rs 5.06 lakh on 3,156;
  - Range Fade: -Rs 5.11 lakh on 3,512.
- **About 36% of ORB trades win.**
- **The flaw is the entry:** ORB buys after the break has mostly happened. Even on days that trended its way it lost
  Rs 52 a trade (1,094 trades).
- **Nothing known at entry helped:** in R3's thirds of VIX, VIX change, gap, range width, time and expiry, even the best
  third lost.
- **All 16 enhanced versions failed.** The arms were paused on 13 Oct.

**What this means for R144b's gates:** gate 3 requires the arm to still make money after a skip filter. A skip
filter on an arm that loses in every period can pass it only if skipping alone makes the arm profitable. R3's
thirds suggest this is unlikely. R144b will still report each arm's anatomy, with the new volume, OI and IV features
and the unseen years, as pre-registered.

### Solo 2.0 (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY)

From R57:
- **Final:** 128 trades, +Rs 99,172.
- **72% of that profit is MIDCPNIFTY** (82 trades). Without MIDCPNIFTY it is +Rs 27,586 on 46 trades.
- **92 of the 128 trades are setup S3**, whose exit was chosen on the same window.
- **Its 15-minute ORB setup (S4) lost on its own** (459 trades, -Rs 1.14 lakh).
- **Its losing trades have never been taken apart.**

**R144b's design for Solo 2.0:**
- It uses the app's frozen model (`A2.json`, threshold 0.4871), not R57's monthly refits.
- Its design months are therefore in-sample for the model and will look better than they were.
- Its unseen years (2022-24) are fully out of sample, but they need R57's store rebuilt from Sep 2022 in its own
  folder (`r144b_old`), which the adapter does.

## Per-arm sections

To be filled by the run: loser rate, money lost by exit type, bunching, the worst 20 trades, the loser rate and
average net by fifth of each of the 31 features per period, steady slices, up to 3 filters, and their tests with the
win rate before and after (Boss's 65% rule).

| arm | rebuilt | trades (unseen / design / final) | loser rate | steady losing signs | filters chosen | passed |
|---|---|---|---|---|---|---|
| Liquidity 15+5 | not yet (no lake) | - | - | - | - | - |
| ORB | not yet (no lake) | - | - | - | - | - |
| ORB Fresh | not yet (no lake) | - | - | - | - | - |
| ORB Sweep | not yet (no lake) | - | - | - | - | - |
| Range Fade | not yet (no lake) | - | - | - | - | - |
| Solo 2.0 | not yet (no lake) | - | - | - | - | - |

## Features the lake cannot give (stated now, so nobody reads them as tested)

- **Bid-ask spread and depth:** the lake has no quotes. The liquidity proxies are the entry premium, the zero-volume
  minutes in the last 15, and the contract's OI.
- **True order flow:** the lake has no order book. Two proxies, labelled as such:
  - the option's tick-direction volume delta;
  - its OI-price quadrant.
- **Index VWAP:** the index has no volume, so TWAP is used instead.
- **Not in the stores used:** futures volume, the futures basis, breadth and the US overnight move. R101 / R141
  already tried breadth on Liquidity.
- **Option band:** R3's store keeps only 4 strikes either side of the 09:20 ATM. A Liquidity or Solo trade whose
  strike drifted outside it has no option features. The run reports each feature's coverage per period.

## Proposals

None yet: no filter has been tested. Any filter that passes will be proposed (not built) as a paper switch, off by
default and logged in the Autopilot timeline. Live would use it only after a shadow phase (CLAUDE.md).

## Files

- `research/hunt/r144b_loss_orb_liq/`:
  - `PREREG.md`, `r144b_lib.py`, `r144b.py`, `test_r144b.py`;
  - `results/status.json` (inputs found: none);
  - `results/pvalues.json` (no tests run).
- Entries:
  - `research/experiments/entries/r144b-anatomy.yaml` (not-a-test, blocked);
  - `research/experiments/entries/r144b-filters.yaml` (not-a-test, blocked).
