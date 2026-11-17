package xb.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一張表畫成格子需要的全部內容；每次編輯後重算一份。 */
class GridModel(
    val cols: Int,
    val rows: Int,
    val text: Array<Array<String>>,
    /** 0～1 的冷熱程度；NaN 表示不上色。 */
    val heat: Array<FloatArray>,
    val flags: Array<IntArray>,
    val xLabels: List<String>?,
    val yLabels: List<String>?,
    val xEditable: Boolean,
    val yEditable: Boolean,
    val xFlags: IntArray?,
    val yFlags: IntArray?,
    val corner: String,
) {
    companion object {
        const val MODIFIED = 1
        const val OUT_OF_RANGE = 2
        const val DIFF = 4
    }
}

/** 一格的大小；測試也用這兩個數字算點擊位置。 */
val CELL_W = 70.dp
val CELL_H = 28.dp

/**
 * 油圖上引擎目前的位置（準星），格子座標：x＝欄、y＝列，第 i 格的中心是 i＋0.5，可以超出表格（見 [xb.tune.Scope]）。
 * [corners]：四角顯示的即時值（左上、右上、右下、左下）；不顯示時是 null。
 */
class ScopeMark(val x: Double, val y: Double, val corners: List<String>?)

/**
 * 表格。上方與左方的軸固定不動；可改的軸點下去可以選。
 * 選取用滑鼠拖曳或方向鍵，按住 Shift 延伸。
 */
