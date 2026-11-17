package xb.app

import xb.container.DefinitionPack
import xb.container.KeyStore
import xb.container.TuneIo
import xb.tune.EditResult
import xb.tune.Cell
import java.io.File

/**
 * 不開視窗的自我檢查：`XenoBrex --selfcheck <調校檔> [報告檔]`。
 * 用途是驗證「打包好的程式」本身——安裝檔裡帶的是精簡過的 Java 執行環境，
 * 單元測試跑的不是它。這裡用同一套程式碼把檔案開起來、原樣存回去、改一格再存，
 * 看結果對不對。會讀使用者的金鑰檔（加密的檔案才需要），不寫任何設定，只在暫存資料夾裡寫檔。
 */
object SelfCheck {
    fun run(args: List<String>): Int {
        val out = StringBuilder()
        fun say(s: String) { out.appendLine(s) }
        var ok = false
        try {
            val file = File(args.getOrNull(0) ?: error("usage: --selfcheck <tune file> [report file]"))
            val defs = Settings.defaultDefinitionsDir()
            say("os=${System.getProperty("os.name")} ${System.getProperty("os.arch")} java=${System.getProperty("java.version")}")
            say("definitions=${defs.path}")
            val keys = KeyStore.load()?.container
            say("keys=${if (keys != null) "present" else "none"}")
            val pack = DefinitionPack(defs, keys)
            require(pack.complete) { "definitions folder is incomplete" }
            val opened = TuneIo.open(file, pack, keys)
            say("opened=${file.name} platform=${opened.platform.display} tables=${opened.tune.file.tables.size} encrypted=${opened.encrypted}")

            // 1. 沒改任何東西、時間也不動：存出來要和原檔每個位元組都一樣。
            val original = file.readBytes()
            val same = TuneIo.content(opened.tune, opened.encrypted, null, keys).contentEquals(original)
            say("unchangedSaveIdentical=$same")

            // 2. 改一格、存到暫存檔、再開回來：只有那一張表不同。
            val dir = File(System.getProperty("java.io.tmpdir"), "xenobrex-selfcheck-${System.nanoTime()}").apply { mkdirs() }
            val target = File(dir, "edited." + file.extension)
            val tune = opened.tune
            val def = tune.file.tables.keys.asSequence().mapNotNull { tune.definition(it) }
                .first { !it.adjustDisabled && tune.level(it) == 1 && it.rawMax > 2 }
            val before = tune.rows(def.absId)!![0][0]
            val edit = tune.setRaw(def.absId, Cell(0, 0), if (before < def.rawMax) before + 1 else before - 1)
            require(edit is EditResult.Done) { "could not edit table ${def.absId}" }
            TuneIo.save(opened, target, container = keys)
            val again = TuneIo.open(target, pack, keys)
            val diff = xb.tune.TuneCompare.compare(TuneIo.open(file, pack, keys).tune, again.tune)
            say("editedTable=${def.absId} changedTables=${diff.size} changedCells=${diff.sumOf { it.cells.size }}")
            target.delete(); dir.delete()

            ok = same && diff.size == 1 && diff[0].absId == def.absId
        } catch (e: Throwable) {
            say("error=${e::class.simpleName}: ${e.message}")
        }
        say("result=${if (ok) "OK" else "FAILED"}")
        print(out)
        args.getOrNull(1)?.let { runCatching { File(it).writeText(out.toString()) } }
        return if (ok) 0 else 1
    }
}
