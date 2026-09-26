# App tests (`android/app/src/test`)

JVM tests for the app module: no device or emulator needed. Three kinds, all run by
`./gradlew :app:testDebugUnitTest`:

| Kind | Runner | Use for | Examples |
|---|---|---|---|
| Pure JVM | plain JUnit 4 | logic that never calls Android (formatting, price maths, settings defaults) | `ui/FormatTest`, `data/ProtectionsLogicTest`, `AppLogicTest` |
| Robolectric | `@RunWith(AndroidJUnit4::class)` / extend `testing.RobolectricTest` | anything needing a Context, files, `org.json`, `Base64`, the vault | `security/VaultTest`, `security/PinLockTest`, `data/BackupTest`, `data/SettingsTest` |
| Compose UI (functional) | Robolectric + `createComposeRule()` | rendering a screen or card with fake state and driving every control | `ui/screens/LockScreenTest`, `ui/screens/OrderReviewTest`, `ui/screens/RefusedScreenTest` |
| Screen matrix (visual) | `ParameterizedRobolectricTestRunner` + `testing.ScreenTest` | screenshot + layout lint + click smoke on 24 device set-ups | `ui/screens/ScreensLayoutTest` |
| Live money paths | Robolectric + `testing.FakeKite` | the real `Broker` HTTP code and everything above it against a fake Zerodha | `data/BrokerLiveTest`, `data/ProtectionsLiveTest`, `ui/OrderFlowLiveTest` |

With `isReturnDefaultValues = true`, an Android call in a *pure JVM* test returns 0/null/false
rather than throwing. If a test depends on Android behaving, make it a Robolectric test.

## Test harness (`testing/`)

- **`robolectric.properties`** (in `src/test/resources`) sets `sdk=34` (Robolectric needs JDK 21
  to run SDK 35, and CI runs JDK 17) and `application=TestApp`.
- **`TestApp`** calls every store's `init(context)`, just as `IraAlgoApp.onCreate` does. It
  skips the crash handler, notification channels, `Jobs` (alarms and WorkManager), the
  lifecycle observer and the PineScripts thread. It also clears each store's in-memory cache.
  Robolectric creates a new Application for every test, but the app's `object` singletons
  stay alive for the whole run, so without that step one test's data would leak into the next.
- **`FakeAndroidKeyStore`** registers a JCA provider named `"AndroidKeyStore"` that keeps
  software AES and HMAC keys in memory. `Vault`, `SecurePrefs`, `SecretBox`/`PinPepper` and
  `PinLock` run **unchanged**. They make the same `KeyStore`/`KeyGenerator` calls with the same
  aliases.
  - `FakeAndroidKeyStore.failing += "<alias>"` simulates a Keystore fault.
  - `Vault.destroy()` / `PinPepper.destroy()` simulate a lost key.
  - The provider lives only in `src/test`, so it cannot reach an APK, and the app has no
    runtime flag or setter that could turn it on. That is why no production seam was needed
    for the Keystore.

- **`NetworkGuard`** (installed by `TestApp`) is the JVM's default `ProxySelector`. Any
  connection to a host other than localhost is sent to a closed port, so it fails at once and
  the host is recorded in `NetworkGuard.blocked`. Tests assert that list stays empty. Maven
  Central is let through only because Robolectric downloads its own jars from it.
- **`FakeKite`** is a Kite Connect v3 server on localhost built on okhttp `MockWebServer`
  (HTTPS with a throwaway certificate). It keeps an order book, positions (updated by fills),
  funds, quotes, the NFO instrument dump, GTTs and basket margins. Replies use Kite's JSON
  shapes, and every request is recorded (`requests`, `placed`, `writes`), so a test can assert
  the exact method, path and form fields.
  - Scenario controls:
    - `nextPlace(...)`: `REJECT` (RMS margin rejection), `PARTIAL`, `OPEN` (stays open),
      `FILL_AFTER_POLLS`, `INPUT_EXCEPTION` (for example freeze quantity), `NETWORK_EXCEPTION`,
      and two lost replies: `DROP_AFTER_ACCEPT` and `ERROR_500_AFTER_ACCEPT`.
    - `throttle` (429), `sessionExpired` (TokenException), `down` (NetworkException on one path).
    - `fill(id)` triggers a resting stop.
  - A limit order away from the quote rests, as it would at the exchange.
  - `login()` stores a made-up session and switches to Live.
  - Close it in `@After`. Tests using it run with `@ConscryptMode(OFF)`, so the JDK's TLS talks
    to the fake.
