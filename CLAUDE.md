# IraAlgo: rules for every Claude session

IraAlgo is an Android options trading app (Kotlin: `android/app`, `android/ira-core`, `android/engine`). Research is in
`research/`. The owner is "Boss" (they/them). The assistant inside the app is "Jarvis". Plain-English comments with dated
notes; match the surrounding code.

## Before every push (Boss, 13 Oct: "test each and everything, and do code review before pushing")

1. **Test everything you touched.**
   - Every new or changed function gets unit tests, including edge cases (empty data, gaps, holidays, expiry day, market
     closed, stale prices, errors).
   - UI changes get a Compose/Robolectric test.
   - Research code gets pytest tests plus the look-ahead guard.
2. **Run the checks locally:**
   - `cd android && ./gradlew -q --no-daemon :engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification`
   - Research: `python -m pytest research/brain/tests -q && python -m research.brain.features catalog --check`, plus the tests
     for any research folder you changed.
   - `:app` compiles only in CI. Never skip, disable or quarantine a test to get green.
3. **Code review before pushing.**
   - Review your own full diff (`git diff origin/<base>...HEAD`) adversarially, line by line. This applies to every
     change.
   - Changes to app code (`android/**`) or workflows (`.github/**`) also need the `code-review` skill or a separate
     reviewer agent. Fix every confirmed finding, then re-test.
   - Research-only and docs-only changes need tests plus the self-review only (Boss, 13 Oct).
   - Check: correctness, threading (no main-thread I/O), the order and tick path, the safety rules below, leaks of secrets
     or personal data, and that the change does only what was asked.
4. **After pushing**, watch CI on your commit (`gh api repos/mevishalsonawane-ai/options-lab/commits/<sha>/check-runs`)
   and fix every failure until everything is green, including the app tests and Build APK.

## Safety rules (never broken, whoever asks)

- Real orders only with Boss's PIN and "AI trades go live" on. Jarvis's own trades stay on paper.
- LIVE: automatic changes may only LOWER risk (skip, smaller size, earlier exit). Anything new that could skip or resize
  live trades starts in shadow (logged only), behind a switch that is off by default.
  - Boss's exception (4 Nov: "if I select 3 lots, buy 3 if the money is there, else try 2, else 1 ... make it default, dont
    have 1 more option"): an arm's live index option buy of more than one lot is cut to the lots the free cash fits, always on,
    no switch. It never buys more than Boss's lots setting and still passes every live gate.
  - The funds check (Boss, 15 Nov: "Yes" to building it in shadow): an arm's live option buy, index or stock options (the arms' live copies,
    ORB and Liquidity 15+5), that the free cash cannot pay for even one lot. It reads only the free cash the entry already read; unknown, it
    says nothing. In shadow by default (logged and counted, the order goes as before); its Bot settings switch (off by default)
    makes it skip the entry. On needs no PIN (it lowers risk); off takes the PIN.
