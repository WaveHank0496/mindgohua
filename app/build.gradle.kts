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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mindgohua"
        // 目標實機是 OPPO Reno7 5G（ColorOS on Android 11+）
        minSdk = 30

        // Play 對新上架 app 要求 targetSdk 35。
        //
        // 這不只是一個數字 —— targetSdk 35 會啟用 Android 15 的行為變更，其中
        // 對本 app 最有影響的是**強制邊到邊顯示**：系統不再自動幫畫面避開狀態列，
        // 而 android:statusBarColor / navigationBarColor 這類屬性同時失效
        //（res/values/themes.xml 裡那行透明狀態列設定因此變成無作用）。
        //
        // 設定頁用 Material3 Scaffold + innerPadding，Scaffold 預設會把系統列
        // 留白算進 innerPadding，理論上不受影響；但 overlay 與 HUD 是自己管
        // WindowManager 的，只能靠實機確認。見 docs/ROADMAP.md 的實機驗收清單。
        targetSdk = 35
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
