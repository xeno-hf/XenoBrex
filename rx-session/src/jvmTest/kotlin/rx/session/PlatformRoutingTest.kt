package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rx.protocol.CanId
import rx.protocol.DashFn
import rx.protocol.DashTable
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.Rpc

/** Public, synthetic protocol vectors; no vehicle data or external service required. */
class PlatformRoutingTest {
    @Test fun recognizesAllDeclaredNodesAndIgnoresAppHeartbeat() {
        val nodes = setOf(1, 2, 4, 5, 0x21, 0x22, 0x25)
        assertEquals(nodes, EcuPlatform.entries.map { it.node }.toSet())
        for (p in EcuPlatform.entries) {
            assertEquals(p, EcuPlatform.ofHeartbeat(0x700 or p.node))
            assertEquals(0x600 or p.node, Rpc(p.node).rpcId)
            assertEquals(0x580 or p.node, CanId.rpcReply(p.node))
            assertEquals(0x180 or p.node, CanId.broadcast(p.node))
        }
        assertNull(EcuPlatform.ofHeartbeat(CanId.HEARTBEAT_APP))
        assertNull(EcuPlatform.ofHeartbeat(0x77F))
        assertNull(EcuPlatform.ofHeartbeat(0x181))
    }

    @Test fun eachNodeKeepsTheSameDashboardOnlyWriteBoundary() {
        val verifiedPlatforms = setOf(EcuPlatform.MINI_X, EcuPlatform.SUPER_X, EcuPlatform.SUPER_X_PRO_RZR,
            EcuPlatform.MINI_XX, EcuPlatform.SUPER_XX)
        for (p in EcuPlatform.entries) {
            val rpc = Rpc(p.node)
            val gate = WriteGate(p.node)
            // 儀表鈕只放行已驗證的平台；沒驗過的平台連合法值也擋
            val verified = p in verifiedPlatforms
            for (map in 1..3) assertEquals(verified, gate.allows(rpc.mapSwitch(map)), p.label)
            for (fn in DashFn.entries) {
                val idx = rpc.dashIndex(fn)
                assertEquals(verified, gate.dashVerified(idx), "${p.label} $fn")
                assertEquals(verified, gate.allows(rpc.dashWrite(fn, 0)), "${p.label} $fn off")
                assertEquals(verified, gate.allows(rpc.dashWrite(fn, 1)), "${p.label} $fn on")
                assertFalse(gate.allows(rpc.smallWrite(idx, 0, 1, 2)), "${p.label} $fn width")
                assertTrue(gate.allows(rpc.smallRead(idx)), "${p.label} $fn read")
            }
            assertFalse(gate.allows(rpc.smallWrite(Rpc.MAP_SWITCH, 0, 4, 1)))
            assertFalse(gate.allows(rpc.smallWrite(0x301D, 0, 1, 1)))
            assertFalse(gate.allows(Frame(CanId.SYS, byteArrayOf(0x88.toByte(), 1, 1, 2, 3))))
            assertFalse(gate.allows(Frame(CanId.NMT, byteArrayOf(0x81.toByte(), 0))))
            assertTrue(gate.allows(rpc.monitorStart()))
            assertTrue(gate.allows(rpc.monitorStop()))
        }
    }

    @Test fun autoTuneObjectFollowsThePlatform() {
        // Super XX：AT 是 a1500（Auto_Tune_Pro）；舊的 a14 在這個平台不是儀表物件，寫入一律擋
        val sxx = Rpc(EcuPlatform.SUPER_XX.node); val sxxGate = WriteGate(EcuPlatform.SUPER_XX.node)
        assertEquals(DashTable.AT_PRO, sxx.dashIndex(DashFn.AT))
        assertTrue(sxxGate.allows(sxx.dashWrite(DashFn.AT, 1)))
        assertFalse(sxxGate.allows(sxx.smallWrite(DashFn.AT.index, 0, 1, 1)))
        // 其他平台：AT 是 a14；a1500 在那些平台是別的設定，寫入一律擋
        for (p in EcuPlatform.entries) if (p != EcuPlatform.SUPER_XX) {
            val rpc = Rpc(p.node)
            assertEquals(DashFn.AT.index, rpc.dashIndex(DashFn.AT), p.label)
            assertFalse(WriteGate(p.node).allows(rpc.smallWrite(DashTable.AT_PRO, 0, 1, 1)), p.label)
        }
        // 其餘五顆每個平台都一樣
        for (p in EcuPlatform.entries) for (fn in DashFn.entries) if (fn != DashFn.AT)
            assertEquals(fn.index, Rpc(p.node).dashIndex(fn), "${p.label} $fn")
    }
}
