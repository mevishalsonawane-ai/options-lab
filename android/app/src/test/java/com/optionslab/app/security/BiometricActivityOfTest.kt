package com.optionslab.app.security

import android.view.ContextThemeWrapper
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.robolectric.Robolectric

/**
 * 2 Nov (Boss: "Square off should have a fingerprint option, it asks only the PIN"): a PIN box shown over a dialog or a sheet sees a
 * wrapper of the activity, not the activity. The fingerprint prompt needs the activity, so it is found through the wrappers.
 */
class BiometricActivityOfTest : RobolectricTest() {
    @Test fun theActivityIsFoundThroughADialogsWrappers() {
        val act = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).create().get()
        assertSame(act, BiometricGate.activityOf(act))
        val dialogContext = ContextThemeWrapper(act, android.R.style.Theme_Material_Dialog)
        assertSame("a dialog's content", act, BiometricGate.activityOf(dialogContext))
        assertSame("a sheet inside a dialog", act, BiometricGate.activityOf(ContextThemeWrapper(dialogContext, 0)))
    }

    @Test fun noActivityBehindTheApplicationOrNothing() {
        assertNull("the application is no screen", BiometricGate.activityOf(context))
        assertNull(BiometricGate.activityOf(null))
        assertNull(BiometricGate.activityOf(ContextThemeWrapper(context, 0)))
    }
}
