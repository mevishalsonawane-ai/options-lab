# Optimal Control Models in Finance: A New Computational Approach - Ping Chen and Sardar M.N. Islam (2005)

Source: optimal-control-models-in-finance-a-new-computational-approa.txt, 220 pages (Springer, Applied Optimization
vol. 95), read in full on 25 Oct 2026 (text layer, pdftotext; Preface, Introduction, Chapters 1-6, Appendices A-F,
references and index). Every displayed equation and every figure is missing from the text layer, and most numeric tables
are blank (see "Could not read"); the prose, the algorithm steps, the MATLAB listings and the Appendix B outputs did come
through. Book claims are the authors', not tested results. Signal source per method is in IraAlgo terms (CLAUDE.md).

## What it is about
A short research monograph (a PhD thesis turned book) on computing deterministic "bang-bang" optimal control problems.
The control can only jump between two or three fixed values, so the task becomes finding the best switching times.
Its main idea is to charge a fixed cost for every switch, which makes the best number of switches finite.
It builds MATLAB programs (called CSTVA) that treat the switching times as the unknowns and solve them with SQP.
The finance examples are toy models: an all-in / all-out stock allocation that tracks a target path, a damped "financial oscillator", and a 1970 corporate financing model.

## Methods, rules and setups

The book teaches no trading rule. It is about numerical method; its "finance" models have known, deterministic
dynamics and a known target path, which a trader never has. The chapter-by-chapter account below keeps the practical
methods in full blocks and gives the theory one or two lines.

### Chapter 1. Optimal control in finance: the theory and a survey of solvers
- A control model has a state that moves by a differential equation, a control chosen over a fixed horizon, and a cost
  (an integral plus an end-point term) to minimise. Karush-Kuhn-Tucker conditions are stated as the static analogue;
  Pontryagin's maximum principle (Hamiltonian, co-state solved backwards) as the dynamic one. Theory only.
- Bang-bang control: when the dynamics are linear in the control, the best control sits on the corners of the allowed
  set and jumps between them. A "singular arc" (control strictly inside the set for a while) can follow and must be
  checked for. Theory only.
- Blatt's (1976) "indifference principle" for problems with a cost per switch: the maximum principle is replaced by a
  weaker condition. Without a switch cost, the control must flip the moment the other setting becomes better. With a
  cost, it is merely ALLOWED to flip when the two settings are equally good, and it can stay put inside a region where
  the other setting is better. See method 2 below: this is the book's one idea a trader can use.
- A survey of solvers: OCIM (augmented Lagrangian, FORTRAN), RIOTS 95, MISER3 / control parameterisation with a
  penalty on total variation of the control, Li's optimal switching among subsystems, SCOM (Craven and Islam, MATLAB,
  step-function control, finite-difference gradients), a natural-resource investment model with switching costs and
  impulse control, CPET and STC for time-optimal problems, the Leap-frog algorithm. Step-function controls work well
  because integrating the dynamics smooths (low-pass filters) the coarse control.

### 1. Bang-bang allocation with a cost per switch (Chapter 2, Sections 2.5-2.8; Chapter 3, Section 3.6)
- What it computes: the switching times of an all-in / all-out decision (control 1 or 0; later +2 / -2) that keep a
  state (in the toy model, a "stock price" pushed up while money is in stocks) close to a target path, minimising the
  tracking error plus K times the number of switches.
- Entry / stop / exit: not a trading rule; the "switches" are allocation flips chosen with full knowledge of the dynamics.
- Inputs: the dynamics, the target path, the horizon, the per-switch cost K, the number of switches to try.
- Book's result: without a switch cost, the error keeps falling as switches are added (Appendix B, toy model: 0.0627
  with 2 intervals, 0.0291 with 4, 0.0184 with 6, 0.0134 with 8), so the "optimum" chatters forever. With a cost, the
  total first falls and then rises, giving an interior best number of switches. With K = 0.02 every extra switch made
  things worse; with K = 0.0002 the cost was too small to matter; K = 0.002 gave a clear interior minimum. In the
  oscillator model with K = 0.01, both starting orders reached the same optimum at 6 big intervals.
- The authors' conclusion: a higher switch cost means fewer switches; an investment strategy should always count the
  cost of switching when deciding how often to flip between in and out.
- How we could use it: as a way of thinking about how often a rule may flip. Every flip of an option position costs a
  round trip (about Rs 98 NIFTY / Rs 133 BANKNIFTY ATM per R16), so a flip is worth making only if the expected gain
  beats that. It is the same arithmetic as R16's verdict, not a new signal.
- Signal source: none (a deterministic planning model, no market data); maps to: NSE index options, MCX and crypto only
  as the general principle "switch frequency must pay for its round trips"; crypto rules would still need a stop and a
  profit lock.
