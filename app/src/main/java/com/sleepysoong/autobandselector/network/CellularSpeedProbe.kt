package com.sleepysoong.autobandselector.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Failure kinds for [CellularSpeedProbe.measureSelected]. */
sealed interface ProbeFailure {
    data object PermissionBlocked : ProbeFailure
    data object NoCellularSubscription : ProbeFailure
    data object NetworkUnavailable : ProbeFailure
    data class HttpError(val code: Int) : ProbeFailure
    data class IncompleteBody(val bytes: Long) : ProbeFailure
    data object Timeout : ProbeFailure
    data class Cancelled(val cause: Throwable) : ProbeFailure
    data object SubscriptionMismatch : ProbeFailure
}

/** Outcome of a probe: failure kind, or median Mbps over the samples. */
sealed interface ProbeOutcome {
    data class Failure(val kind: ProbeFailure) : ProbeOutcome
    data class Success(val medianMbps: Double, val samplesMbps: List<Double>) : ProbeOutcome
}

/** A cellular network acquired for a specific subscription; [release] unregisters the request. */
interface AcquiredNetwork {
    val network: Network

    /** Opens a connection strictly through the bound [network]; the default never touches the process-default route. */
    fun openConnection(url: URL): java.net.URLConnection = network.openConnection(url)

    fun release()
}

/** Test seam for network acquisition; production default uses [ConnectivityManager]. */
interface NetworkProbeTransport {
    @Throws(SecurityException::class)
    suspend fun acquire(subscriptionId: Int): AcquiredNetwork
}

/** Monotonic-clock seam so tests can drive fixed timings. */
fun interface NanoClock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM = NanoClock { System.nanoTime() }
    }
}

class NoCellularSubscriptionException(message: String) : Exception(message)
class NetworkUnavailableException(message: String) : Exception(message)
class SubscriptionMismatchException(message: String) : Exception(message)

private const val SAMPLE_URL = "https://speed.cloudflare.com/__down?bytes=3000000"
private const val SAMPLE_BYTES = 3_000_000L
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000
private const val SAMPLE_DEADLINE_MS = 10_000L

/**
 * Measures downstream speed over the cellular network bound to one subscription.
 * Never touches the default process network: every sample opens its connection through
 * [Network.openConnection] on the network granted by [ConnectivityManager.requestNetwork].
 */
