package com.optionslab.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Base for tests that need Android (Context, files, org.json, Base64, the vault). Runs under
 * Robolectric in [TestApp] with the in-memory [FakeAndroidKeyStore]: each test starts on a
 * fresh "phone" - empty app directories, empty settings vault, no keys.
 *
 * 21 Oct, enhancement round 4 (idea 5): and with the market's clock at noon IST on a trading day ([MarketClockRule]); a test that
 * needs another time sets [com.optionslab.app.data.Market.testClock] itself.
 */
@RunWith(AndroidJUnit4::class)
abstract class RobolectricTest {
    @get:Rule val marketClock = MarketClockRule()
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
}
