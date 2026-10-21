# How the big players think and work: a guide for IraAlgo

Written 19 Oct 2026 (R86 Part A), for Boss.

Boss asked (19 Oct 2026): "Find a way, how the big traders who have the ability to move the market up and down, how do
they think or work, means we will understand how actually the market works, it will be very helpful for our bot."

This page is for **understanding only**. It explains who the large traders are, the jobs they are doing, and the marks
those jobs leave in the data. Nothing here is about moving or manipulating a market. Section 3 lists what the law
forbids.

> **Rule for this page: a web claim is never evidence of a tradeable edge.**
> A paper, a regulator's report or a news story tells us how the market works. It does not tell us that a rule will
> make money for us after charges and slippage. Only a pre-registered, walk-forward test on our own data, with real
> costs and Benjamini–Hochberg across the ideas tried, can say that. Section 6 lists what our own tests have already found.

How to read it:
- Numbers in square brackets, like [4], point to the References at the end. Every reference was checked on 19 Oct
  2026: the page or PDF was opened, or the paper was found by its exact title. Where only a secondary source (press,
  broker) could be reached, the reference says so.
- "Alleged" means a regulator's finding that is not final. Nobody in this guide is described as guilty unless a final
  order or a court says so.
- Related pages in this repo: `research/MARKET_HOW.md` (when and how the Indian index moves, h41) and
  `research/MARKET_DRIVERS.md` (evidence vs folklore on candles, h18). This guide adds the "who and why" view, the
  commodity side and the data-source map.

---

## 0. The short version

1. **The big players are mostly not "betting on direction".** Most large flows are jobs with a fixed purpose:
   - a fund buying or selling because money came in or went out;
   - an index fund following an index change;
   - a market maker quoting both sides and getting rid of its stock;
   - an option dealer hedging the options it sold;
   - a producer or a refiner locking in a price;
   - a commodity index fund rolling its futures.
   Each job has a timetable and a footprint.
2. **They hide their size, because size costs them money.** A big order moves the price against its owner, roughly in
   proportion to the square root of its size [31][32]. So big orders are cut into many small ones and spread over time
   (VWAP, TWAP, iceberg orders) [29][33].
3. **Who wins in Indian F&O:** prop trading firms and FPIs, almost all of it through algorithms. In FY26, 99% of FPI and
   prop profits were made by "algo entities", and 87.7% of individual traders lost money [4].
4. **Price moves through order flow, not through intentions.** A stop-loss cluster, an option hedge or an index
   rebalance moves the price only when it turns into market orders that eat the order book. That is why the same events
   can look like "someone hunted the stops" from the outside.
5. **What a retail bot can see:** daily participant data, weekly CFTC positions, option-chain OI and IV, scheduled
   event calendars, roll and rebalance dates, and its own tick data. It cannot see who is behind a single trade. SEBI
   builds its cases from entity-level data that no retail trader has [6].

---

## 1. Who the big players are

### 1a. Indian index F&O (NSE, BSE)

| group | what they are | main jobs in F&O | size and results we can cite |
|---|---|---|---|
| **FPIs / FIIs** | foreign funds registered with SEBI (pension funds, mutual funds, hedge funds, prop arms of global firms) | hedge their Indian stock holdings with index futures and puts; express a view on India; some run arbitrage and option strategies through algorithms | FY24 gross trading profit in equity F&O ~Rs 28,000 crore; 97% of FPI profits came from algorithmic trading [1][2]. FY26: ~Rs 14,000 crore, down 55% [4]. Index-futures position limits depend on the FPI category [12]. |
| **DIIs and mutual funds** | Indian mutual funds, insurers, banks, pension funds | mostly cash-market buyers fed by SIPs; F&O use is small and mainly hedging or covered calls; **passive index funds and ETFs** must buy and sell when an index changes | MF gross F&O profit ~Rs 3,000 crore in FY26 [4]. Index futures limit: higher of 15% of the index's futures OI or Rs 500 crore [12]. |
| **Proprietary desks, HFT and market makers** | trading members trading their own money, many with co-located servers | quote both sides in options; arbitrage index vs. stocks vs. futures; sell options; run short-term algorithms | Largest category: 47.3% of equity-options premium turnover and 54.7% of equity-derivatives notional turnover in Aug 2026 [5]. FY26 gross profit ~Rs 44,000 crore; the top 10 prop entities took ~75% of it [4]. FY24: ~Rs 33,000 crore, 96% from algorithms [1][2]. |
| **Option writers and dealers** | not a legal category: prop firms, some FPIs, HNIs and many individual clients who sell options | sell premium; hedge the delta, or not | NSE's daily participant file shows Client and Pro as the net index-option writers on most days (our OF1 study, section 6). |
| **Individuals** | retail and HNI traders (resident individuals, HUFs, NRIs, sole proprietors) | buy options, mostly near expiry | 93% lost money over FY22–FY24, more than Rs 1.8 lakh crore in total [1]. FY25: 90.9% lost (revised, Rs 1.12 lakh crore). FY26: 87.7% lost, Rs 91,685 crore [4]. Earlier: 89% lost in FY22 [62]. |

How much is algorithmic? NSE's Market Pulse (Sept 2026) puts algo orders at about **66% of equity-derivatives notional
turnover** in FY26 and FY27 to date (70% in FY25), and about **60% of equity-options premium turnover** in FY26
(59% FY27 to date) [5, Figures 286 and 292]. Co-located servers alone did 53.4% of options premium turnover in FY26 [5].
The same report shows mobile-app trading rising to a record 32.2% of index-options turnover in Aug 2026 [5].

Other facts from SEBI's Aug 2026 study [4], all for FY26 unless marked:
- 59% of index-options turnover happened on the expiry day itself (70% in FY25). Only 3% was in contracts with more than
  seven days left.
- About 23% of individual traders made about 90% of all individual losses. Options were 92% of the losses.
- Individuals paid about Rs 25,000 crore of transaction costs in each of FY25 and FY26.
- The P&L figures by category are gross (before costs) and count squared-off trades only.

#### A real case: SEBI's Jane Street interim order (3 July 2025)

This is the clearest public record of how a very large trader can affect an Indian index, so it is worth knowing in
detail. It is an **ex-parte interim order with prima facie findings, not a final ruling** [6].

- **Who:** four Jane Street group entities (JSI Investments, JSI2 Investments, Jane Street Singapore, Jane Street Asia
  Trading) [6].
