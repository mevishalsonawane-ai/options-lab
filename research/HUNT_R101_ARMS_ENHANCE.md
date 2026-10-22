# R101: can the arms with a real record make more, or win more often, without making less?

22 Oct 2026. Pre-registered in `research/hunt/r101_arms_enhance/PREREG.md` (commit 29c5b67, pushed before any
result); the design read was committed (613b355) before the one final read (`results/FINAL_READ`). Code and tests:
`research/hunt/r101_arms_enhance/` (20 pure tests: look-ahead guards for every feature, the walks against R64's
taking rules, the keep rules, the sizing rule, BH and the gates). Full tables: `results/report.md`.

Boss, 22 Oct: *"find if could enhance it by increasing the profit / winrate"*. Boss asked for the win rate AND the
net side by side, so every row below shows both; a variant that wins more often but makes less is marked as such.

## Answer in one paragraph

**None of the 20 pre-registered variants passed.** Every B0 was reproduced to the rupee first (Liquidity 881 / Rs
2,57,249 and 357 / Rs 11,155; R72 3,071 / Rs 2,16,929 and 1,202 / Rs 1,71,366; R51 4,498 / Rs 9,998 and 1,781 /
Rs 81,845; R52 Rs 2,90,573 and Rs 48,612). Of the 20, only four made MORE than B0 in the final period and none of
them also made more in the design walk-forward, which is what the gates ask; across all 20 tests the best
Benjamini-Hochberg q is 0.40. Two findings are worth Boss's attention even though they fail: (1) **the opening
bar's breaks (the 09:15-09:19 bar, bought at 09:20) are the most valuable part of both Donchian arms**: dropping
them costs R72 Rs 1,49,195 in design and Rs 76,078 in the final, and R51 Rs 87,186 / Rs 97,444; (2) **a VIX-confirmed
Liquidity entry (L2) wins more often in both periods (48% vs 44%; 40% vs 37%) and made Rs 37,912 more in the final
(twin p 0.02), but Rs 54,136 LESS in design and lost in two of the four walk-forward quarters, so it is a higher win
rate that does not reliably make more money.** The arms stay as they are; nothing changes on paper or live.

## What was tested (all signals on the index; the option is only bought)

Entry filters remove candidate signals BEFORE the one-position walk (a removed signal frees its book, exactly as the
app would); B5 is a sizing rule. Features are point-in-time (VIX minutes before the entry row; the 5-minute bar that
ends at the signal bar's last minute; daily data of sessions before the day). The exits are each arm's own.

| id | arm | rule |
|---|---|---|
| L1 BREADTH3 | Liquidity | >= 3 of the 5 indices' 5-minute bars ending at the signal bar's last minute closed the signal's way |
| L2 VIXDIR | Liquidity | a put only with India VIX up >= 1% since the 09:15 open; a call only with VIX down >= 1% |
| L3 RVHI / L3b RVLO | Liquidity | the index's 20-session realised vol ranked among the previous 250 sessions: high / low half |
| L4 DOW | Liquidity | drop the weekday with the lowest net on the design days before each walk-forward quarter (picks: Friday, Wednesday, Wednesday, Friday; final: Wednesday) |
| B1 VIXLO / B1b VIXHI | R72, R51 (C1/C1b), R52 (D1/D1b) | previous session's VIX close <= / > the median of the 60 sessions before |
| B2 GAP | R72, R51 (C2) | the index's own gap within +-0.5% |
| B3 NO0920 | R72, R51 (C3) | not the opening bar's break |
| B4 AM | R72, R51 (C4) | entries by 12:00 |
| B5 SIZE55 | R72 | 2 lots when all 5 indices break, 1 lot when 4 do |
| D2 FAR | R52 | the open beyond the value edge by >= 25% of the value-area width |
| D3 OPEN3 | R52 | >= 3 of the 5 indices open outside value the same way |

Engine: R51's Amendment A clean stores (Nov 2024 onward), R64's held-contract simulator, R17 liquidity fills, dated
Zerodha charges, 1 lot. Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026 in quarters), final
1 Apr - 13 Oct 2026 read once (and already read by about twenty earlier studies: weak evidence; the paper days from
14 Oct are the only unseen test). Gates (never relaxed): above B0 in the walk-forward window AND in the final; 500
random-skip twins p < 0.05 (as many signals dropped at random, the books walked again; B5: 500 random extra-lot
assignments, per lot); BH q <= 0.10 across all 20 (q per arm also shown); >= 40 final trades; above B0 without the 5
best trades.

