package xb.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rx.defs.ChannelDef
import rx.defs.ChannelRegistry
import rx.protocol.BroadcastReassembler
import rx.protocol.ChannelTable

/**
 * 即時監控：在連著的 [EcuLink] 上開 ECU 的監控串流，只串要用的那幾個通道（準星的軸、四角的值）。
 * - 頻道表的寫入序列：monitorStop → 各格 → 數量 → monitorStart，每一幀等 ACK。
 * - 串流停了 [lossMs] 就重送 monitorStart；再停就整張頻道表重寫（ReX 的重新武裝規則）。
 * - 跟即時調校的 SDO 讀寫共用同一條線，互斥由 [EcuLink] 負責；串流本身不佔 RPC。
 * [values]：通道 absid → 物理值。還沒收到過的通道不在表裡。
 */
class LiveMonitor(
    private val link: EcuLink,
    private val registry: ChannelRegistry,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val lossMs: Long = 2_000,
    private val nowMs: () -> Long = { startMark.elapsedNow().inWholeMilliseconds },
) {
    private val _values = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val values: StateFlow<Map<Int, Double>> = _values

    /** 每秒完整輪數（指數平均）；0＝還沒收到。 */
    private val _hz = MutableStateFlow(0.0)
    val hz: StateFlow<Double> = _hz

    private val lock = Mutex()
    private var table: ChannelTable? = null
    private var defs: List<ChannelDef> = emptyList()
    private var reassembler: BroadcastReassembler? = null
    private var supervisor: Job? = null
    @kotlin.concurrent.Volatile private var lastRound = 0L

    /** 能串的通道（值通道、韌體有）。名錄裡沒有的 absid 不收。 */
    fun channel(absid: Int): ChannelDef? = registry.values[absid]

    /**
     * 開始（或改成）串 [absids] 這幾個通道。重複的、名錄裡沒有的略過；一個都沒有就停掉。
     * 回傳頻道表沒收 ACK 的幀數（0＝全部收下）。
     */
    suspend fun watch(absids: Collection<Int>): Int = lock.withLock {
        val wanted = absids.distinct().mapNotNull { registry.values[it] }
        if (wanted.isEmpty()) { stopLocked(); return@withLock 0 }
        val next = ChannelTable(wanted.map { ChannelTable.Entry(it.absid, it.cellSize) })
        if (next.entries == table?.entries && supervisor?.isActive == true) return@withLock 0
        defs = wanted
        table = next
        val r = BroadcastReassembler(next.payloadLength) { round -> onRound(round) }
        reassembler = r
        link.onBroadcast = { f -> r.canId = f.canId; r.feed(f) }
        val miss = writeTable(next)
        log("Live monitor: ${wanted.size} channels (${wanted.joinToString(",") { it.name }}), ${next.payloadLength} B/round, no ACK $miss")
        lastRound = nowMs()
        if (supervisor?.isActive != true) supervisor = scope.launch { supervise() }
        miss
    }

    private suspend fun writeTable(t: ChannelTable): Int {
        var miss = 0
        for (f in t.writeSequence(link.rpcFrames)) if (!link.ackedWrite(f)) miss++
        return miss
    }

    private fun onRound(r: BroadcastReassembler.Round) {
        val t = table ?: return
        val raw = (if (r.complete) t.unpack(r.payload) else null) ?: return
        val now = nowMs()
        val dt = (now - lastRound).coerceAtLeast(1)
        _hz.value = (1000.0 / dt).let { if (_hz.value == 0.0) it else _hz.value * 0.9 + it * 0.1 }
        lastRound = now
        val ds = defs
        val out = HashMap<Int, Double>(ds.size)
        for (i in ds.indices) out[ds[i].absid] = ds[i].physical(raw[i])
        _values.value = out
    }

    private suspend fun supervise() {
        var restarts = 0
        while (link.open.value) {
            delay(lossMs / 2)
            if (nowMs() - lastRound < lossMs) { restarts = 0; continue }
            val t = table ?: return
            _hz.value = 0.0
            if (restarts == 0) {
                log("Live monitor: stream lost >$lossMs ms, resending start")
                runCatching { link.ackedWrite(link.rpcFrames.monitorStart()) }
            } else {
                log("Live monitor: still no stream, rewriting the channel table")
                runCatching { lock.withLock { writeTable(t) } }
            }
            restarts = (restarts + 1) % 2
            lastRound = nowMs()
        }
    }

    /** 停掉串流（送 monitorStop）。連線本身不關。 */
    suspend fun stop() = lock.withLock { stopLocked() }

    private suspend fun stopLocked() {
        supervisor?.cancelAndJoin(); supervisor = null
        link.onBroadcast = null
        if (table != null && link.open.value) runCatching { link.ackedWrite(link.rpcFrames.monitorStop()) }
        table = null; reassembler = null; defs = emptyList()
        _values.value = emptyMap(); _hz.value = 0.0
    }

    private companion object {
        val startMark = kotlin.time.TimeSource.Monotonic.markNow()
    }
}
