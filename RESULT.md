# Erase key race: result (26 Oct)

## Cause
CI on main 29ba08f failed once: `RootNavigationTest.aWipeThatThrowsStillDestroysTheKeys`, "the vault key is gone".

`eraseEverything()` destroyed the vault and PIN-pepper keys and then started the slow "erase-files" thread. On that thread,
`MarketRecorder.wipe()` called `SecurePrefs.put(k, null)`. The settings file had already been deleted, so the write was not
skipped as "unchanged": it wrote a new `prefs.vault`, and `Vault.dataKey()` made a new key for it.

This happened on every erase started from the screen's thread. The test only failed when that thread beat the test's check.
Any other save racing the erase could do the same: a settings write, an arm's book, the ledger, or the pepper.

## Fix
- **`Vault.beginErase()` / `endErase()`** (an erase gate, with a counter and an epoch). While an erase runs:
  - no key is made or used (`dataKey()` and `PinPepper.key()` throw `Vault.Erasing`);
  - vault file saves are dropped, including a save that began before the erase and reaches its rename after it;
  - `endErase()` destroys both keys one last time.
- **`destroy()` holds the key lock**, so a key being made at that moment is destroyed too.
- **`eraseEverything()`** wraps all its wipes in `beginErase` / `try … finally endErase`.
- **`MarketRecorder.wipe` split:**
  - its settings are reset inside the gate;
  - its folder is deleted on the slow thread, which then resets its in-memory day again;
  - the slow thread writes no vault file, so a PIN set right after an erase still saves.
- **Review fixes:**
  - `Vault.writeFileWritten` reports a save that an erase dropped. `LastWrite`, `SecurePrefs` and `SecureBlobs` no longer
    treat that save as written. A save that a restore drops counts as before.
  - An `ArmLive` book read refused by an erase raises no "could not be read" alarm.
  - A `SecureBlobs` migration never sets a file aside because of an erase.
- **Not touched:** the order/tick path, the open-position watch and the main thread. Only volatile/atomic reads were added
  there.

## Tests
- **`VaultEraseTest`:**
  - no key or file during an erase, and saves work again after it;
  - a save from before the erase never lands after it;
  - 300 erase rounds with 4 racing writers (vault file, encrypt, settings, pepper);
  - a dropped save is not remembered as written (`LastWrite`, settings);
  - nested erases;
  - a failing hook still ends the erase.
- **`RootNavigationTest`:**
  - `theSlowFileWipesNeverMakeANewKey` waits for the "erase-files" thread. It fails every time on the old code.
  - `aBackgroundSaveRacingTheEraseNeverRecreatesTheKey` runs 40 erases, on and off the main thread, with a racing saver. It
    checks just before the last destroy that no key was made and that no wiped file came back.
- The existing tests are unchanged.

## CI
All checks are green on `caea2fbfe4763ccc7115a84630e113983a663247`: App tests 0–3/4, Build APK, PC harness, engine parity.
Publish screenshots was skipped, as usual.

The first push (424f77b) failed only in the new loop test's own setup: a ticket the racer saved after the erase ended blocked
the next round's seed. That was fixed in caea2fb.

## Left as is (noted by review, low risk)
- **Durable settings put in a narrow window.** A durable settings put made in the few milliseconds between `SecurePrefs.wipe()`
  and `endErase()` stays in memory only until the next save.
- **Erase on the screen's thread.** `beginErase` / `endErase` may wait behind a Keystore key being made at that moment.
- **Stores the erase does not wipe.** Several arm stores (e.g. GrammarArm, NightArm, HeroTicketArm, McxPaperArms, GoldPaper)
  are not in `eraseEverything()`. They keep their books in memory and save them again (with a new key) after an erase. This
  is out of scope here and worth a follow-up.
