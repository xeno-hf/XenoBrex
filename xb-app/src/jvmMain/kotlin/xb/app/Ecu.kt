package xb.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import xb.tune.Tune
import xb.tune.Convert
import xb.link.TableRead
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import rx.protocol.Sdo
import xb.container.TuneIo
import xb.link.Amc
import xb.link.EcuLink
import xb.link.EcuSync
import xb.link.LiveMonitor
import rx.defs.ChannelRegistry
import xb.tune.Scope
import xb.link.LiveTuning
import xb.link.Snapshot
import xb.link.TableDelta
import xb.link.TcpTransport
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 連 ECU 的各個階段（畫面依它顯示對話框與狀態列）。 */
sealed interface EcuPhase {
    data class Connecting(val host: String) : EcuPhase
    data class Reading(val done: Int, val total: Int) : EcuPhase
    /** ECU 跟檔案不同，等使用者選。 */
    class Differs(val link: EcuLink, val snapshot: Snapshot, val deltas: List<TableDelta>, val backup: EcuBackup) : EcuPhase
    class Live(val link: EcuLink, val live: LiveTuning, val snapshot: Snapshot, val backup: EcuBackup, val monitor: LiveMonitor? = null) : EcuPhase
    class ReadOnly(val link: EcuLink, val reason: String) : EcuPhase
    /** 寫入失敗後已斷線；必須重新讀回，不能沿用快取。 */
    data class Recovery(val message: String) : EcuPhase
    /** ECU 寫入或還原中；完成讀回驗證後才恢復調校。 */
    class Writing(val live: Live, val what: String, val done: Int, val total: Int) : EcuPhase
    data class Failed(val message: String) : EcuPhase
}

/** abort 碼的說明。aRacer 自訂的 0x06010004 在 CiA 301 裡沒有。 */
fun abortText(code: Long?): String = when (code) {
    0x06010004L -> tr("abort.notLicensed")
    0x06010003L -> tr("abort.login")
    0x06010002L -> tr("abort.readOnly")
    0x06020000L -> tr("abort.noTable")
    0x06090011L -> tr("abort.rows")
    null -> tr("abort.unknown")
    else -> Sdo.abortText(code)
}

/** 已存到磁碟並讀回比對的備份，綁定這次連線。 */
class EcuBackup(val file: File, bytes: ByteArray, val generation: Long) {
    private val content = bytes.copyOf()
    val name: String get() = file.name
    val path: String get() = file.path
    fun verify() { check(file.readBytes().contentEquals(content)) { "backup verification failed" } }
    fun matches(other: File): Boolean = other.readBytes().contentEquals(content)
}

