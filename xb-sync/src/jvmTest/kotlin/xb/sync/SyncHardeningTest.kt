package xb.sync

import rx.session.sync.SyncClient
import rx.session.sync.SyncCrypto
import rx.session.sync.SyncWire
import xb.container.XbxProject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 收檔硬化：秘密檔權限、手機 id／名稱不逃出 rides、配額擋下超量上傳。不需要定義檔或實車資料。 */
class SyncHardeningTest {
    private val work = Files.createTempDirectory("xbsync-hard").toFile()
    private var sync: XbSync? = null

    @AfterTest
    fun cleanup() {
        sync?.stop()
        work.deleteRecursively()
    }

    private fun server(limits: RideLimits = RideLimits()): XbSync {
        val s = XbSync(SyncStore(File(work, "sync")), { null }, { File(work, "rides") }, "XenoBrex test", limits = limits)
        s.start(InetAddress.getLoopbackAddress(), 0)
        sync = s
        return s
    }

    /** 配對一台手機；回傳（伺服器記下的 peer、接下來要用的 client）。 */
    private fun pair(s: XbSync, id: String, name: String): Pair<SyncWire.Peer, SyncClient> {
        val me = SyncWire.Identity(id, name)
        val c = SyncClient(InetAddress.getLoopbackAddress().hostAddress, s.port, me)
        return c.pair(s.startPairing().code.lowercase()) to c
    }

