package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rx.protocol.CanId
import rx.protocol.DashFn
import rx.protocol.DashTable
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.Rpc

class WriteGatePublicTest {
    @Test fun rejectsCalibrationPasswordAndFirmwareWrites() {
        val gate = WriteGate(1)
        // A calibration address must remain blocked for every expedited command variant.
        for (command in listOf(0x23, 0x27, 0x2B, 0x2F)) {
            assertFalse(gate.allows(Frame(0x601, byteArrayOf(command.toByte(), 0x34, 0x12, 0, 1, 0, 0, 0))))
        }
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 1, 1, 2, 3))))
        assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x3C))))
        assertFalse(gate.allows(Frame(CanId.NMT, byteArrayOf(0x81.toByte(), 0))))
        assertTrue(gate.allows(Frame(CanId.NMT, byteArrayOf(1, 0))))
        assertTrue(gate.allows(Frame(0x601, byteArrayOf(0x40, 8, 0x10, 0, 0, 0, 0, 0))))
    }

    /** 儀表物件只收標準 1-byte expedited download（0x2F）；其他命令字（含舊 0x0X、segmented、bit4 設起）一律擋。 */
    @Test fun dashboardWritesAcceptOnlyTheStandardOneByteCommand() {
        for (p in EcuPlatform.entries) {
            val gate = WriteGate(p.node); val rpc = Rpc(p.node)
            val verified = p.node in DashTable.VERIFIED
            val objects = listOf(Rpc.MAP_SWITCH to 1) + DashFn.entries.map { rpc.dashIndex(it) to (if (it == DashFn.EB) 0xA5 else 1) }
            for ((idx, value) in objects) for (b0 in 0..0xFF) {
                val f = Frame(rpc.rpcId, byteArrayOf(b0.toByte(), idx.toByte(), (idx ushr 8).toByte(), 0, value.toByte(), 0, 0, 0))
                val download = (b0 ushr 5) == 1 || (b0 ushr 5) == 0
                if (!download) continue
                assertEquals(verified && b0 == 0x2F, gate.allows(f), "${p.label} 0x${idx.toString(16)} cmd 0x${b0.toString(16)}")
            }
        }
    }
}
