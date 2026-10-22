# Options selling, understood deeply (R103, 22 Oct 2026)

Boss (22 Oct 2026): "understand deeply how options selling work." This is that understanding, in plain English, for
NIFTY and BANKNIFTY as they are traded in India today. It has three kinds of numbers, and each is marked:

* **our lake** (Dhan v2 copies on Drive: `index_1m`, `index_daily`, `options_rolling_1m`, the F&O bhavcopy; Aug 2021 -
  19 Oct 2026 for options), with its sample size `n`;
* **public rules** verified online on 22 Oct 2026 (SEBI, NSE, Zerodha; the source in brackets, the list at the end);
* **illustrations**: a Black-Scholes table at NIFTY 25,000 and 15% IV, so the payoffs and Greeks have concrete numbers.
  They are examples, not quotes.

The pre-registered test of defined-risk selling is R103 (`research/hunt/r103_sell/PREREG.md`); its results are in
`research/HUNT_R103_OPTIONS_SELLING.md` and summarised in section 5 here. Research only: nothing in `android/**` or
`.github/**` changes, nothing goes to paper or live.

Signal on the underlying (CLAUDE.md): every structure here is centred on the index's own price; the option is only
what is sold or bought. Stops, locks and targets are read on the premium, which CLAUDE.md allows.

## 0. The short version

1. **A seller is an insurer.** They take a premium now for an uncertain payout later. The most they can make is the
   premium; what they can lose is the move minus the premium (naked) or the wing width minus the premium (hedged).
2. **The premium is usually too high, which is the whole business.** India VIX over-states NIFTY's next month on
   81% of days (median ratio 1.28, n 4,507 days; R102). The 09:20 ATM straddle is beaten by the close on only 7.1% of
   NIFTY non-expiry days (n 961) and 6.5% of BANKNIFTY's (n 1,000). That is why selling "wins" 70-90% of the time.
3. **The rare day pays for all of it.** On expiry day the close beats the 09:20 straddle 40.5% / 37.8% of the time
   (n 259 / 185); the day's furthest excursion beats it 68-69% of the time. R98: of cheap expiry options, 97% end below
   their price, but 1% end 10x or more and the worst ended 178x (13 Oct 2026: a Rs 5,000 short became about -Rs 95,000).
4. **Theta is the pay, gamma is the bill.** A normal NIFTY day burns 12-14% of the 09:20 straddle by the close; the
   day before expiry 22%; expiry day 87% by 15:15 (n 963 / 241 / 259). The same expiry afternoon, one ATM option moves
   a median 13.5% per minute in the last hour (n 30,562 contract-minutes). You cannot have one without the other.
5. **Costs are the same per round trip for a seller and a buyer** (STT is paid by whoever sells, and both sides sell
   once): about Rs 66 for a Rs 120 NIFTY option, Rs 76 for a Rs 400 BANKNIFTY option, at 2026 rates. A four-leg iron
   fly is eight orders: about Rs 244 in charges plus Rs 50-200 of slippage, against a credit of a few thousand.
6. **Margin is the capital.** A hedged one-lot structure needs roughly 2.5% of the short legs' notional (about Rs 75k
   on NIFTY at 22,900 x 65; Rs 83k on BANKNIFTY at 55,500 x 30), and 4.5% on expiry day since SEBI's 2% expiry-day
   ELM (20 Nov 2024). A naked short lot needs about Rs 1.5 lakh. Boss's -Rs 40k account stop is a quarter to a half
   of one hedged lot's margin.
7. **The rules changed in 2024-26 and they bite sellers most:** one weekly expiry per exchange (NIFTY on Tuesday, SENSEX
   on Thursday; BANKNIFTY is monthly), bigger lots (65 / 30), the 2% expiry-day ELM, no calendar-spread margin benefit
   on expiry day, upfront premium from buyers, delta-based position limits with intraday snapshots, STT 0.15% of the
   sell premium from 1 Apr 2026, and a 3,510 / 1,440 freeze quantity per order.
8. **Naked selling is not for this account.** The naked 09:20 short straddle on BANKNIFTY made +Rs 33k and +Rs 37k in
   two years (NONDIRECTIONAL.md) with a worst day of -Rs 65,483 and a drawdown of -Rs 1.05 lakh: both beyond the
   -Rs 40k / -Rs 13k stops. Only defined-risk structures are even eligible, and R103 tests exactly those.
9. **R103's answer (24 pre-registered tests, design to 14 Jul 2026):** {{R103_SHORT}}
10. **What the app lacks before any selling arm goes to paper** (section 8): a basket margin check with the hedge
    benefit and the expiry-day ELM, long wings filled first and shorts closed first (the app already orders exits that
    way), a credit-based stop and lock on the basket, broker-resting stops on the short legs, an event blackout, and
    expiry-day rules.

## 1. What a seller takes on

* **The deal.** Selling a call or a put means receiving the premium today and promising the buyer the option's value at
  expiry: max(index - strike, 0) for a call, max(strike - index, 0) for a put. Indian index options are European and
  cash-settled: nothing is delivered, nothing can be exercised early, and at expiry the exchange settles in rupees at
  the final settlement price (the index's official close) [NSE contract specifications; Kotak, Angel One pages on
  cash settlement]. So a seller's obligations are a number, not a delivery, but it is an open-ended number.