## Results, win rate and net side by side (net after charges and slippage, 1 lot)

### Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5)

| variant | design trades / win / net | vs B0 (walk-forward vs B0) | final trades / win / net | vs B0 | twin p | q all (arm) | verdict |
|---|---|---|---|---|---|---|---|
| B0 (the arm today) | 881 / 44% / Rs 2,57,249 | | 357 / 37% / Rs 11,155 | | | | |
| L1 BREADTH3 | 733 / 44% / 1,97,895 | -59,354 (-46,315) | 303 / 37% / 115 | -11,040 | 0.74 | 1.0 (0.92) | fail |
| **L2 VIXDIR** | 411 / **48%** / 2,03,113 | -54,136 (-26,727; quarters -11,276 / +11,561 / +4,760 / -31,772) | 244 / **40%** / **49,067** | **+37,912** | **0.02** | 0.40 (0.10) | fail: less in design and walk-forward, q 0.40 |
| L3 RVHI | 380 / 43% / 1,46,868 | -1,10,382 (-80,285) | 243 / 35% / -21,162 | -32,317 | 0.92 | 1.0 | fail |
| L3b RVLO | 501 / 44% / 1,10,382 | -1,46,868 (-1,57,779) | 114 / 41% / 32,317 | +21,162 | 0.07 | 0.74 (0.18) | fail |
| L4 DOW | 815 / 43% / 2,47,812 | -9,438 (-9,438) | 288 / 40% / 18,997 | +7,842 | 0.28 | 1.0 (0.47) | fail |

Both halves of a split (L3 / L3b) are positive, so neither can beat the whole. L2 keeps the trades where VIX agrees
with the direction; its trades are better per trade (Rs 494 vs 292 in design, Rs 201 vs 31 in the final) but it
throws away more than half the signals, and the signals it throws away made money in design (Rs 54,136). Its final
gain rests on 5 trades (without them -Rs 13,464, B0 -Rs 51,376).

### R72 Breadth Donchian

| variant | design trades / win / net | vs B0 (walk-forward vs B0) | final trades / win / net | vs B0 | twin p | q all (arm) | verdict |
|---|---|---|---|---|---|---|---|
| B0 | 3,071 / 47% / Rs 2,16,929 | | 1,202 / 46% / Rs 1,71,366 | | | | |
| B1 VIXLO | 1,535 / 46% / 1,19,155 | -97,774 (-2,61,672) | 797 / 45% / 1,11,187 | -60,179 | 0.72 | 1.0 | fail |
| B1b VIXHI | 1,536 / 47% / 97,774 | -1,19,155 (-74,211) | 405 / 48% / 60,179 | -1,11,187 | 0.84 | 1.0 | fail: wins more often, makes far less |
| B2 GAP | 2,434 / 46% / 1,56,609 | -60,321 (-1,17,792) | 750 / 44% / 1,02,363 | -69,003 | 0.81 | 1.0 | fail |
| B3 NO0920 | 2,770 / 45% / 67,734 | **-1,49,195** (-1,56,675) | 1,016 / 43% / 95,288 | **-76,078** | 1.0 | 1.0 | fail: the opening bar's breaks are the arm's best |
| B4 AM | 1,614 / **48%** / 2,05,942 | -10,987 (-1,09,681) | 599 / **47%** / 89,799 | -81,567 | 0.55 | 1.0 | fail: wins more often, makes less |
| B5 SIZE55 | 4,536 lots (1,465 of 3,071 trades at 2 lots), Rs 107 a lot vs 71 | +1,10,442 at B0's lots (+55,073) | 1,862 lots (660 at 2), Rs 161 a lot vs 143 | +22,199 at B0's lots | 0.47 (random extra lots give Rs 160 a lot) | 1.0 | fail: in the final the extra lot earned what any extra lot earns; worst day -44,435, drawdown 1.12 lakh vs 61,884 |

### R51 Donchian 20

