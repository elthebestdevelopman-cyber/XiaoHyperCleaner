plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xiaohypercleaner"
    compileSdk = 37

    // Ключ владельца: нужен, чтобы ставить сборку ПОВЕРХ уже установленного APK
    // (debug-ключ AS даёт INSTALL_FAILED_UPDATE_INCOMPATIBLE). Секреты — только из
    // окружения: XHC_KEYSTORE_PATH, XHC_STORE_PASSWORD, XHC_KEY_PASSWORD,
    // необязательный XHC_KEY_ALIAS (по умолчанию xiaohypercleaner).
    val ownerKeystore = System.getenv("XHC_KEYSTORE_PATH")
        ?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (ownerKeystore != null) {
            create("owner") {
                storeFile = file(ownerKeystore)
                storePassword = System.getenv("XHC_STORE_PASSWORD")
                keyAlias = System.getenv("XHC_KEY_ALIAS") ?: "xiaohypercleaner"
                keyPassword = System.getenv("XHC_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.xiaohypercleaner"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0-beta2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Ключ владельца, если он задан в окружении: установка поверх его сборки.
            signingConfig =
                signingConfigs.findByName("owner") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.datastore)
    implementation(libs.coroutines.android)

    // Shizuku API (MIT License)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.mockito.core)
}