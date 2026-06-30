/*
 * TypiAI — app/build.gradle.kts
 * ══════════════════════════════════════════════════════════════════════════════
 * Android version support matrix
 * ──────────────────────────────────────────────────────────────────────────────
 *  minSdk  = 30  →  Android 11  (covers ~97 % of active Android devices)
 *  targetSdk = 35  →  Android 15  (latest stable)
 *  compileSdk = 35  →  Latest SDK APIs available at compile time
 *
 * Why minSdk 30 (Android 11)?
 *  • android:exported enforcement — API 30 made it mandatory to declare
 *    exported=true/false on components with intent-filters.  We comply.
 *  • Clipboard background write — still permitted on API 30–32, giving us
 *    a reliable paste fallback in the AccessibilityService.
 *  • Dynamic Color (Material You) — gracefully degraded on API <31 via the
 *    static Material 3 colour scheme; no crash, just no wallpaper colours.
 *  • SplashScreen compat — core-splashscreen 1.0.1 back-ports the API to 23+.
 * ══════════════════════════════════════════════════════════════════════════════
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "com.typiai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typiai"
        minSdk        = 30   // Android 11 — lowest supported version
        targetSdk     = 35   // Android 15 — latest stable
        versionCode   = 1
        versionName   = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
        )
    }

    buildFeatures {
        compose    = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Ensure consistent APK naming
    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = "TypiAI-${buildType.name}-${versionName}.apk"
        }
    }
}

dependencies {
    // ── AndroidX core ──────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)          // API 23+ compat splash

    // ── Jetpack Compose (BOM pins all compose versions together) ───────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // ── DataStore (replaces SharedPreferences) ─────────────────────────────
    implementation(libs.androidx.datastore.preferences)

    // ── Networking (OkHttp + Gson) ─────────────────────────────────────────
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // ── Coroutines ─────────────────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.android)

    // ── Testing ────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
