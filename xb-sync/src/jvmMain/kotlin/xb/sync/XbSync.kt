package xb.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import rx.defs.ChannelRegistry
import rx.protocol.Frame
import rx.session.DecodedLayout
import rx.session.Recorder
import rx.session.Uplink
import rx.session.UplinkStream
import rx.session.sync.SyncCrypto
import rx.session.sync.SyncHost
import rx.session.sync.SyncServer
import rx.session.sync.SyncWire
import xb.container.DefinitionPack
import xb.container.SafeFiles
import xb.container.XblRide
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * 收檔上限。預設每台手機 4 GiB、全部 16 GiB、每台 2000 個檔（全部 20000 個），
 * 上傳前至少留 512 MiB；即時副本最多 2 小時或 1 GiB。
 */
class RideLimits(
    val phoneBytes: Long = 4L shl 30,
    val totalBytes: Long = 16L shl 30,
    val phoneFiles: Int = 2_000,
    val totalFiles: Int = 20_000,
    val freeBeforeUpload: Long = 512L shl 20,
    val liveBytes: Long = 1L shl 30,
    val liveMs: Long = 2 * 60 * 60 * 1_000L,
)

/**
 * XB 的同步伺服器（桌面版與 `xbrex sync serve` 共用）。
 * - 配對：[startPairing] 開一組 5 分鐘有效的配對碼，配好的手機存在 [store]。
 * - 即時資料：手機連上時把串流解成「通道 absid → 物理值」放進 [live]（油圖的準星可以直接用），
 *   [keepLiveCopy] 開著就同時存一份錄製到 [ridesDir]/<手機資料夾>/live/（跟手機本機錄製同格式）。
 * - 錄製：手機送來的 .xbl 存到 [ridesDir]/<手機資料夾>/<名稱>.xbl。
 * - 手機資料夾＝清理過的手機名稱＋id 的 SHA-256 前 12 碼，一律夾在 [ridesDir] 底下；
 *   舊版的「名稱＋id 前 6 碼」資料夾會先讀得到，第一次寫入時原子搬成新名字。
 * - 油圖定義包：照手機的 ECU 型號，從 [pack] 的定義檔現做（[MapDefs]）。
 */
