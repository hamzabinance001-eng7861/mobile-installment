plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun cfg(name: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)
        ?: ""

val keystorePath = cfg("RELEASE_KEYSTORE_PATH")
val hasReleaseKey = keystorePath.isNotBlank() && file(keystorePath).exists()

android {
    namespace = "com.installment.customerapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.installment.customerapp"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${versionCode}"

        buildConfigField("String", "FCM_PROJECT_ID", "\"${cfg("FCM_PROJECT_ID")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${cfg("FCM_APP_ID")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${cfg("FCM_API_KEY")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${cfg("FCM_SENDER_ID")}\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = cfg("RELEASE_KEYSTORE_TYPE").ifBlank { "pkcs12" }
                storePassword = cfg("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = cfg("RELEASE_KEY_ALIAS")
                keyPassword = cfg("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the APK is signed with the debug key: fine for testing only.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { buildConfig = true }

    // Lint still runs (reports are uploaded by CI) but API-level notes must not block building the APK.
    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
}