* **The shape of the bet.** The seller's profit is capped at the premium; the loss is uncapped for a naked call
  (the index can rise without limit), capped only by zero for a naked put, and capped at wing width minus credit for a
  hedged structure. Everything about selling follows from this asymmetry: many small wins, a few large losses, and a
  win rate that says nothing about the result. R48 found the same for buying: high design win rates became losses.
* **Why there is money in it at all.** The option market prices a move; the realised move is usually smaller. R102:
  the 09:20 ATM straddle is a median 0.96% of spot on NIFTY (n 1,222 days) and 1.49% on BANKNIFTY (n 1,190; 2.15% in
  2025, 2.78% in 2026 since it became a monthly), while the median close-to-09:20 move is 0.36 / 0.25 straddles on a
  normal day. VIX over-states NIFTY's next 21 days 81% of the time. For BANKNIFTY the same VIX under-states realised
  vol on 60% of days, because BANKNIFTY realises about 1.3x NIFTY; the "free" premium is not the same on both indices.
* **Who sells.** Prop desks write 23-24% of index-option shorts; clients hold 58% of the longs (R102, n 3,577 days).
  SEBI's study of FY22-24 found 93% of over one crore individual F&O traders lost money, Rs 1.8 lakh crore in total,
  and 91% lost in FY24 alone; FY25's individual losses were Rs 1.05 lakh crore [SEBI study Sep 2024 via Outlook
  Money, Business Today; Moneylife on the FY25 study]. Those losses are mostly buyers' and naked sellers'.
* **The seller's four jobs,** in the order they matter: (1) size so that the worst day is survivable (the wing width,
  not the win rate, sets it); (2) never be naked into an event or an expiry afternoon; (3) have a stop that executes,
  not one that is hoped for; (4) collect the theta without paying it back in gamma: enter when the premium is rich
  and leave before the last hour, or let the structure's wings carry the last hour.

## 2. Payoffs of the common structures

The illustration: NIFTY at 25,000, IV 15%, Black-Scholes. Premiums in points; one lot is 65, so Rs = points x 65.
Entered on a Thursday for the Tuesday expiry (5 calendar days):

| strike | 24,500 | 24,700 | 24,800 | 24,900 | 25,000 | 25,100 | 25,200 | 25,300 | 25,500 |
|---|---|---|---|---|---|---|---|---|---|
| call | 546 | 380 | 307 | 243 | **186** | 139 | 101 | 71 | 32 |
| put | 24 | 58 | 85 | 120 | **164** | 217 | 279 | 349 | 509 |

The same strikes on expiry morning (1 day): ATM call 81, put 76; 25,200 call 17; 24,800 put 15. At 13:30 on expiry
day: ATM call 40, put 39; 25,200 call under 1 point. This is R102's decay clock in Black-Scholes form (section 3.2).

| structure | legs (illustration) | credit / debit | max profit | max loss | breakevens | margin (estimate, section 4) | wins when |
|---|---|---|---|---|---|---|---|
| **short straddle** (naked) | sell 25,000 C and P | +350 (Rs 22,750) | the credit | unlimited | 24,650 / 25,350 | about Rs 1.5-1.7 lakh for the pair with cross-margin, 1.5 lakh per naked lot | the close stays inside +-350 (1.4%); on a normal NIFTY day the move beats the straddle 7.1% of the time, on expiry day 40.5% |
| **short strangle** (naked) | sell 24,800 P and 25,200 C | +186 (Rs 12,090) | the credit | unlimited | 24,614 / 25,386 | about the same as the straddle | wider room, half the pay; the same tail |
| **iron fly** (R103 IF) | straddle + buy 24,650 P and 25,350 C (wings = 1 straddle out) | about +180 after the wings | the credit | wing 350 - credit 180 = 170 points (Rs 11,050) | 24,820 / 25,180 | about Rs 75k (Rs 1.35 lakh on expiry day) | the close is within about half a straddle; the wings turn the 178x day into -Rs 11k |
| **wide iron fly** (R103 IFW) | wings 1.5 straddles out (24,475 / 25,525) | about +250 | the credit | 525 - 250 = 275 points (Rs 17,875) | 24,750 / 25,250 | as above | more credit, more room, bigger worst day |
| **iron condor** (R103 IC) | sell 24,800 P and 25,200 C, buy 24,450 P and 25,550 C (shorts 0.5, wings 1.0 straddle beyond) | about +115 | the credit | 350 - 115 = 235 points (Rs 15,275) | 24,685 / 25,315 | as above | a wider flat zone, a smaller credit; the classic "90% win rate" structure, and the one that lost most in NONDIRECTIONAL.md (costs and the minute-worst pricing ate the small credit) |
| **bull put spread** (credit spread) | sell 24,900 P, buy 24,700 P | +62 | the credit | 200 - 62 = 138 points (Rs 8,970) | 24,838 | about Rs 35-40k | the index does not fall more than 0.4%; a directional bet with theta on your side |
| **bear call spread** | sell 25,100 C, buy 25,300 C | +68 | the credit | 132 points (Rs 8,580) | 25,168 | about Rs 35-40k | the mirror for a fall |
| **covered call** | long 1 NIFTY future (or 65 x index ETF units) + sell 25,200 C | +101 on top of the future | 200 + 101 points up | the future's whole downside minus 101 | 24,899 | the future's margin (about 11-12% of notional, Rs 1.8 lakh) less a small benefit | a slow rise; it is a long position with a cap, not a neutral trade |
| **cash-secured put** | sell 24,800 P with the cash to "take delivery" | +85 | the credit | 24,800 - 85 = 24,715 points (Rs 16 lakh) if the index went to zero | 24,715 | on an index there is no delivery: it is a naked put with cash parked, the margin is the naked put's | the owner is happy to be long at 24,715 |
| **ratio spread** (1x2) | buy 25,000 C, sell 2 x 25,200 C | about +16 (near zero) | 200 + 16 at 25,200 | unlimited above 25,416 | 25,416 | the extra naked call's margin, about Rs 1.5 lakh | a drift up to the short strike; a melt-up is the ruin |
| **calendar** | sell the weekly 25,000 C, buy the next week's 25,000 C | a debit (the far leg costs more) | the near leg dies and the far keeps its time value | the debit | around the strike | both legs' margin; **since 1 Feb 2025 the calendar-spread margin benefit is removed on the near leg's expiry day** [SEBI 1 Oct 2024; Business Standard] | the index sits still into the near expiry and IV holds |

