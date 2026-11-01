plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.galaxy.autocluster"
    compileSdk = 37
    defaultConfig { applicationId = "com.galaxy.autocluster"; minSdk = 29; targetSdk = 37; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":auto-client"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
