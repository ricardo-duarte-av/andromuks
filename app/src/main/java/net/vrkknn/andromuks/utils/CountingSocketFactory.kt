package net.vrkknn.andromuks.utils

import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * When bytes last arrived on the WebSocket's TCP socket, in [System.currentTimeMillis] — the same
 * clock as `WebSocketService.lastMessageReceivedTimestamp`, so the two can be compared directly.
 *
 * OkHttp only reports a WebSocket message once the whole frame has arrived. On a weak link a
 * ~500 KB `sync_complete` can take many seconds to trickle in, and the pong for our ping queues
 * behind it on the same stream, so frame-level liveness cannot tell "slow" from "dead". This clock
 * ticks on every raw read, so a link that is delivering anything at all counts as alive.
 */
object InboundByteClock {
    private val lastBytesAt = AtomicLong(0L)
    private val bytesSinceReset = AtomicLong(0L)

    val lastBytesReceivedAt: Long get() = lastBytesAt.get()

    /** Bytes counted since the last [reset]; lets a log line show whether counting works at all. */
    val bytesCounted: Long get() = bytesSinceReset.get()

    fun onBytes(count: Int) {
        bytesSinceReset.addAndGet(count.toLong())
        lastBytesAt.set(System.currentTimeMillis())
    }

    fun reset() {
        lastBytesAt.set(0L)
        bytesSinceReset.set(0L)
    }
}

/** The most recent evidence of inbound traffic: a whole frame or raw bytes, whichever is later. */
fun latestInboundActivity(lastFrameAt: Long, lastBytesAt: Long): Long = maxOf(lastFrameAt, lastBytesAt)

/** Whether anything arrived after [since] — the liveness test every watchdog applies. */
fun isLinkAlive(lastActivityAt: Long, since: Long): Boolean = lastActivityAt > since

/** Reports the size of every successful read to [onBytes]; end-of-stream and empty reads are not traffic. */
class CountingInputStream(delegate: InputStream, private val onBytes: (Int) -> Unit) : FilterInputStream(delegate) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0) onBytes(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) onBytes(count)
        return count
    }
}

/**
 * A plain [Socket] whose input stream is counted. OkHttp creates it unconnected and connects it
 * itself; TLS is layered on top, so the bytes counted here are the encrypted ones — which is exactly
 * "the link is delivering".
 */
private class CountingSocket(private val onBytes: (Int) -> Unit) : Socket() {
    private var countingStream: InputStream? = null

    @Synchronized
    override fun getInputStream(): InputStream = countingStream
        ?: CountingInputStream(super.getInputStream(), onBytes).also { countingStream = it }
}

/**
 * Socket factory for the WebSocket client only. Every overload returns a [CountingSocket]; OkHttp
 * uses the no-argument one and connects the socket itself.
 */
class CountingSocketFactory(private val onBytes: (Int) -> Unit = InboundByteClock::onBytes) : SocketFactory() {
    override fun createSocket(): Socket = CountingSocket(onBytes)

    override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port), null)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port), null)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket = CountingSocket(onBytes).apply {
        if (local != null) bind(local)
        connect(remote)
    }
}
