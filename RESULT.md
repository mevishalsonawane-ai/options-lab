# RESULT: ntfy alerts fit IraThinker's System Feed (26 Oct 2026)

Branch `claude/ntfy-irathinker-fit` (from `claude/android-options-lab-kotlin-xgt1ym`). Follows the compatibility section of
`irathinker-app/REVIEW_2026-10-26.md`. All changes are on the IraAlgo side; IraThinker is unchanged. Paper by default; live only
through the arm's own "Trade live" switch and Boss's PIN, as before.

## What changed

1. **mom60 follows its own exits, only where they are tighter or earlier** (`NtfyParser.signal`, `NtfyRules.sourceExits`, `NtfyArm`).
   - Exit fields read: `premiumStop` (turned into a percent of the payload's `option.premium`, so mom60's 30% applies to IraAlgo's
     real fill; with no premium it stays a premium level), `premiumStopPct` / `stopPct`, `premiumTarget`, `exitBy`, `holdMin` / `holdMinutes`.
   - A field that is present but not understood (a string, ≤ 0, a stop not below the premium, an `exitBy` not after `tsIST`, a
     hold that isn't a whole 1–375 minutes) refuses the signal with the reason. It is never guessed.
   - Risk only lowers. The stop is the higher of IraAlgo's (20% under the fill) and the source's; the target is the nearer one; the
     exit time is the earlier of `exitBy` and fill + hold, and only if it falls before the square-off (15:15, or 15:05 on expiry day).
     mom60's 30% stop is wider than IraAlgo's 20%, so IraAlgo's stays and the record says so. Its 60-minute hold is used.
   - With no exit fields, the exits are exactly as before.
   - The time exit runs in `exitOne`, so it runs in the 15-second stop lane (`manageOnly`) as well as the pass. It is kept in the
     encrypted book (`srcExit`), so after a restart the position comes back with its exit time.
   - A signal whose `exitBy` has already passed when the pass decides is not bought.
   - Labelled in the record: the trade's line says "IraThinker mom60 (a paper signal; not a proven strategy; research R110: about
     breakeven after costs on real options)". The owner tags of all its orders (entry, stop, exit) and the Open card carry
     "IraThinker mom60".
   - `"paper": true` is shown as a label only. Its live path is as before: the arm's own "Trade live" switch (off) and Boss's PIN.
     A first version skipped the live path for paper signals. The reviewer pointed out that this is a new skip of live trades,
     which CLAUDE.md says must start in shadow, so it was taken out. If Boss wants paper signals never to go live, that needs a
     shadow switch, off by default.
2. **SELL_STRANGLE and other sales or multi-leg trades are information.** New `Parsed.Info` and state `information`:
   "information, not traded: IraAlgo only buys options (SELL_STRANGLE: a multi-leg trade)". The same goes for any `SELL_*` / `SHORT_*`
   side, any `legs`, and straddles, spreads and condors. Exits are never information: EXIT / SELL / CLOSE, `_CALL` / `_PUT`, and now
   `SELL_CE`, `EXIT_PE`, `CLOSE_CE` and the like (the BUY_CE spellings) close what the arm holds. `PRICE_ALERT` and `MARKET_FLUSH`
   are information too. They are read once, never wait for a pass and are never ordered. The page shows "ⓘ information" in a neutral
   colour, not the refusal header.
3. **Stock strike off the grid.** Still refused, now with a clearer reason. Example: "not traded: INFY 1130 CE is not listed for
   2026-10-27 - not on the exchange's strike grid (listed either side: 1120 and 1140)". When the payload says the strike is estimated
   (`strikeEstimated: true`, `strikeVerified: false`, or `strikeSource` "estimate" / "guess" / "band"), the reason says so, and a
   traded line says "strike estimated by the sender". Another strike is never put in its place.
4. **Send time.** Checked: IraThinker stamps `tsIST` with `SystemFeed.nowIst()` when it sends (`yyyy-MM-dd'T'HH:mm:ssXXX`, IST). The
   parser reads it with `OffsetDateTime`, and the stale check uses the earlier of it and ntfy's time. The stale rule is unchanged;
   tests pin it with IraThinker's exact format.
5. What's New: a new top entry, "ntfy alerts follow your alert app's own exits". `WhatsNewChangelogTest` indices are shifted by one,
   and `BatteryRestTest`'s 43 is now 44.

Not changed: text messages ("SL 20%" and the like) keep their exits as before. Live stays behind the arm's own switch and the PIN.
Nothing new on the order or tick path touches disk, the network or blocks.

## Tests

- Engine `NtfyIraThinkerTest` (new, 7 tests): the exact mom60, strangle, price-alert and market-flush payload shapes; exit fields read;
  missing fields keep today's exits; bad numbers refused; tighter used and wider kept; hold and `exitBy`; hold past the square-off;
  expiry day 15:05; SELL / SELL_CALL still exits; stock grid and estimate wording; `tsIST` stale rule.
- App `NtfyIraThinkerArmTest` (new, Robolectric, 7 tests):
  - mom60 bought on paper, labelled, never told to the live path, restored after a restart with its exit time, and sold at 10:28 by
    `manageOnly`;
  - a tighter source stop is used and a plain signal still reaches the live path;
  - expiry day with a hold past 15:05 (square-off first);
  - `exitBy` already past, duplicates, stale by `tsIST`, bad numbers, Sunday (market closed);
  - strangle as information, read once;
  - INFY 1130 off the grid, with and without the estimate;
  - the record words.
- `NtfyPageTest`: new Compose test for the neutral information row and the Open card's source rule.
- Local: `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` passed, before and after the
  review fixes. `:app` builds only in CI.

## Review

- Self-review of the full diff.
- A separate reviewer agent. It ran the engine and ira-core tests: all passed, including 86 of 86 for What's New and 12 of 12
  for BatteryRest. Fixed after its review:
  1. Confirmed: two app tests assumed a paper fill of 121, but paper fills pay the half-spread (121.20). The expected stops are
     now worked out from the fill.
  2. The live skip for `"paper": true` was taken out (CLAUDE.md shadow-first rule). It is now a label only.
  3. `SELL_CE` / `SELL_PE` (and the `EXIT_` / `CLOSE_` forms) are exits, not information.
  4. Exit sides skip the multi-leg check, so an exit is never swallowed as information.
  5. The exit and re-placed-stop order tags also carry the source strategy.

## CI

- `97f66db`: the app did not compile. `p.strike` cannot be smart-cast across modules (`NtfyArm.kt:760`, in `:app` only, which
  builds only in CI). Fixed with `p.strike!!`, which is safe because it sits inside the `p.strike != null` check.
- **`3e0914176279943861da243f386568e4103f0172`: all green.** Build APK, App tests 0/4 to 3/4 (JVM, Robolectric, Compose UI), Engine
  parity with the PC and the PC harness tests passed; Publish screenshots was skipped. This RESULT.md commit changes docs only.
