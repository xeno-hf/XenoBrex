package xb.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** ECU WiFi 模組的 TCP 6666（ProLink 幀）。做法同 ReX `TcpTransport`。 */
class TcpTransport private constructor(private val socket: Socket) : Transport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buf = ByteArray(8192)

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) { output.write(bytes); output.flush() }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        var result: ByteArray? = null
        while (true) {
            try {
                val n = input.read(buf)
                if (n >= 0) result = buf.copyOf(n)
                break
            } catch (_: SocketTimeoutException) {
                // 讀逾時只是讓協程有機會被取消
                if (socket.isClosed) break
                ensureActive()
            }
        }
        result
    }

    override fun close() { runCatching { socket.close() } }

    companion object {
        const val PORT = 6666

        suspend fun connect(host: String, port: Int = PORT, timeoutMs: Int = 4000): TcpTransport = withContext(Dispatchers.IO) {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 500
            s.connect(InetSocketAddress(host, port), timeoutMs)
            TcpTransport(s)
        }
    }
}
