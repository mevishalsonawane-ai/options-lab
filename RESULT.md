# 22 Oct diagnostics: why some arms did not trigger, the Hero ticket switch, the night kills

Boss, the short version:

| What you saw | What it was | What I did |
|---|---|---|
| (Boss, later on 22 Oct) "No Breadth Donchian ran today, which is our primary arm" | R72 did decide at 09:21:24, but every break became a would-be trade, so nothing was bought. Your uploaded 22 Oct file confirms which trades held the two put places at 09:21: **Liquidity 5m MIDCPNIFTY's put (bought 09:20:02)** and **R52's FINNIFTY put (bought 09:21:24, in the same pass as R72, just before it)**. Two of our own faults made R72 lose both places: it decided only at 09:21:24 instead of 09:20:03 (its indices' 09:19 minute was not ready), and inside the pass it decided **last**, after R51, R52 and Solo 2.0. Its FINNIFTY put was the very option R52 had bought. | Fixed. **R72 now decides first** in every pass, so it takes the cap's places and its options before R51, R52 and Solo 2.0. On a bar R72 and R51 both break, R72 buys and R51 keeps the would-be (before, the other way round). The cap is unchanged (2 a side). It is shared with the other arms (Liquidity, ORB) on a first-come basis, so an arm outside this group that already holds the places still wins. Giving R72 a reserved place across all arms is a bigger change: say if you want it. |
| R72 FINNIFTY "09:20 put break missed (the fill window passed)" | A real signal **lost to a clash between two of our own arms**. R52 bought FINNIFTY27OCT2624550PE at 09:21:24, the very put R72's break wanted. R72 was refused ("held by R52"), retried until 09:22, and was then labelled "missed". | Fixed. The trade is now kept as a **would-be trade** that names the arm holding the option. Nothing is bought, it is never sent live, and it closes by R72's own exits, so R72's record still counts the signal. |
| Every R72 entry judged at 09:21:24, not 09:20:03 | Our own slowness. R72 needs four of five indices breaking. FINNIFTY, MIDCPNIFTY and SENSEX were only followed on the live stream from the first check (09:20:03), so their 09:19 minute was never whole at 09:20:03. The feed's copy came later, and breadth could only be judged at the next minute's check. | Fixed. The passed arms now follow their indices on the stream from 09:16 to 15:12, so each bar's last minute is whole when the bar closes. SENSEX was never followed at all (the app could not match it to its stream token). That is fixed too. |
| 21 Oct "13:45 put break missed" (R51 and R72; BANKNIFTY, FINNIFTY, SENSEX) and 21 Oct 09:20 FINNIFTY | Not a cap refusal. A cap refusal is already kept as a would-be trade labelled "refused by the same-direction cap". These breaks first showed after their two-minute window. The most likely cause is the same index minute arriving late, but the diagnostics did not record which reason applied. | Fixed the labelling. A passed window now says exactly why (below), so the next diagnostics will show it. |
| "Solo 2.0: S3 FINNIFTY passed but could not be bought: … held by R52 … nothing more today" | The rule is intended: Solo 2.0 (R57) takes the day's first passing candidate and nothing more. A pick it cannot buy also ends its day. | It now also becomes a would-be trade naming R52, so its record counts the pick. The "one pick a day" rule is unchanged. |
| "13:02:23 Hero ₹1,000 ticket: switched off" | **The app did not switch it off by itself.** Only two things in the code can do that: its own switch on Home, and an Undo of its entry in Autopilot's change log (which also writes an "[autopilot] undone by you" line). The Trend watch's sale, and that sale's Undo, never touch the arm's switch. A test now proves it stays on after both. | The diary now says who switched it: "by you, from its row on Home" or "by the Undo in Autopilot's change log". If your 22 Oct diagnostics has no "[autopilot] undone by you: Hero ₹1,000 ticket's switch" line near 13:02, it was the switch on its row (a tap). Switch it back on from its row if you want it running. |
| Night kills 02:43 and 02:51, "excessive binder traffic during cached" | The worst source I found: the night **strategy forge** read the battery and battery-saver state on **every one of about 200 simulated "twin" runs per idea, for up to 20 ideas**. That is thousands of calls into Android a night, at full CPU. It runs after 18:00 (the 21 Oct heat), and again after midnight while the phone charges. A killed run is redone on the next hourly wake, which fits the 02:43 → 02:51 repeat. | Fixed. It now asks at most every 5 s. WorkManager's stop is still seen at once. |
| (found while checking) | After midnight on a trading day, a watch still running (for example from an MCX evening look past midnight) counted NSE as open and could loop until morning. | Fixed. The running watch's NSE hours now start at 09:00, which is before its 09:14 start. The MCX terms are untouched. |

The open-position watch is untouched: no stop, target, trail, lock, square-off or guard check is slowed, and MCX positions keep their full cadence. Nothing new touches disk, settings or the network on the order or tick path. All changes are paper-only or words-only. Live behaviour is unchanged: the clash case was already refused live, and a would-be trade is never sent live.

## The uploaded diagnostics never showed R72

The 22 Oct file cut every line at 300 characters, and the passed arms shared one long line. So R72, R52 and Solo 2.0 (and every index's status) were cut off, and R72 could not be seen in the file at all. Fixed: in the file, each passed arm now has its own line, R72 first, and each index's status and R72's comparison with R51 have short lines of their own.

What else your 22 Oct file shows:

- No errors, no stream drops, no health incidents and no stop-check gaps today.
- The Hero ticket is off. The file cannot tell who switched it off: the Undo line goes to the phone's own diary, which is not uploaded. From now on the diary line says who did it.

## What a passed window now says

Before, a break whose 2-minute fill window passed said only "missed (the fill window passed)". Now it says one of:

- **"09:20 put break not bought: \<reason> (tried until 09:22, …)"**: it was tried inside the window and refused. Examples: the kill switch, the day lock, the account guard, no price, not filled.
- **"… missed: no entry check ran in its fill window (09:20-09:22)"**: our own pace.
- **"… missed: the index's 09:19 minute had not reached the phone in its fill window (…; the last check, 09:22:40, had minutes to 09:18)"**: the feed was late. For R72, which reads all five indices, the late index is named (for example "SENSEX's 09:19 minute …"). A read that failed says "could not read it".
- **"… missed: the bar read in its fill window did not break; the index's minutes were corrected after"**: the feed changed the bar after the window.

One side effect, the same as for the existing twin and cap would-be trades: while a would-be trade is open, it counts as that arm's position. So a later same-side break on that index waits for it to close. Paper only, and it can only skip.

Nothing is ever bought after its window. That is the research's rule (fill at the bar's close or within 2 minutes), and it is unchanged.

## Correct behaviour (no change)

- The same-direction cap (2 puts) refusing R72's 3 puts and Liquidity 15m BANKNIFTY at 09:21-09:30. These are labelled as cap refusals and kept as would-be trades.
- R52 inside value on 3 indices, and the arms you switched off.
- Solo 2.0's one pick a day.

## Other things in the diagnostics that look wrong (not fixed here)

These need their own look, or your call:

- **21 Oct "stop check: the stops were looked at N s or more apart while a position was held" (2 times).** The 15 s stop cadence slipped twice while holding. It fits the hot phone and the 15% battery around 11:28-13:02, plus 16 "worker overruns". Worth a dedicated check under the open-positions-first rule.
- **21 Oct 09:30 Liquidity 5m: "order refused: no price to fill at; the order was cancelled"** (BANKNIFTY 55700 CE). The option had no price at the fill.
- **"your api secret was [hidden], or set up the keys again" (3 times) and "not the right PIN" (once)** on 21 Oct. A key may need setting up again.
- **More night load from the background audit (larger changes, left for a follow-up):**
  - The hourly night jobs (strategy discovery, GitHub sync, study) each cold-start the app. Every cold start re-arms about 14 alarms, reads Android's exit reasons twice, and decrypts many vault files through the Android keystore.
  - The nightly GitHub backup decrypts every vault file.
  - The night CPU budgets live in memory only, so a kill resets them.
  - A flush on every background trim re-encrypts settings just as Android freezes the app.
  - These add to the night binder traffic and heat. Merging the night jobs into one, and putting off heavy start-up work when the app is woken in the background, are the next steps if the kills continue.
- 14 live-stream drops on 21 Oct (11 "no data from Zerodha", 3 network changes), mostly 11:54-12:38 and 20:23-20:28, around the heat and low-battery spells.

## Second round (Boss, 22 Oct: "reserve R72's place and do all fixes")

| What | What was wrong | What changed |
|---|---|---|
| **R72's reserved place** | The same-direction limit (2 a side) went to whichever arm asked first. On 22 Oct Liquidity's MIDCPNIFTY put (09:20:02) and R52's FINNIFTY put took both put places. | While R72 is on, during the first 3 minutes after each 5-minute bar closes (09:20-15:12), another arm may take only the places before the last. The last place each way is kept for R72, and the refusal says so ("the last put place is kept for R72 Breadth Donchian at its bar close"). Outside those minutes the limit is as before. It only ever refuses, so it lowers risk. Paper only. |
| **Stop checks that never slip** | The 15-second stop check ran inside the watch loop, after the full pass (up to 20 s), after each bar-close entry check (up to 45 s), and after an "anything held?" check that waited on ORB's lock. On 21 Oct the stops were looked at too far apart twice. | The stops now have their own 15-second clock, beside everything else, and nothing can hold it up. It counts each 15 s from the last look's **start**. No battery, saver, heat or memory logic can stretch it. Every step runs under its own book's lock, so a position is never sold twice. The arms' exits are waited on 8 s (was 20 s). MCX's pass is now timed from its start. |
| **Liquidity "no price to fill at"** (21 Oct 09:30) | The arm read the option's price but did not hand it to the paper fill. The fill looked again by itself, found nothing usable, and the order was cancelled with no second try. | Liquidity and ORB now read the price when the option is chosen and use it for the fill. If there is no price, they try once more (one more read, or one more order if the first was cancelled), making every check again at the new price. If that also fails, the message names the option. A retried trade is labelled and not sent to the live path. The retry only happens when the ORB/Liquidity book holds no open position, because its stop checks share the same lock. |
| **Night kills** (02:43, 02:51) | Besides the strategy forge (fixed earlier), every hourly night job started the whole app. Each start re-armed about 12 alarms (four calls into Android each), read Android's exit records twice, cleared an old Dhan store, built the shared knowledge and drew the widget twice. The night CPU budgets were kept in memory only, so each kill reset them. | When the app is started in the background, the positions-first start-up steps run as before. The heavy non-trading work waits until the screen or the market watch starts, or until a night job asks for it. Alarms are re-armed only if their time changed or Android lost them. The 09:14 watch alarm and the dead-man check are still armed at every start. Exit records are read once per process. The old Dhan clean-up runs once per installed app. The night CPU budget and the Candle agent's study day are saved, so a kill can no longer reset them. |
| **"‹redacted›" in the diagnostics** | The phone's diary blanked every run of 24 or more letters and digits as a possible key. That included 24-character option names (MIDCPNIFTY27OCT2613250PE) and long reason codes, which is why Liquidity's afternoon lines read "‹redacted›". | Option and future symbols, and lowercase reason codes, are kept. Anything else that long is still hidden. |
| **Afternoons with no orders** | Not one cause: the 21 Oct paper day lock was reached ("no new entries" for the rest of the day); Liquidity only enters 09:20-14:00; most NSE arms are switched off; after 15:30 only the MCX arms can trade. Two app faults made it worse: R72 missing its places (fixed above) and R72's lines missing from the file (fixed). | Your call: raise the paper day lock if a good morning should not stop R72 for the afternoon. |

Still worth watching:

- CI once had a failing key-wipe test (`RootNavigationTest`) on a diagnostics-only commit. It passed on the next commit with the same code; I will fix it if it fails again.
- After the night change, the shared knowledge a background Jarvis answer reads is empty until the screen or a job opens it.

## Tests

- Engine: `DonchianArmTest.aMissedBreakSaysWhyItsFillWindowPassed` covers every branch: tried, no check, feed late, minutes corrected, the window's edges, and call vs put.
- App (they run in CI):
  - PassedArmsHost: another arm holds the option → a labelled would-be trade, decided once, never "missed", closed by its own stop, nothing bought, nothing at Zerodha. A kill-switch refusal → "not bought: refused …" once the window passes. Feed late → said with the last check's minutes. No check in the window → said. The indices followed from 09:16, never at 09:15, never after 15:12, never with every arm off.
  - HeroTicketArm: the diary says which door switched it. The arm stays on after the Trend watch sells and after that sale's Undo.
  - BackgroundBudget: the forge's budget is asked at most every 5 s, and a "no" is seen within 5 s.
  - Jobs: the running watch's NSE hours start at 09:00, never at midnight. The quarter-hour after the close for an open strategy run is unchanged.
