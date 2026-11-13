package io.github.jkfran.frameartmode.tv

import android.annotation.SuppressLint
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

/**
 * HTTP clients for talking to one TV. Samsung TVs present a self-signed certificate on port
 * 8002, issued for a name that does not match their address. These clients accept it, and
 * only for the TV's own address; nothing else in the app uses them, and nothing is installed
 * globally.
 */
object TvHttp {
    private val trustManager = TvTrustManager()

    private val sslSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf<TrustManager>(trustManager), null) }
            .socketFactory
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT),
            )
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** A client whose TLS connections succeed only for [host]. */
    fun forHost(host: String, connectMs: Long, readMs: Long, callMs: Long): OkHttpClient {
        val expected = TvUrls.canonicalHost(host)
        return base.newBuilder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { hostname, _ -> expected != null && hostname.equals(expected, ignoreCase = true) }
            .connectTimeout(connectMs, MILLISECONDS)
            .readTimeout(readMs, MILLISECONDS)
            .writeTimeout(readMs, MILLISECONDS)
            .callTimeout(callMs, MILLISECONDS)
            .build()
    }
}

/**
 * Accepts the TV's self-signed certificate chain. This is deliberate: the TV's certificate
 * cannot be validated, and the reference implementations do the same. It is only used by
 * [TvHttp.forHost], whose hostname check limits it to the configured TV.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal class TvTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("The TV sent no certificate")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Runs the call without blocking a thread; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
