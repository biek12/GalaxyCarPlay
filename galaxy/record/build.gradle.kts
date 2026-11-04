plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.galaxy.autorecord"
    compileSdk = 37
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "com.galaxy.autorecord"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.galaxy.autorecord.RecordOfflineInstrumentation"
        buildConfigField("boolean", "EMULATOR_CAMERA", "false")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    testBuildType = if (providers.gradleProperty("galaxyRecordEmulatorTest").orNull == "true") "emulator" else "debug"

    /**
     * 现场验证包：与 debug 同签名同包名，但开启 R8 与资源压缩。
     * 起因是远程通道对 APK 体积有限制（artifact 429、shell 命令体 4KB），
     * debug 包 3.4 MB 推不上去；精简后既省带宽也省安装时间。
     * 不作为发布包：仍用调试签名，仅用于实车验证。
     */
    buildTypes {
        create("emulator") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "EMULATOR_CAMERA", "true")
            matchingFallbacks += listOf("debug")
        }
        create("field") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            matchingFallbacks += listOf("debug")
        }
    }
}
dependencies {
    implementation(project(":auto-client"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
