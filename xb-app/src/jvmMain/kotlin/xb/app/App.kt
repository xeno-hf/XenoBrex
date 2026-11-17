package xb.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** 檔案與資料夾的選擇視窗由平台提供；測試時換成假的。 */
interface FilePicker {
    fun openTune(startDir: String?): File?
    fun saveTune(startDir: String?, suggestedName: String): File?
    fun chooseFolder(startDir: String?): File?
    /** 選一個 XTune 的安裝檔（.xapk／.apks／.apk）。 */
    fun openPackage(startDir: String?): File?
}

/** 系統剪貼簿。 */
interface ClipboardBridge {
    fun get(): String?
    fun set(text: String)
}

/** 測試或沒有系統剪貼簿時用的：只存在記憶體裡。 */
class MemoryClipboard : ClipboardBridge {
    private var text: String? = null
    override fun get() = text
    override fun set(text: String) { this.text = text }
}

/**
 * 把裡面的內容整體放大 [scale] 倍（字、間距、格子一起）。
 * 視窗、對話框、提示框都在這裡面，才會一起放大。
 */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    if (scale == 1f) { content(); return }
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale), content = content)
}

/**
 * [systemScale]：系統顯示縮放裡 Java 沒有處理的部分（見 [UiScale]）；再乘上使用者自己調的縮放。
 */
@Composable
fun App(state: AppState, picker: FilePicker, clipboard: ClipboardBridge = remember { MemoryClipboard() }, systemScale: Float = 1f) {
    Scaled(systemScale * state.zoom) { AppContent(state, picker, clipboard) }
}

@Composable
private fun AppContent(state: AppState, picker: FilePicker, clipboard: ClipboardBridge) {
    var showReport by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var confirmFlash by remember { mutableStateOf(false) }
    var restoreFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    fun doOpen() { picker.openTune(state.settings.lastDir)?.let { state.open(it) } }
    fun requestOpen() { if (state.hasChanges) confirmOpen = true else doOpen() }
    fun saveAs() {
        val s = state.session ?: return
        // 副檔名照型號（跟官方一樣：Mini X＝amx、X2 Mini＝amx2…）；原本是 .xml 就照存 .xml
        val name = s.file.nameWithoutExtension + "_xb." + (s.file.extension.lowercase().takeIf { it == "xml" } ?: xb.container.TuneIo.extension(s.tune))
        picker.saveTune(s.file.absoluteFile.parent, name)?.let { state.save(it) }
    }

    XbTheme(state.theme) {
        Column(
            Modifier.fillMaxSize().xbBackground().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed)) return@onKeyEvent false
                when (e.key) {
                    Key.Equals, Key.Plus, Key.NumPadAdd -> { state.zoomBy(1); true }
                    Key.Minus, Key.NumPadSubtract -> { state.zoomBy(-1); true }
                    Key.Zero, Key.NumPad0 -> { state.zoomBy(0); true }
                    Key.Z -> { state.undo(); true }
                    Key.S -> { if (e.isShiftPressed) saveAs() else if (state.hasChanges) state.save(); true }
                    Key.O -> { requestOpen(); true }
                    else -> false
                }
            },
        ) {
            if (state.pack == null) {
                Setup(state, picker) { state.about = true }
            } else {
                TopBar(state, ::requestOpen, ::saveAs, { picker.openTune(state.settings.lastDir)?.let { state.openOther(it) } }, { showReport = true }, { state.about = true },
                    onEcu = { if (state.ecu == null) showConnect = true else state.ecuControl.disconnect(scope) },
                    onFlash = { confirmFlash = true },
                    onRestore = { picker.openTune(state.ecuBackupDir.path)?.let { restoreFile = it } },
                    onSettings = { state.showSettings = true })
                EtherLine()
                if (state.session == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CrystalDot(Xb.accent, glow = true, Modifier.size(34.dp, 46.dp))
                            Spacer(Modifier.height(14.dp))
                            Wordmark(46)
                            Spacer(Modifier.height(6.dp))
                            Text(tr("home.tagline"), fontSize = 14.sp, color = Xb.dim)
                            Spacer(Modifier.height(22.dp))
                            ToolButton(tr("home.open"), tr("home.open.hint"), primary = true, tag = "openBig") { requestOpen() }
                            Spacer(Modifier.height(16.dp))
                            Text(tr("home.liveTip"), fontSize = 12.sp, color = Xb.dim)
                        }
                    }
                } else {
                    Row(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TableList(state, Modifier.width(290.dp).fillMaxHeight())
                        val cal = state.calItem
                        if (cal != null) CalPanel(state, cal, clipboard, Modifier.weight(1f).fillMaxHeight())
                        else TableEditor(state, clipboard, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                StatusBar(state)
            }
        }

        state.error?.let { msg ->
            AlertDialog(
                onDismissRequest = { state.error = null },
                confirmButton = { TextButton(onClick = { state.error = null }) { Text(tr("common.ok")) } },
                title = { Text(tr("error.title")) },
                text = { Text(msg) },
            )
        }
        if (confirmOpen) {
            AlertDialog(
                onDismissRequest = { confirmOpen = false },
                confirmButton = { TextButton(onClick = { confirmOpen = false; doOpen() }) { Text(tr("open.discard")) } },
                dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text(tr("common.cancel")) } },
                title = { Text(tr("unsaved.title")) },
                text = { Text(tr("open.discard.detail")) },
            )
        }
        if (showReport) ReportDialog(state) { showReport = false }
        if (showConnect) ConnectDialog(state, { h, p, pw -> showConnect = false; state.ecuControl.connect(scope, h, p, pw) }) { showConnect = false }
        EcuDialogs(state) { state.ecuControl.disconnect(scope) }
        if (confirmFlash) FlashConfirmDialog(tr("ecu.flash"), tr("ecu.flash.detail"),
            { confirmFlash = false; state.ecuControl.saveToEcu(scope) }) { confirmFlash = false }
        restoreFile?.let { f ->
            FlashConfirmDialog(tr("ecu.restore.title"), tr("ecu.restore.detail", f.name),
                { restoreFile = null; state.ecuControl.restore(scope, f) }) { restoreFile = null }
        }
        if (state.about) AboutDialog(state) { state.about = false }
        if (state.showSettings) SettingsDialog(state, picker) { state.showSettings = false }
        KeyDialog(state, clipboard, picker)
    }
}

