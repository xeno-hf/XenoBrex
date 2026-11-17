package xb.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rx.defs.ChannelDef

/**
 * 設定頁（上方工具列的「設定」）：外觀、定義檔與金鑰、表格、ECU、油圖準星。
 * 改了馬上生效、馬上存；沒有「套用」鈕。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(state: AppState, picker: FilePicker, onClose: () -> Unit) {
    var pickingVar by remember { mutableStateOf<Int?>(null) }
    var pairing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("settingsClose")) { Text(tr("common.close")) } },
        title = { Text(tr("settings.title")) },
        text = {
            Column(Modifier.width(600.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()).testTag("settingsPage")) {
                // 外觀
                Section(tr("settings.look"))
                Label(tr("settings.theme"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in ThemeChoice.entries) Choice(c.hint, state.theme == c, "theme_${c.name}") { state.pickTheme(c, quiet = true) }
                }
                Spacer(Modifier.height(10.dp))
                Label(tr("settings.language"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice(tr("settings.language.system"), state.languageSetting == Lang.SYSTEM, "lang_system") { state.pickLanguage(Lang.SYSTEM) }
                    for (l in Lang.entries) Choice(l.native, state.languageSetting == l.tag, "lang_${l.tag}") { state.pickLanguage(l.tag) }
                }
                Spacer(Modifier.height(10.dp))
                Label(tr("settings.zoom"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolButton("−", tr("settings.zoom.out"), tag = "zoomOut") { state.zoomBy(-1) }
                    Text("${Math.round(state.zoom * 100)}%", style = Xb.digits(14.sp, FontWeight.SemiBold), color = Xb.text, modifier = Modifier.width(56.dp).testTag("zoomValue"))
                    ToolButton("＋", tr("settings.zoom.in"), tag = "zoomIn") { state.zoomBy(1) }
                    ToolButton(tr("settings.zoom.reset"), tr("settings.zoom.reset.hint"), tag = "zoomReset") { state.zoomBy(0) }
                }

                // 定義檔與金鑰
                Section(tr("settings.data"))
                Label(tr("about.defs"))
                Value(state.settings.definitionsDir)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolButton(tr("defs.choose"), tr("defs.choose.hint"), tag = "settingsDefs") {
                        picker.chooseFolder(state.settings.definitionsDir)?.let { state.setDefinitionsDir(it) }
                    }
                    ToolButton(tr("defs.import"), tr("defs.import.hint"), tag = "settingsImport") {
                        picker.openPackage(state.settings.lastDir)?.let { f -> state.importXTune(f)?.let { state.error = it } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Label(tr("keys.title"))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.keys != null) tr("settings.keys.set") else tr("settings.keys.none"), fontSize = 13.sp, color = if (state.keys != null) Xb.accent else Xb.warn)
                    ToolButton(tr("settings.keys.manage"), tr("settings.keys.manage.hint"), tag = "keys") { state.manageKeys() }
                }

                // 表格
                Section(tr("settings.tables"))
                Toggle(tr("settings.showAll"), tr("settings.showAll.detail"), state.showAll, "showAll") { state.showAll = it; state.settings.showAll = it }

                // ECU
                Section("ECU")
                var host by remember { mutableStateOf(state.settings.ecuHost) }
                var pw by remember { mutableStateOf(state.settings.ecuPassword) }
                OutlinedTextField(host, { host = it.trim(); state.settings.ecuHost = host }, label = { Text(tr("connect.host")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settingsHost"))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(pw, { pw = it.filter(Char::isDigit); state.settings.ecuPassword = pw }, label = { Text(tr("connect.password")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settingsPassword"))
                Text(tr("settings.password.note"), fontSize = 11.5.sp, color = Xb.dim, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                Label(tr("settings.backupDir"))
                Value(state.ecuBackupDir.path)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolButton(tr("settings.backupDir.choose"), tr("settings.backupDir.choose.hint"), tag = "backupDir") {
                        picker.chooseFolder(state.ecuBackupDir.path)?.let { state.chooseEcuBackupDir(it) }
                    }
                    if (state.settings.ecuBackupDir != null) ToolButton(tr("settings.backupDir.default"), tr("settings.backupDir.default.hint"), tag = "backupDirDefault") { state.chooseEcuBackupDir(null) }
                }

                // 手機同步（ReX）
                PhoneSyncSection(state, picker) { pairing = true }

                // 油圖準星
                Section(tr("settings.scope"))
                Text(tr("settings.scope.detail"), fontSize = 12.sp, color = Xb.dim, lineHeight = 18.sp)
                Spacer(Modifier.height(6.dp))
                Toggle(tr("editor.scope"), tr("settings.scope.on"), state.scopeOn, "settingsScope") { state.switchScope(it) }
                Toggle("Cell Track", tr("editor.cellTrack.hint"), state.cellTrack, "settingsCellTrack") { state.switchCellTrack(it) }
                Toggle(tr("settings.scope.vars"), tr("settings.scope.vars.detail"), state.scopeShowVars, "settingsScopeVars") { state.switchScopeVars(it) }
                val reg = state.ecuControl.registry()
                val corners = listOf(tr("corner.0"), tr("corner.1"), tr("corner.2"), tr("corner.3"))
                for (i in 0 until 4) {
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(corners[i], fontSize = 12.5.sp, color = Xb.dim, modifier = Modifier.width(80.dp))
                        val id = state.scopeVars[i]
                        Text(reg?.values?.get(id)?.let { tr("settings.scope.varLabel", it.label, it.unit) } ?: "#$id", fontSize = 13.sp, color = Xb.text, modifier = Modifier.weight(1f))
                        ToolButton(tr("settings.scope.pick"), tr("settings.scope.pick.hint"), enabled = reg != null, tag = "pickVar$i") { pickingVar = i }
                    }
                }
                if (reg == null) Text(tr("settings.scope.noDefs"), fontSize = 11.5.sp, color = Xb.dim)
            }
        },
    )
    if (pairing) PairDialog(state) { pairing = false }
    pickingVar?.let { i ->
        val reg = state.ecuControl.registry() ?: return@let
        VariablePicker(reg.values.values.sortedBy { it.absid }, { state.setScopeVar(i, it.absid); pickingVar = null }) { pickingVar = null }
    }
}

/** 選一個即時變數：可以用名稱或編號搜尋。 */
@Composable
private fun VariablePicker(channels: List<ChannelDef>, onPick: (ChannelDef) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common.cancel")) } },
        title = { Text(tr("settings.scope.pick")) },
        text = {
            Column(Modifier.width(440.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text(tr("list.search")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("varSearch"))
                Spacer(Modifier.height(8.dp))
                val needle = q.trim().lowercase()
                val shown = channels.filter { needle.isEmpty() || it.label.lowercase().contains(needle) || it.name.lowercase().contains(needle) || it.absid.toString() == needle }.take(200)
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    for (c in shown) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onPick(c) }.padding(horizontal = 6.dp, vertical = 5.dp).testTag("var_${c.absid}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("#${c.absid}", style = Xb.digits(11.5.sp), color = Xb.dim, modifier = Modifier.width(52.dp))
                            Text(c.label, fontSize = 13.sp, color = Xb.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.unit.isNotBlank() && c.unit != "NA") Text(c.unit, fontSize = 12.sp, color = Xb.dim)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Xb.brandInk)
    Spacer(Modifier.height(4.dp))
    EtherLine()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.5.sp, color = Xb.dim)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Value(text: String) {
    Text(text, fontSize = 12.5.sp, color = Xb.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(6.dp))
}

/** 一個可以選的方塊（主題、語言）。 */
@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label, fontSize = 13.sp, color = if (selected) Xb.onBrand else Xb.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(shape).background(if (selected) Xb.brand else Xb.panel).border(1.dp, if (selected) Xb.brand else Xb.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp).testTag(tag),
    )
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = Xb.text)
            Text(detail, fontSize = 11.5.sp, color = Xb.dim, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/** 手機同步：開關伺服器、這台電腦的位址、配對、配過的手機、錄製放哪。 */
@Composable
private fun PhoneSyncSection(state: AppState, picker: FilePicker, onPair: () -> Unit) {
    val ps = state.phoneSync
    Section(tr("sync.title"))
    Text(tr("sync.detail"), fontSize = 12.sp, color = Xb.dim, lineHeight = 18.sp)
    Spacer(Modifier.height(6.dp))
    Toggle(tr("sync.on"), tr("sync.on.detail", state.settings.syncPort), ps.running, "syncToggle") { ps.switch(it) }
    ps.error?.let { Text(it, fontSize = 12.sp, color = Xb.bad, lineHeight = 18.sp, modifier = Modifier.testTag("syncError")) }
    if (ps.running) {
        Label(tr("sync.addresses"))
        val addrs = remember { PhoneSync.localAddresses() }
        Value(if (addrs.isEmpty()) tr("sync.addresses.none") else addrs.joinToString("\n") { "$it  :${state.settings.syncPort}" })
        val feed by ps.sync.live.collectAsState()
        feed?.takeIf { it.online }?.let { f ->
            Text(tr("sync.live", f.phone, f.rounds), fontSize = 12.5.sp, color = Xb.accent, modifier = Modifier.testTag("syncLive"))
            Spacer(Modifier.height(6.dp))
        }
    }
    Spacer(Modifier.height(4.dp))
    Label(tr("sync.phones"))
    val changes by ps.sync.changes.collectAsState()
    LaunchedEffect(changes) { ps.refresh() }
    if (ps.phones.isEmpty()) Text(tr("sync.phones.none"), fontSize = 12.5.sp, color = Xb.dim)
    for (p in ps.phones) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = 13.sp, color = Xb.text, modifier = Modifier.weight(1f))
            Text(tr("sync.rides.count", ps.sync.rides(p).size), fontSize = 12.sp, color = Xb.dim)
            Spacer(Modifier.width(8.dp))
            ToolButton(tr("sync.forget"), tr("sync.forget.hint"), tag = "forget_${p.id}") { ps.forget(p) }
        }
    }
    Spacer(Modifier.height(4.dp))
    ToolButton(tr("sync.pair"), tr("sync.pair.hint"), enabled = ps.running, primary = true, tag = "syncPair") { onPair() }
    Spacer(Modifier.height(10.dp))
    Label(tr("sync.ridesDir"))
    Value(ps.ridesDir.path)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolButton(tr("settings.backupDir.choose"), tr("sync.ridesDir.choose.hint"), tag = "ridesDir") {
            picker.chooseFolder(ps.ridesDir.path)?.let { state.settings.ridesDir = it.path }
        }
        if (state.settings.ridesDir != null) ToolButton(tr("settings.backupDir.default"), tr("sync.ridesDir.default.hint"), tag = "ridesDirDefault") { state.settings.ridesDir = null }
    }
}

