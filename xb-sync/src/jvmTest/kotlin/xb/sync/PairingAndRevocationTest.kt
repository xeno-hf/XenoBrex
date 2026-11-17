package xb.sync

import rx.session.sync.SyncClient
import rx.session.sync.SyncCrypto
import rx.session.sync.SyncWire
import java.io.BufferedOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 配對碼換掉／關掉，以及把已配對手機移除時，進行中的連線不能留下來。 */
class PairingAndRevocationTest {
    private val work = Files.createTempDirectory("xbsync-pair").toFile()
    private var sync: XbSync? = null

    @AfterTest
    fun cleanup() {
        sync?.stop()
        work.deleteRecursively()
    }

    private fun server(): XbSync {
        val s = XbSync(SyncStore(File(work, "sync")), { null }, { File(work, "rides") }, "XenoBrex test")
        s.start(InetAddress.getLoopbackAddress(), 0)
        sync = s
        return s
    }

    private fun pair(s: XbSync, id: String, name: String): Pair<SyncWire.Peer, SyncClient> {
        val me = SyncWire.Identity(id, name)
        val c = SyncClient(InetAddress.getLoopbackAddress().hostAddress, s.port, me)
        return c.pair(s.startPairing().code.lowercase()) to c
    }

    private fun storedPhone(s: XbSync, id: String) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 5_000) {
            if (s.store.phone(id) != null) return
            Thread.sleep(10)
        }
        error("Phone $id was not stored")
    }

    @Test
    fun replacingOrClearingAPairingWindowCancelsTheOldOne() {
        val s = server()
        val first = s.startPairing()
        assertTrue(first.open)
        val second = s.startPairing()
        assertFalse(first.open, "換新的一組時舊的作廢")
        assertTrue(second.open)
        s.cancelPairing()
        assertFalse(second.open)
        assertNull(s.pairing.value)
    }

    /** 握手已經走到一半、對方也算好 mac：舊的配對碼被換掉就不能完成。 */
    @Test
    fun aHandshakeStartedOnAReplacedWindowCannotFinish() {
        val s = server()
        val window = s.startPairing()
        val id = "abcdefabcdefabcdefabcdef"
        val kp = SyncCrypto.ecKeyPair()
        val cn = SyncCrypto.randomBytes(16)

        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer)
        val serverOut = PipedOutputStream()
        val fromServer = PipedInputStream(serverOut)
        val result = CompletableFuture<Any>()
        val worker = thread(isDaemon = true) {
            try {
                result.complete(SyncWire.accept(serverIn, serverOut, SyncWire.Identity("0123456789abcdef01234567", "XB"), { null }, { window }))
            } catch (e: Throwable) {
                result.complete(e)
            }
        }
        try {
            val out = BufferedOutputStream(toServer)
            val m1 = SyncWire.writeMsg(out, linkedMapOf(
                "op" to "pair", "id" to id, "name" to "TestPhone",
                "epk" to SyncCrypto.hex(kp.public.encoded), "cn" to SyncCrypto.hex(cn),
            ))
            val (r, m2) = SyncWire.readMsg(fromServer)
            val z = SyncCrypto.ecdh(kp, SyncCrypto.publicKey(SyncCrypto.unhex(r["epk"]!!)))
            val ak = SyncCrypto.hmac(window.code.toByteArray(), z, SyncCrypto.sha256(m1 + m2))
            assertTrue(s.startPairing().open)
            SyncWire.writeMsg(out, mapOf("mac" to SyncCrypto.hex(SyncCrypto.hmac(ak, "client".toByteArray()))))
            val e = result.get(5, TimeUnit.SECONDS)
            assertTrue(e is SyncWire.SyncRefused && e.reason == "no-pairing", "$e")
        } finally {
            worker.join(1_000)
        }
    }

    @Test
    fun forgettingAPhoneClosesOnlyItsOpenSessions() {
        val s = server()
        val id = "feedfacefeedfacefeedface"
        val otherId = "0badc0de0badc0de0badc0de"
        val (peer, client) = pair(s, id, "Forget")
        storedPhone(s, id)
        val (otherPeer, _) = pair(s, otherId, "Keep")
        storedPhone(s, otherId)

        fun openSession(peerId: String, name: String, server: SyncWire.Peer): Socket {
            val sock = Socket()
            sock.soTimeout = 5_000
            sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), s.port), 5_000)
            SyncWire.connectClient(sock.getInputStream(), sock.getOutputStream().buffered(), SyncWire.Identity(peerId, name), server, SyncWire.MODE_SYNC)
            return sock
        }
        val sock = openSession(id, "Forget", peer)
        val other = openSession(otherId, "Keep", otherPeer)
        val t0 = System.currentTimeMillis()
        while ((s.activeSessions(id) == 0 || s.activeSessions(otherId) == 0) && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
        assertEquals(1, s.activeSessions(id))
        assertEquals(1, s.activeSessions(otherId))

        assertTrue(s.store.forget(id))
        // 伺服器把這台的連線關掉：讀到結尾，而不是繼續收
        assertEquals(-1, sock.getInputStream().read())
        val t1 = System.currentTimeMillis()
        while (s.activeSessions(id) != 0 && System.currentTimeMillis() - t1 < 5_000) Thread.sleep(10)
        assertEquals(0, s.activeSessions(id))
        // 另一台的連線不受影響
        assertEquals(1, s.activeSessions(otherId))
        assertTrue(s.store.phone(otherId) != null)
        // 移除之後也開不了新連線
        assertFailsWith<SyncWire.SyncRefused> { client.sync(peer, emptyList(), { null }, null) }
        sock.close()
        other.close()
    }
}
