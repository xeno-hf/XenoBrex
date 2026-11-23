rootProject.name = "xenobrex"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":xb-tune", ":xb-crypto", ":xb-container", ":xb-link", ":xb-sync", ":xb-cli", ":xb-app")

// 協定層、即時通道定義與同步協定：跟 ReX 共用同一份原始碼（ReX 的 GPL-3.0 原始碼）。
include(":rx-protocol")
include(":rx-defs")
include(":rx-session")
