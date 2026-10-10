# MCX guide for Boss: what to trade, what it costs, what drives it, what is worth testing

Written 8-9 Oct 2026 by M1 (MCX market researcher). For Boss and the testing agents M2, M3, M4.
Frame: Zerodha, capital Rs 1,00,000, fixed lots, option BUYING preferred, no option selling. Futures (long or short) are
studied here, with the risk flagged.

- Code: `research/hunt/m1/` (`fetch_m1.py`, `chain_stats.py`, `daily_stats.py`, `quick_evidence.py`, `costs.py`,
  `probe_rolling.py`).
- Notes, raw downloads, data, logs: `scratchpad/hunt/m1/` (`raw/`, `data/`, `NOTES.md`, `*_stats.txt`,
  `quick_evidence.txt`, `costs.txt`, `probe_rolling.txt`).
- Live numbers are from 7-8 Oct 2026: Zerodha's commodity margin page (updated 8 Oct), the Kite instrument list,
  a Dhan option-chain snapshot taken after the 8 Oct close, and Dhan continuous daily futures (2012 to 7 Oct 2026).
- Nothing committed. `android/` untouched.

---

## Verdict (read this first)

1. **With Rs 1 lakh, MCX gives you six usable option books and about ten usable mini futures.**
   - Options worth looking at, all near-month only: **CRUDEOIL, NATURALGAS, GOLDM, SILVERM**.
     CRUDEOILM and NATGASMINI options also trade a lot, but Rs 40 of brokerage on a Rs 2,500-3,500 premium costs
     about 2% per round trip.
   - Options on GOLD (1 kg) and SILVER (30 kg) cost Rs 1.6-2.7 lakh per ATM lot. They are out of reach.
     Copper, zinc and MCX index options barely trade.
   - Next-month options of every commodity are dead: almost no trades and spreads of 17-97%.
   - Futures you can hold 1 lot of: CRUDEOILM (Rs 27k margin), NATGASMINI (Rs 13k), SILVERMIC (Rs 29k),
     GOLDTEN (Rs 14k), GOLDPETAL (Rs 1.4k), ZINCMINI (Rs 39k), ALUMINI (Rs 31k), LEADMINI (Rs 14k), NATURALGAS (Rs 65k).
     The big lots (CRUDEOIL Rs 2.7 lakh, GOLDM Rs 1.4 lakh, SILVERM Rs 1.4 lakh, GOLD Rs 13.8 lakh) do not fit.
2. **Costs on MCX are low; the edge is the problem.**
   - A CRUDEOIL ATM option costs about Rs 150 a round trip (0.6% of premium). GOLDM and SILVERM options cost about
     Rs 90 (0.35%). Mini futures cost Rs 70-170 (0.04-0.11% of notional).
   - Our two earlier crude studies already showed that **intraday direction on crude is a coin flip** (NN_CRUDE: AUC
     0.48-0.51 on the locked holdout) and that **buying crude straddles loses** (STRAD_CRUDE: 0 of 1,496 variants paid).
3. **The one family with strong outside evidence is trend following over weeks to months (time-series momentum).**
   - It works across a century of futures data (Moskowitz-Ooi-Pedersen 2012, Hurst-Ooi-Pedersen 2017).
   - On MCX itself, my rough check (9 liquid futures, 2012-2026, not a validated test) gives a 12-month trend
     portfolio t = 1.95. It was positive in every year from 2020 to 2026 and mixed in 2012-2019.
   - The caveat is serious: per-commodity evidence is weak (Huang-Li-Wang-Zhou 2020), and with fixed 1-lot sizing
     we cannot scale by volatility the way the papers do.
4. **What the regulator says about retail:** there is no SEBI profit-and-loss study for commodity traders yet. In
   equity F&O, 87.7% of individuals lost money in FY26, and 92% of the losses came from options. On MCX, clients are only
   39% of options turnover; the other 61% is proprietary desks. Option buyers on MCX face the same kind of opponent.
5. **Rs 5,000 a day from MCX with Rs 1 lakh: NO, on current evidence.** The best realistic hope is a slow trend system:
   a few trades a month, held for weeks, with lumpy results. That is the top test for M2-M4. The ranked list is in
   section 4.

---

## 1. The contracts

### 1a. Quick map: what you can actually use with Rs 1 lakh

