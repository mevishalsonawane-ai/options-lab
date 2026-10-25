"""R104 rule register (PREREG.md section 3): every one of the 211 book rules of research/books/TESTABLE_RULES.md, with
what R104 does with it. Written 25 Oct 2026 BEFORE any R104 result.

status
  test      run as pre-registered; one gated test per market listed in `mk`
  covered   a near-duplicate of a rule our research already tested; the earlier result is recorded, no new test
            (no multiple-testing budget spent). `why` says which study and why the book's version does not differ
            materially.
  lack      needs data this environment does not have (or has too little of); `why` says exactly what.
  desc      a descriptive question (no trade); reported, not gated, not in BH.

markets (engines, PREREG.md section 5)
  IDX   index intraday, signal on the NIFTY / BANKNIFTY spot, BUY the option (R51 held-contract engine), flat same day
  IDXF  index on spot 1-minute bars as a near-month FUTURE proxy (spot points x lot, carry, futures charges)
  DLY   index daily bars 2006+ as a near-month FUTURE proxy (the book's multi-day holds)
  MCXI  MCX intraday on the mini futures' own 1-minute bars with real volume (Oct 2025 - Oct 2026), trade the future
  MCXD  MCX daily near-month futures 2012+, back-adjusted at rolls, trade the future
  CRY   BTC / ETH USD-M perpetual 1-minute bars (Oct 2023+), trade the perp, always with a stop and a profit lock
  STK   F&O stocks daily 2002+, trade a stock-future proxy
  ARM   an exit overlay or entry filter on the arms' own candidates (R101's B0 lists, Nov 2024 - Oct 2026)
  MM    a money-management rule on the arms' B0 trade lists (risk-lowering bar, PREREG.md section 7)
  VOL   an option structure on the NIFTY / BANKNIFTY chain (R103's lake; stale legs repriced)
"""
from __future__ import annotations

FAMILIES = {
    "F1": "Volume-confirmed breakouts",
    "F2": "Wyckoff tape / volume and Wyckoff trade management",
    "F3": "Price-action bar and chart patterns with the books' own stops",
    "F4": "Carli divergence",
    "F5": "Swing rules on daily bars",
    "F6": "Porwal's Indian setups",
    "F7": "Aziz VWAP / ABCD / bull flag",
    "F8": "Volatility and premium structures (Bennett, Wilmott, Javaheri, Bjork, Musiela, Ross, WHD)",
    "F9": "Money management on the arms",
    "F10": "Indicator and intraday trend rules",
    "F11": "Exit overlays and entry filters on the arms",
    "F12": "Pairs, mean reversion, seasonals and crypto (quant books)",
    "NEW": "Amendment 1: new strategies from combining book ideas",
}