Reading the table:

* The credit falls as the structure gets safer: straddle 350, iron fly 180, condor 115, credit spread 62. The max loss
  falls faster (unlimited, 170, 235, 138 points), and the margin falls from about Rs 1.5 lakh to Rs 35-75k. That is the
  trade-off a seller is actually choosing: **return on margin against the worst day**.
* Every hedged structure buys its wings from a cheaper, further strike. The wing is insurance; it costs theta of its
  own (the 25,350 call loses about 10 points a day in the example) and it is what makes the max loss a number.
* "Covered" and "cash-secured" are stock ideas. On an index there is no stock to deliver, so a cash-secured put is a
  naked put with idle cash, and a covered call is a long future (or ETF) with a sold call: both are directional
  positions, not income.

## 3. The Greeks, from the seller's side

**Delta** is how many points the position moves per index point. A short straddle starts near zero (the call's +0.52
and the put's -0.48 cancel). It does not stay there: when the index rises the call's delta grows and the put's shrinks,
so the seller becomes short the market exactly when it is rising. That is **gamma**: the seller is short gamma, and
the loss grows with the square of the move. In the illustration the ATM gamma is 0.09 delta per point with five days
left, 0.20 on expiry morning and **0.41 at 13:30 on expiry day**: a 100-point move at 13:30 turns a neutral straddle
into a 41-delta position, i.e. it behaves like 27 lots of index per lot of straddle. R102 measured the same thing in
premium: one ATM option moves a median 3.3% a minute in the first 15 minutes and 13.5% a minute in expiry's last hour.

**Delta hedging** is how professionals carry short gamma: when the position's delta reaches, say, +-0.2 per lot, they
trade the index future or the stock basket the other way, and keep doing it. Each hedge locks in a small loss (they
buy after a rise, sell after a fall); over the life of the trade those losses add up to the *realised* variance, and
the premium received was the *implied* variance. The hedger's P&L is implied minus realised, which is positive on
81% of NIFTY months (section 1) and negative on the crash days. For one lot it is nearly impossible: the NIFTY future's
lot is 65, the same as the option, so a 0.2-delta hedge is 13 units and does not exist. A one-lot seller hedges with
the structure (the wings) and the stop, not with futures. That is why R103 tests wings and stops, not hedging.

**Theta** is the premium the seller collects per day for being short gamma. R102 measured it on the rolling ATM
straddle as a share of its 09:20 value (medians):

| time | expiry day, NIFTY (n 259) | day before (241) | 2-4 days before (441) | other days (963) | expiry day, BANKNIFTY (186) |
|---|---|---|---|---|---|
| 10:30 | 0.844 | 0.947 | 0.966 | 0.965 | 0.848 |
| 12:00 | 0.702 | 0.906 | 0.937 | 0.937 | 0.727 |
| 13:00 | 0.608 | 0.881 | 0.923 | 0.924 | 0.609 |
| 14:30 | 0.395 | 0.831 | 0.897 | 0.896 | 0.379 |
| 15:00 | 0.221 | 0.808 | 0.883 | 0.884 | 0.231 |
| 15:15 | 0.128 | 0.794 | 0.874 | 0.876 | 0.114 |

So on a normal day a NIFTY 09:20 straddle of 0.96% x 22,900 = 220 points (Rs 14,300 a lot) loses about 3.5% by 10:30
(Rs 500) and 12-14% by the close (Rs 1,800-2,000); the day before expiry 22% (Rs 3,100); on expiry day a 0.50% straddle
(115 points, Rs 7,500) loses 16% by 10:30, 39% by 13:00, 60% by 14:30 and 87% by 15:15 (Rs 6,500). Theta is not a
straight line: it is a cliff after 14:30 on expiry day and nearly flat on the days before. A seller who wants the
rupees must be there in the expiry afternoon, which is exactly when gamma is 0.4.

**Vega and IV crush.** Vega is the premium's sensitivity to implied volatility: about 12 points per IV point for the
ATM with five days left, 5 on expiry morning, 2.6 at 13:30. The seller is short vega: a VIX spike against an open
position is a loss before the index has moved. R102: the nearest-expiry ATM IV sits 0.3 points above VIX on a normal
NIFTY day and **7.7 points above on expiry day** (16 points for BANKNIFTY's monthly); the 09:15 -> 09:30 "crush" is
-1.8% of the straddle on a normal day and -8.0% on expiry (n 962 / 259). Event days are the vega trade: the Budget
day doubles the range (2.13% / 2.62%, n 6) and its straddle is priced 3-5x (R98d), the 2024 election result moved
NIFTY -5.93% and +3.36% on consecutive days. Selling *into* an event is selling the crash insurance at the moment it
is most likely to be claimed; selling *after* it, when IV is still high and the move is known, is the textbook trade,
and it is still a short-gamma position.

**Skew.** Puts five strikes out are dearer than calls five strikes out by 2.56 IV points on NIFTY (91% of days,
n 1,222) and 2.11 on BANKNIFTY (90%). The put side pays more credit because the market prices a crash more than a
melt-up; it is not free money, it is the price of the 23 Mar 2020 day (-12.98%).

**Rho** (interest rates) does not matter for a weekly; it is in the table only because the formula has it.

## 4. Margin and capital in India today (public rules, verified 22 Oct 2026)

**How margin is computed.** SPAN (the exchange's scenario loss over a set of price and volatility moves) plus
exposure margin (a flat percentage of notional on short positions), less the spread benefit for hedged legs
[Zerodha Z-Connect, margin calculator]. Zerodha: "Total margin = SPAN + exposure - spread benefit"; when legs hedge each
other SPAN drops and exposure stays. NSE's 2020 framework gives hedged spreads about 70-80% less margin than naked
shorts [Zerodha Z-Connect 2021; Varsity, iron condor chapter].

**Numbers.**

| position | margin | source |
|---|---|---|
| one naked short NIFTY option lot | about Rs 1.5-1.7 lakh (a 29 Sep 2026 example: Rs 1,63,578 for a short call, from a computed SPAN file) | MarketCalls, third party |
| Varsity's worked iron condor (NIFTY 9,973, lot 75, 200-point wings) | Rs 44,303 against Rs 1,45,090 for the naked strangle (-69.5%); its max loss was Rs 5,366 | Zerodha Varsity |
| the same condor's margin per rupee of short notional | 2.96% of two legs of Rs 7.48 lakh (pre-2024 ELM) | computed from Varsity |
| a Nov 2024 trader's NIFTY condor when the 2% expiry-day ELM arrived | Rs 35 lakh -> Rs 62 lakh (+80%); about 2.5% -> 4.5% of short notional | TradingQnA thread, user report |
| **R103's stated estimate** (PREREG Amendment 1) | 2.5% of the short legs' notional on a normal day, 4.5% on the index's expiry day from 20 Nov 2024 | this study |
| ... one NIFTY lot at 22,889 x 65 | Rs 74k normal, Rs 1.34 lakh on expiry day | estimate |
| ... one BANKNIFTY lot at 55,488 x 30 | Rs 83k normal, Rs 1.50 lakh on expiry day | estimate |

The broker's calculator decides on the day; these are planning numbers. Note the ratio: the iron fly's max loss is
Rs 11-18k, its margin is Rs 75k-1.5 lakh. Margin is 5-10x the worst case, so return on margin is small even when the
trade works.

**The mechanics that cost real money if ignored.**

* **Order of legs.** Place the long wings first (or all four in a basket); a short placed alone is margined naked at
  placement. On exit close the shorts first: Zerodha's guidance is to exit the leg with the highest blocked margin
  first; exiting the hedge first removes the benefit, creates a peak-margin shortfall, and the penalty is no hedge
  benefit and double margin on new positions during market hours [Zerodha support, "margin benefit not received for a
  hedged trade"; "final margin in options basket orders"]. The app already buys back shorts first on a square-off
  (`AppModel.planSquareOffAll`) and sends legs one at a time, each filled before the next.
* **Peak margin.** Since Sep 2021 the full margin must be there at every one of the clearing corporation's intraday
  snapshots, not only overnight; intraday leverage on option selling is gone.
* **Expiry-day ELM.** Since 20 Nov 2024 every short option expiring that day carries an extra 2% of contract value, on
  positions carried in and on new ones [SEBI 1 Oct 2024 via Zerodha Z-Connect, Business Standard, Stocko].
* **Calendar spreads** lose their margin benefit on the near leg's expiry day since 1 Feb 2025 [same circular].
* **Buyers pay upfront** since 1 Feb 2025: no leverage on option buying either.
* **Position limits** are now delta-based: per client and per index, Rs 1,500 crore net FutEq / Rs 10,000 crore gross
  at end of day (SEBI 29 May 2025), Rs 5,000 crore net / 10,000 crore gross intraday with at least four random snapshots
  a day (SEBI 1 Sep 2025, in force 1 Oct 2025); the expiry-day breach penalty from 8 Dec 2025 [Outlook Money, MSE
  circular 17898 / 18255]. Irrelevant to one lot, but the snapshots are why brokers tightened intraday checks.
* **Freeze quantity** per order: NIFTY 3,510, BANKNIFTY 1,440 from 5 Oct 2026 (NSE circular FAOP, 1 Oct 2026) [Choice,
  Angel One]. 54 / 48 lots; one lot never meets it.
* **Strikes.** Zerodha restricts overnight (NRML) buying of far-OTM NIFTY and BANKNIFTY strikes under the OI-based
  member limit, allows all strikes intraday (MIS), allows shorting on all strikes, and allows a hedge buy outside the
  range up to the shorted quantity [Zerodha support; TradingQnA]. An iron fly's wings are inside the range on a normal
  day; a very wide wing may not be, which is a reason to check the order's rejection message in paper first.
* **Auto square-off.** Zerodha's F&O MIS square-off is 15:26 since 26 Dec 2025, Rs 50 + GST per order it has to send
  [Zerodha Z-Connect, support]. The app's own rule is 15:10, and 15:05 on expiry day (CLAUDE.md).
* **Hours.** 09:15 - 15:40 for index derivatives since 3 Aug 2026 (15:45 from 7 Dec 2026, the expiring contract stops
  at 15:30 on its expiry day) [R102 1.5, NSE/CMTR/74466]. The lake's minute bars end at 15:29.

**The 2024-26 rule changes, in one list.**

| from | change | source |
|---|---|---|
| 20 Nov 2024 | one weekly expiry per exchange: NSE kept NIFTY, BSE kept SENSEX; BANKNIFTY, FINNIFTY, MIDCPNIFTY, BANKEX monthly only | SEBI 1 Oct 2024; Zerodha Z-Connect; Business Standard |
| 20 Nov 2024 | minimum contract value Rs 15 lakh: lots NIFTY 25 -> 75, BANKNIFTY 15 -> 30 | NSE; R102 1.5 |
| 20 Nov 2024 | +2% ELM on short options on their expiry day | SEBI 1 Oct 2024 |
| 1 Feb 2025 | option premium collected upfront from buyers; no calendar-spread benefit on expiry day | SEBI 1 Oct 2024; Business Standard |
| 1 Apr 2025 / 1 Oct 2025 | intraday monitoring of position limits (four snapshots); delta-based limits | SEBI 29 May and 1 Sep 2025 |
| 2 Sep 2025 (contracts from 29 Aug) | NIFTY weekly and monthly expiries move to **Tuesday**; BANKNIFTY monthly on the last Tuesday; SENSEX on **Thursday** | NSE June 2025 circular; Business Standard 28 Aug 2025; HDFC Securities |
| 30 Dec 2025 - 27 Jan 2026 | lots NIFTY 75 -> **65**, BANKNIFTY 35 -> **30**, FINNIFTY 60, MIDCPNIFTY 120 | NSE/FAOP/70616, 3 Oct 2025; Zerodha bulletin 4 Oct 2025; HDFC Sky |
| 31 Dec 2025 | BANKNIFTY to 14 members, caps 19 / 14 / 10% | NSE Indices; R102 1.2 |
| 1 Apr 2026 | STT on an option **sale 0.10% -> 0.15%** of premium; on exercise 0.125% -> 0.15% of intrinsic; futures 0.02% -> 0.05% | Budget 2026-27; ICICI Direct, Outlook Money, Navia |
| 3 Aug 2026 | index derivatives trade to 15:40 | NSE/CMTR/74466 (R102) |
| 5 Oct 2026 | freeze quantities NIFTY 3,510, BANKNIFTY 1,440 | NSE FAOP 1 Oct 2026; Choice, Angel One |

## 5. Tail risk, with our own numbers

**The biggest days.** Daily close-to-close, 2006 - 14 Jul 2026 (R102 2.1): NIFTY's worst day -12.98% and BANKNIFTY's
-16.73% (both 23 Mar 2020); the 1st / 99th percentiles are -3.77 / +3.60% (NIFTY) and -4.82 / +4.86% (BANKNIFTY);
kurtosis 14 and 8.5 (a normal distribution has 3). By year, the worst and best day:

| year | NIFTY worst | NIFTY best | BANKNIFTY worst | BANKNIFTY best |
|---|---|---|---|---|
| 2019 | -2.14 | +5.32 (20 Sep, the corporate-tax cut) | -2.77 | +8.31 |
| 2020 | -12.98 (23 Mar) | +8.76 | -16.73 | +10.51 |
| 2021 | -3.76 | +4.74 (1 Feb, Budget) | -5.10 | +8.26 |
| 2022 | -4.78 (24 Feb, Ukraine) | +3.03 | -5.79 | +4.00 |
| 2023 | -1.61 | +2.07 | -3.13 | +3.61 |
| 2024 | -5.93 (4 Jun, election count) | +3.36 (5 Jun) | -7.95 | +4.53 |
| 2025 | -3.24 (7 Apr, tariffs) | +3.82 (12 May) | -3.19 | +3.34 |
| 2026 to 14 Jul | -3.26 (19 Mar) | +3.78 (8 Apr) | -3.82 | +5.67 |

**What a 09:20 short straddle lost on them.** Before Aug 2021 there is no option data in the lake, so the loss is an
estimate: (open-to-close move - the median 09:20 straddle) x index x lot. 23 Mar 2020: NIFTY opened down and lost
about another 8% from the open; with a straddle of even 3% of spot in that regime the naked straddle lost about
(8 - 3)% x 7,600 x 75 = Rs 28,000 a lot on top of the gap it never saw, and a seller who was short from the previous
Friday lost the whole 12.98%: about Rs 70,000 a lot at 2020's lot of 75 and level of 7,600 (a lot of 65 at today's
22,900 would be Rs 1.9 lakh). After Aug 2021 the lake prices the real contracts; the table below is from
`research/hunt/r103_sell/results/tail.md` (naked 09:20 ATM short straddle and the IF with its 1-straddle wings, both
held to the 15:10 / 15:05 square-off with no stop, 1 lot, the engine's slippage and dated charges):

{{R103_TAIL}}

**R98's lesson from the buyer's side is the seller's warning.** Of expiry-day options at Rs 1-10 (911 expiries, five
indices), 97% end below their price and 85% near zero: whoever sold them was "right" 97% of the time. But 1% end at
10x or more (the seller loses 9x the premium), 14% double at some minute, and the worst ended 178x: on 13 Oct 2026
the 22,700 CE sold at 13:30 for Rs 5,000 of premium was worth about Rs 95,000 at the close. A seller who collects
Rs 5,000 a day and meets one 178x in two years is behind by Rs 95,000 - about 250 days of Rs 5,000 wins minus costs.
That is the arithmetic of naked expiry selling, and no win rate changes it.

**NONDIRECTIONAL.md (BANKNIFTY, Feb 2024 - Feb 2026, 1 lot of 30, every leg at its worst minute price).** The naked
09:20 short straddle with a 50%-of-credit stop made +Rs 59,964 and +Rs 28,895 in the two years (70% / 61% win) with
worst days of -Rs 29,503 and -Rs 12,348 and drawdowns of -Rs 63,854 and -Rs 31,913. Every iron fly and iron condor
lost (-Rs 62k to -Rs 478k a year), because the wings were priced at their worst minute and the stop fired on noise.
R103 prices the same structures on held contracts at the next minute's open, which is the fair test; its results:

{{R103_RESULTS}}

## 6. Costs per lot: selling against buying (2026 rates, the repo's dated cost model)

| trade | premium | charges, round trip | of which STT | slippage at 1 tick + 5 bps |
|---|---|---|---|---|
| one NIFTY option at Rs 120 x 65, sell then buy back | Rs 7,800 | **Rs 65.69** (sell Rs 38.58 + buy Rs 27.11) | Rs 11.70 | about Rs 11 |
| the same option bought then sold | Rs 7,800 | **Rs 65.69** | Rs 11.70 | about Rs 11 |
| one NIFTY option at Rs 60 | Rs 3,900 | Rs 56.45 | Rs 5.85 | about Rs 9 |
| one BANKNIFTY option at Rs 400 x 30 | Rs 12,000 | Rs 75.66 | Rs 18.00 | about Rs 15 |
| a NIFTY iron fly, 8 orders (shorts at 120, wings at 60) | credit about Rs 7,800 | **Rs 244** | Rs 23 | **Rs 50** at 1 tick, Rs 200 at 4 ticks |

The brokerage is Rs 20 an order, STT 0.15% of the premium on every sell, exchange 0.03503% + IPFT, SEBI Rs 10 a crore,
stamp 0.003% on buys, GST 18% on brokerage and exchange charges. Two things follow:

* **Per round trip the seller pays the same as the buyer.** STT is on the sell side, and both the seller (opening) and
  the buyer (closing) sell once. The seller saves only when the option expires worthless and is never bought back:
  no closing order, no closing charges. A buyer who holds an in-the-money option to expiry pays 0.15% of the intrinsic
  value as exercise STT (Rs 9.75 on 100 points x 65); a seller assigned on it pays nothing extra.
* **A four-leg structure pays four round trips** for one position: about Rs 244 + slippage against a credit of a few
  thousand, which is 3-6% of the credit before anything happens. Over R103's design period the mean credit and the
  cost share per structure are in the results table; the IC's small credit is why it has the thinnest margin for
  error. Doubling slippage (gate 7) is the test of whether the edge is bigger than the spread.

## 7. How selling would fit Boss's safety rules (CLAUDE.md)

* **Stop and profit lock, always.** R103's X2 exit is exactly the crypto rule: a stop at 50% of the credit, a lock armed
  at +40% that exits at +20%, a target at +60%. X1 is the stop alone. No "no stop" variant was run as a strategy; the
  tail table's hold-to-close numbers are descriptive only.
* **-Rs 40k drawdown, -Rs 13k a month.** Gate 6 rejects any test whose worst calendar month is below -Rs 13,000 at
  one lot; the report also counts trades at or below -Rs 13,000 in a day and the maximum drawdown. One hedged lot's
  max loss (Rs 11-18k on NIFTY) is already most of a month's allowance, so even a passing test is a one-lot test.
* **Paper first, and separately labelled.** A pass is a paper candidate only. It would run beside, not inside, the
  Liquidity BANKNIFTY record, so the 26 Oct plain-rules decision is untouched.
* **Signal on the underlying.** The structure is centred on the index's ATM at 09:20; nothing is read from an option's
  own chart. Stops, locks and targets are on the premium, which the rule allows.
* **Live only ever lowers risk.** A selling arm has no live path here. If one ever existed, the only automatic changes
  would be skip, smaller size, earlier exit; and real orders stay behind the PIN and "AI trades go live".
* **Open positions first.** Four legs are four positions: the stop check, the restart recovery and the broker-resting
  stops must cover all four at full cadence, and a leg that fails to fill must never leave a naked short (the app's
  one-leg-at-a-time send and its shorts-first square-off are the right shape).

## 8. What the app has, and what it lacks, for a selling arm

The app already has more of this than a buying-only app would: a Strategy Module with multi-leg baskets whose legs
can be short (`StrategyDef` / `LegDef`, with per-leg stop and target in points, a trail, an overall MTM stop and
target, a daily loss limit, NRML or MIS, a weekday scheduler; the preset editor opens on a NIFTY short straddle), a
"block naked shorts" switch, a live-entry margin check against the free margin Kite reports (`Broker` reads
`/user/margins`), legs sent one at a time and each filled completely before the next, shorts bought back first on a
square-off, and SandboxCosts that charge the sell side correctly in paper. The presets were backtested on harvested
sessions and are "paper and not armed" (TODO D8).

What it lacks before any selling arm could take even a paper trade under CLAUDE.md:

1. **A pre-registered, passing rule.** None exists yet; R103 is the first proper test and its verdict is in section 5.
2. **A basket margin check with the hedge benefit and the expiry-day ELM.** The live check compares a single need with
   the free margin; a four-leg basket needs Kite's basket-margin call (or the 2.5% / 4.5% estimate as a floor) before
   the first leg goes, and a rule that refuses the whole basket when any leg would be refused.
3. **Long wings first on entry.** The send is sequential; the plan must put the bought legs first so a half-filled
   basket is a long option, never a naked short. The exit already closes shorts first.
4. **A credit-based basket stop and lock.** The module's basket stop is in MTM rupees and the leg stops in points; X2
   is "50% of the credit received", "lock at +40% / +20%", "target +60%", which must be computed from the four fills.
5. **Broker-resting stops on the short legs** (SL buy orders), so a dead phone cannot miss a stop, with the resting
   order cancelled when the app closes the leg itself (two resting exits on one leg is the known hazard noted in
   `Strategies.kt`).
6. **An event blackout**: no entry on Budget day, the election count, RBI policy day, and the session after a Fed
   decision or US CPI print (R98d's `events.csv` is the calendar), and no entry while India VIX is above a set level.
7. **Expiry-day rules**: the 15:05 square-off (exists), no new short after 14:30 on expiry day (the gamma cliff), the
   2% ELM in the margin check, and the knowledge that BANKNIFTY's expiry is monthly and NIFTY's is Tuesday.
8. **Restart recovery for four legs as one position**, so the stop and lock are restored on the basket, not on four
   unrelated legs; and the position-first priority of CLAUDE.md applied to all four.
9. **A margin and P&L view for a credit position**: the premium received is not profit, the margin blocked is not a
   loss, and the P&L must be shown as credit minus current cost to close, per basket.

None of this is built here (research only). The list is the work order for an app session if R103, or a later
pre-registered test, ever passes.

## 9. Method, limits, honesty

* The lake's option bars have no bid/ask: slippage is modelled (5 bps plus 1-4 ticks by the contract's volume in the
  five minutes before, doubled for a stop fill). The true intraday spread at a signal is an open question
  (`signal-time-spread`).
* Margin is an estimate (section 4); there is no SPAN file in the lake. Return on margin in R103 uses it.
* The Black-Scholes table is an illustration at one spot, one IV and a flat 6.5% rate; real premiums carry skew and
  the expiry-day term structure (R102 3.4).
* The pre-Aug-2021 straddle losses are estimates from the daily index and the median straddle; they are labelled so.
* The public rules were checked on 22 Oct 2026 against the sources below. Rules move; the broker's calculator and the
  exchange circular decide on the day.

## Sources (checked 22 Oct 2026)

* SEBI circular of 1 Oct 2024 (index derivatives: one weekly per exchange, contract size, expiry-day ELM, upfront
  premium, calendar spreads): [Zerodha Z-Connect](https://zerodha.com/z-connect/business-updates/sebis-new-rules-for-index-derivatives-heres-whats-changing),
  [Business Standard](https://www.business-standard.com/amp/markets/news/sebi-announces-six-key-changes-to-curb-speculation-in-derivatives-trading-124100101316_1.html),
  [Stocko](https://stocko.in/?p=1643), [Fyers](https://fyers.in/notice-board/sebi-announces-significant-reforms-in-equity-index-derivatives-framework/).
* SEBI position limits 29 May 2025 and intraday monitoring 1 Sep 2025: [Outlook Money](https://www.outlookmoney.com/invest/equity/sebi-outlines-new-framework-for-monitoring-of-intraday-position-limits-for-index-derivatives),
  [MSE circular 17898](https://www.msei.in/SX-Content/Circulars/2025/October/Circular-17898.pdf), [MSE circular 18255](https://www.msei.in/SX-Content/Circulars/2025/December/Circular-18255.pdf),
  [Business Standard](https://www.business-standard.com/amp/markets/news/sebi-fo-revamp-new-oi-metric-position-limits-july-december-rollout-125052901798_1.html).
* Expiry days (NIFTY Tuesday, SENSEX Thursday): [Business Today](https://businesstoday.in/markets/stocks/story/nse-revises-expiry-dates-for-index-derivatives-contracts-from-august-29-481484-2025-06-23),
  [Business Standard 28 Aug 2025](https://www.business-standard.com/amp/markets/news/nse-bids-adieu-to-thursday-expiry-as-dates-swap-come-into-effect-explained-125082800635_1.html),
  [HDFC Securities](https://www.hdfcsec.com/blog/details/revision-of-nse-bse-expiry-day-of-derivatives-contracts).
* Lot sizes (NSE/FAOP/70616): [Zerodha bulletin 4 Oct 2025](https://zerodha.com/marketintel/bulletin/429705/revision-in-lot-size-of-index-derivative-contracts-from-december-30-2025),
  [HDFC Sky](https://hdfcsky.com/news/nse-revises-market-lot-sizes-for-major-index-derivatives-effective-january-2026), [Fyers](https://fyers.in/notice-board/revision-in-index-derivatives-contracts-lot-size/).
* STT from 1 Apr 2026 (Budget 2026-27): [ICICI Direct](https://www.icicidirect.com/ilearn/futures-and-options/articles/stt-changes-in-budget-2026-what-f-o-traders-should-know),
  [Outlook Money](https://www.outlookmoney.com/amp/story/invest/stt-hike-from-april-1-2026-budget-what-it-means-for-futures-and-options-traders), [Navia](https://navia.co.in/blog/union-budget-2026-stt-hike/).
* Freeze quantities from 5 Oct 2026: [Choice](https://choiceindia.com/news/nse-revises-quantity-freeze-limit-for-index-fno-contracts-from-october-5-2026),
  [Angel One](https://www.angelone.in/news/market-updates/nse-updates-quantity-freeze-limits-for-indices-effective-september-1-2026).
* Margin: [Zerodha Z-Connect, new margin framework](https://zerodha.com/z-connect/general/new-margin-framework-for-fo-trades),
  [Zerodha F&O margin calculator](https://zerodha.com/z-connect/general/zerodha-span-calculator),
  [Zerodha Varsity, iron condor](https://zerodha.com/varsity/chapter/iron-condor/),
  [TradingQnA, hedged margins up 80% (Nov 2024)](https://tradingqna.com/t/margin-requirements-for-hedged-trades-up-by-80/176129),
  [MarketCalls (SPAN example, third party)](https://www.marketcalls.in/?p=64873).
* Hedge order, basket margin, penalties: [Zerodha support, margin benefit not received](https://support.zerodha.com/category/trading-and-markets/margins/margin-leverage-and-product-and-order-types/articles/margin-benefit-not-received-for-a-hedged-trade),
  [Zerodha support, final margin in options basket orders](https://support.zerodha.com/category/trading-and-markets/charts-and-orders/basket-order/articles/final-margin-calculated-in-options-basket-orders).
* Strike restrictions: [Zerodha support, Bank Nifty order rejected](https://support.zerodha.com/category/trading-and-markets/trading-faqs/f-otrading/articles/why-did-my-bank-nifty-option-order-get-rejected),
  [TradingQnA](https://tradingqna.com/t/why-cant-i-buy-far-otm-options/156072).
* Auto square-off 15:26 and its charge: [Zerodha Z-Connect](https://zerodha.com/z-connect/updates/changes-to-the-auto-square-off-timings-for-equity-and-fo),
  [Zerodha support](https://support.zerodha.com/category/account-opening/charges-at-zerodha/articles/auto-square-off).
* Cash settlement of index options: [Kotak Securities](https://kotaksecurities.com/support/what-are-kotak-securities-policy-on-physical-settlement-of-equity-derivatives-on-expiry),
  [Angel One](https://angelone.in/derivatives/settlement-procedure).
* Greeks for sellers: [Zerodha Varsity, theta](https://zerodha.com/varsity/chapter/theta-2), [Varsity, the straddle](https://zerodha.com/varsity/chapter/the-straddle/).
* Retail F&O losses: [Outlook Money on the SEBI study](https://www.outlookmoney.com/invest/93-of-individual-traders-suffered-losses-in-fo-in-last-3-years-sebi-study),
  [Business Today 24 Sep 2024](https://businesstoday.in/bt-tv/video/sebi-report-says-93-individuals-faced-massive-losses-in-equity-fo-in-fy24-447334-2024-09-24),
  [Moneylife on FY25](https://moneylife.in/article/91-percentage-of-retail-traders-lost-money-in-derivatives-losses-in-fo-surged-41-percentage-to-rs105-lakh-crore-in-fy2425-sebi-study/77613.html).
* Our own studies: `research/DEEP_NIFTY_BANKNIFTY.md` (R102), `research/HUNT_R98_HERO_ALL_INDEX.md` (R98),
  `research/NONDIRECTIONAL.md`, `research/obuy/costs.py` (charges), `research/hunt/r103_sell/` (R103).
