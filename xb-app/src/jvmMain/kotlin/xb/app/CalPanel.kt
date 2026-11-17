package xb.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import xb.calui.CalColors
import xb.calui.CalHost
import xb.calui.CalPage
import xb.calui.Pill
import xb.link.LiveTuning
import xb.tune.CalCommands
import xb.tune.CalItems
import xb.tune.Tune

/**
 * 功能專頁（xb-calui，跟 ReX 同一份畫面）放在編輯區：XB 這邊提供調校檔、即時寫入（[EcuController]）、監控值與表格編輯器。
 * 沒連 ECU 時改的是檔案；命令類的頁（暖車、怠速、TPS 學習…）要連上才能用。
 */
@Composable
fun CalPanel(state: AppState, item: CalItems.Item, clipboard: ClipboardBridge, modifier: Modifier = Modifier) {
    if (state.editingFrozen) { Text(tr("ecu.editingFrozen"), modifier = modifier); return }
    val scope = rememberCoroutineScope()
    val live = (state.ecu as? EcuPhase.Live)
    val states = (live?.live?.states ?: EMPTY_STATES).collectAsState()
    val values = (state.liveValues ?: EMPTY_VALUES).collectAsState()
    val host = remember(state.session, live) { XbCalHost(state, live, scope, clipboard, states, values) }
    Column(modifier) {
        CalPage(host, item, xbCalColors()) { state.calItem = null }
    }
}

private val EMPTY_STATES: StateFlow<Map<Int, LiveTuning.TableState>> = MutableStateFlow(emptyMap())
private val EMPTY_VALUES: StateFlow<Map<Int, Double>> = MutableStateFlow(emptyMap())

fun xbCalColors() = CalColors(
    bg = Xb.bg, card = Xb.panel, line = Xb.line, text = Xb.text, muted = Xb.dim, brand = Xb.brand, onBrand = Xb.onBrand,
    accent = Xb.accent, warn = Xb.warn, danger = Xb.bad, ok = Xb.accent, digits = Xb.digits,
)

private class XbCalHost(
    private val state: AppState,
    private val phase: EcuPhase.Live?,
    private val scope: CoroutineScope,
    private val clipboard: ClipboardBridge,
    private val states: State<Map<Int, LiveTuning.TableState>>,
    private val values: State<Map<Int, Double>>,
) : CalHost {
    override val tune: Tune get() = state.tune!!
    override val node: Int get() = phase?.link?.node?.value ?: state.featureNode ?: 1
    override val cCode: Int? get() = state.tune?.file?.cCode
    override val fwCode: Int? get() = null
    override val lang: String get() = calLang()
    private val active: Boolean get() = phase != null && state.ecu === phase && phase.link.calibrationEnabled && !phase.live.closed
    override val online: Boolean get() = active
    override val revision: Int get() = state.revision + states.value.hashCode()

    override fun has(absId: Int): Boolean {
        val t = state.tune ?: return false
        if (t.definition(absId) == null || t.rows(absId) == null) return false
        return phase == null || phase.live.ecuRows(absId) != null
    }

    override fun writable(absId: Int): Boolean = !state.editingFrozen && if (phase != null) active && phase.live.writable(absId) else state.tune?.definition(absId) != null
    override fun status(absId: Int): LiveTuning.TableState? = states.value[absId]

    override fun edited(absId: Int) { state.ecuControl.edited(absId); state.contentChanged() }

    override suspend fun readTables(ids: List<Int>): String? {
        val p = phase?.takeIf { active } ?: return tr("ecu.readOnly")
        val l = p.live
        return state.ecuControl.liveOperation(p) {
            for (id in ids) {
                val rows = l.readTable(id).getOrElse { return@liveOperation "${state.title(tune.definition(id)!!)}: ${it.message}" }
                tune.loadRows(id, rows, undoable = true)
            }
            state.contentChanged()
            null
        }
    }

    override suspend fun writeTables(ids: List<Int>): String? {
        if (phase == null) return if (state.editingFrozen) tr("ecu.editingFrozen") else null
        if (!active) return tr("ecu.readOnly")
        val l = phase.live
        return state.ecuControl.liveOperation(phase) {
            for (id in ids) {
                l.writeTable(id)
                when (val st = l.states.value[id]) {
                    null, LiveTuning.TableState.Synced -> Unit
                    else -> return@liveOperation liveDetail(st) ?: liveBadge(st)?.first ?: st.toString()
                }
            }
            null
        }
    }

    override suspend fun command(index: Int, sub: Int, value: Long, bytes: Int): String? {
        val link = phase?.takeIf { active }?.link ?: return xb.calui.CalText.get("cal_need_online", calLang())
        if (!CalCommands.writeAllowed(index, sub, value, bytes)) return "command not allowed"
        val r = state.ecuControl.liveOperation(phase) { link.calCommand(CalCommands.sdoFrame(index, sub, value, bytes)) }
        state.ecuLog("Cal command 0x${index.toString(16)}/$sub=0x${value.toString(16)}: ${r ?: "ACK"}")
        return r
    }

    override suspend fun readObject(index: Int, sub: Int): Result<ByteArray> {
        val link = phase?.takeIf { active }?.link ?: return Result.failure(IllegalStateException(xb.calui.CalText.get("cal_need_online", calLang())))
        if (!CalCommands.readAllowed(index, sub)) return Result.failure(IllegalArgumentException("read not allowed"))
        return runCatching { link.read(index, sub, block = false) }
    }

    override suspend fun raw(canId: Int, data: ByteArray, reply: Int?, count: Int, timeoutMs: Long, match: (ByteArray) -> Boolean): Result<List<ByteArray>> {
        val p = phase?.takeIf { active } ?: return Result.failure(IllegalStateException(xb.calui.CalText.get("cal_need_online", calLang())))
        return runCatching { state.ecuControl.liveOperation(p) { p.link.calRaw(canId, data, reply, count, timeoutMs, match) } }
    }

    override fun watch(vars: List<Int>) { state.ecuControl.calWatch(vars.map { if (it < 0) -it / 10 else it }.distinct()) }

    override fun live(varId: Int): Double? {
        if (varId < 0) return values.value[-varId / 10]?.let { ((it.toLong() shr (-varId % 10)) and 1L).toDouble() }
        return values.value[varId]
    }

    override fun varLabel(varId: Int): String {
        val ch = state.ecuControl.registry()?.values?.get(if (varId < 0) -varId / 10 else varId) ?: return "#$varId"
        return ch.label + if (ch.unit.isNotBlank() && ch.unit != "NA") " ${ch.unit}" else ""
    }

    override fun tableTitle(absId: Int): String = state.tune?.definition(absId)?.let { state.title(it) } ?: "#$absId"

    @Composable
    override fun TableEditor(ids: List<Int>, modifier: Modifier) {
        LaunchedEffect(ids) { if (ids.isNotEmpty() && state.selectedTable !in ids) state.select(ids.first()) }
        Column(modifier) {
            if (ids.size > 1) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in ids) Pill(tableTitle(id), on = state.selectedTable == id, modifier = Modifier.testTag("calTable.$id")) { state.select(id) }
            }
            xb.app.TableEditor(state, clipboard, Modifier.weight(1f))
        }
    }
}
