package xb.cli

import xb.container.ConvertFlow
import xb.container.KeysRequiredException
import xb.container.OpenedTune
import xb.container.SafeFiles
import xb.container.TuneContainer
import xb.container.TuneIo
import xb.container.XbxException
import xb.container.XbxProject
import xb.tune.TuneConvert
import xb.tune.TuneFile
import java.io.File
import java.nio.file.Files

/** 調校檔讀取上限：正常檔案只有幾 MB。 */
private const val MAX_TUNE_BYTES = 64L * 1024 * 1024

/** 存成 .xbx：副檔名是 xbx，或是已經存在的 .xbx 檔（看開頭，不看副檔名）。 */
internal fun isProject(f: File): Boolean =
    f.extension.lowercase() == "xbx" || (f.isFile && runCatching { XbxProject.isXbx(f.inputStream().use { it.readNBytes(256) }) }.getOrDefault(false))

/** 不准覆蓋別的專案：已存在的 .xbx 只能是輸入的那一個（不然它的修改紀錄會不見）。 */
internal fun checkProjectTarget(o: OpenedTune, out: File) {
    if (out.isFile && isProject(out) && out.canonicalFile != o.file.canonicalFile)
        fail("${out.path} is an existing project and not the input; writing would replace its history. Use it as the input, or pick a new name.")
}

/** 寫檔：不跟符號連結，交給 SafeFiles 用唯一暫存檔原子換過去。 */
internal fun writeFileAtomically(out: File, bytes: ByteArray) {
    val path = out.toPath()
    if (Files.isSymbolicLink(path)) fail("${out.path} is a symbolic link; refusing to write through it")
    try { SafeFiles.atomicWrite(path) { it.write(bytes) } } catch (e: Exception) { fail("Cannot write ${out.path}: ${e.message}") }
}

/** 輸入的專案；輸入是一般調校檔就開一個新的。 */
internal fun projectFor(o: OpenedTune): XbxProject = o.project ?: XbxProject(
    "tune." + TuneIo.extension(o.tune),
    try { o.file.inputStream().use { SafeFiles.boundedRead(it, MAX_TUNE_BYTES) } }
    catch (e: Exception) { fail("${o.file.path}: cannot read: ${e.message}") },
).also { it.log(linkedMapOf("kind" to "new", "by" to "xbrex", "from" to o.file.name)) }

/**
 * 存檔。輸出是 .xbx 就存成專案（修改紀錄記 [entries]）；不然存成一般調校檔，加密照原檔、沒加密的輸入有金鑰也加密，.xml 不加密。
 * 一般調校檔的副檔名跟型號不合時提醒（XTune／STX 照副檔名分型號）。回傳一段說明。
 */
internal fun saveTune(o: OpenedTune, out: File, entries: List<Map<String, Any?>>, project: XbxProject? = null): String {
    val encrypted = out.extension.lowercase() != "xml" && (o.encrypted || keys != null)
    try {
        if (isProject(out)) {
            val p = TuneIo.saveProject(o, out, project ?: projectFor(o), entries, encrypted = encrypted, container = keys)
            return "project, tune ${p.tuneName} ${if (encrypted) "encrypted" else "not encrypted"}, ${count(entries.size, "change log entry", "change log entries")} added"
        }
        TuneIo.save(o, out, encrypted, container = keys)
    } catch (e: KeysRequiredException) { fail("${out.path}: keys are required. Run xbrex keys <key1> <key2> first.") }
    val want = TuneIo.extension(o.tune)
    if (out.extension.lowercase() != "xml" && out.extension.lowercase() != want)
        System.err.println("Note: ${o.tune.file.mapType} tunes are normally named .$want; XTune and STX tell the platform by the extension.")
    return if (encrypted) "encrypted" else "not encrypted"
}

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun TuneFile.ident() = linkedMapOf("map_type" to mapType, "v" to vCode, "c" to cCode, "a" to aCode, "table_id" to tableId)

