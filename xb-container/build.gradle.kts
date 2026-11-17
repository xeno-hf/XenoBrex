// xb-container：檔案層，只有 JVM。容器加解密（Base64＋AES-CBC）、定義檔資料夾、開檔與存檔。
// 不帶金鑰：金鑰由使用者自己提供，存在使用者的設定資料夾（見 KeyStore）。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }

    sourceSets {
        jvmMain.dependencies { api(project(":xb-tune")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("xb.xtune", System.getenv("XB_XTUNE") ?: "")
    systemProperty("xb.definitions", System.getenv("XB_DEFINITIONS") ?: File(System.getProperty("user.home"), ".local/share/xenobrex/definitions").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