| commodity | contract | lot | 1 tick = Rs/lot | price 7 Oct | Zerodha margin / lot (NRML = MIS) | lots Rs 1L can margin | median day range Rs/lot (last 12 m) | bad day (90th pct) Rs/lot | futures volume (lots/day, 60 d median) | options? |
|---|---|---|---|---|---|---|---|---|---|---|
| Crude oil | CRUDEOIL | 100 bbl | 100 | 8,552 /bbl | 2,67,290 (31.3%) | 0 | 31,076 | 60,761 | 61,272 | **yes, liquid near month** |
| | CRUDEOILM | 10 bbl | 10 | 8,554 | 26,729 | 3 (1 prudent) | 3,152 | 6,295 | 163,133 | yes, liquid, but costly per rupee of premium |
| Natural gas | NATURALGAS | 1,250 mmBtu | 125 | 309.9 /mmBtu | 64,842 (16.7%) | 1 | 15,501 | 29,312 | 103,231 | **yes, liquid near month** |
| | NATGASMINI | 250 mmBtu | 25 | 310.0 | 12,968 | 7 (1-2 prudent) | 3,179 | 5,891 | 163,044 | yes, liquid, costly per rupee |
| Gold | GOLD | 1 kg | 100 | 1,49,103 /10 g | 13,79,178 (9.25%) | 0 | 2,27,580 | 4,60,415 | 6,016 | thin; ATM costs ~Rs 2.7 lakh |
| | GOLDM | 100 g | 10 | 1,47,897 | 1,36,807 | 0 | 22,945 | 47,557 | 38,025 | **yes, liquid near month** |
| | GOLDTEN | 10 g | 1 | 1,48,276 | 13,715 | 7 (1-2 prudent) | 2,335 | 5,853 | 20,281 | no |
| | GOLDGUINEA | 8 g | 1 | 1,19,074 /8 g | 11,014 | 9 | 1,779 | 4,716 | 9,390 | no |
| | GOLDPETAL | 1 g | 1 | 14,887 /g | 1,377 | 72 | 232 | 586 | 200,950 | no |
| Silver | SILVER | 30 kg | 30 | 2,23,561 /kg | 8,55,135 (12.75%) | 0 | 1,98,772 | 4,29,697 | 8,584 | thin; ATM costs ~Rs 1.6 lakh |
| | SILVERM | 5 kg | 5 | 2,25,773 | 1,43,930 | 0 | 35,269 | 70,441 | 36,745 | **yes, liquid near month** |
| | SILVERMIC | 1 kg | 1 | 2,25,834 | 28,793 | 3 (1 prudent) | 7,288 | 14,888 | 112,940 | no |
| | SILVER100 | 100 g | 10 | 2,222 /10 g | 2,837 | 35 | 519 | 907 | 23,655 | no (new; history from mid-2026) |
| Copper | COPPER | 2,500 kg | 125 | 1,419.45 /kg | 3,29,357 (9.3%) | 0 | 48,343 | 1,28,764 | 7,423 | exists, thin (7% spreads) |
| Zinc | ZINC | 5 t | 250 | 419.6 /kg | 1,96,225 | 0 | 30,156 | 65,539 | 2,370 | exists, nearly dead |
| | ZINCMINI | 1 t | 50 | 419.6 | 39,245 | 2 (1 prudent) | 6,127 | 12,842 | 4,754 | no |
| Aluminium | ALUMINIUM | 5 t | 250 | 339.65 /kg | 1,56,228 | 0 | 23,031 | 48,533 | 1,472 | no |
| | ALUMINI | 1 t | 50 | 340.55 | 31,256 | 3 (1 prudent) | 4,767 | 11,176 | 2,544 | no |
| Lead | LEAD | 5 t | 250 | 195.35 /kg | 72,209 | 1 | 7,677 | 17,984 | 124 (thin) | no |
| | LEADMINI | 1 t | 50 | 195.1 | 14,438 | 6 | 1,487 | 3,603 | 169 (thin) | no |
| Nickel | NICKEL | 250 kg | 25 | 1,543.9 /kg | 35,824 | 2 | 5,658 | 18,758 | 99 (very thin) | no |
| Bullion index | MCXBULLDEX | 30 x index | 30 | 33,845 | 81,012 | 1 | - | - | ~0 | listed, no trades |
| Metal index | MCXMETLDEX | 40 x index | 40 | 25,734 | 62,638 | 1 | - | - | 0 | no |
| Electricity | ELECDMBL | 50 MWh | 50 | 7,166 /MWh | 1,13,928 | 0 | 9,976 | 21,659 | 1,736 | no (not for us) |
| Agri | MENTHAOIL, CARDAMOM | 360 kg / 100 kg | | | 53k / ~40k | | | | 105 / 2 | no; the only agri Zerodha allows. Avoid |

How to read it:
- "Margin" is from [Zerodha's commodity margin calculator](https://zerodha.com/margin-calculator/Commodity/) (page
  dated 8 Oct 2026). On MCX, Zerodha's intraday (MIS) margin equals the overnight (NRML) margin. The Kite margin feed
  (`api.kite.trade/margins/commodity`) shows `mis_margin = nrml_margin` for every contract. There is no extra intraday
  leverage.
- **Crude margin is unusually high (31-36% of notional)** because of the 2026 Middle East crisis. In calm years it
  was 8-15%. It changes daily; an hour earlier the Kite feed showed Rs 3,14,061 for CRUDEOIL.
- "Lots Rs 1L can margin" is the hard ceiling. **Prudent is 1 lot**, so that a bad day (90th percentile range) stays
  below about 10% of capital.
- Day ranges are the high-low of the near-month future over 8 Oct 2025 - 7 Oct 2026, times today's lot value.
  That year was violent: crude, silver and natural gas ran at 58-70% annualised volatility, against 34-45% over five
  years. Expect smaller ranges in a calm year.
- Lot sizes, ticks and expiries are from the Kite instrument list (`api.kite.trade/instruments/MCX`) and the Dhan
  scrip master. Volumes are from Dhan daily data (lots).

### 1b. Options: which books are really tradable (Dhan chain after the 8 Oct close)

| option | expiry | ATM premium per lot | ATM IV | volume on 8 Oct (all strikes, lots) | volume ATM ±3 strikes | open interest | strike step | verdict |
|---|---|---|---|---|---|---|---|---|
| CRUDEOIL | 15 Oct | ~Rs 24,200 (5 days left) | 40% | 2,997,161 | 1,535,719 | 190,208 | Rs 50 | **best book on MCX** |
| CRUDEOILM | 15 Oct | ~Rs 2,440 | 40% | 10,192,043 | 5,063,730 | 549,851 | Rs 50 | liquid; costs 2.4% a round trip |
| NATURALGAS | 23 Oct | ~Rs 17,400 | 55% | 1,027,245 | 664,661 | 156,674 | Rs 5 | **liquid** |
| NATGASMINI | 23 Oct | ~Rs 3,500 | 55% | 1,112,268 | 836,040 | 166,826 | Rs 5 | liquid; costs 1.9% a round trip |
| GOLDM | 29 Oct | ~Rs 25,100 | 18% | 511,239 | 218,183 | 101,013 | Rs 500 | **liquid** |
| SILVERM | 27 Oct | ~Rs 27,000 | 23% | 376,382 | 99,222 | 79,673 | Rs 1,000 | **liquid** |
| GOLD (1 kg) | 30 Oct | ~Rs 2,66,000 | 17% | 26,629 | 4,124 | 8,091 | Rs 500 | too big |
| SILVER (30 kg) | 27 Oct | ~Rs 1,58,000 | 26% | 23,396 | 3,172 | 10,970 | Rs 1,000 | too big |
| COPPER | 23 Oct | ~Rs 49,000 | 15% | 34,726 | 21,412 | 7,744 | Rs 10 | thin |
| ZINC | 23 Oct | ~Rs 40,000 | 25% | 605 | 470 | 375 | Rs 5 | dead |
| MCXBULLDEX | 28 Oct | - | - | 0 | 0 | 0 | 100 | dead |
| any commodity, next month | Nov | - | - | 3 - 12,804 | | | | **no market** (spreads 17-97%) |

