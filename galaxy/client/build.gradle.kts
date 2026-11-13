plugins {
    id("com.android.library")
}

android {
    namespace = "com.galaxy.auto.client"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation(project(":auto-ui"))
    api(project(":auto-contracts"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
