# IraThinker — Android app source

Snapshot of the IraThinker app source at **v59** (855/855 JVM harness checks),
pushed 2026-10-26 at the owner's request.

IraThinker is a **support / alert-provider app only** — market board, analysis,
Adaptive Brain (Live Ledger, Time Machine, self-grading), and ntfy alerts: human
alerts to the owner's topics plus a machine-readable JSON System Feed that the
owner's separate order application (IraAlgo) consumes. It never places orders.

## Layout

- `app/app/src/main/java/com/irathinker/app/` — all application sources
  (53 classes: engines, alert paths, System Feed, mom60 ×3 indices, strangle
  paper track, Ledger, chain probes, UI activities).
- `app/app/src/main/AndroidManifest.xml`, `app/app/src/main/res/` — manifest
  and text resources.
- `app/*.kts`, `app/gradle.properties` — the original Gradle project skeleton.
  The shipping build does **not** use Gradle (the daemon is unreliable in the
  build sandbox); it uses the manual pipeline below.
- `build_apk.sh` — the manual build pipeline (aapt2 → javac → d8 → zipalign →
  apksigner). Usage: `bash build_apk.sh v<N>` from the app workspace root;
  needs JDK 17 and an Android SDK (platform android-36, build-tools 34.0.0).
- `tests/` — the JVM harness mains (`TestMain.java` is the ship gate; the
  others are auxiliary checkers). The harness runs against the SDK's
  android.jar plus a local `json.jar` (see Exclusions).

## Exclusions (deliberate)

This snapshot travels through a text-only channel and a repo, so the following
stay in the owner's local workspace only:

- **Built APKs and `.idsig` files** (v1–v59 binaries) — build outputs,
  reproducible from this source with `build_apk.sh`.
- **`debug.keystore`** — the signing key for every shipped APK. Never committed,
  anywhere.
- **Launcher PNG mipmaps** (`res/mipmap-*/ic_launcher*.png`) — binary assets;
  the adaptive-icon XML is included. Icon masters live in the workspace's
  `icon_src/`.
- **`tests/json.jar`** — binary test dependency (org.json), fetched locally.
- **`app/local.properties`** — machine-specific SDK path.
- Research scratch inside `tests/` (study scripts, CSVs, emulator helpers) —
  not part of the application.

No secrets exist in this source: ntfy topics are generated on-device at first
run and stored only on the phone; the Dhan fields in Settings are user-entered
and stored only on the phone.
