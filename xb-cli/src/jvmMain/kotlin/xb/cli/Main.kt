package xb.cli

import xb.container.ConvertFlow
import xb.container.DefinitionPack
import xb.container.OfficialStock
import xb.container.KeyStore
import xb.container.KeysRequiredException
import xb.container.OpenedTune
import xb.container.SafeFiles
import xb.container.TuneIo
import xb.container.TuneOpenException
import xb.container.XbxProject
import xb.tune.AdjustOp
import xb.tune.AxisLookup
import xb.tune.Cell
import xb.tune.Convert
import xb.tune.DiffKind
import xb.tune.EditResult
import xb.tune.TableDef
import xb.tune.TuneCompare
import xb.tune.TuneConvert
import java.io.File
import kotlin.system.exitProcess

// CLI 一律英文；其他語言在桌面版。
private const val USAGE = """xbrex: XenoBrex command-line tool (inspect, edit, convert, ECU access, diagnostics)
  xbrex doctor                                Check the environment: definitions, keys (presence only), log, backups
  xbrex log [lines]                           Show the desktop app log (default: last 80 lines)
  xbrex skill [--install <dir>]               Print the AI agent debugging guide (SKILL.md); --install writes <dir>/xenobrex-debug/SKILL.md
  Every <tune> may also be an .xbx project (its tune is used). Saving to an .xbx keeps a change log in it.
  xbrex info <tune>                           Header and open-time checks
  xbrex list <tune> [max-level]               List tables (default: level 1)
  xbrex show <tune> <table id or name> [--raw]
                                              Show one table
  xbrex diff <tuneA> <tuneB>                  Compare two tunes
  xbrex edit <in> <out> <action>...           Edit values and save as a new file (.xml is saved unencrypted; so is an
                                              unencrypted input when no keys are set). <out> may be the input .xbx itself.
  xbrex convert <in> <target type> <out> [--table-id <vehicle>] [--carry <ids>] [--baseline <ids>] [--yes]
                                              Convert to another version or platform (target type AMX, ASX, AMX2, ASX2).
                                              The target vehicle's latest stock tune is downloaded from the official
                                              server as the baseline; without it, nothing is converted.
                                              The target vehicle defaults to the input's; --table-id picks any vehicle in
                                              the official list (e.g. one sharing the engine). A different vehicle must be
                                              confirmed with y/n, or with --yes.
                                              --carry / --baseline override the choice for listed tables (comma-separated
                                              ids): carry the input's values, or keep the baseline's. Learned values are
                                              always cleared; tables whose meaning changed cannot be carried.
  xbrex project new|info|log|export|notes ...  XenoBrex projects (.xbx): tune, vehicle notes, change log, ECU read-backs,
                                              conversion reports. Run xbrex project for details.
  xbrex ride info|export ...                  Ride logs (.xbl, shared with ReX). Run xbrex ride for details.
  xbrex sync serve|phones|forget|rides|mapdefs ...
                                              Let ReX phones connect: live data, recordings, map definitions.
                                              Run xbrex sync for details.
  xbrex stock <type> [search]                 Vehicles in the official list (Table_ID, vehicle, configuration, stock file)
Actions (cell range row0:col0:row1:col1, zero-based, inclusive):
  add:<table>:<range>:<n>   mul:<table>:<range>:<n>   div:<table>:<range>:<n>   set:<table>:<range>:<n>
  linear:<table>:<range>    smooth:<table>:<range>    raw:<table>:<row>:<col>:<raw value>
ECU (join the ECU's Wi-Fi first; address defaults to 192.168.88.88:6666; password from XB_ECU_PASSWORD, factory default 888888):
  xbrex ecu info <address>                    Platform, login, version, device name
  xbrex ecu compare <address> <tune>          Read the whole calibration back and list tables and cells that differ from the tune
  xbrex ecu snapshot <address> <tune> [out]   Read the whole calibration back and save it (default: ECU backup folder)
  xbrex ecu read <address> <SDO index> [sub]  Read one object (index in hex; table = 3000 + table id, sub = row)
  xbrex ecu save <address> <tune> [--backup <n>] [--yes]
                                              Write a whole tune to the ECU's flash (same as the desktop app's save to ECU).
                                              The ECU's version and platform must match the tune. The ECU is read back and
                                              backed up first; without --yes it only shows what would change.
                                              Restoring a backup is the same command with the backup file; for a project,
                                              add --backup <n> to write its ECU read-back #n (see xbrex project info).
Definitions folder: XB_DEFINITIONS, otherwise the one set in the desktop app.
Keys: needed only for encrypted files. xbrex keys - reads two lines from stdin; xbrex keys --file <path> reads a key file.
  They are saved to the settings folder (or point XB_KEYS at a key file). xbrex keys <key1> <key2> also works, but
  arguments may be kept in your shell history.
  xbrex import <XTune package or folder>      Read the keys and definitions from your own XTune package (.xapk/.apks/.apk),
                                              same as the desktop app's import. Keys are always saved; definitions go to the
                                              default folder unless you already use a folder of your own."""

