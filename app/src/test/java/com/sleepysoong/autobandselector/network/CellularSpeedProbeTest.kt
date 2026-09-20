package com.sleepysoong.autobandselector.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [CellularSpeedProbe] driving fake transports and fake connections. */
class CellularSpeedProbeTest {

    private class FakeConnection(
        url: URL,
        private val bodyBytes: ByteArray = ByteArray(SAMPLE_BYTES),
        private val responseCodeValue: Int = 200,
        private val readFailure: Exception? = null
    ) : HttpURLConnection(url) {
        var connected = false
        var disconnected = false
        lateinit var stream: TrackingInputStream

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = responseCodeValue

        override fun getInputStream(): InputStream {
            stream = TrackingInputStream(bodyBytes, readFailure)
            return stream
        }
    }

    private class TrackingInputStream(
        bodyBytes: ByteArray,
        private val failure: Exception?
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bodyBytes)
        var closed = false

        override fun read() = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            failure?.let { throw it }
            return delegate.read(b, off, len)
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class BlockingInputStream(
        private val started: CompletableDeferred<Unit>
    ) : InputStream() {
        private val release = java.util.concurrent.CountDownLatch(1)
        @Volatile var closed = false

        override fun read() = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            started.complete(Unit)
            release.await()
            return -1
        }