- **Period examined:** 1 Jan 2023 to 31 Mar 2025. SEBI looked in detail at 18 trading days, mostly expiry days [6].
- **What SEBI alleged, in neutral terms** [6]:
  - *"Intraday index manipulation"* on 15 of the 18 days. The sample day is 17 Jan 2024:
    - Patch I (09:15–11:47): the group bought about Rs 4,370 crore of BANKNIFTY stocks and stock futures. SEBI says
      the buying was aggressive and large against market volume, and supported the index.
    - At the same time it built about Rs 32,115 crore of bearish index-option positions (bought puts, sold calls),
      7.3 times the size of the cash and futures buying.
    - Patch II (11:49–15:30): it sold the stocks and futures back. SEBI says this pushed the index down, so the much
      larger option book gained more than the cash and futures lost.
    - SEBI puts that day's profit at about Rs 735 crore.
  - *"Extended marking the close"* on the other 3 days (4 Oct 2023, 8 May 2024, 10 Jul 2024): large orders near the
    close of expiry day to move the settlement price. SEBI saw the same pattern on 3 NIFTY days in May 2025.
  - NSE sent the group a caution letter on SEBI's instructions on 6 Feb 2025. SEBI says large positions continued
    after it.
- **What followed:** SEBI barred the entities and impounded Rs 4,843.57 crore [6]. The group deposited the amount in
  escrow, and SEBI issued a compliance update on 14 Jul 2025 [7]. Jane Street disputes the findings and appealed to the
  Securities Appellate Tribunal; hearings were still being adjourned in 2026 [8]. We found no final order.
- **Context:** over the whole period SEBI counts Rs 36,502 crore of group profit across all segments: Rs 43,289 crore in
  index and stock options, minus losses in stock futures, index futures and cash [6].

What this tells a bot builder: the mechanism SEBI describes works **through the cash and futures order book**, and it
needs an option position many times larger than the cash trade. It was found from entity-level trade and position
data. On a price chart such a day looks like an ordinary morning rally that fades (our h18 T3 test, section 6).

### 1b. MCX (Indian commodities)

SEBI tells commodity exchanges to sort every participant into **six categories** and to publish open interest and
turnover by category, for each commodity, every day by 6 pm on T+1 [20][21]:

