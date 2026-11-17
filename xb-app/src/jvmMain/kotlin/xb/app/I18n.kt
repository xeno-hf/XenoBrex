package xb.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.Properties

/**
 * 桌面版的語言。字串在 `resources/i18n/<tag>.properties`（UTF-8），四種語言同一組 key（測試會核對）。
 * [labelTag]：定義檔名稱檔（TabShortName、GroupDes）用的語言；日文名稱檔沒有，退回英文。
 */
enum class Lang(val tag: String, val native: String, val labelTag: String) {
    EN("en", "English", "en-US"),
    ZH_HANT("zh-Hant", "繁體中文", "zh-TW"),
    ZH_HANS("zh-Hans", "简体中文", "zh-CN"),
    JA("ja", "日本語", "en-US");

    companion object {
        /** 設定裡存的值：語言 tag，或 "system"＝跟系統。 */
        const val SYSTEM = "system"

        fun parse(s: String?): Lang? = entries.firstOrNull { it.tag == s }

        /** 系統語言對到的那一種；對不到就英文。 */
        fun ofLocale(l: Locale): Lang = when (l.language) {
            "zh" -> if (l.script == "Hans" || (l.script.isEmpty() && l.country in setOf("CN", "SG", "MY"))) ZH_HANS else ZH_HANT
            "ja" -> JA
            else -> EN
        }

        fun resolve(setting: String?): Lang = parse(setting) ?: ofLocale(Locale.getDefault())
    }
}

/**
 * 目前的語言與字串表。讀 [lang] 會訂閱 Compose 狀態，換語言時畫面自動重繪。
 * 找不到 key 時用英文，英文也沒有就顯示 key 本身（測試會擋）。
 */
object I18n {
    var lang by mutableStateOf(Lang.resolve(null))

    private val tables = HashMap<Lang, Map<String, String>>()

    fun table(l: Lang): Map<String, String> = tables.getOrPut(l) {
        val props = Properties()
        I18n::class.java.getResourceAsStream("/i18n/${l.tag}.properties")?.use { props.load(it.reader(Charsets.UTF_8)) }
        props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    /** `{0}`、`{1}`… 換成 [args]。不用 MessageFormat：中文裡的單引號不該被當成跳脫字元。 */
    fun t(key: String, vararg args: Any?): String {
        val l = lang
        var s = table(l)[key] ?: table(Lang.EN)[key] ?: key
        for (i in args.indices) s = s.replace("{$i}", args[i].toString())
        return s
    }
}

/** 畫面文字。 */
fun tr(key: String, vararg args: Any?): String = I18n.t(key, *args)