| variant | design trades / win / net | vs B0 (walk-forward vs B0) | final trades / win / net | vs B0 | twin p | q all (arm) | verdict |
|---|---|---|---|---|---|---|---|
| B0 | 4,498 / 45% / Rs 9,998 | | 1,781 / 44% / Rs 81,845 | | | | |
| C1 VIXLO | 2,311 / 45% / -4,386 | -14,384 (-1,25,523) | 1,265 / 44% / 28,538 | -53,307 | 0.88 | 1.0 | fail |
| C1b VIXHI | 2,187 / 45% / 14,384 | +4,386 (-50,266) | 516 / 45% / 53,307 | -28,538 | 0.39 | 1.0 | fail |
| C2 GAP | 3,602 / 45% / 43,012 | +33,014 (-46,480) | 1,199 / 43% / 48,703 | -33,142 | 0.69 | 1.0 | fail |
| C3 NO0920 | 4,291 / 44% / -77,188 | -87,186 (-1,05,328) | 1,672 / 43% / -15,599 | -97,444 | 1.0 | 1.0 | fail: as B3 |
| C4 AM | 2,769 / 46% / 56,693 | +46,694 (-34,837) | 1,061 / 45% / 17,487 | -64,358 | 0.73 | 1.0 | fail: better in design, worse walk-forward and final |

### R52 Open outside value

| variant | design trades / win / net | vs B0 (walk-forward vs B0) | final trades / win / net | vs B0 | twin p | q all (arm) | verdict |
|---|---|---|---|---|---|---|---|
| B0 | 706 / 56% / Rs 2,90,573 | | 293 / 52% / Rs 48,612 | | | | |
| D1 VIXLO | 358 / 57% / 1,04,797 | -1,85,777 (-1,42,949) | 217 / 52% / 29,554 | -19,058 | 0.62 | 1.0 | fail |
| D1b VIXHI | 348 / 54% / 1,85,777 | -1,04,797 (-1,04,109) | 76 / 51% / 19,058 | -29,554 | 0.35 | 1.0 | fail |
| D2 FAR | 589 / 56% / 2,91,751 | +1,178 (+8,753) | 249 / 53% / 40,315 | -8,297 | 0.53 | 1.0 | fail |
| D3 OPEN3 | 521 / 57% / 2,74,819 | -15,754 (-28,614) | 239 / 49% / 12,049 | -36,563 | 0.95 | 1.0 | fail |

## What this means for Boss

- **Nothing to switch on.** Every arm keeps its rules; no paper change, nothing in shadow, nothing live.
- **Do not add a "skip the 09:20 bar" rule anywhere**: on both Donchian arms the opening bar's break is where most of
  the money is (R72: Rs 1.49 lakh of its Rs 2.17 lakh design net).
- **Filters that raise the win rate here lower the profit** (L2, B4, B1b, D3): the trades they remove are individually
  weak but collectively positive. The arms make their money from many small breaks, not from picking better ones.
- **Sizing by signal strength (B5) is not an edge**: in the final the second lot on 5-of-5 breaks earned exactly what a
  second lot on random trades earns, with twice the worst day. More lots is more risk, not more skill.
- **What is exhausted now** (with this study): Liquidity exits and locks (R100 and before) and its entry filters
  (H12, R15, R79, R80, R101); ORB (R3, R49, R92); Hero (R21-R98); FT3 frozen; Donchian exits (R64-R71) and filters
  (R73, R75, R101); R72 add-ons (R73, R75, R84, R101); OOV exits and now its first entry filters (R101).
- **Not run** (with the reason, PREREG section 0): Solo 2.0's second pick (needs the R57 model and brain cube), Night's
  event-eve skips (hidden arm; needs equity_1m and chain OI), US-night silver's stop (COMEX data outside the lake), the
  12-month trend's T4 trail (Boss hid the arm on 21 Oct).

## Honest limits

- The final window has been read about twenty times since R51; L2's final p 0.02 is one of 20 tests on a window that
  chose Donchian itself. The design and walk-forward carry the weight, and L2 lost there.
- The first final attempt (12:05 UTC) stopped on R51's own final-read guard before reading any final-period row; the
  flag was reset and the read ran once after `open_final()` was added (commit a32e7cd).
- 1 lot per trade; the account-stop rule (-Rs 40k drawdown) is not modelled; B5's drawdown figure is at its lots.
- Lots and charges as the R51 family (dated); fills at the next minute with R17's spreads; stops on the minute low.
