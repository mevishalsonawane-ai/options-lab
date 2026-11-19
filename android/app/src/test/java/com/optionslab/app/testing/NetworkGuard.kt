package com.optionslab.app.testing

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TEST ONLY: no test may reach the internet. Installed by [TestApp]: every connection made through
 * the JVM's default proxy selection (HttpURLConnection, OkHttp) to anything but this machine is sent
 * to a closed local port, so it fails at once instead of reaching Zerodha, Upstox or NSE, and the
 * host is recorded in [blocked] (tests can assert it stays empty).
 *
 * Maven Central stays reachable: Robolectric itself downloads its Android runtime from there.
 */
object NetworkGuard : ProxySelector() {
    val blocked = CopyOnWriteArrayList<String>()
    /** 25 Oct: where each blocked connection came from (host, thread and the first frames of ours), for the failure's log line. */
    val traces = CopyOnWriteArrayList<String>()
    private val local = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    private val runner = setOf("repo1.maven.org", "repo.maven.apache.org")
    private val sink = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))

    @Synchronized fun install() { if (getDefault() !== this) setDefault(this) }

    /**
     * 20 Nov 2026 (minor fixes, item 6: [traces] was never cleared, so one test's blocked hosts and traces stayed for every later test in
     * the same JVM): both lists forgotten. Called as each test's app starts ([TestApp]) and after each [RobolectricTest] (after the
     * test's own @After has asserted on them).
     */
    fun reset() { blocked.clear(); traces.clear() }

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
        if (host in local || host in runner) return listOf(Proxy.NO_PROXY)
        blocked += host
        // Said at once on the test's error stream (the asserting test may be another one: a leaked background job's request).
        val t = Thread.currentThread()
        val frames = t.stackTrace.map { it.toString() }.filter { it.startsWith("com.optionslab") }.take(14)
        val trace = "NetworkGuard: $host asked from thread '${t.name}'\n    " + frames.joinToString("\n    ")
        traces += trace
        System.err.println(trace)
        return sink
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
