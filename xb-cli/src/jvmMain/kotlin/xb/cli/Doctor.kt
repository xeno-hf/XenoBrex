package xb.cli

import xb.container.KeyStore
import xb.container.XbPaths
import java.io.File
import java.util.Properties

internal fun version(): String {
    val p = Properties()
    Thread.currentThread().contextClassLoader.getResourceAsStream("xb-version.properties")?.use(p::load)
    return p.getProperty("version") ?: "dev"
}

/** 環境檢查。只說有沒有金鑰，不讀內容。 */
internal fun doctor() {
    println("XenoBrex ${version()} (xbrex)")
    println("System       ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}")
    val cfg = XbPaths.configDir()
    println("Settings     ${cfg.path}${if (cfg.isDirectory) "" else " (not created yet)"}")
    val defs = XbPaths.definitionsDir()
    val src = when {
        System.getenv("XB_DEFINITIONS") != null -> "environment variable XB_DEFINITIONS"
        XbPaths.settingsFile().isFile && Properties().also { p -> runCatching { XbPaths.settingsFile().inputStream().use(p::load) } }.getProperty("definitionsDir") != null -> "desktop app settings"
        else -> "default"
    }
    println("Definitions  ${defs.path} ($src)")
    if (!defs.isDirectory) println("             x folder does not exist: no tune can be opened")
    else {
        val names = defs.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        val control = names.filter { it.startsWith("ControlFile_") }.map { it.removePrefix("ControlFile_").substringBeforeLast('.') }
        println("             ControlFile: ${control.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        println("             ScaleData: ${if (names.any { it.startsWith("ScaleData") }) "present" else "x missing (no tune can be opened)"}")
        println("             Table names: ${if (names.any { it.contains("TabShortName") }) "present" else "missing (only internal names are shown)"}")
    }
    val keyFile = KeyStore.defaultFile()
    val keys = runCatching { KeyStore.load() }.getOrNull()
    println("Keys         ${if (keys != null) "present" else "missing (encrypted files cannot be opened)"} (${keyFile.path}${if (System.getenv("XB_KEYS") != null) ", from XB_KEYS" else ""})")
    val log = XbPaths.logFile()
    println("Log          ${log.path}${if (log.isFile) " (${log.length() / 1024} KB)" else " (none yet)"}")
    val bk = XbPaths.backupDir().listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: emptyList()
    println("ECU backups  ${XbPaths.backupDir().path}: ${bk.size}" + (bk.lastOrNull()?.let { ", latest ${it.name}" } ?: ""))
    System.getenv("XB_ECU_PASSWORD")?.let { println("ECU password set by XB_ECU_PASSWORD") }
}

/** 定義檔資料夾能不能用：要有 ControlFile_… 與 ScaleData（同桌面版）。 */
private fun usableDefinitions(dir: File): Boolean {
    val names = dir.listFiles()?.map { it.name } ?: return false
    return names.any { it.startsWith("ControlFile_") } && names.any { it.startsWith("ScaleData") }
}

/**
 * `xbrex import`：跟桌面版「從 XTune 安裝檔匯入」一樣。金鑰一定存；定義檔只在目前沒有可用的定義檔資料夾、
 * 或目前用的就是預設（匯入用）的資料夾時才寫進去，並把設定指到那裡；不動使用者自己指定的資料夾。
 */
internal fun importXTune(args: List<String>) {
    val src = File(args.getOrNull(0) ?: fail("Usage: xbrex import <XTune package (.xapk/.apks/.apk) or a folder holding it>"))
    if (!src.exists()) fail("Not found: ${src.path}")
    val r = try { xb.container.XTunePackage.read(src) } catch (e: xb.container.XTuneImportException) { fail(e.message ?: "Import failed") }
    try { KeyStore.save(r.keys) } catch (e: Exception) { fail("Cannot save the keys: ${e.message}") }
    println("Keys read from the XTune package and saved to ${KeyStore.defaultFile().path}")
    val current = XbPaths.definitionsDir()
    val importDir = XbPaths.defaultDefinitionsDir()
    if (usableDefinitions(current) && current.absoluteFile != importDir.absoluteFile) {
        println("Definitions not written: you use your own definitions folder (${current.path}); it was left as is.")
        return
    }
    val n = try { xb.container.XTunePackage.installDefinitions(r, importDir) } catch (e: Exception) { fail("Cannot write definitions to ${importDir.path}: ${e.message}") }
    if (!usableDefinitions(importDir)) fail("The package's definitions are incomplete (need ControlFile_... and ScaleData) in ${importDir.path}")
    if (System.getenv("XB_DEFINITIONS") == null) {
        val p = Properties()
        val f = XbPaths.settingsFile()
        runCatching { f.inputStream().use(p::load) }
        p.setProperty("definitionsDir", importDir.path)
        f.parentFile?.mkdirs()
        f.outputStream().use { p.store(it, "XenoBrex") }
    }
    println("Wrote $n definition files to ${importDir.path}" + if (System.getenv("XB_DEFINITIONS") == null) " and set it as the definitions folder" else "")
}

internal fun log(lines: Int) {
    val f = XbPaths.logFile()
    if (!f.isFile) { println("No log yet (${f.path})"); return }
    val all = f.readLines()
    all.takeLast(lines).forEach(::println)
    if (all.size > lines) System.err.println("(${all.size} lines in total, showing the last $lines; ${f.path})")
}

internal fun skill(args: List<String>) {
    val text = Thread.currentThread().contextClassLoader.getResourceAsStream("xenobrex-debug/SKILL.md")?.readBytes()?.decodeToString()
        ?: fail("This xbrex build does not include SKILL.md")
    val i = args.indexOf("--install")
    if (i < 0) { print(text); return }
    val dir = File(args.getOrNull(i + 1) ?: fail("--install needs a folder, e.g. ~/.claude/skills"), "xenobrex-debug")
    dir.mkdirs()
    File(dir, "SKILL.md").writeText(text)
    println("Saved to ${File(dir, "SKILL.md").path}")
}
