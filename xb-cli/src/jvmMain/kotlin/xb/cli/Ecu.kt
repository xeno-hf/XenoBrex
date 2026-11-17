package xb.cli

import kotlinx.coroutines.runBlocking
import rx.protocol.hexLower
import xb.container.AppLog
import xb.container.TuneIo
import xb.container.XbPaths
import xb.link.Amc
import xb.link.EcuLink
import xb.link.EcuSync
import xb.link.SdoAbortException
import xb.link.TcpTransport
import java.io.File
import java.io.FileOutputStream
import xb.container.XbxProject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** abort 碼的說明（aRacer 自訂的 0x06010004 不在 CiA 301 裡）。 */
private fun abortNote(code: Long): String = when (code) {
    0x06010004L -> "feature not enabled on this ECU"
    0x06010003L -> "login required"
    0x06010002L -> "read-only"
    0x06020000L -> "object does not exist on the ECU"
    0x06090011L -> "sub (row) out of range"
    else -> rx.protocol.Sdo.abortText(code)
}

/** 整份讀回（進度印在 stderr）。 */
private suspend fun readAll(link: EcuLink, tune: xb.tune.Tune) =
    EcuSync.readAll(link, tune) { d, t -> if (d % 100 == 0 || d == t) System.err.print("\r  read $d/$t tables") }.also { System.err.println() }

/**
 * ECU 原值存成備份。輸入是 .xbx 而沒指定 [out]：放進專案（同樣內容只存一份）。
 * 否則存到設定資料夾/ecu-backups/；有金鑰就照官方寫法加密、副檔名照型號，沒金鑰存 .xml。回傳說明。
 */
private fun saveBackup(snap: xb.link.Snapshot, opened: xb.container.OpenedTune, id: xb.link.EcuIdentity, reason: String, out: File? = null): Backup {
    check(snap.complete) { "The ECU snapshot is incomplete; no restorable backup can be saved (${snap.failed.size} unreadable tables)" }
    Amc.pack(opened.tune)
    val text = EcuSync.backup(snap, opened.tune, TuneIo.now()).toByteArray(Charsets.UTF_8)
    Amc.pack(xb.tune.Tune(opened.tune.definitions, opened.tune.scales, xb.tune.TuneFile.parse(text.decodeToString()), opened.tune.attachedDefinitions))
    if (out == null && opened.project != null) {
        val ecu = linkedMapOf<String, Any?>("version" to id.label(), "device" to id.name, "tables_read" to snap.ok.size, "unreadable" to snap.failed.size, "complete" to snap.complete)
        val name = addBackupToProject(opened, text, reason, ecu, keys)
        val expected = opened.project!!.ecuFiles[name]!!.copyOf()
        val stored = XbxProject.read(opened.file).ecuFiles[name] ?: error("Backup is missing from the saved project")
        check(stored.contentEquals(expected)) { "Project backup byte verification failed" }
        val decoded = if (xb.container.TuneContainer.looksEncrypted(stored)) (keys ?: error("Backup keys unavailable")).decrypt(stored) else stored
        val file = xb.tune.TuneFile.parse(decoded.decodeToString())
        // 同樣內容在專案裡只存一份；時間戳可能較舊，每張表仍要跟這次 ECU 讀回逐格相同。
        check(snap.ok.all { r -> file.tables[r.def.absId]?.rows?.let { rows ->
            rows.size == r.rows.size && rows.indices.all { rows[it].contentEquals(r.rows[it]) }
        } == true }) { "Project backup does not match the complete ECU snapshot" }
        return Backup(null, name, "${opened.file.path}, member $name")
    }
    val f = out ?: File.createTempFile(
        "ECU_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))}_${opened.file.nameWithoutExtension}_",
        ".${if (keys != null) TuneIo.extension(opened.tune) else "xml"}", XbPaths.backupDir().apply { mkdirs() })
    val encrypt = f.extension.lowercase() != "xml" && keys != null
    val bytes = if (encrypt) keys!!.encrypt(text) else text
    FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
    check(f.readBytes().contentEquals(bytes)) { "Backup verification failed" }
    return Backup(f, null, "${f.path} (${if (encrypt) "encrypted" else "not encrypted"})")
}

/**
 * `ecu save <p.xbx> --backup <n>`：寫回專案裡第 n 次讀回的內容。內容開成調校檔，專案照掛著（備份、修改紀錄照記）。
 */
