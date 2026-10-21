# R96 round 1: hunting an MCX commodity option-BUYING strategy (21 Oct 2026)

Boss, 21 Oct: *"I want the best commodities strategy developed ... Run until we find something: a commodities options
BUYING strategy."* Research only: nothing in `android/**` or `.github/**` changed; nothing trades.

- Pre-registration: `research/hunt/r96_mcx_buy/PREREG_ROUND1.md` (commit c1d5f8e8, before any statistic). Code and tests
  frozen in fd44b2d1 before any result; notes N1-N3 in `hunt/r96_mcx_buy/NOTES.md` (all written before the first run).
- Internet scan: `hunt/r96_mcx_buy/INTERNET.md`. Every number: `hunt/r96_mcx_buy/results/` (`design_round1.json`,
  `report_round1.md`, `trades_design_round1.csv`, `ledger.json`). Cumulative ledger: `hunt/r96_mcx_buy/LEDGER.md`.
- **Signal source of every rule: the MCX future** (near future minute closes; daily rules: the book's near-month daily
  continuous future). The trade is always an option **buy** (1-ITM call / put, or an ATM straddle) on CRUDEOILM,
  NATGASMINI, GOLDM, SILVERM. Every trade has a hard stop and a profit lock.
- **Locked final holdout: 15 Jul - 19 Oct 2026. It was NOT read** (nothing passed the design gates).
- **Ideas tried so far in R96: 9** (all in round 1).

## The answer for round 1

**LEADS, nothing found yet. 0 of 9 rules passed the design gates; 3 are leads, 6 die.** No holdout read, no APP_SPEC,
nothing goes to paper.

| rule (signal on the future) | design trades | net (1 lot) | twin p (BH q, 9 ideas) | folds + | worst month | verdict |
|---|---|---|---|---|---|---|
| **N1** NR7 breakout -> 1-ITM, 3 sessions | 58 | **+Rs 1.84 lakh** | **0.006 (0.054)** | 2 of 4 | -Rs 20,791 | **lead** (fails folds, worst month, max DD -Rs 51,030); profit rests on model-priced silver exits (below) |
| P1 pullback in trend -> 1-ITM, 5 sessions | 35 | +Rs 1.08 lakh | 0.164 (0.467) | 3 of 4 | -Rs 14,621 | weak lead; the 2012-25 futures check is **negative** (-0.24% a trade, 1,509), so the design gain is the 2025-26 metals trend |
| S1 squeeze release -> ATM straddle, 5 sessions | 6 | +Rs 18,814 | 0.068 (0.305) | 1 of 4 | -Rs 732 | lead on too few trades (6; one crude trade made it) |
| S2 squeeze release -> 1-ITM | 7 | -Rs 16,521 | 0.563 | 1 of 4 | -Rs 24,695 | dies |
| W1 wide-range continuation -> 1-ITM | 25 | -Rs 2.19 lakh | 1.000 | 1 of 4 | -Rs 98,573 | dies (12% win; silver -Rs 1.75 lakh) |
| V1 IV below HAR forecast -> ATM straddle | 15 | -Rs 46,640 | 0.242 | 1 of 4 | -Rs 1.38 lakh | dies |
| E1 event straddle (EIA, storage, CPI, NFP) | 103 | -Rs 61,416 | 0.768 | 1 of 4 | -Rs 34,696 | dies: gross -Rs 33,194 before costs (17% win) |
| E2 event follow -> 1-ITM | 103 | -Rs 28,960 | 0.259 | 1 of 4 | -Rs 18,705 | dies: gross -Rs 14,421 |
| U1 US-session momentum (big first half-hour) -> 1-ITM | 236 | -Rs 1.64 lakh | 0.882 | 1 of 4 | -Rs 72,247 | dies: gross -Rs 1.24 lakh |

Design = 5 Aug 2025 - 14 Jul 2026 (folds Aug-Oct, Nov-Jan, Feb-Apr, May-14 Jul). Net after Zerodha MCX option charges
and half-spreads (0.30% / 0.60% before 17:00; NATGASMINI 0.45% / 0.90%). Full table with gross, costs, 2x-spread,
drawdown, without-top-3, model / stale exits and capital needed: `results/report_round1.md`.

## What was learned