@Composable
private fun TopBar(state: AppState, onOpen: () -> Unit, onSaveAs: () -> Unit, onCompare: () -> Unit, onReport: () -> Unit, onAbout: () -> Unit,
                   onEcu: () -> Unit, onFlash: () -> Unit, onRestore: () -> Unit, onSettings: () -> Unit) {
    val rev = state.revision
    val s = state.session
    val dirty = rev >= 0 && state.hasChanges
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 字標按下去是「關於」；旁邊那顆水晶是小彩蛋：按一下換一套配色（正式的在設定頁）。
        LogoButton(21, onAbout)
        ThemeCrystal(state.theme) { state.nextTheme() }
        Spacer(Modifier.width(6.dp))
        ToolButton(tr("bar.open"), tr("bar.open.hint"), tag = "open") { onOpen() }
        ToolButton(tr("bar.save"), tr("bar.save.hint"), enabled = dirty, primary = dirty, tag = "save") { state.save() }
        ToolButton(tr("bar.saveAs"), tr("bar.saveAs.hint"), enabled = s != null, tag = "saveAs") { onSaveAs() }
        ToolButton(tr("bar.undo"), tr("bar.undo.hint"), enabled = rev >= 0 && state.tune?.canUndo == true, tag = "undo") { state.undo() }
        Spacer(Modifier.width(8.dp))
        if (state.other == null) ToolButton(tr("bar.compare"), tr("bar.compare.hint"), enabled = s != null, tag = "compare") { onCompare() }
        else ToolButton(tr("bar.compareOff"), tr("bar.compareOff.hint"), tag = "compareOff") { state.closeOther() }
        ToolButton(tr("bar.report"), tr("bar.report.hint"), enabled = s != null, tag = "report") { onReport() }
        val live = state.ecu is EcuPhase.Live
        ToolButton(if (state.ecu == null) tr("bar.ecu") else tr("bar.ecuOff"), if (state.ecu == null) tr("bar.ecu.hint") else tr("bar.ecuOff.hint"),
            enabled = s != null, primary = live, tag = "ecu") { onEcu() }
        if (live) {
            ToolButton(tr("ecu.flash"), tr("bar.flash.hint"), tag = "flash") { onFlash() }
            ToolButton(tr("bar.restore"), tr("bar.restore.hint"), tag = "restore") { onRestore() }
        }
        ToolButton(tr("bar.settings"), tr("bar.settings.hint"), tag = "settings") { onSettings() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (s != null) {
                val f = s.tune.file
                Text(s.file.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (dirty) Xb.warn else Xb.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("fileName"))
                // 一行寫完，放不下就在尾巴省略，不要從中間切掉。
                Text(
                    buildAnnotatedString {
                        append(s.platform.display)
                        withStyle(SpanStyle(fontFamily = Xb.digits)) { append("   V${f.vCode} C${f.cCode} A${f.aCode}   ${f.tableId}") }
                        state.other?.let { withStyle(SpanStyle(color = Xb.warn)) { append(tr("bar.comparingWith", it.file.name)) } }
                    },
                    fontSize = 11.sp, color = Xb.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.other != null) {
            Text(tr("bar.diffOnly"), fontSize = 12.sp, color = Xb.dim)
            Switch(checked = state.diffOnly, onCheckedChange = { state.diffOnly = it }, modifier = Modifier.testTag("diffOnly"))
        }
    }
}

@Composable
private fun StatusBar(state: AppState) {
    val rev = state.revision
    val dirty = rev >= 0 && state.hasChanges
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.session != null) {
            CrystalDot(if (dirty) Xb.warn else Xb.accent, glow = false, Modifier.size(8.dp, 11.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (dirty) tr("status.unsaved") else tr("status.saved"), fontSize = 12.sp, color = if (dirty) Xb.warn else Xb.dim)
            Spacer(Modifier.width(14.dp))
        }
        Text(state.message ?: "", fontSize = 12.sp, color = Xb.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("status"))
        when (val p = state.ecu) {
            is EcuPhase.Live -> {
                val written by p.live.written.collectAsState()
                val states by p.live.states.collectAsState()
                val busy = states.values.count { it == xb.link.LiveTuning.TableState.Pending || it == xb.link.LiveTuning.TableState.Writing }
                val bad = states.values.count { liveBadge(it)?.second == Xb.bad }
                CrystalDot(if (bad > 0) Xb.bad else Xb.accent, glow = busy > 0, Modifier.size(8.dp, 11.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    tr("status.live", p.link.platform?.label ?: "ECU", written.first, written.second) + (if (busy > 0) tr("status.live.busy", busy) else "") + (if (bad > 0) tr("status.live.bad", bad) else ""),
                    fontSize = 12.sp, color = if (bad > 0) Xb.bad else Xb.accent, modifier = Modifier.testTag("ecuStatus"),
                )
            }
            null -> Text(tr("status.offline"), fontSize = 12.sp, color = Xb.dim)
            else -> Text(tr("status.connecting"), fontSize = 12.sp, color = Xb.warn)
        }
    }
}

@Composable
private fun Setup(state: AppState, picker: FilePicker, onAbout: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(600.dp).panel(16).padding(28.dp), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoButton(34, onAbout)
                Spacer(Modifier.width(4.dp))
                ThemeCrystal(state.theme, 16, 22) { state.nextTheme() }
                Spacer(Modifier.weight(1f))
                ToolButton(tr("bar.settings"), tr("bar.settings.hint"), tag = "settings") { state.showSettings = true }
            }
            Spacer(Modifier.height(14.dp))
            Text(tr("setup.title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Xb.text)
            Spacer(Modifier.height(8.dp))
            Text(
                tr("setup.detail"),
                fontSize = 13.sp, color = Xb.dim, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(tr("setup.current", state.settings.definitionsDir), fontSize = 12.sp, color = Xb.dim)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(tr("defs.choose"), tr("defs.choose.hint"), primary = true, tag = "chooseDefs") {
                    picker.chooseFolder(state.settings.definitionsDir)?.let { state.setDefinitionsDir(it) }
                }
                ToolButton(tr("defs.import"), tr("defs.import.hint"), tag = "importXTune") {
                    picker.openPackage(state.settings.lastDir)?.let { f -> state.importXTune(f)?.let { state.error = it } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                tr("setup.importTip"),
                fontSize = 12.sp, color = Xb.dim, lineHeight = 18.sp,
            )
        }
    }
}

/** 跟使用者要金鑰：貼上兩段文字，拿加密的檔案試過才收下。 */
@Composable
private fun KeyDialog(state: AppState, clipboard: ClipboardBridge, picker: FilePicker) {
    val prompt = state.keyPrompt ?: return
    var text by remember(prompt) { mutableStateOf("") }
    var problem by remember(prompt) { mutableStateOf<String?>(null) }
    val has = state.keys != null
    AlertDialog(
        onDismissRequest = { state.cancelKeys() },
        title = { Text(if (prompt.manage) tr("keys.title") else tr("keys.needed")) },
        text = {
            Column(Modifier.width(460.dp)) {
                Text(
                    (if (prompt.manage) (if (has) tr("keys.have") else tr("keys.none")) else tr("keys.fileEncrypted")) + "\n\n" + tr("keys.detail"),
                    fontSize = 13.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                val shape = RoundedCornerShape(8.dp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; problem = null },
                    textStyle = Xb.digits(14.sp, color = Xb.text),
                    cursorBrush = SolidColor(Xb.brand),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().background(Xb.panelHi, shape).border(1.dp, Xb.line, shape).padding(10.dp).testTag("keyInput"),
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.5.sp, color = Xb.bad, modifier = Modifier.testTag("keyProblem"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { problem = state.submitKeys(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("keyOk")) { Text(tr("common.confirm")) }
        },
        dismissButton = {
            Row {
                if (prompt.manage && has) TextButton(onClick = { state.forgetKeys() }, modifier = Modifier.testTag("keyForget")) { Text(tr("keys.forget")) }
                TextButton(onClick = { picker.openPackage(state.settings.lastDir)?.let { problem = state.importXTune(it) } }, modifier = Modifier.testTag("keyFromXTune")) { Text(tr("keys.fromXTune")) }
                TextButton(onClick = { clipboard.get()?.let { text = it.trim(); problem = null } }, modifier = Modifier.testTag("keyPaste")) { Text(tr("keys.paste")) }
                TextButton(onClick = { state.cancelKeys() }, modifier = Modifier.testTag("keyCancel")) { Text(tr("common.cancel")) }
            }
        },
    )
}

/** 關於：版本、這是什麼、出處、資料放在哪裡。按字標打開。 */
@Composable
private fun AboutDialog(state: AppState, onClose: () -> Unit) {
    val open = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.testTag("aboutClose")) { Text(tr("common.close")) } },
        title = {
            Row(verticalAlignment = Alignment.Bottom) {
                Wordmark(30)
                Spacer(Modifier.width(12.dp))
                Text(BuildInfo.version, style = Xb.digits(15.sp, FontWeight.SemiBold), color = Xb.dim, modifier = Modifier.padding(bottom = 4.dp).testTag("aboutVersion"))
            }
        },
        text = {
            Column(Modifier.width(480.dp).testTag("about")) {
                EtherLine()
                Spacer(Modifier.height(12.dp))
                Text(tr("about.what"), fontSize = 13.sp, lineHeight = 20.sp, color = Xb.text)
                Spacer(Modifier.height(8.dp))
                Text(tr("about.disclaimer"), fontSize = 12.5.sp, lineHeight = 19.sp, color = Xb.dim)
                Spacer(Modifier.height(14.dp))
                AboutRow(tr("about.source")) {
                    Text(
                        BuildInfo.SOURCE_URL.removePrefix("https://"), fontSize = 12.5.sp, color = Xb.brandInk,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { runCatching { open.openUri(BuildInfo.SOURCE_URL) } }.testTag("aboutSource"),
                    )
                }
                AboutRow(tr("about.config")) { Text(Settings.configDir().path, fontSize = 12.5.sp, color = Xb.text) }
                AboutRow(tr("about.defs")) { Text(state.settings.definitionsDir, fontSize = 12.5.sp, color = Xb.text) }
                AboutRow(tr("about.font")) { Text(tr("about.font.value"), fontSize = 12.5.sp, color = Xb.text) }
                AboutRow(tr("about.runtime")) {
                    Text("Java ${System.getProperty("java.version")}　${System.getProperty("os.name")} ${System.getProperty("os.arch")}", fontSize = 12.5.sp, color = Xb.text)
                }
            }
        },
    )
}

@Composable
private fun AboutRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 12.5.sp, color = Xb.dim, modifier = Modifier.width(92.dp))
        value()
    }
}

