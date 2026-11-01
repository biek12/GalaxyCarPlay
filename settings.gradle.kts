pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeModule("com.github.Flyfish233", "spake2-java") } }
    }
}

// 产品仅构建车机接收端；上游示例与其他入口保留在研究快照，不进入产品依赖图。
rootProject.name = "GalaxyPlay"
include(":common", ":mobile", ":shared")
// mobile仅为Gradle逻辑名，应用目录只有galaxy/mobile，不再保留根mobile壳。
project(":mobile").projectDir = file("galaxy/mobile")
include(":auto-contracts", ":auto-client", ":service", ":cluster")
project(":auto-contracts").projectDir = file("galaxy/contracts")
project(":auto-client").projectDir = file("galaxy/client")
project(":service").projectDir = file("galaxy/service")
project(":cluster").projectDir = file("galaxy/cluster")
