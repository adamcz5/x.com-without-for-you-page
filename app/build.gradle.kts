plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.su.noforyou"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.su.noforyou"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("vc") as String?)?.toIntOrNull() ?: 1
        versionName = "1.2"
    }

    signingConfigs {
        getByName("debug") {      // fixed key (committed on purpose, personal use): every build can be installed over the previous one
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