/** CLI 顯示表名用的語言（定義檔的名稱檔）。 */
internal const val LANG = "en-US"

internal fun fail(msg: String): Nothing { System.err.println(msg); exitProcess(1) }

internal fun pack(): DefinitionPack {
    val dir = xb.container.XbPaths.definitionsDir()
    if (!dir.isDirectory) fail("Definitions folder not found: $dir")
    return DefinitionPack(dir, keys)
}

/** 使用者的金鑰；沒有設就是 null，只能開未加密的檔案。 */
internal val keys by lazy { KeyStore.load()?.container }

/** 金鑰輸入上限；金鑰很短，避免從 stdin／檔案讀進大量資料。 */
private const val MAX_KEYS_INPUT = 64L * 1024

private fun keyFile(path: String): xb.container.UserKeys {
    val bytes = try {
        java.nio.file.Files.newInputStream(java.nio.file.Path.of(path)).use { SafeFiles.boundedRead(it, MAX_KEYS_INPUT) }
    } catch (e: Exception) { fail("Cannot read the key file: ${e.message}") }
    return KeyStore.parse(String(bytes, Charsets.UTF_8)) ?: fail("The key file is not a key file or two keys: $path")
}

/** 讀金鑰：`-` 從 stdin 兩行，`--file` 讀金鑰檔，其他照舊當兩個參數。 */
private fun readKeys(args: List<String>): xb.container.UserKeys = when {
    args == listOf("-") -> {
        val twoLines = object : java.io.InputStream() {
            private var lines = 0
            override fun read(): Int {
                if (lines == 2) return -1
                val next = System.`in`.read()
                if (next == '\n'.code) lines++
                return next
            }
        }
        val bytes = try { SafeFiles.boundedRead(twoLines, MAX_KEYS_INPUT) } catch (e: Exception) { fail("Cannot read the keys from stdin: ${e.message}") }
        KeyStore.parse(String(bytes, Charsets.UTF_8)) ?: fail("Expected two lines (key1 and key2) on stdin")
    }
    args.size == 2 && args[0] == "--file" -> keyFile(args[1])
    args.size == 1 && args[0].startsWith("--file=") -> keyFile(args[0].substringAfter('='))
    args.isNotEmpty() && args[0] == "--file" -> fail("Usage: xbrex keys --file <path>")
    args.isEmpty() -> fail("Usage: xbrex keys - | xbrex keys --file <path> | xbrex keys <key1> <key2>")
    else -> {
        System.err.println("Warning: keys passed as arguments may be kept in your shell history. Prefer: xbrex keys - or xbrex keys --file <path>")
        KeyStore.parse(args.joinToString(" ")) ?: fail("Usage: xbrex keys - | xbrex keys --file <path> | xbrex keys <key1> <key2>")
    }
}

private const val NO_KEYS = "Encrypted; keys are required. Run xbrex keys <key1> <key2> first (XenoBrex does not ship keys)."

internal fun open(path: String, pack: DefinitionPack): OpenedTune =
    try { TuneIo.open(File(path), pack, keys) }
    catch (e: TuneOpenException) { fail("$path: ${e.message}") }
    catch (e: KeysRequiredException) { fail("$path: $NO_KEYS") }

private fun OpenedTune.find(key: String): TableDef {
    val defs = tune.definitions.tables.values + (tune.attachedDefinitions?.tables?.values ?: emptyList())
    key.toIntOrNull()?.let { id -> tune.definition(id)?.let { return it } }
    return defs.firstOrNull { it.name == key } ?: fail("Table not found: $key")
}

private fun fmt(v: Double, dec: Int): String = "%.${dec}f".format(v)

private fun cells(spec: List<String>): List<Cell> {
    val (r0, c0, r1, c1) = spec.map { it.toInt() }
    return (r0..r1).flatMap { r -> (c0..c1).map { c -> Cell(r, c) } }
}

