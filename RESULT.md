# Enhancement round 2: RESULT (20 Oct 2026)

Branch `claude/enhance-r2` (based on main `c8daba1`: the deep-review fixes and round 1). Boss, 20 Oct: "run a loop for
enhancement and adding new features in the application." The main item is round 1's idea 1 (one exit coordinator per
contract), then ideas 3, 12, 6, 8 and 10.

## What was added (plain words)

1. **Exits take turns on one contract (idea 1, the main item).** Every exit the app sends (an arm's, ORB and Liquidity's,
   Pine's, a strategy's, a protection's, the 15:05 expiry square-off, MCX's expiry exit, and your own from the Trade
   screen) is now noted until Zerodha's order book shows it. Two automatic exits on the same contract no longer read
   Zerodha's books at the same moment: the second waits its turn, then reads again, so it sees the first and sells
   nothing more. A read that is missing an exit sent a moment ago (Zerodha's book can lag by a second) is read again.
   It never changes a size and never refuses an exit: a turn is waited for at most 4 seconds and a lagging book read
   again for under 2 seconds; after that the exit goes exactly as before, and it is logged. Your own exits never wait.
   **It starts in shadow** (it only counts and logs what it would have done) until you switch it on in
   Bot settings → "One exit at a time". The daily diagnostics have one line for it.
   - Found on the way: Pine's live exit read the positions before the orders (the order that let a fill between the two
     reads double a sell). It now reads the orders first and the positions again, inside its turn. The protections'
     morning re-rest and overnight GTT also read what is held again after the books.
2. **MCX carried stops overnight (idea 3).** MCX's day stop and target end at its 23:30 close (23:55 in US winter time),
   long after NSE's evening look. Now, while a carried MCX protection waits for its overnight GTT, the watch keeps one
   small look for half an hour after MCX's close (past midnight too), and the GTT rests then, the same way as NSE's. At
   MCX's 09:00 open the GTT is taken out first and the day's orders go back.
