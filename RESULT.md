# Boss's decisions on the app and arms audits (25 Oct 2026)

Boss said: "Do whatever you think better for application". This branch (`claude/boss-decisions`) puts the 13 decisions in.

- **Paper:** each change acts now. It is logged once in the Autopilot change timeline as Boss's decision, and one tap
  undoes it. Undoing a risk limit asks the app PIN, as loosening the paper loss limit does.
- **Live:** nothing new acts. Each new live rule only writes what it would have done ("shadow"). Where a rule has a live
  switch, it is off by default.
- **Always:** open positions keep their exits, and Boss's own orders are never refused.

## What changed, decision by decision

| # | Decision | Paper | Live |
|---|---|---|---|
| 1 | Account stop rule (−₹40k from the best, −₹13k a calendar month) | **On.** New automatic paper entries are refused once it is reached, with the reason. It counts from the day it came on. Once reached, it holds until Boss restarts the count with the PIN (Bot settings → Risk rules). MCX arms and scheduled strategy starts ask it too. | Unchanged: the existing rule, in shadow behind its own switch (off). |
| 2 | 2% risk cap per trade (risk to the stop ≤ 2% of the paper account) | **On.** Too many lots are sized down; if one lot is already over 2%, the entry is skipped. Each case is logged. The stop is the order's own, else the arm's known stop (15% for most arms, 25% Hero ticket, 80% FT3, the whole premium for Night). | Shadow, switch off. If switched on, it refuses the whole order (it never resizes inside the door). |
| 3 | h14 capped limit and spread check | **On for Liquidity.** It buys with a LIMIT at the signal's price +0.5% and waits up to 3 minutes, never moving the price. If the price never comes back under the cap, the entry is let go. These trades are labelled (`capped`) and still count in the plain-rules record (the rules are the same; only the order type differs). **Spread check:** shadow (logged only). | Capped limit: logged only (what the cap would have been), no switch. Changing the live order type is a change to the live order path, so it needs its own reviewed round. Spread check: logged only. |
| 4 | Faster stop checks while prices are quiet | **On everywhere.** While a held position has no fresh price from Zerodha's stream (with a Zerodha login), the stops are checked every 5 s instead of 15 s. Each such check waits at most 3 s for the arms' exits. The gaps between checks are measured, and the diagnostics show "Quiet-stream stop checks …". | Same (it only adds checks). |
| 5 | Opposite bets between arms on one index | **On.** If another arm already holds the other way on that index, a later arm's entry is refused and kept as a would-be entry. One arm's own two sides are left to that arm's rules. | Shadow, switch off. |
| 6 | Weekly loss stop | **On, ₹20,000 a week** (Monday to Sunday, after charges). No new paper entries for the rest of the week. Changed on the Account guard card or by voice ("set paper weekly loss stop to …"). Loosening it asks the PIN in both places. | Shadow at ₹20,000 (its own limit), switch off. |
| 7 | IV at entry | Recorded for every automatic paper entry: immediate fills, and resting orders when they fill. Record only. | Recorded for the bots' live entries too, from the Zerodha symbol. |
| 8 | Liquidity skips its index's expiry day | **On.** No paper entry on that index's own expiry day; it is logged, and one tap undoes it. | A live entry that day is only logged. |
| 9 | Hero ₹1,000 ticket trail from the fill | **On.** A book still on the old "after 2×" placeholder moves once to "from the fill". A deliberate "after 3×" stays as it is. New installs start at "from the fill". Logged, one tap back. Note: the trend-turn exit was tested only with the trail starting after 2×, so on these tickets it now only takes notes. | — (paper only) |
| 10 | R72's kept place | The cap now keeps R72's place only when R72 has a break due on that side, or when R72 has not looked at the just-closed bar yet. Before, it kept the place on every bar close. | — |
| 11 | NG evening breakout | **Switched off once**, as research R53 and R83 advise. Logged, and one tap switches it back on. An open position still closes by its own exits. | — (paper only) |
| 12 | Night (R3) while hidden | At 15:20 it still records the day's OI build-up, so its history stays current. It buys nothing while hidden. | — |
| 13 | NSE Muhurat, Sunday 8 Nov 2026 | Listed as a special session (18:00–19:00, the window in MCX's circular; NSE's circular gives the exact minutes). It is never a regular trading day for the arms, even if added by hand. It is shown on the Market holidays card. | — |