@Composable
fun TableGrid(
    model: GridModel,
    selection: Selection?,
    onSelect: (Selection) -> Unit,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {},
    onPaste: () -> Unit = {},
    /** 正在格子裡打的字；打字、退格、確定、取消由外面處理。 */
    entry: String? = null,
    onType: (Char) -> Unit = {},
    onBackspace: () -> Unit = {},
    onCommit: () -> Unit = {},
    onCancel: () -> Unit = {},
    /** 準星；null＝不畫。 */
    scope: ScopeMark? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 4096)
    val hState = rememberScrollState()
    val vState = rememberScrollState()
    val focus = remember { FocusRequester() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val sel by rememberUpdatedState(selection)
    val select by rememberUpdatedState(onSelect)
    val m by rememberUpdatedState(model)
    val copy by rememberUpdatedState(onCopy)
    val paste by rememberUpdatedState(onPaste)
    val typing by rememberUpdatedState(entry)
    val type by rememberUpdatedState(onType)
    val backspace by rememberUpdatedState(onBackspace)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val cellStyle = Xb.digits(13.sp, FontWeight.Medium, color = Xb.text)
    val headStyle = Xb.digits(11.5.sp, FontWeight.Medium, color = Xb.dim)
    val headEditStyle = headStyle.copy(color = Xb.brandInk)
    val gap = if (Xb.palette.light) Xb.line.copy(alpha = 0.7f) else Xb.bg
    val shape = RoundedCornerShape(10.dp)
    // 準星照 XTune 兩秒轉一圈
    val spin = rememberInfiniteTransition(label = "scope").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "spin",
    )
    val tagStyle = Xb.digits(11.sp, FontWeight.SemiBold, color = Xb.text)

    // 選取的終點跑出畫面時，捲動到看得到
    val density = LocalDensity.current
    LaunchedEffect(selection, viewport) {
        val s = selection ?: return@LaunchedEffect
        if (s.target != SelTarget.Body || viewport == IntSize.Zero) return@LaunchedEffect
        val cellW = with(density) { CELL_W.toPx() }
        val cellH = with(density) { CELL_H.toPx() }
        val x0 = (s.c1 * cellW).toInt()
        val x1 = ((s.c1 + 1) * cellW).toInt()
        if (x0 < hState.value) hState.scrollTo(x0) else if (x1 > hState.value + viewport.width) hState.scrollTo(x1 - viewport.width)
        val y0 = (s.r1 * cellH).toInt()
        val y1 = ((s.r1 + 1) * cellH).toInt()
        if (y0 < vState.value) vState.scrollTo(y0) else if (y1 > vState.value + viewport.height) vState.scrollTo(y1 - viewport.height)
    }

    fun move(dr: Int, dc: Int, extend: Boolean) {
        val s = sel ?: Selection(SelTarget.Body, 0, 0, 0, 0)
        when (s.target) {
            SelTarget.Body -> {
                val r = (s.r1 + dr).coerceIn(0, m.rows - 1)
                val c = (s.c1 + dc).coerceIn(0, m.cols - 1)
                select(if (extend) s.copy(r1 = r, c1 = c) else Selection(SelTarget.Body, r, c, r, c))
            }
            SelTarget.AxisX -> {
                val c = (s.c1 + dc).coerceIn(0, m.cols - 1)
                select(if (extend) s.copy(c1 = c) else Selection(SelTarget.AxisX, 0, c, 0, c))
            }
            SelTarget.AxisY -> {
                val c = (s.c1 + dr).coerceIn(0, m.rows - 1)
                select(if (extend) s.copy(c1 = c) else Selection(SelTarget.AxisY, 0, c, 0, c))
            }
        }
    }

    Column(
        modifier
            .clip(shape)
            .background(Xb.bg.copy(alpha = if (Xb.palette.light) 0.45f else 0.35f))
            .border(1.dp, Xb.line, shape)
            .testTag("grid")
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = e.isCtrlPressed || e.isMetaPressed
                // 直接打數字：打完按 Enter 或方向鍵確定，Esc 取消
                if (!command) {
                    typedChar(e.key)?.let { type(it); return@onPreviewKeyEvent true }
                    if (typing != null) when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { commit(); return@onPreviewKeyEvent true }
                        Key.Escape -> { cancel(); return@onPreviewKeyEvent true }
                        Key.Backspace -> { backspace(); return@onPreviewKeyEvent true }
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> commit()
                        else -> {}
                    }
                }
                when (e.key) {
                    Key.DirectionUp -> { move(-1, 0, e.isShiftPressed); true }
                    Key.DirectionDown -> { move(1, 0, e.isShiftPressed); true }
                    Key.DirectionLeft -> { move(0, -1, e.isShiftPressed); true }
                    Key.DirectionRight -> { move(0, 1, e.isShiftPressed); true }
                    Key.A -> if (e.isCtrlPressed || e.isMetaPressed) {
                        select(Selection(SelTarget.Body, 0, 0, m.rows - 1, m.cols - 1)); true
                    } else false
                    Key.C -> if (e.isCtrlPressed || e.isMetaPressed) { copy(); true } else false
                    Key.V -> if (e.isCtrlPressed || e.isMetaPressed) { paste(); true } else false
                    else -> false
                }
            },
    ) {
        val hasX = model.xLabels != null
        val hasY = model.yLabels != null
        // 上方：角落＋X 軸
        if (hasX) Row(Modifier.height(CELL_H)) {
            if (hasY) Box(Modifier.size(CELL_W, CELL_H).background(Xb.header), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { centered(measurer, model.corner, headStyle, Offset.Zero, size) }
            }
            Box(Modifier.weight(1f).horizontalScroll(hState, enabled = false)) {
                Canvas(
                    Modifier.size(CELL_W * model.cols, CELL_H).pointerInput(model.cols, model.xEditable) {
                        if (!model.xEditable) return@pointerInput
                        awaitPointerEventScope {
                            var dragging = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.first()
                                val c = (p.position.x / CELL_W.toPx()).toInt().coerceIn(0, m.cols - 1)
                                when (ev.type) {
                                    PointerEventType.Press -> {
                                        dragging = true
                                        runCatching { focus.requestFocus() }
                                        val s = sel
                                        select(if (ev.keyboardModifiers.isShiftPressed && s?.target == SelTarget.AxisX) s.copy(c1 = c) else Selection(SelTarget.AxisX, 0, c, 0, c))
                                    }
                                    PointerEventType.Move -> if (dragging && p.pressed) sel?.takeIf { it.target == SelTarget.AxisX && it.c1 != c }?.let { select(it.copy(c1 = c)) }
                                    PointerEventType.Release -> dragging = false
                                }
                            }
                        }
                    },
                ) {
                    val w = CELL_W.toPx()
                    val h = CELL_H.toPx()
                    for (c in 0 until model.cols) {
                        val o = Offset(c * w, 0f)
                        drawRect(if (model.xEditable) Xb.headerEditable else Xb.header, o, Size(w, h))
                        drawRect(gap, o, Size(w, h), style = Stroke(1f))
                        val mod = model.xFlags?.getOrNull(c)?.let { it and GridModel.MODIFIED != 0 } == true
                        centered(measurer, model.xLabels?.getOrNull(c) ?: "", if (mod) headStyle.copy(color = Xb.modified) else if (model.xEditable) headEditStyle else headStyle, o, Size(w, h))
                        if (mod) crystal(Xb.modified, Offset(o.x + 6f, o.y + 7f), 3.4f, 4.6f, cross = false)
                        if (selection?.target == SelTarget.AxisX && c in selection.left..selection.right) drawRect(Xb.selection, Offset(o.x + 1f, o.y + 1f), Size(w - 2f, h - 2f), style = Stroke(2f))
                        if (entry != null && selection?.target == SelTarget.AxisX && c == selection.c1) entryBox(measurer, entry, cellStyle, o, Size(w, h))
                    }
                }
            }
        }
        Row(Modifier.weight(1f)) {
            // 左方：Y 軸
            if (hasY) Box(Modifier.width(CELL_W).verticalScroll(vState, enabled = false)) {
                Canvas(
                    Modifier.size(CELL_W, CELL_H * model.rows).pointerInput(model.rows, model.yEditable) {
                        if (!model.yEditable) return@pointerInput
                        awaitPointerEventScope {
                            var dragging = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val p = ev.changes.first()
                                val c = (p.position.y / CELL_H.toPx()).toInt().coerceIn(0, m.rows - 1)
                                when (ev.type) {
                                    PointerEventType.Press -> {
                                        dragging = true
                                        runCatching { focus.requestFocus() }
                                        val s = sel
                                        select(if (ev.keyboardModifiers.isShiftPressed && s?.target == SelTarget.AxisY) s.copy(c1 = c) else Selection(SelTarget.AxisY, 0, c, 0, c))
                                    }
                                    PointerEventType.Move -> if (dragging && p.pressed) sel?.takeIf { it.target == SelTarget.AxisY && it.c1 != c }?.let { select(it.copy(c1 = c)) }
                                    PointerEventType.Release -> dragging = false
                                }
                            }
                        }
                    },
                ) {
                    val w = CELL_W.toPx()
                    val h = CELL_H.toPx()
                    for (r in 0 until model.rows) {
                        val o = Offset(0f, r * h)
                        drawRect(if (model.yEditable) Xb.headerEditable else Xb.header, o, Size(w, h))
                        drawRect(gap, o, Size(w, h), style = Stroke(1f))
                        val mod = model.yFlags?.getOrNull(r)?.let { it and GridModel.MODIFIED != 0 } == true
                        centered(measurer, model.yLabels?.getOrNull(r) ?: "", if (mod) headStyle.copy(color = Xb.modified) else if (model.yEditable) headEditStyle else headStyle, o, Size(w, h))
                        if (mod) crystal(Xb.modified, Offset(o.x + 6f, o.y + 7f), 3.4f, 4.6f, cross = false)
                        if (selection?.target == SelTarget.AxisY && r in selection.left..selection.right) drawRect(Xb.selection, Offset(o.x + 1f, o.y + 1f), Size(w - 2f, h - 2f), style = Stroke(2f))
                        if (entry != null && selection?.target == SelTarget.AxisY && r == selection.c1) entryBox(measurer, entry, cellStyle, o, Size(w, h))
                    }
                }
            }
            // 內容
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewport = it }) {
                Box(Modifier.verticalScroll(vState).horizontalScroll(hState)) {
                    Canvas(
                        Modifier.size(CELL_W * model.cols, CELL_H * model.rows).testTag("gridBody").pointerInput(model.cols, model.rows) {
                            awaitPointerEventScope {
                                var dragging = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val p = ev.changes.first()
                                    val c = (p.position.x / CELL_W.toPx()).toInt().coerceIn(0, m.cols - 1)
                                    val r = (p.position.y / CELL_H.toPx()).toInt().coerceIn(0, m.rows - 1)
                                    when (ev.type) {
                                        PointerEventType.Press -> {
                                            dragging = true
                                            runCatching { focus.requestFocus() }
                                            val s = sel
                                            select(if (ev.keyboardModifiers.isShiftPressed && s?.target == SelTarget.Body) s.copy(r1 = r, c1 = c) else Selection(SelTarget.Body, r, c, r, c))
                                        }
                                        PointerEventType.Move -> if (dragging && p.pressed) sel?.takeIf { it.target == SelTarget.Body && (it.r1 != r || it.c1 != c) }?.let { select(it.copy(r1 = r, c1 = c)) }
                                        PointerEventType.Release -> dragging = false
                                    }
                                }
                            }
                        },
                    ) {
                        val w = CELL_W.toPx()
                        val h = CELL_H.toPx()
                        // 只畫看得到的範圍
                        val c0 = (hState.value / w).toInt().coerceIn(0, model.cols - 1)
                        val c1 = if (viewport.width > 0) ((hState.value + viewport.width) / w).toInt().coerceIn(0, model.cols - 1) else model.cols - 1
                        val r0 = (vState.value / h).toInt().coerceIn(0, model.rows - 1)
                        val r1 = if (viewport.height > 0) ((vState.value + viewport.height) / h).toInt().coerceIn(0, model.rows - 1) else model.rows - 1
                        for (r in r0..r1) for (c in c0..c1) {
                            val o = Offset(c * w, r * h)
                            val f = model.flags[r][c]
                            drawRect(Xb.heat(model.heat[r][c]), o, Size(w, h))
                            drawRect(gap, o, Size(w, h), style = Stroke(1f))
                            val style = if (f and GridModel.OUT_OF_RANGE != 0) cellStyle.copy(color = Xb.bad, fontWeight = FontWeight.Bold) else cellStyle
                            centered(measurer, model.text[r][c], style, o, Size(w, h))
                            // 超出定義範圍：右上角一個紅色三角
                            if (f and GridModel.OUT_OF_RANGE != 0) drawPath(Path().apply { moveTo(o.x + w - 10f, o.y + 1f); lineTo(o.x + w - 1f, o.y + 1f); lineTo(o.x + w - 1f, o.y + 10f); close() }, Xb.bad)
                            // 改過：左上角一顆小水晶
                            if (f and GridModel.MODIFIED != 0) crystal(Xb.modified, Offset(o.x + 6f, o.y + 7f), 3.4f, 4.6f, cross = false)
                            if (f and GridModel.DIFF != 0) drawRect(Xb.warn, Offset(o.x + 1.5f, o.y + 1.5f), Size(w - 3f, h - 3f), style = Stroke(1.5f))
                        }
                        val s = selection
                        if (s != null && s.target == SelTarget.Body) {
                            val so = Offset(s.left * w, s.top * h)
                            val ss = Size((s.right - s.left + 1) * w, (s.bottom - s.top + 1) * h)
                            drawRect(Xb.selection.copy(alpha = if (Xb.palette.light) 0.14f else 0.18f), so, ss)
                            drawRect(Xb.brand.copy(alpha = 0.35f), Offset(so.x - 1f, so.y - 1f), Size(ss.width + 2f, ss.height + 2f), style = Stroke(4f))
                            drawRect(Xb.selection, Offset(so.x + 1f, so.y + 1f), Size(ss.width - 2f, ss.height - 2f), style = Stroke(2f))
                            if (entry != null && s.r1 < model.rows && s.c1 < model.cols) entryBox(measurer, entry, cellStyle, Offset(s.c1 * w, s.r1 * h), Size(w, h))
                        }
                        if (scope != null) drawScope(measurer, scope, model, w, h, spin.value, tagStyle)
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(vState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                HorizontalScrollbar(rememberScrollbarAdapter(hState), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

/** 鍵盤上能直接打進格子的字：數字、小數點、負號。 */
private fun typedChar(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    Key.Period, Key.NumPadDot -> '.'
    Key.Minus, Key.NumPadSubtract -> '-'
    else -> null
}

/**
 * 畫準星：一個會轉的瞄準圈加十字，四角可選擇顯示即時值。
 * 超出表格時畫在邊上（變淡），讓人知道引擎在表的範圍外。
 */
private fun DrawScope.drawScope(measurer: TextMeasurer, s: ScopeMark, model: GridModel, w: Float, h: Float, angle: Float, tagStyle: TextStyle) {
    val maxX = model.cols.toDouble()
    val maxY = model.rows.toDouble()
    val outside = s.x < 0 || s.x > maxX || s.y < 0 || s.y > maxY
    val cx = (s.x.coerceIn(0.12, maxX - 0.12) * w).toFloat()
    val cy = (s.y.coerceIn(0.12, maxY - 0.12) * h).toFloat()
    val c = Offset(cx, cy)
    val color = if (outside) Xb.warn.copy(alpha = 0.55f) else Xb.brand
    val r = minOf(w, h) * 0.62f
    val halo = Xb.bg.copy(alpha = 0.55f)
    drawCircle(halo, r + 2.5f, c, style = Stroke(5f))
    rotate(angle, c) {
        // 四段弧，中間留缺口，轉起來看得出在動
        for (k in 0 until 4) drawArc(color, k * 90f + 12f, 66f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2), style = Stroke(2.4f))
    }
    val t = r * 0.55f
    drawLine(halo, Offset(cx - r - 4f, cy), Offset(cx + r + 4f, cy), 3.5f)
    drawLine(halo, Offset(cx, cy - r - 4f), Offset(cx, cy + r + 4f), 3.5f)
    drawLine(color, Offset(cx - r - 4f, cy), Offset(cx - t, cy), 1.6f)
    drawLine(color, Offset(cx + t, cy), Offset(cx + r + 4f, cy), 1.6f)
    drawLine(color, Offset(cx, cy - r - 4f), Offset(cx, cy - t), 1.6f)
    drawLine(color, Offset(cx, cy + t), Offset(cx, cy + r + 4f), 1.6f)
    drawCircle(color, 2.2f, c)
    val tags = s.corners ?: return
    val gapPx = r + 3f
    for ((i, text) in tags.withIndex()) {
        if (text.isEmpty()) continue
        val layout = measurer.measure(text, tagStyle, maxLines = 1)
        val tw = layout.size.width.toFloat()
        val th = layout.size.height.toFloat()
        val x = if (i == 0 || i == 3) cx - gapPx - tw else cx + gapPx
        val y = if (i <= 1) cy - gapPx - th else cy + gapPx
        drawRoundRect(Xb.panel.copy(alpha = 0.82f), Offset(x - 3f, y - 1f), Size(tw + 6f, th + 2f), androidx.compose.ui.geometry.CornerRadius(4f))
        drawText(layout, topLeft = Offset(x, y))
    }
}

/** 正在打字的格子：蓋掉原本的值，顯示打到一半的字。 */
private fun DrawScope.entryBox(measurer: TextMeasurer, entry: String, style: TextStyle, origin: Offset, box: Size) {
    drawRect(Xb.panel, origin, box)
    drawRect(Xb.brand, Offset(origin.x + 1f, origin.y + 1f), Size(box.width - 2f, box.height - 2f), style = Stroke(2f))
    centered(measurer, entry + "▏", style, origin, box)
}

private fun DrawScope.centered(measurer: TextMeasurer, text: String, style: TextStyle, origin: Offset, box: Size) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style, maxLines = 1)
    drawText(layout, topLeft = Offset(origin.x + (box.width - layout.size.width) / 2f, origin.y + (box.height - layout.size.height) / 2f))
}
