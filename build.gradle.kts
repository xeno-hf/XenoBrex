plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

// 跟另一個專案（ReX／XenoBrex）共用原始碼的模組：建置輸出放在這個專案自己的 build/ 底下，兩邊同時建置不會互踩
subprojects {
    if (!projectDir.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath()))
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("shared/$name"))
}

// rx-protocol（ReX 的協定層）：純 Kotlin、零依賴。它的建置檔沿用 ReX 的寫法，外掛由根目錄套上。
// 它的測試在 ReX 那邊跑，XB 裡不跑。
project(":rx-protocol") {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        iosArm64()
        applyDefaultHierarchyTemplate()
    }
    tasks.matching { it.name.endsWith("Test") || it.name.startsWith("compileTestKotlin") }.configureEach { enabled = false }
}

// rx-defs（ReX 的即時通道名錄）：同上，只編 commonMain。
project(":rx-defs") {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        iosArm64()
        applyDefaultHierarchyTemplate()
        sourceSets.getByName("jvmMain").kotlin.setSrcDirs(emptyList<Any>())
    }
    tasks.matching { it.name.endsWith("Test") || it.name.startsWith("compileTestKotlin") }.configureEach { enabled = false }
}

// rx-session（ReX 的連線、錄製與同步協定）：XB 的同步伺服器用。只編 JVM；測試在 ReX 那邊跑。
project(":rx-session") {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        applyDefaultHierarchyTemplate()
    }
    tasks.matching { it.name.endsWith("Test") || it.name.startsWith("compileTestKotlin") }.configureEach { enabled = false }
}