class CellularSpeedProbe(
    private val transport: NetworkProbeTransport,
    private val clock: NanoClock = NanoClock.SYSTEM
) {
    /** Android adapter constructor. */
    constructor(context: Context, clock: NanoClock = NanoClock.SYSTEM) : this(
        AndroidNetworkProbeTransport(context.applicationContext), clock
    )

    /**
     * Runs [samples] sequential speed samples against the cellular network for [subscriptionId],
     * acquiring and releasing the bound network for every sample. Any failure aborts the call
     * with no retry.
     */
    suspend fun measureSelected(subscriptionId: Int, samples: Int = 3): ProbeOutcome {
        if (subscriptionId < 0) return ProbeOutcome.Failure(ProbeFailure.NoCellularSubscription)
        val mbps = ArrayList<Double>(samples)
        repeat(samples) {
            when (val sample = runSample(subscriptionId)) {
                is SampleResult.Ok -> mbps.add(sample.mbps)
                is SampleResult.Failed -> return ProbeOutcome.Failure(sample.kind)
            }
        }
        if (mbps.any { !it.isFinite() || it <= 0.0 }) {
            return ProbeOutcome.Failure(ProbeFailure.NetworkUnavailable)
        }
        return ProbeOutcome.Success(medianOf(mbps), mbps.toList())
    }

    private sealed interface SampleResult {
        data class Ok(val mbps: Double) : SampleResult
        data class Failed(val kind: ProbeFailure) : SampleResult
    }

    private suspend fun runSample(subscriptionId: Int): SampleResult {
        val acquired = try {
            transport.acquire(subscriptionId)
        } catch (error: SecurityException) {
            return SampleResult.Failed(ProbeFailure.PermissionBlocked)
        } catch (error: NoCellularSubscriptionException) {
            return SampleResult.Failed(ProbeFailure.NoCellularSubscription)
        } catch (error: NetworkUnavailableException) {
            return SampleResult.Failed(ProbeFailure.NetworkUnavailable)
        } catch (error: SubscriptionMismatchException) {
            return SampleResult.Failed(ProbeFailure.SubscriptionMismatch)
        } catch (error: CancellationException) {
            return SampleResult.Failed(ProbeFailure.Cancelled(error))
        }
        var stream: InputStream? = null
        try {
            return withTimeout(SAMPLE_DEADLINE_MS) {
                val startNanos = clock.nowNanos()
                val totalBytes = run {
                    // Blocking HTTP read runs on the caller's dispatcher so the withTimeout
                    // deadline stays virtual-time friendly; the read loop is cancellation-checked.
                    val connection = acquired.openConnection(URL(SAMPLE_URL)) as HttpURLConnection
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    try {
                        connection.connect()
                        val code = connection.responseCode
                        if (code != HttpURLConnection.HTTP_OK) {
                            return@run -1L - code // negative sentinel encodes the HTTP code
                        }
                        var total = 0L
                        val input = connection.inputStream
                        stream = input
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                        }
                        total
                    } finally {
                        connection.disconnect()
                    }
                }
                when {
                    totalBytes < 0 -> SampleResult.Failed(ProbeFailure.HttpError((-totalBytes - 1).toInt()))
                    totalBytes != SAMPLE_BYTES -> SampleResult.Failed(ProbeFailure.IncompleteBody(totalBytes))
                    else -> {
                        val elapsedNanos = clock.nowNanos() - startNanos
                        if (elapsedNanos <= 0) {
                            SampleResult.Failed(ProbeFailure.NetworkUnavailable)
                        } else {
                            SampleResult.Ok(
                                totalBytes * 8.0 / (elapsedNanos / 1_000_000_000.0) / 1_000_000.0
                            )
                        }
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            return SampleResult.Failed(ProbeFailure.Timeout)
        } catch (error: SocketTimeoutException) {
            return SampleResult.Failed(ProbeFailure.Timeout)
        } catch (error: java.io.InterruptedIOException) {
            return SampleResult.Failed(ProbeFailure.Timeout)
        } catch (error: CancellationException) {
            // Disconnect the open stream so the blocking read aborts, then propagate.
            try {
                stream?.close()
            } catch (_: Exception) {
            }
            throw error
        } finally {
            acquired.release()
        }
    }

    companion object {
        fun medianOf(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}

/**
 * Default transport: binds the cellular network for a subscription via
 * [ConnectivityManager.requestNetwork] with a TelephonyNetworkSpecifier.
 * android.telephony.TelephonyNetworkSpecifier is not in the public SDK jar, so it is built
 * reflectively; the callback object fully exists before the request is issued, and the
 * network is only accepted when its capabilities carry TRANSPORT_CELLULAR and (when the
 * specifier can be inspected, API 31+) its specifier targets the requested subscription.
 */
class AndroidNetworkProbeTransport(private val context: Context) : NetworkProbeTransport {

    override suspend fun acquire(subscriptionId: Int): AcquiredNetwork {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("ACCESS_NETWORK_STATE not granted")
        }
        if (subscriptionId < 0) {
            throw NoCellularSubscriptionException("No cellular subscription $subscriptionId")
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: throw NetworkUnavailableException("ConnectivityManager unavailable")

        val specifier = buildTelephonySpecifier(subscriptionId)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .setNetworkSpecifier(specifier)
            .build()

        lateinit var callback: ConnectivityManager.NetworkCallback
        val network = suspendCancellableCoroutine<Network> { continuation ->
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val specifierSubId = specifierSubscriptionId(capabilities.networkSpecifier)
                        if (specifierSubId != null && specifierSubId != subscriptionId) {
                            tryResumeWith(
                                Result.failure(
                                    SubscriptionMismatchException(
                                        "Specifier subId $specifierSubId != $subscriptionId"
                                    )
                                )
                            )
                            return
                        }
                    }
                    tryResumeWith(Result.success(network))
                }

                override fun onUnavailable() {
                    tryResumeWith(Result.failure(NetworkUnavailableException("requestNetwork denied")))
                }

                override fun onLost(network: Network) {
                    tryResumeWith(Result.failure(NetworkUnavailableException("network lost")))
                }

                private fun tryResumeWith(result: Result<Network>) {
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            }
            continuation.invokeOnCancellation {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
            // The callback object fully exists in memory before this call; requestNetwork
            // registers the callback and issues the request together.
            connectivity.requestNetwork(request, callback)
        }

        return object : AcquiredNetwork {
            override val network: Network = network
            override fun release() {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun buildTelephonySpecifier(subscriptionId: Int): android.net.NetworkSpecifier {
        return try {
            val builderClass = Class.forName("android.telephony.TelephonyNetworkSpecifier\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setSubscriptionId", Int::class.javaPrimitiveType)
                .invoke(builder, subscriptionId)
            builderClass.getMethod("build").invoke(builder) as android.net.NetworkSpecifier
        } catch (error: ReflectiveOperationException) {
            throw NetworkUnavailableException(
                "TelephonyNetworkSpecifier unavailable: ${error.message}"
            )
        }
    }

    private fun specifierSubscriptionId(specifier: android.net.NetworkSpecifier?): Int? {
        if (specifier == null) return null
        if (!specifier.javaClass.name.startsWith("android.telephony.TelephonyNetworkSpecifier")) {
            return null
        }
        return try {
            specifier.javaClass.getMethod("getSubscriptionId").invoke(specifier) as? Int
        } catch (error: ReflectiveOperationException) {
            null
        }
    }
}