/** 轉換報告：原檔、目標、官方基準、每張表的決定。 */
internal fun convertReport(o: OpenedTune, flow: ConvertFlow.Outcome, tableId: String): Map<String, Any?> {
    val r = flow.result
    val t = flow.target
    return linkedMapOf(
        "time" to XbxProject.now(),
        "by" to XbxProject.APP,
        "source" to linkedMapOf<String, Any?>("file" to o.file.name) + o.tune.file.ident(),
        "target" to r.tune.file.ident(),
        "vehicle_forced" to (tableId.trim() != o.tune.file.tableId.trim()),
        "baseline" to linkedMapOf("list" to t.list.source.display, "list_version" to t.list.version, "file" to t.entry.file, "md5" to t.entry.md5,
            "vehicle" to t.entry.vehicle, "style" to t.entry.styleEn),
        "source_stock" to (flow.sourceStock?.let { linkedMapOf("file" to it.entry.file, "md5" to it.entry.md5) }),
        "source_stock_note" to flow.sourceStockNote,
        "carried" to r.carried,
        "carried_different" to r.carriedDifferent,
        "dropped" to r.dropped.map { linkedMapOf("table" to it.first, "name" to it.second) },
        "decisions" to r.decisions.map { d ->
            linkedMapOf("table" to d.absId, "name" to d.name, "action" to when (d.action) {
                TuneConvert.Action.Carry -> "carry"; TuneConvert.Action.KeepTemplate -> "baseline"; TuneConvert.Action.Reset -> "reset"
            }, "reason" to d.reason, "review" to d.review, "user_chosen" to d.userChosen)
        },
    )
}

/** `xbrex info` 對 .xbx 多印的幾行。 */
internal fun printProjectSummary(p: XbxProject) {
    println("  Project: tune ${p.tuneName}, created ${p.created} by ${p.createdBy}")
    println("  ${count(p.changelog.size, "change log entry", "change log entries")}, ${count(p.ecuIndex.size, "ECU read-back")} (${count(p.ecuFiles.size, "distinct content")}), ${count(p.conversions.size, "conversion report")}")
    (p.vehicle["notes"] as? String)?.takeIf { it.isNotBlank() }?.let { println("  Notes: ${it.lines().first()}${if (it.lines().size > 1) " ..." else ""}") }
}

private fun readProject(path: String): XbxProject = try { XbxProject.read(File(path)) } catch (e: XbxException) { fail("$path: ${e.message}") }

private fun describe(e: Map<String, Any?>): String {
    val head = "${e["time"]}  ${e["kind"]}"
    return when (e["kind"]) {
        "edit" -> "$head  ${e["table"]} ${e["name"]}  ${e["action"]}  (${(e["cells"] as? List<*>)?.size ?: 0} cells)"
        "convert" -> "$head  ${e["from"]} -> ${e["to"]}  report ${e["report"]}"
        "ecu-save" -> "$head  written to the ECU${e["restored"]?.let { ", ECU read-back #$it restored" } ?: ""}, ${(e["tables"] as? List<*>)?.size ?: 0} tables changed, backup ${e["backup"]}"
        "new" -> "$head  from ${e["from"]}"
        "notes" -> "$head  vehicle notes changed"
        else -> head
    }
}

