package xb.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xb.tune.Axis
import xb.tune.AxisLookup
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.DiffKind
import xb.tune.Scope
import xb.tune.TableDef
import xb.tune.TableKind
import xb.tune.Tune
import java.util.Locale

fun fmt(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals.coerceIn(0, 6)}f", v)

/** 軸刻度：整數不帶小數，其餘最多兩位。 */
fun fmtAxis(v: Float): String {
    val d = v.toDouble()
    return if (d == Math.rint(d)) String.format(Locale.ROOT, "%.0f", d)
    else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')
}

/** 一格要顯示的字。超出定義範圍的值顯示真正的值，不夾在 Min～Max。 */
fun cellText(def: TableDef, raw: Long): String =
    if (def.gain == 0f) raw.toString()
    else if (Convert.isOutOfRange(def, raw)) fmt(Convert.rawToPhysicalExact(def, raw), def.decimals)
    else fmt(Convert.rawToPhysical(def, raw), def.decimals)

fun buildGrid(state: AppState, tune: Tune, def: TableDef): GridModel {
    val rows = tune.rows(def.absId)!!
    val ax = (tune.axisX(def) as? AxisLookup.Found)?.axis
    val ay = (tune.axisY(def) as? AxisLookup.Found)?.axis
    val nx = minOf(tune.effectiveColumns(def), def.storedColumns)
    val ny = minOf(tune.effectiveRows(def), def.storedRows)
    val diff = state.diffs[def.absId]?.cells?.map { it.row to it.col }?.toSet() ?: emptySet()
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    val phys = Array(ny) { r -> DoubleArray(nx) { c -> if (def.gain == 0f) rows[r][c].toDouble() else Convert.rawToPhysical(def, rows[r][c]) } }
    for (r in phys) for (v in r) { if (v < lo) lo = v; if (v > hi) hi = v }
    val span = hi - lo
    val heat = Array(ny) { r -> FloatArray(nx) { c -> if (nx * ny == 1 || span <= 0.0) Float.NaN else ((phys[r][c] - lo) / span).toFloat() } }
    val flags = Array(ny) { r ->
        IntArray(nx) { c ->
            var f = 0
            if (tune.isCellModified(def.absId, r, c)) f = f or GridModel.MODIFIED
            if (def.gain != 0f && Convert.isOutOfRange(def, rows[r][c])) f = f or GridModel.OUT_OF_RANGE
            if ((r to c) in diff) f = f or GridModel.DIFF
            f
        }
    }
    fun axisFlags(a: Axis?): IntArray? = a?.sourceTable?.let { src -> IntArray(a.values.size) { if (tune.isCellModified(src, 0, it)) GridModel.MODIFIED else 0 } }
    return GridModel(
        cols = nx, rows = ny,
        text = Array(ny) { r -> Array(nx) { c -> cellText(def, rows[r][c]) } },
        heat = heat, flags = flags,
        xLabels = ax?.values?.map(::fmtAxis), yLabels = ay?.values?.map(::fmtAxis),
        xEditable = ax?.editable == true, yEditable = ay?.editable == true,
        xFlags = axisFlags(ax), yFlags = axisFlags(ay),
        corner = listOfNotNull(ay?.name, ax?.name).joinToString("＼"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TableEditor(state: AppState, clipboard: ClipboardBridge, modifier: Modifier = Modifier) {
    if (state.editingFrozen) {
        Box(modifier.panel(), contentAlignment = Alignment.Center) { Text(tr("ecu.editingFrozen"), color = Xb.dim) }
        return
    }
    val tune = state.tune
    val def = state.selectedTable?.let { tune?.definition(it) }
    if (tune == null || def == null || tune.rows(def.absId) == null) {
        Box(modifier.panel(), contentAlignment = Alignment.Center) { Text(tr("editor.pick"), color = Xb.dim) }
        return
    }
    val rev = state.revision
    val scope = rememberCoroutineScope()
    val model = remember(def.absId, rev, state.other) { buildGrid(state, tune, def) }
    val level = state.level(def)
    // 即時值：連著 ECU 用監控串流，沒連就用手機（ReX）同步過來的；準星與 Cell Track 用
    val phoneFeed by state.phoneSync.sync.live.collectAsState()
    val values = state.liveValues?.collectAsState()?.value
        ?: phoneFeed?.takeIf { it.online && it.defs == state.session?.platform?.definition }?.values
    val scopeVars = Scope.vars(def)
    val mark = if (state.scopeOn && scopeVars != null && values != null) scopeMark(state, tune, def, model, scopeVars, values) else null
    if (mark != null && state.cellTrack) CellTrack(state, def.absId, mark, model)
    Column(modifier.panel().padding(horizontal = 16.dp, vertical = 14.dp)) {
        // 標題
        Row(verticalAlignment = Alignment.CenterVertically) {
            val neighbours = remember(def.absId, rev, state.query, state.showAll, state.diffOnly) { state.tables().flatMap { g -> g.tables.map { it.def.absId } } }
            val at = neighbours.indexOf(def.absId)
            ToolButton("‹", tr("editor.prev.hint"), enabled = at > 0, tag = "prevTable") { state.select(neighbours[at - 1]) }
            Spacer(Modifier.width(4.dp))
            ToolButton("›", tr("editor.next.hint"), enabled = at in 0 until neighbours.size - 1, tag = "nextTable") { state.select(neighbours[at + 1]) }
            Spacer(Modifier.width(10.dp))
            Text(state.title(def), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Xb.text, modifier = Modifier.testTag("tableTitle"))
            Spacer(Modifier.width(10.dp))
            Text("${def.name}  #${def.absId}", style = Xb.digits(12.sp), color = Xb.dim)
            if (tune.isModified(def.absId)) { Spacer(Modifier.width(10.dp)); Pill(tr("editor.modified"), Xb.modified) }
            val live = (state.ecu as? EcuPhase.Live)?.live
            val liveStates = live?.states?.collectAsState()?.value
            liveBadge(liveStates?.get(def.absId))?.let { (txt, col) -> Spacer(Modifier.width(8.dp)); Pill(txt, col) }
            if (live != null && !live.writable(def.absId) && liveStates?.get(def.absId) !is xb.link.LiveTuning.TableState.Refused) {
                Spacer(Modifier.width(8.dp)); Pill(tr("editor.notOnEcu"), Xb.dim)
            }
        }
        (state.ecu as? EcuPhase.Live)?.live?.states?.collectAsState()?.value?.get(def.absId)?.let { st ->
            liveDetail(st)?.let { Spacer(Modifier.height(6.dp)); Text(it, fontSize = 12.sp, color = Xb.bad, lineHeight = 18.sp, modifier = Modifier.testTag("liveDetail")) }
        }
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(when (def.kind) { TableKind.D1 -> tr("kind.d1"); TableKind.D2 -> tr("kind.d2"); TableKind.D3 -> tr("kind.d3") }, Xb.brandInk)
            if (def.unit.isNotBlank() && def.unit != "NA" && def.unit != "_") Pill(def.unit, digits = true)
            if (def.gain != 0f) Pill("${fmt(def.min, def.decimals)} ～ ${fmt(def.max, def.decimals)}", digits = true)
            if (def.kind != TableKind.D1) {
                Pill("${model.cols}×${model.rows}", digits = true)
                if (model.cols != def.storedColumns || model.rows != def.storedRows) Text(tr("editor.stored", def.storedColumns, def.storedRows), fontSize = 11.5.sp, color = Xb.dim)
            }
            if (level != null && level > 1) Pill(tr("editor.advanced", level), Xb.warn)
            if (tune.file.tables[def.absId]?.attached == true) Pill(tr("editor.attached"), Xb.partner)
            if (scopeVars != null) {
                Spacer(Modifier.weight(1f))
                val reg = state.ecuControl.registry()
                val axisNames = listOfNotNull(scopeVars.first, scopeVars.second).joinToString(" × ") { reg?.values?.get(it)?.label ?: "#$it" }
                if (values != null) Text(axisNames, fontSize = 11.5.sp, color = Xb.dim, modifier = Modifier.testTag("scopeAxes"))
                Spacer(Modifier.width(6.dp))
                ToolButton(tr("editor.scope"), tr("editor.scope.hint"), primary = state.scopeOn, tag = "scopeToggle") { state.switchScope(!state.scopeOn) }
                ToolButton("Cell Track", tr("editor.cellTrack.hint"), primary = state.cellTrack, enabled = state.scopeOn, tag = "cellTrack") { state.switchCellTrack(!state.cellTrack) }
            }
        }
        state.session?.labels?.description(def.absId, state.lang)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.lines().filter { l -> l.isNotBlank() }.joinToString("\n"), fontSize = 12.5.sp, color = Xb.text.copy(alpha = 0.78f), maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(12.dp))

        // 工具列
        if (!def.adjustDisabled && def.gain != 0f) {
            // 視窗窄的時候整列自動換行。
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fieldShape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.clip(fieldShape).background(Xb.bg.copy(alpha = if (Xb.palette.light) 0.6f else 0.5f)).border(1.dp, Xb.line, fieldShape).padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("editor.operand"), fontSize = 12.sp, color = Xb.dim)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = state.operand,
                        onValueChange = { v -> state.operand = v.filter { it.isDigit() || it == '.' || it == '-' }.take(12) },
                        singleLine = true,
                        textStyle = Xb.digits(15.sp, FontWeight.SemiBold, color = Xb.text),
                        cursorBrush = SolidColor(Xb.brand),
                        modifier = Modifier.width(72.dp).testTag("operand"),
                    )
                }
                // XTune 的步進：每張表各自記住，＋步進／−步進用它
                Row(
                    Modifier.clip(fieldShape).background(Xb.bg.copy(alpha = if (Xb.palette.light) 0.6f else 0.5f)).border(1.dp, Xb.line, fieldShape).padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("editor.step"), fontSize = 12.sp, color = Xb.dim)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = state.stepText,
                        onValueChange = { state.editStep(it) },
                        singleLine = true,
                        textStyle = Xb.digits(15.sp, FontWeight.SemiBold, color = Xb.text),
                        cursorBrush = SolidColor(Xb.brand),
                        modifier = Modifier.width(56.dp).testTag("step"),
                    )
                }
                ToolButton("＋" + tr("editor.stepShort"), tr("editor.stepUp.hint"), tag = "stepUp") { state.apply(Op.StepUp) }
                ToolButton("−" + tr("editor.stepShort"), tr("editor.stepDown.hint"), tag = "stepDown") { state.apply(Op.StepDown) }
                Spacer(Modifier.width(8.dp))
                ToolButton("＋", tr("editor.add.hint")) { state.apply(Op.Add) }
                ToolButton("−", tr("editor.sub.hint")) { state.apply(Op.Subtract) }
                ToolButton("×", tr("editor.mul.hint")) { state.apply(Op.Multiply) }
                ToolButton("÷", tr("editor.div.hint")) { state.apply(Op.Divide) }
                ToolButton("＝", tr("editor.set.hint")) { state.apply(Op.Set) }
                Spacer(Modifier.width(8.dp))
                if (def.kind != TableKind.D1) {
                    ToolButton("LINEAR", tr("editor.linear.hint"), digits = true) { state.apply(Op.Linear) }
                    ToolButton("SMOOTH", tr("editor.smooth.hint"), digits = true) { state.apply(Op.Smooth) }
                    Spacer(Modifier.width(8.dp))
                }
                ToolButton(tr("editor.revert"), tr("editor.revert.hint"), enabled = tune.isModified(def.absId)) { state.revertTable() }
                if (state.ecuControl.live?.writable(def.absId) == true) {
                    Spacer(Modifier.width(8.dp))
                    ToolButton(tr("editor.readAll"), tr("editor.readAll.hint"), tag = "readAll") { state.ecuControl.readTable(scope, def.absId) }
                    ToolButton(tr("editor.writeAll"), tr("editor.writeAll.hint"), tag = "writeAll") { state.ecuControl.writeTable(scope, def.absId) }
                }
                if (state.other != null && state.diffKind(def.absId) == DiffKind.Different) {
                    Spacer(Modifier.width(8.dp))
                    ToolButton(tr("op.takeCells"), tr("editor.takeCells.hint")) { state.takeFromOther(false) }
                    ToolButton(tr("op.takeTable"), tr("editor.takeTable.hint")) { state.takeFromOther(true) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 位元選項與開關
        val raw0 = tune.rows(def.absId)!![0][0]
        if (def.mark == "Option" && def.kind == TableKind.D1) {
            val names = def.optionBits.split('-')
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                names.forEachIndexed { bit, name ->
                    if (name.isBlank() || name == "_") return@forEachIndexed
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = (raw0 shr bit) and 1L == 1L,
                            onCheckedChange = { on ->
                                val next = if (on) raw0 or (1L shl bit) else raw0 and (1L shl bit).inv()
                                state.setRaw(def.absId, Cell(0, 0), next, tr("editor.bitSet", name, if (on) tr("common.on") else tr("common.off")))
                            },
                        )
                        Text(name, fontSize = 13.sp, color = Xb.text)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else if (def.adjustDisabled && def.kind == TableKind.D1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = raw0 != 0L, onCheckedChange = { on -> state.setRaw(def.absId, Cell(0, 0), if (on) 1 else 0, if (on) tr("common.on") else tr("common.off")) }, modifier = Modifier.testTag("switch"))
                Spacer(Modifier.width(10.dp))
                Text(if (raw0 != 0L) tr("common.on") else tr("common.off"), fontSize = 14.sp, color = Xb.text)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (def.adjustDisabled && def.kind == TableKind.D1) {
            // 開關與位元選項不用格子；原始值寫在下面的說明列
            Spacer(Modifier.weight(1f))
        } else {
            TableGrid(
                model, state.selection, { state.entry = null; state.selection = it; state.lastTouchMs = System.currentTimeMillis() }, Modifier.weight(1f).fillMaxWidth(),
                onCopy = { state.copyText()?.let(clipboard::set) },
                onPaste = { clipboard.get()?.let(state::pasteText) },
                entry = state.entry,
                onType = state::typeChar,
                onBackspace = state::backspace,
                onCommit = { state.commitEntry() },
                onCancel = { state.entry = null },
                scope = mark,
            )
        }

        // 選取資訊
        Spacer(Modifier.height(10.dp))
        Text(selectionInfo(state, tune, def, model), fontSize = 12.sp, color = Xb.dim, maxLines = 2, modifier = Modifier.testTag("cellInfo"))
    }
}

private fun selectionInfo(state: AppState, tune: Tune, def: TableDef, model: GridModel): String {
    val s = state.selection ?: return tr("sel.none")
    state.entry?.let { return tr("sel.typing", it) + if (s.count > 1 && s.target == SelTarget.Body) tr("sel.typing.many", s.count) else "" }
    if (s.target != SelTarget.Body) {
        val lookup = if (s.target == SelTarget.AxisX) tune.axisX(def) else tune.axisY(def)
        val axis = (lookup as? AxisLookup.Found)?.axis ?: return ""
        val src = axis.sourceTable ?: return tr("msg.axisFixed")
        val n = tune.axisUsers(src).size
        return tr("sel.axis", axis.name, s.right - s.left + 1, n)
    }
    if (s.r1 >= model.rows || s.c1 >= model.cols) return ""
    val raw = tune.rows(def.absId)!![s.r1][s.c1]
    val parts = ArrayList<String>()
    if (s.count > 1) parts.add(tr("sel.count", s.count))
    val pos = listOfNotNull(model.xLabels?.getOrNull(s.c1)?.let { "X ${it}" }, model.yLabels?.getOrNull(s.r1)?.let { "Y ${it}" })
    if (pos.isNotEmpty()) parts.add(pos.joinToString(tr("sep.list")))
    parts.add(tr("sel.shown", cellText(def, raw)))
    parts.add(tr("sel.raw", raw))
    if (def.gain != 0f) {
        if (Convert.isOutOfRange(def, raw)) parts.add(tr("sel.outOfRange", fmt(Convert.rawToPhysical(def, raw), def.decimals)))
        else if (!Convert.isReversible(def, raw)) parts.add(tr("sel.notReversible"))
    }
    state.diffs[def.absId]?.cells?.firstOrNull { it.row == s.r1 && it.col == s.c1 }?.let {
        val o = state.other?.tune?.definition(def.absId) ?: def
        parts.add(tr("sel.other", cellText(o, it.right)) + if (!it.shownDiffers) tr("sel.other.sameShown", it.right) else "")
    }
    return parts.joinToString(tr("sep.parts"))
}

/**
 * 這張表的準星位置。X 軸變數對欄、Y 軸變數對列；某個方向沒有變數或沒有軸就放在中間。
 * 軸變數還沒收到值（剛連上、串流斷了）就不畫。
 */
private fun scopeMark(state: AppState, tune: Tune, def: TableDef, model: GridModel, vars: Pair<Int?, Int?>, values: Map<Int, Double>): ScopeMark? {
    fun axisPos(v: Int?, lookup: AxisLookup, cells: Int): Double? {
        if (v == null) return cells / 2.0
        val value = values[v] ?: return null
        val axis = (lookup as? AxisLookup.Found)?.axis ?: return cells / 2.0
        val n = minOf(axis.values.size, cells)
        return Scope.locate(value, axis.values.copyOf(n))?.pos
    }
    val x = axisPos(vars.first, tune.axisX(def), model.cols) ?: return null
    val y = axisPos(vars.second, tune.axisY(def), model.rows) ?: return null
    val corners = if (state.scopeShowVars) {
        val reg = state.ecuControl.registry()
        state.scopeVars.map { id ->
            val ch = reg?.values?.get(id) ?: return@map ""
            val v = values[id] ?: return@map ch.label
            "${ch.label} ${fmt(v, ch.decimals ?: 0)}"
        }
    } else null
    return ScopeMark(x, y, corners)
}

/** 準星所在的格子（Cell Track 用）：格子座標取整，夾在表格範圍內。 */
private fun nearestCell(mark: ScopeMark, model: GridModel): Cell =
    Cell(kotlin.math.floor(mark.y).toInt().coerceIn(0, model.rows - 1), kotlin.math.floor(mark.x).toInt().coerceIn(0, model.cols - 1))

/**
 * Cell Track（同 XTune）：手放開 3 秒後，選取自動跳到準星所在的格子，表格跟著捲過去。
 * 正在打字、選了一片、或選在軸上的時候不搶。
 */
@Composable
private fun CellTrack(state: AppState, absId: Int, mark: ScopeMark, model: GridModel) {
    val cell = nearestCell(mark, model)
    val latest = androidx.compose.runtime.rememberUpdatedState(cell)
    androidx.compose.runtime.LaunchedEffect(absId) {
        while (true) {
            kotlinx.coroutines.delay(200)
            if (state.entry != null) continue
            if (System.currentTimeMillis() - state.lastTouchMs < CELL_TRACK_IDLE_MS) continue
            val sel = state.selection
            if (sel != null && (sel.target != SelTarget.Body || sel.count > 1)) continue
            val c = latest.value
            if (sel == null || sel.r1 != c.row || sel.c1 != c.col) state.selection = Selection(SelTarget.Body, c.row, c.col, c.row, c.col)
        }
    }
}

/** Cell Track 等使用者停手多久（XTune：3 秒）。 */
const val CELL_TRACK_IDLE_MS = 3_000L
