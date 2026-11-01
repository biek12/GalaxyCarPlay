plugins { id("com.android.library") }
android {
    namespace = "com.galaxy.auto.client"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { api(project(":auto-contracts")) }
