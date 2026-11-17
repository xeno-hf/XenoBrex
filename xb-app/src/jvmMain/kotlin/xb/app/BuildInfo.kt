package xb.app

import java.util.Properties

/** 這份程式的版本與出處。版本由建置時寫進來的小檔案提供（見 `xb-app/build.gradle.kts`）。 */
object BuildInfo {
    const val SOURCE_URL = "https://github.com/xeno-hf/XenoBrex"

    val version: String by lazy {
        runCatching {
            BuildInfo::class.java.getResourceAsStream("/xb-version.properties")?.use { s ->
                Properties().apply { load(s) }.getProperty("version")
            }
        }.getOrNull() ?: "dev"
    }
}
