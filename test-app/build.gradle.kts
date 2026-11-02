// Test fixtures only. No production module depends on these APKs.
plugins { id("com.android.application") }
android {
    namespace = "com.haibo.tiaotiao.fixture"
    compileSdk = 37
    defaultConfig { applicationId = "com.haibo.tiaotiao.fixture"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1" }
    flavorDimensions += "fixture"
    productFlavors {
        create("alpha") { dimension = "fixture"; applicationIdSuffix = ".alpha" }
        create("beta") { dimension = "fixture"; applicationIdSuffix = ".beta" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
