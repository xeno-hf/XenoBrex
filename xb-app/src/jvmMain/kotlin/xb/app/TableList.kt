package xb.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xb.tune.CalItems
import xb.link.LiveTuning
import xb.tune.DiffKind
import xb.tune.TableDef

private sealed class ListRow {
    class Group(val g: GroupEntry) : ListRow()
    class Table(val t: TableEntry) : ListRow()
    class FGroup(val g: CalItems.Group) : ListRow()
    class FItem(val item: CalItems.Item, val usable: List<Int>, val open: Boolean) : ListRow()
    class FTable(val item: CalItems.Item, val absId: Int) : ListRow()
}

/** CalItems 的字串用 XTune 的語系代號；日文有自己的字串（表名沒有，所以 [AppState.lang] 給 en-US）。 */
internal fun calLang() = when (I18n.lang) { Lang.ZH_HANT -> "zh-TW"; Lang.ZH_HANS -> "zh-CN"; Lang.JA -> "ja-JP"; else -> "en-US" }

/** 左邊的表清單：依群組分段、可以收合、可以搜尋。 */
@Composable
fun TableList(state: AppState, modifier: Modifier = Modifier) {
    val rev = state.revision
    val groups = remember(state.session, state.showAll, state.query, state.diffOnly, state.diffs) { state.tables() }
    val collapsed = remember(state.session) { mutableStateMapOf<String, Boolean>() }
    val tune = state.tune
    val liveStates = (state.ecu as? EcuPhase.Live)?.live?.states?.collectAsState()?.value ?: emptyMap()
    Column(modifier.panel()) {
        val searchShape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().padding(10.dp).clip(searchShape).background(Xb.bg.copy(alpha = if (Xb.palette.light) 0.6f else 0.5f))
                .border(1.dp, Xb.line, searchShape).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (state.query.isEmpty()) Text(tr("list.search"), fontSize = 13.sp, color = Xb.dim)
            BasicTextField(
                value = state.query,
                onValueChange = { state.query = it },
                singleLine = true,
                textStyle = TextStyle(color = Xb.text, fontSize = 13.sp),
                cursorBrush = SolidColor(Xb.brand),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
        val node = state.featureNode
        if (node != null) Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChip(tr("list.mode.features"), state.features, "modeFeatures") { state.features = true; state.settings.features = true }
            ModeChip(tr("list.mode.tables"), !state.features, "modeTables") { state.features = false; state.settings.features = false }
        }
        val featureMode = node != null && state.features && state.query.isBlank() && !state.diffOnly
        if (featureMode) { FeatureList(state); return@Column }
        val total = groups.sumOf { it.tables.size }
        Text(
            (if (state.diffOnly) tr("list.countDiff", total) else tr("list.count", total)) + if (state.showAll) tr("list.withAdvanced") else "",
            fontSize = 11.sp, color = Xb.dim, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
        )
        val rows = buildList {
            for (g in groups) {
                add(ListRow.Group(g))
                if (collapsed[g.name] != true) g.tables.forEach { add(ListRow.Table(it)) }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("tableList"), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
            items(rows, key = { if (it is ListRow.Group) "g:${it.g.name}" else "t:${(it as ListRow.Table).t.def.absId}" }) { row ->
                when (row) {
                    is ListRow.Group -> Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { collapsed[row.g.name] = collapsed[row.g.name] != true }
                            .padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (collapsed[row.g.name] == true) "▸" else "▾", fontSize = 10.sp, color = Xb.brandInk)
                        Spacer(Modifier.width(6.dp))
                        Text(row.g.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Xb.brandInk, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${row.g.tables.size}", style = Xb.digits(11.sp), color = Xb.dim)
                    }
                    is ListRow.Table -> TableRow(state, row.t.def, row.t.title, row.t.level, liveStates)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        label, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Xb.brandInk else Xb.dim,
        modifier = Modifier.clip(shape).background(if (on) Xb.brand.copy(alpha = if (Xb.palette.light) 0.22f else 0.16f) else Color.Transparent)
            .border(1.dp, if (on) Xb.brand.copy(alpha = 0.5f) else Xb.line, shape).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp).testTag(tag),
    )
}

/** 一張表的那一列：選取、比對結果、即時狀態、改過的點。[indent] 給功能清單底下的表用。 */
@Composable
private fun TableRow(state: AppState, d: TableDef, title: String, level: Int, liveStates: Map<Int, LiveTuning.TableState>, indent: Int = 0) {
    val rev = state.revision
    val tune = state.tune
    val selected = state.selectedTable == d.absId
    val modified = rev >= 0 && tune?.isModified(d.absId) == true
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = indent.dp).height(if (title != d.name) 44.dp else 32.dp).clip(shape)
            .background(if (selected) Xb.brand.copy(alpha = if (Xb.palette.light) 0.22f else 0.16f) else Color.Transparent)
            .clickable { state.select(d.absId) }
            .testTag("table:${d.absId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 選到的那一列：左邊一條代表色
        Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 7.dp).background(if (selected) Xb.brand else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (level > 1) Xb.text.copy(alpha = 0.72f) else Xb.text,
            )
            if (title != d.name) Text(d.name, style = Xb.digits(10.sp), color = Xb.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.diffs[d.absId]?.let { diff ->
            Pill(
                when (diff.kind) {
                    DiffKind.Different -> "${diff.cells.size}"
                    DiffKind.OnlyLeft -> tr("diff.onlyLeft")
                    DiffKind.OnlyRight -> tr("diff.onlyRight")
                    DiffKind.SizeDiffers -> tr("diff.size")
                },
                Xb.warn, digits = diff.kind == DiffKind.Different,
            )
        }
        liveStates[d.absId]?.let { st -> liveBadge(st)?.let { (txt, col) -> Spacer(Modifier.width(4.dp)); Pill(txt, col) } }
        if (modified) {
            Spacer(Modifier.width(6.dp))
            CrystalDot(Xb.modified, glow = false, Modifier.size(8.dp, 11.dp))
        }
        Spacer(Modifier.width(8.dp))
    }
}

/**
 * 功能清單（XTune 的調校選單：群組 → 項目）。油圖頁（d3）點了打開它的第一張表、展開其餘的；
 * 其他頁（單值、回火、快排、eBoost…）在編輯區開功能專頁（xb-calui，跟 ReX 同一份畫面）。
 */
@Composable
private fun FeatureList(state: AppState) {
    val tune = state.tune ?: return
    val rev = state.revision
    val lang = calLang()
    val entries = remember(state.session) { state.featureItems() }
    val open = remember(state.session) { mutableStateMapOf<String, Boolean>() }
    val liveStates = (state.ecu as? EcuPhase.Live)?.live?.states?.collectAsState()?.value ?: emptyMap()
    val rows = buildList {
        for (g in CalItems.Group.entries) {
            val inGroup = entries.filter { it.first.group == g }
            if (inGroup.isEmpty()) continue
            add(ListRow.FGroup(g))
            for ((item, usable) in inGroup) {
                // 項目裡有表是目前選的：自動展開
                val isOpen = open[item.key] ?: (state.selectedTable in usable)
                add(ListRow.FItem(item, usable, isOpen))
                // 油圖頁（d3）照舊在清單展開表；其他頁開功能專頁，不展開
                if (isOpen && usable.size > 1 && item.page.grid) usable.forEach { add(ListRow.FTable(item, it)) }
            }
        }
    }
    LazyColumn(Modifier.fillMaxHeight().testTag("featureList"), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
        items(rows, key = {
            when (it) {
                is ListRow.FGroup -> "fg:${it.g.key}"
                is ListRow.FItem -> "fi:${it.item.key}"
                is ListRow.FTable -> "ft:${it.item.key}:${it.absId}"
                else -> it.hashCode()
            }
        }) { row ->
            when (row) {
                is ListRow.FGroup -> Text(
                    CalItems.name(row.g.key, lang), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Xb.brandInk,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                )
                is ListRow.FItem -> {
                    val ready = row.usable.isNotEmpty() || !row.item.page.grid && state.calUsable(row.item)
                    val selected = if (row.item.page.grid) row.usable.size == 1 && state.selectedTable == row.usable[0] && state.calItem == null else state.calItem?.key == row.item.key
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Xb.brand.copy(alpha = if (Xb.palette.light) 0.22f else 0.16f) else Color.Transparent)
                            .clickable(enabled = ready) {
                                if (!row.item.page.grid) { state.calItem = row.item; return@clickable }
                                state.calItem = null
                                val nowOpen = !row.open
                                open[row.item.key] = nowOpen
                                if (nowOpen || row.usable.size == 1) state.select(row.usable[0])
                            }
                            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("feature:${row.item.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                CalItems.name(row.item.key, lang), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (ready) Xb.text else Xb.dim,
                            )
                        }
                        val modified = rev >= 0 && row.usable.any { tune.isModified(it) }
                        if (modified) { CrystalDot(Xb.modified, glow = false, Modifier.size(8.dp, 11.dp)); Spacer(Modifier.width(6.dp)) }
                        if (row.usable.size > 1 && row.item.page.grid) Text(if (row.open) "▾" else "▸", fontSize = 10.sp, color = Xb.dim)
                    }
                }
                is ListRow.FTable -> tune.definition(row.absId)?.let { d ->
                    TableRow(state, d, state.title(d), state.level(d) ?: 1, liveStates, indent = 12)
                }
                else -> {}
            }
        }
    }
}