class XbSync(
    val store: SyncStore,
    private val pack: () -> DefinitionPack?,
    val ridesDir: () -> File,
    private val app: String,
    /** 金鑰（做調校包要解官方原廠檔）；沒有就給不出調校包。 */
    private val container: () -> xb.container.TuneContainer? = { null },
    /** 收檔與即時副本的上限（測試會縮小）。 */
    private val limits: RideLimits = RideLimits(),
    private val log: (String) -> Unit = {},
) {
    /** 正在送即時資料的手機。 */
    class LiveFeed(
        val phone: String,
        val defs: String?,
        val values: Map<Int, Double>,
        val rounds: Long,
        val lastMs: Long,
        val online: Boolean,
        val recording: File?,
    )

    var keepLiveCopy: Boolean = true

    /** 測試用：上傳驗證完、寫入前叫一次（測試會在這時把這台手機移除）。 */
    internal var beforeUploadCommit: (() -> Unit)? = null

    private val _live = MutableStateFlow<LiveFeed?>(null)
    val live: StateFlow<LiveFeed?> = _live

    private val _pairing = MutableStateFlow<SyncWire.PairingWindow?>(null)
    val pairing: StateFlow<SyncWire.PairingWindow?> = _pairing

    /** 換掉或關掉配對碼時，舊的不能還能完成。 */
    private val pairingLock = Any()

    /** 配對成功、收到錄製時加一（介面靠它重讀清單）。 */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    @Volatile private var server: SyncServer? = null
    val running: Boolean get() = server?.running == true
    val port: Int get() = server?.boundPort ?: -1

    private val quotas = RideQuota({ ridesDir() }, limits, keysOf = { name ->
        val suffix = name.substringAfterLast('_')
        if (Regex("[0-9a-f]{12}").matches(suffix)) setOf(suffix)
        else store.phones().filter { it.id.take(6) == suffix }.map { folder(it).substringAfterLast('_') }.toSet().ifEmpty { setOf(name) }
    })
    private val rideSaveLock = Any()

    init {
        // 設定頁直接刪 store 裡的紀錄；這裡跟著把那台手機的連線關掉。
        store.onForget = { id -> server?.revoke(id) }
    }

    fun start(bind: InetAddress?, port: Int = SyncWire.DEFAULT_PORT) {
        stop()
        val s = SyncServer(store.identity(), host, bind, port)
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(); server = null
        cancelPairing()
        _live.value = _live.value?.let { LiveFeed(it.phone, it.defs, it.values, it.rounds, it.lastMs, false, it.recording) }
    }

    fun startPairing(): SyncWire.PairingWindow = synchronized(pairingLock) {
        _pairing.value?.cancel()
        SyncWire.PairingWindow(SyncCrypto.newPairingCode(), System.currentTimeMillis() + SyncWire.PairingWindow.LIFETIME_MS).also {
            _pairing.value = it
            log("Sync: pairing open for ${SyncWire.PairingWindow.LIFETIME_MS / 60_000} min")
        }
    }

    fun cancelPairing() = synchronized(pairingLock) {
        _pairing.value?.cancel()
        _pairing.value = null
    }

    /** 這台手機目前幾條同步連線（測試與介面用）。 */
    fun activeSessions(phoneId: String): Int = server?.activeSessions(phoneId) ?: 0

    /** 一台手機同步過來的 .xbl（新的在前）；舊版資料夾還沒搬過來時也讀得到。 */
    fun rides(phone: SyncWire.Peer): List<File> {
        val byName = LinkedHashMap<String, File>()
        for (dir in rideDirs(phone)) {
            if (!Files.isDirectory(dir)) continue
            for (f in dir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".xbl") } ?: emptyArray()) byName.putIfAbsent(f.name, f)
        }
        return byName.values.sortedByDescending { it.name }
    }

    /** 手機的資料夾：清理過的名稱＋id 雜湊前 12 碼；不含路徑分隔符。 */
    fun folder(phone: SyncWire.Peer): String = folderOf(phone.id, phone.name)

    private fun ridesRoot(): Path = ridesDir().toPath().toAbsolutePath().normalize().also {
        if (Files.isSymbolicLink(it)) throw IOException("Rides folder is a symbolic link")
        Files.createDirectories(it)
    }

    /** 舊版的資料夾名（名稱＋id 前 6 碼），只用來讀回或搬移既有錄製。 */
    private fun legacyFolder(peer: SyncWire.Peer): String {
        val clean = peer.name.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }
            .joinToString("").trim('.', '_').take(64).ifEmpty { "phone" }
        return "${clean}_${peer.id.take(6)}"
    }

    /**
     * 舊版資料夾。別的已配對手機也用同一個舊名字時不動，免得以名稱＋id 前 6 碼撞進別人的資料。
     */
    private fun legacyDir(root: Path, peer: SyncWire.Peer): Path? {
        val name = legacyFolder(peer)
        if (name == folder(peer)) return null
        if (store.phones().any { it.id != peer.id && legacyFolder(it) == name }) return null
        val dir = root.resolve(name).normalize()
        if (dir.parent != root || dir == root) return null
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null
        return dir
    }

    /** 新資料夾優先；舊資料夾還沒搬、或搬不動時也列入（只讀）。 */
    private fun rideDirs(peer: SyncWire.Peer): List<Path> {
        val dirs = LinkedHashSet<Path>()
        dirs.add(phoneDir(peer, create = false))
        legacyDir(ridesRoot(), peer)?.let { dirs.add(it) }
        return dirs.toList()
    }

    /** 手機資料夾一定在 [ridesRoot] 正下方，而且不是符號連結。 */
    private fun phoneDir(peer: SyncWire.Peer, create: Boolean = true): Path {
        val root = ridesRoot()
        val dir = SafeFiles.resolveBasename(root, folder(peer))
        require(dir.parent == root) { "Phone folder escapes the rides folder" }
        if (Files.isSymbolicLink(dir)) throw IOException("Phone folder is a symbolic link")
        if (create) {
            val legacy = legacyDir(root, peer)
            if (legacy != null && !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { Files.move(legacy, dir, StandardCopyOption.ATOMIC_MOVE) }
                    .onFailure { log("Sync: could not move old rides folder ${legacy.fileName}: ${it.message ?: it.javaClass.simpleName}") }
            }
            Files.createDirectories(dir)
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw IOException("Phone folder is not a folder")
        } else if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            legacyDir(root, peer)?.let { return it }
        }
        return dir
    }

    private fun liveDir(peer: SyncWire.Peer): Path {
        val dir = SafeFiles.resolveBasename(phoneDir(peer), "live")
        if (Files.isSymbolicLink(dir)) throw IOException("Live folder is a symbolic link")
        Files.createDirectories(dir)
        return dir
    }

    /** 檔名先驗過，再確認結果還在那個資料夾裡。 */
    private fun ridePath(dir: Path, name: String): Path {
        SafeFiles.requireSafeBasename(name)
        val target = dir.resolve("$name.xbl").normalize()
        require(target.parent == dir && target.startsWith(dir)) { "Ride name escapes its folder" }
        return target
    }

    /** 同資料夾、唯一檔名；POSIX 從建立就只給自己讀寫。 */
    private fun tempFile(dir: Path, prefix: String): Path {
        val posix = Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView::class.java) != null
        return if (posix) Files.createTempFile(dir, prefix, ".part", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else Files.createTempFile(dir, prefix, ".part")
    }

    private val host = object : SyncHost {
        override fun findPeer(id: String) = store.phone(id)

        override fun pairing() = _pairing.value

        override fun onPaired(peer: SyncWire.Peer) {
            synchronized(pairingLock) {
                // 只關掉用掉的那一組；期間若換了新碼，新的不能跟著被清掉。
                val w = _pairing.value
                if (w == null || !w.open) {
                    w?.cancel()
                    _pairing.value = null
                }
            }
            store.addPhone(peer)
            _changes.value++
        }

        override fun live(peer: SyncWire.Peer, input: InputStream): Boolean {
            var layout: DecodedLayout? = null
            var rec: Recorder? = null
            var recBudget: RecordingBudget? = null
            var recDir: File? = null
            var recTicket: RideQuota.Ticket? = null
            val recordingLock = Any()
            var watchdog: java.util.Timer? = null
            var recordingAttempted = false
            val cap = LiveRecordingCap(limits.liveMs, limits.liveBytes)
            var defsKey: String? = null
            var rounds = 0L
            var lastPublish = 0L
            fun publish(values: Map<Int, Double>, online: Boolean) {
                _live.value = LiveFeed(peer.name, defsKey, values, rounds, System.currentTimeMillis(), online, synchronized(recordingLock) { recDir })
            }
            fun stopRecording(reason: String) = synchronized(recordingLock) {
                val r = rec ?: return@synchronized
                rec = null; recDir = null
                watchdog?.cancel(); watchdog = null
                // 先打開收檔保留量：關檔的 flush 與最後的 meta 不會被資料上限打斷。
                recBudget?.beginFinalize(); recBudget = null
                runCatching { r.onEvent(System.nanoTime(), "Live copy stopped: $reason") }
                runCatching { r.close() }.onFailure {
                    // 不再重試：放棄沒寫出去的資料、關檔（meta 維持上一份，看得出沒收完）
                    r.abandon()
                    log("Sync: ${peer.name} live copy did not finalize: ${it.message ?: it.javaClass.simpleName}")
                }
                quotas.release(recTicket); recTicket = null
                log("Sync: ${peer.name} live copy stopped ($reason)")
            }
            fun checkRecordingLimit() {
                val dir = recDir ?: return
                cap.exceeded(dir, System.nanoTime() / 1_000_000)?.let { stopRecording(it) }
            }
            fun record(write: (Recorder) -> Unit) = synchronized(recordingLock) {
                checkRecordingLimit()
                val r = rec ?: return@synchronized
                // 寫入端自己擋上限；踫到就收掉副本，即時資料照常。
                try { write(r) } catch (e: RecordingLimitException) { stopRecording("size limit") } catch (e: java.io.IOException) { stopRecording("write failed: ${e.message}") }
                checkRecordingLimit()
            }
            var ended = false
            try {
                ended = UplinkStream.read(input, object : UplinkStream.Handler {
                    override fun onHello(text: String) {
                        val (_, table, info) = Uplink.parseHello(text)
                        // 用內建定義的 ReX 給平台代號；匯入定義檔的給序號，拿來對這台電腦的定義檔
                        defsKey = info["rx.defs"]?.takeIf { it.isNotBlank() } ?: info["rx.defs_serial"]?.takeIf { it.isNotBlank() }?.let { sn ->
                            pack()?.let { p -> p.platforms.firstOrNull { runCatching { p.definitions(it.definition)?.serial }.getOrNull() == sn }?.definition }
                        }
                        val reg = defsKey?.let { key -> runCatching { pack()?.text("ControlFile_$key")?.let(ChannelRegistry::parse) }.getOrNull() }
                        layout = reg?.let { DecodedLayout(it, table) }
                        if (reg == null) log("Sync: ${peer.name} live: no ControlFile_${defsKey ?: "?"} here, values not decoded")
                        if (!recordingAttempted && keepLiveCopy && layout != null) {
                            recordingAttempted = true
                            runCatching { synchronized(recordingLock) {
                                val dir = liveDir(peer)
                                // 先訂下整個上限，收檔時才不會跟同步上傳一起超量；收檔後退回實際用量
                                val ticket = quotas.reserve(folder(peer), dir.toFile(), limits.liveBytes, LIVE_SESSION_FILES)
                                try {
                                    val unique = Files.createTempDirectory(dir, "LINK_${System.currentTimeMillis()}_")
                                    val session = kotlinx.io.files.Path(unique.toString())
                                    // 每個寫入都先扣預算，收檔的 meta 也走同一條路；時間上限仍交給每秒的時鐘。
                                    // 留一段收檔保留量，資料先停，關檔與 meta 才寫得完。
                                    // 保留量＝撞到上限時還在緩衝的資料（最長的事件、loga 列）＋最後的 meta（hello 帶的 info 可能很長），照最壞的跳脫算
                                    val recInfo = info + ("link.from" to peer.name)
                                    val budget = RecordingBudget(limits.liveBytes, LIVE_FINALIZE_RESERVE + Recorder.finalizeBound(recInfo, layout!!))
                                    rec = Recorder(session, layout!!, recInfo, openSink = { p ->
                                        CappedSink(kotlinx.io.files.SystemFileSystem.sink(p), budget)
                                    })
                                    recBudget = budget
                                    recDir = File(session.toString())
                                    recTicket = ticket
                                    cap.start(System.nanoTime() / 1_000_000)
                                    watchdog = java.util.Timer("xb-live-limit", true).also { timer ->
                                        timer.schedule(object : java.util.TimerTask() {
                                            override fun run() { synchronized(recordingLock) { checkRecordingLimit() } }
                                        }, 1_000L, 1_000L)
                                    }
                                } catch (e: Exception) {
                                    quotas.release(ticket)
                                    throw e
                                }
                            } }.onFailure { log("Sync: ${peer.name} live copy not recorded: ${it.message ?: it.javaClass.simpleName}") }
                        }
                        publish(emptyMap(), true)
                    }
                    override fun onFrame(tNanos: Long, dir: Int, frame: Frame) { record { it.onFrame(tNanos, dir, frame) } }
                    override fun onRound(tNanos: Long, raw: LongArray) {
                        rounds++
                        record { it.onRound(tNanos, raw) }
                        val l = layout ?: return
                        val now = System.currentTimeMillis()
                        if (now - lastPublish < 50) return
                        lastPublish = now
                        val out = HashMap<Int, Double>(l.columns.size)
                        for (c in l.columns) if (c.entryIndex < raw.size) out[c.def.absid] = l.value(c, raw)
                        publish(out, true)
                    }
                    override fun onEvent(tNanos: Long, text: String) { record { it.onEvent(tNanos, text) } }
                })
            } finally {
                synchronized(recordingLock) {
                    watchdog?.cancel(); watchdog = null
                    rec?.let { r ->
                        rec = null
                        recBudget?.beginFinalize(); recBudget = null
                        runCatching { r.onEvent(System.nanoTime(), if (ended) "Sync ended" else "Sync dropped") }
                        runCatching { r.close() }.onFailure {
                            r.abandon()
                            log("Sync: ${peer.name} live copy did not finalize: ${it.message ?: it.javaClass.simpleName}")
                        }
                    }
                    quotas.release(recTicket); recTicket = null
                }
                publish(_live.value?.values ?: emptyMap(), false)
            }
            return ended
        }

        override fun missingRides(peer: SyncWire.Peer, names: List<String>): List<String> {
            val dirs = rideDirs(peer)
            return names.filter { name ->
                val paths = runCatching { dirs.map { ridePath(it, name) } }.getOrNull() ?: return@filter false
                paths.none { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            }
        }

        override fun storeRide(peer: SyncWire.Peer, name: String, input: InputStream, length: Long): ByteArray {
            SafeFiles.requireSafeBasename(name)
            require(length in 1..(1L shl 30)) { "Invalid or oversized ride" }
            val dir = phoneDir(peer)
            val target = ridePath(dir, name)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("Already synced")
            val ticket = quotas.reserve(folder(peer), dir.toFile(), length, 1)
            var tmp: Path? = null
            val md = MessageDigest.getInstance("SHA-256")
            try {
                val staging = tempFile(dir, ".$name.")
                tmp = staging
                Files.newOutputStream(staging, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { out ->
                    val digestOut = java.security.DigestOutputStream(out, md)
                    val total = SafeFiles.copyBounded(input, digestOut, length)
                    require(total == length) { "Ride was cut off" }
                }
                val head = Files.newInputStream(staging).use { it.readNBytes(256) }
                require(XblRide.isXbl(head)) { "Not a .xbl ride" }
                runCatching { XblRide.open(staging.toFile()) }.onFailure { throw IllegalArgumentException("Ride file is damaged: ${it.message}") }
                beforeUploadCommit?.invoke()
                // 同一台手機的兩個連線同時送同名檔時，只有一個能存進去；定案和 forget 也排在同一把鎖上。
                synchronized(rideSaveLock) {
                    store.locked {
                        // 驗證期間可能在另一個行程被移除：不再寫入，暫存檔由 finally 丟掉。
                        if (!store.stillAuthorized(peer)) throw IllegalStateException("This phone is no longer paired")
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("Already synced")
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } finally {
                tmp?.let { Files.deleteIfExists(it) }
                quotas.release(ticket)
            }
            _changes.value++
            return md.digest()
        }

        override fun defs(peer: SyncWire.Peer, request: Map<String, String>): ByteArray {
            val p = pack() ?: throw MapDefs.NoDefinitions("No definitions folder on this computer")
            val type = request["map_type"]?.takeIf { it.isNotBlank() } ?: request["platform"] ?: throw MapDefs.NoDefinitions("Phone did not say which ECU")
            val json = MapDefs.build(p, type, request["c"]?.toIntOrNull(), MapDefs.modelOf(request["table_id"]), app)
            log("Sync: sent map definitions for $type C${request["c"] ?: "?"} to ${peer.name}")
            return json.toByteArray(Charsets.UTF_8)
        }

        override fun tunePack(peer: SyncWire.Peer, request: Map<String, String>): ByteArray {
            val p = pack() ?: throw TunePackZip.NoPack("No definitions folder on this computer")
            val type = request["map_type"]?.takeIf { it.isNotBlank() } ?: throw TunePackZip.NoPack("Phone did not say which ECU")
            val zip = TunePackZip.build(p, container(), type, request["table_id"] ?: "", app)
            log("Sync: sent tune pack for $type ${request["table_id"]} to ${peer.name} (${zip.size} B)")
            return zip
        }

        override fun log(line: String) = this@XbSync.log(line)
    }

    companion object {
        /** 一個即時副本大約 4 個檔，先多訂一點。 */
        private const val LIVE_SESSION_FILES = 8

        /** 收檔保留量的餘裕（另加 [Recorder.finalizeBound]：緩衝中的資料與最後的 meta）；資料寫到上限前就會先停。 */
        private const val LIVE_FINALIZE_RESERVE = 64L shl 10

        /** 手機的資料夾名：只有 ASCII、單一路徑段，id 用雜湊所以原字串再怪也逃不出去。 */
        internal fun folderOf(id: String, name: String): String {
            val hash = SyncCrypto.hex(SyncCrypto.sha256(id.toByteArray(Charsets.UTF_8))).take(12)
            val clean = name.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_.") it else '_' }
                .joinToString("").trim('.', '_').take(48).ifEmpty { "phone" }
            return "${clean}_$hash"
        }
    }
}

/** 即時副本已達位元組上限。 */
/** 寫之前就擋下、一個位元組都沒寫（[rx.session.WriteRejectedException]）：收檔放寬上限後會重送。 */
internal class RecordingLimitException : rx.session.WriteRejectedException("Recording size limit reached")

/**
 * 即時副本的位元組上限：每個 sink 寫入前先扣預算，超過就丟 [RecordingLimitException]。
 * 目錄是新的，計數從零開始；同一段錄製的所有檔共用一個。
 * [finalizeReserve] 留給收檔：平常寫到 `maxBytes - finalizeReserve` 就停，
 * 呼叫 [beginFinalize] 之後關檔的 flush 與最後的 meta 才能用到保留量。
 */
internal class RecordingBudget(private val maxBytes: Long, finalizeReserve: Long = 0L) {
    private var used = 0L
    private var finalizing = false
    private val dataLimit = (maxBytes - finalizeReserve).coerceAtLeast(0)

    val bytes: Long get() = synchronized(this) { used }

    /** 收檔開始：之後的寫入（關檔 flush、meta）可以用保留量。 */
    fun beginFinalize() = synchronized(this) { finalizing = true }

    fun take(count: Long) {
        require(count >= 0) { "Negative write" }
        synchronized(this) {
            val limit = if (finalizing) maxBytes else dataLimit
            if (count > limit - used) throw RecordingLimitException()
            used += count
        }
    }
}

/** 包住一個 sink，寫入前先扣 [budget]；超過的部分一個位元組都不寫。 */
internal class CappedSink(private val inner: RawSink, private val budget: RecordingBudget) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        budget.take(byteCount)
        inner.write(source, byteCount)
    }

    override fun flush() = inner.flush()
    override fun close() = inner.close()
}

/** 即時副本的上限：時間（單調時鐘）到或資料夾超過大小就停；大小每秒最多掃一次。 */
internal class LiveRecordingCap(private val maxMs: Long, private val maxBytes: Long) {
    private var startedMs = -1L
    private var nextScanMs = 0L

    fun start(nowMs: Long) { startedMs = nowMs; nextScanMs = nowMs }

    /** 超過上限回「time limit」或「size limit」，還沒到回 null。 */
    fun exceeded(dir: File, nowMs: Long): String? {
        if (startedMs < 0) return null
        if (nowMs - startedMs >= maxMs) return "time limit"
        if (nowMs < nextScanMs) return null
        nextScanMs = nowMs + 1_000
        var bytes = 0L
        try {
            Files.walk(dir.toPath()).use { stream ->
                stream.forEach { p -> if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(p) }
            }
        } catch (e: Exception) { return "storage check failed" }
        return if (bytes >= maxBytes) "size limit" else null
    }
}

/**
 * 收檔配額：先掃目前用量，再加上還沒寫完的保留量。同一把鎖，兩個連線同時上傳也不會一起超量。
 * 用量每次重掃，使用者自己刪檔也會反映。
 */
internal class RideQuota(
    private val root: () -> File,
    private val limits: RideLimits,
    private val keysOf: (String) -> Set<String> = { setOf(it.substringAfterLast('_')) },
    private val freeBytes: (File) -> Long = { Files.getFileStore(it.toPath()).usableSpace },
) {
    private val lock = Any()
    private var nextId = 1L
    private val tickets = HashMap<Long, Ticket>()
    private val bytesByPhone = HashMap<String, Long>()
    private val filesByPhone = HashMap<String, Int>()
    private var bytesTotal = 0L
    private var filesTotal = 0

    class Ticket internal constructor(val id: Long, val phone: String, val bytes: Long, val files: Int)

    private class Size(val bytes: Long, val files: Int)

    private class Scan(val total: Size, val phones: Map<String, Size>)

    fun reserve(phone: String, dir: File, bytes: Long, files: Int): Ticket = synchronized(lock) {
        require(bytes >= 0 && files >= 0)
        val key = phone.substringAfterLast('_')
        val scan = scan(root())
        val mine = scan.phones[key] ?: Size(0, 0)
        val reservedHere = bytesByPhone[key] ?: 0L
        val filesHere = filesByPhone[key] ?: 0
        if (over(limits.phoneFiles.toLong(), mine.files.toLong(), filesHere.toLong(), files.toLong())) throw IllegalStateException("Too many recordings on this computer (limit ${limits.phoneFiles} per phone)")
        if (over(limits.totalFiles.toLong(), scan.total.files.toLong(), filesTotal.toLong(), files.toLong())) throw IllegalStateException("Too many recordings on this computer (limit ${limits.totalFiles} total)")
        if (over(limits.phoneBytes, mine.bytes, reservedHere, bytes)) throw IllegalStateException("Not enough room for this ride (limit ${human(limits.phoneBytes)} per phone)")
        if (over(limits.totalBytes, scan.total.bytes, bytesTotal, bytes)) throw IllegalStateException("Not enough room for this ride (limit ${human(limits.totalBytes)} total)")
        if (over(freeBytes(dir), bytesTotal, bytes, limits.freeBeforeUpload)) throw IllegalStateException("Not enough free disk space for this ride")
        val t = Ticket(nextId++, key, bytes, files)
        tickets[t.id] = t
        bytesByPhone[key] = reservedHere + bytes
        filesByPhone[key] = filesHere + files
        bytesTotal += bytes
        filesTotal += files
        t
    }

    fun release(ticket: Ticket?) {
        if (ticket == null) return
        synchronized(lock) {
            if (tickets.remove(ticket.id) == null) return
            bytesByPhone[ticket.phone] = ((bytesByPhone[ticket.phone] ?: 0) - ticket.bytes).coerceAtLeast(0)
            filesByPhone[ticket.phone] = ((filesByPhone[ticket.phone] ?: 0) - ticket.files).coerceAtLeast(0)
            bytesTotal = (bytesTotal - ticket.bytes).coerceAtLeast(0)
            filesTotal = (filesTotal - ticket.files).coerceAtLeast(0)
        }
    }

    private fun scan(root: File): Scan {
        var totalBytes = 0L
        var totalFiles = 0
        val per = HashMap<String, Size>()
        val dirs = root.listFiles { f -> Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS) } ?: throw IOException("Cannot inspect recording storage")
        for (d in dirs) {
            var bytes = 0L
            var files = 0
            Files.walk(d.toPath()).use { stream ->
                val it = stream.filter { p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) }.iterator()
                while (it.hasNext()) {
                    val path = it.next()
                    val size = try { Files.size(path) } catch (_: java.nio.file.NoSuchFileException) { continue }
                    bytes = Math.addExact(bytes, size)
                    files = Math.addExact(files, 1)
                }
            }
            for (key in keysOf(d.name)) {
                val old = per[key] ?: Size(0, 0)
                per[key] = Size(Math.addExact(old.bytes, bytes), Math.addExact(old.files, files))
            }
            totalBytes = Math.addExact(totalBytes, bytes)
            totalFiles = Math.addExact(totalFiles, files)
        }
        return Scan(Size(totalBytes, totalFiles), per)
    }

    private fun over(limit: Long, vararg parts: Long): Boolean {
        var left = limit
        for (part in parts) {
            if (part < 0 || part > left) return true
            left -= part
        }
        return false
    }

    private fun human(bytes: Long): String =
        if (bytes >= 1L shl 30) "${bytes / (1L shl 30)} GiB" else "${bytes / (1L shl 20)} MiB"
}