        override fun close() {
            closed = true
            release.countDown()
        }
    }

    private class BlockingConnection(
        url: URL,
        val stream: BlockingInputStream
    ) : HttpURLConnection(url) {
        @Volatile var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; stream.close() }
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        override fun getInputStream(): InputStream = stream
    }

    private class FakeAcquired(
        private val connectionFactory: () -> FakeConnection
    ) : AcquiredNetwork {
        override val network: android.net.Network
            get() = throw UnsupportedOperationException("fakes never touch android.net.Network")
        var released = false
        val openedConnections = mutableListOf<FakeConnection>()

        override fun openConnection(url: URL): java.net.URLConnection {
            val connection = connectionFactory()
            openedConnections.add(connection)
            return connection
        }

        override fun release() {
            released = true
        }
    }

    private class ScriptedTransport(
        private val acquireForCall: (callIndex: Int) -> AcquiredNetwork
    ) : NetworkProbeTransport {
        var acquireCalls = 0
        var subIdSeenBeforeAcquire = -1
        var onBeforeAcquire: (() -> Unit)? = null
        val acquiredNetworks = mutableListOf<AcquiredNetwork>()

        override suspend fun acquire(subscriptionId: Int): AcquiredNetwork {
            onBeforeAcquire?.invoke()
            val acquired = acquireForCall(acquireCalls)
            acquireCalls++
            subIdSeenBeforeAcquire = subscriptionId
            acquiredNetworks.add(acquired)
            return acquired
        }
    }

    private class ScriptedClock(nanosPerSample: List<Long>) : NanoClock {
        private val ticks = java.util.ArrayDeque<Long>().apply {
            var current = 0L
            for (nanos in nanosPerSample) {
                addLast(current)
                current += nanos
                addLast(current)
            }
        }

        override fun nowNanos(): Long = ticks.removeFirst()
    }

    private fun okAcquired() = FakeAcquired { FakeConnection(URL("https://x/")) }

    /** Elapsed nanos for a full 3,000,000-byte body downloaded at [mbps] megabits/second. */
    private fun nanosFor(mbps: Double): Long =
        (SAMPLE_BYTES * 8.0 / (mbps * 1_000_000.0) * 1_000_000_000.0).toLong()

    @Test
    fun medianOfThreeFixedSamplesIsMiddleValue() = runTest {
        val transport = ScriptedTransport { okAcquired() }
        // 8.0, 20.0, 11.0 Mbps -> median 11.0
        val clock = ScriptedClock(listOf(nanosFor(8.0), nanosFor(20.0), nanosFor(11.0)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(7)
        assertTrue(outcome is ProbeOutcome.Success)
        val success = outcome as ProbeOutcome.Success
        assertEquals(11.0, success.medianMbps, 0.001)
        assertEquals(3, success.samplesMbps.size)
    }

    @Test
    fun exactlyThreeAcquisitionsForSuccessfulMeasure() = runTest {
        val transport = ScriptedTransport { okAcquired() }
        val clock = ScriptedClock(listOf(nanosFor(12.5), nanosFor(12.5), nanosFor(12.5)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(3)
        assertTrue(outcome is ProbeOutcome.Success)
        assertEquals(3, transport.acquireCalls)
        assertEquals(listOf(3, 3, 3), List(3) { transport.subIdSeenBeforeAcquire })
        assertTrue(transport.acquiredNetworks.all { (it as FakeAcquired).released })
    }

    @Test
    fun sampleAcquiresAndConnectionsAreSequentialAndPaired() = runTest {
        val transport = ScriptedTransport { okAcquired() }
        val clock = ScriptedClock(listOf(nanosFor(10.0), nanosFor(10.0), nanosFor(10.0)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(9)
        assertTrue(outcome is ProbeOutcome.Success)
        transport.acquiredNetworks.forEach { fake ->
            fake as FakeAcquired
            assertEquals(1, fake.openedConnections.size)
            assertTrue(fake.openedConnections.single().connected)
            assertTrue(fake.openedConnections.single().disconnected)
        }
    }

    @Test
    fun negativeSubscriptionIdIsNoCellularSubscription() = runTest {
        val transport = ScriptedTransport { error("must not be called") }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(-1)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.NoCellularSubscription), outcome)
        assertEquals(0, transport.acquireCalls)
    }

    @Test
    fun permissionBlockedWhenTransportThrowsSecurityException() = runTest {
        val transport = ScriptedTransport { throw SecurityException("denied") }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.PermissionBlocked), outcome)
    }

    @Test
    fun networkUnavailableWhenTransportThrows() = runTest {
        val transport = ScriptedTransport { throw NetworkUnavailableException("down") }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.NetworkUnavailable), outcome)
    }

    @Test
    fun noSubscriptionWhenTransportThrows() = runTest {
        val transport = ScriptedTransport { throw NoCellularSubscriptionException("no sim") }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.NoCellularSubscription), outcome)
    }

    @Test
    fun subscriptionMismatchWhenTransportThrows() = runTest {
        val transport = ScriptedTransport { throw SubscriptionMismatchException("wrong sim") }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.SubscriptionMismatch), outcome)
    }

    @Test
    fun httpErrorCarriesStatusCode() = runTest {
        val transport = ScriptedTransport { FakeAcquired { FakeConnection(URL("https://x/"), responseCodeValue = 503) } }
        val clock = ScriptedClock(listOf(nanosFor(10.0)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.HttpError(503)), outcome)
    }

    @Test
    fun incompleteBodyReportsActualByteCount() = runTest {
        val transport = ScriptedTransport {
            FakeAcquired { FakeConnection(URL("https://x/"), bodyBytes = ByteArray(1_500_000)) }
        }
        val clock = ScriptedClock(listOf(nanosFor(10.0)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.IncompleteBody(1_500_000L)), outcome)
    }

    @Test
    fun socketReadTimeoutIsTimeoutFailure() = runTest {
        val transport = ScriptedTransport {
            FakeAcquired { FakeConnection(URL("https://x/"), readFailure = SocketTimeoutException("read timed out")) }
        }
        val clock = ScriptedClock(listOf(nanosFor(10.0)))
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.Timeout), outcome)
    }

    @Test(timeout = 5_000)
    fun externalCancellationDisconnectsBlockedReadImmediately() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stream = BlockingInputStream(started)
        val connection = BlockingConnection(URL("https://x/"), stream)
        val acquired = object : AcquiredNetwork {
            override val network: android.net.Network
                get() = throw UnsupportedOperationException()
            var released = false
            override fun openConnection(url: URL) = connection
            override fun release() { released = true }
        }
        val probe = CellularSpeedProbe(ScriptedTransport { acquired }, ScriptedClock(listOf(1_000_000_000L)))
        val job = launch(Dispatchers.Default) { probe.measureSelected(41, samples = 1) }
        started.await()

        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(stream.closed)
        assertTrue(connection.disconnected)
        assertTrue(acquired.released)
    }

    @Test
    fun cancelledAcquireIsCancelledFailure() = runTest {
        val cause = CancellationException("caller gave up")
        val transport = ScriptedTransport { throw cause }
        val outcome = CellularSpeedProbe(transport, ScriptedClock(listOf(1L))).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.Cancelled(cause)), outcome)
    }

    @Test
    fun cancellationDuringReadClosesStreamAndRethrows() = runTest {
        val cancel = CancellationException("cancelled mid-read")
        val transport = ScriptedTransport {
            FakeAcquired { FakeConnection(URL("https://x/"), readFailure = cancel) }
        }
        val clock = ScriptedClock(listOf(nanosFor(10.0)))
        var propagated: CancellationException? = null
        try {
            CellularSpeedProbe(transport, clock).measureSelected(5)
        } catch (error: CancellationException) {
            propagated = error
        }
        assertEquals("cancelled mid-read", propagated?.message)
        val acquired = transport.acquiredNetworks.single() as FakeAcquired
        assertTrue(acquired.openedConnections.single().stream.closed)
        assertTrue(acquired.released)
    }

    @Test
    fun firstFailureAbortsWithoutRetry() = runTest {
        val transport = ScriptedTransport {
            FakeAcquired { FakeConnection(URL("https://x/"), responseCodeValue = 500) }
        }
        val clock = ScriptedClock(
            listOf(nanosFor(10.0), nanosFor(10.0), nanosFor(10.0))
        )
        val outcome = CellularSpeedProbe(transport, clock).measureSelected(5)
        assertEquals(ProbeOutcome.Failure(ProbeFailure.HttpError(500)), outcome)
        assertEquals(1, transport.acquireCalls)
    }

    private companion object {
        const val SAMPLE_BYTES = 3_000_000
    }
}
