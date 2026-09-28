package com.optionslab.app.ui.screens

import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Alarms
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Backup
import com.optionslab.app.data.Holidays
import com.optionslab.app.data.Market
import com.optionslab.app.security.Capture
import com.optionslab.app.security.Integrity
import com.optionslab.app.security.KitePin
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.SessionLock
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.FakePicker
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.OfflineModel
import com.optionslab.app.testing.has
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.testing.pause
import com.optionslab.app.testing.until
import com.optionslab.app.testing.reveal
import com.optionslab.app.testing.switchFor
import com.optionslab.app.testing.waitForNoText
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.app.ui.wipes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every page under More that belongs to the app's controls: Security (guarded switches, change
 * PIN, the Zerodha certificate, seal, erase), backup and restore (passphrase rules, v1 PIN files,
 * v2/v3 passphrase files, the one-way restore to a restart), Bot settings (kill switch, limits),
 * Data & Harvest, Alarms, Schedules, and the More list itself. The pages run on a real, offline
 * [AppModel]; the file pickers are [FakePicker]. Test PINs and passphrases only.
 */
// A tall phone: a More page is a lazy list, and its later cards (the standing alarms, the backup buttons) are composed only on screen.
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ControlPagesTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var offline: OfflineModel
    private val model: AppModel get() = offline.model
    private lateinit var picker: FakePicker
    private val pin = "246813"

    @Before fun up() {
        AreaE.resetGlobals()
        offline = OfflineModel(app)
        picker = FakePicker(app)
    }

    @After fun down() {
        offline.close()
        AreaE.resetGlobals()
        assertEquals("no test may reach the network", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Every page here opens dialogs with text fields (PIN, passphrase): the clock runs paused (see [pause]). */
    private fun show(content: @Composable () -> Unit) {
        compose.pause()
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { IraAlgoTheme("light") { content() } }
        }
        compose.frames()
    }

    private fun tap(text: String) { compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }
    private fun toggle(title: String) { compose.switchFor(title).performSemanticsAction(SemanticsActions.OnClick); compose.frames() }
    private fun field(label: String, value: String) { compose.onNodeWithText(label).performTextReplacement(value); compose.frames() }
    private fun clearAlerts() { com.optionslab.app.work.Alerts.queue.value.forEach { com.optionslab.app.work.Alerts.dismiss(it.id) }; com.optionslab.app.work.Alerts.forgetPosted() }
    private fun waitAlert(text: String) = compose.until(20_000, "the alert '$text'") { AreaE.alerted(text) }
    private fun waitSettings(timeoutMs: Long = 10_000, ok: (AppSettings) -> Boolean) = compose.until(timeoutMs, "the settings") { ok(model.settings.value) }

    /** The PIN step every protection-lowering switch asks for. */
    private fun reauth(with: String, expectWhy: String? = null) {
        compose.waitForText("Confirm it is you")
        if (expectWhy != null) compose.onNodeWithText(expectWhy).assertExists()
        field("PIN", with)
        tap("Confirm")
    }

    // ---- Security ---------------------------------------------------------------------------

    @Test fun switchesThatLowerProtectionAskForThePin() {
        PinLock.setPin(pin.toCharArray())
        show { SecurityPage(model) }
        compose.waitForText("Security")
        // Raising protection: at once.
        toggle("Refuse compromised devices")
        waitSettings { it.refuseCompromised }
        assertFalse(compose.has("Confirm it is you"))
        // Lowering it: the PIN first. Cancel keeps it on.
        toggle("Refuse compromised devices")
        compose.waitForText("Confirm it is you")
        compose.onNodeWithText("Enter your app PIN to let IraAlgo open on a compromised phone.").assertExists()
        tap("Cancel")
        compose.waitForNoText("Confirm it is you")
        assertTrue(model.settings.value.refuseCompromised)
        // A wrong PIN is refused and keeps the dialog.
        toggle("Refuse compromised devices")
        reauth("135790")
        waitAlert("Not the right PIN.")
        compose.onNodeWithText("Confirm it is you").assertExists()
        assertTrue(model.settings.value.refuseCompromised)
        // The right one lowers it.
        field("PIN", pin); tap("Confirm")
        waitSettings { !it.refuseCompromised }
        compose.waitForNoText("Confirm it is you")

        // Erase after wrong PINs: on at once, off with the PIN.
        toggle("Erase after ${PinLock.WIPE_AFTER} wrong PINs")
        waitSettings { it.wipeOnExhaustion }
        toggle("Erase after ${PinLock.WIPE_AFTER} wrong PINs")
        reauth(pin, "Enter your app PIN to switch off erasing after wrong PINs.")
        waitSettings { !it.wipeOnExhaustion }
    }

    @Test fun aShorterIdleLockIsFreeALongerOneNeedsThePin() {
        PinLock.setPin(pin.toCharArray())
        show { SecurityPage(model) }
        compose.reveal("5 min")
        compose.onNodeWithText("5 min").assertIsSelected()
        tap("1 min")
        waitSettings { it.idleSeconds == 60 }
        assertFalse(compose.has("Confirm it is you"))
        tap("10 min")
        reauth(pin, "Enter your app PIN to keep the app open longer when idle.")
        waitSettings { it.idleSeconds == 600 }
        // Saved where the session lock reads it.
        compose.until(10_000) { SecurePrefs.getInt(SessionLock.K_IDLE, 0) == 600 }
    }

    @Test fun screenshotsCanBeBlockedAtOnceAndAllowedOnlyWithThePin() {
        PinLock.setPin(pin.toCharArray())
        show { SecurityPage(model) }
        val title = "Allow screenshots and screen recording"
        compose.switchFor(title).assertIsOn()
        toggle(title)
        compose.switchFor(title).assertIsOff()
        assertFalse(Capture.allowed)
        assertEquals(androidx.compose.ui.window.SecureFlagPolicy.SecureOn, Capture.policy)
        toggle(title)
        reauth(pin, "Enter your app PIN to allow screenshots and screen recording.")
        compose.until(10_000) { Capture.allowed }
    }

    @Test fun widgetAndLockScreenPrivacySwitches() {
        show { SecurityPage(model) }
        assertFalse(model.settings.value.widgetPnl)
        toggle("Show my P&L on the widget")
        waitSettings { it.widgetPnl }
        assertTrue(model.settings.value.hideAmountsOnLockScreen)
        toggle("Hide figures on the lock screen")
        waitSettings { !it.hideAmountsOnLockScreen }
        compose.until(10_000) { AppSettings.load().widgetPnl && !AppSettings.load().hideAmountsOnLockScreen }
    }

    @Test fun aFailedDeviceCheckPausesTheFingerprintAndIsListed() {
        compose.until(15_000) { model.integrity.value.isNotEmpty() }   // the model's own first check
        model.integrity.value = listOf(Integrity.Finding("Root", Integrity.Severity.DANGER, "su binaries present (test)"),
            Integrity.Finding("Debugger", Integrity.Severity.OK, "none attached"))
        model.update { it.copy(biometric = true) }
        show { SecurityPage(model) }
        compose.reveal("Device integrity")
        compose.onNodeWithText("su binaries present (test)").assertExists()
        assertTrue(compose.has("DANGER"))
        compose.reveal("Fingerprint")
        compose.onNodeWithText("Paused: this phone failed the security check (Root: su binaries present (test))", substring = true).assertExists()
        // No FragmentActivity here, so no sensor: the switch shows off and says why.
        compose.switchFor("Fingerprint").assertIsOff()
        compose.onNodeWithText("No fingerprint added on this phone").assertExists()
    }

    @Test fun turningTheFingerprintOnWithoutASensorNeedsThePinAndStaysOff() {
        model.update { it.copy(biometric = true, allowWeakFace = true) }
        show { SecurityPage(model) }
        // With no sensor the switch reads off; switching it "on" asks for the PIN only (never a finger).
        PinLock.setPin(pin.toCharArray())
        toggle("Fingerprint")
        reauth(pin, "Enter your app PIN to switch the fingerprint on. Every finger on this phone can then approve orders.")
        waitSettings { !it.biometric && !it.allowWeakFace }
    }

    @Test fun changePinRefusesAWrongCurrentPinAndAWeakNewOneThenChangesIt() {
        PinLock.setPin(pin.toCharArray())
        show { SecurityPage(model) }
        compose.reveal("Change PIN")
        tap("Change PIN")
        compose.waitForText("Current PIN")
        // Cancel closes it.
        tap("Cancel")
        compose.waitForNoText("Current PIN")
        tap("Change PIN")
        field("Current PIN", "135790"); field("New PIN (6 digits)", "135792")
        tap("Change")
        waitAlert("The current PIN is not right.")
        compose.onNodeWithText("Current PIN").assertExists()
        clearAlerts()
        field("Current PIN", pin); field("New PIN (6 digits)", "111111")
        tap("Change")
        compose.until(20_000) { Alerts2.anyError() }
        compose.onNodeWithText("Current PIN").assertExists()
        assertEquals("the old PIN still stands", PinLock.Result.Ok, PinLock.verify(pin.toCharArray(), false))
        field("Current PIN", pin); field("New PIN (6 digits)", "135792")
        tap("Change")
        compose.until(20_000) { model.message.value == "PIN changed." }
        compose.waitForNoText("Current PIN")
        assertEquals(PinLock.Result.Ok, PinLock.verify("135792".toCharArray(), false))
        assertTrue(PinLock.verify(pin.toCharArray(), false) is PinLock.Result.Wrong)
    }

    @Test fun sealNowLocksTheSession() {
        SessionLock.unlock()
        show { SecurityPage(model) }
        compose.reveal("Seal now")
        tap("Seal now")
        assertTrue(SessionLock.locked.value)
    }

    @Test fun eraseEverythingPersonalAsksThenErases() {
        PinLock.setPin(pin.toCharArray())
        show { SecurityPage(model) }
        val before = wipes.value
        compose.reveal("Erase everything personal")
        tap("Erase everything personal")
        compose.waitForText("Erase everything personal?")
        tap("Keep")
        compose.waitForNoText("Erase everything personal?")
        assertTrue(PinLock.isSet)
        tap("Erase everything personal")
        compose.waitForText("Erase everything personal?")
        tap("Erase")
        compose.until(10_000) { wipes.value == before + 1 }
        assertFalse(PinLock.isSet)
        assertTrue(SessionLock.locked.value)
    }

    @Test fun withNoPinsTheCertificateCardSaysSo() {
        show { SecurityPage(model) }
        compose.onNodeWithText("Not pinned yet", substring = true).assertExists()
        assertFalse(compose.has("Re-trust (Zerodha changed its CA)"))
    }

    @Test fun pinnedCertificatesAreListedAndReTrustAsksForThePin() {
        PinLock.setPin(pin.toCharArray())
        SecurePrefs.putAll(mapOf("tls.kite.pins" to "cGluLW9uZS1ub3QtcmVhbA==,cGluLXR3by1ub3QtcmVhbA==", "tls.kite.since" to 1_767_225_600_000L))
        show { SecurityPage(model) }
        compose.onNodeWithText("Pinned CAs").assertExists()
        assertTrue(compose.has("2"))
        tap("Re-trust (Zerodha changed its CA)")
        compose.waitForText("Confirm it is you")
        tap("Cancel")
        assertEquals(2, KitePin.pins.size)
        tap("Re-trust (Zerodha changed its CA)")
        reauth(pin)
        compose.until(20_000) { KitePin.pins.isEmpty() }
        compose.waitForText("Not pinned yet", substring = true)
        assertEquals("Pins cleared. The next connection, on a network you trust, records them again.", model.message.value)
    }

    @Test fun theSandboxCardSaysWhatTheAppCanAndCannotDo() {
        show { SecurityPage(model) }
        compose.reveal("IT CANNOT")
        compose.onNodeWithText("IT CAN").assertExists()
        compose.onNodeWithText("◆  Reach the internet", substring = true).assertExists()
        compose.onNodeWithText("✕  Use the camera, microphone or location").assertExists()
    }

    // ---- Backup and restore ------------------------------------------------------------------

    private fun openBackupDialog() {
        show { SecurityPage(model) }
        compose.reveal("Back up now")
        tap("Back up now")
        compose.waitForText("Seal the backup")
    }

    @Test fun backupPassphraseStrengthMismatchAndWeakRefusals() {
        PinLock.setPin(pin.toCharArray())
        openBackupDialog()
        field("App PIN", pin)
        field("Backup passphrase", "short")
        compose.waitForText("5 of at least ${Backup.MIN_PASSPHRASE} characters")
        tap("Continue")
        waitAlert("The backup passphrase needs at least ${Backup.MIN_PASSPHRASE} characters.")
        field("Backup passphrase", "aaaaaaaaaaaa")
        compose.waitForText("Weak: make it longer, or mix words, digits and symbols")
        field("Passphrase again", "aaaaaaaaaaab")
        compose.waitForText("The two passphrases differ.")
        tap("Continue")
        waitAlert("The two passphrases differ.")
        field("Passphrase again", "aaaaaaaaaaaa")
        compose.waitForNoText("The two passphrases differ.")
        tap("Continue")
        waitAlert("That passphrase is too easy to guess")
        field("Backup passphrase", "Abcdefgh12")
        compose.waitForText("Fair: a few more words would make it strong")
        field("Backup passphrase", "correct horse battery staple")
        compose.waitForText("Strong")
        assertTrue("nothing was saved", picker.launched.isEmpty())
        // Cancel closes it with nothing made.
        tap("Cancel")
        compose.waitForNoText("Seal the backup")
        assertTrue(picker.launched.isEmpty())
    }

    @Test fun aBackupNeedsTheAppPin() {
        PinLock.setPin(pin.toCharArray())
        openBackupDialog()
        field("App PIN", "135790")
        field("Backup passphrase", "correct horse battery staple")
        field("Passphrase again", "correct horse battery staple")
        tap("Continue")
        waitAlert("Not the right PIN.")
        assertTrue(picker.launched.isEmpty())
        compose.onNodeWithText("Seal the backup").assertExists()
    }

    @Test fun aBackupIsSavedToThePickedFileAndOnlyItsPassphraseOpensIt() {
        PinLock.setPin(pin.toCharArray())
        SecurePrefs.put("s.capital", 321_000.0)
        val out = picker.writable("out.irabk")
        picker.answer = { out }
        openBackupDialog()
        field("App PIN", pin)
        field("Backup passphrase", "correct horse battery staple")
        field("Passphrase again", "correct horse battery staple")
        tap("Continue")
        waitAlert("Backup saved.")
        compose.waitForNoText("Seal the backup")
        assertEquals(1, picker.launched.size)
        assertTrue(picker.launched.single().toString().matches(Regex("iraalgo-backup-\\d{4}-\\d{2}-\\d{2}\\.irabk")))
        val bytes = picker.written(out)
        assertNotNull(bytes)
        assertEquals(2, Backup.version(bytes!!))
        val c = runBlocking { Backup.open(bytes, "correct horse battery staple".toCharArray()) }
        assertEquals(321_000.0, c.json.getJSONObject("prefs").getDouble("s.capital"), 0.0)
        try { runBlocking { Backup.open(bytes, pin.toCharArray()) }; throw AssertionError("the PIN opened the backup") } catch (_: Backup.WrongPin) {}
    }

    @Test fun cancellingTheSaveDialogWritesNothing() {
        PinLock.setPin(pin.toCharArray())
        picker.answer = { null }
        openBackupDialog()
        field("App PIN", pin)
        field("Backup passphrase", "correct horse battery staple")
        field("Passphrase again", "correct horse battery staple")
        tap("Continue")
        compose.until(30_000) { picker.launched.isNotEmpty() }
        compose.waitForNoText("Seal the backup")
        assertFalse(AreaE.alerted("Backup saved."))
    }

    private val phrase = "correct horse battery staple"

    private fun restoreFrom(bytes: ByteArray) {
        picker.answer = { picker.bytesAt("in.irabk", bytes) }
        show { SecurityPage(model) }
        compose.reveal("Restore…")
        tap("Restore…")
    }

    @Test fun restoringAPassphraseBackupRunsOnceToARestart() {
        PinLock.setPin(pin.toCharArray())
        SecurePrefs.put("s.capital", 321_000.0)
        val bytes = runBlocking { Backup.create(app, phrase.toCharArray()) }
        SecurePrefs.put("s.capital", 1.0)
        restoreFrom(bytes)
        compose.waitForText("Open the backup")
        compose.onNodeWithText("Enter the passphrase the backup was sealed with.").assertExists()
        assertFalse("a passphrase file asks for no PIN", compose.has("PIN"))
        clearAlerts()
        field("Passphrase", "Wrong passphrase 42")
        tap("Continue")
        compose.until(30_000) { Alerts2.anyError() }
        compose.onNodeWithText("Open the backup").assertExists()
        field("Passphrase", phrase)
        tap("Continue")
        compose.waitForText("Restore this backup?")
        compose.onNodeWithText("It REPLACES this phone's", substring = true).assertExists()
        tap("Replace and restart")
        reauth(pin, "Enter this phone's app PIN to replace its data with the backup.")
        waitAlert("Restored. IraAlgo is closing; open it again.")
        assertEquals(321_000.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
        assertTrue(SecurePrefs.getBoolean(Backup.DISARM, false))
        // Once: the confirmation cannot come back and a second restore cannot start.
        compose.frames(); compose.waitForIdle()
        assertFalse(compose.has("Restore this backup?"))
        assertFalse(compose.has("Confirm it is you"))
    }

    @Test fun cancellingTheRestoreConfirmationOrItsPinChangesNothing() {
        PinLock.setPin(pin.toCharArray())
        SecurePrefs.put("s.capital", 321_000.0)
        val bytes = runBlocking { Backup.create(app, phrase.toCharArray()) }
        SecurePrefs.put("s.capital", 1.0)
        restoreFrom(bytes)
        compose.waitForText("Open the backup")   // the file is read off the main thread
        field("Passphrase", phrase)
        tap("Continue")
        compose.waitForText("Restore this backup?")
        tap("Replace and restart")
        compose.waitForText("Confirm it is you")
        tap("Cancel")
        compose.waitForText("Restore this backup?")
        tap("Cancel")
        compose.waitForNoText("Restore this backup?")
        assertEquals(1.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
    }

    /** An old backup, built from the documented layout (as data/BackupTest does). */
    private fun craftV1(secret: String, prefs: JSONObject): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret.toCharArray(), salt, 200_000, 256)).encoded
        val body = JSONObject().put("v", 1).put("at", 1_700_000_000_000L).put("prefs", prefs).put("files", JSONObject()).toString().toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv)); updateAAD("IRABK1".toByteArray())
        }
        return "IRABK1".toByteArray() + salt + iv + c.doFinal(body)
    }

    @Test fun anOldPinSealedBackupAsksForItsPinAndWarnsToChangeIt() {
        PinLock.setPin(pin.toCharArray())
        restoreFrom(craftV1("135790", JSONObject().put("ui.theme", "dark")))
        compose.waitForText("Open the backup")
        compose.onNodeWithText("An older backup, sealed with a PIN. Enter the PIN it was made with.").assertExists()
        assertFalse("a PIN file asks for no passphrase", compose.has("Passphrase"))
        field("PIN", "135790")
        tap("Continue")
        compose.waitForText("Restore this backup?")
        tap("Replace and restart")
        reauth(pin)
        waitAlert("This backup was sealed with a PIN")
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
    }

    @Test fun aFileThatIsNotABackupIsRefused() {
        restoreFrom("just some bytes, not a backup at all".toByteArray())
        waitAlert("This is not an IraAlgo backup.")
        assertFalse(compose.has("Open the backup"))
    }

    @Test fun cancellingTheFilePickerDoesNothing() {
        picker.answer = { null }
        show { SecurityPage(model) }
        compose.reveal("Restore…")
        tap("Restore…")
        compose.frames(); compose.waitForIdle()
        assertEquals(1, picker.launched.size)
        assertFalse(compose.has("Open the backup"))
        assertTrue(com.optionslab.app.work.Alerts.queue.value.isEmpty())
    }

    // ---- Bot settings: the kill switch and the limits -----------------------------------------

    @Test fun theKillSwitchAsksBothWays() {
        show { RiskPage(model) }
        compose.waitForText("Bot settings")
        toggle("Kill switch")
        compose.waitForText("Turn the kill switch on?")
        tap("Cancel")
        compose.waitForNoText("Turn the kill switch on?")
        assertFalse(model.settings.value.guardKill)
        toggle("Kill switch")
        tap("Turn on")
        waitSettings { it.guardKill }
        compose.onNodeWithText("ON: new entries are refused", substring = true).assertExists()
        compose.until(10_000) { AppSettings.load().guardKill }
        toggle("Kill switch")
        compose.waitForText("Clear the kill switch?")
        tap("Clear")
        waitSettings { !it.guardKill }
    }

    /**
     * The guard lets exits through with the kill switch on (data/Guard.kt: "An exit is never stopped: ... not by the
     * kill switch", checked in data/GuardTest); this page used to tell the owner the opposite.
     */
    @Test fun theKillSwitchTextMatchesWhatTheGuardDoes() {
        show { RiskPage(model) }
        toggle("Kill switch")
        compose.waitForText("Turn the kill switch on?")
        assertFalse(compose.has("including closing positions", substring = true))
        tap("Turn on")
        waitSettings { it.guardKill }
        assertFalse(compose.has("exits included", substring = true))
    }

    @Test fun eachLimitIsSavedWhenPicked() {
        show { RiskPage(model) }
        val picks = listOf<Pair<String, (AppSettings) -> Boolean>>(
            "₹5,000" to { it.guardDailyLoss == 5_000.0 },
            "20%" to { it.guardDrawdownPct == 20.0 },
            "₹10L" to { it.guardMaxValue == 1_000_000.0 },
            "14:00" to { it.guardCutoff == 14 * 60 },
        )
        for ((chip, ok) in picks) {
            compose.reveal(chip)
            tap(chip)
            waitSettings(ok = ok)
            compose.onNodeWithText(chip).assertIsSelected()
        }
        compose.until(10_000) { AppSettings.load().let { it.guardDailyLoss == 5_000.0 && it.guardCutoff == 14 * 60 } }
        // Paper has no limits of its own any more: the page says so and offers none.
        compose.reveal("Paper is practice", substring = true)
        assertTrue(compose.onAllNodesWithText("Paper daily loss limit").fetchSemanticsNodes().isEmpty())
    }

    @Test fun expirySquareOffAndNakedShortSwitches() {
        show { RiskPage(model) }
        val keep = "Keep the Expiry Put to settlement"
        val sq = "Square off on expiry day at 15:05"
        val was = model.settings.value.expirySquareOff
        toggle(sq)
        waitSettings { it.expirySquareOff != was }
        compose.reveal(sq)
        assertEquals(!was, compose.has(keep))
        val naked = model.settings.value.guardNakedShort
        toggle("Block naked option shorts")
        waitSettings { it.guardNakedShort != naked }
    }

    @Test fun theDrawdownPeakCanBeRestarted() {
        SecurePrefs.put("guard.peak.paper", 999_999.0)
        show { RiskPage(model) }
        compose.reveal("Restart the drawdown peak (paper)")
        tap("Restart the drawdown peak (paper)")
        assertFalse(SecurePrefs.snapshot().containsKey("guard.peak.paper"))
        assertEquals("Drawdown peak restarts from today's equity.", model.message.value)
    }

    // ---- Data & Harvest --------------------------------------------------------------------------

    @Test fun dataPageDeletesHarvestedDataOnlyWhenConfirmed() {
        show { DataPage(model) }
        compose.waitForText("Data & Harvest")
        val incl = model.settings.value.includeDeviceSessions
        toggle("Include this phone's captures")
        waitSettings { it.includeDeviceSessions != incl }
        compose.reveal("Delete this phone's harvested data")
        tap("Delete this phone's harvested data")
        compose.waitForText("Delete harvested data?")
        tap("Keep")
        compose.waitForNoText("Delete harvested data?")
        tap("Delete this phone's harvested data")
        compose.waitForText("Delete harvested data?")
        tap("Delete")
        compose.until(15_000) { model.message.value == "Harvested data deleted." }
        compose.waitForNoText("Delete harvested data?")
    }

    // ---- Alarms ---------------------------------------------------------------------------------------

    @Test fun anAlarmIsSetToggledAndRemoved() {
        show { AlarmsPage(model) }
        compose.waitForText("Set alarm")
        compose.onNodeWithText("None yet.").assertExists()
        compose.onNodeWithText("Set alarm").assertIsNotEnabled()
        field("Level", "24000")
        compose.onNodeWithText("Set alarm").assertIsEnabled()
        tap("Set alarm")
        compose.until(10_000) { Alarms.all().size == 1 }
        val a = Alarms.all().single()
        assertEquals("NIFTY", a.symbol); assertFalse(a.above); assertEquals(24_000.0, a.level, 0.0); assertTrue(a.enabled)
        assertEquals("Alarm set. It rings once, then rests 30 minutes.", model.message.value)
        val row = a.describe()
        compose.waitForText(row)
        toggle(row)
        compose.until(10_000) { Alarms.all().singleOrNull()?.enabled == false }
        compose.reveal("Remove")
        tap("Remove")
        compose.until(10_000) { Alarms.all().isEmpty() }
        compose.waitForText("None yet.")
    }

    @Test fun anyInstrumentNeedsAValidSymbol() {
        show { AlarmsPage(model) }
        tap("rises above")
        tap("Any instrument")
        compose.waitForText("EXCHANGE:SYMBOL, e.g. NSE:INFY or NFO:NIFTY26SEP24500PE")
        field("Level", "1500")
        compose.onNodeWithText("Set alarm").assertIsNotEnabled()
        field("EXCHANGE:SYMBOL, e.g. NSE:INFY or NFO:NIFTY26SEP24500PE", "nse:infy")
        compose.onNodeWithText("Set alarm").assertIsEnabled()
        tap("Set alarm")
        compose.until(10_000) { Alarms.all().size == 1 }
        assertEquals("NSE:INFY", Alarms.all().single().symbol)
        assertTrue(Alarms.all().single().above)
    }

    // ---- Schedules & notices -------------------------------------------------------------------------

    @Test fun holidaysAreAddedByHandAndRemoved() {
        show { SchedulePage(model) }
        compose.waitForText("Market holidays")
        compose.onNodeWithText("No holiday list yet", substring = true).assertExists()
        val label = "Add a holiday (yyyy-mm-dd)"
        field(label, "2026-13-40")
        compose.onNodeWithText("Add").assertIsNotEnabled()
        val day = Market.today().plusDays(40)
        field(label, day.toString())
        compose.onNodeWithText("Add").assertIsEnabled()
        tap("Add")
        assertTrue(Holidays.isHoliday(day))
        compose.waitForText("added by you", substring = true)
        tap("✕")
        assertFalse(Holidays.isHoliday(day))
        compose.waitForText("No holiday list yet", substring = true)
    }

    @Test fun notificationsTheDayAndAppearanceAreSaved() {
        show { SchedulePage(model) }
        val other = model.settings.value.otherAlerts
        toggle("Other notifications")
        waitSettings { it.otherAlerts != other }
        val health = model.settings.value.healthAlerts
        toggle("Tell me when health changes")
        waitSettings { it.healthAlerts != health }
        val remind = model.settings.value.entryReminder
        toggle("Entry reminder 10:55")
        waitSettings { it.entryReminder != remind }
        compose.reveal("Dark")
        tap("Dark")
        waitSettings { it.theme == "dark" }
        tap("Follow the phone")
        waitSettings { it.theme == "system" }
        val calm = model.settings.value.reduceMotion
        toggle("Calm motion")
        waitSettings { it.reduceMotion != calm }
        compose.reveal("Precise alarms")
        compose.onNodeWithText("Precise alarms").assertExists()
    }

    // ---- The More list -----------------------------------------------------------------------------

    @Test fun theMoreListOpensEachControlPageAndComesBack() {
        var page by mutableStateOf<String?>(null)
        show { CabinetScreen(model, page) { page = it } }
        for (t in listOf("Zerodha", "Alerts", "Security", "Schedules", "Bot settings", "Signal lab", "IC table", "Sizing and tail risk",
            "Cost calculator", "Lot sizes", "Research notes", "Data and harvest")) {
            compose.reveal(t)
            compose.onNodeWithText(t).assertExists()
        }
        val pages = listOf("Alerts" to ("alarms" to "Checked every minute by the market watch, and whenever the Home screen is open"),
            "Security" to ("security" to "Nothing personal leaves this phone, and nothing is logged"),
            "Schedules" to ("schedule" to "The strategy's day, kept by the phone"), "Bot settings" to ("risk" to "Limits on every order the bot or you place, paper and live"),
            "Data and harvest" to ("data" to "No free source serves expired contracts: a day not collected is gone"))
        for ((title, target) in pages) {
            val (key, marker) = target
            compose.reveal(title)
            tap(title)
            compose.until(5_000) { page == key }
            compose.waitForText(marker)
            tap("‹  More")
            compose.until(5_000) { page == null }
            compose.waitForText("Zerodha")
        }
    }
}

/** The error alerts currently posted (a refusal whose exact words come from elsewhere). */
private object Alerts2 {
    fun anyError() = (com.optionslab.app.work.Alerts.queue.value + com.optionslab.app.work.Alerts.posted).any { it.kind == com.optionslab.app.work.Alerts.Kind.ERROR }
}
