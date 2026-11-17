package xb.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import rx.protocol.EcuPlatform
import xb.calui.CalHost
import xb.calui.D1
import xb.calui.Interlock
import xb.calui.PageMsg
import xb.calui.usable
import xb.link.LiveTuning
import xb.tune.CalItems
import xb.tune.Definitions
import xb.tune.ScaleData
import xb.tune.Tune
import xb.tune.TuneFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 功能互斥（xb-calui Interlock）與功能頁能不能開；合成的表與宿主，不連裝置。 */
class InterlockTest {
    private fun offs(p: EcuPlatform, id: Int) = Interlock.conflicts(p.node, id, 1L)!!.first

    /** 超級自動調校只在 Super XX（1500）與 SxS（1580）；Super X Pro 的 1500 是別的設定，開寬域閉迴路不能動它。 */
    @Test fun wideBandOnlyTurnsOffProAtWhereItExists() {
        assertTrue(1500 in offs(EcuPlatform.SUPER_XX, 1059))
        assertTrue(1580 in offs(EcuPlatform.SXS, 1059))
        assertFalse(1500 in offs(EcuPlatform.SXS, 1059))
        for (p in listOf(EcuPlatform.SUPER_X_PRO_RZR, EcuPlatform.SUPER_X_PRO_CYL4, EcuPlatform.MINI_X, EcuPlatform.SUPER_X)) {
            assertFalse(1500 in offs(p, 1059), "$p")
            assertEquals(listOf(60, 14), offs(p, 1059), "$p")
        }
    }

    @Test fun table1500IsNotProAtOnSuperXPro() {
        assertEquals(null, Interlock.conflicts(EcuPlatform.SUPER_X_PRO_RZR.node, 1500, 1L))
        assertEquals(listOf(60, 1059), Interlock.conflicts(EcuPlatform.SUPER_XX.node, 1500, 1L)!!.first)
    }

    private fun tune(values: Map<Int, Long>): Tune {
        fun tab(id: Int) = """<Tab name="T$id" absid="$id"><Tinf>$id,0,0,1,0,0,0,0,255,0,1,1,0,_,_,Others,0,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_,_</Tinf><Ver c_version="7" status="En"/></Tab>"""
        val defs = Definitions.parse("<ECUControlFile><Parameters>${values.keys.joinToString("") { tab(it) }}</Parameters></ECUControlFile>")
        val file = TuneFile.parse("""<AMC_File><MAP_Type>AMX</MAP_Type><V_Code>1</V_Code><C_Code>7</C_Code><A_Code>1</A_Code><Table_ID>00_00_10_01</Table_ID><LastSaveTime/><CarNote/><IO_Config/><TableValue>${values.entries.joinToString("") { (id, v) -> """<Table name="T$id" absid="$id">$v</Table>""" }}</TableValue></AMC_File>""")
        return Tune(defs, ScaleData("t", emptyMap()), file)
    }

    private class Host(
        override val tune: Tune,
        override val node: Int,
        override val online: Boolean,
        private val unreadable: Set<Int> = emptySet(),
    ) : CalHost {
        val written = ArrayList<Int>()
        override val cCode: Int? = 7
        override val fwCode: Int? = null
        override val lang = "en-US"
        override val revision = 0
        override fun has(absId: Int) = absId !in unreadable && tune.rows(absId) != null
        override fun writable(absId: Int) = has(absId)
        override fun status(absId: Int): LiveTuning.TableState? = null
        override fun held(absId: Int) = false
        override fun edited(absId: Int) {}
        override suspend fun readTables(ids: List<Int>): String? = null
        override suspend fun writeTables(ids: List<Int>): String? { written += ids; return null }
        override suspend fun command(index: Int, sub: Int, value: Long, bytes: Int): String? = "no"
        override suspend fun commandThenRead(index: Int, sub: Int, value: Long, bytes: Int, ids: List<Int>, settleMs: Long) = LiveTuning.CommandRead.notSent("no")
        override suspend fun readObject(index: Int, sub: Int): Result<ByteArray> = Result.failure(IllegalStateException())
        override suspend fun raw(canId: Int, data: ByteArray, reply: Int?, count: Int, timeoutMs: Long, match: (ByteArray) -> Boolean): Result<List<ByteArray>> = Result.failure(IllegalStateException())
        override fun watch(vars: List<Int>) {}
        override fun live(varId: Int): Double? = null
        override fun varLabel(varId: Int) = ""
        override fun tableTitle(absId: Int) = "T$absId"
        @Composable override fun TableEditor(ids: List<Int>, modifier: Modifier) {}
    }

    /** 線上、自動調校 14 開著但這次連線讀不到：開寬域閉迴路時確認不了 14 關著，不能開，也不能寫任何表。 */
    @Test fun unreadableActiveConflictBlocksTheSwitch() = runBlocking {
        val t = tune(mapOf(1059 to 0L, 60 to 0L, 14 to 1L, 42 to 0L))
        val host = Host(t, EcuPlatform.MINI_X.node, online = true, unreadable = setOf(14))
        val msg = PageMsg()
        Interlock.set(host, D1(host, 1059), 1, msg, CoroutineScope(Dispatchers.Default))
        withTimeout(5_000) { while (msg.text == null) delay(10) }
        assertTrue(host.written.isEmpty(), "${host.written}")
        assertEquals(0L, t.rows(1059)!![0][0], "寬域閉迴路不能開")
        assertTrue("T14" in msg.text!! || "unreadable" in msg.text!!, msg.text)
    }

    /** 燈效頁沒有表（0x689 訊框）：X2 平台連著 ECU 就能開，沒連就不行。 */
    @Test fun rgbLightingIsUsableOnConnectedX2() {
        val t = tune(mapOf(14 to 0L))
        for (p in listOf(EcuPlatform.MINI_XX, EcuPlatform.SUPER_XX, EcuPlatform.SXS)) {
            val item = CalItems.items(p.node).first { it.page == CalItems.Page.RgbLed }
            assertTrue(Host(t, p.node, online = true).usable(item), "$p")
            assertFalse(Host(t, p.node, online = false).usable(item), "$p offline")
        }
    }
}
