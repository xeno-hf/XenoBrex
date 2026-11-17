package xb.app

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    private val placeholder = Regex("\\{(\\d+)}")

    @Test fun allLanguagesHaveTheSameKeysAndArguments() {
        val en = I18n.table(Lang.EN)
        assertTrue(en.size > 200, "English table looks empty: ${en.size}")
        for (l in Lang.entries) {
            val t = I18n.table(l)
            assertEquals(en.keys.sorted(), t.keys.sorted(), "keys of ${l.tag}")
            for ((k, v) in en) {
                val want = placeholder.findAll(v).map { it.value }.toSet()
                val got = placeholder.findAll(t.getValue(k)).map { it.value }.toSet()
                assertEquals(want, got, "arguments of $k in ${l.tag}")
            }
        }
    }

    @Test fun everyKeyUsedInTheCodeExists() {
        val src = File("src/jvmMain/kotlin/xb/app")
        val used = src.listFiles()!!.filter { it.extension == "kt" }.flatMap { f -> Regex("tr\\(\"([^\"$]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet() +
            ThemeChoice.entries.map { "theme.${it.name}" }
        val missing = used - I18n.table(Lang.EN).keys
        assertTrue(missing.isEmpty(), "keys used but not defined: $missing")
    }

    @Test fun systemLocaleMapsToALanguage() {
        assertEquals(Lang.ZH_HANT, Lang.ofLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(Lang.ZH_HANT, Lang.ofLocale(Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals(Lang.ZH_HANS, Lang.ofLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(Lang.ZH_HANS, Lang.ofLocale(Locale.forLanguageTag("zh-Hans")))
        assertEquals(Lang.JA, Lang.ofLocale(Locale.JAPAN))
        assertEquals(Lang.EN, Lang.ofLocale(Locale.GERMANY))
        assertEquals(Lang.EN, Lang.resolve("en"))
    }

    @Test fun argumentsAndLeadingSpacesSurvive() {
        val before = I18n.lang
        try {
            I18n.lang = Lang.EN
            assertEquals(" · ", tr("sep.parts"))
            assertEquals("Opened a.amx: Mini X, 3 tables", tr("msg.opened", "a.amx", "Mini X", 3))
            I18n.lang = Lang.JA
            assertEquals("a.amx を開きました：Mini X、3 テーブル", tr("msg.opened", "a.amx", "Mini X", 3))
        } finally { I18n.lang = before }
    }
}
