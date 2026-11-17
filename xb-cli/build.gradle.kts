// 打包時跟桌面版裝在一起（xb-app 的 --add-launcher）。
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        mainRun { mainClass.set("xb.cli.MainKt") }
    }
    sourceSets {
        jvmMain { resources.srcDir(rootProject.file("skills")) }
        jvmMain.dependencies {
            implementation(project(":xb-container"))
            implementation(project(":xb-link"))
            implementation(project(":xb-sync"))
        }
    }
}

// 開發用腳本 ./xb 的 classpath：jar 加上全部執行期依賴（rx-session、kotlinx-io…），一行寫進 build/dev.classpath
val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
tasks.register("devClasspath") {
    dependsOn("jvmJar")
    val out = layout.buildDirectory.file("dev.classpath")
    val jar = tasks.named("jvmJar").map { (it as Jar).archiveFile.get().asFile }
    val runtime = jvmMainCompilation.runtimeDependencyFiles
    outputs.file(out)
    doLast { out.get().asFile.writeText((listOf(jar.get()) + runtime!!.files).joinToString(File.pathSeparator) { it.absolutePath }) }
}
