package xb.sync

import rx.session.sync.SyncClient
import rx.session.sync.SyncOps
import rx.session.sync.SyncWire
import xb.container.XbxProject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 撤銷要傳到另一個行程的伺服器：CLI 樣式的 forget，以及上傳驗證到一半被移除。 */
class RevocationSyncTest {
    private val work = Files.createTempDirectory("xbsync-revoke").toFile()
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
        val c = SyncClient(InetAddress.getLoopbackAddress().hostAddress, s.port, SyncWire.Identity(id, name))
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

    private class Link(val sock: Socket, val opened: SyncWire.Opened)

    private fun open(s: XbSync, me: SyncWire.Identity, xb: SyncWire.Peer): Link {
        val sock = Socket()
        sock.soTimeout = 5_000
        sock.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), s.port), 5_000)
        val o = SyncWire.connectClient(sock.getInputStream(), sock.getOutputStream().buffered(1 shl 15), me, xb, SyncWire.MODE_SYNC)
        return Link(sock, o)
    }

    private fun waitSessions(s: XbSync, id: String, count: Int) {
        val t0 = System.currentTimeMillis()
        while (s.activeSessions(id) != count && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
        assertEquals(count, s.activeSessions(id))
    }

    @Test
    fun forgettingFromAnotherStoreClosesTheRunningSession() {
        val s = server()
        val id = "111111111111111111111111"
        val otherId = "222222222222222222222222"
        val (peer, client) = pair(s, id, "Forget")
        storedPhone(s, id)
        val (otherPeer, _) = pair(s, otherId, "Keep")
        storedPhone(s, otherId)
        val mine = open(s, SyncWire.Identity(id, "Forget"), peer)
        val other = open(s, SyncWire.Identity(otherId, "Keep"), otherPeer)
        waitSessions(s, id, 1)
        waitSessions(s, otherId, 1)

        // CLI 的 forget 是另一支程式：同一個資料夾、另一個 store 實例，沒有這台伺服器的 callback。
        assertTrue(SyncStore(File(work, "sync")).forget(id))
        SyncOps.send(mine.opened.output, SyncOps.NAMES, ByteArray(0))
        assertNull(runCatching { SyncOps.readHeader(mine.opened.input) }.getOrNull(), "revoked phone must not get a reply")
        waitSessions(s, id, 0)

        // 另一台照常
        SyncOps.send(other.opened.output, SyncOps.NAMES, ByteArray(0))
        assertEquals(SyncOps.MISSING, runCatching { SyncOps.readHeader(other.opened.input) }.getOrNull()?.first)
        waitSessions(s, otherId, 1)

        // 用舊 token 重新連也不行
        assertEquals("unknown-device", assertFailsWith<SyncWire.SyncRefused> { client.sync(peer, emptyList(), { null }, null) }.reason)
        mine.sock.close()
        other.sock.close()
    }

    @Test
    fun forgettingDuringUploadValidationDiscardsTheStagedFile() {
        val s = server()
        val id = "333333333333333333333333"
        val (peer, _) = pair(s, id, "Paused")
        storedPhone(s, id)
        val phone = s.store.phone(id)!!

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        s.beforeUploadCommit = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val failure = AtomicReference<Throwable?>()
        val link = open(s, SyncWire.Identity(id, "Paused"), peer)
        val ride = minimalRide("paused")
        val worker = thread(isDaemon = true) {
            try {
                SyncOps.send(link.opened.output, SyncOps.NAMES, "paused".toByteArray(Charsets.UTF_8))
                SyncOps.expect(link.opened.input, SyncOps.MISSING)
                val nb = "paused".toByteArray(Charsets.UTF_8)
                link.opened.output.write(SyncOps.header(SyncOps.UPLOAD, 2L + nb.size + ride.length()))
                link.opened.output.write(byteArrayOf(nb.size.toByte(), (nb.size ushr 8).toByte()))
                link.opened.output.write(nb)
                ride.inputStream().use { it.copyTo(link.opened.output, 1 shl 15) }
                link.opened.output.flush()
                val reply = runCatching { SyncOps.readHeader(link.opened.input) }.getOrNull()
                // 收線（撤銷後不再回答）或錯誤都可以，就是不能是「已收下」
                if (reply != null) assertTrue(reply.first == SyncOps.ERROR, "unexpected reply ${reply.first.toInt().toChar()}")
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "upload should reach the final check")
            // 驗證停住的期間，另一支程式把這台手機移除
            assertTrue(SyncStore(File(work, "sync")).forget(id))
            release.countDown()
            worker.join(10_000)
            assertNull(failure.get(), "upload thread: ${failure.get()}")
        } finally {
            s.beforeUploadCommit = null
            release.countDown()
            link.sock.close()
        }

        // 沒有定案的檔案，暫存檔也要清掉
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 5_000 && walkFiles().isNotEmpty()) Thread.sleep(20)
        assertTrue(walkFiles().isEmpty(), "left behind: ${walkFiles().map { it.name }}")
        assertTrue(s.rides(phone).isEmpty())
        waitSessions(s, id, 0)
    }

    private fun walkFiles(): List<File> = File(work, "rides").walkTopDown().filter { it.isFile }.toList()

    /** 最小合法 .xbl：mimetype 在第一個成員且不壓縮，manifest 列了原始資料與 loga 的雜湊。 */
    private fun minimalRide(name: String): File {
        val f = File(work, "$name.xbl")
        val frames = ByteArray(0)
        val loga = ByteArray(0)
        ZipOutputStream(f.outputStream()).use { z ->
            val mime = "application/vnd.xenobrex.ride+zip".toByteArray()
            val crc = CRC32().apply { update(mime) }
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                this.crc = crc.value
            })
            z.write(mime)
            z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"format":"xbl-1","files":{"frames.rxraw":"${XbxProject.sha256(frames)}","decoded.loga":"${XbxProject.sha256(loga)}"}}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("frames.rxraw"))
            z.write(frames)
            z.closeEntry()
            z.putNextEntry(ZipEntry("decoded.loga"))
            z.write(loga)
            z.closeEntry()
        }
        return f
    }
}