    /** 配對的回應先到，伺服器才寫 phones.properties；等它落地再讀。 */
    private fun storedPhone(s: XbSync, id: String): SyncWire.Peer {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 5_000) {
            s.store.phone(id)?.let { return it }
            Thread.sleep(10)
        }
        error("Phone $id was not stored")
    }

    @Test
    fun secretsAndTheirFolderAreOwnerOnly() {
        val dir = File(work, "secret-sync")
        val store = SyncStore(dir)
        store.identity()
        store.addPhone(SyncWire.Peer("phone-1", "TestPhone", ByteArray(32) { 7 }, 1_000))
        assertEquals("TestPhone", store.phones().single().name)
        // 暫存檔不可以留在資料夾裡
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        if (Files.getFileAttributeView(dir.toPath(), PosixFileAttributeView::class.java) == null) return
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.toPath())))
        for (name in listOf("identity.properties", "phones.properties")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, name).toPath())), name)
        }
    }

    @Test
    fun refusesToWriteThroughASymlink() {
        val dir = File(work, "link-sync").apply { mkdirs() }
        val outside = File(work, "outside.properties").apply { writeText("untouched=1\n") }
        val link = File(dir, "phones.properties").toPath()
        runCatching { Files.createSymbolicLink(link, outside.toPath()) }.getOrElse { return }
        assertFailsWith<IOException> {
            SyncStore(dir).addPhone(SyncWire.Peer("phone-2", "Maya", ByteArray(32) { 3 }, 2_000))
        }
        assertEquals("untouched=1\n", outside.readText())
    }

    @Test
    fun hostilePeerNameStaysUnderRides() {
        val s = server()
        // 協定層（rx-session）已經只收 24–64 位 hex id；這裡用合法 id、惡意名稱，確認檔案仍夾在 rides 底下
        val id = "0123456789abcdef01234567"
        val (peer, client) = pair(s, id, "../Evil/Name")
        val phone = storedPhone(s, id)
        val name = "ride-one"
        val result = client.sync(peer, listOf(name), { minimalRide(name) }, null)
        assertEquals(listOf(name), result.uploaded, "failed: ${result.failed}")

        val root = File(work, "rides").canonicalFile
        val saved = s.rides(phone).single()
        assertTrue(saved.canonicalPath.startsWith(root.path + File.separator), saved.canonicalPath)
        val folder = s.folder(phone)
        assertTrue(folder.none { it == '/' || it == '\\' || it == ':' }, folder)
        assertEquals("Evil_Name_" + SyncCrypto.hex(SyncCrypto.sha256(id.toByteArray())).take(12), folder)
        assertTrue(folder.endsWith("_" + SyncCrypto.hex(SyncCrypto.sha256(id.toByteArray())).take(12)), folder)
        assertTrue(root.listFiles()!!.single().isDirectory)
        assertTrue(!File(work, "escape").exists() && !File(work.parentFile, "Evil").exists())
    }

    @Test
    fun folderStaysASingleSegmentForAnyPeerId() {
        val folder = XbSync.folderOf("../../escape", "../../evil")
        assertTrue(folder.none { it == '/' || it == '\\' || it == ':' }, folder)
        assertEquals("evil_" + SyncCrypto.hex(SyncCrypto.sha256("../../escape".toByteArray())).take(12), folder)
    }

    @Test
    fun oldFolderIsListedThenMigrated() {
        val s = server()
        val id = "abc123000000000000000000"
        val old = File(work, "rides/TestPhone_${id.take(6)}").apply { mkdirs() }
        minimalRide("old").renameTo(File(old, "old.xbl"))
        val (peer, client) = pair(s, id, "TestPhone")
        val phone = storedPhone(s, id)
        // 還沒上傳新檔前，舊資料夾的錄製仍看得到
        assertEquals(listOf("old.xbl"), s.rides(phone).map { it.name })
        assertEquals(listOf("new"), client.sync(peer, listOf("new"), { minimalRide("new") }, null).uploaded)
        val migrated = File(work, "rides/" + s.folder(phone))
        assertTrue(File(migrated, "old.xbl").isFile && File(migrated, "new.xbl").isFile)
        assertFalse(old.exists())
    }

    @Test
    fun rejectsRideOverTheByteQuota() {
        val s = server(RideLimits(phoneBytes = 1_024, totalBytes = 64L shl 10, phoneFiles = 10, totalFiles = 100, freeBeforeUpload = 0))
        val id = "aaaaaaaaaaaaaaaaaaaaaaaa"
        val (peer, client) = pair(s, id, "Quota")
        val phone = storedPhone(s, id)
        val big = client.sync(peer, listOf("big"), { minimalRide("big", padding = 4_096) }, null)
        assertTrue(big.uploaded.isEmpty())
        assertTrue(big.failed["big"]?.contains("room", ignoreCase = true) == true, "failed: ${big.failed}")
        assertTrue(s.rides(phone).isEmpty())

        // 額度內的小檔還是進得去（失敗的保留量有還回去）
        val small = client.sync(peer, listOf("small"), { minimalRide("small") }, null)
        assertEquals(listOf("small"), small.uploaded, "failed: ${small.failed}")
    }

    @Test
    fun rejectsRideOverTheFileQuota() {
        val s = server(RideLimits(phoneBytes = 1L shl 20, totalBytes = 8L shl 20, phoneFiles = 1, totalFiles = 10, freeBeforeUpload = 0))
        val id = "bbbbbbbbbbbbbbbbbbbbbbbb"
        val (peer, client) = pair(s, id, "Files")
        val phone = storedPhone(s, id)
        assertEquals(listOf("one"), client.sync(peer, listOf("one"), { minimalRide("one") }, null).uploaded)
        val two = client.sync(peer, listOf("two"), { minimalRide("two") }, null)
        assertTrue(two.uploaded.isEmpty())
        assertTrue(two.failed["two"]?.contains("Too many", ignoreCase = true) == true, "failed: ${two.failed}")
        assertEquals(1, s.rides(phone).size)
    }

    @Test
    fun liveCopyStopsAtItsCaps() {
        val dir = File(work, "live-cap").apply { mkdirs() }
        val bySize = LiveRecordingCap(maxMs = 10_000, maxBytes = 100)
        bySize.start(0)
        assertNull(bySize.exceeded(dir, 500))
        File(dir, "frames.rxraw").writeBytes(ByteArray(200))
        assertEquals("size limit", bySize.exceeded(dir, 1_600))

        val byTime = LiveRecordingCap(maxMs = 1_000, maxBytes = 1L shl 30)
        byTime.start(0)
        assertEquals("time limit", byTime.exceeded(dir, 1_001))
    }

    /** 最小合法 .xbl：mimetype 在第一個成員且不壓縮，manifest 列了原始資料與 loga 的雜湊。 */
    private fun minimalRide(name: String, padding: Int = 0): File {
        val f = File(work, "$name.xbl")
        val frames = ByteArray(padding)
        val loga = ByteArray(0)
        if (padding > 0) Random(7).nextBytes(frames)
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
