# Confluence search and robustness — registered 14 November 2026 before outcomes

User target interpreted as >=65% net-positive trades AND positive cost-adjusted
profit, not a guaranteed65% return. No claim of searching the entire internet.
Use primary research on support/resistance, order-flow impact and overfitting,
plus repository R105D/R133/R165/R169–171; do not treat old winning subsets as fresh.

## Fixed experiment family
Four underlying entries, lookback20 minutes (all levels shifted to exclude the
current candle): range breakout; sweep-and-close-back-inside; prior-session
high/low reclaim; Bollinger2sigma fade with current reversal candle. Previous
session levels exist only after a full prior observed session. Require20 prior
contiguous current-session minutes. Signals09:45–13:45 IST, no simultaneous CE/PE.

Four contexts: plain; chain (selected-contract relative volume>=1.5, PCR-OI and
same-fixed-contract five-minute OI-change imbalance agree); Greek/proxy (selected
reported-IV Black-Scholes absdelta0.50–0.80, IV<=25%, selected-contract signed
minute-volume PROXY over3 completed contiguous minutes positive, VIX15-minute
rise<=3%); all (both contexts plus observed futures volume>=1.5 prior20-minute
median, futures VWAP and approximate session volume-profile POC agree). No
aggressor delta, resting liquidity or real order book is claimed. Liquidity sweep
means a price-level event, not proven stop executions. Futures profile allocates
bar volume at typical price into25-point bins; it is not tick volume-at-price.
Use actual available futures contract, explicitly disclose far-month bias and
limited July–October coverage. Missing features fail their context gate.

Two exits: +30% target and +10% target, both initial15% premium stop, existing60%
profit lock after+15%,20-minute failure exit,45-minute max,14:50 square-off.
32 predefined rules. Shared fixed-contract/cost engine, one lot,100000 starting
capital, max2trades/day, risk2500, daily3000, monthly13000, drawdown40000.
Each normal/double-friction account independently replayed.

## Chronology and robustness
Latest available12months. First window through31March2026, next Apr–Jun, last
Jul–Oct. Research history already reviewed: these are reused chronological
windows, NOT pristine holdouts. Per-window accounts start100000 for diagnostics;
full account never silently resets. Walk-forward selector evaluates each rule
on all preceding sessions and locks at most one rule for the next window.
Qualify only with >=100trades, winrate>=.65, positive normal AND stress net,
profitfactor>1, realizedDD<40000 and worstmonth>-13000. Highest stressed net breaks
ties deterministically. If none qualify, choose NO_TRADE. Separate descriptive
best-in-design is NOT a passing strategy and never replaces no-trade in WF.

Before seeing later results freeze design-leader sensitivity: lookback16/20/24
and initialstop12/15/18%, same family/context/target. The20/15center is already
in the32; eight extra unique rules. Report all9 cells, not only best. No tuning
on later data. Count40unique rules,80primary full-account normal/stress endpoints
in jointBH; diagnostic fold counts not additional independent hypotheses. Old
research search multiplicity remains uncorrected. Fresh data required for promotion.

MonteCarlo1000 permutations of leader's closed trade net amounts (fixed rupee
P&L, no compounding), report 95th/99th-percentile realized DD, retain fixed ending
P&L limitation. Also1000 day-block resamples, blocklength5days, preserve within-day
trade order and zero-trade days; report net/DD intervals. These are ledger
sequence diagnostics, not full cash-gated engine replays under shuffled signals.
Use seeded RNG42. Confidence: Wilson95% for net winrate (trade-independence
approximation disclosed), day-block mean-net inference and BH. To claim true65%
winning probability require lowerconfidence bound>=.65 as well as all gates;
a point estimate alone is insufficient. No fake65% success, no trades or production.

Pre-outcome implementation clarification: choose the descriptive design leader
only among rules with at least30 normal-friction design trades, using stressed
net to rank; if none have30, use the greatest observed count. A zero-trade
futures gate must not become the sensitivity leader merely because zero beats
a loss. This does not relax the100-trade passing/WF selector. Carried WF account
state preserves cash, peaks and monthly risk across the two evaluation windows.
