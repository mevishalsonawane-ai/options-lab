package com.optionslab.app.security

import android.net.http.X509TrustManagerExtensions
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for api.kite.trade, trust-on-first-use.
 *
 * The chain must first pass the system's own validation (user-installed CAs
 * are already refused by the network security config). On the first good
 * connection the public-key hashes of the chain's CAs - every certificate but
 * the server's own, which rotates often - are recorded in the vault. From then
 * on a chain is accepted only if it runs through one of those CAs, so even a
 * certificate mis-issued by some other trusted CA is refused. The check runs
 * inside the TLS handshake, before a single byte of the request (the access
 * token included) is sent.
 *
 * If Zerodha moves to a new CA the app refuses to connect and says so; the
 * owner re-trusts under More → Security (with the PIN).
 */
object KitePin {
    const val HOST = "api.kite.trade"
    private const val K_PINS = "tls.kite.pins"
    private const val K_SINCE = "tls.kite.since"

    /** Set when the last refusal was a pin mismatch (not an ordinary network fault). */
    @Volatile var mismatch: Boolean = false
        private set

    val pins: List<String> get() = SecurePrefs.getString(K_PINS)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    val since: Long get() = SecurePrefs.getLong(K_SINCE, 0L)

    /** Forget the pins; the next connection learns them again. The UI asks for the PIN first. */
    fun reset() { SecurePrefs.putAll(mapOf(K_PINS to null, K_SINCE to null, K_WS_PINS to null)); mismatch = false }

    private fun spki(c: X509Certificate): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(c.publicKey.encoded), Base64.NO_WRAP)

    private val system: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val pinning = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not used")
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            // The system's validation, with the chain it actually built (root included).
            val verified = X509TrustManagerExtensions(system).checkServerTrusted(chain, authType, HOST)
            val cas = verified.drop(1).map(::spki)
            if (cas.isEmpty()) throw CertificateException("no CA in the chain")
            val known = pins
            if (known.isEmpty()) {
                SecurePrefs.putAll(mapOf(K_PINS to cas.joinToString(","), K_SINCE to System.currentTimeMillis()))
                mismatch = false
                return
            }
            if (cas.none { it in known }) {
                mismatch = true
                throw CertificateException("the certificate chain for $HOST does not match the pinned CAs")
            }
            mismatch = false
        }
    }

    // ---- ws.kite.trade: the live price stream, pinned the same way with its own CA set -----------

    const val WS_HOST = "ws.kite.trade"
    private const val K_WS_PINS = "tls.kitews.pins"

    /** The stream's trust manager: system validation for ws.kite.trade, then its own trust-on-first-use CA pins. */
    val streamTrust: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not used")
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val verified = X509TrustManagerExtensions(system).checkServerTrusted(chain, authType, WS_HOST)
            val cas = verified.drop(1).map(::spki)
            if (cas.isEmpty()) throw CertificateException("no CA in the chain")
            val known = SecurePrefs.getString(K_WS_PINS)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
            if (known.isEmpty()) { SecurePrefs.put(K_WS_PINS, cas.joinToString(",")); return }
            if (cas.none { it in known }) throw CertificateException("the certificate chain for $WS_HOST does not match the pinned CAs")
        }
    }

    val streamSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(streamTrust), null) }.socketFactory
    }

    /** The socket factory every call to api.kite.trade uses. */
    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(pinning), null) }.socketFactory
    }
}