private fun restoreSource(o: xb.container.OpenedTune, n: String?): xb.container.OpenedTune {
    val p = o.project ?: fail("--backup needs an .xbx project")
    val i = n?.toIntOrNull() ?: fail("--backup needs a number (see xbrex project info)")
    val e = p.ecuIndex.getOrNull(i - 1) ?: fail("No ECU read-back #$i in ${o.file.name}")
    val metadata = e["ecu"] as? Map<*, *>
    if (metadata?.get("complete") != true) fail("ECU read-back #$i is not a verified complete snapshot; it cannot be restored")
    val name = e["file"] as? String ?: fail("ECU read-back #$i has no file")
    val bytes = p.ecuFiles[name] ?: fail("$name is missing from the project")
    val tmp = File.createTempFile("xbrex-backup", "." + name.substringAfterLast('.'))
    try {
        tmp.writeBytes(bytes)
        val r = open(tmp.path, pack())
        println("Using ECU read-back #$i (${e["time"]}, ${e["reason"]}) from ${o.file.name}")
        return xb.container.OpenedTune(r.tune, o.file, r.encrypted, r.platform, r.labels, p)
    } finally { tmp.delete() }
}

private fun restoredFrom(args: List<String>): Int? = args.indexOf("--backup").takeIf { it > 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }

/** 備份在哪：一般檔案 [file]，或專案裡的成員 [member]。 */
private class Backup(val file: File?, val member: String?, val label: String)

