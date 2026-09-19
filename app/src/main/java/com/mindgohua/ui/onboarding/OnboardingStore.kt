package com.mindgohua.ui.onboarding

import android.content.Context

/**
 * 只記一件事：這個人有沒有走過引導。
 *
 * ## 為什麼不放進 `SettingsStore`
 *
 * 兩個理由：
 *
 * 1. `SettingsStore` 存的是**使用者設定** —— 他調過、也看得到的東西。
 *    「引導走過了沒」不是設定，是 app 自己的狀態，混在一起會讓那個檔案
 *    慢慢變成什麼都塞的雜物間。
 *
 * 2. 更實際的理由：`MainActivity.onCreate` 必須在**畫第一幀之前**就知道
 *    要顯示引導還是設定頁。`SettingsStore` 是 DataStore，只有 suspend 可讀，
 *    第一幀一定會先畫出設定頁再跳走 —— 一個缺權限的新使用者會先看到
 *    那面七張卡片的牆閃一下。那正是這次要修掉的東西。
 *    SharedPreferences 是同步的，第一幀就能決定。
 *
 * 這個檔案**不寫單元測試**：它只有兩行邏輯，而那兩行全都是 Android API 呼叫，
 * 在純 JVM 測試環境裡是沒有實作的空殼。真正會寫錯的判斷全在
 * [OnboardingFlow.shouldShow]，那裡才是測試該去的地方。
 */
class OnboardingStore(context: Context) {

    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    /**
     * 標記走完。
     *
     * 中途放棄（歡迎頁的「我自己來」）也算走完 —— 使用者已經表達過
     * 「不要帶我」了，下次開 app 再問一次就是不聽人話。
     */
    fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    private companion object {
        const val NAME = "mindgohua_onboarding"
        const val KEY_COMPLETED = "completed"
    }
}