- Mechanical? yes as an optimiser, but only for a known model; no market rule.
- Our research: the cost side is measured: FINDINGS.md "What is predictable? (R16)": every option-buying direction
  version loses Rs 12-142 a trade after costs. Not tested by us as a switch-frequency optimiser.

### 2. Hysteresis from a switching cost: Blatt's indifference principle (Chapter 1, Section 1.6)
- What it computes: a rule for when an on/off decision may change. With a cost per switch, keep the current setting
  until the advantage of the other one is large enough to cross an "indifference curve"; the region in between is a
  band where staying is optimal.
- Entry / stop / exit: not stated as a trading rule. The trader's reading: a dead-band (hysteresis) around any on/off
  filter, so a filter that hovers near its threshold does not flip the arm on and off.
- Inputs: the gain from each setting and the cost of a switch.
- How we could use it: (a) for any regime filter that turns an arm on or off (for example a VIX level filter in the
  R101 arms) use separate on and off thresholds instead of one line; (b) for hedging, rehedge only when delta leaves a
  band, which is exactly what DEEP_OPTIONS_SELLING.md describes professionals doing ("when the position's delta
  reaches, say, +-0.2 per lot"), and why it does not work for one lot.
- Signal source: none by itself; in use it would sit around an `underlying price/volume` or VIX filter; maps to: NSE
  index options (filter design, paper first), MCX (same), crypto (same; stop and profit lock still required).
- Mechanical? partly: the band width must be chosen, and the book gives no way to choose it from market data.
- Our research: not tested by us as a separate idea. DEEP_OPTIONS_SELLING.md (Delta hedging paragraph) explains why a
  one-lot seller cannot hedge with futures at all.

### Chapter 2. The switching-time-variable (STV) method
- Instead of fixing a grid and asking which value the control takes on each piece, make the lengths of the pieces the
  unknowns (they must be non-negative and add to the horizon). A piecewise-linear change of time maps the unequal pieces
  to equal ones so a fixed-step solver lands its steps exactly on the switches. A non-linear time change handles long
  horizons and discounting; a scaling maps any horizon to [0, 1].
- The solver is MATLAB's old `constr` (sequential quadratic programming, quasi-Newton Hessian), with gradients from
  finite differences, and Craven's `nqq` fixed-step Runge-Kutta routine that tolerates jumps in the control.
- Algorithm 2.1-2.4: solve for the best switch times at a given number of switches, add K times that number, increase
  the number until the penalised total stops falling.

### 3. Optimising event times directly instead of on a grid (Chapter 2, Sections 2.2-2.6)
- What it computes: the exact times at which a piecewise-constant decision should change, as continuous unknowns,
  rather than restricting changes to grid points.
- Inputs: a model of the outcome as a function of the switch times; bounds; a starting guess.
- How we could use it: in principle, for fitting cut-off times (entry window ends, square-off times) as continuous
  numbers. In practice this is in-sample fitting of time parameters, which our studies show over-fits: R30's
  combinations made +Rs 300-520 a trade on training years and Rs -111 out of sample (FINDINGS.md, R30 section). Any
  such use would need our usual pre-registration, walk-forward and Benjamini-Hochberg.
- Signal source: none (an optimiser); maps to: any market, as a research tool only.
- Mechanical? yes.
- Our research: not tested by us as a method; the over-fitting risk is documented in R30.

### Chapter 3. A financial oscillator model
- The "financial sector" is modelled as a damped oscillator (a second-order equation, rewritten as two first-order
  ones) driven by a bang-bang forcing of +2 / -2, which must keep it near a target path. Context only: the dynamics are
  assumed, not estimated from any market.
- New computing device: two grids, "big" intervals where the control may switch and "small" ones inside them for
  accurate integration. More of either lowers the error.

### 4. Escaping local minima: multi-start, warm start, perturbation, all orderings (Chapters 3 and 5)
- What it computes: nothing new; it is a practical checklist for any non-convex optimisation, learned from the
  book's own failures.
- The failures it reports: with 4 or 6 big intervals and 8 small ones, the optimiser stopped early and the control
  collapsed to fewer switches; nudging one control value (2, -2.05, 2) let it continue to J = 0.2034. In the
  three-value model, 9 big intervals with 1 small gave J = 1.5791, nearly double the 6-interval result: a local
  minimum where the control jumped only twice. One error came from a starting guess that broke the constraint (interval
  lengths of one half each that did not add to 1).
- The fixes tested (Table 5.3): warm-start from the best solution of the smaller problem (halve its interval lengths)
  and perturbing the control both worked about equally; raising the lower bound on interval lengths to force switches
  did NOT work, because it forced switches where none were optimal; a larger control value fixed a target that rose
  faster than the control could push.
- Starting order matters: beginning with -2 or with +2 gave very different answers on coarse grids and similar answers
  on fine ones, so every ordering has to be tried.
- How we could use it: when a study tunes anything by optimisation (a sizing curve, a model fit), use several starts,
  check the starting point is feasible, and report the spread of answers, not only the best one.
- Signal source: none (method); maps to: research tooling for all markets.
- Mechanical? yes.
- Our research: not tested by us as such; our studies mostly use grids and pre-registered parameter sets rather than
  continuous optimisers.

### Chapter 4. Davis and Elzinga's optimal corporate financing model
- A public utility chooses how much of its earnings to retain and how much new stock to sell; the states are the share
  price and the equity per share; the aim is the present value of dividends plus the final share price. The 1970
  analytic answer is bang-bang (singular arcs appear but are not optimal); this book's programs reproduce it (a
  computed 1.49 against the analytic 1.47 in one case; one switch of financing source in the other; with starting price
  0.5 and equity 1, final price 1.99 and equity 1.96). Corporate finance, not trading.
