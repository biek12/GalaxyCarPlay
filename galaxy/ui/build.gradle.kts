plugins { id("com.android.library") }
android {
    namespace = "com.galaxy.auto.ui"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    testOptions {unitTests.isIncludeAndroidResources=true}
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}

dependencies {testImplementation(libs.junit);testImplementation("org.robolectric:robolectric:4.17")}
