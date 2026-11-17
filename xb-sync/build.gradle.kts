// xb-sync：讓 ReX 連進來的同步伺服器。配對、收即時資料（準星可以用手機的串流）、收錄製（.xbl）、送油圖定義包。
// 協定在 ReX 的 rx-session（rx.session.sync）；這裡是 XB 這一端：存配對、存檔、從定義檔做定義包。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        jvmMain.dependencies {
            api(project(":rx-session"))
            implementation(project(":xb-container"))
        }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("xb.definitions", System.getenv("XB_DEFINITIONS") ?: File(System.getProperty("user.home"), ".local/share/xenobrex/definitions").absolutePath)
}
