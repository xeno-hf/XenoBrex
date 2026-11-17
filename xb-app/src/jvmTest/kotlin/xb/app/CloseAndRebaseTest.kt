package xb.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.FrameParser
import xb.link.Amc
import xb.link.EcuLink
import xb.link.LiveTuning
import xb.link.Transport
import xb.tune.Cell
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 關視窗的防護、存檔後的基準，以及金鑰樣本的讀取上限。
 * 用自造的定義、單表與管線；不開網路，也不需要素材。
 */
class CloseAndRebaseTest {
    /** 表 20 有 256 列、每列 4 bytes：整包寫入會超過一塊，中途才有東西可以打斷。 */
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("xb-close-test").toFile()
        val defs = File(dir, "defs").apply { mkdirs() }
        val file = File(dir, "synthetic.xml")
        val values: Map<Int, List<LongArray>> = linkedMapOf(
            1 to listOf(longArrayOf(10)),
            20 to List(256) { longArrayOf(it.toLong()) },
            1001 to listOf(longArrayOf(2561)),
            1002 to listOf(longArrayOf(1)),
            1016 to listOf(longArrayOf(7)),
        )
        val widths = mapOf(1 to 1, 20 to 4, 1001 to 4, 1002 to 1, 1016 to 1)
        val state: AppState

        init {
            fun tinf(id: Int, x: Int, y: Int, cell: Int, max: String) =
                "$id,$x,$y,$cell,0,0,0,0,$max,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_"
            val shapes = mapOf(1 to tinf(1, 0, 0, 1, "255"), 20 to tinf(20, 1, 256, 4, "255"),
                1001 to tinf(1001, 0, 0, 4, "4294967295"), 1002 to tinf(1002, 0, 0, 1, "255"), 1016 to tinf(1016, 0, 0, 1, "255"))
            val tabs = shapes.entries.joinToString("") { (id, inf) ->
                """<Tab name="Synthetic$id" absid="$id"><Tinf>$inf</Tinf><Ver c_version="7" status="En"><Model name="Synthetic" mid="10">1</Model></Ver></Tab>"""
            }
            File(defs, "ControlFile_S3.xml").writeText("<ECUControlFile><Parameters>$tabs</Parameters></ECUControlFile>")
            File(defs, "ScaleData.xml").writeText("<ScaleData/>")
            val rows = values.entries.joinToString("") { (id, list) ->
                "<Table name=\"Synthetic$id\" absid=\"$id\">\n" + list.joinToString("\n") { it.joinToString(",") } + "\n</Table>"
            }
            file.writeText("<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue>$rows</TableValue></AMC_File>")
            state = AppState(Settings(File(dir, "settings.properties")), defs, File(dir, "no-keys"))
            state.logToFile = false
            state.ecuBackupDir = File(dir, "backups")
            assertTrue(state.open(file), state.error)
        }

