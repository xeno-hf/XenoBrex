package xb.sync

import rx.session.sync.SyncCrypto
import rx.session.sync.SyncWire
import xb.container.SafeFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * 同步的設定與配對紀錄（設定資料夾/sync/）。
 * - `identity.properties`：這台電腦的 id（第一次用時亂數產生）與給手機看的名稱。
 * - `phones.properties`：配對過的手機，一行一台：`<id>=<名稱>|<token hex>|<配對時間 ms>`。token 是秘密。
 * 兩個檔案都只給自己讀寫：資料夾 0700、檔案 0600（Windows 用擁有者 ACL）；寫入一律先寫同資料夾的
 * 唯一暫存檔再原子換掉，目標是符號連結就拒絕。
 */
class SyncStore(val dir: File) {
    private val identityFile = File(dir, "identity.properties")
    private val phonesFile = File(dir, "phones.properties")

    /** 手機被移掉時通知（例：關掉它的連線）；在鎖外呼叫。 */
    @Volatile var onForget: ((String) -> Unit)? = null

    init { SafeFiles.ensurePrivateDirectory(dir.toPath()) }

    @Synchronized
    fun identity(): SyncWire.Identity {
        val p = load(identityFile)
        val id = p.getProperty("id") ?: SyncWire.newId().also { p["id"] = it; save(identityFile, p) }
        val name = p.getProperty("name")?.takeIf { it.isNotBlank() } ?: defaultName()
        return SyncWire.Identity(id, name)
    }

    @Synchronized
    fun rename(name: String) {
        val p = load(identityFile)
        identity().let { p["id"] = it.id }
        p["name"] = name.trim().take(64)
        save(identityFile, p)
    }

    @Synchronized
    fun phones(): List<SyncWire.Peer> = load(phonesFile).entries.mapNotNull { (k, v) ->
        val parts = v.toString().split('|')
        if (parts.size < 3) return@mapNotNull null
        runCatching { SyncWire.Peer(k.toString(), parts[0], SyncCrypto.unhex(parts[1]), parts[2].toLong()) }.getOrNull()
    }.sortedBy { it.pairedAt }

    fun phone(id: String): SyncWire.Peer? = phones().firstOrNull { it.id == id }

    fun addPhone(peer: SyncWire.Peer) = locked {
        val p = load(phonesFile)
        p[peer.id] = "${peer.name.replace('|', '/')}|${SyncCrypto.hex(peer.token)}|${peer.pairedAt}"
        save(phonesFile, p)
    }

    fun forget(id: String): Boolean {
        val removed = locked {
            val p = load(phonesFile)
            if (p.remove(id) == null) false else { save(phonesFile, p); true }
        }
        if (removed) runCatching { onForget?.invoke(id) }
        return removed
    }

    /**
     * 配對紀錄的跨行程鎖：`forget` 和上傳定案都排在這把鎖上，另一支程式（例如 CLI 的 forget）也一樣。
     * 鎖檔放在 0700 的資料夾裡；行程結束時由系統自動放掉。
     */
    internal fun <T> locked(block: () -> T): T = synchronized(monitorFor(dir)) {
        val channel = FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val lock = channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }

    /** 這台手機現在的配對還是同一組 token 嗎（重新配對會換 token，舊連線就不算）。 */
    fun stillAuthorized(peer: SyncWire.Peer): Boolean = phone(peer.id)?.token?.contentEquals(peer.token) == true

    /** 讀限定大小；檔案被換成超大檔或不存在時不讓它撐爆記憶體。 */
    private fun load(f: File): Properties {
        val p = Properties()
        if (!f.isFile) return p
        val bytes = Files.newInputStream(f.toPath()).use { SafeFiles.boundedRead(it, MAX_BYTES) }
        p.load(ByteArrayInputStream(bytes))
        return p
    }

    private fun save(f: File, p: Properties) {
        SafeFiles.atomicWrite(f.toPath(), secret = true) { out -> p.store(out, "XenoBrex sync") }
    }

    companion object {
        private const val MAX_BYTES = 4L shl 20
        private const val LOCK_FILE = "store.lock"

        /** 同一個 JVM 裡兩個 store 指向同一個資料夾時先靠這個排隊，才不會拿到重疊的檔案鎖。 */
        private val monitors = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun monitorFor(dir: File): Any = monitors.computeIfAbsent(dir.canonicalPath) { Any() }

        fun defaultName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "XenoBrex"
    }
}
