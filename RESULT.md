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

(Ideas 8 and 10: see below once merged.)

## Safety

- Nothing new skips or resizes a live trade. The coordinator only orders reads and sends; it is off (shadow) by
  default, and even on it lets every exit through after a short bounded wait. The account stop rule stays in shadow.
- The order path reads memory only (the switch is read once in the background at start): OrderPathRound2Test and the
  new `anExitAndItsSettledReadReadNothingFromTheSettingsVault` stay at 0 vault reads. Diagnostics lines are written on
  the diary's own thread.
- Open positions first: the MCX look only adds a stops-and-targets look after the close; nothing waits in shadow.

## Tests

(filled in below)

## Review

(filled in below)

## Next: ranked ideas for round 3

(filled in below)
