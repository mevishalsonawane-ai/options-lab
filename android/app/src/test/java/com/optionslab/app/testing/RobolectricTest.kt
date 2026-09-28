package com.optionslab.app.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Base for tests that need Android (Context, files, org.json, Base64, the vault). Runs under
 * Robolectric in [TestApp] with the in-memory [FakeAndroidKeyStore]: each test starts on a
 * fresh "phone" - empty app directories, empty settings vault, no keys.
 */
@RunWith(AndroidJUnit4::class)
abstract class RobolectricTest {
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
}