/** 配對碼：手機在 ReX 的「同步到電腦」輸入。配好了就關掉。 */
@Composable
private fun PairDialog(state: AppState, onClose: () -> Unit) {
    val ps = state.phoneSync
    val window = remember { ps.pair() }
    val open by ps.sync.pairing.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = System.currentTimeMillis() } }
    val paired = open == null && window.done
    val left = ((window.expiresAt - now) / 1000).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = { ps.sync.cancelPairing(); onClose() },
        confirmButton = { TextButton(onClick = { ps.sync.cancelPairing(); onClose() }, modifier = Modifier.testTag("pairClose")) { Text(tr(if (paired) "common.close" else "common.cancel")) } },
        title = { Text(tr("sync.pair")) },
        text = {
            Column(Modifier.width(440.dp)) {
                if (paired) {
                    Text(tr("sync.pair.done", ps.phones.lastOrNull()?.name ?: ""), fontSize = 14.sp, color = Xb.accent, modifier = Modifier.testTag("pairDone"))
                } else if (!window.open) {
                    Text(tr("sync.pair.expired"), fontSize = 13.sp, color = Xb.warn, modifier = Modifier.testTag("pairExpired"))
                } else {
                    Text(tr("sync.pair.how"), fontSize = 12.5.sp, color = Xb.dim, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(rx.session.sync.SyncCrypto.showCode(window.code), style = Xb.digits(26.sp, FontWeight.SemiBold), color = Xb.text, modifier = Modifier.testTag("pairCode"))
                    Spacer(Modifier.height(6.dp))
                    Text(tr("sync.pair.left", left / 60, (left % 60).toString().padStart(2, '0')), fontSize = 12.sp, color = Xb.dim)
                    Spacer(Modifier.height(10.dp))
                    Text(PhoneSync.localAddresses().joinToString("\n") { "$it  :${state.settings.syncPort}" }, fontSize = 12.5.sp, color = Xb.text)
                }
            }
        },
    )
}
