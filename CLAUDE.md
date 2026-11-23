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
    - The cheaper strike (Boss, 20 Nov: "if its says insufficient balance for any trade, then try cheaper options increment it 1 by 1";
      live now with the switch on, at most 3 strikes further out, lots first then strikes, 100-point steps; R51, R72 and Liquidity
      15+5): an R51 Donchian 20, R72 Breadth Donchian or Liquidity 15+5 (every book, told apart by source, never by name) live option
      buy that, after the lots cut, the free cash the entry already read cannot pay for even one lot, or that Zerodha definitely
      refused for funds or margin (its own refusal or REJECTED in its book; never a lost or unknown reply, nor Kite's unsure
      DataException / GeneralException; after a refusal the free cash is read fresh, unknown means no step), steps 100 points further
      out at a time (an index whose strike gap is wider uses its gap): NIFTY 25000 PE -> 24900 -> 24800 -> 24700, 25000 CE -> 25100
      -> 25200 -> 25300, the listed strike at or beyond each mark (same expiry, side and exchange, SENSEX on BFO), at most 3 steps, a
      strike not listed or with no live quote skipped and counted, and buys 1 lot (never more than Boss's lots setting) of the first
      that fits. Each try runs the arm's whole live entry path again (PIN, Live, "AI trades go live", the arm's Trade live, the guard,
      the exposure cap, Liquidity's order flow, the order door and fingerprint, Kite.refusals, kill switch, day lock, Stop for today, the
      square-off time), only within 60 s of the entry's request (R51 / R72: the paper fill; Liquidity: its entry or Boss's approval),
      once per signal. None fits: the entry goes on as before (logged). Liquidity in approve mode (Boss, 20 Nov): the approved strike
      may be swapped for one up to 300 points cheaper without asking again; the approval notice says so and the alert after the fill
      names the strike bought. R51 / R72's paper trade keeps its own strike; only the live copy holds the cheaper option (its book row
      keeps the paper contract and the strike it stepped from). Liquidity has no paper twin while live: its live trade is on the
      cheaper contract, labelled with the strike its signal chose. Each stepped buy is said to Boss and logged, each give-up logged.
      Its Bot settings switch ("Cheaper strike when funds are short (Boss's exception, 20 Nov)") is on by default: off is one tap (it
      lowers risk), on takes the PIN; a backup carries only its "off", never an "on"; an erase keeps an "off" until the app restarts
      (then the erased phone takes the default, on); every flip is in the Autopilot change timeline with an Undo (an Undo that turns it
      on takes the PIN). It is the one exception to "anything new that could add live trades starts in shadow", by Boss's choice on
      20 Nov.
  - The funds check (Boss, 15 Nov: "Yes" to building it in shadow): an arm's live option buy, index or stock options (the arms' live copies,
    ORB and Liquidity 15+5), that the free cash cannot pay for even one lot. It reads only the free cash the entry already read; unknown, it
    says nothing. In shadow by default (logged and counted, the order goes as before); its Bot settings switch (off by default)
    makes it skip the entry. On needs no PIN (it lowers risk); off takes the PIN.
  - The fresh-bar check (20 Nov audit): Liquidity 15+5's live entry (automatic or approval) on a late or stale bar - seen past its fill window
    (its close + 2 min), its own last minute missing, or the index's newest minute behind. In shadow by default (logged "would have skipped
    (stale bar)" and counted, the entry goes as before); paper is never skipped, only logged. Its Bot settings switch (off by default) skips
    such a live entry: the signal waits (in memory) and is decided again if fresh candles land within its fill window, never bought late
    (after a restart the bar stays spent). On needs no PIN (it lowers risk); off takes the PIN.
  - The list wait (20 Nov audit, a sibling switch on the same card): a Liquidity signal decided while today's instrument list is not
    loaded. Off by default, as before: lost as "no_contract", logged "would have waited for the list and entered" and counted. On, the
    signal waits (in memory) and is decided again within its fill window once the list lands, never bought late. On adds live entries,
    so it takes the PIN; off needs none.
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
  - Lifting Stop for today in Live takes the PIN, on the index side and the commodity (MCX) side alike. The quick tile and voice cannot
    lift it; the tile tells Boss to open the app. The change timeline's Undo that starts either side again asks the PIN whenever one is
    set (in Paper too); with no PIN set it has no PIN step.
  - A saved strategy's automatic live entries (scheduler runs) need Live and "AI trades go live". A run Boss started himself does not.
  - Switching off a live risk rule (account stop, exposure cap, Boss's risk rules) takes the PIN, even before the saved switch is
    read; so does raising the same-direction cap while its live switch is on, or the portfolio cap while it is on. Raising Liquidity's
    lots in Live takes the PIN.
  - Trade-manager exits of a paper trade with a real live copy are held back (shadow) unless that family's live mode is ACT (PIN).
  - Voice "cancel all" and "cancel the last order" keep protective SL / SL-M orders.
  - ntfy alerts: its Trade live starts off and is switched off while neither an access token nor Boss's own ntfy server is set
    (anyone who learns a public topic can post). The phones where it was on by the old default were switched off once, logged in the
    change timeline. Boss confirmed it on 20 Nov ("Keep ntfy off").
- PAPER ENDS AND LIVE COPIES (20 Nov audit). Every automatic paper end (square-off, loss limit, sweeper, expiry exit, an unreadable book)
  sells the live copy, as before. Only Boss's own paper reset or hand close (in Trade, or Jarvis closing one paper position Boss names, by
  name, number or nickname) leaves a live copy running, and only an NSE intraday (MIS)
  copy, which keeps its own Zerodha stop, its rule exits and the 15:15 square-off; Night (NRML) and MCX copies are sold with the paper.
  A paper-only refusal (another arm holds the option on paper, the paper guard, no paper price) still skips live: it is logged "would
  have gone live", and its Bot settings switch (off by default, on takes the PIN) would send the live copy.
