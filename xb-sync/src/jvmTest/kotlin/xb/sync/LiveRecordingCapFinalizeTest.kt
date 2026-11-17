package xb.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.protocol.ChannelTable
import rx.session.Uplink
import rx.session.uplinkHello
import rx.session.sync.SyncClient
import rx.session.sync.SyncWire
import xb.container.DefinitionPack
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 即時副本撞到資料上限要從真正的停檔路徑收乾淨：收檔的 meta 完整可讀、每個檔都關好。
 * 用最小定義檔跑真的同步連線，不需要實車資料或廠商定義檔。
 */
class LiveRecordingCapFinalizeTest {
    private val work = Files.createTempDirectory("xblive-cap").toFile()
    private var sync: XbSync? = null

    @AfterTest fun cleanup() { sync?.stop(); work.deleteRecursively() }

    private fun defsDir(): File {
        val dir = File(work, "defs").apply { mkdirs() }
        File(dir, "ControlFile_S3.xml").writeText(
            """<ECUControlFile><ControlFileSerial sn="T1"/><Variables>
               |<Var name="RPM" absid="26"><Vinf>26,2,1,0,20000,0,Para,rpm,Stage_1,0,_,_,_,_</Vinf><Ver c_version="17" status="Enable"></Ver></Var>
               |</Variables></ECUControlFile>""".trimMargin(),
        )
        return dir
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun cappedRecordingFinalizesAndClosesEveryFile() = capped(bigEvents = false)

    /** 撞到上限時緩衝裡可能有一筆很長、全是控制字元的事件（跳脫後放大 6 倍）：收檔保留量也要裝得下。 */
    @Test fun cappedRecordingWithMaximallyEscapedEventsStillFinalizes() = capped(bigEvents = true)

    private fun capped(bigEvents: Boolean) = runBlocking {
        val pack = DefinitionPack(defsDir(), null)
        val logs = ArrayList<String>()
        val server = XbSync(
            SyncStore(File(work, "sync")), { pack }, { File(work, "rides") }, "XenoBrex test",
            limits = RideLimits(liveBytes = 512L shl 10, liveMs = 60_000),
            log = { logs += it },
        )
        sync = server
        server.start(InetAddress.getLoopbackAddress(), 0)

        val me = SyncWire.Identity("cccccccccccccccccccccccc", "Cap")
        val client = SyncClient(InetAddress.getLoopbackAddress().hostAddress, server.port, me)
        val pc = client.pair(server.startPairing().code.lowercase())

        val reg = ChannelRegistry.parse(pack.text("ControlFile_S3")!!)
        val rpm = reg.values[26]!!
        val table = ChannelTable(listOf(ChannelTable.Entry(26, rpm.cellSize)))
        val up = Uplink(
            InetAddress.getLoopbackAddress().hostAddress, server.port,
            { uplinkHello("cap-1", table, mapOf("rx.defs" to "S3", "rx.target" to "Ecu(test)")) },
            CoroutineScope(SupervisorJob() + Dispatchers.IO), secure = client.liveHandshake(pc),
        )
        try {
            val t0 = System.currentTimeMillis()
            while (!up.connected && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(10)
            assertTrue(up.connected, up.lastError)

            var dir: File? = null
            while (System.currentTimeMillis() - t0 < 5_000) {
                dir = server.live.value?.recording
                if (dir != null) break
                Thread.sleep(10)
            }
            assertNotNull(dir, "recording did not start")
            val session = dir
            val metaFile = File(session, "meta.json")
            assertFalse(metaFile.readText().contains("\"stopped_wall_ms\""), "剛開始不該是收檔版")

            // 一直送輪，直到收檔路徑把 meta 換成有 stopped_wall_ms 的那份
            var i = 0L
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                up.onRound(i * 1_000_000L, longArrayOf(i + 1000))
                if (bigEvents && i % 50 == 0L) up.onEvent(i * 1_000_000L, "\u0001".repeat(65_000))
                i++
                if (i % 200 == 0L) {
                    Thread.sleep(15)
                    if (metaFile.readText().contains("\"stopped_wall_ms\"")) break
                }
            }
            assertTrue(metaFile.readText().contains("\"stopped_wall_ms\""), "到達上限後 meta 沒有收檔：${metaFile.readText()}")
            assertTrue(logs.any { "size limit" in it }, "不是撞到資料上限停下來的：$logs")
            assertTrue(logs.none { "did not finalize" in it }, "收檔有錯：$logs")

            up.close(3_000)
            val t1 = System.currentTimeMillis()
            while (server.live.value?.online != false && System.currentTimeMillis() - t1 < 8_000) Thread.sleep(20)

            val meta = metaFile.readText()
            assertTrue("\"rounds\": " in meta, meta)
            for (name in listOf("frames.rxraw", "events.jsonl", "decoded.loga")) {
                val hex = Regex("\"$name\":\"([0-9a-f]{64})\"").find(meta)?.groupValues?.get(1)
                assertNotNull(hex, "$name 的雜湊不在 meta：$meta")
                assertEquals(hex, sha256(File(session, name).readBytes()), "$name 的內容與雜湊不符（沒有關好）")
            }
            assertFalse(File(session, ".meta.json.tmp").exists(), "暫存 meta 不該留下")
            assertTrue(File(session, "frames.rxraw").length() > 0, "frames 檔是空的")
            assertTrue(File(session, "events.jsonl").length() > 0, "events 檔是空的")
            val loga = Loga.parse(File(session, "decoded.loga").readBytes())
            assertEquals(0, loga.rejectedRows)
            assertTrue(loga.rows.isNotEmpty(), "loga 沒有資料列")
        } finally {
            up.close(0)
        }
    }
}
