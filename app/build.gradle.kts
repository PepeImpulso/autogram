import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release signing: read credentials from keystore.properties at repo root.
// The file is gitignored; if missing, the release build falls back to unsigned
// (which is fine for local dev — CI / release cuts must provide the file).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

// Telegram MTProto credentials from local.properties (gitignored).
// Obtained once at my.telegram.org per user account.
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}
val telegramApiId: String = localProperties.getProperty("telegram.apiId", "0")
val telegramApiHash: String = localProperties.getProperty("telegram.apiHash", "")
// Sentry DSN. Empty string disables crash reporting at runtime (see AutogramApplication).
val sentryDsn: String = localProperties.getProperty("sentry.dsn", "")

android {
    namespace = "com.autogram.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autogram.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 146
        versionName = "1.26.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            "\"https://www.impulsotecnologico.com/legal/privacidad-autogram\""
        )
        buildConfigField(
            "String",
            "ACCEPTABLE_USE_URL",
            "\"https://www.impulsotecnologico.com/legal/terminos-autogram\""
        )

        // Restrict native libs to architectures we actually ship.
        // arm64-v8a: all modern phones. x86_64: Android emulators on Intel.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_SELF_UPDATE", "true")
            buildConfigField("boolean", "ENABLE_AUTOMATIC_CRASH_REPORTING", "true")
            // Off: a crash screenshot can show contact names and message text
            // that server-side scrubbing never sees.
            buildConfigField("boolean", "ENABLE_SENTRY_SCREENSHOTS", "false")
            buildConfigField("boolean", "ALLOW_CLEARTEXT_LLM_ENDPOINT", "true")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                // v3 lets the signing key be rotated later without breaking
                // in-place upgrades; v2 stays on for older verifiers.
                enableV2Signing = true
                enableV3Signing = true
            }
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
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "MissingTranslation"
    }
}

// Release builds must carry real Telegram credentials: an api_id of 0 or an
// empty api_hash produces an APK that cannot log in. Debug builds and unit
// tests keep working without them. Forks must register their own api_id at
// my.telegram.org (see docs/BUILDING.md).
val verifyTelegramCredentials by tasks.registering {
    val apiId = telegramApiId
    val apiHash = telegramApiHash
    doLast {
        if ((apiId.trim().toIntOrNull() ?: 0) <= 0 || apiHash.isBlank()) {
            throw GradleException(
                "Release builds need telegram.apiId and telegram.apiHash in " +
                    "local.properties. Register your own app at " +
                    "https://my.telegram.org and see docs/BUILDING.md."
            )
        }
    }
}
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("ReleaseBuild") }
    .configureEach { dependsOn(verifyTelegramCredentials) }

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core)
    // Needed for AppCompatDelegate.setApplicationLocales (per-app language on pre-33).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Drag-and-drop reordering for the Shortcuts editor LazyColumn.
    implementation(libs.reorderable)

    // Android Auto
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    // androidx.media — MediaBrowserServiceCompat + MediaSessionCompat. Lets
    // Autogram appear as a media app in Android Auto's media tab and routes
    // audio through the car's media stream during playback.
    implementation(libs.androidx.media)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Security
    implementation(libs.security.crypto)

    // Logging
    implementation(libs.timber)

    // Crash reporting is packaged in the GitHub flavor.
    "githubImplementation"(libs.sentry.android)
    "githubImplementation"(libs.sentry.timber)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Real org.json instead of the Android stub so JSON parsing works in
    // pure unit tests (no Robolectric needed).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
}