**Also:**
- **Bot settings:** a new "Risk rules" card lists every switch and today's counts.
- **What's New:** a new entry at the top.
- **Diagnostics:** new lines for each rule, the IV record and the quiet-stream gaps.
- **Research:** `research/APP_AUDIT_BOOKS.md` has a short "Boss's decisions" section.

## Tests added

- **ira-core:**
  - `BossRiskTest` (18 tests): the weekly stop, the 2% cap, opposite bets, the spread check, the cap's tick rounding, the IV solve, Zerodha symbols, and the timeline entries.
  - `StopLaneTickerTest` (+4 tests): the 5 s quiet clock, the gap measure, an unreadable quiet signal counted as quiet, and the day reset.
  - Settings voice phrases.
  - The What's New entry.
- **engine:**
  - `McxEveParkTest`.
  - `BreadthDonchianArmTest.dueSides`.
  - `NseHoursTest` (Muhurat).
  - Hero trail default in `HeroTicketRulesTest`, `HeroTicketTalkTest` and `TrendWatchTest`.
- **app (Robolectric / Compose):**
  - `BossRiskHostTest`: the paper gate and the live door, each rule with edge cases (empty days, exits, Boss's own order, an entry that may go live, PIN), and the IV record.
  - `LiquidityBossRulesTest`: the expiry-day skip; the capped limit filled, waiting, let go and filled within 3 minutes.
  - `McxEveParkMigrationTest`.
  - `NightHiddenHistoryTest`.
  - `BossDecisionsAppTest`: the quiet clock's inputs, Muhurat, the Autopilot Undo with and without the PIN, and the watch pass.
  - `BossRiskCardTest` (Compose).
  - `ControlPagesTest`: the weekly row asks the PIN to loosen; the Muhurat line.
  - `IraKillSwitchTest`: voice loosening asks the PIN.
  - Updated: Hero ticket, R72 kept place, MCX morning park.

CI: see the bottom of this file.

## What I could not do, and why

- **Live capped limit:** only logged; it has no switch. Making live orders capped LIMITs changes the live order path itself, which CLAUDE.md treats as needing its own round. The audit said the same for E4.
- **Quiet stop checks:** they start every 5 s, but one check that is itself slow (a slow Zerodha or Upstox read) can still stretch a gap. The new diagnostics line counts every gap over 6 s. Making each read faster (Zerodha first, a time limit per read; audit H4) changes the stop path and was not part of this decision.
- **Account stop paper copy:** it counts from the day it came on (25 Oct), not from 13 Oct. Counting back would have stopped every paper arm on the 26 Oct decision day, on figures from before the rule existed. Boss can see the figure on the card.
- **R72's kept place:** if another arm's pass reaches the cap before R72 has looked at the just-closed bar, the place is still kept for that moment, as before. R72 normally looks within seconds.
- **Data Brain research tests:** 6 tests fail and 3 error in this container, from library versions here. I did not touch `research/brain/`. The `FEATURES.md` catalog check passes.

## CI

All checks green on `ae62ba9`: Engine parity with the PC (engine + ira-core tests and engine coverage), PC harness tests,
App tests 0/4, 1/4, 2/4 and 3/4 (JVM, Robolectric, Compose UI), and Build APK. The research workflow's checks were green on
`1f6f382` (this branch's earlier push). The emulator workflow runs only on the main development branch, so it did not run
here.

- **The first push failed two of the app-test shards** (45 tests). My new step that records a live entry's implied volatility
  could download the contract list, and the tests forbid any network access. It now reads only the list already saved,
  fixed in `ae62ba9`.
- **A separate reviewer read the whole diff, and its findings were fixed in `1f6f382`:**
  - the MCX paper loss limit's one-time move from ₹6,000 to ₹10,000 had been attached to the new weekly setting;
  - entries that the 2% cap sized down were left out of the fill record;
  - when R72 had not yet looked at a bar, other arms could take its place;
  - the 3-minute capped-limit wait now ends if the app goes Live or entry hours end;
  - the live weekly stop now has its own limit;
  - missing locks around the account stop rule's figures;
  - last week's "reached" could still hold on a Monday;
  - the trend-exit note on the Hero ticket.