# id: (status, family, markets, why / R104 note)
R = {
    # ---------------------------------------------------------------- Wyckoff
    "book-wyckoff-01": ("lack", "F2", (), "index-futures tick prints exist only in the phone's recorder since 12 Oct 2026 (private repo); ~3 months needed"),
    "book-wyckoff-02": ("test", "F2", ("MCXI",), "MCX minis have real minute volume (Oct 2025+); the index version lacks volume (index-futures minutes only from 29 Jul 2026, 57 sessions)"),
    "book-wyckoff-03": ("lack", "F2", (), "index only; needs index-futures minute volume, which exists only from 29 Jul 2026 (57 sessions, too few for design + holdout)"),
    "book-wyckoff-04": ("test", "F1", ("MCXI",), "CRUDE and NATURALGAS minis with real volume; price-only twin reported beside it (not gated); index version lacks volume"),
    "book-wyckoff-05": ("covered", "F1", (), "is the R72 Breadth Donchian arm: PASS (final +Rs 1,71,366 on 1,202 trades), on paper"),
    "book-wyckoff-06": ("test", "F11", ("ARM",), "exit overlay on LIQ and R72"),
    "book-wyckoff-07": ("test", "F2", ("IDX",), ""),
    "book-wyckoff-08": ("test", "F11", ("ARM",), "swing-low trail overlay on R72, R51, LIQ"),
    "book-wyckoff-09": ("test", "F11", ("ARM",), "fixed-distance trail to cost cover, overlay on every arm; the crypto clause has no crypto arm to apply to"),
    "book-wyckoff-10": ("test", "F11", ("ARM",), "no-progress exit at 20 minutes, overlay on every arm"),
    "book-wyckoff-11": ("test", "F11", ("ARM",), "4-day streak skip filter (3-day variant chosen walk-forward)"),
    "book-wyckoff-12": ("test", "F11", ("ARM",), "late-day tightening overlay"),
    "book-wyckoff-13": ("test", "F11", ("ARM",), "skip NIFTY/BANKNIFTY longs while a secondary index bursts alone"),
    # ---------------------------------------------------------------- Woods
    "book-woods-01": ("test", "F3", ("IDX", "MCXI"), "the book's wider pattern stop (README pick 3)"),
    "book-woods-02": ("test", "F3", ("IDX", "MCXI"), ""),
    "book-woods-03": ("test", "F3", ("IDX",), ""),
    "book-woods-04": ("test", "F5", ("DLY", "MCXD"), ""),
    "book-woods-05": ("test", "F1", ("DLY", "MCXD"), "volume = the near future's daily volume (index futures daily volume 2012+)"),
    "book-woods-06": ("test", "F3", ("IDX",), ""),
    "book-woods-07": ("test", "F3", ("IDX",), ""),
    "book-woods-08": ("covered", "F3", (), "R71 P7 three-bar reversal (final -Rs 1,67,689 on 855, twin p 0.002: timing real, below option cost); the book's stop/exit are R71's"),
    "book-woods-09": ("test", "F3", ("IDX",), "differs from R71 P1 (two-sided) by taking the trend side only"),
    "book-woods-10": ("test", "F3", ("IDX",), ""),
    "book-woods-11": ("test", "F3", ("IDX",), ""),
    "book-woods-12": ("test", "F3", ("IDX", "DLY", "MCXI"), "differs from R71 P2 (two-sided) by taking the trend side only"),
    "book-woods-13": ("test", "F3", ("IDX", "DLY"), ""),
    "book-woods-14": ("test", "F3", ("IDX",), ""),
    "book-woods-15": ("test", "F3", ("IDX",), "adds R87-1's missing prior-decline filter and the book's stop"),
    "book-woods-16": ("test", "F3", ("IDX", "DLY", "MCXI"), ""),
    "book-woods-17": ("test", "F3", ("IDX",), ""),
    "book-woods-18": ("test", "F3", ("IDX",), ""),
    "book-woods-19": ("test", "F3", ("IDX",), ""),
    "book-woods-20": ("test", "F3", ("IDX",), ""),
    # ---------------------------------------------------------------- Carli
    "book-carli-01": ("test", "F4", ("DLY", "MCXD"), "README pick 4"),
    "book-carli-02": ("test", "F4", ("IDXF", "MCXI"), "half-session bars, held overnight: index as a futures proxy"),
    "book-carli-03": ("test", "F4", ("IDX",), "R95's confirmed divergences re-built on 15/60-minute bars with Carli's three filters"),
    "book-carli-04": ("test", "F11", ("ARM",), "gap-direction skip filter"),
    # ---------------------------------------------------------------- Hougaard (Trader Tom)
    "book-tom-01": ("test", "F10", ("IDX", "MCXI"), ""),
    "book-tom-02": ("test", "F10", ("IDX", "MCXI"), ""),
    "book-tom-03": ("test", "F11", ("ARM",), "daily 3-bar swing bias filter"),
    "book-tom-04": ("test", "F10", ("IDX", "MCXI"), ""),
    "book-tom-05": ("test", "F3", ("IDX",), ""),
    "book-tom-06": ("test", "F3", ("IDX",), ""),
    "book-tom-07": ("test", "F3", ("IDX", "MCXI"), "15-minute 2-bar fractal swing, not R91's 1-hour swing"),
    "book-tom-08": ("test", "F3", ("IDX",), ""),
    "book-tom-09": ("test", "F3", ("IDX",), ""),
    "book-tom-10": ("test", "F10", ("IDX", "MCXI"), ""),
    "book-tom-11": ("test", "F3", ("IDX",), ""),
    "book-tom-12": ("test", "F3", ("IDX",), ""),
    "book-tom-13": ("test", "F3", ("IDX",), ""),
    "book-tom-14": ("test", "F3", ("IDX", "DLY"), ""),
    "book-tom-15": ("test", "F3", ("IDX",), ""),
    "book-tom-16": ("test", "F3", ("IDX", "MCXI"), ""),
    "book-tom-17": ("covered", "F11", (), "R101 B4/C4 'entries by 12:00' on R72/R51 (raised win rate, cut final net -Rs 81,567) and R92 entry-hour study; the book's 12:15 differs by 15 minutes"),
    "book-tom-18": ("test", "F11", ("ARM",), "events.csv (2024+, India and US); the MCX clause has no MCX arm"),
    "book-tom-19": ("covered", "F11", (), "R81 X7 pyramid on Liquidity (+Rs 58k design, -Rs 20k final) and H5: the same add-at-+1R mechanism"),
    "book-tom-20": ("covered", "F11", (), "R81 X5 scale-out (half at 1R, -Rs 79k design / -Rs 35k final) and R100: the same partial-profit mechanism"),
    # ---------------------------------------------------------------- Ozenbas
    "book-ozenbas-01": ("test", "F5", ("DLY", "MCXD"), ""),
    "book-ozenbas-02": ("covered", "F11", (), "h14 capped limit +0.5%: the only execution policy to pass (+Rs 288/day), already the Liquidity arm's entry"),
    "book-ozenbas-03": ("lack", "F11", (), "no historical bid/ask for index options (one snapshot, 6 Oct 2026)"),
    "book-ozenbas-04": ("test", "F10", ("IDX",), "event days = events.csv (2024+); earlier days count as no-news days (calendar gap, labelled)"),
    "book-ozenbas-05": ("test", "F10", ("IDX",), "1-minute spot close as the best-bid proxy (the book's futures bid has no history)"),
    "book-ozenbas-06": ("lack", "F11", (), "no option book-depth history"),
    "book-ozenbas-07": ("lack", "F11", (), "needs tick-by-tick book and queue position"),
    # ---------------------------------------------------------------- Advanced Trading Book
    **{f"book-atb-{i:02d}": ("test", "F3", m, "") for i, m in (
        (1, ("IDX", "MCXI")), (2, ("IDX", "MCXI")), (3, ("IDX",)), (4, ("IDX", "MCXI")), (5, ("IDX",)),
        (6, ("IDX", "MCXI")), (7, ("IDX", "MCXI")), (8, ("IDX", "MCXI")), (9, ("IDX", "MCXI")), (10, ("IDX", "MCXI", "CRY")),
        (11, ("IDX", "MCXI")), (12, ("IDX", "MCXI")), (13, ("IDX", "MCXI")), (14, ("IDX", "MCXI")), (15, ("IDX", "MCXI", "CRY")),
        (16, ("IDX", "MCXI")), (17, ("IDX", "MCXI")), (18, ("IDX", "MCXI")), (19, ("IDX", "MCXI", "CRY")))},
    # ---------------------------------------------------------------- Bennett
    "book-bennett-01": ("test", "F8", ("VOL",), "version A (intraday iron fly); README pick 2"),
    "book-bennett-02": ("test", "F8", ("VOL",), "future = spot + carry proxy; call held to expiry (cash settled)"),
    "book-bennett-03": ("test", "F8", ("VOL",), ""),
    "book-bennett-04": ("test", "F8", ("VOL",), "hedge on the spot as a futures proxy"),
    "book-bennett-05": ("test", "F8", ("VOL",), "hedge on the spot as a futures proxy"),
    "book-bennett-06": ("lack", "F8", (), "next-expiry ATM chain is mostly missing from the rolling lake"),
    "book-bennett-07": ("lack", "F8", (), "needs two expiries' chains on event days"),
    "book-bennett-08": ("lack", "F8", (), "matched-maturity IVs need a second expiry; with the nearest only, a 10-session vol position has to roll weekly, which the book's trade is not"),
    "book-bennett-09": ("test", "F8", ("VOL",), "rare signal (>= 4% fall in 3 sessions); min-trades gate likely binding"),
    "book-bennett-10": ("desc", "F8", (), "regression only, no trade"),
    # ---------------------------------------------------------------- Sadowski
    "book-sadowski-01": ("test", "F5", ("DLY", "MCXD"), "daily; differs from OBUY TI-03 (intraday EMA crosses)"),
    "book-sadowski-02": ("test", "F5", ("DLY", "MCXD"), ""),
    "book-sadowski-03": ("test", "F10", ("IDX",), ""),
    "book-sadowski-04": ("test", "F3", ("IDX",), ""),
    "book-sadowski-05": ("test", "F3", ("IDX",), ""),
    "book-sadowski-06": ("test", "F3", ("IDX",), "three limit parts modelled as three sub-lots"),
    "book-sadowski-07": ("test", "F10", ("IDX", "MCXI"), "60-minute / 15-minute, entry across the middle SMA (R71 P3 used the band close)"),
    "book-sadowski-08": ("test", "F3", ("IDX", "MCXI"), ""),
    "book-sadowski-09": ("test", "F10", ("IDX", "DLY"), "momentum reading on NIFTY/BANKNIFTY not tested before"),
    "book-sadowski-10": ("test", "F5", ("IDXF",), "multi-session hold: index as a futures proxy"),
    # ---------------------------------------------------------------- Heitkoetter
    "book-heitkoetter-01": ("test", "F10", ("IDX",), ""),
    "book-heitkoetter-02": ("covered", "F10", (), "H25 price x SMA crosses (15,120 variants, Rs -145 a trade, 1 of 28 walk-forward families positive), exits of this kind included"),
    "book-heitkoetter-03": ("covered", "F10", (), "OBUY_GB TI-03 MA crosses (-Rs 3,69,856 walk-forward on 4,526) and R29"),
    "book-heitkoetter-04": ("test", "F10", ("IDX", "MCXI"), "15-minute bars with 0.27% / 0.41% / 45-minute exits differ from the 5-minute Donchian arm"),
    "book-heitkoetter-05": ("covered", "F10", (), "H25 MACD signal / zero cross: 27,000 variants, 0 of 50 families positive walk-forward"),
    "book-heitkoetter-06": ("covered", "F10", (), "H25 Williams %R as given: no better than random"),
    "book-heitkoetter-07": ("covered", "F10", (), "OBUY_GB MR-03 RSI 30/70 reversal: -Rs 82,004 walk-forward on 1,272"),
    "book-heitkoetter-08": ("covered", "F10", (), "OBUY_GB MR-02 Bollinger fade: -Rs 90,790 walk-forward on 2,136"),
    "book-heitkoetter-09": ("test", "F10", ("IDX",), ""),
    "book-heitkoetter-10": ("covered", "F10", (), "R1 N11/N12 first-candle rules and the ORB family (R3, R92 0 of 257), PDH/PDL R89 0 of 180"),
    "book-heitkoetter-11": ("test", "F11", ("ARM",), "15-minute time stop on R51/R72 (R18 V3 was 45 minutes on other arms)"),
    "book-heitkoetter-12": ("test", "F11", ("ARM",), ""),
    "book-heitkoetter-13": ("covered", "F11", (), "R81 X5 scale-out failed; thirds are the same partial-profit mechanism"),
    "book-heitkoetter-14": ("test", "F9", ("MM",), "README pick 5"),
    "book-heitkoetter-15": ("test", "F11", ("ARM",), ""),
    "book-heitkoetter-16": ("test", "F11", ("ARM",), "events.csv covers the arms' window (2024+); NSE holidays from the trading calendar; US payrolls not in the calendar (dropped, labelled)"),
    # ---------------------------------------------------------------- Larry Swing
    "book-lswing-01": ("test", "F5", ("DLY",), "Force Index on the near future's daily volume (2012+)"),
    "book-lswing-02": ("test", "F5", ("MCXD",), ""),
    "book-lswing-03": ("test", "F5", ("STK",), "stock-future proxy (the 10 R58 names); the book's monthly option not priced"),
    "book-lswing-04": ("test", "F5", ("DLY",), ""),
    "book-lswing-05": ("test", "F5", ("DLY",), "exit swap on SWING_DEEP's three hosts, judged against the hosts' own exits"),
    "book-lswing-06": ("test", "F5", ("IDXF",), "A vs B on the same setups, 1-minute execution (NIFTY 2017+, BANKNIFTY 2021+)"),
    # ---------------------------------------------------------------- Graeme-Smith
    "book-smith-01": ("test", "F5", ("DLY",), ""),
    "book-smith-02": ("test", "F5", ("IDX",), "intraday variant (15:15 out) bought options"),
    "book-smith-03": ("test", "F5", ("DLY",), "exit swap on SWING_DEEP's hosts"),
    "book-smith-04": ("test", "F5", ("MCXD",), ""),
    "book-smith-05": ("lack", "F5", (), "no results / AGM calendar for F&O stocks"),
    "book-smith-06": ("test", "F12", ("CRY",), "the step trail is the profit lock; stop always on"),
    "book-smith-07": ("test", "F5", ("DLY",), "gap-skip filter on the daily stop-entry hosts"),
    # ---------------------------------------------------------------- Boussafsaf and Taleb
    "book-atm-01": ("lack", "F11", (), "multi-lot exit fills need book depth"),
    "book-atm-02": ("lack", "F12", (), "index-futures minutes only from 29 Jul 2026 (57 sessions)"),
    # ---------------------------------------------------------------- Porwal
    "book-porwal-01": ("test", "F6", ("IDX", "MCXI"), "thirds as three sub-lots"),
    "book-porwal-02": ("test", "F6", ("DLY",), ""),
    "book-porwal-03": ("test", "F6", ("DLY", "MCXD"), ""),
    "book-porwal-04": ("test", "F6", ("DLY",), ""),
    "book-porwal-05": ("test", "F1", ("MCXI",), "with real volume on MCX; the index price-only box is the R51 Donchian arm (covered) and its volume part lacks data"),
    "book-porwal-06": ("covered", "F6", (), "R23 first-15-minute break (-Rs 71,624 on 1,171) and R92 by day type (0 of 257); the prior-day trend and EMA bias are day-type filters R92 covered"),
    "book-porwal-07": ("test", "F11", ("ARM",), "52-week filter on R51/R72"),
    "book-porwal-08": ("test", "F6", ("IDX",), ""),
    "book-porwal-09": ("test", "F6", ("IDX", "MCXI"), ""),
    "book-porwal-10": ("lack", "F6", (), "index rule needs futures minute volume (57 sessions only); the price part (fade the day-high break) is covered by R86 (6 of 6 lost) and R91"),
    "book-porwal-11": ("lack", "F6", (), "index rule needs futures minute volume (57 sessions only); the price part (day-low reclaim) is R87's held fact, below option cost"),
    "book-porwal-12": ("test", "F11", ("ARM",), ""),
    # ---------------------------------------------------------------- Aziz
    "book-aziz-01": ("covered", "F7", (), "ORB on VWAP's side with a narrow range: R10 V1 / T6 and R3 / R49 / R92 (0 of 16, 0 of 336, 0 of 257)"),
    "book-aziz-02": ("test", "F7", ("MCXI", "IDX"), "MCX with real VWAP; index with a session TWAP proxy (labelled)"),
    "book-aziz-03": ("test", "F7", ("IDX",), "TWAP proxy for VWAP"),
    "book-aziz-04": ("test", "F7", ("IDX", "MCXI"), "index TWAP proxy; MCX real VWAP"),
    "book-aziz-05": ("test", "F7", ("IDX",), "TWAP proxy; the 1-minute volume condition dropped on the index (no volume), labelled"),
    "book-aziz-06": ("test", "F7", ("MCXI", "IDX"), "MCX with volume; index without the volume condition (labelled)"),
    "book-aziz-07": ("test", "F7", ("IDX",), "TWAP proxy; volume test dropped (labelled)"),
    "book-aziz-08": ("test", "F11", ("ARM",), ""),
    "book-aziz-09": ("covered", "F11", (), "R81 X5 half at 1R + break-even (-Rs 79k design, -Rs 35k final), R100 G13"),
    "book-aziz-10": ("test", "F11", ("ARM",), "R18's post-hoc lead, now pre-registered"),
    "book-aziz-11": ("test", "F11", ("ARM",), ""),
    "book-aziz-12": ("test", "F9", ("MM",), "account-level day rule on the combined arms"),
    "book-aziz-13": ("test", "F9", ("MM",), ""),
    # ---------------------------------------------------------------- Douglas
    "book-douglas-01": ("test", "F9", ("MM",), ""),
    "book-douglas-02": ("test", "F9", ("MM",), ""),
    "book-douglas-03": ("covered", "F11", (), "R81 X5 / R100 partial exits failed; the first third here is taken even earlier"),
    "book-douglas-04": ("test", "F10", ("IDX", "MCXI"), ""),
    "book-douglas-05": ("test", "F11", ("ARM",), ""),
    # ---------------------------------------------------------------- FinMathematics textbooks
    "book-fmwilmott-01": ("test", "F8", ("VOL",), "NIFTY monthly series; hedge on the spot as a futures proxy"),
    "book-fmjavaheri-01": ("lack", "F8", (), "10% OTM monthly strikes are outside the lake's ATM +-10; bhavcopy in the lake is a weekly sample, closes only"),
    "book-fmjavaheri-02": ("lack", "F8", (), "needs the next-monthly chain and far legs (see javaheri-01)"),
    "book-fmjavaheri-03": ("test", "F8", ("VOL",), "intraday; legs beyond ATM +-10 or stale repriced as R103 Amendment 2"),
    "book-fmaqf-01": ("lack", "F8", (), "implied density needs every strike on the entry day; the lake has ATM +-10 intraday and a weekly bhavcopy sample"),
    "book-fmaqf-02": ("lack", "F8", (), "as fmaqf-01"),
    "book-fmaqf-03": ("test", "F12", ("DLY", "MCXD"), "NIFTY vs BANKNIFTY and GOLD vs SILVER, spot / back-adjusted futures"),
    "book-fmaqf-04": ("test", "F9", ("MM",), ""),
    "book-fmbayes-01": ("test", "F12", ("MCXD",), ""),
    "book-fmfabozzi-01": ("test", "F5", ("DLY",), "futures proxy instead of the bought option"),
    "book-fmfabozzi-02": ("test", "F5", ("DLY",), "futures proxy instead of the bought option"),
    "book-fmfabozzi-03": ("test", "F12", ("IDX",), "two bought options, intraday"),
    "book-fmlyuu-01": ("lack", "F12", (), "no minute ETF/basket prices, dividend schedule or futures quotes"),
    "book-fmbjork-01": ("test", "F8", ("VOL",), ""),
    "book-fmbjork-02": ("test", "F8", ("VOL",), ""),
    "book-fmbjork-03": ("lack", "F12", (), "needs synchronous bid/ask on three legs"),
    "book-fmbjork-04": ("test", "F9", ("MM",), ""),
    "book-fmbjork-05": ("test", "F9", ("MM",), ""),
    "book-fmcapinski-01": ("lack", "F12", (), "needs executable bid/ask; last prints in the lake are often stale (R103)"),
    "book-fmcapinski-02": ("lack", "F12", (), "needs executable bid/ask"),
    "book-fmbinomial-01": ("lack", "F12", (), "needs simultaneous bid/ask"),
    "book-fmbinomial-02": ("lack", "F12", (), "needs simultaneous bid/ask"),
    "book-fmbinomial-03": ("test", "F8", ("VOL",), ""),
    "book-fmoptim-01": ("test", "F5", ("DLY", "MCXD"), ""),
    "book-fmoptim-02": ("test", "F9", ("MM",), ""),
    "book-fmhughston-01": ("lack", "F12", (), "needs executable bid/ask"),
    "book-fmschmidt-01": ("test", "F10", ("IDX",), ""),
    "book-fmwhd-01": ("test", "F8", ("VOL",), "IV of each strike from its own minute price (BS on the spot plus carry)"),
    "book-fmwhd-02": ("test", "F8", ("VOL",), "RBI / Budget events in events.csv (2024+); too few events to pass min trades"),
    "book-fmmusiela-01": ("lack", "F12", (), "needs synchronous bid/ask for all legs"),
    "book-fmmusiela-02": ("test", "F8", ("VOL",), "hedge B vs hedge A on the same short straddles"),
    "book-fmmusiela-03": ("test", "F8", ("VOL",), ""),
    "book-fmmusiela-04": ("lack", "F12", (), "no intraday USDINR or dated import-duty history"),
    "book-fmchan-01": ("test", "F10", ("IDXF",), "the book trades the futures: spot points with futures costs"),
    "book-fmchan-02": ("test", "F12", ("DLY",), ""),
    "book-fmchan-03": ("test", "F12", ("MCXD",), "R94 idea 2 without the book's cointegration gate and half-life exit"),
    "book-fmchan-04": ("test", "F12", ("MCXD",), "one trade a year: at most 14 trades, min-trades gate binds"),
    "book-fmchan-05": ("test", "F9", ("MM",), ""),
    "book-fmchan-06": ("test", "F9", ("MM",), ""),
    "book-fmchan-07": ("test", "F5", ("DLY",), ""),
    "book-fmchan-08": ("test", "F12", ("CRY",), ""),
    "book-fmchan-09": ("test", "F12", ("CRY",), ""),
    "book-fmross-01": ("test", "F12", ("MCXD",), ""),
    "book-fmross-02": ("test", "F8", ("VOL",), "index only (MCX options only from 2025)"),
    "book-fmross-03": ("test", "F12", ("MCXD", "DLY"), ""),
    "book-fmross-04": ("test", "F8", ("VOL",), "index only: BANKNIFTY near (monthly) and NIFTY monthly series"),
    "book-fmkaratzas-01": ("test", "F9", ("MM",), ""),
    # ---------------------------------------------------------------- Amendment 1 (new strategies)
    "new-N1": ("test", "NEW", ("ARM",), "volume-confirmed Donchian breaks (option volume as the volume proxy)"),
    "new-N2": ("test", "NEW", ("ARM",), "Wyckoff effort vs result on the 09:20 bar"),
    "new-N3": ("test", "NEW", ("ARM",), "skip option buying on vol-rich days"),
    "new-N4": ("test", "NEW", ("MM",), "month / week account pause on Boss's frame"),
    "new-N5": ("test", "NEW", ("ARM",), "daily SMA50 + DMI(8) direction filter on the arms"),
    "new-N6": ("test", "NEW", ("VOL",), "hedged short straddle only when vol is rich"),
}

ALL_IDS = tuple(R)


BOOK_IDS = tuple(r for r in R if r.startswith("book-"))


def tests() -> list[tuple[str, str]]:
    """Every gated (rule, market) test, in register order."""
    return [(rid, m) for rid, (st, _f, mk, _w) in R.items() if st == "test" for m in mk]


def by_status(status: str) -> list[str]:
    return [rid for rid, v in R.items() if v[0] == status]
