package xb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

private class AwtPicker(private val frame: () -> Frame?) : FilePicker {
    private val extensions = listOf("amx", "asx", "amx2", "asx2", "a4x", "azr", "sxs", "amg", "am6", "xml", "xbx")

    override fun openTune(startDir: String?): File? {
        val d = FileDialog(frame(), tr("dialog.openTune"), FileDialog.LOAD)
        startDir?.let { d.directory = it }
        d.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in extensions }
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    override fun saveTune(startDir: String?, suggestedName: String): File? {
        val d = FileDialog(frame(), tr("bar.saveAs"), FileDialog.SAVE)
        startDir?.let { d.directory = it }
        d.file = suggestedName
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    override fun openPackage(startDir: String?): File? {
        val packages = listOf("xapk", "apks", "apkm", "apk", "zip")
        val d = FileDialog(frame(), tr("dialog.choosePackage"), FileDialog.LOAD)
        startDir?.let { d.directory = it }
        d.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in packages }
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    override fun chooseFolder(startDir: String?): File? {
        val c = JFileChooser(startDir)
        c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        c.dialogTitle = tr("dialog.chooseFolder")
        return if (c.showOpenDialog(frame()) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
    }
}

private class AwtClipboard : ClipboardBridge {
    private val board get() = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    override fun get(): String? = runCatching { board.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String }.getOrNull()
    override fun set(text: String) { runCatching { board.setContents(java.awt.datatransfer.StringSelection(text), null) } }
}

fun main(args: Array<String>) {
    // 不開視窗的自我檢查，給打包後的驗收用（見 SelfCheck）。
    if (args.firstOrNull() == "--selfcheck") kotlin.system.exitProcess(SelfCheck.run(args.drop(1)))
    ui(args)
}

private fun ui(args: Array<String>) = application {
    val systemScale = remember { UiScale.systemFactor() }
    val state = remember {
        AppState(Settings.default()).also { s ->
            args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }?.let { s.open(it) }
            s.phoneSync.restore()
        }
    }
    var confirmClose by remember { mutableStateOf(false) }
    var frame: Frame? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    // ECU 寫入或還原中不關；其他情況先停連線再跳出。
    fun closeApp() { state.ecuControl.requestClose(scope) { exitApplication() } }
    val rev = state.revision
    val name = state.session?.file?.name
    Window(
        onCloseRequest = { if (state.hasChanges) confirmClose = true else closeApp() },
        icon = AppIcon,
        title = (if (name != null) "$name${if (rev >= 0 && state.hasChanges) " ●" else ""} — " else "") + "XenoBrex",
        // 視窗大小跟著縮放放大，但不超過螢幕
        state = rememberWindowState(size = remember { initialWindowSize(systemScale * state.zoom) }),
    ) {
        frame = window
        // 再小工具列就擠不下了。
        val scale = systemScale * state.zoom
        window.minimumSize = java.awt.Dimension((MIN_WIDTH * scale).toInt(), (MIN_HEIGHT * scale).toInt())
        App(state, remember { AwtPicker { frame } }, remember { AwtClipboard() }, systemScale)
        if (confirmClose) Scaled(systemScale * state.zoom) { XbTheme(state.theme) {
            AlertDialog(
                onDismissRequest = { confirmClose = false },
                confirmButton = { TextButton(onClick = { confirmClose = false; closeApp() }) { Text(tr("close.discard")) } },
                dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(tr("common.cancel")) } },
                title = { Text(tr("unsaved.title")) },
                text = { Text(tr("close.discard.detail")) },
            )
        } }
    }
}

private const val MIN_WIDTH = 940
private const val MIN_HEIGHT = 600

private fun initialWindowSize(scale: Float): DpSize {
    val wanted = DpSize((1280 * scale).dp, (800 * scale).dp)
    return runCatching {
        val gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val b = gc.bounds
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val maxW = (b.width - insets.left - insets.right) * 0.95f
        val maxH = (b.height - insets.top - insets.bottom) * 0.92f
        DpSize(minOf(wanted.width.value, maxW).dp, minOf(wanted.height.value, maxH).dp)
    }.getOrDefault(wanted)
}
