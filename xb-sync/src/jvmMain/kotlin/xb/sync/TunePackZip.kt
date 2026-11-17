package xb.sync

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import xb.container.DefinitionPack
import xb.container.OfficialStock
import xb.container.TuneContainer
import xb.tune.Platform
import xb.tune.TunePack
import xb.tune.TuneFile

/**
 * 做調校包（[TunePack]）給 ReX：用使用者自己的定義檔與金鑰，把這個車種的官方原廠檔當結構範本。
 * 一定要拿得到官方原廠檔（跟 XB 的轉換一樣，沒基準就不做），不拿電腦上隨便一份檔案頂替。
 */
object TunePackZip {
    class NoPack(message: String) : Exception(message)

    fun build(pack: DefinitionPack, container: TuneContainer?, mapType: String, tableId: String, app: String): ByteArray {
        if (tableId.isBlank()) throw NoPack("Phone did not say the ECU's Table ID; connect to the ECU first")
        val base = try { OfficialStock.baseline(mapType.uppercase(), tableId.trim(), pack, container) }
            catch (e: OfficialStock.NoBaselineException) { throw NoPack(e.message ?: "No official stock tune") }
        val platform = base.opened.platform
        val tf = base.opened.tune.file
        val raw = base.file.readBytes()
        // 解密後的原廠範本會原封不動放進調校包，只送給已配對的手機（內容與桌機開檔一致，TunePackTest 驗證）。
        val template = (if (TuneContainer.looksEncrypted(raw)) container!!.decrypt(raw) else raw).toString(Charsets.UTF_8).removePrefix("﻿")
        fun need(name: String) = pack.text(name) ?: throw NoPack("No $name on this computer")
        val files = linkedMapOf(
            TunePack.CONTROL to need("ControlFile_${platform.definition}"),
            TunePack.SCALES to need("ScaleData"),
            TunePack.TEMPLATE to template,
        )
        pack.text("${platform.labelSet}_GroupDes")?.let { files[TunePack.GROUP] = it }
        pack.text("${platform.labelSet}_TabShortName")?.let { files[TunePack.SHORT] = it }
        pack.text("${platform.labelSet}_ParaAndVarDes")?.let { files[TunePack.DESC] = it }
        if (tf.symgu != null) {
            files[TunePack.ATTACHED + TunePack.CONTROL] = need("ControlFile_${Platform.ATTACHED_DEFINITION}")
            val set = Platform.ATTACHED_LABEL_SET
            pack.text("${set}_GroupDes")?.let { files[TunePack.ATTACHED + TunePack.GROUP] = it }
            pack.text("${set}_TabShortName")?.let { files[TunePack.ATTACHED + TunePack.SHORT] = it }
            pack.text("${set}_ParaAndVarDes")?.let { files[TunePack.ATTACHED + TunePack.DESC] = it }
        }
        val manifest = linkedMapOf(
            "format" to TunePack.FORMAT, "app" to app,
            "platform" to platform.definition, "map_type" to tf.mapType, "v" to tf.vCode.toString(), "c" to tf.cCode.toString(),
            "table_id" to tf.tableId.trim(), "vehicle" to base.entry.vehicle, "style" to base.entry.styleEn,
            "stock_file" to base.entry.file, "stock_md5" to base.entry.md5, "list_version" to base.list.version,
            // 定義檔序號：手機開包時跟 control.xml 本身的序號比對，對不上就不用
            "serial" to xb.tune.Definitions.parse(files.getValue(TunePack.CONTROL)).serial,
        )
        files[TunePack.ATTACHED + TunePack.CONTROL]?.let { manifest["attached_serial"] = xb.tune.Definitions.parse(it, TuneFile.SYMGU_ID_OFFSET).serial }
        return zip(mapOf(TunePack.MANIFEST to TunePack.writeManifest(manifest)) + files)
    }

    fun zip(files: Map<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, text) in files) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    /** 解開（測試與 CLI 用；ReX 用自己的 ZIP 讀法）。 */
    fun read(bytes: ByteArray): TunePack {
        val files = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) files[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return TunePack.of(files)
    }
}
