package xb.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.FrameParser
import xb.link.EcuLink
import xb.link.Transport
import xb.link.LiveTuning
import xb.tune.Cell
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 自造定義、單格表與管線；不需要原廠素材，也不開網路。 */
class EcuWriteSafetyTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("xb-write-test").toFile()
        val defs = File(dir, "defs").apply { mkdirs() }
        val tableValues = linkedMapOf(1 to 10L, 1001 to 2561L, 1002 to 1L, 1016 to 7L)
        val file = File(dir, "synthetic.xml")
        val state: AppState
        init {
            val tabs = tableValues.map { (id, _) ->
                """<Tab name="Synthetic$id" absid="$id"><Tinf>$id,0,0,${if (id == 1001) 4 else 1},0,0,0,0,${if (id == 1001) 4294967295 else 255},0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"><Model name="Synthetic" mid="10">1</Model></Ver></Tab>"""
            }.joinToString("")
            File(defs, "ControlFile_S3.xml").writeText("<ECUControlFile><Parameters>$tabs</Parameters></ECUControlFile>")
            File(defs, "ScaleData.xml").writeText("<ScaleData/>")
            val rows = tableValues.map { (id, v) -> "<Table name=\"Synthetic$id\" absid=\"$id\">$v</Table>" }.joinToString("")
            file.writeText("<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue>$rows</TableValue></AMC_File>")
            state = AppState(Settings(File(dir, "settings.properties")), defs, File(dir, "no-keys"))
            state.logToFile = false
            state.ecuBackupDir = File(dir, "backups")
            assertTrue(state.open(file), state.error)
        }
        override fun close() { dir.deleteRecursively() }
    }

    private class Wire(values: Map<Int, Long>) : Transport {
        private val input = Channel<ByteArray>(Channel.UNLIMITED)
        val rows = values.mapKeys { it.key + 0x3000 }.mapValues { (index, value) ->
            ByteArray(if (index == 0x3000 + 1001) 4 else 1) { (value ushr (8 * it)).toByte() }
        }.toMutableMap()
        val sent = ArrayList<Frame>()
        var cCode = 7
        var unreadable: Int? = null
        var failAfterAmc = false
        var failAfterNextWrite = false
        var amcCompleted = false
        var writes = 0
        init { reply(Frame(CanId.ecuHeartbeat(1), byteArrayOf(5))) }
        private fun reply(f: Frame) { input.trySend(f.encode()) }
        private fun upload(f: Frame, bytes: ByteArray) {
            val data = byteArrayOf((0x43 or ((4 - bytes.size) shl 2)).toByte(), f.data[1], f.data[2], f.data[3]) + bytes + ByteArray(4 - bytes.size)
            reply(Frame(CanId.rpcReply(1), data))
        }
        private val parser = FrameParser { f ->
            sent += f
            when {
                f.canId == CanId.SYS && f.u8(0) == 0x88 -> reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x89.toByte(), 0)))
                f.canId == CanId.SYS && f.u8(0) == 0x30 -> reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x31, 0)))
                f.canId == CanId.SYS && f.u8(0) == 0x32 && f.u8(1) == 171 -> {
                    amcCompleted = true
                    reply(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x33, 0, 2)))
                }
                f.canId == CanId.rpc(1) && f.u8(0) == 0x40 -> {
                    val index = f.u8(1) or (f.u8(2) shl 8); val sub = f.u8(3)
                    when {
                        index == 0x1018 -> upload(f, byteArrayOf((if (sub == 8) cCode else 1).toByte()))
                        index == 0x1008 -> upload(f, byteArrayOf('S'.code.toByte()))
                        index == unreadable || (amcCompleted && failAfterAmc) -> reply(Frame(CanId.rpcReply(1), byteArrayOf(0x80.toByte(), f.data[1], f.data[2], f.data[3], 0, 0, 2, 6)))
                        else -> upload(f, rows[index] ?: error("unexpected read: $index"))
                    }
                }
                f.canId == CanId.rpc(1) && f.u8(0) in setOf(0x23, 0x27, 0x2B, 0x2F) -> {
                    val index = f.u8(1) or (f.u8(2) shl 8)
                    val n = 4 - ((f.u8(0) shr 2) and 3)
                    rows[index] = f.data.copyOfRange(4, 4 + n)
                    writes++
                    if (failAfterNextWrite) { unreadable = index; failAfterNextWrite = false }
                    reply(Frame(CanId.rpcReply(1), byteArrayOf(0x60, f.data[1], f.data[2], f.data[3])))
                }
            }
        }
        override suspend fun send(bytes: ByteArray) { parser.feed(bytes) }
        override suspend fun receive(): ByteArray? = input.receiveCatching().getOrNull()
        override fun close() { input.close() }
    }

    private fun controller(f: Fixture, wire: Wire): EcuController {
        val ctrl = EcuController(f.state) { _, _, scope, log ->
            EcuLink(wire, scope, EcuLink.Timing(0, 0, 60_000, 300, 300, 300), log)
        }
        return ctrl
    }
    private suspend fun awaitReady(state: AppState) = withTimeout(5000) {
        while (state.ecu is EcuPhase.Connecting || state.ecu is EcuPhase.Reading) delay(5)
    }
    private suspend fun close(ctrl: EcuController, scope: CoroutineScope, state: AppState) {
        ctrl.disconnect(scope)
        withTimeout(5000) { while (state.ecu != null) delay(5) }
    }

    @Test fun backupFailureLeavesConnectionReadOnly() = runBlocking {
        Fixture().use { f ->
            val blocker = File(f.dir, "not-a-directory").apply { writeText("synthetic") }
            f.state.ecuBackupDir = File(blocker, "backups")
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.ReadOnly>(f.state.ecu)
                assertFalse(p.link.calibrationEnabled)
                assertEquals(null, ctrl.live)
                ctrl.saveToEcu(this)
                assertEquals(0, wire.writes)
                assertTrue(wire.sent.none { it.canId == CanId.SYS && it.u8(0) == 0x30 })
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun incompatibleIdentityLeavesConnectionReadOnly() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues).apply { cCode = 8 }; val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertFalse(assertIs<EcuPhase.ReadOnly>(f.state.ecu).link.calibrationEnabled)
                assertEquals(0, wire.writes)
                assertFalse(f.state.ecuBackupDir.exists())
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun incompleteReadBackNeverCreatesFallbackBackupOrEnablesWrites() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues).apply { unreadable = 0x3001 }; val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertFalse(assertIs<EcuPhase.ReadOnly>(f.state.ecu).link.wholeImageEnabled)
                assertFalse(f.state.ecuBackupDir.exists())
                assertEquals(0, wire.writes)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun savedLiveTuneRemainsTheEditorsWriteSource() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                p.backup.verify()
                val tune = f.state.tune!!
                f.state.setRaw(1, Cell(0, 0), 11, "synthetic")
                p.live.writeTable(1)
                assertTrue(f.state.save())
                assertSame(tune, f.state.tune)
                assertFalse(f.state.hasChanges)
                f.state.setRaw(1, Cell(0, 0), 12, "synthetic")
                p.live.writeTable(1)
                assertEquals(12, wire.rows.getValue(0x3001)[0].toInt())
                assertEquals(LiveTuning.TableState.Synced, p.live.states.value[1])
                assertTrue(f.state.hasChanges)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun unreadableAmcVerificationEntersRecoveryAndDiscardsLiveCache() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues).apply { failAfterAmc = true }; val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                ctrl.saveToEcu(this)
                assertTrue(f.state.editingFrozen)
                f.state.setRaw(1, Cell(0, 0), 99, "must be frozen")
                assertEquals(10L, f.state.tune!!.rows(1)!![0][0])
                // 寫完先等 2 秒再讀回，讀不到的表每張重試（Amc.write settleMs／readAttempts），全讀不到要比 10 秒久
                withTimeout(60_000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertIs<EcuPhase.Recovery>(f.state.ecu)
                assertTrue(p.live.closed)
                assertFalse(p.link.open.value)
                assertFalse(p.link.calibrationEnabled)
                assertEquals(null, ctrl.live)
                assertEquals(null, f.state.liveValues)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun restoreReadBackFailureIsNotReportedAsRestored() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                f.state.setRaw(1, Cell(0, 0), 11, "synthetic")
                p.live.writeTable(1)
                wire.failAfterNextWrite = true
                ctrl.restore(this, p.backup.file)
                withTimeout(5000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertIs<EcuPhase.Recovery>(f.state.ecu)
                assertFalse(p.link.calibrationEnabled)
                assertTrue(p.live.closed)
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun restoreRejectsUnverifiedFileWithoutSendingWrites() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertIs<EcuPhase.Live>(f.state.ecu)
                val unverified = File(f.dir, "unverified.xml").apply { writeText("not the verified backup") }
                ctrl.restore(this, unverified)
                assertIs<EcuPhase.Live>(f.state.ecu)
                assertEquals(0, wire.writes)
                assertTrue(f.state.error != null)
            } finally { close(ctrl, this, f.state) }
        }
    }
    @Test fun changedIdentityIsRecheckedBeforeAmcRequest() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                assertIs<EcuPhase.Live>(f.state.ecu)
                wire.cCode = 8
                ctrl.saveToEcu(this)
                withTimeout(5000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertIs<EcuPhase.Recovery>(f.state.ecu)
                assertTrue(wire.sent.none { it.canId == CanId.SYS && it.u8(0) == 0x30 })
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun tamperedBackupCannotEnableWholeImageWrite() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                p.backup.file.appendText("tampered")
                ctrl.saveToEcu(this)
                withTimeout(5000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                assertIs<EcuPhase.Recovery>(f.state.ecu)
                assertFalse(p.link.calibrationEnabled)
                assertTrue(wire.sent.none { it.canId == CanId.SYS && it.u8(0) == 0x30 })
            } finally { close(ctrl, this, f.state) }
        }
    }

    @Test fun refusedTableIsNotWrittenByRestoreOrAmc() = runBlocking {
        Fixture().use { f ->
            val wire = Wire(f.tableValues); val ctrl = controller(f, wire)
            try {
                ctrl.connect(this, "synthetic", 0, 888888); awaitReady(f.state)
                val p = assertIs<EcuPhase.Live>(f.state.ecu)
                p.link.revokeCalibrationObject(0x3001)
                f.state.setRaw(1, Cell(0, 0), 12, "refused table edit")
                ctrl.restore(this, p.backup.file)
                withTimeout(5000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                val restored = assertIs<EcuPhase.Live>(f.state.ecu)
                assertEquals(0, wire.writes)
                assertFalse(restored.live.writable(1))
                ctrl.saveToEcu(this)
                withTimeout(10_000) { while (f.state.ecu is EcuPhase.Writing) delay(5) }
                val saved = assertIs<EcuPhase.Live>(f.state.ecu)
                assertFalse(saved.live.writable(1))
                assertEquals(10L, f.state.tune!!.rows(1)!![0][0])
                assertEquals(0, wire.writes)
                assertTrue(wire.amcCompleted)
            } finally { close(ctrl, this, f.state) }
        }
    }

}
