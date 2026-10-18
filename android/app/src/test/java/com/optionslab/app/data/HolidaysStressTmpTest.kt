package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import org.junit.Test
import java.io.File

/** TEMPORARY (throwaway branch, never merged): every HolidaysTest test 20 times, each on a fresh holiday file. */
class HolidaysStressTmpTest : RobolectricTest() {
    @Test fun everyHolidaysTestTwentyTimes() {
        val tests = HolidaysTest::class.java.methods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }
        check(tests.size >= 13) { "found ${tests.size}" }
        repeat(20) { round ->
            for (m in tests) {
                File(context.filesDir, "holidays.json").delete()
                val t = HolidaysTest()
                t.fresh()
                try { m.invoke(t) } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw AssertionError("round ${round + 1}: ${m.name}: ${e.targetException}", e.targetException)
                } finally { t.down() }
            }
        }
        println("STRESS: ${tests.size} HolidaysTest tests x 20 rounds passed")
    }
}
