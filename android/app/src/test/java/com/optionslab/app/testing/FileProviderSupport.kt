package com.optionslab.app.testing

/**
 * 20 Nov 2026: androidx's FileProvider keeps each authority's folders in a static map the first time it is asked. Every Robolectric
 * test has its own app folders (a new /tmp/robolectric-... directory), so a later test's file would be "outside" the folders an earlier
 * test left there. A test that shares a file forgets them first; the phone itself has one set of folders for its whole life.
 */
fun forgetFileProviderPaths() {
    runCatching {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        val m = f.get(null) as java.util.HashMap<*, *>
        synchronized(m) { m.clear() }
    }
}