/** `xbrex ecu …`。只有 save 會寫 ECU；沒加 --yes 就只比對、不寫。 */
internal fun ecu(args: List<String>) = runBlocking {
    val cmd = args.getOrNull(0) ?: fail("Usage: xbrex ecu info|compare|snapshot|save|read <address> ... (see xbrex help)")
    if (cmd !in setOf("info", "compare", "snapshot", "save", "read")) fail("Unknown command: ecu $cmd (see xbrex help)")
    val (host, port) = (args.getOrNull(1) ?: "192.168.88.88").split(":").let { it[0] to (it.getOrNull(1)?.toInt() ?: TcpTransport.PORT) }
    // 開調校檔要在連線前：檔案有問題就不必連
    val opened = (if (cmd == "compare" || cmd == "snapshot" || cmd == "save") open(args.getOrNull(2) ?: fail("A tune file is required"), pack()) else null)
        ?.let { o -> args.indexOf("--backup").takeIf { it > 0 }?.let { restoreSource(o, args.getOrNull(it + 1)) } ?: o }
    val pw = System.getenv("XB_ECU_PASSWORD")?.toInt() ?: 888888
    val log: (String) -> Unit = { System.err.println("  · $it"); AppLog.write("xbrex", it) }
    val link = try { EcuLink(TcpTransport.connect(host, port), this, log = log) }
        catch (e: Exception) { fail("Cannot connect to $host:$port: ${e.message}. Is this computer on the ECU's Wi-Fi?") }
    try {
        val login = link.connect(pw)
        val id = EcuSync.identity(link)
        println("${link.platform?.label ?: "node ${link.node.value}"}, login $login, version ${id.label()}, device name ${id.name ?: "?"}")
        if (login != "ok" && cmd != "info") fail("Login failed ($login); stopping.")
        when (cmd) {
            "info" -> Unit
            "read" -> {
                val index = args.getOrNull(2)?.toInt(16) ?: fail("An SDO index is required")
                val sub = args.getOrNull(3)?.toInt() ?: 0
                val d = link.read(index, sub)
                println("0x%04X/%d: %d bytes %s".format(index, sub, d.size, d.hexLower(" ")))
            }
            "compare", "snapshot" -> {
                val tune = opened!!.tune
                if (!id.matches(tune)) println("Note: ECU version ${id.label()} differs from the tune's V${tune.file.vCode} C${tune.file.cCode}; the comparison is for reference only")
                val snap = readAll(link, tune)
                println("Read ${snap.ok.size} tables, ${snap.failed.size} unreadable")
                snap.failed.groupBy { it.abortCode }.forEach { (code, l) ->
                    println("  Unreadable (${code?.let { "0x%08X %s".format(it, abortNote(it)) } ?: l.first().reason}): ${l.size} tables, e.g. ${l.take(6).joinToString { "${it.def.absId} ${it.def.name}" }}")
                }
                val deltas = EcuSync.differences(snap, tune)
                println("Differs from ${opened.file.name}: ${deltas.size} tables, ${deltas.sumOf { it.cells }} cells")
                for (d in deltas) {
                    val mine = tune.rows(d.def.absId)!!
                    val ecu = snap.rows(d.def.absId)!!
                    val cells = buildList { for (r in ecu.indices) for (c in ecu[r].indices) if (mine[r][c] != ecu[r][c]) add("[$r,$c] ${mine[r][c]}->${ecu[r][c]}") }
                    println("  ${d.def.absId} ${d.def.name} (${opened.labels.shortName(d.def.absId, LANG) ?: ""}): ${d.cells} cells  " +
                        cells.take(8).joinToString("  ") + if (cells.size > 8) "  ..." else "")
                }
                if (cmd == "snapshot") {
                    val out = saveBackup(snap, opened, id, "snapshot", args.getOrNull(3)?.let(::File))
                    println("Saved ${out.label}")
                }
            }
            "save" -> {
                val tune = opened!!.tune
                val reason = EcuSync.compatibility(EcuSync.identity(link), link.platform, tune)
                if (reason != null) fail("$reason. Not writing.")
                val packed = try { Amc.pack(tune) } catch (e: Amc.AmcException) { fail("Cannot pack the tune: ${e.message}") }
                val gen = link.generation
                val before = readAll(link, tune)
                if (!before.complete) fail("The ECU snapshot is incomplete (${before.failed.size} unreadable tables). Whole-image write and restore are disabled; nothing written.")
                EcuSync.compatibility(before.identity, link.platform, tune)?.let { fail("$it. Not writing.") }
                val backup = saveBackup(before, opened, before.identity, "before writing")
                println("Backup of the ECU before writing: ${backup.label} (${before.ok.size} tables read, ${before.failed.size} unreadable)")
                val undo = backup.file?.path ?: "${opened.file.path} --backup ${opened.project!!.ecuIndex.size}"
                val deltas = EcuSync.differences(before, tune)
                println("Tables that will change: ${deltas.size} (${deltas.sumOf { it.cells }} cells)" +
                    if (deltas.isEmpty()) "" else ": " + deltas.take(12).joinToString { "${it.def.absId} ${it.def.name}" } + if (deltas.size > 12) ", ..." else "")
                println("Package: ${packed.tables} tables, ${packed.data.size} bytes, ${packed.blocks} blocks, C${packed.cCode}")
                if ("--yes" !in args) { println("Dry run: nothing written. Add --yes to write ${opened.file.name} to the ECU."); return@runBlocking }
                if (!link.enableCalibration(before.ok.map { EcuSync.index(it.def) }.toSet(), before.complete, gen)) fail("The ECU connection changed; nothing written.")
                AppLog.write("xbrex", "ecu save ${opened.file.name}: ${packed.data.size} B, ${packed.blocks} blocks, backup ${backup.file?.name ?: backup.member}")
                val after = try {
                    link.amcExclusive {
                        val fresh = EcuSync.compatibility(EcuSync.identity(link), link.platform, tune)
                        check(fresh == null) { fresh ?: "Compatibility check failed" }
                        val verified = Amc.write(link, packed, log = log) { d, t -> System.err.print("\r  wrote $d/$t blocks") }
                        System.err.println()
                        verified
                    }
                } catch (e: Exception) {
                    link.disableCalibration()
                    System.err.println()
                    fail("Write not completed: ${e.message}\nThe ECU must be re-read before further tuning. Restore the verified backup if needed: xbrex ecu save $host:$port $undo --yes")
                }
                val left = EcuSync.differences(after, tune)
                if (!after.complete || left.isNotEmpty()) {
                    link.disableCalibration()
                    fail("Verification failed: ${after.failed.size} unreadable tables, ${left.size} differing tables. Re-read the ECU or restore the verified backup: xbrex ecu save $host:$port $undo --yes")
                }
                println("Written and fully verified: ${after.ok.size} tables all match the tune")
                opened.project?.let { p ->
                    p.log(linkedMapOf("kind" to "ecu-save", "by" to "xbrex", "ecu" to id.label(), "backup" to backup.member, "restored" to restoredFrom(args),
                        "tables" to deltas.map { it.def.absId }, "still_differ" to left.map { it.def.absId }))
                    p.save(opened.file)
                }
                println("To undo: xbrex ecu save $host:$port $undo --yes")
            }
            else -> fail("Unknown command: ecu $cmd (see xbrex help)")
        }
    } catch (e: SdoAbortException) {
        fail("Rejected by the ECU: 0x%08X %s (%s)".format(e.abort.code, abortNote(e.abort.code), e.abort))
    } finally {
        link.close()
    }
}
