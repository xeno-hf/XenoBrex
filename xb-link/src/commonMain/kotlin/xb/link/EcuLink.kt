package xb.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import rx.protocol.ChannelTable
import rx.protocol.CanId
import rx.protocol.EcuPlatform
import rx.protocol.Frame
import rx.protocol.FrameParser
import rx.protocol.IOException
import rx.protocol.Rpc
import rx.protocol.RpcReply
import rx.protocol.Sdo
import rx.protocol.SdoDownload
import rx.protocol.SdoPort
import rx.protocol.SdoUpload
import rx.protocol.hex

/** 位元組管線（TCP 6666）。 */
interface Transport {
    suspend fun send(bytes: ByteArray)
    /** 收一塊資料；null＝對端關閉。 */
    suspend fun receive(): ByteArray?
    fun close()
}

/**
 * 一條連到 ECU 的線：握手、登入、讀寫物件字典與監控串流。
 * 登入 0x680{88 00 pw} 等 0x6A0 → NMT Start。之後每秒送 0x70A 心跳。
 * 平台從 ECU 心跳 0x700|node 判斷；RPC 走 0x600|node。
 * 每次讀寫都互斥。校正寫入只放行這次連線備份後授權的表；系統與監控幀各有白名單。
 */
class EcuLink(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val log: (String) -> Unit = {},
) : SdoPort {
    data class Timing(
        val stepMs: Long = 80,
        val sessionOpenMs: Long = 200,
        val heartbeatMs: Long = 1000,
        val platformWaitMs: Long = 1500,
        val loginTimeoutMs: Long = 600,
        val replyTimeoutMs: Long = 1500,
    )

    /** 判斷到的 CANopen node（＝XTune deviceType）；還沒聽到心跳是 null。 */
    private val _node = MutableStateFlow<Int?>(null)
    val node: StateFlow<Int?> = _node
    val platform: EcuPlatform? get() = _node.value?.let { EcuPlatform.ofNode(it) }

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    /** 對端關閉或讀取出錯後變 false。 */
    private val _open = MutableStateFlow(true)
    val open: StateFlow<Boolean> = _open

    private val replies = Channel<Frame>(Channel.UNLIMITED)
    private val sendLock = Mutex()
    private val rpcLock = Mutex()
    private val operationLock = Mutex()
    private val trafficEpoch = MutableStateFlow(0L)
    private val amcActive = MutableStateFlow(false)
    private class Operation(val link: EcuLink) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Operation>
        var active = true
    }
    private data class Authorization(val generation: Long, val objects: Set<Int>, val complete: Boolean)
    private val authorization = MutableStateFlow<Authorization?>(null)
    private val epoch = MutableStateFlow(0L)
    private val revoked = MutableStateFlow<Set<Int>>(emptySet())
    val refusedCalibrationObjects: Set<Int> get() = revoked.value
    val generation: Long get() = epoch.value
    val calibrationEnabled: Boolean get() = authorization.value?.let {
        it.generation == generation && _open.value && _loggedIn.value && _node.value != null
    } == true
    val wholeImageEnabled: Boolean get() = calibrationEnabled && authorization.value?.complete == true

    /** 呼叫端已持久保存並逐 byte 驗證備份後，才可授權這次連線的表。 */
    fun enableCalibration(objects: Set<Int>, completeSnapshot: Boolean = false, expectGeneration: Long = generation): Boolean {
        require(objects.isNotEmpty() && objects.all { it in EcuSync.TABLE_BASE until EcuSync.TABLE_BASE + 0x1000 }) { "only calibration table objects are allowed" }
        if (expectGeneration != generation || !_open.value || !_loggedIn.value || _node.value == null) return false
        authorization.value = Authorization(expectGeneration, objects.toSet(), completeSnapshot)
        if (expectGeneration != generation || !_open.value || !_loggedIn.value) { authorization.value = null; return false }
        return true
    }

    fun disableCalibration() { authorization.value = null }

    /** ECU 已拒絕的表在這次連線不再走列寫入；整包寫入也必須保留它的現值。 */
    fun revokeCalibrationObject(index: Int) {
        revoked.value = revoked.value + index
        authorization.value = authorization.value?.let { it.copy(objects = it.objects - index) }
    }

    /** 整個操作共用一把鎖；整包寫入連同讀回驗證都要放在這裡。 */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        val owner = coroutineContext[Operation]
        if (owner?.link === this && owner.active) return block()
        return operationLock.withLock {
            val token = Operation(this)
            try { withContext(token) { block() } } finally { token.active = false }
        }
    }

    private suspend fun <T> withRpc(block: suspend () -> T): T {
        val expected = trafficEpoch.value
        return exclusive {
            check(expected == trafficEpoch.value) { "operation superseded by whole-image write" }
            rpcLock.withLock { block() }
        }
    }

    /** 停止其他操作後，整包寫入與完整驗證共用同一把鎖。 */
    suspend fun <T> amcExclusive(block: suspend () -> T): T = exclusive {
        if (amcActive.value) return@exclusive block()
        amcActive.value = true
        trafficEpoch.value++
        try { block() } finally { trafficEpoch.value++; amcActive.value = false }
    }

    private fun requireCalibration(): Long {
        check(calibrationEnabled) { "calibration writes are off (a verified backup is required)" }
        return generation
    }

    private fun invalidate() {
        disableCalibration()
        revoked.value = emptySet()
        epoch.value = epoch.value + 1
        _loggedIn.value = false
    }
    private var reader: Job? = null
    private var heartbeat: Job? = null
    private var rpc = Rpc(1)

    private val parser = FrameParser { onRx(it) }
    val parserStats: FrameParser.Stats get() = parser.stats

    /** 監控串流（0x180|node）的每一幀交給它（[LiveMonitor] 用）。在收資料的協程裡呼叫，不要做重的事。 */
    var onBroadcast: ((Frame) -> Unit)? = null

    /** 目前 node 的 RPC 物件（頻道表、監控開關的幀用它組）。 */
    val rpcFrames: Rpc get() = rpc

    private fun onRx(f: Frame) {
        EcuPlatform.ofHeartbeat(f.canId)?.let { p ->
            if (_node.value != p.node) {
                invalidate()
                _node.value = p.node; rpc = Rpc(p.node)
                log("ECU: ${p.label} (node 0x${p.node.hex(2)})")
            }
            return
        }
        if (f.canId == CanId.HEARTBEAT_APP) return
        if (f.canId == CanId.broadcast(_node.value ?: 1)) { onBroadcast?.invoke(f); return }
        replies.trySend(f)
    }

    private suspend fun send(f: Frame, writeGeneration: Long? = null) = sendLock.withLock {
        if (writeGeneration != null) check(calibrationEnabled && writeGeneration == generation) { "calibration connection changed" }
        transport.send(f.encode())
    }

    /**
     * 開線：握手、（有密碼就）登入、NMT Start。回傳登入結果文字（"ok"、"no reply"、"rejected (status n)"、"skipped"）。
     * 沒聽到 ECU 心跳就當 Mini X（node 1，ReX 的預設）。
     */
    suspend fun connect(password: Int?): String {
        check(_open.value && reader == null) { "connection already used" }
        invalidate()
        reader = scope.launch {
            try {
                while (true) {
                    val chunk = transport.receive() ?: break
                    parser.feed(chunk)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("receive failed: ${e.message}")
            } finally {
                invalidate()
                _open.value = false
            }
        }
        heartbeat = scope.launch { while (true) { runCatching { send(rpc.heartbeat()) }; delay(timing.heartbeatMs) } }
        withTimeoutOrNull(timing.platformWaitMs) { while (_node.value == null) delay(50) }
        if (_node.value == null) log("No ECU heartbeat yet; assuming node 0x01")

        return withRpc {
            send(rpc.sessionOpen()); delay(timing.sessionOpenMs)
            for (f in rpc.handshake()) { send(f); delay(timing.stepMs) }
            drain()
            val result = if (password == null) "skipped" else {
                send(rpc.login(password))
                val r = await(timing.loginTimeoutMs) { it.canId == CanId.LOGIN_REPLY }
                when {
                    r == null -> "no reply"
                    r.dlc >= 2 && r.u8(1) == 0 -> "ok"
                    else -> "rejected (status ${if (r.dlc >= 2) r.u8(1) else -1})"
                }
            }
            _loggedIn.value = result == "ok"
            log("Login: $result")
            send(rpc.nmtStart()); delay(timing.stepMs)
            result
        }
    }

    suspend fun close() {
        invalidate()
        _open.value = false
        heartbeat?.cancelAndJoin(); heartbeat = null
        transport.close()
        reader?.cancelAndJoin(); reader = null
        _open.value = false
        _loggedIn.value = false
    }

    private fun drain() { while (replies.tryReceive().isSuccess) { } }

    private suspend fun await(timeoutMs: Long, match: (Frame) -> Boolean): Frame? =
        withTimeoutOrNull(timeoutMs) {
            var hit: Frame? = null
            while (hit == null) replies.receive().let { if (match(it)) hit = it }
            hit
        }

    private fun replyId() = CanId.rpcReply(_node.value ?: 1)

    /**
     * 系統通道 0x680：依序送 [frames]（每幀間隔 [paceMs]），再等第一個符合 [match] 的 0x6A0 回應；逾時回 null。
     * AMC 校正寫入（[Amc]）用。跟 SDO 讀寫互斥。
     */
    internal suspend fun sysExchange(frames: List<Frame>, paceMs: Long, timeoutMs: Long, match: (Frame) -> Boolean): Frame? = withRpc {
        check(_open.value) { "not connected" }
        val gen = requireCalibration()
        check(wholeImageEnabled) { "whole-image writes require a complete verified backup" }
        check(amcActive.value) { "system writes require an exclusive whole-image operation" }
        require(amcFramesAllowed(frames)) { "system frames not allowed" }
        drain()
        for (f in frames) { send(f, gen); if (paceMs > 0) delay(paceMs) }
        await(timeoutMs) { it.canId == CanId.LOGIN_REPLY && match(it) }
    }

    /**
     * 送一個已經組好的 expedited 寫入（頻道表、監控開關），等 ECU 回 0x60；被 abort 或逾時回 false。
     * 跟 SDO 讀寫互斥。做法同 ReX `Session.sendAwaitAck`（逾時重送 [retries] 次）。
     */
    internal suspend fun ackedWrite(f: Frame, retries: Int = 2): Boolean = withRpc {
        check(_open.value) { "not connected" }
        check(!amcActive.value) { "whole-image operation in progress" }
        require(f.flags == 0 && monitorFrameAllowed(f)) { "monitor frame not allowed" }
        val addr = f.u8(1) or (f.u8(2) shl 8)
        val sub = f.u8(3)
        repeat(retries + 1) {
            drain()
            send(f)
            val r = await(timing.replyTimeoutMs) {
                it.canId == replyId() && when (val c = RpcReply.classify(it)) {
                    is RpcReply.WriteAck -> c.addr == addr && c.sub == sub
                    is RpcReply.Nack -> c.abort.index == addr && c.abort.sub == sub
                    else -> false
                }
            }
            if (r != null) return@withRpc RpcReply.classify(r) is RpcReply.WriteAck
        }
        false
    }

    /**
     * 功能專頁的命令（自動暖車、怠速調整、TPS 學習、噴嘴校正、保養資訊）：[data] 是 [xb.tune.CalCommands.sdoFrame] 組好的 8 bytes，
     * 不在 [xb.tune.CalCommands] 清單裡就不送。等 ECU 回 0x60；回 null＝收下，否則是原因（abort 原碼、逾時）。不重送。
     */
    suspend fun calCommand(data: ByteArray): String? = withRpc {
        if (!_open.value) return@withRpc "not connected"
        if (!xb.tune.CalCommands.frameAllowed(data)) return@withRpc "command not allowed"
        if (!calibrationEnabled || amcActive.value) return@withRpc "calibration writes are off"
        val gen = requireCalibration()
        val f = Frame(rpc.rpcId, data)
        val addr = f.u8(1) or (f.u8(2) shl 8); val sub = f.u8(3)
        drain()
        send(f, gen)
        val r = await(timing.replyTimeoutMs * 3) {
            it.canId == replyId() && when (val c = RpcReply.classify(it)) {
                is RpcReply.WriteAck -> c.addr == addr && c.sub == sub
                is RpcReply.Nack -> c.abort.index == addr && c.abort.sub == sub
                else -> false
            }
        } ?: return@withRpc "no reply"
        when (val c = RpcReply.classify(r)) { is RpcReply.WriteAck -> null; is RpcReply.Nack -> c.abort.toString(); else -> "unexpected reply" }
    }

    /**
     * 功能專頁的廠商訊框（ECU 燈效 0x689、每缸 AFR 0x680 `62`）：只送 [xb.tune.CalCommands.rawAllowed] 放行的。
     * [reply]＝要等的回應 CAN id；收到 [count] 筆符合 [match] 的就回傳資料；[reply] 為 null 就不等。
     */
    suspend fun calRaw(canId: Int, data: ByteArray, reply: Int?, count: Int, timeoutMs: Long, match: (ByteArray) -> Boolean): List<ByteArray> = withRpc {
        check(_open.value) { "not connected" }
        check(!amcActive.value) { "whole-image operation in progress" }
        require(xb.tune.CalCommands.rawAllowed(canId, data)) { "frame not allowed" }
        val gen = requireCalibration()
        drain()
        send(Frame(canId, data), gen)
        if (reply == null) return@withRpc emptyList()
        val out = ArrayList<ByteArray>()
        withTimeoutOrNull(timeoutMs) {
            while (out.size < count) replies.receive().let { if (it.canId == reply && match(it.data)) out += it.data }
        }
        if (out.size < count) throw IOException("0x${canId.hex(3)}: ${out.size}/$count replies in $timeoutMs ms")
        out
    }

    /**
     * 讀一個物件（SDO upload）。[block]＝true 走區塊讀（XTune 讀表的方式），false 走一般讀。
     * ECU abort 時丟 [SdoAbortException]，逾時丟 [IOException]。
     */
    override suspend fun read(index: Int, sub: Int, block: Boolean): ByteArray = withRpc {
        check(_open.value) { "not connected" }
        val gen = generation
        val up = SdoUpload(rpc.rpcId, index, sub, block)
        val rpcId = rpc.rpcId
        var finished = false
        drain()
        try {
            send(up.start())
            while (true) {
                val f = await(timing.replyTimeoutMs) { it.canId == replyId() }
                    ?: throw IOException("read 0x${index.hex(4)}/$sub: no reply in ${timing.replyTimeoutMs} ms")
                check(gen == generation && _open.value) { "ECU connection changed" }
                when (val st = up.onReply(f)) {
                    is SdoUpload.Step.Send -> st.frames.forEach { send(it) }
                    SdoUpload.Step.Wait -> Unit
                    is SdoUpload.Step.Done -> { up.pendingEnd?.let { send(it) }; finished = true; return@withRpc st.data }
                    is SdoUpload.Step.Failed -> { finished = true; throw st.abort?.let { SdoAbortException(it) } ?: IOException("read 0x${index.hex(4)}/$sub: ${st.reason}") }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } finally {
            // 取消正在傳的列時先結束 SDO，下一個操作才不會接到殘留片段。
            if (!finished && _open.value && gen == generation) withContext(NonCancellable) {
                runCatching { send(Sdo.abortFrame(rpcId, index, sub, 0x05040000L)) }
            }
        }
    }

    /**
     * 寫一個物件（SDO download）。4 bytes 以內 expedited，其餘區塊寫入（同 XTune）。
     * ECU abort 時丟 [SdoAbortException]，逾時丟 [IOException]。不自動讀回；要確認就再 [read]。
     */
    override suspend fun write(index: Int, sub: Int, data: ByteArray): Unit = withRpc {
        check(_open.value) { "not connected" }
        check(!amcActive.value) { "whole-image operation in progress" }
        val gen = requireCalibration()
        require(index in authorization.value!!.objects && sub in 0..255) { "calibration object not authorized" }
        val rpcId = rpc.rpcId
        val dl = SdoDownload(rpcId, index, sub, data)
        var finished = false
        drain()
        try {
            send(dl.start(), gen)
            while (true) {
                val f = await(timing.replyTimeoutMs) { it.canId == replyId() }
                    ?: throw IOException("write 0x${index.hex(4)}/$sub: no reply in ${timing.replyTimeoutMs} ms")
                check(gen == generation && calibrationEnabled) { "calibration connection changed" }
                when (val st = dl.onReply(f)) {
                    is SdoDownload.Step.Send -> st.frames.forEach { send(it, gen) }
                    SdoDownload.Step.Wait -> Unit
                    SdoDownload.Step.Done -> { finished = true; return@withRpc }
                    is SdoDownload.Step.Failed -> {
                        finished = true
                        if (st.abort?.code in setOf(0x06010002L, 0x06010003L, 0x06010004L)) revokeCalibrationObject(index)
                        throw st.abort?.let { SdoAbortException(it) } ?: IOException("write 0x${index.hex(4)}/$sub: ${st.reason}")
                    }
                }
            }
        } finally {
            if (!finished && _open.value && gen == generation) withContext(NonCancellable) {
                runCatching { send(Sdo.abortFrame(rpcId, index, sub, 0x05040000L), gen) }
            }
        }
    }

    private fun monitorFrameAllowed(f: Frame): Boolean {
        if (f.canId != rpc.rpcId || f.dlc != 8) return false
        val addr = f.u8(1) or (f.u8(2) shl 8)
        val sub = f.u8(3)
        if (addr == Rpc.MON_ADDR && sub == Rpc.MON_SUB) return f.data.contentEquals(rpc.monitorStart().data) || f.data.contentEquals(rpc.monitorStop().data)
        if (addr != Rpc.CHAN_TABLE_ADDR) return false
        if (sub == 0) return f.u8(0) == 0x2F && f.u8(4) in 1..ChannelTable.MAX_ENTRIES && (5..7).all { f.u8(it) == 0 }
        return sub in 1..ChannelTable.MAX_ENTRIES && f.u8(0) == 0x23 &&
            f.u8(4) in setOf(8, 16, 24, 32) && f.u8(5) == 0 && (f.u8(6) or (f.u8(7) shl 8)) >= 0x4000
    }

    private fun amcFramesAllowed(frames: List<Frame>): Boolean {
        if (frames.any { it.canId != CanId.SYS || it.flags != 0 }) return false
        if (frames.size == 1) return frames[0].let { it.dlc == 7 && it.u8(0) == Amc.REQUEST && (it.u8(3) or it.u8(4)) != 0 }
        if (frames.size != 171) return false
        return frames.withIndex().all { (i, f) ->
            f.dlc == 8 && f.u8(0) == Amc.DATA && f.u8(1) == i + 1 && (i != 0 || f.u8(3) == 16)
        }
    }

}
