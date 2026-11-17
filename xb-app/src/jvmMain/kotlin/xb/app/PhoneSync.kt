package xb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import rx.session.sync.SyncWire
import xb.container.AppLog
import xb.container.XbPaths
import xb.sync.SyncStore
import xb.sync.XbSync
import java.io.File

/**
 * 設定頁的「手機同步（ReX）」：開關同步伺服器、配對、配過的手機、收到的錄製。
 * 伺服器本身在 xb-sync（跟 `xbrex sync serve` 同一份）；這裡只管開關與畫面狀態。
 */
class PhoneSync(private val state: AppState, store: SyncStore = SyncStore(XbPaths.syncDir())) {
    val sync = XbSync(store, { state.pack }, { ridesDir }, "XenoBrex ${BuildInfo.version}", { state.keys?.container }) { line ->
        if (state.logToFile) AppLog.write("sync", line)
        state.ecuLog(line)
    }

    var running by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var phones: List<SyncWire.Peer> by mutableStateOf(store.phones())
        private set

    val ridesDir: File get() = state.settings.ridesDir?.let(::File) ?: XbPaths.ridesDir()

    /** 設定裡開著就啟動（程式開啟時呼叫一次）。 */
    fun restore() { if (state.settings.syncOn) start() }

    fun switch(on: Boolean) {
        state.settings.syncOn = on
        if (on) start() else stop()
    }

    private fun start() {
        try {
            sync.start(null, state.settings.syncPort)
            running = true; error = null
        } catch (e: Exception) {
            running = false
            error = tr("sync.err.start", state.settings.syncPort, e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() { sync.stop(); running = false }

    fun pair(): SyncWire.PairingWindow = sync.startPairing()

    fun refresh() { phones = sync.store.phones() }

    fun forget(peer: SyncWire.Peer) { sync.store.forget(peer.id); refresh() }

    companion object {
        /** 這台電腦給手機連的位址（IPv4、略過 loopback），附網卡名稱。 */
        fun localAddresses(): List<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { "${it.hostAddress} (${i.name})" } }
        }.getOrDefault(emptyList())
    }

    /** 這支手機送來的即時資料能不能拿來畫準星：手機用的定義檔要跟開著的調校檔同一個平台。 */
    fun liveFor(definition: String?): Map<Int, Double>? {
        val f = sync.live.value ?: return null
        if (!f.online || definition == null || f.defs != definition) return null
        return f.values
    }
}
