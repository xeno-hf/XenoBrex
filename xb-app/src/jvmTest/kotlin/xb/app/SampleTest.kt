package xb.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import xb.container.DefinitionPack
import xb.container.TuneContainer
import xb.container.TuneIo
import xb.tune.TuneCompare
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 用 `sample/` 裡自己編的示範檔測整條路：不需要金鑰，也不需要任何實際的定義檔或調校檔，
 * 所以在任何機器上都會跑。
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SampleTest {
    private val sample = File(System.getProperty("xb.sample")!!)
    private val defs = File(sample, "definitions")
    private val work = File(System.getProperty("xb.shots")!!, "sample")

    private class Picker(var next: File? = null) : FilePicker {
        override fun openTune(startDir: String?) = next
        override fun saveTune(startDir: String?, suggestedName: String) = next
        override fun chooseFolder(startDir: String?) = next
        override fun openPackage(startDir: String?) = next
    }

    private fun fresh(name: String): Pair<AppState, File> {
        val dir = File(work, name).also { it.deleteRecursively(); it.mkdirs() }
        val file = File(sample, "Demo.amx").copyTo(File(dir, "Demo.amx"))
        // 金鑰檔指到不存在的位置：確認沒有金鑰也能用
        return AppState(Settings(File(dir, "settings.properties")), defs, File(dir, "no-keys.properties")) to file
    }

    @Test
    fun opensEditsAndSavesWithoutKeys() {
        val (state, file) = fresh("edit")
        val original = file.readBytes()
        assertEquals(null, state.keys)
        runComposeUiTest {
            setContent { App(state, Picker(file)) }
            onNodeWithTag("openBig").performClick()
            assertEquals(null, state.keyPrompt)
            assertEquals("Demo.amx", state.session?.file?.name)
            assertFalse(state.session!!.encrypted)
            // 預設是功能清單；示範檔的表 3 不在 XTune 選單裡，切到全部表
            onNodeWithTag("featureList").assertExists()
            onNodeWithTag("modeTables").performClick()
            onNodeWithTag("table:3").performClick()
            onNodeWithTag("tableTitle").assertTextContains("示範目標空燃比")
            // 點左上角那一格，打 13.5、Enter
            onNodeWithTag("gridBody").performMouseInput { click(Offset(35f, 14f)) }
            onNodeWithTag("grid").performKeyInput { pressKey(Key.One); pressKey(Key.Three); pressKey(Key.Period); pressKey(Key.Five); pressKey(Key.Enter) }
            assertEquals(135L, state.tune!!.rows(3)!![0][0])
            assertTrue(state.hasChanges)
            onNodeWithTag("save").performClick()
            assertFalse(state.hasChanges)
        }
        // 存出來仍然是沒加密的 XML；和原檔只差那一格；原檔留了 .bak
        val saved = file.readBytes()
        assertFalse(TuneContainer.looksEncrypted(saved))
        assertTrue(File(file.path + ".bak").readBytes().contentEquals(original))
        val pack = DefinitionPack(defs, null as TuneContainer?)
        val diffs = TuneCompare.compare(TuneIo.open(File(file.path + ".bak"), pack, null).tune, TuneIo.open(file, pack, null).tune)
        assertEquals(listOf(3), diffs.map { it.absId })
        assertEquals(1, diffs[0].cells.size)
    }

    @Test
    fun unchangedSaveIsByteIdentical() {
        val (_, file) = fresh("identical")
        val pack = DefinitionPack(defs, null as TuneContainer?)
        val opened = TuneIo.open(file, pack, null)
        assertTrue(TuneIo.content(opened.tune, false, null, null).contentEquals(file.readBytes()))
        val report = opened.tune.check()
        assertEquals(0, report.cellsOutOfRange)
        assertEquals(0, report.cellsNotReversible)
    }

    /** 字標旁那顆水晶按一下換一套配色，繞一圈回到原來的；字標按下去是「關於」。 */
    @Test
    fun crystalCyclesThemesAndLogoOpensAbout() {
        val (state, file) = fresh("about")
        runComposeUiTest {
            setContent { App(state, Picker(file)) }
            assertEquals(ThemeChoice.EMERALD, state.theme)
            for (expected in listOf(ThemeChoice.RUBY, ThemeChoice.TOPAZ, ThemeChoice.SYSTEM, ThemeChoice.EMERALD)) {
                onNodeWithTag("themeNext").performClick()
                assertEquals(expected, state.theme)
            }
            onNodeWithTag("themeNext").performClick()
            assertEquals("RUBY", Settings(File(File(work, "about"), "settings.properties")).theme)

            assertFalse(state.about)
            onNodeWithTag("logo").performClick()
            assertTrue(state.about)
            onNodeWithTag("aboutVersion").assertTextContains(BuildInfo.version)
            onNodeWithTag("aboutClose").performClick()
            assertFalse(state.about)
        }
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(BuildInfo.version), BuildInfo.version)
    }

    private fun shot(state: AppState, name: String, width: Int = 1280, height: Int = 800) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { App(state, Picker()) }
        var img = scene.render(0)
        for (i in 1..4) img = scene.render(i * 50_000_000L)
        val out = File(System.getProperty("xb.shots")!!, "$name.png")
        out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 20_000)
        println("  shot ${out.path}")
    }

    /** 「關於」視窗、窄視窗、四種配色選擇各畫一張。 */
    @Test
    fun rendersAboutAndNarrowWindow() {
        val (state, file) = fresh("shot2")
        assertTrue(state.open(file))
        state.select(1)
        shot(state, "sample_narrow_1000", 1000, 640)
        shot(state, "sample_narrow_900", 900, 600)
        for (c in ThemeChoice.entries) { state.pickTheme(c); shot(state, "sample_bar_${c.name.lowercase()}", 1100, 260) }
        state.pickTheme(ThemeChoice.EMERALD)
        state.about = true
        shot(state, "sample_about")
    }

    /** 把示範檔開起來的畫面畫成圖檔。每個系統各畫一次，可以看字型和排版有沒有走樣。 */
    @Test
    fun rendersSample() {
        val (state, file) = fresh("shot")
        assertTrue(state.open(file))
        state.select(1)
        state.selection = Selection(SelTarget.Body, 2, 2, 4, 6)
        val scene = ImageComposeScene(width = 1280, height = 800, density = Density(1f)) { App(state, Picker()) }
        var img = scene.render(0)
        for (i in 1..4) img = scene.render(i * 50_000_000L)
        val out = File(System.getProperty("xb.shots")!!, "sample_${System.getProperty("os.name").lowercase().substringBefore(' ')}.png")
        out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 20_000)
        println("  shot ${out.path}")
    }
}
