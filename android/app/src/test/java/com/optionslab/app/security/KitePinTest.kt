package com.optionslab.app.security

import com.optionslab.app.testing.RobolectricTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Zerodha's certificate pins (trust on first use). What can be checked without a publicly trusted
 * chain: the stored pins and their reset, and that a chain the system does not trust is refused
 * before anything is pinned (fail closed), on both the REST and the stream trust managers.
 */
class KitePinTest : RobolectricTest() {
    private fun selfSigned(host: String): HeldCertificate = HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).build()

    @Test fun aFreshPhoneHasNoPins() {
        assertTrue(KitePin.pins.isEmpty())
        assertEquals(0L, KitePin.since)
        assertFalse(KitePin.mismatch)
    }

    @Test fun storedPinsAreReadAndResetClearsEveryPin() {
        SecurePrefs.putAll(mapOf("tls.kite.pins" to "cGluLTE=,, ,cGluLTI=", "tls.kite.since" to 1_767_225_600_000L, "tls.kitews.pins" to "cGluLTM="))
        assertEquals(listOf("cGluLTE=", "cGluLTI="), KitePin.pins)
        assertEquals(1_767_225_600_000L, KitePin.since)
        KitePin.reset()
        assertTrue(KitePin.pins.isEmpty())
        assertEquals(0L, KitePin.since)
        assertFalse("the stream's pins go too", SecurePrefs.snapshot().containsKey("tls.kitews.pins"))
        assertFalse(KitePin.mismatch)
    }

    @Test fun clientCertificatesAreNeverUsed() {
        val chain = arrayOf(selfSigned("ws.kite.trade").certificate)
        try { KitePin.streamTrust.checkClientTrusted(chain, "RSA"); fail() } catch (_: CertificateException) {}
    }

    @Test fun anUntrustedStreamChainIsRefusedAndNothingIsPinned() {
        val chain: Array<X509Certificate> = arrayOf(selfSigned(KitePin.WS_HOST).certificate)
        try { KitePin.streamTrust.checkServerTrusted(chain, "RSA"); fail("a self-signed chain was trusted") } catch (_: Exception) {}
        assertFalse(SecurePrefs.snapshot().containsKey("tls.kitews.pins"))
        assertNotNull(KitePin.streamSocketFactory)
    }

    @Test fun theRestSocketRefusesAnUntrustedServerBeforeSendingAnythingAndPinsNothing() {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        try {
            val s = KitePin.socketFactory.createSocket(server.hostName, server.port) as SSLSocket
            s.soTimeout = 5_000   // a stalled handshake fails the test instead of hanging it
            try { s.startHandshake(); fail("an untrusted server was accepted") } catch (_: Exception) {} finally { s.close() }
            assertEquals("no request got through", 0, server.requestCount)
            assertTrue("nothing pinned from an untrusted chain", KitePin.pins.isEmpty())
            assertFalse(KitePin.mismatch)
        } finally { server.shutdown() }
    }
}
