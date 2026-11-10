plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

// 许可只从源码公开声明生成，不读参考工作区、身份或签名输入。
val prepareGalaxyLicenses by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/galaxy-license-assets/third-party"))
    from(rootProject.file("docs/THIRD_PARTY_NOTICES.md")) { rename { "NOTICE.md" } }
    from(rootProject.file("docs/licenses")) {
        include("**/*.txt")
        into("licenses")
        eachFile { relativePath = RelativePath(true, "licenses", name) }
        includeEmptyDirs = false
    }
    from(rootProject.file("LICENSE")) { into("licenses"); rename { "GPL-3.0.txt" } }
    from(rootProject.file("shared/src/main/java/org/concentus/LICENSE")) {
        into("licenses"); rename { "Concentus-BSD.txt" }
    }
    from(rootProject.file("shared/src/main/jni/speexdsp/COPYING")) {
        into("licenses"); rename { "SpeexDSP-BSD.txt" }
    }
    from(rootProject.file("shared/src/main/assets/byd-hud-icons/LICENSE-BYDMate.txt")) { into("licenses") }
}

// Deployment endpoint stays outside version control; public builds use a reserved example host.
val privateServer = java.util.Properties().apply {
    rootProject.file(".private/server.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val bridgeOrigin = providers.environmentVariable("GALAXY_ORIGIN")
    .orElse(privateServer.getProperty("bridge.origin", "https://bridge.example.invalid/galaxy")).get()
require(bridgeOrigin.startsWith("https://") && !bridgeOrigin.contains('"') && !bridgeOrigin.contains('\\') && !bridgeOrigin.contains('\n') && !bridgeOrigin.contains('\r'))

android {
    buildFeatures { buildConfig = true }
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        buildConfigField("String", "GALAXY_ORIGIN", "\"$bridgeOrigin\"")
        // 核心宿主保留上游兼容范围；本产品APK最低系统由galaxy/mobile单独限定为29。
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // 学习旧GalaxyPlay的产品组织：核心仍在common，产品适配只放galaxy/common。
    sourceSets {
        getByName("main").kotlin.directories.add(rootProject.file("galaxy/common/src/main/java").path)
        getByName("main").java.directories.add(rootProject.file("galaxy/common/src/main/java").path)
        getByName("main").assets.directories.add(rootProject.file("galaxy/common/src/main/assets").path)
        getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/galaxy-license-assets").get().asFile.path)
        listOf("debug", "release").forEach { variant ->
            getByName(variant).apply {
                res.directories.add(rootProject.file("galaxy/common/src/main/res").path)
                manifest.srcFile(rootProject.file("galaxy/common/src/main/AndroidManifest.xml"))
            }
        }
        getByName("test").kotlin.directories.add(rootProject.file("galaxy/common/src/test/java").path)
        getByName("test").java.directories.add(rootProject.file("galaxy/common/src/test/java").path)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes. 1g sat right at the
        // ceiling: adding one more Robolectric class pushed a full run into OutOfMemoryError in
        // unrelated tests, on CI and locally both, which is what BydSettingsDecoyTest measured.
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

tasks.named("preBuild") { dependsOn(prepareGalaxyLicenses) }

dependencies {
    implementation(project(":auto-client"))
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.zxing.core)
    // 仅动态构造车机已安装SDK的抽象回调，不再分发OEM二进制。
    implementation("com.linkedin.dexmaker:dexmaker:2.28.6")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
