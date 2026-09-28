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
    private val local = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    private val runner = setOf("repo1.maven.org", "repo.maven.apache.org")
    private val sink = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))

    @Synchronized fun install() { if (getDefault() !== this) setDefault(this) }

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
        if (host in local || host in runner) return listOf(Proxy.NO_PROXY)
        blocked += host
        return sink
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
