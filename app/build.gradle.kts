import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key: from keystore.properties (local, gitignored) or from env vars (CI secrets).
// Without either, release builds fall back to the debug key.
val props = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)
val releaseStore = cfg("storeFile", "KEYSTORE_FILE")

android {
    namespace = "com.dbuezas.t9launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dbuezas.t9launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5.0"
    }

    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = cfg("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = cfg("keyAlias", "KEY_ALIAS")
            keyPassword = cfg("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += "**"
    }
}