1. **Intraday ideas die again, now even before costs.** Event straddles (103), event follow (103) and US-session
   momentum (236) all lost *gross*: the option market prices EIA, storage, CPI and NFP hours (agrees with the
   literature: IV falls on report days) and the first US half-hour does not carry into the rest of the MCX session.
   Intraday momentum, the best-documented effect in commodity futures papers, does not survive a 1-ITM option on MCX.
2. **Buying vol when IV looks cheap loses too** (V1: 15 trades, -Rs 46,640; S1 6 trades). Our HAR forecast rarely
   exceeds MCX ATM IV (15 entries in 11 months), and when it does the straddle still lost. Consistent with a negative
   variance risk premium (option buyers pay for insurance).
3. **The only signal with an edge is the NR7 breakout, and its edge is in the future's direction, not in vol.**
   - In the option design, N1 beat random-day twins on the same books, sides and clock (twin mean +Rs 1,933 against
     +Rs 1.84 lakh; p 0.006, q 0.054 over 9 ideas).
   - On 13.5 years of daily futures (2012 - Jul 2025, before any option data), the same NR7 break made +0.58% a trade
     after a 0.10% cost on 1,436 trades, against +0.32% (trade-weighted) for random sessions of the same years. It beat its twins on
     every book: gas +1.08% vs +0.54% (p 0.02), gold +0.19% vs +0.06% (p 0.04), silver +0.48% vs +0.30% (p 0.08),
     crude +0.58% vs +0.41% (p 0.26).
4. **But N1's option profit is not proven to be realisable.** The lake keeps only ATM+/-3 strikes. When a winner runs
   deep in the money its strike leaves that window and the engine prices it with Black-76 (PREREG s3, "model exits",
   2x half-spread). N1's design trades split:

   | N1 design, by book | real-print exits: trades, net | model-priced exits: trades, net |
   |---|---|---|
   | CRUDEOILM | 14, -Rs 3,987 | 7, +Rs 18,670 |
   | NATGASMINI | 14, -Rs 7,883 | 4, +Rs 6,113 |
   | GOLDM | 6, -Rs 36,753 | 4, -Rs 26,477 |
   | SILVERM | 2, -Rs 578 | 7, **+Rs 2,34,879** |

   Seven silver trades priced by the model carry the whole result (silver's 2025-26 swings; best +Rs 65,267 on
   19 Jan 2026). A real deep-ITM SILVERM option is thin, and a model mark is not a fill. Without the model exits, N1
   lost on every book. This is the first thing round 2 must fix (R2-1 below).
5. **Metals multi-day holds can't be tested well on this lake.** GOLDM strikes are 100 points apart (0.06% of the
   price), SILVERM 1,000; ATM+/-3 covers a 0.2-1% move, so most multi-day metal exits are model-priced (W1 52%, P1 54%,
   V1 87%). Energy strikes are wider (crude 50 on ~8,000, gas 5 on ~300) and stay printed far longer.
6. **Size.** The median stop risk of a 1-lot trade was Rs 1,500-3,300 on the directional rules, so 2% of capital needs
   about Rs 0.75-1.6 lakh (V1 Rs 3.1 lakh). N1's gold and silver 1-ITM premiums were about Rs 10,000-89,000 a lot; a -40% stop
   is a Rs 4,000-36,000 loss, which is why N1 and W1 breached the -Rs 13k month.

## Which ideas died and why