3. **Whole-day test of an arm's GTT beside your protection (idea 12).** No code change was needed: the test plays a whole
   day on one contract (fills, a part fill, the GTT firing, your own exit at the same moment as the protection's look)
   and checks after every look that the exits that could fire together never add up to more than is held. The arm yields
   to your protection: it takes its GTT out when your stop and target already cover everything.
4. **Account stop rule net of charges (idea 6).** Each day's figure is now Zerodha's day P&L less the day's charges
   (Zerodha's exact contract note when the app has it, else the estimate), the same net figure the arms' own rule uses.
   Still in shadow until you switch it on.
5. **Candles page (idea 8).** An "as of HH:mm" line says when the candle agent last read (and says plainly when nothing was
   read yet, when the read is late while a market is open, or when markets are closed). Chips (All, NIFTY, BANKNIFTY, ...)
   filter the candles, the table, the lessons and the notes. A tap on the Candle agent's What's New card opens
   Research → Candles. Only 5- and 15-minute candles become lessons for Jarvis; a 1-minute candle is explained only (and
   says so), and an old 1-minute lesson is let go.
6. **Jarvis's Sunday review (idea 10, TODO I15).** On Sundays at 18:00 (under the existing "Weekly review" switch) Jarvis
   says a review of the week in about two minutes: paper and live in separate parts, never added together; trades, wins,
   net after charges, the best and worst arm with their trade counts, the biggest cost, and one thing to look at (words
   only, nothing changed). "No live trades this week" is said plainly; a book that could not be read is said as unread.
   **Never on a locked phone:** then only a "Sunday review ready" notice with no figure, and asking for it says to unlock.
   You can also ask "Jarvis, Sunday review".

## Safety

- Nothing new skips or resizes a live trade. The coordinator only orders reads and sends; it is off (shadow) by
  default, and even on it lets every exit through after a short bounded wait. The account stop rule stays in shadow.
- The order path reads memory only (the switch is read once in the background at start): OrderPathRound2Test and the
  new `anExitAndItsSettledReadReadNothingFromTheSettingsVault` stay at 0 vault reads. Diagnostics lines are written on
  the diary's own thread.
- Open positions first: the MCX look only adds a stops-and-targets look after the close (never during NSE's session, and
  MCX's own pass goes first when it is due); nothing waits in shadow. The Sunday review reuses the Sunday 18:00 report's
  worker (no new alarm) and a test shows the position watch's alarms are untouched.
- Nothing about the account is spoken on a locked phone (the Sunday review; tested).

## Tests

- **New ira-core tests:** ExitDeskTest (30: the registry, per-read judgement, lost replies, the turns, the timeout race, a
  let-through sender's nested turn, 200 racing senders), StopCadenceTest (MCX's close past midnight), AccountStopTest (net
  of charges), CandlePageTest (14), SundayReviewTest (20), WhatsNewChangelogTest (the new entry; indices +1), BatteryRestTest
  (+1), CollisionTest and CoverageTest (the Sunday question routed).
- **New app tests:**
  - ExitCoordinatorTest (21), in shadow and on: the door's notes, refused and lost replies, GTTs, a restart, the book trailing
    an order, an exit that never shows (let through in under 2 s), Boss's exit not yet in the book, a part fill, two senders
    at once, a stuck sender (let through), another worker's read during the pass, a fill between the reads, a fired GTT,
    Boss's exit never waiting, no vault reads on the order path, the switch saved.
  - ArmGttProtectionDayTest (3): a whole day on one contract, the coordinator on and in shadow, plus a GTT firing beside
    Boss's stop and a part-filled stop.
  - ProtectionsGttTest (MCX after its close and at its open), EnhanceRound1Test (net of charges), EnhanceRound2CardsTest
    (Compose: the card), CandleAgentPageTest and RootNavigationTest (the chips, the "as of" line, the What's New tap),
    IraSundayReviewTest (locked phone, unread lock, on demand, Sunday only, once, the watch's alarms untouched).
- **Checks run locally:** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` green
  (3,800+ tests). App tests for everything touched green with CI's flags (433 in one run: every exit sender, the order and
  tick path guards, OrderPathRound2Test, LiveOrderPathsE2ETest, the watch and job tests), and the Sunday review's in the
  Jarvis build too. (This container needed the Android SDK installed and Google's mirror of Maven Central; not committed.)
- **CI:** see below.

## Review

Three separate reviewer agents read the diff (the order and exit path; the MCX look, the account rule, the UI and tests; the
candle page and the Sunday review). Every finding was checked against the code; the confirmed ones were fixed, with tests:

- **The coordinator judged a read by the shared registry, not by that read** (high): another worker's later read showing an
  exit made an older read look whole, and the expiry pass could sell beside an arm's exit. Now each read is judged on its own
  rows, and the expiry and MCX passes check their own read.
- **A turn could stay locked for good** if the 4 s wait timed out just as the lock was taken. Fixed (and a 200-sender test).
- **A lost reply could be taken for an earlier identical exit.** Now only a row placed within 5 s of the send whose id is no
  other exit's.
- A let-through sender waited again for its own nested turn; a deleted GTT was looked for; an Error left an exit "sending";
  the start's late read could undo Boss's toggle: all fixed.
- **MCX's after-close look missed the close minute itself**, so the watch would have ended first (high). It is now due from
  that minute; it never runs while NSE trades, and MCX's own pass goes first in the evening branch.
- Pine: a failed read after its stop came out now puts the stop back. The MCX test's day avoids evening-only sessions.
- Sunday review: phrases like "read me my weekly review" or "Sunday report on BankNifty" go to their own answers again; a
  book that fails to read is said as unread; trades on your paper trail count; quiet hours are checked before speaking.
- One idea I tried and **reverted**: making your protection cover less when an arm's GTT rests beside it. The arms already
  step back for your protection (they take their GTT out), so that would have left lots with no stop at Zerodha.
- Not changed, noted: the coordinator turned on can hold a stop exit a few seconds (a 4 s turn plus re-reads); turning it
  on needs no PIN (it only adds waits, never refuses). The account rule's days saved before today are before charges.

## Boss: two switches to consider

- **Bot settings → One exit at a time.** Watch its diagnostics line for a few days in shadow ("two exits on one contract at
  once", "a book read missing an exit just sent"); if those counts are not zero, switch it on.
- The account stop rule is still in shadow (Bot settings → Account stop rule).

## Next: ranked ideas for round 3

1. **Positions-lag guard:** an exit that filled can show COMPLETE in the orders before the positions move; for the
   coordinator's own recent fills, read the positions once more before a second sell. High; low.
2. **Payout-aware drawdown** for the account guard's kill switch (Kite's utilised payout), with Boss's confirmation.
3. **Paper positions as an explicit stream owner** instead of 3-minute touches (idea 7).
4. **The protections' stop and target as one OCO at Zerodha** (a two-leg GTT intraday too), so a dead phone never leaves
   both resting after one fills.
5. **A real two-book twin test** (idea 11): both Liquidity books signal the same option on one test day.
6. **Clipboard diagnostics marked sensitive** (Android 13+) and the Liquidity live stop rule's state kept (idea 9).
7. **Sunday review catch-up:** if the phone was off at 18:00, say it at the next unlocked moment that evening.
8. **Encrypted diagnostics** (waits for Boss's choice).