@Composable
private fun ReportDialog(state: AppState, onClose: () -> Unit) {
    val r = state.report ?: return
    val s = state.session ?: return
    val f = s.tune.file
    val lines = buildList {
        add(tr("report.head", s.platform.display, f.mapType, f.vCode, f.cCode, f.aCode, f.tableId, f.modelId))
        add(tr("report.saved", f.lastSaveTime))
        add("")
        add(tr("report.checked", r.tablesChecked, r.cells))
        add(tr("report.outOfRange", r.cellsOutOfRange, r.tablesOutOfRange.size))
        add(tr("report.notReversible", r.cellsNotReversible, r.tablesNotReversible.size))
        if (r.sizeMismatch.isNotEmpty()) add(tr("report.sizeMismatch", r.sizeMismatch.size))
        if (r.axisMissing.isNotEmpty()) add(tr("report.axisMissing", r.axisMissing.size))
        if (r.unknownTables.isNotEmpty()) add(tr("report.unknown", r.unknownTables.size))
        if (r.missingTables.isNotEmpty()) add(tr("report.missing", r.missingTables.size))
        if (f.duplicateIds.isNotEmpty()) add(tr("report.duplicate", f.duplicateIds.size))
        if (r.sizeMismatch.isEmpty() && r.axisMissing.isEmpty() && r.unknownTables.isEmpty() && r.missingTables.isEmpty()) add(tr("report.allMatch"))
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text(tr("common.close")) } },
        title = { Text(tr("report.title")) },
        text = { Text(lines.joinToString("\n"), fontSize = 13.sp, modifier = Modifier.testTag("reportText")) },
    )
}
