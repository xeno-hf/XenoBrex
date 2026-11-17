package xb.cli

import xb.container.OfficialStock
import xb.container.SafeFiles
import xb.container.XblRide
import xb.container.XbxException
import java.io.File
import java.nio.file.Files

private fun openRide(path: String): XblRide = try { XblRide.open(File(path)) } catch (e: XbxException) { fail("$path: ${e.message}") }

/** 從官方清單查車種代碼對應的車款（要金鑰、要連網）。 */
private fun lookupVehicle(mapType: String?, tableId: String?): String {
    val src = mapType?.let { OfficialStock.Source.ofMapType(it) } ?: return "no official list for ${mapType ?: "this platform"}"
    if (tableId.isNullOrBlank()) return "no vehicle code recorded"
    val k = keys ?: return "keys are required to read the official list"
    return try {
        val e = OfficialStock.fetchList(src, k).find(tableId) ?: return "not in the official ${src.display} list"
        "${e.vehicle} / ${e.styleEn}"
    } catch (e: OfficialStock.NoBaselineException) { e.message ?: "official list unavailable" }
}

/** `xbrex ride …`：讀 ReX 打包的騎乘紀錄。 */
internal fun ride(args: List<String>) {
    when (args.getOrNull(0)) {
        "info" -> {
            val path = args.getOrNull(1) ?: fail("Usage: xbrex ride info <ride.xbl> [--lookup]")
            val r = openRide(path)
            val m = r.manifest
            val v = r.vehicle
            println("${File(path).name}: ${m["format"]}, written by ${m["created_by"]}, ${m["mode"]} copy")
            println("  Recording ${m["recording"]}" + if (m["started"] != null) ": ${m["started"]} to ${m["stopped"] ?: "?"}" else " (times not included in a public copy)")
            println("  Vehicle: ${v["platform"] ?: "?"} (${v["map_type"] ?: "?"}), vehicle code ${(v["table_id"] as? String)?.ifBlank { null } ?: "?"}, " +
                "version V${v["v"] ?: "?"} C${v["c"] ?: "?"} A${v["a"] ?: "?"}")
            if ("--lookup" in args) println("  Official list: ${lookupVehicle(v["map_type"] as? String, v["table_id"] as? String)}")
            println("  Members: ${r.members.joinToString()}")
            val w = r.writes()
            println("  Writes to the ECU: ${w.size}" + if (w.isEmpty()) "" else ", " + w.groupBy { it["what"] ?: it["index"] }.entries.joinToString { "${it.key} x${it.value.size}" })
        }
        "writes" -> {
            val r = openRide(args.getOrNull(1) ?: fail("Usage: xbrex ride writes <ride.xbl>"))
            for (w in r.writes()) println("  ${w["t_ms"]} ms  ${w["index"]}/${w["sub"]}  ${w["what"] ?: ""}  data ${w["data"]}  -> ${w["result"]}")
        }
        "export" -> {
            val path = args.getOrNull(1) ?: fail("Usage: xbrex ride export <ride.xbl> <out dir> | xbrex ride export <ride.xbl> --loga <out.loga>")
            val r = openRide(path)
            val li = args.indexOf("--loga")
            if (li > 0) {
                val out = File(args.getOrNull(li + 1) ?: (File(path).nameWithoutExtension + ".loga"))
                if (Files.isSymbolicLink(out.toPath())) fail("${out.path} is a symbolic link; refusing to write through it")
                if (out.exists()) fail("${out.path} already exists")
                writeFileAtomically(out, r.bytes("decoded.loga"))
                println("Wrote ${out.path} (open it in XTune)")
                return
            }
            val dir = File(args.getOrNull(2) ?: fail("An output folder is required"))
            if (Files.isSymbolicLink(dir.toPath())) fail("${dir.path} is a symbolic link; refusing to write through it")
            if (dir.exists() && !dir.isDirectory) fail("${dir.path} is not a folder")
            if (dir.exists() && (dir.list()?.isNotEmpty() == true)) fail("${dir.path} is not empty")
            try { Files.createDirectories(dir.toPath()) } catch (e: Exception) { fail("Cannot create ${dir.path}: ${e.message}") }
            // 成員只能留在目的資料夾內，也不能穿過目錄符號連結
            val root = try { dir.toPath().toRealPath() } catch (e: Exception) { fail("Cannot open ${dir.path}: ${e.message}") }
            val targets = r.members.filter { it != "mimetype" }.map { n ->
                val f = try { SafeFiles.resolveMember(root, n) } catch (e: Exception) { fail("Bad member name: $n") }
                if (!f.normalize().startsWith(root)) fail("Bad member name: $n")
                if (Files.isSymbolicLink(f)) fail("$n is a symbolic link; refusing to write through it")
                n to f
            }
            for ((n, f) in targets) writeFileAtomically(f.toFile(), r.bytes(n))
            println("Wrote ${r.members.size - 1} files to ${dir.path}")
        }
        else -> fail("""Usage:
  xbrex ride info <ride.xbl> [--lookup]       Recording, vehicle, writes to the ECU (--lookup: vehicle name from the official list)
  xbrex ride writes <ride.xbl>                Every write the app sent to the ECU during the ride, with the ECU's answer
  xbrex ride export <ride.xbl> <out dir>      Unpack every member
  xbrex ride export <ride.xbl> --loga [out]   Write only the loga, for XTune""")
    }
}
