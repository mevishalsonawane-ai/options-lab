# App tests (`android/app/src/test`)

JVM tests for the app module: no device or emulator needed. Three kinds, all run by
`./gradlew :app:testDebugUnitTest`:

| Kind | Runner | Use for | Examples |
|---|---|---|---|
| Pure JVM | plain JUnit 4 | logic that never calls Android (formatting, price maths, settings defaults) | `ui/FormatTest`, `data/ProtectionsLogicTest`, `AppLogicTest` |
| Robolectric | `@RunWith(AndroidJUnit4::class)` / extend `testing.RobolectricTest` | anything needing a Context, files, `org.json`, `Base64`, the vault | `security/VaultTest`, `security/PinLockTest`, `data/BackupTest`, `data/SettingsTest` |
| Compose UI | Robolectric + `createComposeRule()` | rendering a screen or card with fake state and driving it | `ui/screens/LockScreenTest`, `ui/screens/OrderReviewTest`, `ui/screens/RefusedScreenTest` |

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

### Production seams (keep this list short)

| Seam | Why it is safe |
|---|---|
| `ui/screens/BrokerScreens.kt`: `PlanCard` changed from `private` to `internal` | Only its visibility changed, and only within the module. Tests can render the order-review card with a hand-made `OrderPlan` instead of an `AppModel` (which would reach Zerodha and Upstox). |

## Rules

- **No network.** Never build an `AppModel` in a test, and never call `Broker`, `Market`
  fetches, `KiteStream`, `StaticIp.status`, `Relay` or `Holidays` refresh. Pass state
  directly to the composable. If a screen only accepts `AppModel`, split out a composable
  that takes plain values plus callbacks (as `PlanCard` does), and add it to the seam table.
- **No real credentials, and nothing logged.** Use obviously fake values such as
  `"test-key-not-real"` and PINs like `246813`. Do not print API data or secrets.
- A test must not depend on another test's order. Each test starts on a fresh "phone".

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
- JaCoCo coverage (turned on with `enableUnitTestCoverage` in the debug build type):
  `app/build/reports/coverage/test/debug/` (`index.html`, `report.xml`)
- On CI, the "Coverage summary" step prints the totals and per-package line and branch
  coverage (`android/tools/coverage_summary.py`) to the job log and the run summary. The
  `app-test-reports` artifact holds the HTML, XML and JUnit results.
