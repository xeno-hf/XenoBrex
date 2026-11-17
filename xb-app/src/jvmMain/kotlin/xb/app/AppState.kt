package xb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xb.container.DefinitionPack
import xb.container.KeyStore
import xb.container.KeysRequiredException
import xb.container.OpenedTune
import xb.container.TuneContainer
import xb.container.TuneIo
import xb.container.TuneOpenException
import xb.container.UserKeys
import xb.container.XTuneImportException
import xb.container.XTunePackage
import xb.tune.CalStep
import xb.tune.AdjustOp
import xb.tune.AxisLookup
import xb.tune.Cell
import xb.tune.DiffKind
import xb.tune.EditResult
import xb.tune.Platform
import xb.tune.TableDef
import xb.tune.TableDiff
import xb.tune.Tune
import xb.tune.TuneCompare
import xb.tune.TuneReport
import java.io.File

enum class SelTarget { Body, AxisX, AxisY }

/** 選取範圍：從起點（r0,c0）拉到終點（r1,c1）的矩形。軸上的選取只用到 c。 */
data class Selection(val target: SelTarget, val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    val top get() = minOf(r0, r1)
    val bottom get() = maxOf(r0, r1)
    val left get() = minOf(c0, c1)
    val right get() = maxOf(c0, c1)
    fun contains(r: Int, c: Int) = r in top..bottom && c in left..right
    val count get() = (bottom - top + 1) * (right - left + 1)
    fun cells(): List<Cell> = (top..bottom).flatMap { r -> (left..right).map { c -> Cell(r, c) } }
}

typealias TableEntry = xb.tune.TableEntry
typealias GroupEntry = xb.tune.GroupEntry

enum class Op { Add, Subtract, Multiply, Divide, Set, Linear, Smooth, StepUp, StepDown }

/**
 * 正在跟使用者要金鑰。
 * [sample]：拿來試金鑰的加密檔內容；[retry]：金鑰對了以後要接著做的事；[manage]：使用者自己點開來看或換金鑰。
 */
class KeyPrompt(val sample: ByteArray?, val retry: (() -> Unit)?, val manage: Boolean)

/** 拿檔案試金鑰時讀到的樣本；太大的檔不整份讀進來。 */
sealed interface KeySample {
    class Bytes(val value: ByteArray) : KeySample
    class TooLarge(val limit: Int) : KeySample
    object Unreadable : KeySample

    companion object {
        /** 最多讀這麼多；加密的調校檔遠小於這個大小。 */
        const val LIMIT = 4 * 1024 * 1024

        /** 讀 [file] 當樣本：最多讀 [LIMIT] 位元組，再大就回 [TooLarge]。 */
        fun read(file: File?): KeySample {
            if (file == null) return Unreadable
            return runCatching {
                file.inputStream().use { ins ->
                    val bytes = ins.readNBytes(LIMIT + 1)
                    if (bytes.size > LIMIT) TooLarge(LIMIT) else Bytes(bytes)
                }
            }.getOrElse { Unreadable }
        }
    }
}

