package xb.cli

import rx.session.sync.SyncCrypto
import rx.session.sync.SyncWire
import xb.container.AppLog
import xb.container.XbPaths
import xb.sync.MapDefs
import xb.sync.TunePackZip
import xb.sync.SyncStore
import xb.sync.XbSync
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date

internal const val SYNC_USAGE = """xbrex sync: let ReX phones connect to this computer
  xbrex sync serve [--bind <address>] [--port <n>] [--pair]
                                Run the sync server until Ctrl-C (default port 6680, all network interfaces).
                                Paired phones can send live data and their recordings (.xbl) and get map definitions.
                                --pair shows a pairing code (valid 5 minutes) to type into ReX.
  xbrex sync phones             Paired phones
  xbrex sync forget <phone>     Unpair a phone (name or id)
  xbrex sync rides              Recordings received from phones
  xbrex sync mapdefs <type> <out.json> [--c <n>] [--table-id <vehicle>]
                                Write the map definitions ReX uses for its map page, for importing by hand
                                (type: AMX, ASX, AMX2, ASX2…; --c and --table-id keep only that version's tables)
  xbrex sync tunepack <type> <table-id> <out.zip>
                                Write the tune pack ReX uses for live tuning (all tables; needs the official stock tune)
Received recordings go to the rides folder: XB_RIDES, otherwise the one set in the desktop app."""

private fun store() = SyncStore(XbPaths.syncDir())

internal fun appName() = "XenoBrex ${runCatching { XbSync::class.java.`package`.implementationVersion }.getOrNull() ?: "dev"}"

/** 這台電腦可以給手機連的位址（略過 loopback）。 */
internal fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { i -> i.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { "${it.hostAddress} (${i.name})" } }
}.getOrDefault(emptyList())

internal fun sync(args: List<String>) {
    when (args.getOrNull(0)) {
        "serve" -> {
            val bind = args.valueOf("--bind")?.let(InetAddress::getByName)
            val port = args.valueOf("--port")?.toIntOrNull() ?: SyncWire.DEFAULT_PORT
            val s = XbSync(store(), { runCatching { packOrNull() }.getOrNull() }, XbPaths::ridesDir, appName(), { keys }) { line ->
                println(line); AppLog.write("sync", line)
            }
            s.start(bind, port)
            val me = s.store.identity()
            println("Sync server \"${me.name}\" on port ${s.port}. Recordings go to ${XbPaths.ridesDir()}")
            localAddresses().forEach { println("  address: $it") }
            if ("--pair" in args) {
                val w = s.startPairing()
                println("Pairing code: ${SyncCrypto.showCode(w.code)}  (valid for 5 minutes; type it into ReX → More → Sync to computer)")
            }
            Runtime.getRuntime().addShutdownHook(Thread { s.stop() })
            Thread.currentThread().join()
        }
        "phones" -> {
            val phones = store().phones()
            if (phones.isEmpty()) println("No paired phones. Run xbrex sync serve --pair, or pair in the desktop app's settings.")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm")
            for (p in phones) println("  ${p.name}  id ${p.id}  paired ${fmt.format(Date(p.pairedAt))}")
        }
        "forget" -> {
            val who = args.getOrNull(1) ?: fail("Usage: xbrex sync forget <phone name or id>")
            val st = store()
            val p = st.phones().filter { it.id == who || it.name == who }
            if (p.isEmpty()) fail("No paired phone called $who")
            if (p.size > 1) fail("More than one phone is called $who; use its id (xbrex sync phones)")
            st.forget(p.single().id)
            println("Unpaired ${p.single().name}. It has to be paired again to connect.")
        }
        "rides" -> {
            val root = XbPaths.ridesDir()
            val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
            if (dirs.isEmpty()) println("No recordings received yet ($root)")
            for (d in dirs) {
                val rides = d.listFiles { f -> f.name.endsWith(".xbl") }?.sortedBy { it.name } ?: emptyList()
                val live = File(d, "live").listFiles { f -> f.isDirectory }?.size ?: 0
                println("${d.name}: ${rides.size} recordings, $live live copies")
                rides.forEach { println("  ${it.name}  ${it.length() / 1024} KB") }
            }
        }
        "mapdefs" -> {
            val type = args.getOrNull(1) ?: fail("Usage: xbrex sync mapdefs <type> <out.json> [--c <n>] [--table-id <vehicle>]")
            val out = File(args.getOrNull(2) ?: fail("An output file is required"))
            if (out.exists()) fail("${out.path} already exists")
            val json = try {
                MapDefs.build(pack(), type, args.valueOf("--c")?.toIntOrNull(), MapDefs.modelOf(args.valueOf("--table-id")), appName())
            } catch (e: MapDefs.NoDefinitions) { fail(e.message ?: "No map definitions") }
            out.writeText(json)
            println("Wrote ${out.path} (${json.length / 1024} KB). Import it in ReX → Map → Import map definitions.")
        }
        "tunepack" -> {
            val type = args.getOrNull(1) ?: fail("Usage: xbrex sync tunepack <type> <table-id> <out.zip>")
            val tableId = args.getOrNull(2) ?: fail("A Table ID is required (for example 05_18_10_01)")
            val out = File(args.getOrNull(3) ?: fail("An output file is required"))
            if (out.exists()) fail("${out.path} already exists")
            val zip = try { TunePackZip.build(pack(), keys, type, tableId, appName()) } catch (e: TunePackZip.NoPack) { fail(e.message ?: "No tune pack") }
            out.writeBytes(zip)
            val p = TunePackZip.read(zip)
            println("Wrote ${out.path} (${zip.size / 1024} KB): ${p.platform.definition} ${p.mapType} C${p.cCode} ${p.tableId} ${p.vehicle ?: ""}")
        }
        else -> { println(SYNC_USAGE); if (args.isNotEmpty()) kotlin.system.exitProcess(1) }
    }
}

private fun List<String>.valueOf(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
