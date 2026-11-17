// xb-app：桌面版（Compose Multiplatform，JVM）。調校檔編輯器；連上 ECU 可即時調校（xb-link）。
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

val xbVersion: String = providers.gradleProperty("xb.version").get()

// 授權文字：GitHub 工作樹用根目錄 LICENSE；本機原始碼樹退回 oss 的 GPL 全文。
val gplLicense = layout.file(providers.provider {
    listOf(
        rootProject.file("LICENSE"),
        rootProject.file("../../oss/gpl-3.0.txt"),
    ).firstOrNull { it.isFile }
        ?: error("GPL license text not found (LICENSE or ../../oss/gpl-3.0.txt)")
})

// 字型授權一定附上；NOTICE 在公開工作樹才有。
val extraLicenses: List<File> = listOfNotNull(
    rootProject.file("NOTICE.md").takeIf { it.isFile },
    project.file("src/jvmMain/resources/licenses/OFL-Oxanium.txt").also { require(it.isFile) { "Font license text not found" } },
)

// Compose 的 appResourcesRootDir/common 會進到每個安裝檔的程式資源目錄。
val distributionLicenseDir = layout.buildDirectory.dir("generated/distribution-licenses")

val prepareDistributionLicenses by tasks.registering(Sync::class) {
    into(distributionLicenseDir)
    from(gplLicense) {
        into("common")
        rename { "LICENSE" }
    }
    from(extraLicenses) {
        into("common")
    }
}

// 把版本寫成一個小檔案放進程式裡，「關於」視窗讀它。
val versionResource by tasks.registering {
    val out = layout.buildDirectory.dir("generated/xbVersion")
    val version = xbVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        out.get().file("xb-version.properties").asFile.apply { parentFile.mkdirs(); writeText("version=$version\n") }
    }
}

kotlin {
    jvm { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }

    sourceSets {
        jvmMain {
            resources.srcDir(versionResource)
            // 功能專頁的畫面跟 ReX 共用一份原始碼（xb-calui；各自用自己的 Compose 版本編）
            kotlin.srcDir(rootDir.resolve("xb-calui/src/commonMain/kotlin"))
        }
        jvmMain.dependencies {
            implementation(project(":xb-container"))
            implementation(project(":xb-link"))
            implementation(project(":xb-sync"))
            // xbrex（命令列）跟桌面版裝在一起，見下面的 --add-launcher
            implementation(project(":xb-cli"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

compose.desktop {
    application {
        mainClass = "xb.app.MainKt"
        nativeDistributions {
            // Linux 不出 .deb：用 packageTarGz 打成壓縮檔，或直接從原始碼跑 install.sh（見下面與 README）。
            targetFormats(TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "XenoBrex"
            packageVersion = xbVersion
            description = "aRacer ECU tune file editor"
            vendor = "XenoBrex"
            // MSI／DMG 的授權頁；AppImage 沒有授權頁，靠 appResourcesRootDir 一併帶上 LICENSE。
            licenseFile.set(gplLicense)
            appResourcesRootDir.set(distributionLicenseDir)
            linux { iconFile.set(project.file("icons/xenobrex.png")) }
            macOS {
                iconFile.set(project.file("icons/xenobrex.icns"))
                bundleID = "io.github.xeno_hf.xenobrex"
                // macOS 的安裝檔規定版本的第一段不能是 0，所以 0.x.y 在這裡寫成 1.x.y；
                // 檔名和「關於」視窗仍然用真正的版本（GitHub 上打包完會改檔名）。
                packageVersion = if (xbVersion.startsWith("0.")) "1." + xbVersion.substring(2) else xbVersion
            }
            windows {
                iconFile.set(project.file("icons/xenobrex.ico"))
                menuGroup = "XenoBrex"
                menu = true
                shortcut = true
                dirChooser = true
                // 裝在使用者自己的資料夾，不需要系統管理員權限。
                perUserInstall = true
                // 固定不變：Windows 靠它認出「這是同一個程式的新版」，升級時才會蓋掉舊版。
                upgradeUuid = "6f0e6b0e-6a54-4d3f-9c0e-2b7f5e0a9d41"
            }
        }
    }
}

// 第二個執行檔 xbrex：同一份程式、同一個 Java，主程式是命令列（xb-cli）。
// Windows 上要開主控台視窗才看得到輸出。
val xbrexLauncher by tasks.registering {
    val out = layout.buildDirectory.file("generated/xbrex/xbrex.properties")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText("main-class=xb.cli.MainKt\nwin-console=true\n") }
    }
}

// Windows 安裝程式多一頁，讓使用者勾選要不要建桌面捷徑和開始功能表捷徑。
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (name == "packageMsi") freeArgs.add("--win-shortcut-prompt")
    dependsOn(xbrexLauncher)
    freeArgs.addAll("--add-launcher", "xbrex=" + xbrexLauncher.get().outputs.files.singleFile.absolutePath)
}

// 先把授權檔產生好，prepareAppResources 才複製得到。
tasks.matching { it.name.startsWith("prepare") && it.name.endsWith("AppResources") }.configureEach {
    dependsOn(prepareDistributionLicenses)
}

// Linux：把打包好的程式資料夾加上安裝腳本，壓成一個 tar.gz。
// 解開後跑裡面的 install.sh 就會裝到 ~/.local（不需要 root）。
tasks.register<Tar>("packageTarGz") {
    dependsOn("createDistributable")
    val top = "XenoBrex-$xbVersion-linux-x64"
    archiveFileName.set("$top.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    compression = Compression.GZIP
    into(top) {
        from(layout.buildDirectory.dir("compose/binaries/main/app")) { include("XenoBrex/**") }
        from(rootProject.file("install.sh"))
        // 壓縮檔根目錄（install.sh 旁邊）也放一份授權。
        from(gplLicense) { rename { "LICENSE" } }
        from(extraLicenses)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("xb.definitions", System.getenv("XB_DEFINITIONS") ?: File(System.getProperty("user.home"), ".local/share/xenobrex/definitions").absolutePath)
    systemProperty("xb.shots", layout.buildDirectory.dir("shots").get().asFile.absolutePath)
    systemProperty("xb.sample", File(rootDir, "sample").absolutePath)
    systemProperty("xb.xtune", System.getenv("XB_XTUNE") ?: "")
    systemProperty("java.awt.headless", "true")
    // 畫面測試比對的是繁中字樣：測試時把「跟隨系統」固定成繁中（語言測試自己切換）
    jvmArgs("-Duser.language=zh", "-Duser.country=TW")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