| category | who it includes (SEBI's words, shortened) [20] |
|---|---|
| Farmers / FPOs | farmers, farmers' cooperatives, farmer producer organisations |
| **Value chain participants (VCPs)** | processors, commercial users (e.g. dal and flour millers), importers, exporters, physical traders, stockists, cash-and-carry players, producers, SMEs/MSMEs, wholesalers |
| Proprietary traders | exchange members trading their own account |
| Domestic financial institutional investors | mutual funds, portfolio managers, AIFs, banks, insurers, pension funds |
| Foreign participants | eligible foreign entities, NRIs and similar |
| Others | everyone else, including clients who gave no self-declaration |

Points to know:
- The category is **self-declared** for each commodity. The exchange can reclassify. Clients with no declaration go to
  "Others". If a category has fewer than 10 participants, the count is shown as "<10" [20].
- MCX publishes this as "Disclosure of Open Interest and Turnover for various categories of Market Participants" [22].
  (The page blocks scripted downloads; it opens in a browser.)
- Genuine hedgers can get a **hedge limit** on top of the normal position limit, under each exchange's hedge policy [20].
- For non-agricultural commodities (bullion, energy, metals) the client position limit is the higher of a fixed number
  or 5% of market-wide OI; the member limit is the higher of 10 times that number or 20% of market-wide OI [20].
- In Aug 2026 individual investors were 34.7% of commodity-derivatives notional turnover and prop traders 53.8% [5].

### 1c. COMEX / NYMEX: the CFTC Commitments of Traders (COT)

MCX gold, silver, crude and natural gas follow the US futures closely, so the US big players matter for our MCX arms.
The CFTC publishes their positions every week.

The **Disaggregated COT** report (for physical commodities) has these groups [23]:

| group | who they are [23] |
|---|---|
| **Producer / Merchant / Processor / User** | firms that produce, process, pack or handle the physical commodity and use futures to hedge it (miners, refiners, oil producers, airlines) |
| **Swap Dealers** | firms that deal mainly in commodity swaps and hedge those swaps with futures. Their clients can be speculators or commercial firms. |
| **Managed Money** | registered CTAs and commodity pool operators, and unregistered funds the CFTC identifies (hedge funds). These are the trend-following and macro funds. |
| **Other Reportables** | every other trader above the reporting level |
| **Non-reportable** | not a group of named traders: total OI minus all reportable positions (the small traders) |

Timing: positions are **as of Tuesday** and the reports are released **every Friday at 3:30 pm US Eastern** (about
01:00–02:00 IST on Saturday), moved for US holidays [24][25]. A market is included when 20 or more traders hold
positions at or above the CFTC's reporting levels [24]. For financial futures there is a separate "Traders in Financial
Futures" report with other groups [23].

---

## 2. How each one works, and the footprint it leaves

### 2a. Executing a big order without moving the price against yourself

**How they think.** A fund that must buy Rs 500 crore of stock faces a trade-off:
- buy fast, and the price runs away from you (market impact);
- buy slowly, and the price may drift away anyway (timing risk).

Almgren and Chriss (2000) turned this into a formula: choose a trading schedule that balances expected impact cost
against the risk of waiting [29]. Kyle (1985) showed why an informed trader spreads its trades out: it hides inside the
noise of other orders, and its information leaks into the price gradually [30].

**The tools** [33][32][55]:

| algorithm | what it does |
|---|---|
| VWAP | trades in line with the day's usual volume curve, to match the volume-weighted average price |
| TWAP | trades equal slices at equal time steps |
| POV (percent of volume) | stays at a fixed share of market volume, e.g. 10% |
| Implementation shortfall | trades faster early to lock in the decision price, slower later (the Almgren–Chriss style) |
| Iceberg | shows only a small part of the order; the rest refills as it fills. Indian brokers offer it on NSE and BSE F&O; the order is sliced into legs placed one after another [60]. |

**What is known about impact.**
- The total price impact of a "metaorder" (all the child orders of one big decision) grows roughly with the **square
  root** of its size. This holds across many markets. Tóth et al. (2011) explain it by liquidity being very thin close to
  the current price [31]. Bouchaud et al. (2018) cover the evidence in depth [32].
- Because big orders are split, the **signs of trades are long-lasting**: buys follow buys for a long time [31][32].
- The BIS found that FX execution algorithms improve matching but can shift risk to the user and may add to sharp moves
  in stress [55].

**Footprint.**
- Steady one-sided flow at a regular pace, often in line with the volume curve.
- Repeated small prints at the same price as a hidden order refills.
- Volume and price drift in the same direction for hours or days. The price often partly comes back after the order
  finishes, because only part of the impact is permanent [32].

**Can we see it?** Partly, with tick data and our own order-flow recorder (best bid/ask and trade prints). Not with
minute candles alone.

### 2b. Liquidity around stop clusters, round numbers and old highs/lows

**What is documented.**
- Osler (2003) studied real orders at a large FX dealing bank. **Take-profit orders cluster at round numbers**, which
  helps explain why trends often stall or reverse at round numbers [34].
- Osler (2005): **stop-loss orders cluster just beyond round numbers**. When they trigger, they cause **price
  cascades**: fast, self-reinforcing moves that **continue** in the same direction [35].
- Prices in general cluster on round numbers. Harris (1991) found the clustering everywhere, stronger at high prices and
  high volatility [36].

**How a large trader thinks about it.** A big buyer needs sellers. Just above a recent high there are many resting buy
stops and some sell limit orders. A large seller can fill a lot of size by selling into the burst of buying when those
stops trigger. A large buyer may prefer to buy *into* a stop cascade of sellers below a low. This is about finding the
other side for big size. It is not a personal hunt for one trader's stop.

**Footprint.** A quick burst of volume and a jump through a level, followed by either:
- continuation (a stop cascade [35]), or
- a stall or reversal (take-profit orders, or a large opposite order that was waiting there [34]).

**Our data:** in h18 T4, a poke beyond a prior high/low that closed back inside did **not** reverse; it drifted on
slightly. Clean breaks continued. That matches Osler's cascades and is why Liquidity 15+5 trades the break (section 6).

### 2c. Market makers: inventory and spreads

**How they think.** A market maker earns the spread and wants to stay flat. Three classic models explain its quotes:
- **Glosten–Milgrom (1985):** some traders know more. The spread exists to cover losses to them (adverse selection),
  even for a risk-neutral, zero-profit dealer [39].
- **Ho–Stoll (1981):** a dealer holding inventory faces price risk. It **shades both quotes** to attract trades that
  reduce its inventory [37].
- **Avellaneda–Stoikov (2008):** the same idea for a modern electronic order book: quote around a "reservation price"
  that moves away from mid as inventory builds [38].

**Footprint.**
- Spreads widen before news and in fast markets (more informed flow, more risk).
- After a big one-sided fill, quotes shift so that the market maker can unload. Short-lived, small reversals follow.
- Depth near the best price is thin and refills quickly [31].

**Can we see it?** The bid-ask spread and its widening, yes, from the broker's quote stream. Inventory, no.

### 2d. Option dealers: delta and gamma hedging

**How they think.** A dealer who sells an option wants no direction risk. It buys or sells the underlying (delta
hedging) and re-hedges as the price moves (gamma).
- If the dealer is **long gamma** (it bought options), hedging means selling into rises and buying into falls. That
  **damps** moves and can **pin** the price near a big strike at expiry.
- If the dealer is **short gamma** (it sold options), hedging means buying into rises and selling into falls. That
  **amplifies** moves.

**What is documented.**
- **Pinning:** US optionable stocks close at strike prices on expiry days more often than chance. Ni, Pearson and
  Poteshman (2005) link it to market makers' hedge rebalancing and, in part, to traders trying to move prices [40].
  Avellaneda and Lipkin (2003) built a model in which delta hedging pulls the price to a strike with large OI [41].
- **Volatility:** hedge rebalancing by option market makers changes stock volatility and the chance of large moves
  [42].
- **Amplification:** Barbon and Buraschi ("Gamma Fragility") find that negative dealer gamma together with illiquidity
  goes with intraday momentum, and positive gamma with reversal [43]. Baltussen, Da, Lammers and Martens (2021) find
  momentum in the last half hour of the day in more than 60 futures markets, 1974–2020, linked to hedging by option
  market makers and leveraged ETFs [44].

**The India caution.** US "GEX" assumes dealers are long calls and short puts. In India the participant file shows
Client and Pro as the net option writers and FIIs as net buyers (OF1). Many sellers do not hedge continuously. So the
sign of "dealer gamma" in India is an assumption, not a fact.

**Footprint.** Calm, range-bound sessions when a lot of gamma sits near spot; faster one-way moves after spot leaves a
big OI zone; some clustering of the expiry close near large strikes.

### 2e. Expiry days in India

The rules changed a lot in 2024–2025. Current state (checked 19 Oct 2026):

| rule | what it says | source |
|---|---|---|
| One weekly index per exchange | each exchange may offer weekly expiry on only one benchmark index, from 20 Nov 2024 | [9] |
| Bigger contracts | index derivative value at least Rs 15 lakh at launch, lot size reviewed to stay within Rs 15–20 lakh, for contracts after 20 Nov 2024 | [9] |
| Extra 2% ELM on expiry day | on short options expiring that day, from 20 Nov 2024 | [9] |
| Upfront option premium | buyers pay the premium upfront | [9] (circular date 1 Feb 2025; SEBI's July 2025 study lists 10 Feb 2025 as the date it took effect [3]) |
| No calendar-spread benefit on expiry day | for positions that include the expiring contract | [9] (same dates as above [3]) |
| Intraday position monitoring | at least four random snapshots a day, more near the close; index options limit Rs 5,000 crore net / Rs 10,000 crore gross, futures-equivalent, from 1 Oct 2025; expiry-day penalties later | [13] |
| Delta-based (FutEq) OI | positions measured as delta-adjusted "futures-equivalent" OI; end-of-day index options limit Rs 1,500 crore net and Rs 10,000 crore gross per PAN | [12] |
| Expiry weekday | every exchange's equity derivatives expire on Tuesday **or** Thursday; one weekly benchmark contract; everything else monthly on the last Tuesday/Thursday | [10] |
| NSE Tuesday, BSE Thursday | approved June 2025, for contracts expiring on or after 1 Sep 2025 | [11] (press); our lake's 13 Oct 2026 NIFTY session is a Tuesday expiry |

**How the big players work on expiry day.**
- Option writers collect the last of the time value; on expiry day options can lose most of their value within hours.
- Positions are closed or rolled, so OI falls fast and the order book thins.
- The settlement price is set from the last part of the day, so the close matters a lot to large option books. That is
  the exact window of SEBI's "marking the close" allegation [6] and of the extra position snapshots near the close [13].

**Footprint.** Very high option volume (59–70% of index-option turnover is 0DTE [4]), a busy last hour, sharp moves in
cheap options, and sometimes a close near a big strike.

### 2f. Roll periods

**Index futures.** Near the monthly expiry, funds and arbitrageurs roll from the current to the next month. The spread
between the two months ("roll cost") and the shift of OI from one to the other show this. Since the 2024 circular, a
calendar spread that includes the contract expiring that day gets no spread margin benefit on expiry day [9], which
gives index rollers a reason to roll before expiry day.

**Commodity index funds: the "Goldman roll".**
- The S&P GSCI rolls its futures over the **5th to 9th business day** of the month, about 20% a day (index
  methodology, seen through a search excerpt [46]).
- Mou (2010) found that this predictable rolling had a significant price impact, and that front-running it earned
  large excess returns from 2000 to March 2010 [45].
- **Footprint:** the spread between the front and next contract moves against the roller during the roll window.

**MCX.** Bullion and some other MCX contracts have a **tender / staggered delivery period** in the last days before
expiry. Positions left open may lead to delivery, and brokers ask clients to close earlier [61] (a broker guide; check
the MCX contract specification for each contract). Speculators therefore leave the expiring contract early, and volume
moves to the next month before the official expiry.

### 2g. Rebalancing and index flows

**Index inclusion and exclusion.**
- Index funds must buy a stock that joins an index and sell one that leaves. Prices react:
  - S&P 500 additions earned abnormal returns that did not vanish for at least ten days (Shleifer 1986) [47];
  - Harris and Gurel (1986) found a rise of more than 3% after the announcement, almost fully reversed in about two
    weeks (temporary "price pressure") [48].
- India: on NIFTY changes from 1996 to 2003, prices rose about 1.47% on the effective day and the move reversed by the
  ninth day; there was no announcement-day effect (Kumar, NSE research paper) [49].
- NIFTY 50 is reviewed twice a year (the changes usually take effect at the end of March and September; see NSE
  Indices' methodology, not separately verified here).
- **MSCI** reviews its indexes every quarter (Feb, May, Aug, Nov) and publishes the dates ahead. Example: the Nov 2026
  review is announced on 11 Nov and takes effect on 1 Dec [50]. Funds that track MSCI indexes trade on those dates,
  so these are flow dates for Indian stocks too.

**Month-end and quarter-end rebalancing.** Pension funds and others rebalance between stocks and bonds on a calendar or
when weights drift. Harvey, Mazzoleni and Melone (2025) find that when stocks are overweight, the selling lowers equity
returns by about 17 basis points the next day, and that the predictability lets others trade ahead of it [51].

**Footprint.** Very large volume in the closing minutes of the effective day (index funds aim to match the closing
price); a
price move in the added or removed stocks that partly reverses; for index futures, month-end flows.

### 2h. News and event positioning

**How they think.** Big funds reduce or hedge positions before a scheduled event whose result they cannot know, and act
on the surprise after it. Volatility before the event is priced into options (implied volatility rises, then falls
after the event: "IV crush").

| market | scheduled events that matter |
|---|---|
| Indian index | RBI monetary policy, the Union Budget, election results, results of heavyweight stocks, US Fed (FOMC) and US CPI overnight |
| MCX bullion | US CPI, payrolls, FOMC; the US dollar |
| MCX crude / natural gas | EIA Weekly Petroleum Status Report (normally Wednesday 10:30 am US Eastern; holiday weeks move) [53]; EIA storage report for gas; OPEC+ meetings |

**A documented pre-event pattern.** Lucca and Moench (2015) found large average US stock returns in the hours *before*
scheduled FOMC announcements, a pattern not seen before other major US macro news [52]. That is a US finding; it says
nothing about NIFTY on RBI days.

**Footprint.** Rising implied volatility and falling volume before the event; a jump and a volume spike at the release
time; wider spreads around the release.

---

## 3. What the rules allow and forbid (short)

### 3a. India (SEBI, NSE, BSE, MCX)

**Allowed:** hedging, arbitrage, market making, large orders cut into slices, algorithms through approved brokers,
writing options within margin and position limits.

**Forbidden (examples):**
- **SEBI PFUTP Regulations, 2003** ban manipulative, fraudulent and unfair trade practices [16]. The original
  regulation 4(2) lists, among others:
  - (a) any act that creates a false or misleading appearance of trading;
  - (e) any act or omission amounting to manipulation of the price of a security;
  - (g) transactions without the intention of performing them or changing ownership (for example, wash trades);
  - (n) circular transactions between intermediaries;
  - (q) front-running a substantial client order, including through futures or options;
  - (r) planting false or misleading news.
  The regulations have been amended several times since 2003; the PDF we read is the original text [16].
- **Spoofing and layering** (large orders placed to be cancelled, to fool others about demand) is treated as creating a
  false appearance of trading. Example: in 2025 SEBI barred Patel Wealth Advisors and four directors pending further
  orders, alleging 292 spoofing instances across 173 securities in cash and F&O from Jan 2022 to Jan 2025, with about
  Rs 3.22 crore of alleged gains (press report of an interim order) [17].
- **Position limits** (section 2e table): index options per PAN in FutEq terms; index futures by participant category
  (FPI Category I / MFs / prop / clients: higher of 15% of the index's futures OI or Rs 500 crore; FPI Category II:
  10% or 5% depending on the type) [12]. Positions above these need matching stock or cash holdings [12].
- **Order-to-trade ratio (OTR):** exchanges charge members whose algo orders are cancelled or modified far more often
  than they trade. NSE's slabs go from nil (below 50) up to 25 paise per algo order (2,000 or more), and a member at
  2,000+ on three days in 30 cannot place orders for the first 15 minutes of the next day [18]. From 6 Apr 2026,
  equity-option orders within ±40% of the premium or ±Rs 20 are exempt, and designated market makers' algo orders are
  not counted [19].
- **Retail algo framework (SEBI, 4 Feb 2025)** [14]: algo orders through broker APIs are tagged with an exchange-given
  identifier; open APIs are not allowed (unique vendor/client API key, static IP, two-factor login); strategies above an
  order-per-second threshold must be registered; brokers must keep a kill switch; "black box" algo providers must
  register as research analysts. Its timelines were extended in 2025 [15].
- **Pre-trade risk controls and kill switches** are expected of every trading venue and participant in IOSCO's 2011
  recommendations on algorithmic and high-frequency trading [54], and SEBI's retail algo framework keeps the broker's
  kill switch [14].
- **MCX:** commodity position limits and hedge limits as in section 1b [20].

### 3b. United States (CFTC)

- Dodd-Frank section 747 added Commodity Exchange Act section 4c(a)(5), which bans **spoofing** ("bidding or offering
  with the intent to cancel the bid or offer before execution"), violating bids or offers, and disorderly trading in
  the closing period. The CFTC explained it in 2013 interpretive guidance [26].
- **Examples:**
  - JPMorgan paid a record **$920.2 million** in 2020 for spoofing in precious-metals and US Treasury futures on
    COMEX, NYMEX and CBOT over at least eight years [27].
  - Navinder Sarao was ordered to pay more than $38 million (2016). The order found that his layering program ran for
    over 4 hours 25 minutes on the 2010 Flash Crash day and contributed to an extreme order-book imbalance in E-mini S&P
    futures [28].

### 3c. Real behaviour vs folklore

| folklore | what the evidence says |
|---|---|
| "Operators hunt *my* stop." | Nobody sees individual retail stops. What is real: stops **cluster** at obvious levels (round numbers, old highs/lows), and when they trigger they cause fast moves [34][35]. Big traders look for **liquidity** there because that is where the other side is. |
| "Max pain is a magnet at expiry." | Pinning to *some* strike is documented in the US [40][41]. "Max pain" as a magnet is not: our R16 study found that max pain predicts the expiry close **worse than "no change"** (0.74% vs 0.43% error on BANKNIFTY, 0.58% vs 0.52% on NIFTY), and OF1 found max-OI strikes are neither walls nor magnets. |
| "FII data tells you tomorrow's direction." | FIIs trend-follow on the same day. Follow-the-FII signals passed on 2012–2025 but **0 of 15 held** on the locked last 12 months (OF1). |
| "Big players always win." | Prop and FPI algos win as a group [1][4], but their edge is speed, spread capture, volatility selling and arbitrage, not knowing the next candle. Their FY26 gross profits fell sharply (FPIs −55%) [4]. |
| "The Jane Street pattern can be faded every expiry." | SEBI alleged it on specific days found with entity data [6]. Our h18 T3 test found no general morning-to-afternoon reversal on BANKNIFTY expiries (correlation 0.01). |
| "Dealer GEX tells the direction." | It is linked to the **size** of moves [42][43]; direction is not shown. Our R28 found the same: size of the day only. |

---

## 4. Summary table: behaviour, who, footprint, where we could see it

All sources below are public and legal to use for research. "Free" means no fee; some sites block scripts and need a
browser or polite request headers.

| behaviour | who | observable footprint | where we could see it (source; frequency; cost; legal) |
|---|---|---|---|
| Net positioning in index futures and options | FII, DII, Pro, Client | long/short contracts by group and product | NSE participant-wise OI, `fao_participant_oi_DDMMYYYY.csv` [56]; daily after close; free; public. Also the participant-wise volume file. |
| FII derivatives buying/selling | FPIs | contracts and Rs crore bought/sold, end-of-day OI | NSE FII derivatives statistics, `fii_stats_DD-Mon-YYYY.xls` [57]; daily; free; public |
| FPI cash and debt flows | FPIs | gross buy, gross sell, net by asset class | NSDL FPI "Daily Trends" [58] (also mirrored by SEBI); daily (provisional, then confirmed); free; public |
| Large single trades in stocks | funds, promoters, block buyers | bulk deals and block-window deals, with the buyer's or seller's name | NSE bulk and block deals report [59]; daily; free; public |
| Who wins and loses in F&O | all categories | P&L by category, algo share, expiry-day share | SEBI studies [1][3][4]; yearly; free |
| Algo and co-location share | prop/HFT | turnover share by channel and algo/non-algo | NSE Market Pulse [5]; monthly; free |
| Option writing and hedging, pinning | option writers, dealers | OI build-up by strike, IV and skew, PCR, our GEX | broker option-chain API (Dhan/Zerodha); minute; free with an account; legal for own use. Our lake and Data Brain `gex_*` features. |
| Big order execution (VWAP/TWAP/iceberg) | funds, prop | one-sided trade flow, repeated same-price prints, large prints | tick data and best bid/ask from the broker feed; our app's order-flow recorder (`hunt/of1/footprints.py`); tick-level; free with an account; own use only |
| Stop clusters and liquidity | everyone | volume bursts and jumps through round numbers and old highs/lows | our minute and tick data plus level maps (Liquidity arm); minute/tick; free |
| Market-maker spread behaviour | prop/HFT | spread width and depth changes | broker quote stream (market depth); tick; free with an account |
| Index rebalancing | index funds, ETFs, FPIs | closing-auction volume, moves in added/removed stocks | NSE Indices announcements; MSCI review calendar [50]; twice a year / quarterly; free |
| Futures rolls | arbitrageurs, funds | OI moving to next month, roll spread | NSE/MCX bhavcopy (daily); minute futures from broker; free |
| Commodity hedgers and funds (US) | producers, swap dealers, managed money | net long/short by group | CFTC Disaggregated COT [23][24]; weekly (Tue data, Fri 3:30 pm ET); free; public |
| Commodity hedgers and VCPs (India) | farmers/FPOs, VCPs, prop, DFIs, foreign | OI and turnover by category, per commodity | MCX category-wise disclosure [22] (required by SEBI [20][21]); daily by 6 pm T+1; free; public (browser download) |
| Event positioning | everyone | IV rising before events, jumps at release | event calendar (`brain/calendar_data/events.csv` has Budget and RBI only), EIA schedule [53]; scheduled; free |
| Manipulation cases | regulators | case records with entity-level detail | SEBI orders, CFTC press releases [6][17][27][28]; as issued; free; use as background, never as a signal |

---

## 5. Folklore vs evidence (box)

> **Evidence (documented in papers or regulator records):**
> - Big orders are sliced; impact grows about with the square root of size [29][31][32].
> - Stop-loss orders cluster just beyond round numbers and cause cascades that continue; take-profits cluster at round
>   numbers and stall moves [34][35].
> - Market makers widen spreads when informed flow is likely and shade quotes to unload inventory [37][38][39].
> - Option hedging changes volatility; pinning to strikes happens on some US expiries [40][41][42][43][44].
> - Index additions and deletions cause temporary price pressure, in the US and in India [47][48][49].
> - Predictable rolls and rebalances can be traded against by others [45][51].
> - In India, prop and FPI algorithms take most F&O profits; most individuals lose [1][4].
>
> **Folklore (no support, or our tests say no):**
> - Someone hunts individual retail stops.
> - Max pain or max-OI strikes pull the expiry price.
> - "Smart money sweeps the stops, then reverses" as a reliable rule.
> - FII positioning tells the next day's direction.
> - Dealer GEX tells direction.
>
> **Never forget:** even true mechanisms are usually too small, too rare or too fast to pay for an option buyer's costs
> (an ATM round trip costs about Rs 133 on BANKNIFTY and Rs 98 on NIFTY, R16).

---

## 6. What our own research already found

These are our own pre-registered tests. Details in `research/FINDINGS.md` and each study file. Each rule's signal is on
the underlying index unless stated.

| topic | study | what we found |
|---|---|---|
| Who placed the orders and who made money | OF1 (`HUNT_OF1_ORDERFLOW_INDIA.md`) | 3,632 days, 2012–2026 (end-of-day, gross). Pro made money on index options in 13 of 15 years; FIIs made money on futures on trend days; Clients lost on both. FII trend-follows (same-day correlation +0.44), Client takes the other side (−0.35), DII buys after falls. Client and Pro are the net option writers on 97% / 92% of days. 15 follow-the-group signals passed on 2012–2025, **0 of 15 held** on the locked last 12 months (246 days). FPI cash flow predicts nothing. Max-OI strikes are neither walls nor magnets (873 expiries). |
| Dealer gamma (GEX) | R28 (`HUNT_R28_GEX.md`) | Negative GEX at 10:00 came before a bigger rest-of-day range on all 5 indices (e.g. BANKNIFTY 0.87% vs 0.75%, 199 / 397 design days). It does not tell trend from chop. 0 of 10 GEX filters on the arms passed. |
| What is predictable | R16 (`HUNT_R16_PREDICTABILITY.md`) | The **size** of the next 30–60 minutes is predictable (holdout error 15–20% below the time-of-day average). **Direction** is not (AUC 0.509–0.536). Max pain is worse than "no change" at expiry. |
| Gamma near spot, stop sweeps, expiry reversal | h18 (`HUNT_H18.md`, `MARKET_DRIVERS.md`) | More gamma near spot → calmer next 15–60 min (held in the holdout). Sweeps beyond prior highs/lows drift on, they do not reverse. On BANKNIFTY expiries in SEBI's window, morning and afternoon returns are uncorrelated (0.01). |
| Liquidity arm direction filters | R79 | 0 of 6 filters (Supertrend, a 10-candle wait, other indices agreeing) beat the plain rules. |
| Liquidity entry filters | R80 | 0 of 10 pass, including **round numbers** (V3) and beyond-yesterday's-range (V5). The morning breaks carry the arm. |
| Liquidity exits and trade management | R81 | 0 of 66 pass. The runners are the edge. |
| MCX events and rolls | R82, R83 | US hour on CPI/NFP/FOMC days lost; EIA fade/follow, OPEC+ and the crude rollover week (E12) all lost after costs. |
| Big players' footprints (new angles) | R86 (`HUNT_R86_BIG_PLAYERS.md`) | 0 of 23 pass: FII positioning levels and 5-day shifts, Client option writing, CFTC managed money on MCX, failed pokes (stop runs) at obvious levels, VWAP reversion on busy days, GEX as an extra range input, NIFTY rollover, MCX roll windows. Nearest misses (q 0.115): COMEX silver managed-money extremes (contrarian, carried by its top 5) and gold being ~14% busier before a GOLDM roll. |
| Event straddles | FINDINGS (straddle study) | The 11 RBI days netted −Rs 3.6k; event profits came from one day (2024 election result). |

**What this means for the bot.**
- The big players' footprints we can see (participant OI, GEX, OI walls, max pain, round numbers) have so far told us
  about the **size** of moves, not their **direction**.
- The one mechanism our arms already use is the documented one: breaks through obvious levels, where stops and
  liquidity sit, tend to continue [35]. Liquidity 15+5 trades the break.
- Good next questions (each needs its own pre-registration; none is evidence of an edge yet):
  - Does the MCX category-wise OI (VCP vs prop vs foreign) or the CFTC managed-money position add anything to the MCX
    arms' **size** forecasts? Not tested.
  - Our own tick recordings could show slicing (repeated same-price prints) and spread widening before Liquidity
    breaks. No recordings have been uploaded yet (OF1).
  - Index rebalance days and the 15:15–15:30 closing window: is the range or volume different enough to change stops?
    Not tested.

---

## 7. Claims we checked but left out or softened

- We found the S&P GSCI roll window (5th–9th business day) only through a search excerpt of the index methodology [46];
  the methodology PDF itself blocked our download.
- NIFTY 50 review timing (end of March / September) comes from NSE Indices' methodology as reported by fund houses; we
  did not open the methodology PDF, so it is stated without a reference.
- MCX tender-period lengths differ by contract and come from a broker guide [61]; check the MCX contract specification.
- The Patel Wealth Advisors spoofing numbers come from a press report of SEBI's interim order [17]; we did not find
  the order on SEBI's site by search. An older internal note (`MARKET_DRIVERS.md`) says "621 instances"; the press
  report we read says 292 instances, and we use 292 here.
- We did not find a single official figure for "algo share of all Indian F&O volume". We use NSE's Market Pulse charts
  [5], which give algo share of turnover by segment.
- The later SEBI amendments to the PFUTP Regulations (2013, 2019, 2020, 2025) were not read in full; section 3a quotes
  the original 2003 text [16].

---

## References

Regulators and exchanges (India)

1. SEBI, press release PR No. 37/2024, 23 Sep 2024, "Updated SEBI study reveals 93% of individual traders incurred
   losses in equity F&O between FY22 and FY24; aggregate losses exceed ₹1.8 lakh crores over three years".
   https://www.sebi.gov.in/media-and-notifications/press-releases/sep-2024/updated-sebi-study-reveals-93-of-individual-traders-incurred-losses-in-equity-fando-between-fy22-and-fy24-aggregate-losses-exceed-1-8-lakh-crores-over-three-years_86906.html
2. Moneylife (press coverage of [1], used for the FY24 category and algo figures):
   https://www.moneylife.in/article/93-percentage-of-individual-traders-lost-rs18-lakh-crore-in-equity-fo-in-past-3-years-sebi/75210.html
3. SEBI, "Comparative study of growth in Equity Derivatives Segment vis-à-vis Cash Market after recent measures", July
   2025. https://www.sebi.gov.in/sebi_data/attachdocs/jul-2025/1751900271726.pdf
4. SEBI (Patankar and Rath), "Profitability of Individual Traders in the Equity Derivatives Segment (FY25–FY26)",
   August 2026. https://www.sebi.gov.in/sebi_data/attachdocs/aug-2026/1787233506209.pdf
5. NSE, Market Pulse, September 2026 (Vol. 8, Issue 9).
   https://nsearchives.nseindia.com//web/mediaattachment/2026-09/Market_Pulse_September_2026_Final.pdf
6. SEBI, Interim Order in the matter of Index manipulation by Jane Street Group, WTM/AN/MRD/MRD-SEC-3/31516/2025-26,
   3 Jul 2025. https://www.sebi.gov.in/sebi_data/attachdocs/jul-2025/1751584518593.pdf
7. SEBI, PR No. 83/2025, "Update on Interim Order Compliance by Jane Street", 14 Jul 2025.
   https://www.sebi.gov.in/media-and-notifications/press-releases/jul-2025/update-on-interim-order-compliance-by-jane-street_95237.html
8. Business Today, "Jane Street vs Sebi: SAT adjourns hearing in market manipulation case", 25 Feb 2026.
   https://www.businesstoday.in/amp/markets/story/jane-street-vs-sebi-sat-adjourns-hearing-in-market-manipulation-case-517925-2026-02-25
9. SEBI circular SEBI/HO/MRD/TPD-1/P/CIR/2024/132, 1 Oct 2024, "Measures to Strengthen Equity Index Derivatives
   Framework for Increased Investor Protection and Market Stability".
   https://www.sebi.gov.in/legal/circulars/oct-2024/measures-to-strengthen-equity-index-derivatives-framework-for-increased-investor-protection-and-market-stability_87208.html
10. SEBI circular SEBI/HO/MRD/MRD-TPD-1/P/CIR/2025/76, 26 May 2025, "Final Settlement Day (Expiry Day) for Equity
    Derivatives Contracts".
    https://www.sebi.gov.in/legal/circulars/may-2025/final-settlement-day-expiry-day-for-equity-derivatives-contracts_94189.html
11. Business Standard, "Sebi nod for Tuesday, Thursday expiry of derivatives contracts on NSE, BSE", 17 Jun 2025.
    https://www.business-standard.com/amp/markets/news/bse-gets-sebi-approval-for-thursday-expiry-of-derivatives-contracts-125061700795_1.html
12. SEBI circular SEBI/HO/MRD/TPD-1/P/CIR/2025/79, 29 May 2025, "Measures for Enhancing Trading Convenience and
    Strengthening Risk Monitoring in Equity Derivatives".
    https://www.sebi.gov.in/legal/circulars/may-2025/measures-for-enhancing-trading-convenience-and-strengthening-risk-monitoring-in-equity-derivatives_94293.html
13. SEBI circular, 1 Sep 2025, "Framework for Intraday Position Limits Monitoring for Equity Index Derivatives".
    https://www.sebi.gov.in/legal/circulars/sep-2025/framework-for-intraday-position-limits-monitoring-for-equity-index-derivatives_96376.html
14. SEBI circular SEBI/HO/MIRSD/MIRSD-PoD/P/CIR/2025/0000013, 4 Feb 2025, "Safer participation of retail investors
    in Algorithmic trading".
    https://www.sebi.gov.in/legal/circulars/feb-2025/safer-participation-of-retail-investors-in-algorithmic-trading_91614.html
15. SEBI circular, 30 Sep 2025, "Extension of timeline for implementation of SEBI circular dated February 04, 2025 on
    Safer participation of retail investors in Algorithmic trading".
    https://www.sebi.gov.in/legal/circulars/sep-2025/extension-of-timeline-for-implementation-of-sebi-circular-dated-february-04-2025-on-safer-participation-of-retail-investors-in-algorithmic-trading-_96979.html
16. SEBI (Prohibition of Fraudulent and Unfair Trade Practices relating to Securities Market) Regulations, 2003
    (original text). https://www.sebi.gov.in/acts/pfutpregu.pdf
17. Moneylife, "Patel Wealth Advisors, 4 directors barred from markets over order spoofing charges" (report of a SEBI
    interim order, 2025).
    https://moneylife.in/article/patel-wealth-advisors-4-directors-barred-from-markets-over-order-spoofing-charges/77003.html
18. NSE circular 447/2020 (NSE/SURV/45016), 14 Jul 2020, "High Order to Trade Ratio (OTR)".
    https://archives.nseindia.com/content/circulars/SURV45016.pdf
19. SEBI circular HO/47/11/16(2)2025-MRD-POD2/I/4113/2026, 4 Feb 2026, "Revision of Order-to-Trade Ratio (OTR)
    framework". https://www.sebi.gov.in/sebi_data/attachdocs/feb-2026/1770204200812.pdf
20. SEBI, Master Circular for Commodity Derivatives Segment, SEBI/HO/MRD/MRD-PoD-1/P/CIR/2023/136, 4 Aug 2023
    (sections 1.15.4, 3.6, 3.7). https://www.sebi.gov.in/sebi_data/attachdocs/aug-2023/1691151096694.pdf
21. SEBI circular SEBI/HO/CDMRD/DNPMP/CIR/P/2019/08, 4 Jan 2019, "Disclosures by Stock Exchanges for commodity
    derivatives".
    https://www.sebi.gov.in/legal/circulars/jan-2019/disclosures-by-stock-exchanges-for-commodity-derivatives_41545.html
22. MCX, "Disclosure of Open Interest and Turnover for various categories of Market Participants".
    https://www.mcxindia.com/market-operations/trading-survelliance/TS-reports/disclosures-open-interest-turnover-and-top-participants/disclosure-of-open-interest-and-turnover-for-various-categories-of-market-participants

United States (CFTC)

23. CFTC, Disaggregated Commitments of Traders, Explanatory Notes.
    https://www.cftc.gov/MarketReports/CommitmentsofTraders/DisaggregatedExplanatoryNotes/index.htm
24. CFTC, About the COT Reports. https://www.cftc.gov/MarketReports/CommitmentsofTraders/AbouttheCOTReports/index.htm
25. CFTC, COT Release Schedule. https://www.cftc.gov/MarketReports/CommitmentsofTraders/ReleaseSchedule/index.htm
26. CFTC, Antidisruptive Practices Authority: interpretive guidance and policy statement (78 Fed. Reg. 31890, May
    2013). https://www.cftc.gov/PressRoom/PressReleases/ssLINK/2013-12365a
27. CFTC, Release 8260-20, JPMorgan to pay $920.2 million for spoofing and manipulation, 29 Sep 2020.
    https://www.cftc.gov/PressRoom/PressReleases/8260-20
28. CFTC, Release 7486-16, Navinder Singh Sarao ordered to pay more than $38 million, Nov 2016.
    https://www.cftc.gov/PressRoom/PressReleases/7486-16

Academic and books

29. Almgren, R. and Chriss, N. (2000), "Optimal execution of portfolio transactions", Journal of Risk 3(2).
    https://www.risk.net/journal-risk/2161150/optimal-execution-portfolio-transactions
30. Kyle, A. S. (1985), "Continuous Auctions and Insider Trading", Econometrica 53(6), 1315–1335.
    https://www.econometricsociety.org/publications/econometrica/1985/11/01/continuous-auctions-and-insider-trading
31. Tóth, B., Lempérière, Y., Deremble, C., de Lataillade, J., Kockelkoren, J. and Bouchaud, J.-P. (2011), "Anomalous
    price impact and the critical nature of liquidity in financial markets", Physical Review X 1, 021006.
    https://arxiv.org/abs/1105.1694
32. Bouchaud, J.-P., Bonart, J., Donier, J. and Gould, M. (2018), "Trades, Quotes and Prices: Financial Markets Under
    the Microscope", Cambridge University Press. https://doi.org/10.1017/9781316659335
33. Lehalle, C.-A. and Laruelle, S. (2013), "Market Microstructure in Practice", World Scientific.
    https://worldscientific.com/worldscibooks/10.1142/10739
34. Osler, C. L. (2003), "Currency Orders and Exchange Rate Dynamics: An Explanation for the Predictive Success of
    Technical Analysis", Journal of Finance 58(5). Working paper: NY Fed Staff Report 125.
    https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr125.html
35. Osler, C. L. (2005), "Stop-loss orders and price cascades in currency markets", Journal of International Money and
    Finance 24(2), 219–241. Working paper: NY Fed Staff Report 150.
    https://www.newyorkfed.org/medialibrary/media/research/staff_reports/sr150.html
36. Harris, L. (1991), "Stock Price Clustering and Discreteness", Review of Financial Studies 4(3), 389–415.
    https://ideas.repec.org/a/oup/rfinst/v4y1991i3p389-415.html
37. Ho, T. and Stoll, H. R. (1981), "Optimal dealer pricing under transactions and return uncertainty", Journal of
    Financial Economics 9(1), 47–73. https://ideas.repec.org/a/eee/jfinec/v9y1981i1p47-73.html
38. Avellaneda, M. and Stoikov, S. (2008), "High-frequency trading in a limit order book", Quantitative Finance 8(3),
    217–224. https://math.nyu.edu/~avellane/HighFrequencyTrading.pdf
39. Glosten, L. R. and Milgrom, P. R. (1985), "Bid, ask and transaction prices in a specialist market with
    heterogeneously informed traders", Journal of Financial Economics 14, 71–100.
    https://ideas.repec.org/a/eee/jfinec/v14y1985i1p71-100.html
40. Ni, S. X., Pearson, N. D. and Poteshman, A. M. (2005), "Stock price clustering on option expiration dates",
    Journal of Financial Economics 78(1), 49–87.
    https://scholars.hkbu.edu.hk/en/publications/stock-price-clustering-on-option-expiration-dates-3/
41. Avellaneda, M. and Lipkin, M. D. (2003), "A market-induced mechanism for stock pinning", Quantitative Finance 3,
    417–425. https://math.nyu.edu/~avellane/stock_pinning.html
42. Ni, S. X., Pearson, N. D., Poteshman, A. M. and White, J. (2021), "Does Option Trading Have a Pervasive Impact on
    Underlying Stock Prices?", Review of Financial Studies 34(4), 1952–1986.
    https://ideas.repec.org/a/oup/rfinst/v34y2021i4p1952-1986..html
43. Barbon, A. and Buraschi, A. (2021), "Gamma Fragility", working paper (SSRN 3725454).
    https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3725454
44. Baltussen, G., Da, Z., Lammers, S. and Martens, M. (2021), "Hedging demand and market intraday momentum", Journal
    of Financial Economics 142(1), 377–403. https://pure.eur.nl/en/publications/hedging-demand-and-market-intraday-momentum/
45. Mou, Y. (2010), "Limits to Arbitrage and Commodity Index Investment: Front-Running the Goldman Roll", SSRN 1716841.
    https://papers.ssrn.com/abstract=1716841
46. S&P Dow Jones Indices, S&P GSCI Dynamic Roll methodology (roll window seen through a search excerpt; the PDF blocked
    our download). https://spglobal.com/spdji/pt/documents/methodologies/methodology-sp-gsci-dynamic-roll.pdf
47. Shleifer, A. (1986), "Do Demand Curves for Stocks Slope Down?", Journal of Finance 41(3), 579–590.
    https://shleifer.scholars.harvard.edu/publications/do-demand-curves-stocks-slope-down
48. Harris, L. and Gurel, E. (1986), "Price and Volume Effects Associated with Changes in the S&P 500 List: New
    Evidence for the Existence of Price Pressures", Journal of Finance 41(4), 815–829.
    https://doi.org/10.1111/j.1540-6261.1986.tb04550.x
49. Kumar, S. S. S., "Price and Volume Effects of S&P CNX Nifty Index Reorganizations", NSE research paper.
    https://nsearchives.nseindia.com/content/research/comppaper90.pdf
50. MSCI, "MSCI Announces the Next Eight Index Review Dates".
    https://www.msci.com/eqb/pressreleases/archive/ir_dates.pdf
51. Harvey, C. R., Mazzoleni, M. G. and Melone, A. (2025), "The Unintended Consequences of Rebalancing", NBER Working
    Paper 33554. https://www.nber.org/papers/w33554
52. Lucca, D. O. and Moench, E. (2015), "The Pre-FOMC Announcement Drift", Journal of Finance 70(1), 329–371.
    https://ideas.repec.org/a/bla/jfinan/v70y2015i1p329-371.html
53. US EIA, Weekly Petroleum Status Report release schedule.
    https://www.eia.gov/petroleum/supply/weekly/schedule.php

International bodies

54. IOSCO Technical Committee (2011), "Regulatory Issues Raised by the Impact of Technological Changes on Market
    Integrity and Efficiency", Final Report FR09/11. https://www.iosco.org/library/pubdocs/pdf/IOSCOPD361.pdf
55. BIS Markets Committee (2020), "FX execution algorithms and market functioning", Markets Committee Paper No 13.
    https://www.bis.org/publ/mktc13.htm

Data sources

56. NSE participant-wise open interest, daily CSV (example: 16 Oct 2026).
    https://nsearchives.nseindia.com/content/nsccl/fao_participant_oi_16102026.csv
57. NSE FII derivatives statistics, daily XLS (example: 16 Oct 2026).
    https://nsearchives.nseindia.com/content/fo/fii_stats_16-Oct-2026.xls
58. NSDL, FPI Investments, Daily Trends. https://www.fpi.nsdl.co.in/web/Reports/Latest.aspx
59. NSE, Bulk and block deals report. https://www.nseindia.com/report-detail/display-bulk-and-block-deals

Secondary (broker and press)

60. Zerodha support, "What are Iceberg orders, and how to use them?".
    https://support.zerodha.com/category/trading-and-markets/product-and-order-types/order/articles/iceberg-orders
61. Groww, "What is Tender Period in MCX?". https://groww.in/blog/what-is-tender-period-in-mcx
62. Business Standard, "Sebi study suggests 89% retail traders in equity F&O suffered losses", Jan 2023.
    https://www.business-standard.com/amp/article/markets/sebi-study-suggests-89-retail-traders-in-equity-f-o-suffered-losses-123012501466_1.html