- **`ScreenTest` / `DeviceConfig` / `LayoutLint`**: see "Screen tests" below.

### Production seams (keep this list short)

| Seam | Why it is safe |
|---|---|
| `ui/screens/BrokerScreens.kt`: `PlanCard` changed from `private` to `internal` | Only its visibility changed, and only within the module. Tests can render the order-review card with a hand-made `OrderPlan` instead of an `AppModel` (which would reach Zerodha and Upstox). |
| `data/Broker.kt`: `internal var testEndpoint` (base URL plus TLS trust for `call`) | Its setter throws unless `BuildConfig.DEBUG`. No app code sets it; only `FakeKite` does. When it is null (always, in the app), `call` goes to `Kite.API` with the pinned certificate factory and the relay, exactly as before. Everything else in `call` (headers, auth, 429 retries, error mapping) runs unchanged against the fake. |

## Rules

- **No network.** Never build an `AppModel` in a test, and never call `Broker`, `Market`
  fetches, `KiteStream`, `StaticIp.status`, `Relay` or `Holidays` refresh. Pass state
  directly to the composable. If a screen only accepts `AppModel`, split out a composable
  that takes plain values plus callbacks (as `PlanCard` does), and add it to the seam table.
- **No real credentials, and nothing logged.** Use obviously fake values such as
  `"test-key-not-real"` and PINs like `246813`. Do not print API data or secrets.
- A test must not depend on another test's order. Each test starts on a fresh "phone".

## Screen tests: layout, screenshots, device matrix

Every screen or dialog that gets a functional test also gets a state in a parameterized screen
test. `ScreensLayoutTest` is the example:

```kotlin
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs() = DeviceConfig.matrix()
    }

    @Test fun empty() = checkScreen("my-screen-empty") { MyScreen(state = fakeEmpty, onX = {}) }

    @Test fun loaded() {
        checkScreen("my-screen-loaded") { MyScreen(state = fakeLoaded, onX = {}) }
        smokeEveryAction()          // every clickable once, through its semantics action: nothing may crash
    }
}
```

- **`DeviceConfig.matrix()`** has 24 set-ups: small phone (w360dp-h640dp), typical phone
  (w411dp-h891dp), landscape phone (w891dp-h411dp) and tablet (w800dp-h1280dp), each at font
  scale 1.0, 1.3 and 2.0, in light and dark. Qualifiers are set with
  `RuntimeEnvironment.setQualifiers` and `setFontScale` *before* the activity starts.
- **`checkScreen(name) { content }`**:
  1. Renders the content in `IraAlgoTheme` for the set-up.
  2. Saves a Roborazzi screenshot to `app/build/outputs/roborazzi/<name>_<device>.png`.
  3. Runs `LayoutLint` over every root, which includes dialogs.

  Errors fail the test. Warnings, such as small secondary touch targets or a missing Role, are
  printed to the test's stdout as `LAYOUT <name> <device>: ...`. For a state reached by
  interaction, call `show { }`, then drive it, then `capture(name)` and `lint(name)`.
- **`LayoutLint`** checks (details in its KDoc):
  - **OVERLAP**: sibling text or clickable nodes overlap by more than 1 dp.
  - **OFFSCREEN**: a node extends past the window with nothing to scroll it into view.
  - **CLIPPED**: a node is cut by its parent.
  - **TEXT**: text overflows (is cut off) or is ellipsized.
  - **TOUCH**: a clickable is under 48×48 dp. This is an error for primary actions (send, place,
    close, cancel, …) and a warning otherwise.
  - **EMPTY**: a clickable has zero size or is fully clipped away.
  - **A11Y**: a clickable has no text, description or action label (error), or no Role (warning).
  - **ALIGN**: nodes tagged `row:<id>:label` / `row:<id>:value` have centres more than 2 dp apart.

  To exempt intended overlays, use a test tag starting with `overlay`, or list their text in
  `LayoutLint.Options(overlays = ...)`. To allow deliberate ellipsis, use `ellipsisOk`.
- **A real layout bug** in production UI is not fixed in the test. Add it to `knownBugs`
  (device name, size name or `"*"` → description). The test then *skips* with
  `LAYOUT BUG (...)` and the findings, and CI lists the skipped bugs. Remove the entry once the
  screen is fixed.
