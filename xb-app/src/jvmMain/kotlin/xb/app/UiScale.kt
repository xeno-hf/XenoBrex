package xb.app

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.util.concurrent.TimeUnit

/**
 * 介面要再放大幾倍，才會和系統的顯示縮放一致。
 * Windows 與 macOS 上 Java 自己會跟著系統縮放，這裡回 1。
 * Linux 上（X11／XWayland）Java 只認整數倍：系統設 165% 時它當成 100%，畫面就很小。
 * 所以自己讀系統要的 DPI，除以 Java 已經做的倍數，把差的部分補上。
 */
object UiScale {
    /** 低於這個差距就當作不用補，避免 1.02 這種倍數讓字變糊。 */
    private const val IGNORE_BELOW = 0.05

    fun systemFactor(): Float {
        // 自動判斷不對時的手動指定：環境變數 XB_UI_SCALE=1.5
        System.getenv("XB_UI_SCALE")?.toFloatOrNull()?.takeIf { it in 0.5f..4f }?.let { return it }
        if (!System.getProperty("os.name").lowercase().contains("linux")) return 1f
        if (GraphicsEnvironment.isHeadless()) return 1f
        return runCatching {
            val tk = Toolkit.getDefaultToolkit()
            fun prop(name: String): Int? = (tk.getDesktopProperty(name) as? Number)?.toInt()
            val awt = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
            factor(prop("gnome.Xft/DPI"), prop("gnome.Gdk/UnscaledDPI"), prop("gnome.Gdk/WindowScalingFactor"), xrdbDpi(), awt)
        }.getOrDefault(1f)
    }

    /**
     * 純計算，方便測試。DPI 的來源依序：
     * XSETTINGS 的 `Xft/DPI`（單位 1/1024）、`Gdk/UnscaledDPI`×`Gdk/WindowScalingFactor`（KDE 只給這組）、X 資源的 `Xft.dpi`。
     */
    fun factor(xftDpi1024: Int?, unscaledDpi1024: Int?, windowScale: Int?, xrdbDpi: Double?, awtScale: Double): Float {
        val dpi = when {
            xftDpi1024 != null && xftDpi1024 > 0 -> xftDpi1024 / 1024.0
            unscaledDpi1024 != null && unscaledDpi1024 > 0 -> unscaledDpi1024 / 1024.0 * (windowScale?.takeIf { it >= 1 } ?: 1)
            xrdbDpi != null && xrdbDpi > 0 -> xrdbDpi
            else -> return 1f
        }
        val wanted = dpi / 96.0
        val f = wanted / (if (awtScale >= 1.0) awtScale else 1.0)
        if (f < 1.0 + IGNORE_BELOW) return 1f
        return f.coerceAtMost(4.0).toFloat()
    }

    private fun xrdbDpi(): Double? = runCatching {
        val p = ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        Regex("""Xft\.dpi:\s*([0-9.]+)""").find(out)?.groupValues?.get(1)?.toDouble()
    }.getOrNull()
}
