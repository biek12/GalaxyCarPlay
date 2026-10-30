plugins { alias(libs.plugins.android.application) }
val habLibraries = layout.buildDirectory.dir("generated/hab/jniLibs")
val buildHab by tasks.registering(Exec::class) {
    val sdk = providers.environmentVariable("ANDROID_HOME").orElse(rootProject.file(".work/android-sdk").path)
    val ndk = providers.environmentVariable("GALAXY_ANDROID_NDK").orElse(sdk.map { "$it/ndk/28.2.13676358" })
    inputs.dir("src/main/cpp/hab")
    inputs.file(rootProject.file("scripts/build_hab_bridge.py"))
    outputs.dir(habLibraries)
    commandLine("python3", rootProject.file("scripts/build_hab_bridge.py"), "--ndk", ndk.get(),
        "--output", habLibraries.get().file("arm64-v8a/libgalaxy_hab_bridge.so").asFile)
}
val qnxAssets = layout.buildDirectory.dir("generated/qnx/assets")
val prepareQnxReceiver by tasks.registering(Exec::class) {
    inputs.dir("src/main/cpp/hab")
    inputs.dir(rootProject.file("tools/hab"))
    inputs.file(rootProject.file("scripts/prepare_auto_qnx_asset.py"))
    inputs.file(rootProject.file(".work/hab-probes/build-report.json"))
    outputs.dir(qnxAssets)
    commandLine("python3", rootProject.file("scripts/prepare_auto_qnx_asset.py"), "--output", qnxAssets.get().dir("qnx").asFile)
}
android {
    namespace = "com.ecarx.autoservice"
    compileSdk = 37
    defaultConfig { applicationId = "com.ecarx.autoservice"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
    packaging.jniLibs.useLegacyPackaging = true
    sourceSets.getByName("main").jniLibs.directories.add(habLibraries.get().asFile.path)
    sourceSets.getByName("main").assets.directories.add(qnxAssets.get().asFile.path)
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
tasks.named("preBuild") { dependsOn(buildHab, prepareQnxReceiver) }
dependencies {
    implementation(project(":auto-client"))
    implementation(libs.kadb)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
