import java.util.Properties
plugins { alias(libs.plugins.android.application) }
val privateServer=Properties().apply { rootProject.file(".private/server.properties").takeIf{it.isFile}?.inputStream()?.use{load(it)} }
val bridgeOrigin=providers.environmentVariable("GALAXY_ORIGIN").orElse(privateServer.getProperty("bridge.origin","https://bridge.example.invalid/galaxy")).get()
require(bridgeOrigin.startsWith("https://")&&!bridgeOrigin.contains('"')&&!bridgeOrigin.contains('\\')&&!bridgeOrigin.contains('\n')&&!bridgeOrigin.contains('\r'))
android {
    buildFeatures { buildConfig=true }

    namespace = "com.galaxy.autocluster"
    compileSdk = 37
    defaultConfig { buildConfigField("String","GALAXY_ORIGIN","\"$bridgeOrigin\""); applicationId = "com.galaxy.autocluster"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":auto-lyrics"))
    implementation(project(":auto-ui"))
    implementation(project(":auto-client"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