- Screenshots are recorded on every run, never compared, and never committed (`build/` is
  ignored). CI uploads them as the `app-screenshots` artifact and prints their names.

## Functional coverage every screen test must have

Use fakes and recording doubles at the boundaries. Pass plain state and callbacks to the
composable. For live paths, use `FakeKite`. Never touch the real network.

- **Navigation**: every tab, More sub-page and Lab page is reached, and back or
  `BackHandler` returns to the right place. The same applies to deep links and intent extras.
- **Controls**: every button, switch, chip, dropdown, slider, text field and swipe or hold
  control is operated (`performClick`, `performTextInput`, `performTouchInput { swipe / longClick }`,
  or `performSemanticsAction(OnClick)`). Assert the resulting UI change or the recorded callback
  or model call.
- **Forms**:
  - Validation messages appear for empty, invalid and boundary input (limit price empty or 0,
    lots over the cap, PIN wrong or locked).
  - The submit control is enabled or disabled correctly.
  - A double tap sends one order, not two.
- **Dialogs**: each can be opened, confirmed, and cancelled or dismissed. Sensitive dialogs
  follow `Capture.policy`.
- **Order flows**:
  - Paper: pick → review → send → the position appears → close it.
  - Live, with `FakeKite`: review must send nothing (`kite.writes` is empty). After the
    hold-to-send and PIN gate, exactly the reviewed order is sent (`kite.placed` form fields).
- **State survival**: drafts survive a tab switch; the screen survives recreation
  (`scenario.recreate()`) and `rememberSaveable` restore.
- **Empty, loading and error states**: no data, network failure (`kite.down`,
  `NetworkGuard` blocks), logged out (`kite.sessionExpired`), market closed.
- **Accessibility**: every actionable node has text or a description and a Role, and swipe
  and hold controls expose `onClick` semantics. `LayoutLint` A11Y reports these, and
  `smokeEveryAction` drives them.

A functional bug is not fixed in the test. Mark the test
`@Ignore("UI BUG: <screen>: <steps>; expected ...; actual ...")` and report it.

## Adding a screen test

```kotlin
@RunWith(AndroidJUnit4::class)
class MyCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheThing() {
        var clicked = 0
        compose.setContent { IraAlgoTheme("light") { MyCard(state = fakeState, onGo = { clicked++ }) } }
        compose.onNodeWithText("Go").performClick()
        assertEquals(1, clicked)
    }
}
```

- Call `setContent` only **once** per test. To change the input, hold it in `mutableStateOf`.
- Work that runs on `Dispatchers.Default` (for example PIN checks) finishes on a real thread.
  Use `compose.waitUntil { compose.onAllNodesWithText(x).fetchSemanticsNodes().isNotEmpty() }`.
- For hold-to-confirm or other gesture controls, use
  `performSemanticsAction(SemanticsActions.OnClick)` and `assertIsEnabled()`/`assertIsNotEnabled()`.
- `delay(...)` inside a `LaunchedEffect` does not advance on its own (the Compose clock only
  moves by frames). Do not wait for a banner to go away. Check what stays on screen.
- To make time pass for `SystemClock` (for example PIN lockouts), use
  `ShadowSystemClock.advanceBy(Duration)`.

## Running

- **CI:** the `app-tests` job in `.github/workflows/android.yml` runs
  `./gradlew :app:testDebugUnitTest :app:createDebugUnitTestCoverageReport` in parallel with
  the `apk` job, after `engine`. Failures print their full stack traces in the job log.
- **Locally:** you need an Android SDK (`ANDROID_HOME` or `local.properties`). Without one,
  `:app` is not included in the build. Run one class with:
  `./gradlew :app:testDebugUnitTest --tests '*BackupTest'`.
  Robolectric downloads its `android-all` jar from Maven Central the first time it runs.

## Reports and coverage

- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Screenshots: `app/build/outputs/roborazzi/*.png`. On CI they are uploaded as the
  `app-screenshots` artifact, and the "Screenshots captured" step lists them along with any
  skipped `LAYOUT BUG`s.
- JaCoCo coverage (turned on with `enableUnitTestCoverage` in the debug build type):
  `app/build/reports/coverage/test/debug/` (`index.html`, `report.xml`)
- On CI, the "Coverage summary" step prints the totals and per-package line and branch
  coverage (`android/tools/coverage_summary.py`) to the job log and the run summary. The
  `app-test-reports` artifact holds the HTML, XML and JUnit results.