- WHO CANCELLED A STOP (20 Nov audit, Boss's 16 Nov rule). An SL that comes back cancelled during market hours with no mark of the app's
  own cancel, and with blank or unrecognised words, is Boss's: never put back, the app sells at the level. Exchange / RMS / market
  protection words, or a cancel outside market hours, are Zerodha's: put back. A GTT "deleted" with no app mark is Boss's. The app's own
  cancels (arm exits and Boss's manual exit from the app) are marked before they go and queued to be saved in the book before the cancel
  goes, so a restart does not misread them (one written just after, while the book is busy, can be misread only in that moment).
  (22 Nov) The protections' marks (a square-off's, Boss's exit from the app, an app-watched target's sale) are saved the same way. Any
  mark whose cancel never took (refused, or its stop still seen working well after the cancel) ends, so Boss's later cancel of it is his.
  A protection's stop that its target's sale finds already cancelled (Zerodha refuses the app's cancel) is not the app's: its mark ends,
  nothing is sold, and the next look reads that cancel by the rule above.
  (23 Nov) Boss's manual exit from the app holds the contract while it sells. It is refused or cut - always with the reason, about how
  long, and "close it in Kite" - only when another sale on it may still be filling (a lost or unshown sale, a triggered GTT's sale not
  shown yet, another seller holding the row), a read of Zerodha failed, a book cannot tell yet (up to 2 minutes after the app starts),
  working exits on it cannot be taken over, or a stop is going back on it. When the size is known, the safe part (whole lots) goes under
  his confirmation. After a cut or refused leg the later legs go on only where that is safe (a short's buy-back always; a hedge's sale
  only while no short may stay), and each contract is said ("Sent X of N").
- STOP AND TARGET ON BOSS'S OWN BUYS (Boss, 7 Nov; 23 Nov 2026: "check live orders placed manually in kite immediately, and set a target
  of 10 points and stoploss of 10points, a button, where i could stop/start the functionality"). One switch, on by default, on Bot
  settings and on Home's "Stop & target on my Kite buys" card: off is one tap, on takes the PIN. In Live, a live NSE / BSE option Boss
  bought himself (never shorts, futures, stock or MCX) that nothing in the app follows gets a stop 10 premium points under its average
  and a target sell 10 over it (bought at 120: 110 / 130), in place of 7 Nov's 5% / 10%. Boss edits the points on that card (saved on
  this phone only; in Live a wider stop takes the PIN); an option too cheap for the points (stop under 2 ticks) gets a 5% stop, said;
  protections already placed keep their levels. Boss's own order is any whose tag is not the app's (none, the app's order screen's, another
  app's). A buy of his placed outside the app is covered about 3 s after Zerodha reports it COMPLETE (or the rest cancelled after a part
  fill; a part fill still working waits): the order update only queues the look, which re-reads orders and GTTs in the exit turn (a stop
  he set at once wins) and needs a fresh positions read showing at least that quantity; lots he adds beside the app's own protection are
  covered too (never more than held, checked again just before they rest); anything else after two looks 20 s apart, as before. Every
  leave-alone rule stays. Intraday only the stop rests at Zerodha (the target is watched by the app). Boss's own orders come first: a
  stop or target order of the app's he moved in Kite is kept at his level, never moved back (a GTT of the app's he moves is not
  followed). Per side (his stops fire in a fall, his targets in a rise): his own stop or GTT beside the app's (an order only once Zerodha
  has taken it: open, trigger pending or being modified; never an order of the app's own GTT) brings the app's stop (or its GTT) down to
  what his stops do not cover (raised again if his cover drops), his own target replaces the app's target, and the app's protection comes
  out - marked as its own cancel first, checked again on a fresh read, confirmed out - only when his stops cover all that is held; his
  order refused meanwhile, the app's stop stays (or goes back); told once. A sale of his on the stream asks for that look at once, and one
  that closed the position (or his target beside it seen filled) takes the app's stop out at once (two agreeing reads). His sell on the
  spread's other leg takes the wing's protection off at once (not while a buy of his on that leg could explain it). He is told how to exit
  while the app's SL rests (modify it in Kite, to MARKET or his level, or cancel it), and again when Zerodha refuses his own sale - never
  two exits.
