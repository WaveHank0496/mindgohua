import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release 簽章設定。
 *
 * 上架到 Google Play 的 APK 必須用一把固定的金鑰簽名，而且**這把金鑰一旦遺失，
 * 這個 app 就永遠無法再發布更新**（Play 認的是簽章，不是帳號）。
 *
 * 金鑰本身（.jks）與它的密碼（keystore.properties）都被 .gitignore 排除，
 * 絕不進版控 —— 這個 repo 是公開的。
 *
 * 找不到 keystore.properties 時不會讓建置失敗，只是 release 版本不簽章。
 * 這是刻意的：CI 跟其他開發者沒有這把金鑰，但他們仍然要能建置與跑測試。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.mindgohua"
    // compileSdk 必須 >= targetSdk，否則 AGP 直接拒編。
    // 這裡跟著 targetSdk 一起升到 36（Android 16）。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mindgohua"
        // 目標實機是 OPPO Reno7 5G（ColorOS on Android 11+）
        minSdk = 30

        // Play 自 2026-08-31 起，新上架與更新一律要求 targetSdk 36（Android 16）。
        // 停在 35 的話連上傳都會被擋 —— 這是唯一會卡住上架本身的問題。
        //
        // 這不只是一個數字。targetSdk 36 會啟用 Android 16 的行為變更，
        // 其中對本 app 最有影響的是**預設啟用 predictive back**：
        // 系統不再呼叫 onBackPressed()，也**不再派送 KeyEvent.KEYCODE_BACK**。
        //
        // 這正好打中 overlay 的防線：OverlayController 攔截返回鍵（不然一鍵 back
        // 就把打斷跳過了）靠的就是 dispatchKeyEvent 收 KEYCODE_BACK。官方文件
        // 只描述 Activity 的情況，沒有說明 WindowManager 加上去的
        // TYPE_APPLICATION_OVERLAY 視窗是否同樣受影響 —— 查不到答案，
        // 只能實機驗證。若失效，overlay 會變成 back 一按就穿過去。
        //
        // 邊到邊顯示在 35 就已強制，36 只是移除 windowOptOutEdgeToEdgeEnforcement
        // 這個退出開關；本專案從未使用該屬性，預期無額外變化。
        //
        // 其餘 36 的變更（大螢幕方向/尺寸限制、健康權限、本機網路權限）
        // 與本 app 無關：手機單一裝置、不讀感測器、且刻意沒有 INTERNET 權限。
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-prototype"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 沒有金鑰時維持不簽章，而不是退回 debug 簽章 ——
            // debug 簽章的 APK 上傳到 Play 會被拒，而且失敗訊息很難懂。
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 注意：不得引入任何需要網路的函式庫 / analytics SDK（見 spec §9）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
