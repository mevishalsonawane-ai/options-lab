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
   - Review your own full diff (`git diff origin/<base>...HEAD`) adversarially, line by line.
   - For larger changes, run the `code-review` skill or a separate reviewer agent. Fix every confirmed finding, then re-test.
   - Check: correctness, threading (no main-thread I/O), the order and tick path, the safety rules below, leaks of secrets
     or personal data, and that the change does only what was asked.
4. **After pushing**, watch CI on your commit (`gh api repos/mevishalsonawane-ai/options-lab/commits/<sha>/check-runs`)
   and fix every failure until everything is green, including the app tests and Build APK.

## Safety rules (never broken, whoever asks)

- Real orders only with Boss's PIN and "AI trades go live" on. Jarvis's own trades stay on paper.
- Automatic changes may only LOWER risk (skip, smaller size, earlier exit). Anything new that could skip or resize trades
  starts in shadow (logged only), behind a switch that is off by default.
- No bypass of: PIN, lock, Live, AI-live, guard, kill switch, day lock, Google speech.
- Nothing on the order or tick path touches disk, SecurePrefs or the network, or blocks (OrderPathRound2Test stays 0).
  No main-thread I/O.
- Nothing about the account is spoken on a locked phone.
- Market times: NSE F&O closes 15:40, index/cash 15:30, expiry square-off 15:05.
- Liquidity BANKNIFTY's behaviour is frozen until 26 Oct 2026. Boss is on paper only 13–26 Oct. Account stop rule:
  −₹40k drawdown or −₹13k a month.

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
- Plans and backlog: `TODO.md`. Data Brain design: `research/data/ARCHITECTURE.md`. Owner's guide: `MAINTENANCE.md`.