/** `xbrex project …`。 */
internal fun project(args: List<String>) {
    when (args.getOrNull(0)) {
        "new" -> {
            val src = args.getOrNull(1) ?: fail("Usage: xbrex project new <tune> <out.xbx>")
            val out = File(args.getOrNull(2) ?: fail("Usage: xbrex project new <tune> <out.xbx>"))
            if (out.exists()) fail("${out.path} already exists")
            val o = open(src, pack())
            if (o.project != null) fail("$src is already a project")
            // 調校檔原封放進去：內容跟官方檔一樣，不重新編碼
            val p = projectFor(o)
            writeFileAtomically(out, p.toBytes())
            println("Created ${out.path}: tune ${p.tuneName} (${o.platform.display}, ${if (o.encrypted) "encrypted" else "not encrypted"})")
        }
        "info" -> {
            val p = readProject(args.getOrNull(1) ?: fail("Usage: xbrex project info <project.xbx>"))
            printProjectSummary(p)
            if (p.ecuIndex.isNotEmpty()) {
                println("  ECU read-backs (newest last):")
                p.ecuIndex.forEachIndexed { i, e -> println("    #${i + 1}  ${e["time"]}  ${e["reason"]}  ${e["file"]}  ${(e["ecu"] as? Map<*, *>)?.get("version") ?: ""}") }
            }
            if (p.conversions.isNotEmpty()) println("  Conversion reports: ${p.conversions.keys.joinToString()}")
        }
        "log" -> {
            val p = readProject(args.getOrNull(1) ?: fail("Usage: xbrex project log <project.xbx> [entries] [--cells]"))
            val n = args.getOrNull(2)?.toIntOrNull() ?: 50
            for (e in p.changelog.takeLast(n)) {
                println(describe(e))
                if ("--cells" in args) (e["cells"] as? List<*>)?.forEach { c -> (c as? List<*>)?.let { println("      [${it[0]}][${it[1]}] ${it[2]} -> ${it[3]}") } }
            }
        }
        "export" -> {
            val path = args.getOrNull(1) ?: fail("Usage: xbrex project export <project.xbx> [out] [--backup <n>]")
            val p = readProject(path)
            val bi = args.indexOf("--backup")
            val (name, bytes) = if (bi > 0) {
                val n = args.getOrNull(bi + 1)?.toIntOrNull() ?: fail("--backup needs a number (see xbrex project info)")
                val e = p.ecuIndex.getOrNull(n - 1) ?: fail("No ECU read-back #$n")
                val f = e["file"] as String
                val member = try { SafeFiles.requireSafeBasename(f.substringAfter('/')) } catch (e: Exception) { fail("Bad ECU read-back file name in the project") }
                member to (p.ecuFiles[f] ?: fail("$f is missing from the project"))
            } else p.tuneName to p.tuneBytes
            val outArg = args.drop(2).firstOrNull { !it.startsWith("--") && it != args.getOrNull(bi + 1) }
            val safeName = try { SafeFiles.requireSafeBasename(name) } catch (e: Exception) { fail("Bad file name in the project") }
            val out = File(outArg ?: (File(path).nameWithoutExtension + if (bi > 0) "_ecu_" + safeName else "." + safeName.substringAfterLast('.')))
            if (Files.isSymbolicLink(out.toPath())) fail("${out.path} is a symbolic link; refusing to write through it")
            if (out.exists()) fail("${out.path} already exists")
            writeFileAtomically(out, bytes)
            println("Wrote ${out.path} (${bytes.size} bytes, same content as in the project)")
        }
        "notes" -> {
            val path = args.getOrNull(1) ?: fail("Usage: xbrex project notes <project.xbx> [--set <text>]")
            val p = readProject(path)
            val si = args.indexOf("--set")
            if (si < 0) { println((p.vehicle["notes"] as? String).orEmpty().ifEmpty { "(no notes)" }); return }
            p.vehicle["notes"] = args.drop(si + 1).joinToString(" ")
            p.log(linkedMapOf("kind" to "notes", "by" to "xbrex"))
            writeFileAtomically(File(path), p.toBytes())
            println("Saved the notes in $path")
        }
        else -> fail("""Usage:
  xbrex project new <tune> <out.xbx>          Make a project from a tune (the tune goes in unchanged)
  xbrex project info <project.xbx>            Summary, ECU read-backs, conversion reports
  xbrex project log <project.xbx> [n] [--cells]
                                              Change log (last n entries; --cells lists every cell before -> after)
  xbrex project export <project.xbx> [out] [--backup <n>]
                                              Write out the tune, or ECU read-back #n, in the official format
  xbrex project notes <project.xbx> [--set <text>]
                                              Show or set the vehicle notes""")
    }
}

/** ECU 讀回的內容放進專案（同樣內容只存一份），存回專案檔。回傳成員名。 */
internal fun addBackupToProject(o: OpenedTune, text: ByteArray, reason: String, ecu: Map<String, Any?>, container: TuneContainer?): String {
    val p = o.project ?: error("not a project")
    val file = TuneFile.parse(text.toString(Charsets.UTF_8))
    val bytes = if (o.encrypted || container != null) (container ?: throw KeysRequiredException()).encrypt(text) else text
    val name = p.addEcuBackup(bytes, XbxProject.fingerprint(file), file.mapType, reason, ecu)
    writeFileAtomically(o.file, p.toBytes())
    return name
}
