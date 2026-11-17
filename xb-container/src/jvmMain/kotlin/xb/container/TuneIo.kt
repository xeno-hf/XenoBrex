package xb.container

import xb.tune.Labels
import xb.tune.Platform
import xb.tune.Tune
import xb.tune.TuneFile
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TuneOpenException(message: String) : Exception(message)

/** 開起來的一份調校檔，連同它是怎麼來的。 */
class OpenedTune(
    val tune: Tune,
    val file: File,
    /** 原檔是不是加密的容器；存檔時預設照原樣。 */
    val encrypted: Boolean,
    val platform: Platform,
    val labels: Labels,
    /** 從 .xbx 開的就是那個專案（[file] 是 .xbx 本身）；一般調校檔是 null。 */
    val project: XbxProject? = null,
)

object TuneIo {
    private val STX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** STX 存檔時間的寫法。 */
    fun now(): String = LocalDateTime.now().format(STX_TIME)

    /** 開檔。[container]：使用者的金鑰；檔案或定義檔有加密而沒有金鑰時丟 [KeysRequiredException]。 */
    fun open(file: File, pack: DefinitionPack, container: TuneContainer?): OpenedTune {
        val fileBytes = try { file.inputStream().use { SafeFiles.boundedRead(it, ArchiveSafety.PROJECT.compressed) } } catch (e: Exception) { throw TuneOpenException("Cannot read the file: ${e.message}") }
        val project = if (XbxProject.isXbx(fileBytes)) {
            try { XbxProject.read(fileBytes) } catch (e: XbxException) { throw TuneOpenException(e.message ?: "Not an .xbx project") }
        } else null
        val bytes = project?.tuneBytes ?: fileBytes
        val encrypted = TuneContainer.looksEncrypted(bytes)
        if (encrypted && container == null) throw KeysRequiredException()
        val text = try {
            (if (encrypted) container!!.decrypt(bytes) else bytes).toString(Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (e: Exception) {
            throw TuneOpenException("Cannot decrypt this file; it may not be a tune, or the keys are wrong (UMA M9 is not supported yet)")
        }
        val tuneFile = try { TuneFile.parse(text) } catch (e: Exception) { throw TuneOpenException("Not a tune file: ${e.message}") }
        val platform = Platform.ofMapType(tuneFile.mapType) ?: throw TuneOpenException("Unknown tune type: ${tuneFile.mapType}")
        val scales = pack.scales ?: throw TuneOpenException("No ScaleData in the definitions folder")
        val defs = pack.definitions(platform.definition)
            ?: throw TuneOpenException("No ControlFile_${platform.definition} in the definitions folder (needed for ${platform.display})")
        val attached = if (tuneFile.symgu != null) pack.attachedDefinitions() else null
        val labels = pack.labels(platform.labelSet) + (if (attached != null) pack.attachedLabels() else Labels.EMPTY)
        return OpenedTune(Tune(defs, scales, tuneFile, attached), file, encrypted, platform, labels, project)
    }

    /** 調校檔的副檔名：跟官方一樣，是 MAP_Type 小寫（Mini X＝amx、Super X＝asx、X2 Mini＝amx2…）。 */
    fun extension(tune: Tune): String = XbxProject.extension(tune.file.mapType)

    /**
     * 存成 .xbx：把 [opened] 目前的內容放進 [project]（沒有就新開一個），換成目標的型號副檔名，[entries] 記進修改紀錄。
     * 調校檔照官方的寫法存：[encrypted] 預設照原檔。
     */
    fun saveProject(opened: OpenedTune, target: File, project: XbxProject? = opened.project, entries: List<Map<String, Any?>> = emptyList(),
                    encrypted: Boolean = opened.encrypted, time: String? = now(), container: TuneContainer?): XbxProject {
        val bytes = content(opened.tune, encrypted, time, container)
        val p = project ?: XbxProject("tune." + extension(opened.tune), bytes).also { it.log(mapOf("kind" to "new", "from" to opened.file.name)) }
        p.replaceTune(bytes, opened.tune.file.mapType)
        entries.forEach(p::log)
        p.save(target)
        return p
    }

    /**
     * 存檔。先寫到暫存檔再換過去，寫到一半失敗不會毀掉原檔。
     * [encrypted]：要不要包成加密的容器；預設照原檔。
     */
    fun save(opened: OpenedTune, target: File, encrypted: Boolean = opened.encrypted, time: String? = now(), container: TuneContainer?) {
        val bytes = content(opened.tune, encrypted, time, container)
        SafeFiles.atomicWrite(target.toPath()) { it.write(bytes) }
    }

    fun content(tune: Tune, encrypted: Boolean, time: String?, container: TuneContainer?): ByteArray {
        val xml = tune.toXml(time).toByteArray(Charsets.UTF_8)
        return if (encrypted) (container ?: throw KeysRequiredException()).encrypt(xml) else xml
    }
}