/** 連線、讀回、備份與即時調校；整包寫入後必須完整讀回驗證。 */
class EcuController(
    private val state: AppState,
    private val connectLink: suspend (String, Int, CoroutineScope, (String) -> Unit) -> EcuLink = { host, port, scope, log ->
        EcuLink(TcpTransport.connect(host, port), scope, log = log)
    },
) {
    private var job: Job? = null
    private var scope: CoroutineScope? = null
    private val liveJobs = LinkedHashSet<Job>()
    /** 已在關程式：不再接受新的連線或寫入。 */
    private var closing = false
    /** 寫入中有人按了關視窗：等這一輪寫完再接著關。 */
    private var exitPending: (() -> Unit)? = null
    private var exitScope: CoroutineScope? = null

    /** 功能頁與全讀／全寫的工作也要能在整包寫入前取消並等完。 */
    suspend fun <T> liveOperation(p: EcuPhase.Live, block: suspend () -> T): T {
        check(state.ecu === p && p.link.calibrationEnabled && !p.live.closed) { "live tuning is not active" }
        val active = coroutineContext[Job]!!
        synchronized(liveJobs) { liveJobs += active }
        try { return block() } finally { synchronized(liveJobs) { liveJobs -= active } }
    }

    private suspend fun stopLive(p: EcuPhase.Live) {
        val active = coroutineContext[Job]
        val pending = synchronized(liveJobs) { liveJobs.filter { it !== active } }
        pending.forEach { it.cancel() }
        pending.forEach { it.join() }
        p.live.close()
        p.monitor?.stop()
        state.liveValues = null
    }

    val live: LiveTuning? get() = (state.ecu as? EcuPhase.Live)?.takeIf { it.link.calibrationEnabled }?.live

    fun connect(scope: CoroutineScope, host: String, port: Int, password: Int) {
        val tune = state.tune ?: return
        if (state.ecu != null || closing) return
        state.ecu = EcuPhase.Connecting(host)
        this.scope = scope
        job = scope.launch {
            var link: EcuLink? = null
            try {
                link = connectLink(host, port, scope) { state.ecuLog(it) }
                val login = link.connect(password)
                if (login != "ok") { fail(link, tr("ecu.err.login", login)); return@launch }
                val reason = EcuSync.compatibility(EcuSync.identity(link), link.platform, tune)
                if (reason != null) { readOnly(link, tr("ecu.err.compatibility", reason)); return@launch }
                try { Amc.pack(tune) } catch (e: Amc.AmcException) {
                    readOnly(link, tr("ecu.err.layout", e.message)); return@launch
                }
                val gen = link.generation
                val snap = EcuSync.readAll(link, tune) { d, t -> state.ecu = EcuPhase.Reading(d, t) }
                if (!snap.complete) { readOnly(link, tr("ecu.err.incomplete", snap.failed.size)); return@launch }
                val backup = try { saveBackup(snap, gen) } catch (e: Exception) {
                    readOnly(link, tr("ecu.err.backup", e.message)); return@launch
                }
                val fresh = EcuSync.compatibility(EcuSync.identity(link), link.platform, tune)
                if (fresh != null) { readOnly(link, tr("ecu.err.compatibility", fresh)); return@launch }
                if (!link.enableCalibration(snap.ok.map { EcuSync.index(it.def) }.toSet(), snap.complete, gen)) {
                    readOnly(link, tr("ecu.err.connectionChanged")); return@launch
                }
                val deltas = EcuSync.differences(snap, tune)
                if (deltas.isEmpty()) startLive(link, snap, backup)
                else state.ecu = EcuPhase.Differs(link, snap, deltas, backup)
            } catch (e: Exception) {
                if (e is CancellationException) { withContext(NonCancellable) { link?.close() }; throw e }
                fail(link, tr("ecu.err.connect", e.message ?: e::class.simpleName))
            }
        }
    }

    private fun readOnly(link: EcuLink, reason: String) {
        link.disableCalibration()
        state.ecu = EcuPhase.ReadOnly(link, reason)
        state.message = reason
        state.ecuLog("Read-only: $reason")
    }

    private suspend fun fail(link: EcuLink?, msg: String) {
        state.ecuLog("Failed: $msg")
        link?.close()
        state.ecu = EcuPhase.Failed(msg)
    }

    private fun startLive(link: EcuLink, snap: Snapshot, backup: EcuBackup, current: Snapshot = snap, startMonitor: Boolean = true): LiveTuning {
        check(link.calibrationEnabled && snap.complete)
        val writable = Snapshot(current.identity, current.reads.filter { EcuSync.index(it.def) !in link.refusedCalibrationObjects })
        val live = LiveTuning(link, state.tune!!, writable, scope!!, log = { state.ecuLog(it) })
        val monitor = registry()?.let { LiveMonitor(link, it, scope!!, log = { l -> state.ecuLog(l) }) }
        state.ecu = EcuPhase.Live(link, live, snap, backup, monitor)
        state.liveValues = monitor?.values
        if (startMonitor) monitorChannelsChanged()
        state.message = tr("msg.liveStarted", current.ok.size) + tr("msg.liveStarted.backup", backup.name)
        return live
    }

    /** 不同的表改用 ECU 的值（編輯器跟著 ECU）。 */
    fun useEcuValues() {
        val p = state.ecu as? EcuPhase.Differs ?: return
        val t = state.tune ?: return
        if (!p.link.calibrationEnabled) return
        for (d in p.deltas) p.snapshot.rows(d.def.absId)?.let { t.loadRows(d.def.absId, it) }
        state.contentChanged()
        startLive(p.link, p.snapshot, p.backup)
        state.message = tr("msg.usedEcu", p.deltas.size)
    }

    /** 把檔案的值寫進 ECU（只寫不同的表）。 */
    fun writeFileToEcu() {
        val p = state.ecu as? EcuPhase.Differs ?: return
        if (!p.link.calibrationEnabled) return
        val live = startLive(p.link, p.snapshot, p.backup)
        for (d in p.deltas) live.onEdited(d.def.absId)
        state.message = tr("msg.wroteFile", p.deltas.size)
    }

    fun disconnect(scope: CoroutineScope) {
        val phase = state.ecu
        val active = job; job = null
        exitPending = null
        exitScope = null
        active?.cancel()
        scope.launch {
            active?.join()
            when (phase) {
                is EcuPhase.Live -> { runCatching { stopLive(phase) }; phase.link.close() }
                is EcuPhase.Writing -> { runCatching { stopLive(phase.live) }; phase.live.link.close() }
                is EcuPhase.Differs -> phase.link.close()
                is EcuPhase.ReadOnly -> phase.link.close()
                else -> Unit
            }
            state.ecu = null
            state.liveValues = null
            state.message = tr("msg.disconnected")
        }
    }

    /**
     * 關程式前收乾淨：ECU 寫入或還原中不中斷，先記下來等它結束（回 false）；
     * 其他情況先斷線、等連線工作停完，再回呼 [then]。
     */
    fun requestClose(scope: CoroutineScope, then: () -> Unit): Boolean {
        if (state.ecu is EcuPhase.Writing) {
            exitPending = then
            exitScope = scope
            state.message = tr("close.waitWriting")
            return false
        }
        if (closing) return false
        closing = true
        val phase = state.ecu
        val active = job
        job = null
        scope.launch {
            // 先斷線，卡在收送的工作才會結束，join 才等得完
            phaseLink(phase)?.let { runCatching { it.close() } }
            active?.cancelAndJoin()
            if (phase is EcuPhase.Live) runCatching { stopLive(phase) }
            state.ecu = null
            state.liveValues = null
            state.message = tr("msg.disconnected")
            then()
        }
        return true
    }

    /** 整包寫入或還原結束：剛才有人按過關視窗就在這裡接著關；失敗就留下來看訊息。 */
    private fun afterWrite() {
        val action = exitPending ?: return
        val scope = exitScope
        exitPending = null
        exitScope = null
        if (state.ecu is EcuPhase.Live && scope != null) scope.launch { requestClose(scope, action) }
        else state.message = tr("close.writeStopped")
    }

    private fun phaseLink(p: EcuPhase?): EcuLink? = when (p) {
        is EcuPhase.Live -> p.link
        is EcuPhase.Differs -> p.link
        is EcuPhase.ReadOnly -> p.link
        is EcuPhase.Writing -> p.live.link
        else -> null
    }

    /** 存進 ECU：拒絕或未確認的表只用這次重新讀回的 ECU 原值。 */
    fun saveToEcu(scope: CoroutineScope) {
        val p = state.ecu as? EcuPhase.Live ?: return
        val tune = state.tune ?: return
        if (closing) return
        if (!p.snapshot.complete) { state.error = tr("ecu.err.incomplete", p.snapshot.failed.size); return }
        if (!p.link.wholeImageEnabled) { state.error = tr("ecu.err.connectionChanged"); return }
        state.entry = null
        state.ecu = EcuPhase.Writing(p, tr("ecu.flash"), 0, 0)
        job = scope.launch {
            try {
                p.backup.verify()
                check(p.backup.generation == p.link.generation) { tr("ecu.err.connectionChanged") }
                stopLive(p)
                val preserve = (p.live.ecuOverrides().keys + p.link.refusedCalibrationObjects.map { it - EcuSync.TABLE_BASE }).toMutableSet()
                val refused = p.live.states.value.filterValues { it is LiveTuning.TableState.Refused }.keys +
                    p.link.refusedCalibrationObjects.map { it - EcuSync.TABLE_BASE }
                p.link.amcExclusive {
                    val before = EcuSync.readAll(p.link, tune)
                    val reason = EcuSync.compatibility(before.identity, p.link.platform, tune)
                    check(reason == null) { tr("ecu.err.compatibility", reason) }
                    check(before.complete) { tr("ecu.err.incomplete", before.failed.size) }
                    for (d in EcuSync.differences(before, tune)) {
                        val mine = tune.rows(d.def.absId)!!; val ecu = before.rows(d.def.absId)!!
                        if (mine.indices.any { r -> mine[r].indices.any { c -> mine[r][c] != ecu[r][c] && Convert.isOutOfRange(d.def, mine[r][c]) } }) preserve += d.def.absId
                    }
                    val overrides = preserve.associateWith { before.rows(it) ?: error("Table $it could not be read") }
                    val expected = Tune(tune.definitions, tune.scales, xb.tune.TuneFile.parse(tune.toXml()), tune.attachedDefinitions)
                    for ((id, rows) in overrides) check(expected.loadRows(id, rows))
                    val packed = Amc.pack(expected)
                    val after = Amc.write(p.link, packed, log = { state.ecuLog(it) }) { d, t -> state.ecu = EcuPhase.Writing(p, tr("ecu.flash"), d, t) }
                    val left = EcuSync.differences(after, expected)
                    check(after.complete && left.isEmpty()) { tr("ecu.err.verifyFull", after.failed.size, left.size) }
                    for ((id, rows) in overrides) tune.loadRows(id, rows)
                    // 拒絕標記跨整包寫入保留，不能改走另一條路送同一張表。
                    refused.forEach { id -> p.link.revokeCalibrationObject(EcuSync.TABLE_BASE + id) }
                    startLive(p.link, p.snapshot, p.backup, after, startMonitor = false)
                    state.contentChanged()
                    state.message = tr("msg.flashed", packed.tables) + if (overrides.isNotEmpty()) tr("msg.flashed.overrides", overrides.size) else ""
                }
                monitorChannelsChanged()
            } catch (e: Exception) { recovery(p, tr("ecu.flash"), e) } finally { afterWrite() }
        }
    }

    /** 只還原這次連線已驗證的完整備份；逐列寫入、逐列讀回，不走 AMC。 */
    fun restore(scope: CoroutineScope, file: File) {
        val p = state.ecu as? EcuPhase.Live ?: return
        val tune = state.tune ?: return
        if (closing) return
        if (!p.snapshot.complete) { state.error = tr("ecu.err.incomplete", p.snapshot.failed.size); return }
        if (!p.link.calibrationEnabled) { state.error = tr("ecu.err.connectionChanged"); return }
        try {
            p.backup.verify()
            check(p.backup.matches(file)) { tr("ecu.err.restoreOriginal") }
        } catch (e: Exception) { state.error = tr("ecu.err.restore", e.message); return }
        state.entry = null
        state.ecu = EcuPhase.Writing(p, tr("ecu.restoreWith", file.name), 0, p.snapshot.ok.size)
        job = scope.launch {
            try {
                stopLive(p)
                val refused = p.live.states.value.filterValues { it is LiveTuning.TableState.Refused }.keys +
                    p.link.refusedCalibrationObjects.map { it - EcuSync.TABLE_BASE }
                val original = p.snapshot.ok.filter { it.def.absId !in refused }.associate { it.def.absId to it.rows.map { row -> row.copyOf() } }
                p.link.exclusive {
                    check(p.backup.generation == p.link.generation) { tr("ecu.err.connectionChanged") }
                    val reason = EcuSync.compatibility(EcuSync.identity(p.link), p.link.platform, tune)
                    check(reason == null) { tr("ecu.err.compatibility", reason) }
                    val now = EcuSync.readAll(p.link, tune)
                    check(now.complete) { tr("ecu.err.incomplete", now.failed.size) }
                    val restoring = LiveTuning(p.link, tune, now, scope, log = { state.ecuLog(it) })
                    // 學習開關先關、最後寫回（[xb.link.Learning]）；儀表鈕改的開關表先還原
                    val learning = xb.link.Learning.tables(p.link.platform)
                    val dash = rx.protocol.DashFn.entries.mapNotNull { fn -> p.link.platform?.let { rx.protocol.DashTable.index(it.node, fn) } }
                        .filter { it in 0x3000..0x3FFF }.map { it - 0x3000 }.toSet()
                    val report = try { restoring.restore(original, recheck = dash, learning = learning) } finally { restoring.close() }
                    for (id in report.restored + report.unchanged) original[id]?.let { tune.loadRows(id, it) }
                    state.contentChanged()
                    check(report.ok) { tr("ecu.err.restoreRows", report.restored.size, report.failed.size) }
                    val after = EcuSync.readAll(p.link, tune)
                    check(after.complete) { tr("ecu.err.incomplete", after.failed.size) }
                    // 最後整份讀回也要跟備份一致（跳過的被拒表除外）：逐張確認之後 ECU 又改了值，不能報成功。
                    // 備份裡學習是開著的：還原最後把它開回來，ECU 之後自己改油表是正常的；其他表在學習關著時已逐張確認，這裡只比學習開關。
                    val resumed = learning.any { id -> original[id]?.any { row -> row.any { it != 0L } } == true }
                    val checked = if (resumed) original.filterKeys { it in learning } else original
                    val drift = checked.count { (id, rows) -> after.rows(id)?.let { got -> rows.size != got.size || rows.indices.any { !rows[it].contentEquals(got[it]) } } ?: true }
                    check(drift == 0) { tr("ecu.err.restoreDrift", drift) }
                    refused.forEach { p.link.revokeCalibrationObject(EcuSync.TABLE_BASE + it) }
                    startLive(p.link, p.snapshot, p.backup, after, startMonitor = false)
                    state.message = tr("msg.restoredRows", report.restored.size, report.unchanged.size, refused.size)
                }
                monitorChannelsChanged()
            } catch (e: Exception) { recovery(p, tr("ecu.restoreWith", file.name), e) } finally { afterWrite() }
        }
    }

    private suspend fun recovery(p: EcuPhase.Live, what: String, e: Exception) {
        withContext(NonCancellable) {
            p.link.disableCalibration()
            p.live.close()
            runCatching { p.monitor?.stop() }
            p.link.close()
            state.liveValues = null
            val message = tr("ecu.err.recovery", what, e.message ?: e::class.simpleName, p.backup.path)
            state.ecu = EcuPhase.Recovery(message)
            state.ecuLog(message)
        }
        if (e is CancellationException) throw e
    }

    /** 編輯器改了 [absId]。 */
    fun edited(absId: Int) { live?.onEdited(absId) }

    /** 全寫（XTune 油圖頁的「全寫」）：這張表每一列都送進 ECU，不等停手。 */
    fun writeTable(scope: CoroutineScope, absId: Int) {
        val p = state.ecu as? EcuPhase.Live ?: return
        val l = live ?: return
        val def = state.tune?.definition(absId) ?: return
        if (!l.writable(absId)) { state.message = tr("msg.tableNotWritable", state.title(def)); return }
        scope.launch {
            liveOperation(p) { l.writeTable(absId) }
            state.message = when (val st = l.states.value[absId]) {
                null, LiveTuning.TableState.Synced -> tr("msg.wroteTable", state.title(def))
                else -> liveDetail(st) ?: liveBadge(st)?.first ?: ""
            }
        }
    }

    /** 全讀（XTune 的「全讀」）：從 ECU 重讀這張表，換進編輯器（可以復原）。 */
    fun readTable(scope: CoroutineScope, absId: Int) {
        val p = state.ecu as? EcuPhase.Live ?: return
        val l = live ?: return
        val tune = state.tune ?: return
        val def = tune.definition(absId) ?: return
        scope.launch {
            var changed = false
            val ui = currentCoroutineContext()[ContinuationInterceptor]!!
            // 換進編輯器在即時寫入的鎖裡做（清除學習值後鎖住的表這樣才解開），回畫面的執行緒改
            // 讀不到（逾時、斷線）或換不進編輯器會丟例外：一樣報成讀表失敗（表若鎖著就維持鎖著）
            val r = try {
                liveOperation(p) {
                    l.readTable(absId) { rows ->
                        withContext(ui) {
                            changed = !tune.rows(absId)!!.let { cur -> cur.size == rows.size && cur.indices.all { cur[it].contentEquals(rows[it]) } }
                            check(tune.loadRows(absId, rows, undoable = true)) { "size" }
                            state.contentChanged()
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            r.onSuccess { state.message = tr(if (changed) "msg.readTable" else "msg.readTable.same", state.title(def)) }
                .onFailure { state.message = tr("msg.readTableFailed", state.title(def), it.message) }
        }
    }

    private var registryFor: Any? = null
    private var registryCache: ChannelRegistry? = null

    /** 這份調校檔平台的即時通道名錄（定義檔裡的 Var）。定義檔讀不到就 null（沒有準星）。 */
    fun registry(): ChannelRegistry? {
        val s = state.session ?: return null
        val pack = state.pack ?: return null
        val key = pack to s.platform
        if (registryFor == key) return registryCache
        registryFor = key
        registryCache = runCatching { pack.text("ControlFile_${s.platform.definition}")?.let(ChannelRegistry::parse) }
            .onFailure { state.ecuLog("Live channels unavailable: ${it.message}") }.getOrNull()
        return registryCache
    }

    /** 功能專頁要看的即時變數（[calWatch]）。 */
    private var calVars: List<Int> = emptyList()

    /** 功能專頁開著時要看的變數；跟準星的通道一起上頻道表。 */
    fun calWatch(vars: List<Int>) {
        if (vars == calVars) return
        calVars = vars
        monitorChannelsChanged()
    }

    /** 要串的通道：每張有準星的表的軸變數，加上四角的值（有開的話），加上功能專頁要的。準星關掉也照串功能頁的。 */
    fun wantedChannels(): List<Int> {
        val t = state.tune ?: return emptyList()
        if (!state.scopeOn) return calVars
        val out = LinkedHashSet<Int>(calVars)
        for (d in t.definitions.tables.values) Scope.vars(d)?.let { (x, y) -> x?.let(out::add); y?.let(out::add) }
        if (state.scopeShowVars) out.addAll(state.scopeVars)
        return out.toList()
    }

    /** 準星設定或四角變數改了：照新的清單重寫頻道表（沒變就不動）。 */
    fun monitorChannelsChanged() {
        val m = (state.ecu as? EcuPhase.Live)?.monitor ?: return
        val sc = scope ?: return
        val want = wantedChannels()
        sc.launch { runCatching { if ((state.ecu as? EcuPhase.Live)?.monitor !== m) return@launch; if (want.isEmpty()) m.stop() else m.watch(want) }.onFailure { state.ecuLog("Live monitor: ${it.message}") } }
    }

    /** ECU 原值存成調校檔，放在 [AppState.ecuBackupDir]（預設設定資料夾/ecu-backups/）。原檔有加密且有金鑰就照樣加密。 */
    private fun saveBackup(snap: Snapshot, generation: Long): EcuBackup {
        check(snap.complete) { "incomplete ECU snapshot" }
        val s = state.session!!
        val dir = state.ecuBackupDir
        check(dir.isDirectory || dir.mkdirs()) { "cannot create backup directory" }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val text = EcuSync.backup(snap, s.tune, TuneIo.now())
        Amc.pack(Tune(s.tune.definitions, s.tune.scales, xb.tune.TuneFile.parse(text), s.tune.attachedDefinitions))
        val container = state.keys?.container
        val encrypt = s.encrypted && container != null
        val ext = if (encrypt) TuneIo.extension(s.tune) else "xml"
        val f = File.createTempFile("ECU_${stamp}_${s.file.nameWithoutExtension}_", ".$ext", dir)
        val bytes = text.toByteArray(Charsets.UTF_8).let { if (encrypt) container.encrypt(it) else it }
        FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
        check(f.readBytes().contentEquals(bytes)) { "backup verification failed" }
        return EcuBackup(f, bytes, generation)
    }
}

/** 一張表的即時狀態：顯示文字與顏色。沒動過回 null。 */
fun liveBadge(st: LiveTuning.TableState?): Pair<String, Color>? = when (st) {
    null -> null
    LiveTuning.TableState.Synced -> tr("live.synced") to Xb.accent
    LiveTuning.TableState.Pending -> tr("live.pending") to Xb.warn
    LiveTuning.TableState.Writing -> tr("live.writing") to Xb.warn
    is LiveTuning.TableState.Refused -> tr("live.refused") to Xb.bad
    is LiveTuning.TableState.Mismatch -> tr("live.mismatch", st.cells) to Xb.bad
    is LiveTuning.TableState.OutOfRange -> tr("live.outOfRange", st.cells) to Xb.bad
    is LiveTuning.TableState.Failed -> tr("live.failed") to Xb.bad
}

/** 一張表即時狀態的完整說明（表格上方顯示）。 */
fun liveDetail(st: LiveTuning.TableState?): String? = when (st) {
    null, LiveTuning.TableState.Synced, LiveTuning.TableState.Pending, LiveTuning.TableState.Writing -> null
    is LiveTuning.TableState.Refused -> tr("live.refused.detail", st.reason)
    is LiveTuning.TableState.Mismatch -> tr("live.mismatch.detail", st.cells)
    is LiveTuning.TableState.OutOfRange -> tr("live.outOfRange.detail", st.cells)
    is LiveTuning.TableState.Failed -> tr("live.failed.detail", st.reason)
}

@Composable
fun ConnectDialog(state: AppState, onConnect: (String, Int, Int) -> Unit, onClose: () -> Unit) {
    var host by remember { mutableStateOf(state.settings.ecuHost) }
    var pw by remember { mutableStateOf(state.settings.ecuPassword) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("connect.title")) },
        text = {
            Column(Modifier.width(460.dp)) {
                Text(
                    tr("connect.detail"),
                    fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(host, { host = it.trim() }, label = { Text(tr("connect.host")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ecuHost"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it.filter(Char::isDigit) }, label = { Text(tr("connect.password")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ecuPassword"))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (h, p) = host.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: TcpTransport.PORT) }
                state.settings.ecuHost = host
                state.settings.ecuPassword = pw
                onConnect(h, p, pw.toIntOrNull() ?: 888888)
            }, enabled = host.isNotBlank(), modifier = Modifier.testTag("ecuConnect")) { Text(tr("connect.go")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common.cancel")) } },
    )
}

/** 連線中、讀取中、跟檔案不同、失敗：這幾個階段的對話框。即時調校中不顯示。 */
@Composable
fun EcuDialogs(state: AppState, onDisconnect: () -> Unit) {
    when (val p = state.ecu) {
        is EcuPhase.Connecting, is EcuPhase.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("bar.ecu")) },
            text = {
                Column(Modifier.width(420.dp)) {
                    if (p is EcuPhase.Reading) {
                        Text(tr("connect.reading", p.done, p.total), fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(tr("connect.connecting", (p as EcuPhase.Connecting).host), fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDisconnect) { Text(tr("common.cancel")) } },
        )
        is EcuPhase.ReadOnly -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("ecu.readOnly")) },
            text = { Text(p.reason, modifier = Modifier.testTag("ecuReadOnly")) },
            confirmButton = { TextButton(onClick = onDisconnect) { Text(tr("bar.ecuOff")) } },
        )
        is EcuPhase.Recovery -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("ecu.recovery")) },
            text = { Text(p.message, modifier = Modifier.testTag("ecuRecovery")) },
            confirmButton = { TextButton(onClick = onDisconnect) { Text(tr("common.ok")) } },
        )
        is EcuPhase.Differs -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("differs.title")) },
            text = {
                Column(Modifier.width(500.dp).testTag("ecuDiffers")) {
                    Text(
                        tr("differs.detail", p.deltas.size, p.deltas.sumOf { it.cells }) + "\n\n" +
                            tr("differs.backup", p.backup.path),
                        fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        p.deltas.take(12).joinToString("\n") { tr("differs.row", state.title(it.def), it.def.name, it.cells) } + if (p.deltas.size > 12) "\n…" else "",
                        fontSize = 12.sp, color = Xb.dim, lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { state.ecuControl.useEcuValues() }, modifier = Modifier.testTag("ecuUseEcu")) { Text(tr("differs.useEcu")) }
                    TextButton(onClick = { state.ecuControl.writeFileToEcu() }, modifier = Modifier.testTag("ecuWriteFile")) { Text(tr("differs.writeFile")) }
                }
            },
            dismissButton = { TextButton(onClick = onDisconnect) { Text(tr("bar.ecuOff")) } },
        )
        is EcuPhase.Writing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(p.what) },
            text = {
                Column(Modifier.width(420.dp).testTag("ecuWriting")) {
                    Text(tr("writing.progress", p.done, p.total), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is EcuPhase.Failed -> AlertDialog(
            onDismissRequest = { state.ecu = null },
            title = { Text(tr("connect.failed")) },
            text = { Text(p.message, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("ecuFailed")) },
            confirmButton = { TextButton(onClick = { state.ecu = null }) { Text(tr("common.ok")) } },
        )
        else -> Unit
    }
}

/** 存進 ECU／還原之前的確認。 */
@Composable
fun FlashConfirmDialog(what: String, detail: String, onGo: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(what) },
        text = {
            Text(
                detail + "\n\n" + tr("flash.checklist"),
                fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.width(460.dp),
            )
        },
        confirmButton = { TextButton(onClick = onGo, modifier = Modifier.testTag("flashGo")) { Text(tr("flash.go")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("common.cancel")) } },
    )
}