- ORDER COUNT AND KILL SWITCH (20 Nov audit, B5).
  - Today's order count at Zerodha that cannot be read (read twice) is unknown, never 0. The order count check starts in shadow: the
    live entry goes as before, logged "would have refused: order count unknown" and counted. Its Bot settings switch (off by default)
    refuses such entries; on needs no PIN, off takes the PIN. Exits and stops never look at the count.
  - Boss's own kill switch in the app (turning it on, or its "Cancel the bots' working entries" while it is on; once the switch reads
    on) also cancels the bots' working entry orders at Zerodha (told apart by tag: ORB, Pine, strategies, the arms' live copies).
    It never cancels an exit, a stop, Boss's own entries, a BUY on an underlying with a short held (it may be a hedge) or any BUY
    when positions cannot be read, and nothing when the order book cannot be read. Turned on by the guard, Jarvis, voice or
    Autopilot, it only logs which entries it would cancel.
- R51 / R72 STALE HISTORY (20 Nov audit B5). The earlier sessions a break's 20-bar channel reads must reach the last trading day by
  the calendar, whole in its last 20 five-minute bars. A missing, empty or stale history is read again in the background: with its switch
  (below) on, after 1, 2, then every 5 minutes, and every 15 after about an hour; off, as before, every 10 minutes while one is missing
  and once an hour while one is only empty or stale (for the log); a worse read never replaces a better one, and a history already in use is
  replaced only with the switch on (off, the first one kept stays for the day and is pinned on the phone, as before, and a better
  read is only logged). Its Bot settings switch "Hold R51 / R72 breaks on a stale history" is off by default (shadow: taken as
  before, logged "would have skipped (stale history)"); on, such a break waits in its fill window and is let go when the window
  ends, paper and live; a break let go that the arm would have taken holds its side (up to its 30-minute time stop) and counts in
  the day's caps, as the trade would have, so it never frees a place. On and off both take the PIN (on also lets a better history
  replace the one in use, which can change live breaks). A picker score made from part of its inputs is never reused as whole; the
  first score that decides a break (skip or take) fixes that decision for the day (kept in the book across a restart). R51 / R72
  locks may also rise from the checked prices now while the option's minutes stop coming (the same levels). A live R51 / R72 copy's
  best price counts a stream price only once checked (a trade after the entry minute, not far outside a sane book), else the sane
  book's bid (not streamed: Zerodha's quote as the live stop judges it, its last price unless far outside a sane book), and candle
  highs only from closed minutes.
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
    One exception (20 Nov audit): while a lost exit sale of an ORB-family or Liquidity 15+5 trade on a contract is unresolved (not yet
    seen in Zerodha's order book) or found but not yet booked, every other trade of those arms on that contract WITH A STOP RESTING keeps
    it resting and its exit (15:10 and Stop for today included) waits; a trade with no stop resting still exits, counting the lost sale
    as already going (sold less by it); the price check puts no missing stop back on that contract for any trade; and (22 Nov) the 15:05
    expiry square-off and the flat-by take no stop, target or GTT out on that contract and sell only what is held less that sale
    (nothing while anything rests there) - until the lost sale resolves: found and booked,
    or let go only on a read of the order book that started at least 2 minutes after the sale was sent and still does not show it
    (searched from 3 minutes before its send time; failed reads never let it go; Boss is told its state is unknown, and again at 15:10). The lost sale is
    kept in the book, so a restart keeps the wait and the window. Then the exits run as usual.
    (22 Nov) The arms' live copies hold their own lost exit sale the same way: no second sale and no stop on that contract until it is
    found and booked or let go on such a read; kept in their book; and the square-offs count it too.
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
