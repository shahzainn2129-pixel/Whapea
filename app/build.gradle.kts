plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whapea.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whapea.app"
        minSdk = 26
        targetSdk = 34
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = if (run > 0) run else 5
        versionName = "1.7"
        buildConfigField("int", "BUILD_N", run.toString())
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("whapea") {
            storeFile = file("whapea.jks")
            storePassword = "0058f7f3afab872c89bdb95a"
            keyAlias = "whapea"
            keyPassword = "0058f7f3afab872c89bdb95a"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("whapea") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