- ATM is found by put-call parity, not by the chain's "underlying" field: that field is the previous settlement,
  and for gold and silver it shows the wrong future.
- **Spreads: measure them live.** This snapshot was taken after the close. Its books look 1-3% wide even on liquid
  options; that is stale. NN_CRUDE and STRAD_CRUDE measured CRUDEOIL ATM spreads live at **0.19-0.29%**. GOLDM,
  SILVERM and NATURALGAS spreads have never been measured live in this lab. M2-M4 must log them first.
- Option premium per lot: CRUDEOIL x100, CRUDEOILM x10, NATURALGAS x1,250, NATGASMINI x250, GOLDM x10 (100 g, quoted
  per 10 g), SILVERM x5 (5 kg, quoted per kg).
- Option ticks: crude Rs 0.10 (CRUDEOILM 0.05), natural gas 0.05, gold and silver 0.50, copper and zinc 0.01.
- MCX as a whole (Q3 FY26 investor presentation): options premium turnover is **Rs 7,104 crore a day**, of which
  energy is Rs 4,077 crore and bullion Rs 2,996 crore. Base metals are Rs 31 crore. In futures, gold plus silver are
  78% of turnover, natural gas 12%, crude 3.4% and copper 4.6% (Q3 FY26).
  ([MCX investor presentation, Jan 2026](https://bsmedia.business-standard.com/_media/bs/data/announcements/bse/27012026/1c91c5ca-8903-4898-a7ac-08d3e9d4e60c.pdf); parsed copy `scratchpad/hunt/m1/raw/mcx_q3fy26.txt`.)

### 1c. Trading hours (IST)

| period | non-agri (energy, metals) | MCX intraday square-off | Zerodha MIS cut |
|---|---|---|---|
| US summer time (9 Mar - about 30 Oct 2026) | **09:00 - 23:30** | 23:20 | about 23:20 |
| US winter time (expected Mon 2 Nov 2026 - early Mar 2027) | **09:00 - 23:55** | 23:45 | about 23:45 |
| agri (mentha, cardamom) | 09:00 - 17:00 | 16:50 | |

- US daylight saving ends on **Sunday 1 Nov 2026**. MCX has moved its close to 23:55 from the next Monday in each of
  the last years ([Zerodha bulletin for 3 Nov 2025 - 6 Mar 2026](https://zerodha.com/marketintel/bulletin/429160/mcx-revision-in-trading-hours-from-november-03-2025);
  [ICICI note on 9 Mar 2026](https://www.icicidirect.com/ilearn/commodity/articles/mcx-revision-in-trading-hours-from-march-09-2026)).
  **The Nov 2026 circular was not out on 8 Oct. Check it.**
- Zerodha's own FAQ labels the 23:55 period "daylight saving time (November to March)". That is backwards; it means
  US winter. The times are right.
- Holidays left in 2026 ([MCX circular via HDFC Securities](https://www.hdfcsec.com/mcx-holidays-list)):
  - 20 Oct (Dussehra): morning closed, evening open.
  - **Sun 8 Nov: Diwali Muhurat session** (times to be notified).
  - 10 Nov (Balipratipada): morning closed, evening open.
  - 24 Nov (Guru Nanak Jayanti): morning closed, evening open.
  - 25 Dec (Christmas): closed all day.
- US holidays (Thanksgiving 26 Nov, etc.) are not MCX holidays. The evening session then trades with NYMEX/COMEX
  thin or shut: expect poor fills.

### 1d. Expiry, settlement and delivery (this is where small accounts get hurt)

| contract | futures expiry (current) | settlement | options expiry (current) | what Boss must do |
|---|---|---|---|---|
| CRUDEOIL, CRUDEOILM | 19 Oct, 19 Nov, 18 Dec (one day before NYMEX WTI) | **cash**, at the NYMEX settlement | 15 Oct, 17 Nov, 16 Dec (2 business days before the future) | futures can be held to expiry |
| NATURALGAS, NATGASMINI | 27 Oct, 24 Nov, 28 Dec | **cash**, NYMEX-linked | 23 Oct, 20 Nov, 23 Dec | same |
| GOLD (bi-monthly) | 4 Dec 2026, 5 Feb, 5 Apr 2027 (the 5th) | **physical, staggered delivery** | monthly, on the nearest bi-monthly future: 30 Oct, 27 Nov, 31 Dec | close before the tender period |
| GOLDM | 5 Nov, 4 Dec, 5 Jan (the 5th) | physical | 29 Oct, 27 Nov, 29 Dec | same |
| GOLDTEN, GOLDGUINEA, GOLDPETAL | 30 Oct, 30 Nov, 31 Dec | physical | none | same |
| SILVER (bi-monthly) | 4 Dec 2026, 5 Mar, 5 May 2027 | physical | 27 Oct, 27 Nov, 28 Dec | same |
| SILVERM, SILVERMIC | 30 Nov, 26 Feb, 30 Apr | physical | SILVERM: 27 Oct, 23 Nov, 28 Dec | same |
| COPPER, ZINC, ALUMINIUM, LEAD (+ minis) | last business day of month (30 Oct, 30 Nov, 31 Dec) | **physical, compulsory delivery** (since 2019) | COPPER, ZINC: 23 Oct, 23 Nov, 23 Dec | same |
| NICKEL | 21 Oct, 18 Nov, 16 Dec | physical | none | same |

Rules that bite (sources: [Zerodha: MCX expiry and settlement](https://support.zerodha.com/category/trading-and-markets/trading-faqs/articles/mcx-expiry-settlement),
[Zerodha: how long can I hold MCX contracts](https://support.zerodha.com/category/trading-and-markets/trading-faqs/commoditytrading/articles/how-long-can-i-hold-mcx-contracts),
[Zerodha bulletin, Sept 2026 option expiries](https://zerodha.com/marketintel/bulletin/458286/commodities-option-contract-expiry-september-2026),
[Zerodha bulletin, Jan 2026](https://zerodha.com/marketintel/bulletin/439577/mcx-option-contract-expiry-january-2026)):
- **Zerodha does not allow physical delivery.** For physically settled futures, Zerodha squares off NRML positions
  at **22:30 on the day before the tender period starts**. You can convert to MIS before 22:30 and hold until about
  10 minutes before the close. Each auto square-off costs Rs 50 + GST.
- **MCX options settle into futures, not cash.** Every in-the-money (and "close-to-the-money") option devolves into the
  futures contract on expiry day.
  - Zerodha asks for the full futures margin **by 19:00 on expiry day**, or it squares the option off.
  - That margin is Rs 2.7 lakh for crude and Rs 1.4 lakh for GOLDM or SILVERM. **With Rs 1 lakh, never carry an ITM
    option into expiry evening. Plan to exit by 18:30 on expiry day, or earlier.**
- **No fresh long option positions in NRML on expiry day** (Zerodha, 2026 bulletins). Other brokers allow MIS only.
- Gold and silver options expire in the last week of the month, but their underlying is the next bi-monthly future.
  The option and the futures expiry are weeks apart. Read the underlying from the contract name, not from habit.

### 1e. Round-trip costs on Zerodha (1 lot, verified 8 Oct 2026)

Rates from [zerodha.com/charges](https://zerodha.com/charges/) (Commodity tab), read 8 Oct 2026:

| item | commodity futures | commodity options |
|---|---|---|
| brokerage | 0.03% or Rs 20 per executed order, whichever is lower | Rs 20 per executed order |
| CTT | **0.01% on the sell side** (non-agri) | **0.05% on the sell side, on premium** |
| MCX transaction charge | 0.0021% of turnover | 0.0418% of premium |
| SEBI fee | Rs 10 per crore (Rs 1/crore agri) | Rs 10 per crore |
| stamp duty | 0.002% on the buy side | 0.003% on the buy side |
| GST | 18% on brokerage + exchange charge + SEBI fee | same |

Round trip, 1 lot, at 7-8 Oct prices (`research/hunt/m1/costs.py`). The spread column assumes 1 tick for futures and
for GOLDM/SILVERM options, and the measured 0.29% for crude options. **Exits by stop-loss market orders pay more.**

| futures | notional | charges | 1-tick spread | total | % of notional |
|---|---|---|---|---|---|
| CRUDEOIL | 8,55,200 | 194 | 100 | **294** | 0.034% |
| CRUDEOILM | 85,540 | 62 | 10 | **72** | 0.084% |
| NATURALGAS | 3,87,375 | 114 | 125 | **239** | 0.062% |
| NATGASMINI | 77,500 | 61 | 25 | **86** | 0.110% |
| GOLDM | 14,78,970 | 301 | 10 | 311 | 0.021% |
| GOLDTEN | 1,48,276 | 73 | 1 | **74** | 0.050% |
| GOLDPETAL | 14,887 | 13 | 1 | 14 | 0.095% |
| SILVERM | 11,28,865 | 241 | 5 | 246 | 0.022% |
| SILVERMIC | 2,25,834 | 86 | 1 | **87** | 0.039% |
| ZINCMINI | 4,19,600 | 119 | 50 | **169** | 0.040% |
| ALUMINI | 3,40,550 | 106 | 50 | **156** | 0.046% |
| LEADMINI | 1,95,100 | 81 | 50 | 131 | 0.067% |

| options (ATM, 8 Oct) | premium / lot | charges | spread | total | % of premium |
|---|---|---|---|---|---|
| CRUDEOIL | 24,200 | 84 | 70 | **154** | 0.64% |
| CRUDEOILM | 2,440 | 51 | 7 | 58 | **2.39%** |
| NATURALGAS | 17,375 | 74 | 62 | **136** | 0.78% |
| NATGASMINI | 3,500 | 53 | 12 | 65 | **1.86%** |
| GOLDM | 25,150 | 85 | 5 | **90** | 0.36% (spread unmeasured) |
| SILVERM | 27,000 | 88 | 2 | **91** | 0.34% (spread unmeasured) |

- Cheapest option books per rupee: GOLDM, SILVERM, CRUDEOIL, NATURALGAS (0.3-0.8%).
- The mini option books (CRUDEOILM, NATGASMINI) cost 2-2.4%, because the flat Rs 20 brokerage is large next to a
  small premium. Use them only for very small tests.
- For comparison, BANKNIFTY options in our earlier studies cost about Rs 146 a trade (X3).

---

## 2. What drives each commodity, and when (all times IST)

### 2a. The one-line rule

**MCX prices are international prices times USD/INR, plus Indian duty and carry.**
- MCX crude vs NYMEX WTI x USD/INR: price ratio 1.000-1.005, 5-minute return correlation 0.94, no lead or lag
  (NN_CRUDE).
- Gold and silver follow COMEX x USD/INR, plus India's import duty and GST. **The duty went from 6% to 15% on 13 May
  2026** ([TBS News, 13 May 2026](https://www.tbsnews.net/world/south-asia/india-hikes-import-duties-gold-and-silver-1437591)),
  after the Feb 2026 Budget had left it at 6% ([A2Z Taxcorp](https://a2ztaxcorp.net/budget-2026-27-keeps-customs-duty-unchanged-for-gold-and-silver-bullions/)).
  A duty change re-prices MCX bullion in one jump.
- Base metals follow LME and Shanghai (SHFE), times USD/INR.
- **Consequence: the big moves happen in US hours (18:00-23:30 IST) and overnight.** In crude, 50% of intraday
  variance came after 18:00, 84% of option volume traded after 17:00, and the overnight gap was 16% of daily
  variance (NN_CRUDE).

### 2b. Drivers by commodity

| commodity | main drivers | grade |
|---|---|---|
| Crude | WTI/Brent; OPEC+ decisions; US inventories (API, EIA); Middle East supply news; USD/INR; refinery margins; risk appetite | E |
| Natural gas | Henry Hub (NYMEX); **US weather** (heating degree days in winter, cooling in summer); EIA storage vs expectations; LNG export flows; hurricanes; production | E |
| Gold | US real yields (10y TIPS), DXY, Fed path; central-bank buying (dominant since 2022); geopolitical fear; USD/INR; **Indian duty**; festival and wedding demand (Akshaya Tritiya, Dhanteras, Nov-Feb weddings) | E for yields/USD/duty; P for festivals |
| Silver | gold drivers plus industrial demand (solar, electronics); about 2x gold's volatility | E |
| Copper, zinc, aluminium, lead, nickel | China demand (PMI, property, credit), LME stocks, SHFE night session, USD, energy costs, supply cuts | E |

### 2c. Scheduled events in IST

US summer time ends Sun 1 Nov 2026. UK summer time ends Sun 25 Oct 2026, a week earlier: LME times shift a week
before US times.

| event | local time | IST until 31 Oct 2026 | IST from 2 Nov 2026 | MCX open? |
|---|---|---|---|---|
| API weekly crude stocks | Tue 16:30 ET | **Wed 02:00** | **Wed 03:00** | no: shows up in the Wed 09:00 open |
| **EIA petroleum status (WPSR)** | Wed 10:30 ET | **Wed 20:00** | **Wed 21:00** | yes |
| EIA WPSR holiday shifts | | **Thu 15 Oct 2026, 12:00 ET = 21:30** | **Thu 12 Nov 2026, 12:00 ET = 22:30** | yes |
| **EIA natural gas storage** | Thu 10:30 ET | **Thu 20:00** | **Thu 21:00** | yes |
| EIA gas storage holiday shifts | | | **Fri 13 Nov 2026 21:00; Wed 25 Nov 2026 12:00 ET = 22:30** | yes |
| Baker Hughes rig count | Fri 13:00 ET | Fri 22:30 | Fri 23:30 | yes (near close) |
| CFTC Commitments of Traders | Fri 15:30 ET | Sat 01:00 | Sat 02:00 | no |
| US CPI, payrolls (1st Fri), PCE, retail sales, GDP, jobless claims | 08:30 ET | **18:00** | **19:00** | yes |
| ISM PMIs, UMich sentiment, JOLTS | 10:00 ET | 19:30 | 20:30 | yes |
| **FOMC statement** / press conference | 14:00 / 14:30 ET | **23:30 / 00:00** | **00:30 / 01:00** | **no: the Fed lands at or after the MCX close. It is an overnight gap** |
| NYMEX crude settlement window | 14:28-14:30 ET | 23:58-00:00 | 00:58-01:00 | no (MCX crude expiry settles on it) |
| OPEC+ meetings (mostly online, often a Sunday) | varies | | | weekend: shows in the Monday 09:00 gap |
| NOAA 6-10 / 8-14-day weather outlooks | about 15:00 ET daily | about 00:30 | about 01:30 | no |
| China NBS data (CPI, PPI, activity), official PMI | 09:30-10:00 Beijing | 07:00-07:30 | same | no: before the open |
| China trade data | about 11:00 Beijing | about 08:30 | same | no |
| SHFE base metals, day / night session | 09:00-15:00 / 21:00-01:00 Beijing | 06:30-12:30 / 18:30-22:30 | same | overlaps |
| LME official prices (ring) | about 12:30-13:35 London | about 17:00-18:05 (until 24 Oct) | about 18:00-19:05 (from 26 Oct) | yes |
| LMEselect electronic | 01:00-19:00 London | 05:30-23:30 | 06:30-00:30 | yes |
| RBI policy, India Budget | 10:00 / 11:00 IST | | | yes (USD/INR) |
| Diwali Muhurat | Sun 8 Nov 2026 | | | special session |

Sources: [EIA WPSR schedule](https://www.eia.gov/petroleum/supply/weekly/schedule.php),
[EIA gas storage schedule](https://ir.eia.gov/ngs/schedule.html) (both read 8 Oct 2026; saved in
`scratchpad/hunt/m1/raw/`). The other times are the standard published release times. For Christmas and New Year
2026 gas storage dates, check EIA's page when it is updated.

What the events do on MCX (from our own crude studies):
- The EIA minute itself is 1.7x a normal minute. The next hour is no bigger than a normal evening. The first 5 minutes
  do not predict the next 55 (NN_CRUDE).
- Buying a crude straddle 15 minutes before EIA and selling 5 minutes after made **Rs -2 per trade net** in the
  holdout (STRAD_CRUDE). The event is priced in.
- Natural-gas storage Thursday and the bullion reaction to US CPI have **not been tested here yet**.

---

## 3. What the evidence says makes money in commodities

Grades: **E** = evidence (peer-reviewed or regulator data, or our own tested data); **P** = plausible (a mechanism
and some support, but not clean or not after costs); **F** = folklore.

| idea | what the research says | grade | what it means for Boss |
|---|---|---|---|
| **Trend following / time-series momentum (weeks to months)** | 58 futures, 1985-2009: the past 12-month return predicts the next month for every asset class, commodities included ([Moskowitz, Ooi, Pedersen 2012, JFE](https://doi.org/10.1016/j.jfineco.2011.11.003)). Positive in every decade since 1880 ([Hurst, Ooi, Pedersen 2017, JPM](https://www.aqr.com/Insights/Research/Journal-Article/A-Century-of-Evidence-on-Trend-Following-Investing)). Moving-average and channel rules made money on 28 commodity futures over 48 years ([Szakmary, Shen, Sharma 2010, JBF](https://doi.org/10.1016/j.jbankfin.2009.08.004)). Indian commodity futures 2006-2017: momentum profits after costs, time-varying ([Jaiswal & Uchil 2020, AJBA](https://ajba.um.edu.my/article/view/19492)). **Against:** asset by asset the evidence is weak, and a lot of the profit comes from volatility scaling and diversification ([Huang, Li, Wang, Zhou 2020, JFE](https://ideas.repec.org/a/eee/jfinec/v135y2020i3p774-794.html)). CTA trend funds were flat in 2012-2019 and strong in 2022 | **E** (portfolio level), **P** (single commodity) | The best-supported idea. Needs several commodities, patience (holds of weeks) and tolerance of long flat spells |
| Our MCX rough check (not validated) | 9 liquid MCX futures, 2012-2026, roll days neutralised, volatility-scaled, 0.1% per flip: 12-month lookback **t = 1.95**, positive every year 2020-2026, mixed 2012-2019. 1-month t = 1.33, 3-month t = 0.35, 6-month t = 1.20. Per commodity, t is mostly below 1.5 (`scratchpad/hunt/m1/quick_evidence.txt`) | **P** | Matches the papers: a portfolio effect, recent years strong. Fixed 1-lot sizes cannot do the volatility scaling. M2 must test it properly |
| **Carry / roll yield** | Futures in backwardation (spot above futures) earn more than those in contango. Roll yield and inventories explain much of commodity returns ([Erb & Harvey 2006, FAJ](https://doi.org/10.2469/faj.v62.n2.4084); Gorton, Hayashi & Rouwenhorst 2013, *Review of Finance*, "The fundamentals of commodity futures returns"; carry across asset classes: [Koijen, Moskowitz, Pedersen, Vrugt 2018, JFE](https://doi.org/10.1016/j.jfineco.2017.11.002)) | **E** (cross-section of many commodities) | On MCX, holding a rolled long natural-gas future lost **-1.56% a month** on average 2012-2026, mostly contango drag. Long crude rolled: -0.32%/month. Gold and base metals: positive |
| Commodity risk premium (just be long) | Positive long-run premium for a diversified basket, smaller after 2004 ([Gorton & Rouwenhorst 2006](https://doi.org/10.2469/faj.v62.n2.4083); [Bhardwaj, Gorton, Rouwenhorst 2015, NBER w21243](https://www.nber.org/papers/w21243)) | E (small) | Not a trading edge for a 1-lot account |
| **Option buyers pay a volatility premium** | Variance risk premia in commodity options are negative: implied above realised on average, crude and natural gas included (Trolle & Schwartz 2010, *Journal of Derivatives*, "Variance risk premia in energy commodities"; Prokopczuk, Symeonidis & Wese Simen 2017, *Journal of Banking & Finance*, "Variance risk in commodity markets"). Our crude data: intraday realised is 0.6-0.7x implied; held to expiry 0.91-1.04x (STRAD_CRUDE) | **E** | Every option-buying idea starts behind. Prefer holds to expiry-ish over intraday holds, and prefer direction that a trend explains |
| Seasonality: natural gas | Strong seasonal shape in prices and the futures curve (winter contracts dearer). Weather drives volatility (Mu 2007, Energy Economics). On MCX, monthly returns 2012-2026: Dec -10.7% (t -2.2), Feb -5.7%, Aug +3.7%. One significant month out of 12 is what chance gives | **E** (curve shape), **F** (calendar-month trading) | Do not trade calendar months. The curve shape is the carry idea above |
| Seasonality: gold "autumn effect" | Sept and Nov positive 1980-2010 ([Baur 2013, RIBAF](https://ideas.repec.org/a/eee/riibaf/v27y2013i1p1-11.html)). A 2024 replication finds it reversed. On MCX 2012-2026: Sept -1.0%, Nov -1.0%; Jan +3.3% (t 3.6) | **F** | Festivals and calendar months: folklore. January is one month in 12, so treat it as a fluke unless M3 finds it out of sample |
| **Inventory announcements (EIA crude, EIA gas)** | Prices react within minutes to the surprise against expectations (Halova, Kurov & Kucher 2014, *Journal of Futures Markets*, "Noisy inventory announcements and energy prices"; [Ye & Karali 2016, Energy Economics](https://ideas.repec.org:443/a/eee/eneeco/v59y2016icp349-364.html); natural gas: Linn & Zhu 2004, JFM; Gay, Simkins & Turac 2009, *Journal of Futures Markets*, "Analyst forecasts and price discovery in futures markets: the case of natural gas storage"). Some price drift appears *before* releases (Kurov, Sancetta, Strasser & Wolfe 2019, *JFQA*, "Price drift before U.S. macroeconomic news"). Reaction is fast; no reliable drift after | **E** for the reaction; **F** for trading it without the consensus number | We have no consensus forecasts. Crude EIA straddle: zero net. Gas storage: untested |
| **Intraday momentum** | First half-hour return predicts the last half-hour (S&P ETF: [Gao, Han, Li, Zhou 2018, JFE](https://c.mql5.com/forextsd/forum/173/intraday_momentum_-_the_first_half-hour_return_predicts_the_last_half-hour_return.pdf); Chinese commodity futures: [Jin, Kearney, Li, Yang 2020, JFM](https://hull-repository.worktribe.com/output/3295550)). Crude: first half-hour (mostly the overnight part) predicts the last half-hour; on EIA days the third half-hour does ([Wen, Indriawan, Lien, Xu 2023, Energy Journal](https://digital.library.adelaide.edu.au/items/13d367dd-06b2-4eca-8d21-4dfeb46c4f55)) | **P** | NYMEX's last half-hour (14:00-14:30 ET) is after the MCX close. On MCX crude, the 09:00-17:30 move vs the 17:30-close move had correlation +0.02 (NN_CRUDE). Cheap to test on the MCX clock; low prior |
| Intraday direction from indicators, patterns, ML | Our lab: 26M+ rules, 215k indicator versions and a neural net on crude: all equal to random after costs (HUNT_FINAL, NN_CRUDE) | **E (negative)** | Do not repeat |
| Opening-range / level breakouts | Our only survivor on Indian index options: BANKNIFTY Liquidity 15+5, Rs 65-167/day at 1 lot (HUNT_FINAL). Untested on MCX | **P** | Worth porting to the MCX evening session, where the variance is |
| Retail traders as a group | Equity F&O FY26: **87.7% of individuals lost money; Rs 91,685 crore lost; options were about 92% of losses; about 97% of traders mainly buy options** ([Moneylife on SEBI FY26 study](https://www.moneylife.in/article/92-percentage-of-aggregate-losses-incurred-by-individuals-are-from-options-trading-sebi-study/81429.html); [webindia123, 20 Aug 2026](https://news.webindia123.com/news/Articles/Business/20260820/4489046.html)). FY25: 91% lost (SEBI, July 2025). **No SEBI P&L study of commodity traders was found** (searched 8 Oct 2026). On MCX, clients are 39% of options turnover and 51% of futures turnover; proprietary desks are the rest. 8.9 lakh clients traded MCX options in Q3 FY26 (MCX presentation) | **E** (equity), **P** (that MCX is similar) | Assume the same outcome on MCX until shown otherwise |

Costs and capacity:
- At 1 lot, capacity is not an issue on the liquid books (CRUDEOIL, NATURALGAS, GOLDM, SILVERM options; all minis
  except lead and nickel). Costs are 0.3-0.8% of premium for those options and 0.04-0.11% of notional for minis.
- The cost that matters for option buyers is **theta and the volatility premium**, not brokerage.
- Rs 20 a leg makes CRUDEOILM and NATGASMINI options (premium Rs 2.5-3.5k) cost about 2% a round trip. Scalping them
  is hopeless.

---

## 4. Ideas worth testing on MCX with Rs 1 lakh (ranked) - for M2, M3, M4

How the ranking was made:
- Outside evidence first.
- Then whether the idea dodges what already failed in this lab: intraday direction, straddles, and indicators.
- Then fit to Rs 1 lakh with fixed lots.
- Option buying first; mini futures where the idea needs them.

| rank | idea | instrument | why | prior | suggested owner |
|---|---|---|---|---|---|
| **1** | **Trend options.** Buy a near-month 1-ITM / ATM call or put in the direction of a weeks-to-months trend: 12-month or 3/6/12-month blended return sign, or price vs 50/100-day average, or 20/55-day channel breakout. Enter about 25-35 days before option expiry; roll about 5-7 days before expiry (never into expiry day); exit when the trend flips. One position per commodity | CRUDEOIL, NATURALGAS, GOLDM, SILVERM options (premium Rs 17-27k each; 2-3 at once fit Rs 1 lakh) | Time-series momentum is the best-evidenced commodity edge. Options cap gap risk, and holding near expiry is close to fair value (crude 0.91-1.04x) instead of the 0.6x intraday price | P/E | M2 |
| **2** | **Trend mini futures**, the same signals: 1 lot each of CRUDEOILM, NATGASMINI, SILVERMIC, GOLDTEN, ZINCMINI or ALUMINI (total margin about Rs 1.3 lakh, so pick 3-4) | mini futures, long or short | The same edge without theta. **Risk:** overnight gaps; margin calls when MCX raises margins (crude 31-36% now); short natural gas into a cold snap. Hard stop on each lot and a rule for margin hikes | P/E | M2 |
| **3** | **MCX evening "Liquidity / ORB" port.** Mark the range of a set window (17:00-18:30 IST, or the first 15-30 minutes after 18:30 / 19:00 IST when the US opens); buy the near-month ATM call or put only on a clean break; use the app's Liquidity-style exits; flat by 23:15 | CRUDEOIL and NATURALGAS options (also GOLDM, SILVERM) | The lab's only surviving rule is a breakout on index options. On MCX, the variance and 84% of option volume arrive after 17:00. "Buy only where price travels" | P | M3 |
| **4** | **Natural-gas contango carry.** When the next-month NG future is at least X% above the near month (steep contango), buy a near-month NATURALGAS put (or short NATGASMINI with a hard stop); skip mid-Nov to Feb or require a down-trend filter | NATURALGAS puts; NATGASMINI short | Rolled long NG lost -1.56% a month on MCX 2012-2026. Contango drag is documented (Erb-Harvey; Gorton-Hayashi-Rouwenhorst). **Risk:** winter spikes of 20-40% in days; the put limits that | P/E | M2 |
| **5** | **Intraday momentum on the MCX clock.** (a) Return from the previous close to 19:30 IST (includes the overnight gap) predicts 22:30-23:30 IST. (b) On EIA Wednesdays, the 20:00-20:30 (21:00-21:30 in winter) half-hour predicts the last hour | CRUDEOILM futures for the signal test, then CRUDEOIL options | Wen et al. 2023 found both patterns on NYMEX crude. Cheap to test. Low prior: NN_CRUDE found day-part correlation +0.02 | P (low) | M3 |
| **6** | **Gas-storage Thursday breakout.** Mark 19:55-20:00 IST (20:55-21:00 in winter); after the release, buy the ATM option in the direction of the first 5-15 minutes if the move exceeds k x normal; exit in 15-60 minutes or by a trail | NATURALGAS options | Gas reacts hard to storage surprises. Untested here, unlike crude EIA (zero). **Risk:** whipsaw; spreads widen at the release minute | P | M4 |
| **7** | **US-data breakout in bullion.** Same design at 18:00 / 19:00 IST on CPI and payroll days | GOLDM, SILVERM options | Gold and silver react to the yield/USD surprise. Untested on MCX | P | M4 |
| **8** | **Cross-commodity carry plus trend on minis.** Each month, rank 5-6 minis by roll yield (near vs next future) and by 12-month trend; long the top one, short the bottom one | minis | Carry and trend are the two cross-sectional factors with the strongest evidence. Small universe, so expect noise | P | M2 |
| **9** | **Volatility-gated option buying outside crude.** Buy a NATURALGAS or SILVERM straddle only when the size forecast (HAR) is above implied | NATURALGAS, SILVERM options | Failed on crude (STRAD_CRUDE). Natural gas and silver have a different volatility premium; a cheap check with the STRAD_CRUDE code. Today NG IV is 55% vs 20-day realised 45%; silver IV 23-26% vs 23% | P (low) | M4 |
| **10** | **Seasonal and ratio checks (expect rejection).** Gold January / festival effects; NG winter; the gold/silver ratio mean-reverting with GOLDTEN vs SILVERMIC | futures, options | Mostly folklore. Run once, cheaply, so Boss gets a documented "no" | F | M3 |

Do NOT spend time on (already failed in this lab or folklore): intraday direction models on crude; crude straddles or
strangles at any horizon or around EIA; candlestick, indicator, Fibonacci or Gann rules; cheap far-OTM "lottery"
options in expiry week; next-month options (no market).

### Data the testers have (and lack)

| need | source | span | note |
|---|---|---|---|
| daily continuous near-month futures with OI, all MCX contracts | Dhan `/charts/historical`, expiryCode 0 (`scratchpad/hunt/m1/data/daily_*.parquet`) | **2012 - 7 Oct 2026** | unadjusted at rolls. Detect a roll as OI jumping more than 1.8x, and use the open-to-close return on that day (`daily_stats.load`) |
| MCX option minutes (expired), ATM ±3, near and next month, with IV, OI and the underlying future | Dhan `/charts/rollingoption` | **from about Aug 2025 only**. Confirmed for CRUDEOIL, NATURALGAS, NATGASMINI, GOLD, GOLDM, SILVER, SILVERM, COPPER and ZINC: a Sep 2025 window returns data, Jun 2025 and Sep 2024 return nothing (`probe_rolling.txt`) | about 13 months. Too short for trend tests. Use it to calibrate option-pricing for the long backtests |
| futures minutes | Dhan `/charts/intraday` | live contracts only | for history, use the `spot` field of rolling options (NN_CRUDE) |
| long history (2000+) for trend tests | Yahoo CL=F, NG=F, GC=F, SI=F, HG=F times INR=X (NN_CRUDE's fetchers) | 2000 - | MCX crude = WTI x USD/INR (corr 0.94). For bullion, add the duty regime (6% Jul 2024 - May 2026, 15% from 13 May 2026) |
| live spreads and depth | Dhan `/optionchain`, `/marketfeed/quote` loops (NN_CRUDE `chain_loop.sh`) | from now | **log GOLDM, SILVERM and NATURALGAS spreads during the session before trusting any cost number** |
| consensus forecasts (EIA, storage) | none free | | needed for any "surprise" test |
| Dhan token | `scratchpad/secrets/dhan.env` | **expires 9 Oct 2026, 04:57 UTC** | refresh before running new fetches |

How to test (same honesty rules as the hunt):
- Lock the last year (1 Oct 2025 onward) and open it once.
- Compare with random entries using identical exits.
- Count every variant and apply BH.
- Report gross and net, per year, worst month, and max drawdown at 1 lot.
- For trend ideas, also report each year from 2012 (from the daily data) and the time spent flat or losing.

### Risks to put in front of every test

| risk | how it hits a Rs 1 lakh account | mitigation |
|---|---|---|
| **Overnight gap** | Crude's mean overnight gap in 2026 is about 1.2% (Rs 10k per CRUDEOIL lot); the Fed lands after the close; OPEC lands at weekends | options instead of futures; size 1 lot; never carry a futures position you cannot lose 2 bad days on |
| **US-session moves** | Half of crude's daily variance comes 18:00-23:30, when Boss may be away | hard stops placed at the exchange; no "watch and decide" exits |
| Volatility premium and theta | Options lose 0.6-0.7x of implied move intraday on crude | hold weeks, not minutes; buy only with a trend reason |
| **Low liquidity away from ATM and in next month** | Next-month books: 17-97% spreads. Far OTM: 1-2% | near month, ATM ±2 strikes only; limit orders |
| **Physical delivery / devolvement** | ITM option → future on expiry day (needs Rs 1.4-2.7 lakh margin by 19:00); physically settled futures squared off at 22:30 the day before tender | exit options by the day before expiry; check the tender date of each gold, silver and base-metal future |
| **Margin calls and margin hikes** | MCX raised crude margins to 31-36% in 2026; a hike forces a cut at the worst time | keep at least 50% of capital free; minis only |
| Circuit filters | MCX has daily price bands that expand in steps; a limit move traps a position | options, small size |
| Event spikes | Natural gas can move 10-20% in a day in winter; silver 8-10% | options for any short-volatility-exposed direction; no naked short NG over weekends |
| Thin US-holiday sessions | NYMEX/COMEX shut or short (e.g. 26 Nov) while MCX trades | stay out |

---

## Sources (main)

- Zerodha: [charges](https://zerodha.com/charges/), [commodity margin calculator](https://zerodha.com/margin-calculator/Commodity/),
  [MCX expiry & settlement](https://support.zerodha.com/category/trading-and-markets/trading-faqs/articles/mcx-expiry-settlement),
  [how long can I hold MCX contracts](https://support.zerodha.com/category/trading-and-markets/trading-faqs/commoditytrading/articles/how-long-can-i-hold-mcx-contracts),
  [option expiry bulletin Sept 2026](https://zerodha.com/marketintel/bulletin/458286/commodities-option-contract-expiry-september-2026),
  [trading hours bulletin Nov 2025](https://zerodha.com/marketintel/bulletin/429160/mcx-revision-in-trading-hours-from-november-03-2025),
  Kite public instrument list and margin feed (`api.kite.trade/instruments/MCX`, `api.kite.trade/margins/commodity`).
- MCX: [Q3 FY26 investor presentation](https://bsmedia.business-standard.com/_media/bs/data/announcements/bse/27012026/1c91c5ca-8903-4898-a7ac-08d3e9d4e60c.pdf);
  [2026 holiday circular (via HDFC Securities)](https://www.hdfcsec.com/mcx-holidays-list). mcxindia.com blocked
  automated access (403), so MCX circulars were read through brokers.
- EIA: [WPSR schedule](https://www.eia.gov/petroleum/supply/weekly/schedule.php), [gas storage schedule](https://ir.eia.gov/ngs/schedule.html).
- SEBI / retail: [Moneylife on SEBI FY26 study](https://www.moneylife.in/article/92-percentage-of-aggregate-losses-incurred-by-individuals-are-from-options-trading-sebi-study/81429.html),
  [webindia123, 20 Aug 2026](https://news.webindia123.com/news/Articles/Business/20260820/4489046.html),
  [SEBI FY25 study](https://www.sebi.gov.in/sebi_data/attachdocs/jul-2025/1751900271726.pdf).
- Papers: listed in the section 3 table.
- Our own: `research/NN_CRUDE.md`, `research/STRAD_CRUDE.md`, `research/HUNT_FINAL.md`, `research/MARKET_HOW.md`.