- Penalty terms and the augmented Lagrangian are used to fold the end-point constraint into the cost (a narrow
  approximation to a delta function puts the end-point term inside the integral). For these optimisation methods in
  more depth see research/books/fm-nonlinear-optimization.md.

### Chapter 5. More computing experiments
- Squared and absolute tracking errors, targets that are straight lines, curves and a steep slope; the three-value
  control -1 / 0 / +1 (read as buy back stock / do nothing / issue stock to keep the price steady). A target that
  oscillates needs a "do nothing" middle value to be tracked well. This is the same lesson as long / flat / short:
  a flat state matters. Context only.
- The rest is the local-minimum lessons in method 4.

### Chapter 6 and appendices
- Chapter 6 restates the six parts of the method and the switch-cost conclusion.
- Appendix A lists the MATLAB programs (investment, oscillator, financing, three-value models); B prints their outputs;
  C and D list the SCOM package and its `nqq` solver; E explains its input format; F is a test problem with gradients.
  Nothing to reuse: the code targets MATLAB 5's withdrawn `constr`.

## Performance claims
- No trading, return or win-rate claims anywhere in the book.
- Computing claims: the programs match the analytic Davis-Elzinga solution closely (1.49 vs 1.47; one switch as the
  theory says) and a separate SCOM run with 20 equal intervals agrees. The tracking error falls with more switches
  (0.0627, 0.0291, 0.0184, 0.0134 for 2, 4, 6, 8 intervals). The authors call the approach efficient and plausible;
  no timing benchmark against other solvers is given.

## Risk, sizing and psychology
- Nothing on position sizing, stops, risk measures or psychology. The only "risk" idea is that switching has a cost
  and too much switching destroys value; the authors add that real financing switches also depend on judgement.

## How it relates to our research
- Agrees with our cost findings in spirit: every switch must pay for itself. Our numbers are the round-trip costs in
  research/knowledge/baselines.yaml (about Rs 98 NIFTY, Rs 133 BANKNIFTY ATM, from R16) and HUNT_H24.
- The hysteresis idea matches the hedging band described in DEEP_OPTIONS_SELLING.md, which also explains why a one-lot
  NIFTY seller cannot use it.
- The warning about optimising switch times fits our over-fitting record (R30 section of FINDINGS.md).
- Nothing in the book tests anything on market data, so it neither confirms nor contradicts any HUNT result. It has no
  link to R102/R103 volatility work, GEX, Kelly sizing (HUNT_H15), Monte Carlo or our ML studies.
- For volatility trading see research/books/bennett-trading-volatility.md; this book has nothing on it.

## What is new to us
- A formal reason for hysteresis: with a cost per switch, the best rule has a band where you keep doing what you were
  doing. We have not tested separate on / off thresholds for the arms' regime filters (VIX, breadth); it would be a
  risk-lowering, paper-first change at most.
- The idea of choosing the number of switches (trades, rebalances, filter flips) by minimising "error plus cost times
  switches", with an interior optimum.
- A short checklist for optimisers: feasible starting point, multi-start, warm start from the smaller problem, try
  every ordering, and do not force extra switches.

## Could not read
- Every displayed equation (the models, transformations, Hamiltonians and penalty terms), all 18 figures, and the bodies
  of Tables 2.1, 2.2, 3.1, 3.2, 4.1-4.3 and 5.1-5.4 are missing from the text layer. Some numbers were recovered from
  the prose and from Appendix B's printed outputs. The parameter values of the financing model in Chapter 4 are not
  readable. Nothing else is missing.