- EVERY ARM CAN GO LIVE (Boss, 30 Oct: "make all arms as live, will stop it if i want, dont keep any in paper only or
  record only, make all live if i move to live market").
  - No arm is paper-only or record-only by design. Each arm's live trade goes only through the same gates: the app in
    Live with Boss's PIN, "AI trades go live" on, the arm's own Trade live switch, every guard and the kill switch.
  - Boss stops any arm with its own switch.
  - Liquidity 15+5 (Boss, 15 Nov: "Liquidity should be always working if i have swtich on it on dashboard, no extra pin based
    approval needed"): on the 15 Nov update every Liquidity book that was on was cleared once, by Boss's request, so the books his
    dashboard shows on trade live when the app is in Live (automatic or approve, as each is set). From then on only Boss's own
    switch on Home (in Paper or Live) or his PIN clears a book - no separate PIN when he switches it on there in Live, and a book he
    switched on there in Paper goes live when the app goes Live. Switched on any other way afterwards (Jarvis by voice, the day
    plan, the change timeline, an unpark, a restore), it stays on paper until he switches it on himself on Home; every switch-off
    clears it. Every other gate still applies: the app in Live
    (which takes Boss's PIN), "AI trades go live", the switch itself (and each index's parking), every guard, the kill switch, the
    day stop and the day lock, and - only while their switches are on (off by Boss's choice since 19 Nov, below) - its own live stop
    rule and its self-review's park to paper and pause (a park or pause already in place is lifted in Live only with the PIN; his
    switch lifts the pause only in Paper). Off stays one
    tap. Every other arm is unchanged (armed in Live with its own PIN).
  - Jarvis's own trades still stay on paper (the rule above).
- R51, R72 AND LIQUIDITY RUN UNTIL BOSS STOPS THEM (Boss, 19 Nov: "remove the stop trades after the max loss for all r51,r72 and
  liquidity strategy, i want it to run until i stop them"; he chose all four, each a switch turned off by PIN; and "Yes, Jarvis cutoff
  should leave them alone too"). Five automatic stops no longer act on R51 Donchian 20, R72 Breadth Donchian and Liquidity 15+5 (every
  book, told apart by source, never by name): the forward-test switch-off (40 paper trades), Liquidity's live stop rule (−₹40k / −₹13k a
  month on its own live trades), its self-review's (and Autopilot's) park to paper and pause, the paper account stop, and Jarvis's
  losing-arm cutoff (neither asked nor done by itself).
  - Each is a Bot settings switch ("... applies to R51, R72 and Liquidity"), on in code; Boss's one-time choice switched all five off,
    logged in the Autopilot change timeline (only on a phone that ran them before 19 Nov; never by a restore). Off takes his PIN and
    never comes from Jarvis or voice; on is one tap (no PIN).
  - Nothing was switched back on by it: an arm a stop had already switched off, parked or paused, and a live or paper stop already
    reached, waits for Boss (his own switch, or his PIN as before).
  - Boss, 20 Nov ("Its off then why separate button" / "Yes, make it follow switch"): the arms' live-copies account stop (ArmLive: the
    same −₹40k from the best / −₹13k a month, over the arms' whole live record) follows the "Liquidity live stop rule" switch for R51
    and R72 (Liquidity's live trades never pass through it; their own live stop rule follows the switch as above): off, it no longer
    refuses their live entries, whenever it was reached; on, it refuses as before. Every other arm keeps it.
  - Every other arm keeps all five. The kill switch, the day lock, the guard and daily loss limit, Stop for today, every live gate
    (PIN, Live, "AI trades go live", each arm's Trade live switch), every exit, the whole-account stop rule's own switch, the funds
    check, the R151 picker and Boss's exit rule V4/V5 still apply to these three.
- PAPER (Boss, 13 Oct): an idea that has PASSED the research gates (below) may act on paper arms straight away, with no
  shadow phase.
  - Every change is logged in the Autopilot change timeline, undoable in one tap, and shown to Boss.
  - Ideas that failed the gates stay notes only.
    - One exception, chosen by Boss on 28 Oct ("add S5 trail to arms directly; if it does not perform I will turn it
      off"): R128's S5 Trail, R123's morning network pick (an ATM option, nearly always a put) with its cell's stop
      (−₹1,000 or −₹2,000 net) and a 60% profit lock after +₹1,000, runs as a PAPER arm although it failed the gates
      (555 walk-forward trades, worst month −₹18.6k in Sep 2024; 41 holdout trades, worst month −₹2,328 in Apr 2026.
      Corrected 17 Nov by R188, which rebuilt R128 trade by trade: the −₹18.6k month is in the walk-forward period, not
      the holdout).
      - It is labelled "failed gates, Boss's exception" wherever it is shown.
      - Boss can switch it off in one tap.
      - Live: like every arm since Boss's 30 Oct choice (below), only through the live gates.
    - A second exception, chosen by Boss on 29 Oct ("Build CISD (ICT's version) + network + index stop + lock. And add
      it as a arm, and do forward test too"): R133's S3-V2-E3 runs as a PAPER arm although it failed the gates.
      - The rule: CISD entries (ICT's reading) on the index, R123's N1 network as the expected-value filter, R131's index
        stop and a 60% lock after +₹1,000.
      - Its record: 413 design trades, 69% won, +₹261 a trade; only 35 trades in the holdout period.
      - Same terms as S5 Trail: labelled "failed gates, Boss's exception", one tap off; live only through the live gates (Boss, 30 Oct).
      - R134's forward test keeps judging it on new days.
    - A third exception, chosen by Boss on 1 Nov ("Update all arms which helps", after R144c's before / after backtests):
      R144c's skips act on paper although they failed the gates (found and judged on the same years).
      - FT3: no ticket on BANKNIFTY, FINNIFTY or MIDCPNIFTY (monthly expiries only), and none on a pick whose price and OI
        both fell over the 15 minutes before its mark. Night: no entry when India VIX is down more than 3.13% today.
      - Same terms: labelled "failed gates, Boss's exception", logged, one tap off; live stays in shadow (logged only, never
        skips a trade that would really go live).
      - The Hero ₹1,000 ticket was switched off by the same choice (a risk-lowering switch, one tap back on).
    - A fourth exception, chosen by Boss on 6 Nov ("I am the owner, i wNt you to skip the rule, and i want live", after being told this
      rule): R151's R72 and R51 trade pickers act on PAPER AND LIVE although both failed their gates.
      - The rule: a new R72 Breadth Donchian break that R151's R72 logistic picker scores under 0.4656, and a new R51 Donchian 20 break
        that R151's R51 picker scores under 0.4469, is skipped. It is kept as a would-be trade, so it never goes to Zerodha. A break
        it cannot score yet (history loading, a read failed) is taken as before.
      - Their record on Apr - Oct 2026 (a second look):
        - R72: 652 picks won 48.9% and made ₹2.19 lakh, vs R72's ₹1.71 lakh;
        - R51: 693 picks won 49.9% and made ₹1.59 lakh, vs R51's ₹1.35 lakh;
        - both had about half the drawdown or less. The bar was 50% wins.
      - It only ever skips entries (never adds or resizes). A skipped break still holds its side and counts in the arm's day caps,
        as the trade would have, so the picker can never free a place for a trade the arm would not have made. It may therefore
        skip a little more than the research did.
      - Every other live gate still holds: PIN, Live, AI trades go live, each arm's Trade live switch, the guard and the kill switch.
      - It is labelled "failed gates, Boss's exception", logged in the change timeline, and one tap off. Off adds live trades back,
        so the Undo takes the PIN.
      - It is the one exception to "anything new that could skip live trades starts in shadow", by Boss's explicit choice.
      - R150's W20 on R51 stays a label only (shadow).
    - A fifth exception, chosen by Boss on 15 Nov: Boss's exit rule acts on Liquidity 15+5 (every book and index), R51 and R72, on PAPER
      AND LIVE, although it failed the gates. Its fourth version (Boss, 16 Nov, after R186: "For r51/r72 use Step lock (+₹500 at +₹1,000,
      and so on) And for liquidity use Breakeven at +₹1,000") adds a lock on top of the third (V4: "Switch to + picker + breakeven at +10%
      (V4)"; "Paper and live"), under the same switch; the third replaced the morning's ₹500 step trail.
      - The rule: no rupee stop at the entry (each arm keeps its own stop, the -15%). Once the option has traded 10% above the fill, the
        stop moves to breakeven (the fill plus the round trip's charges). On top of that:
        - R51 and R72: once the best open P&L reaches +₹1,000 a lot net after charges, the stop rises to the premium whose stop sale nets
          +₹500 a lot; then it stays ₹500 a lot under each further ₹500-a-lot step of the best (+₹1,500 locks +₹1,000, +₹2,000 locks
          +₹1,500, ...). The higher of this lock and the breakeven applies.
        - Liquidity: once the best open P&L reaches +₹1,000 a lot net, the stop moves to breakeven (the premium whose stop sale nets ₹0),
          so breakeven at +10% or +₹1,000 a lot, whichever comes first. Nothing more.
        - The levels are R186's (r186engine.lock_levels): net after the round trip's charges on the trade's entry quantity, per lot, and
          the lock padded for the stop sale's slippage as R186 did (0.999 of the stop less two half-spread ticks; the app takes one tick).
        - The stop never moves down. No fixed target. R72 / R51 keep the R151 picker (the fourth exception). The arms' own exits all
          stay (ladder / L25, time stops, index exits, square-offs) and the first one wins. A live trade takes its own levels on its live
          fill and lots; its stop rests at Zerodha and is modified up (the app sells at the level itself when it cannot rest there), never
          two exits. A fast market can sell below a stop. Open trades keep the version they entered with; new trades take the new one.
        - Boss, 16 Nov: "All the stops and profit locks should be on zerodha". Every live stop and lock of these three arms rests at
          Zerodha; one the exchange or Zerodha's risk system ended is put back there (never beside a square-off's sale); one Boss
          cancelled themselves is respected (never put back by the app, which then watches the level itself).
      - Its record: R186 on one year (20 Oct 2025 - 19 Oct 2026, 1 lot): R72 with the step lock 1,097 trades, 64.4% wins, ₹2.13 lakh
        (worst month -₹13,690); R51 1,430 trades, 62.7%, ₹1.63 lakh (worst month -₹29,871); Liquidity with breakeven at +₹1,000 688
        trades, 44.5%, ₹98,239 (worst month -₹32,670). V4 alone on the same year: R72 56.6%, ₹3.25 lakh; R51 55.5%, ₹2.50 lakh;
        Liquidity 41.3%, ₹1.00 lakh. It failed the gates (less money than V4; twin BH q 1.00). V4 itself (R179) failed the gates too
        (twin q 1.00; R72's final win rate 53.5%); the morning's ₹500 step trail (R176) took all three arms from ₹6.37 lakh to
        -₹3.23 lakh.
      - It only lowers risk (a higher stop, an earlier exit), so it acts on live through every existing live gate.
      - It is labelled "failed gates, Boss's exception" wherever it is shown, logged in the Autopilot change timeline, and one tap off
        (Bot settings). Off restores the arms' own exits for new trades (open trades keep the rule they entered with); it adds live
        risk, so off and the Undo take the PIN. The fourth version has its own switch under it, logged as V4 -> V5: its off and its
        Undo go back to V4 alone (the +10% breakeven kept), with the PIN.
    - No other failed idea may use these exceptions.
  - Paper changes never carry over to live by themselves.
- No bypass of: PIN, lock, Live, AI-live, guard, kill switch, day lock, Google speech.
- LIVE GATES ADDED BY THE 19-20 NOV AUDIT (batch 2). Each one only refuses an entry or a loosening, never an exit:
  - Lifting Stop for today in Live takes the PIN. The quick tile and voice cannot lift it; the tile tells Boss to open the app.
  - A saved strategy's automatic live entries (scheduler runs) need Live and "AI trades go live". A run Boss started himself does not.
  - Switching off a live risk rule (account stop, exposure cap, Boss's risk rules) takes the PIN, even before the saved switch is
    read. Raising Liquidity's lots in Live takes the PIN.
  - Trade-manager exits of a paper trade with a real live copy are held back (shadow) unless that family's live mode is ACT (PIN).
  - Voice "cancel all" keeps protective SL / SL-M orders.
  - ntfy alerts: its Trade live starts off and is switched off while neither an access token nor Boss's own ntfy server is set
    (anyone who learns a public topic can post). The phones where it was on by the old default were switched off once, logged in the
    change timeline. Boss confirmed it on 20 Nov ("Keep ntfy off").
- PAPER ENDS AND LIVE COPIES (20 Nov audit). Every automatic paper end (square-off, loss limit, sweeper, expiry exit, an unreadable book)
  sells the live copy, as before. Only Boss's own paper reset or hand close leaves a live copy running, and only an NSE intraday (MIS)
  copy, which keeps its own Zerodha stop, its rule exits and the 15:15 square-off; Night (NRML) and MCX copies are sold with the paper.
  A paper-only refusal (another arm holds the option on paper, the paper guard, no paper price) still skips live: it is logged "would
  have gone live", and its Bot settings switch (off by default, on takes the PIN) would send the live copy.
- WHO CANCELLED A STOP (20 Nov audit, Boss's 16 Nov rule). An SL that comes back cancelled during market hours with no mark of the app's
  own cancel, and with blank or unrecognised words, is Boss's: never put back, the app sells at the level. Exchange / RMS / market
  protection words, or a cancel outside market hours, are Zerodha's: put back. A GTT "deleted" with no app mark is Boss's. The app's own
  cancels (arm exits and Boss's manual exit from the app) are marked before they go and saved in the book, so a restart never misreads them.
- ORDER COUNT AND KILL SWITCH (20 Nov audit, B5).
  - Today's order count at Zerodha that cannot be read (read twice) is unknown, never 0. The order count check starts in shadow: the
    live entry goes as before, logged "would have refused: order count unknown" and counted. Its Bot settings switch (off by default)
    refuses such entries; on needs no PIN, off takes the PIN. Exits and stops never look at the count.
  - Boss's own kill switch in the app (turning it on, or its "Cancel the bots' working entries" while it is on; once the switch reads
    on) also cancels the bots' working entry orders at Zerodha (told apart by tag: ORB, Pine, strategies, the arms' live copies).
    It never cancels an exit, a stop, Boss's own entries, a BUY on an underlying with a short held (it may be a hedge) or any BUY
    when positions cannot be read, and nothing when the order book cannot be read. Turned on by the guard, Jarvis, voice or
    Autopilot, it only logs which entries it would cancel.
- Nothing on the order or tick path touches disk, SecurePrefs or the network, or blocks (OrderPathRound2Test stays 0).
  No main-thread I/O.
- OPEN POSITIONS AND ORDERS COME FIRST (Boss, 14 Oct: "whenever an order is placed it must be pulled on priority, so we
  never miss a stop-loss or target"). While any position or order is open (paper or live, NSE or MCX):
  - its prices stay on the live stream at full speed, and its stop, target, trail, lock, square-off and guard checks run
    at full cadence; no battery, memory, saver or rest logic may slow, pause or skip them;
  - when memory is short, everything else is released first (recorders, caches, the language model, charts), never the
    position watch;
  - after any restart or low-memory kill, the open positions and their stops are restored and watched FIRST, before any
    other start-up work;
  - live stops and targets also rest at the broker where possible (SL/GTT), so a dead phone cannot miss them.
    Boss, 16 Nov ("All the stops and profit locks should be on zerodha"): a live stop of Liquidity 15+5 or any ORB-family arm
    that is missing at Zerodha (not placed, or cancelled or refused by the exchange or Zerodha) is always put back there on the
    next check (no setting); never beside a square-off's or the arm's own working sale, at the 15:05 expiry square-off, from
    15:10 or with Stop for today. A stop Boss cancelled in Kite is never put back: the app watches the level and sells there.
  Every change that touches background work must test this.
- Nothing about the account is spoken on a locked phone.
- Market times: NSE F&O closes 15:40, index/cash 15:30, expiry square-off 15:05.
- Liquidity BANKNIFTY was frozen until 26 Oct 2026, but Boss UNFROZE it on 13 Oct on purpose ("paper costs nothing; for
  testing and enhancing"). Risk-lowering paper changes may apply to it. Every change must stay logged and labelled, so its
  plain-rules record can still be separated from the record after changes for the 26 Oct live decision. Boss is on paper
  only 13–26 Oct. Account stop rule:
  −₹40k drawdown or −₹13k a month.
- SIGNAL ON THE UNDERLYING, TRADE THE OPTION (Boss, 14 Oct: "all the indicators and strategies should be on the
  regular funds/index, and on the basis of that we buy options; make this default"). By default every indicator,
  pattern and strategy, in research, paper, Pine, Strategy Lab, Jarvis and the arms, computes its entry and exit
  signals on the underlying (the index spot or index futures; for stocks, funds or MCX, the stock, fund or future
  itself) and then buys the option (or trades the future) chosen by the strike rule.
  - Option-chain data (OI, PCR, IV, skew, GEX, max pain) may be used as context or filters around an underlying signal.
  - Stops, targets, trails and locks may be set on the option premium.
  - A rule whose signal is read from an option's own price chart is NOT the default: it must be labelled as such,
    reported separately, and switched on only by Boss.
  - Research write-ups state the signal source of every rule.
- Crypto (Boss, 13 Oct): every crypto trade, in research, paper or any future live use, ALWAYS has a stop-loss and a
  profit lock (a trailing or step lock that protects open profit). No crypto strategy is tested, proposed or run without
  both; studies compare lock and stop variants, never "no stop". Nothing in the app trades crypto yet.
  Boss trades crypto like the Indian market (13 Oct): enter, take profit or stop out, exit. No long-term holding; buy-and-hold
  and DCA appear only as baselines to beat. Crypto options (BTC/ETH, e.g. Delta Exchange India INR-settled daily expiries)
  are in scope for research.

## Repository hygiene

- Never print or commit tokens or secrets (Dhan, Zerodha, GitHub). Before each commit,
  `git grep -nE "eyJ0eXAi|1114010050" HEAD` must print nothing.
- No raw data in git (large parquet or CSV go to GitHub Releases). No raw personal P&L outside the app's encryption.
- Don't touch `android/**/coverage-actions.txt`. No model identifiers in commits.
- Research must be honest:
  - Pre-register the idea; use walk-forward testing; read the locked holdout once.
  - Use real charges and slippage; apply Benjamini–Hochberg across the ideas tried.
  - Log every idea in `research/experiments/entries/`, failures included.
  - Give a sample size with every number.
- Data repository: the phone's uploads (recordings, backups) and their catalogue go to the private repo
  `mevishalsonawane-ai/IraAlgo`, never to options-lab (Boss, 13 Oct). The phone's GitHub token is scoped to IraAlgo
  only, so a leaked token can never change the app's code or APK.
- Plans and backlog: `TODO.md`. Data Brain design: `research/data/ARCHITECTURE.md`. Owner's guide: `MAINTENANCE.md`.