/** 桌面版的狀態。調校檔本身不是可觀察的；每次編輯後把 [revision] 加一，畫面跟著重算。 */
class AppState(
    val settings: Settings,
    definitionsDir: File = File(settings.definitionsDir),
    private val keyFile: File = KeyStore.defaultFile(),
    /** 從 XTune 安裝檔匯入的定義檔放在這裡。 */
    private val importDir: File = Settings.defaultDefinitionsDir(),
) {
    /** 使用者的金鑰；程式不帶，沒有就是 null。 */
    var keys: UserKeys? by mutableStateOf(KeyStore.load(keyFile))
        private set
    var keyPrompt: KeyPrompt? by mutableStateOf(null)
        private set
    private val container: TuneContainer? get() = keys?.container

    var pack: DefinitionPack? by mutableStateOf(loadPack(definitionsDir))
        private set
    var session: OpenedTune? by mutableStateOf(null)
        private set
    var report: TuneReport? by mutableStateOf(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    /** 調校檔在編輯動作以外被改了（例如改用 ECU 的值）。 */
    fun contentChanged() { recompare(); revision++ }
    var selectedTable: Int? by mutableStateOf(null)
        private set
    var selection: Selection? by mutableStateOf(null)
    /** 正在格子裡打的字；null 表示沒有在打字。 */
    var entry: String? by mutableStateOf(null)
    var showAll by mutableStateOf(settings.showAll)
    var query by mutableStateOf("")
    /** 左邊清單：true＝功能（XTune 調校選單），false＝全部表。 */
    var features by mutableStateOf(settings.features)
    /** 開著的功能專頁（xb-calui）；null＝編輯區顯示表格。 */
    var calItem by mutableStateOf<xb.tune.CalItems.Item?>(null)
    var operand by mutableStateOf("1")

    /** 目前這張表的步進值文字（XTune 的步進：每張表各自記住，＋步進／−步進用它）。 */
    var stepText by mutableStateOf("2")
        private set

    private fun loadStep(absId: Int?) {
        val def = absId?.let { tune?.definition(it) } ?: return
        stepText = fmtStep(settings.calStep(CalStep.key(def)) ?: CalStep.default(def.absId))
    }

    private fun fmtStep(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

    /** 改步進值：數字對就記到這張表。 */
    fun editStep(text: String) {
        stepText = text.filter { it.isDigit() || it == '.' }.take(10)
        val v = stepText.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        val def = selectedTable?.let { tune?.definition(it) } ?: return
        settings.setCalStep(CalStep.key(def), v)
    }
    var message: String? by mutableStateOf(null)
    private var errorState: String? by mutableStateOf(null)
    /** 要彈窗的錯誤；設定時也寫進紀錄檔。 */
    var error: String?
        get() = errorState
        set(v) { errorState = v; if (v != null && logToFile) xb.container.AppLog.write("error", v) }
    /** 「關於」視窗是不是開著（按字標打開）。 */
    var about by mutableStateOf(false)
    /** 設定頁是不是開著。 */
    var showSettings by mutableStateOf(false)

    init { I18n.lang = Lang.resolve(settings.language) }

    /** 語言設定：[Lang.SYSTEM] 或某個語言的 tag。 */
    var languageSetting by mutableStateOf(settings.language)
        private set

    fun pickLanguage(setting: String) {
        languageSetting = setting
        settings.language = setting
        I18n.lang = Lang.resolve(setting)
    }

    // ---- 油圖準星（Scope.kt）----
    /** 有定義準星的表顯示準星（XTune 的 scope 開關）。 */
    var scopeOn by mutableStateOf(settings.scopeOn)
        private set
    /** 手放開 3 秒後自動選到準星所在的格子（XTune「Cell Track」）。 */
    var cellTrack by mutableStateOf(settings.cellTrack)
        private set
    /** 準星四角顯示即時值。 */
    var scopeShowVars by mutableStateOf(settings.scopeShowVars)
        private set
    /** 四角的 4 個即時變數 absid（左上、右上、右下、左下；XTune 預設 26,45,34,55）。 */
    var scopeVars by mutableStateOf(settings.scopeVars)
        private set

    fun switchScope(on: Boolean) { scopeOn = on; settings.scopeOn = on; ecuControl.monitorChannelsChanged() }
    fun switchCellTrack(on: Boolean) { cellTrack = on; settings.cellTrack = on }
    fun switchScopeVars(on: Boolean) { scopeShowVars = on; settings.scopeShowVars = on; ecuControl.monitorChannelsChanged() }
    fun setScopeVar(i: Int, absid: Int) {
        scopeVars = scopeVars.toMutableList().also { it[i] = absid }
        settings.scopeVars = scopeVars
        ecuControl.monitorChannelsChanged()
    }

    /** 即時值（通道 absid → 物理值）：連著 ECU 時是監控串流，測試時可以直接給。null＝沒有即時資料。 */
    var liveValues: kotlinx.coroutines.flow.StateFlow<Map<Int, Double>>? by mutableStateOf(null)

    /** 使用者最後一次動表格的時間（Cell Track：停手 3 秒後才跟著準星）。 */
    var lastTouchMs = 0L

    // ---- 即時調校（Ecu.kt）----
    /** 連 ECU 的階段；null＝沒連。 */
    var ecu: EcuPhase? by mutableStateOf(null)
    val ecuControl = EcuController(this)
    /** 整包寫入與連線準備時，不讓編輯器改動正在核對的內容。 */
    val editingFrozen: Boolean get() = ecu != null && ecu !is EcuPhase.Live && ecu !is EcuPhase.ReadOnly
    /** 手機同步（ReX 連進來）。 */
    val phoneSync = PhoneSync(this)
    /** 連線時 ECU 原值的備份放這裡。 */
    var ecuBackupDir: File = settings.ecuBackupDir?.let(::File) ?: xb.container.XbPaths.backupDir()

    fun chooseEcuBackupDir(dir: File?) {
        settings.ecuBackupDir = dir?.path
        ecuBackupDir = dir ?: xb.container.XbPaths.backupDir()
    }
    /** 寫紀錄檔（測試時關掉）。 */
    var logToFile = true
    /** 連線過程的紀錄（最近 200 行），給狀態列與除錯用。 */
    val ecuLogLines = androidx.compose.runtime.mutableStateListOf<String>()
    fun ecuLog(line: String) {
        if (logToFile) xb.container.AppLog.write("ecu", line)
        ecuLogLines.add(line)
        while (ecuLogLines.size > 200) ecuLogLines.removeAt(0)
    }

    var theme by mutableStateOf(ThemeChoice.parse(settings.theme))
        private set

    fun pickTheme(c: ThemeChoice, quiet: Boolean = false) {
        theme = c
        settings.theme = c.name
        if (!quiet) message = tr("msg.theme", c.hint)
    }

    /** 換到下一套配色：翠晶 → 緋焰 → 曦光 → 自動 → 翠晶。 */
    fun nextTheme() {
        val all = ThemeChoice.entries
        pickTheme(all[(theme.ordinal + 1) % all.size])
    }

    /** 使用者調的介面縮放（Ctrl 加號／減號／0）。 */
    var zoom by mutableStateOf(settings.zoom)
        private set

    fun zoomBy(steps: Int) {
        val z = if (steps == 0) 1f else (Math.round((zoom + steps * 0.1f) * 10f) / 10f).coerceIn(Settings.ZOOM_MIN, Settings.ZOOM_MAX)
        zoom = z
        settings.zoom = z
        message = tr("msg.zoom", Math.round(z * 100))
    }

    /** 比較對象。 */
    var other: OpenedTune? by mutableStateOf(null)
        private set
    var diffs: Map<Int, TableDiff> by mutableStateOf(emptyMap())
        private set
    var diffOnly by mutableStateOf(false)

    /** 定義檔名稱檔用的語言（跟著介面語言）。 */
    val lang: String get() = I18n.lang.labelTag
    val tune: Tune? get() = session?.tune

    private fun loadPack(dir: File): DefinitionPack? =
        if (dir.isDirectory) DefinitionPack(dir) { container }.takeIf { it.complete } else null

    // ---- 金鑰 ----

    private fun askForKeys(file: File?, retry: () -> Unit) {
        val read = KeySample.read(file)
        if (read is KeySample.TooLarge) error = tr("keys.err.sampleTooLarge", read.limit / (1024 * 1024))
        val own = (read as? KeySample.Bytes)?.value?.takeIf { TuneContainer.looksEncrypted(it) }
        keyPrompt = KeyPrompt(own ?: pack?.encryptedSample(), retry, manage = false)
    }

    /** 使用者自己點開金鑰視窗（看有沒有設、換一組、刪掉）。 */
    fun manageKeys() {
        val read = KeySample.read(session?.takeIf { it.encrypted }?.file)
        if (read is KeySample.TooLarge) error = tr("keys.err.sampleTooLarge", read.limit / (1024 * 1024))
        val own = (read as? KeySample.Bytes)?.value
        keyPrompt = KeyPrompt(own ?: pack?.encryptedSample(), null, manage = true)
    }

    fun cancelKeys() { keyPrompt = null }

    /**
     * 使用者貼上金鑰。拿加密的檔案實際試過才收下，順序貼反了會自動對調。
     * 成功回 null，否則回要顯示的說明。
     */
    fun submitKeys(text: String): String? {
        val prompt = keyPrompt
        val parsed = KeyStore.parse(text) ?: return tr("keys.err.twoParts")
        val sample = prompt?.sample
            ?: return tr("keys.err.noSample")
        val good = KeyStore.matching(parsed, sample) ?: return tr("keys.err.wrong")
        try { KeyStore.save(good, keyFile) } catch (e: Exception) { return tr("keys.err.save", e.message) }
        keys = good
        keyPrompt = null
        message = tr("msg.keysSaved")
        prompt.retry?.invoke()
        return null
    }

    /**
     * 從使用者自己的 XTune 安裝檔讀金鑰與定義檔（見 [XTunePackage]）。
     * 金鑰一定會存下來；定義檔只有在還沒設定定義檔資料夾、或目前用的就是匯入的那個資料夾時才寫入，
     * 不會動使用者自己指定的資料夾。成功回 null，否則回要顯示的說明。
     */
    fun importXTune(file: File): String? {
        val r = try { XTunePackage.read(file) } catch (e: XTuneImportException) { return e.message }
        try { KeyStore.save(r.keys, keyFile) } catch (e: Exception) { return tr("keys.err.save", e.message) }
        keys = r.keys
        var wrote = 0
        if (pack == null || File(settings.definitionsDir).absoluteFile == importDir.absoluteFile) {
            try {
                wrote = XTunePackage.installDefinitions(r, importDir)
            } catch (e: Exception) { return tr("defs.err.write", importDir.path, e.message) }
            if (!setDefinitionsDir(importDir)) return error.also { error = null }
        }
        val prompt = keyPrompt
        keyPrompt = null
        message = if (wrote > 0) tr("msg.importedBoth", wrote) else tr("msg.importedKeys")
        prompt?.retry?.invoke()
        return null
    }

    fun forgetKeys() {
        KeyStore.clear(keyFile)
        keys = null
        keyPrompt = null
        message = tr("msg.keysForgotten")
    }

    fun setDefinitionsDir(dir: File): Boolean {
        val p = loadPack(dir)
        if (p == null) {
            error = tr("defs.err.incomplete")
            return false
        }
        settings.definitionsDir = dir.path
        pack = p
        return true
    }

    // ---- 檔案 ----

    fun open(file: File): Boolean {
        if (ecu != null) { error = tr("open.err.connected"); return false }
        val p = pack ?: return false
        return try {
            val o = TuneIo.open(file, p, container)
            session = o
            report = o.tune.check()
            settings.lastDir = file.absoluteFile.parent
            other = null; diffs = emptyMap(); diffOnly = false
            selectedTable = null; selection = null
            revision++
            val r = report!!
            message = tr("msg.opened", file.name, o.platform.display, o.tune.file.tables.size) +
                (if (r.cellsOutOfRange > 0) tr("msg.opened.outOfRange", r.cellsOutOfRange) else "")
            tables().firstOrNull()?.tables?.firstOrNull()?.let { select(it.def.absId) }
            true
        } catch (e: KeysRequiredException) {
            askForKeys(file) { open(file) }; false
        } catch (e: TuneOpenException) {
            error = e.message; false
        }
    }

    /** 存檔。覆蓋原檔前，先留一份 `.bak`（只留第一次的）。存完重新讀回，確認內容和記憶體裡一致。 */
    fun save(target: File? = null): Boolean {
        if (editingFrozen) return false
        val o = session ?: return false
        val dest = target ?: o.file
        return try {
            if (dest.exists() && dest.absoluteFile == o.file.absoluteFile) {
                val bak = File(dest.path + ".bak")
                if (!bak.exists()) dest.copyTo(bak)
            }
            // 另存：副檔名 .xml 不加密；原檔有加密就照樣加密；原檔沒加密時，有金鑰才加密。
            val encrypted = if (target == null) o.encrypted else dest.extension.lowercase() != "xml" && (o.encrypted || keys != null)
            if (o.project != null || dest.extension.lowercase() == "xbx") {
                // .xbx：調校檔放回專案、改過的表記進修改紀錄（每張表一筆，含每格改前改後）；不會把專案蓋成一般調校檔
                val entries = o.tune.modifiedTables.map { id ->
                    linkedMapOf<String, Any?>("kind" to "edit", "by" to "XenoBrex", "table" to id, "name" to o.tune.definition(id)?.name,
                        "cells" to xb.container.XbxProject.changedCells(o.tune.file.tables[id]?.rows, o.tune.rows(id)))
                }
                TuneIo.saveProject(o, dest, o.project, entries, encrypted, container = container)
            } else TuneIo.save(o, dest, encrypted, container = container)
            val again = TuneIo.open(dest, pack!!, container)
            val diff = TuneCompare.compare(o.tune, again.tune)
            if (diff.isNotEmpty()) {
                error = tr("save.err.verify", diff.size, dest.path)
                return false
            }
            val keep = selectedTable
            val sel = selection
            // 連線中保留 Tune 本體，LiveTuning 才會繼續讀到編輯器後續的改動；
            // 檔案基準換成剛存好的內容，「回到檔案內容」才不會把舊值排進 ECU。
            if (ecu != null) o.tune.rebase(again.tune.file)
            session = if (ecu != null) OpenedTune(o.tune, again.file, again.encrypted, again.platform, again.labels, again.project) else again
            report = session!!.tune.check()
            recompare()
            selectedTable = keep; selection = sel
            revision++
            message = tr("msg.saved", dest.name)
            true
        } catch (e: KeysRequiredException) {
            askForKeys(o.file) { save(target) }; false
        } catch (e: Exception) {
            error = tr("save.err", e.message); false
        }
    }

    fun openOther(file: File): Boolean {
        val p = pack ?: return false
        val s = session ?: return false
        return try {
            val o = TuneIo.open(file, p, container)
            if (o.platform != s.platform) {
                error = tr("compare.err.platform", s.platform.display, o.platform.display)
                return false
            }
            other = o
            recompare()
            diffOnly = true
            message = tr("msg.comparing", file.name, diffs.size)
            revision++
            true
        } catch (e: KeysRequiredException) {
            askForKeys(file) { openOther(file) }; false
        } catch (e: TuneOpenException) {
            error = e.message; false
        }
    }

    fun closeOther() { other = null; diffs = emptyMap(); diffOnly = false; revision++ }

    private fun recompare() {
        val s = session ?: return
        val o = other ?: return
        diffs = TuneCompare.compare(s.tune, o.tune).associateBy { it.absId }
    }

    // ---- 清單 ----

    fun title(def: TableDef): String = session?.labels?.shortName(def.absId, lang) ?: def.name

    fun level(def: TableDef): Int? = tune?.level(def)

    /** 左邊清單：依群組排好、依等級與搜尋字過濾。 */
    fun tables(): List<GroupEntry> {
        val s = session ?: return emptyList()
        return xb.tune.TableList.groups(s.tune, s.labels, lang, showAll, query) { d -> !diffOnly || d.absId in diffs }
    }

    /** 這份檔案的機型 node：連著 ECU 也一樣用檔案類型判斷（XB 只接受同類型的 ECU）。 */
    val featureNode: Int? get() = session?.tune?.file?.mapType?.let { xb.tune.CalItems.nodeOfMapType(it) }

    /** 功能選單：照 XTune 的順序；每項只留調校包裡有、讀得到的表。 */
    fun featureItems(): List<Pair<xb.tune.CalItems.Item, List<Int>>> {
        val t = tune ?: return emptyList()
        val node = featureNode ?: return emptyList()
        return xb.tune.CalItems.items(node, t.file.modelId, t.file.tableId.trim()).map { it to it.tables.filter { id -> t.definition(id) != null && t.rows(id) != null } }
    }

    /** 功能專頁在這份檔案／這台 ECU 上能不能開（命令頁要連 ECU）。 */
    fun calUsable(item: xb.tune.CalItems.Item): Boolean {
        val t = tune ?: return false
        fun has(id: Int) = t.definition(id) != null && t.rows(id) != null
        val online = ecu is EcuPhase.Live
        return when (item.page) {
            xb.tune.CalItems.Page.IdleAdj, xb.tune.CalItems.Page.TpsLearn, xb.tune.CalItems.Page.RgbLed, xb.tune.CalItems.Page.Service -> online
            xb.tune.CalItems.Page.Backfire -> has(42) || has(1569)
            else -> item.tables.any(::has)
        }
    }

    fun select(absId: Int) {
        if (selectedTable == absId) return
        if (calItem?.let { absId in it.tables } != true) calItem = null
        entry = null
        selectedTable = absId
        selection = Selection(SelTarget.Body, 0, 0, 0, 0)
        loadStep(absId)
    }

    // ---- 編輯 ----

    private fun done(r: EditResult, what: String, absId: Int? = null) {
        when (r) {
            is EditResult.Done -> {
                if (r.changed > 0 && absId != null) ecuControl.edited(absId)
                message = if (r.changed == 0) tr("msg.edit.none", what) else tr("msg.edit.changed", what, r.changed)
                recompare()
                revision++
            }
            is EditResult.Refused -> message = tr("msg.edit.refused", what, r.reason)
        }
    }

    fun apply(op: Op) {
        if (editingFrozen) return
        val t = tune ?: return
        val def = selectedTable?.let { t.definition(it) } ?: return
        val sel = selection ?: run { message = tr("msg.selectFirst"); return }
        val stepOp = op == Op.StepUp || op == Op.StepDown
        val v = if (stepOp) stepText.trim().toDoubleOrNull()?.takeIf { it > 0 } else operand.trim().toDoubleOrNull()
        val needsValue = op != Op.Linear && op != Op.Smooth
        if (needsValue && v == null) { message = tr(if (stepOp) "msg.stepNaN" else "msg.operandNaN"); return }
        val label = when (op) {
            Op.StepUp -> tr("op.add", stepText); Op.StepDown -> tr("op.sub", stepText)
            Op.Add -> tr("op.add", operand); Op.Subtract -> tr("op.sub", operand); Op.Multiply -> tr("op.mul", operand); Op.Divide -> tr("op.div", operand)
            Op.Set -> tr("op.set", operand); Op.Linear -> "LINEAR"; Op.Smooth -> "SMOOTH"
        }
        val adj = when (op) {
            Op.Add -> AdjustOp.Add to v!!
            Op.Subtract -> AdjustOp.Add to -v!!
            Op.StepUp -> AdjustOp.Add to v!!
            Op.StepDown -> AdjustOp.Add to -v!!
            Op.Multiply -> AdjustOp.Multiply to v!!
            Op.Divide -> AdjustOp.Divide to v!!
            Op.Set -> AdjustOp.Set to v!!
            else -> null
        }
        if (sel.target == SelTarget.Body) {
            val cells = sel.cells()
            done(when (op) {
                Op.Linear -> t.linear(def.absId, cells)
                Op.Smooth -> t.smooth(def.absId, cells)
                else -> t.adjust(def.absId, cells, adj!!.first, adj.second)
            }, label, def.absId)
            return
        }
        // 軸：改的是另一張表的第 0 列，改完整理成遞增
        val lookup = if (sel.target == SelTarget.AxisX) t.axisX(def) else t.axisY(def)
        val axis = (lookup as? AxisLookup.Found)?.axis
        val src = axis?.sourceTable ?: run { message = tr("msg.axisFixed"); return }
        if (adj == null) { message = tr("msg.axisNotFor", label); return }
        val cols = (sel.left..sel.right).toList()
        val users = t.axisUsers(src).size
        done(t.editAxis(src, axis.values.size, cols, adj.first, adj.second), tr("op.onAxis", label, users), src)
    }

    fun typeChar(ch: Char) {
        if (editingFrozen) return
        val cur = entry ?: ""
        if (cur.length >= 12) return
        if (ch == '-' && cur.isNotEmpty()) return
        if (ch == '.' && cur.contains('.')) return
        entry = cur + ch
    }

    fun backspace() { entry = entry?.dropLast(1) }

    /** 把打的數字放進選取的格子（選了一片就整片都設成這個值）。回傳是否成功。 */
    fun commitEntry(): Boolean {
        if (editingFrozen) return false
        val text = entry ?: return true
        entry = null
        if (text.isEmpty()) return true
        val v = text.toDoubleOrNull() ?: run { message = tr("msg.notNumber", text); return false }
        val t = tune ?: return false
        val def = selectedTable?.let { t.definition(it) } ?: return false
        val sel = selection ?: return false
        if (sel.target == SelTarget.Body) {
            done(t.setValues(def.absId, sel.cells().associateWith { v }), tr("op.set", text), def.absId)
        } else {
            val lookup = if (sel.target == SelTarget.AxisX) t.axisX(def) else t.axisY(def)
            val axis = (lookup as? AxisLookup.Found)?.axis
            val src = axis?.sourceTable ?: run { message = tr("msg.axisFixed"); return false }
            done(t.editAxis(src, axis.values.size, (sel.left..sel.right).toList(), AdjustOp.Set, v), tr("op.onAxis", tr("op.set", text), t.axisUsers(src).size), src)
        }
        return true
    }

    /** 選取的格子轉成文字：欄用 Tab、列用換行分隔，可以貼進試算表。 */
    fun copyText(): String? {
        val t = tune ?: return null
        val def = selectedTable?.let { t.definition(it) } ?: return null
        val sel = selection?.takeIf { it.target == SelTarget.Body } ?: return null
        val rows = t.rows(def.absId) ?: return null
        val text = (sel.top..sel.bottom).joinToString("\n") { r -> (sel.left..sel.right).joinToString("\t") { c -> cellText(def, rows[r][c]) } }
        message = tr("msg.copied", sel.count)
        return text
    }

    /**
     * 貼上：文字裡是 Tab／逗號／空白分隔的數字。
     * 只有一個數字時填滿選取範圍；否則從選取的左上角開始放，超出表的部分略過。
     */
    fun pasteText(text: String) {
        if (editingFrozen) return
        val t = tune ?: return
        val def = selectedTable?.let { t.definition(it) } ?: return
        val sel = selection?.takeIf { it.target == SelTarget.Body } ?: run { message = tr("msg.pasteWhere"); return }
        val block = text.lines().filter { it.isNotBlank() }.map { line ->
            line.trim().split('\t', ',', ' ').filter { it.isNotBlank() }.map { it.toDoubleOrNull() }
        }
        if (block.isEmpty() || block.any { row -> row.any { it == null } }) { message = tr("msg.pasteNaN"); return }
        val nx = minOf(t.effectiveColumns(def), def.storedColumns)
        val ny = minOf(t.effectiveRows(def), def.storedRows)
        val values = HashMap<Cell, Double>()
        if (block.size == 1 && block[0].size == 1) {
            for (c in sel.cells()) values[c] = block[0][0]!!
        } else {
            for ((dr, row) in block.withIndex()) for ((dc, v) in row.withIndex()) {
                val r = sel.top + dr
                val c = sel.left + dc
                if (r < ny && c < nx) values[Cell(r, c)] = v!!
            }
        }
        done(t.setValues(def.absId, values), tr("op.paste"), def.absId)
    }

    fun setRaw(absId: Int, cell: Cell, raw: Long, what: String) {
        if (editingFrozen) return
        val t = tune ?: return
        done(t.setRaw(absId, cell, raw), what, absId)
    }

    fun undo() {
        if (editingFrozen) return
        val t = tune ?: return
        val id = t.undo()
        if (id == null) { message = tr("msg.nothingToUndo"); return }
        recompare(); revision++
        ecuControl.edited(id)
        message = tr("msg.undone", t.definition(id)?.let(::title) ?: id)
    }

    fun revertTable() {
        if (editingFrozen) return
        val t = tune ?: return
        val id = selectedTable ?: return
        if (!t.isModified(id)) { message = tr("msg.notModified"); return }
        t.revert(id); recompare(); revision++
        ecuControl.edited(id)
        message = tr("msg.reverted")
    }

    /** 選取的格子（或整張表）改用比較對象的值。 */
    fun takeFromOther(wholeTable: Boolean) {
        if (editingFrozen) return
        val t = tune ?: return
        val o = other ?: return
        val id = selectedTable ?: return
        val sel = selection
        if (!wholeTable && (sel == null || sel.target != SelTarget.Body)) { message = tr("msg.selectFirst"); return }
        done(t.copyFrom(o.tune, id, if (wholeTable) null else sel!!.cells()), if (wholeTable) tr("op.takeTable") else tr("op.takeCells"), id)
    }

    val hasChanges: Boolean get() = tune?.hasChanges == true

    fun diffKind(absId: Int): DiffKind? = diffs[absId]?.kind
}