private fun count(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

fun main(args: Array<String>) {
    if (args.isEmpty()) fail(USAGE)
    if (args[0] == "keys") {
        val parsed = readKeys(args.drop(1))
        try { KeyStore.save(parsed) } catch (e: Exception) { fail("Cannot save the keys: ${e.message}") }
        println("Saved to ${KeyStore.defaultFile()} (not verified; opening an encrypted file will tell)")
        return
    }
    when (args[0]) {
        "ecu" -> return ecu(args.drop(1))
        "doctor" -> return doctor()
        "log" -> return log(args.getOrNull(1)?.toIntOrNull() ?: 80)
        "skill" -> return skill(args.drop(1))
        "import" -> return importXTune(args.drop(1))
        "project" -> return project(args.drop(1))
        "ride" -> return ride(args.drop(1))
        "sync" -> return sync(args.drop(1))
        "help", "-h", "--help" -> { println(USAGE); return }
    }
    val pack = pack()
    when (args[0]) {
        "info" -> {
            val o = open(args.getOrNull(1) ?: fail(USAGE), pack)
            val f = o.tune.file
            println("${o.file.name}: ${o.platform.display} (${f.mapType}), ${if (o.encrypted) "encrypted" else "not encrypted"}")
            println("  V${f.vCode} C${f.cCode} A${f.aCode}  Table_ID ${f.tableId}  model ${f.modelId}${if (f.is300kPa) " (300 kPa)" else ""}  saved ${f.lastSaveTime}")
            println("  ${count(f.tables.values.count { !it.attached }, "table")}" + (f.symgu?.let { ", ${count(f.tables.values.count { t -> t.attached }, "attached MGU table")} (C${it.cVersion})" } ?: ""))
            val r = o.tune.check()
            println("  Checked ${r.tablesChecked} tables, ${r.cells} cells: ${r.cellsOutOfRange} out of range (${r.tablesOutOfRange.size} tables), ${r.cellsNotReversible} not reversible (${r.tablesNotReversible.size} tables)")
            println("  Size mismatch ${r.sizeMismatch.size}, axis missing ${r.axisMissing.size}, not in definitions ${r.unknownTables.size}, missing from tune ${r.missingTables.size}")
            val levels = o.tune.definitions.tables.values.groupingBy { o.tune.level(it) }.eachCount().toSortedMap(compareBy { it ?: 99 })
            println("  Visibility levels: " + levels.entries.joinToString(", ") { "${it.key ?: "disabled"}=${it.value}" })
            o.project?.let(::printProjectSummary)
        }
        "list" -> {
            val o = open(args.getOrNull(1) ?: fail(USAGE), pack)
            val max = args.getOrNull(2)?.toInt() ?: 1
            for (d in o.tune.definitions.tables.values) {
                val lv = o.tune.level(d) ?: continue
                if (lv > max || d.isEeprom) continue
                println("%5d  %-4s %-34s %-18s %s".format(d.absId, d.kind, d.name, d.group, o.labels.shortName(d.absId, LANG) ?: ""))
            }
        }
        "show" -> {
            val o = open(args.getOrNull(1) ?: fail(USAGE), pack)
            val d = o.find(args.getOrNull(2) ?: fail(USAGE))
            val raw = args.contains("--raw")
            val rows = o.tune.rows(d.absId) ?: fail("${d.absId} ${d.name} has no data in this tune")
            val ax = (o.tune.axisX(d) as? AxisLookup.Found)?.axis
            val ay = (o.tune.axisY(d) as? AxisLookup.Found)?.axis
            println("${d.absId} ${d.name}  ${o.labels.shortName(d.absId, LANG) ?: ""}  ${d.kind}  ${d.unit}  range ${d.min} to ${d.max}  level ${o.tune.level(d) ?: "disabled"}")
            println("  Stored ${d.storedColumns}x${d.storedRows}, effective ${o.tune.effectiveColumns(d)}x${o.tune.effectiveRows(d)}" +
                (ax?.let { "  X=${it.name}${if (it.editable) " (editable)" else ""}" } ?: "") + (ay?.let { "  Y=${it.name}${if (it.editable) " (editable)" else ""}" } ?: ""))
            val ny = minOf(if (raw) d.storedRows else o.tune.effectiveRows(d), rows.size)
            val nx = minOf(if (raw) d.storedColumns else o.tune.effectiveColumns(d), rows.minOfOrNull { it.size } ?: 0)
            if (ny == 0 || nx == 0) { println("  (no cells)"); return }
            if (ax != null && !raw) println("        " + ax.values.take(nx).joinToString(" ") { "%7s".format(fmt(it.toDouble(), 0)) })
            for (y in 0 until ny) {
                val head = if (ay != null && !raw && y < ay.values.size) "%7s ".format(fmt(ay.values[y].toDouble(), 0)) else "        "
                println(head + (0 until nx).joinToString(" ") { x ->
                    val v = rows[y][x]
                    val mark = if (d.gain != 0f && Convert.isOutOfRange(d, v)) "!" else ""
                    "%7s".format(if (raw || d.gain == 0f) "$v" else fmt(Convert.rawToPhysical(d, v), d.decimals) + mark)
                })
            }
        }
        "diff" -> {
            val a = open(args.getOrNull(1) ?: fail(USAGE), pack)
            val b = open(args.getOrNull(2) ?: fail(USAGE), pack)
            val diffs = TuneCompare.compare(a.tune, b.tune)
            for (d in diffs) {
                val def = a.tune.definition(d.absId) ?: b.tune.definition(d.absId)
                when (d.kind) {
                    DiffKind.Different -> {
                        println("${d.absId} ${d.name}: ${count(d.cells.size, "cell")} differ" + (if (d.cells.any { !it.shownDiffers }) " (${d.cells.count { !it.shownDiffers }} of them display the same)" else ""))
                        for (c in d.cells.take(6)) println("    [${c.row}][${c.col}] ${c.left} -> ${c.right}" +
                            (def?.takeIf { it.gain != 0f }?.let { "  (${fmt(Convert.rawToPhysical(it, c.left), it.decimals)} -> ${fmt(Convert.rawToPhysical(it, c.right), it.decimals)})" } ?: ""))
                        if (d.cells.size > 6) println("    ...")
                    }
                    DiffKind.OnlyLeft -> println("${d.absId} ${d.name}: only in A")
                    DiffKind.OnlyRight -> println("${d.absId} ${d.name}: only in B")
                    DiffKind.SizeDiffers -> println("${d.absId} ${d.name}: size differs")
                }
            }
            println("${count(diffs.size, "table")}, ${count(diffs.sumOf { it.cells.size }, "cell")} differ")
        }
        "edit" -> {
            if (args.size < 4) fail(USAGE)
            val o = open(args[1], pack)
            val out = File(args[2])
            checkProjectTarget(o, out)
            val entries = ArrayList<Map<String, Any?>>()
            for (spec in args.drop(3)) {
                val p = spec.split(':')
                val d = o.find(p.getOrNull(1) ?: fail("Bad action: $spec"))
                val before = XbxProject.copyRows(o.tune, d.absId)
                val res = when (p[0]) {
                    "add" -> o.tune.adjust(d.absId, cells(p.subList(2, 6)), AdjustOp.Add, p[6].toDouble())
                    "mul" -> o.tune.adjust(d.absId, cells(p.subList(2, 6)), AdjustOp.Multiply, p[6].toDouble())
                    "div" -> o.tune.adjust(d.absId, cells(p.subList(2, 6)), AdjustOp.Divide, p[6].toDouble())
                    "set" -> o.tune.adjust(d.absId, cells(p.subList(2, 6)), AdjustOp.Set, p[6].toDouble())
                    "linear" -> o.tune.linear(d.absId, cells(p.subList(2, 6)))
                    "smooth" -> o.tune.smooth(d.absId, cells(p.subList(2, 6)))
                    "raw" -> o.tune.setRaw(d.absId, Cell(p[2].toInt(), p[3].toInt()), p[4].toLong())
                    else -> fail("Unknown action: ${p[0]}")
                }
                when (res) {
                    is EditResult.Done -> {
                        println("$spec: ${count(res.changed, "cell")} changed")
                        val changed = XbxProject.changedCells(before, o.tune.rows(d.absId))
                        if (changed.isNotEmpty()) entries += linkedMapOf("kind" to "edit", "by" to "xbrex", "table" to d.absId, "name" to d.name, "action" to spec, "cells" to changed)
                    }
                    is EditResult.Refused -> fail("$spec: ${res.reason}")
                }
            }
            val how = saveTune(o, out, entries)
            println("Saved ${out.path} ($how), ${count(o.tune.modifiedTables.size, "table")} changed: ${o.tune.modifiedTables.joinToString()}")
        }
        "stock" -> {
            val src = OfficialStock.Source.ofMapType(args.getOrNull(1)?.uppercase() ?: fail(USAGE)) ?: fail("Type must be AMX, ASX, AMX2 or ASX2")
            val list = try { OfficialStock.fetchList(src, keys ?: fail(NO_KEYS)) } catch (e: OfficialStock.NoBaselineException) { fail(e.message ?: "") }
            val found = list.vehicles(args.drop(2).joinToString(" ").ifBlank { null })
            println("Official ${src.display} list ver ${list.version}: ${count(found.size, "vehicle configuration")}")
            for (e in found) println("  ${e.tableId ?: "?"}  ${e.vehicle} / ${e.styleEn}  ${e.file}")
        }
        "convert" -> {
            if (args.size < 4) fail(USAGE)
            val o = open(args[1], pack)
            val out = File(args[3])
            checkProjectTarget(o, out)
            val tableId = args.indexOf("--table-id").takeIf { it > 0 }?.let { args.getOrNull(it + 1) } ?: o.tune.file.tableId
            val flow = try { ConvertFlow.run(o, args[2].uppercase(), pack, keys, tableId) }
                catch (e: OfficialStock.NoBaselineException) { fail("No official baseline, not converting: ${e.message}") }
                catch (e: KeysRequiredException) { fail(NO_KEYS) }
            val t = flow.target
            if (tableId.trim() != o.tune.file.tableId.trim() && "--yes" !in args) {
                val srcName = flow.sourceStock?.entry?.let { "${it.vehicle} / ${it.styleEn}" } ?: "not in the official list"
                println("The target vehicle differs from the input:")
                println("  input   ${o.tune.file.tableId} ($srcName)")
                println("  target  $tableId (${t.entry.vehicle} / ${t.entry.styleEn})")
                if (System.console() == null) fail("Add --yes to force the conversion.")
                print("Force the conversion? (y/n) ")
                System.out.flush()
                val ans = readlnOrNull()?.trim()?.lowercase()
                if (ans != "y" && ans != "yes") fail("Not converted.")
            }
            println("Baseline: official ${t.list.source.display} list ver ${t.list.version}  ${t.entry.file} (${t.entry.vehicle} / ${t.entry.styleEn}) md5 ${t.entry.md5}, ${if (t.fromCache) "cached" else "downloaded"}")
            println("Input's stock: " + (flow.sourceStock?.let { "${it.entry.file} (${if (it.fromCache) "cached" else "downloaded"})" } ?: "not used: ${flow.sourceStockNote}"))
            val r = flow.result
            fun ids(flag: String) = args.withIndex().filter { it.value == flag }.flatMap { (i, _) ->
                (args.getOrNull(i + 1) ?: fail("$flag needs table ids")).split(',').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: fail("$flag: not a table id: $it") }
            }
            val choices = ids("--carry").map { it to true } + ids("--baseline").map { it to false }
            choices.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { fail("Table $it is listed more than once") }
            for ((id, carry) in choices) TuneConvert.choose(r, o.tune, id, carry)?.let { fail("Not converted. $it") }
            val f = o.tune.file; val g = r.tune.file
            println("${f.mapType} C${f.cCode} -> ${g.mapType} C${g.cCode}: carried ${r.carried} (${r.carriedDifferent} differ from the baseline), baseline kept ${r.kept.size}, learned values cleared ${r.reset.size}, dropped ${r.dropped.size}")
            r.kept.groupBy { it.reason.substringBefore(" (").substringBefore(':').substringBefore(';') }.forEach { (why, l) -> println("  Baseline kept: $why, ${count(l.size, "table")}") }
            if (r.reset.isNotEmpty()) println("  Learned values cleared: " + r.reset.joinToString { "${it.absId} ${it.name}" })
            fun choice(d: TuneConvert.Decision) = if (d.action == TuneConvert.Action.Carry) "carried" else "baseline"
            val chosen = r.decisions.filter { it.userChosen }
            if (chosen.isNotEmpty()) {
                println("Your choices (${count(chosen.size, "table")}):")
                for (d in chosen) println("  ${d.absId} ${d.name} -> ${choice(d)} (${d.reason})")
            }
            val open = r.toReview.filter { !it.userChosen }
            if (open.isNotEmpty()) {
                println("Please review ${count(open.size, "table")} (current choice in brackets; change with --carry or --baseline):")
                for (d in open) println("  ${d.absId} ${d.name} [${choice(d)}: ${d.reason}]")
            }
            val converted = flow.opened.let { c -> OpenedTune(c.tune, o.file, true, c.platform, c.labels, o.project) }
            val entries = ArrayList<Map<String, Any?>>()
            val project = if (isProject(out)) projectFor(o).also { p ->
                val name = p.addConversion(convertReport(o, flow, tableId))
                entries += linkedMapOf("kind" to "convert", "by" to "xbrex", "from" to "${f.mapType} C${f.cCode} ${f.tableId}", "to" to "${g.mapType} C${g.cCode} ${g.tableId}", "report" to name)
            } else null
            val how = saveTune(converted, out, entries, project)
            println("Saved ${out.path} ($how)")
        }
        else -> fail(USAGE)
    }
}