| idea | why it died |
|---|---|
| S2 squeeze direction | 7 trades in 11 months; lost; the 2012-25 futures check is flat (+0.17%) |
| W1 wide-range continuation | lost on every book (12% win); the big bar is usually exhausted. 2012-25 futures mixed (crude +1.12%, gas -0.69%) |
| V1 IV below forecast | rare and lost; the variance premium is real on MCX too |
| E1 event straddle | lost before costs; events are priced (MB1's bigger event hours are already in the premium) |
| E2 event follow | lost before costs; repeats R83 (EIA / storage follow) with options |
| U1 US-session momentum | lost before costs on all four books; the literature's intraday momentum does not show on MCX after the US open |

## Leads worth refining

1. **N1 NR7 breakout** (strong: design twin p 0.006, the 2012-25 futures check positive on 4 of 4 books). Problems:
   model-priced silver exits, 2 of 4 folds, a -Rs 20,791 month, a -Rs 51,030 drawdown.
2. **P1 pullback in trend** (weak: design +Rs 1.08 lakh, 3 of 4 folds, twin p 0.16, but **negative over 2012-25**). The
   design gain looks like the 2025-26 metals trend; round 2 does not refine it further unless the trend filter is
   changed (R2-6).
3. **S1 squeeze straddle** (too few trades to judge: 6).

## Round 2: pre-registered list (new or refined ideas, from this evidence and the literature)

Same design / holdout / gates / costs / twins as round 1 (PREREG_ROUND1 s2-7); BH over the cumulative ledger
(9 + these). Round 2 writes its own PREREG_ROUND2.md with the exact numbers before running anything.

| # | idea | why (evidence) | rule sketch (signal on the future) |
|---|---|---|---|
| R2-1 | **N1 with real-print exits only** | N1's profit came from model marks | NR7 break as N1; exit at the first real print once the held strike reaches the edge of the stored window ("edge take-profit"), so every exit is a real fill |
| R2-2 | **NR7 break, 2-ITM strike** | deeper strike: more delta, less theta, stays printed longer | as R2-1 with the 2-ITM strike |
| R2-3 | **NR7 + inside day (Crabel's ID/NR7)** | the stronger compression filter in Crabel; fewer, cleaner breaks | NR7 that is also an inside day; break / exits as R2-1 |
| R2-4 | **NR7 break on the big energy books** | energy strikes stay inside the window; CRUDEOIL / NATURALGAS options print in 96-97% of minutes | N1 on CRUDEOIL and NATURALGAS options (1-ITM), R2-1 exits; label the bigger lot size and capital |
| R2-5 | **NR7 break with a capped premium** | N1's month and drawdown breaches came from Rs 25,000+ gold / silver premiums | R2-1, but skip a trade whose 1-lot stop risk exceeds Rs 6,500 (2% of Rs 3.25 lakh); pre-stated, risk-lowering |
| R2-6 | **Squeeze straddle, relaxed** | S1 positive but 6 trades | squeeze on >= 3 of the previous 5 sessions (instead of 5 of 6), all six books incl. CRUDEOIL / NATURALGAS; R2-1 edge exits |
| R2-7 | **NR7 -> straddle (non-directional compression)** | literature: compression predicts range expansion, not direction | ATM straddle at 09:15 after an NR7 day, out after 3 sessions, stop -30%, lock ladder |
| R2-8 | **Multi-day trend with an option "stop-and-reverse" hedge** (Fung-Hsieh: trend following ~ lookback straddle) | P1 / W1 show direction matters more than vol; replace P1 | 20-day Donchian close break confirmed by an NR7 within the previous 5 sessions ("break out of a quiet base"); 1-ITM, R2-1 exits, 5 sessions |

Also for round 2 (data): ask for wider strikes (ATM+/-10) of the four mini books to be added to the lake, so deep-ITM
exits can be priced from real prints; until then R2-1 keeps every exit on a real print.

## Honest limits

- 11.3 months of design data, near-month options, ATM+/-3 only; assumed spreads (no bid/ask), stressed at 2x.
- The design window overlaps windows already studied by R53, R83, R85, R89, R93, R94 and MB1 (other ideas, same
  months). The R96 holdout (15 Jul - 19 Oct 2026) is unseen by R96, not by every earlier study.
- Twins move a trade to a random session of the same month and book, so a twin p measures timing inside the month, not
  the month's trend.
- Model-priced exits (Black-76 at the nearest stored strike's IV) are an approximation; that is why N1 is only a lead.

## Files

`research/hunt/r96_mcx_buy/`: `PREREG_ROUND1.md`, `INTERNET.md`, `NOTES.md`, `LEDGER.md`, `cfg.py`, `data.py`,
`rules.py`, `engine.py`, `history.py`, `study.py`, `report.py`, `entries.py`, `tests/` (29 tests incl. the look-ahead
guard), `results/`. Commands: `IRAALGO_LAKE=~/lake python -m research.hunt.r96_mcx_buy.study design`, then
`... .report 1` and `... .entries 1`; `... .study holdout <rule>` only for a design pass (once).