        override fun close() { dir.deleteRecursively() }
    }

    /** 只有表、沒有監控串流的 ECU。 */
    private class Wire(private val rows: Map<Int, List<LongArray>>, private val widths: Map<Int, Int>) : Transport {
        private val input = Channel<ByteArray>(Channel.UNLIMITED)
        private val table = LinkedHashMap<Int, MutableMap<Int, ByteArray>>()
        val sent = ArrayList<Frame>()
        var cCode = 7
        var writes = 0
        var amcBlocks = 0
        var amcCompleted = false
        /** 這一塊的第一個資料幀先停住，讓測試從中要求關視窗。 */
        var pauseBlock: Int? = null
        /** 整包寫完後的讀回全部都失敗（驗證不通過）。 */
        var failAfterAmc = false
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var expectedBlocks = 1
        /** 目前收到第幾塊（只有每塊的第一幀帶著塊號）。 */
        private var currentBlock = 0

        init {
            for ((id, list) in rows) {
                val w = widths.getValue(id)
                val bySub = LinkedHashMap<Int, ByteArray>()
                for ((sub, row) in list.withIndex()) bySub[sub] = ByteArray(w) { k -> (row[0] ushr (8 * k)).toByte() }
                table[0x3000 + id] = bySub
            }
            reply(Frame(CanId.ecuHeartbeat(1), byteArrayOf(5)))
        }

        fun cell(absId: Int, sub: Int = 0): Long {
            val bytes = table.getValue(0x3000 + absId).getValue(sub)
            var v = 0L
            for (k in bytes.indices) v = v or ((bytes[k].toLong() and 0xFF) shl (8 * k))
            return v
        }

        private fun reply(f: Frame) { input.trySend(f.encode()) }

        private fun upload(f: Frame, bytes: ByteArray) {
            val data = byteArrayOf((0x43 or ((4 - bytes.size) shl 2)).toByte(), f.data[1], f.data[2], f.data[3]) + bytes + ByteArray(4 - bytes.size)
            reply(Frame(CanId.rpcReply(1), data))
        }

        private val parser = FrameParser { f ->
            sent += f
            when {
                f.canId == CanId.SYS && f.u8(0) == 0x88 -> reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x89.toByte(), 0)))
                f.canId == CanId.SYS && f.u8(0) == Amc.REQUEST -> {
                    val size = (f.u8(3) shl 8) or f.u8(4)
                    expectedBlocks = (size + Amc.BLOCK - 1) / Amc.BLOCK
                    reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x31, 0)))
                }
                f.canId == CanId.SYS && f.u8(0) == Amc.DATA && f.u8(1) == 1 -> currentBlock = f.u8(2)
                f.canId == CanId.SYS && f.u8(0) == Amc.DATA && f.u8(1) == 171 -> {
                    val block = currentBlock
                    val last = block == expectedBlocks - 1
                    amcBlocks = block + 1
                    if (last) amcCompleted = true
                    reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x33, block.toByte(), (if (last) 2 else 0).toByte())))
                }
                f.canId == CanId.rpc(1) && f.u8(0) == 0x40 -> {
                    val index = f.u8(1) or (f.u8(2) shl 8); val sub = f.u8(3)
                    when {
                        index == 0x1018 -> upload(f, byteArrayOf((if (sub == 8) cCode else 1).toByte()))
                        index == 0x1008 -> upload(f, byteArrayOf('S'.code.toByte()))
                        amcCompleted && failAfterAmc -> reply(Frame(CanId.rpcReply(1), byteArrayOf(0x80.toByte(), f.data[1], f.data[2], f.data[3], 0, 0, 2, 6)))
                        else -> upload(f, table[index]?.get(sub) ?: error("unexpected read: $index/$sub"))
                    }
                }
                f.canId == CanId.rpc(1) && f.u8(0) in setOf(0x23, 0x27, 0x2B, 0x2F) -> {
                    val index = f.u8(1) or (f.u8(2) shl 8); val sub = f.u8(3)
                    val n = 4 - ((f.u8(0) shr 2) and 3)
                    table.getOrPut(index) { LinkedHashMap() }[sub] = f.data.copyOfRange(4, 4 + n)
                    writes++
                    reply(Frame(CanId.rpcReply(1), byteArrayOf(0x60, f.data[1], f.data[2], f.data[3])))
                }
            }
        }

        /** 送出去的每一幀先看是不是要停住的那一幀，再交給上面那台 ECU。 */
        override suspend fun send(bytes: ByteArray) {
            var f: Frame? = null
            FrameParser { f = it }.feed(bytes)
            val frame = f
            if (frame != null && frame.canId == CanId.SYS && frame.u8(0) == Amc.DATA && frame.u8(1) == 1 && frame.u8(2) == pauseBlock) {
                pauseBlock = null
                paused.complete(Unit)
                release.await()
            }
            parser.feed(bytes)
        }

        override suspend fun receive(): ByteArray? = input.receiveCatching().getOrNull()

        override fun close() { input.close() }
    }

    private fun controller(f: Fixture, wire: Wire): EcuController = EcuController(f.state) { _, _, scope, log ->
        EcuLink(wire, scope, EcuLink.Timing(0, 0, 60_000, 300, 300, 300), log)
    }

    private suspend fun awaitReady(state: AppState) = withTimeout(10_000) {
        while (state.ecu is EcuPhase.Connecting || state.ecu is EcuPhase.Reading) delay(5)
    }

    private suspend fun close(ctrl: EcuController, scope: CoroutineScope, state: AppState) {
        ctrl.disconnect(scope)
        withTimeout(10_000) { while (state.ecu != null) delay(5) }
    }

    @Test fun closeRequestDuringMultiBlockTransferNeitherCancelsNorExits() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.values, f.widths); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertIs<EcuPhase.Live>(f.state.ecu)
                wire.pauseBlock = 1
                ctrl.saveToEcu(this)
                val writing = assertIs<EcuPhase.Writing>(f.state.ecu)
                withTimeout(10_000) { wire.paused.await() }
                assertEquals(1, wire.amcBlocks) // 第一塊寫完了，第二塊正在送
                var exited = false
                assertFalse(ctrl.requestClose(this) { exited = true })
                assertFalse(exited)
                assertIs<EcuPhase.Writing>(f.state.ecu)
                assertTrue(writing.live.link.open.value)
                assertEquals(tr("close.waitWriting"), f.state.message)
                wire.release.complete(Unit)
                withTimeout(30_000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertTrue(wire.amcCompleted)
                assertEquals(2, wire.amcBlocks)
                // 寫完而且驗證通過才真的關：連線收掉、回呼才發出
                withTimeout(10_000) { while (!exited) delay(5) }
                assertNull(f.state.ecu)
                assertFalse(writing.live.link.open.value)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun closeDuringAFailedTransferKeepsTheAppOpen() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.values, f.widths).apply { failAfterAmc = true }; val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertIs<EcuPhase.Live>(f.state.ecu)
                ctrl.saveToEcu(this)
                assertIs<EcuPhase.Writing>(f.state.ecu)
                var exited = false
                assertFalse(ctrl.requestClose(this) { exited = true })
                withTimeout(30_000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertIs<EcuPhase.Recovery>(f.state.ecu)
                delay(200)
                assertFalse(exited, "寫入失敗時不能關掉")
                assertEquals(tr("close.writeStopped"), f.state.message)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun closeAfterTheTransferStopsTheConnectionFirst() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.values, f.widths); val ctrl = controller(f, wire)
            ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
            val p = assertIs<EcuPhase.Live>(f.state.ecu)
            var exited = false
            assertTrue(ctrl.requestClose(this) { exited = true })
            withTimeout(10_000) { while (!exited) delay(5) }
            assertNull(f.state.ecu)
            assertFalse(p.link.open.value)
            assertNull(f.state.liveValues)
        }
    }

    @Test fun revertAfterSavingLiveTuningTargetsTheSavedFile() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.values, f.widths); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                val tune = f.state.tune!!
                f.state.select(1)
                f.state.setRaw(1, Cell(0, 0), 11, "synthetic")
                p.live.writeTable(1)
                assertEquals(11L, wire.cell(1))
                assertTrue(f.state.save())
                assertSame(tune, f.state.tune)
                assertFalse(f.state.hasChanges)
                val writes = wire.writes
                // 存完跟檔案一致：復原不動，也不會把舊的 10 排進 ECU
                f.state.revertTable()
                assertEquals(11L, tune.rows(1)!![0][0])
                assertEquals(writes, wire.writes)
                // 存檔後再改再復原：回到存檔的 11，不是檔案原本的 10
                f.state.setRaw(1, Cell(0, 0), 12, "synthetic")
                f.state.revertTable()
                assertEquals(11L, tune.rows(1)!![0][0])
                delay(1500) // 停手時間過了也不該送出任何東西
                assertEquals(writes, wire.writes)
                assertEquals(11L, wire.cell(1))
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun keySampleReadIsBounded() {
        val dir = Files.createTempDirectory("xb-key-sample").toFile()
        try {
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            val big = File(dir, "big.bin")
            big.outputStream().use { out -> var n = 0; while (n <= KeySample.LIMIT) { out.write(chunk); n += chunk.size } }
            val tooLarge = assertIs<KeySample.TooLarge>(KeySample.read(big))
            assertEquals(KeySample.LIMIT, tooLarge.limit)
            val small = File(dir, "small.bin").apply { writeText("QUJD") }
            assertEquals("QUJD", assertIs<KeySample.Bytes>(KeySample.read(small)).value.decodeToString())
            assertEquals(KeySample.Unreadable, KeySample.read(File(dir, "missing.bin")))
            assertEquals(KeySample.Unreadable, KeySample.read(null))
        } finally { dir.deleteRecursively() }
    }

    @Test fun oversizedEncryptedFileIsRejectedBeforeTheKeyPrompt() {
        Fixture().use { f ->
            val big = File(f.dir, "big.amx")
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            big.outputStream().use { out -> var n = 0; while (n <= KeySample.LIMIT) { out.write(chunk); n += chunk.size } }
            assertFalse(f.state.open(big))
            assertEquals(tr("keys.err.sampleTooLarge", KeySample.LIMIT / (1024 * 1024)), f.state.error)
            assertNull(f.state.keyPrompt?.sample)
        }
    }

    @Test fun liveWriteAfterSaveStillUsesTheSameTable() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.values, f.widths); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                f.state.select(1)
                f.state.setRaw(1, Cell(0, 0), 11, "synthetic")
                assertTrue(f.state.save())
                f.state.setRaw(1, Cell(0, 0), 12, "synthetic")
                p.live.writeTable(1)
                assertEquals(12L, wire.cell(1))
                assertEquals(LiveTuning.TableState.Synced, p.live.states.value[1])
            } finally { close(ctrl, this, f.state) }
        }
    }
}
