package com.mindgohua.settings

import com.mindgohua.core.EngineConfig
import com.mindgohua.core.RhythmConfig

enum class DetectionMode {
    /** Mode A：只看「哪個 app 在前景、連續多久」，看不到畫面內容。 */
    PRIVACY,

    /** Mode B：讀捲動事件的時間戳，判斷滑動節奏。需 AccessibilityService。 */
    FOCUS,
}

/**
 * 使用者設定。**這裡只存設定，不存任何偵測資料**（spec §0.1、架構圖的 Settings Store）。
 * 沒有 session 紀錄、沒有使用時長歷史、沒有 app 使用統計 —— 一律不落地。
 */
data class AppSettings(
    val enabled: Boolean = false,
    val mode: DetectionMode = DetectionMode.PRIVACY,
    val targetPackages: Set<String> = setOf(TargetApps.INSTAGRAM),

    /** 連續使用多久打斷（秒）。 */
    val thresholdSeconds: Int = 120,

    /** 切出去多久內切回來仍算同一 session（秒）。 */
    val cooldownSeconds: Int = 10,

    /** 解除後多久內不再打斷（分鐘）。 */
    val graceMinutes: Int = 5,

    /** overlay 按鈕要等幾秒才能按（防馴化，spec §6）。 */
    val unlockDelaySeconds: Int = 3,

    /**
     * 把偵測器的即時數字顯示在常駐通知上。
     * 調 Mode B 參數時必開 —— 你在滑 IG 的當下沒辦法看設定頁，但可以下拉通知欄。
     * 代價是通知每 2 秒重畫一次，調完記得關掉。
     */
    val diagnosticsNotification: Boolean = false,

    /**
     * 記錄每日滑動計數並寫入本機儲存。
     *
     * **預設關閉**，因為它推翻了 spec §0.1 的「不落地」原則。
     * 開啟後會寫入磁碟的只有「日期 + app + 一個整數」，
     * 但這仍然是一個實質的隱私姿態改變，應該由使用者主動選擇。
     * 詳見 [com.mindgohua.stats.DailyStatsStore]。
     */
    val statsEnabled: Boolean = false,

    /** 在目標 app 右上角顯示今日計數。 */
    val hudEnabled: Boolean = false,

    /** 「一天」從幾點開始。午夜換日會把熬夜那段切成兩天，預設 4 點。 */
    val dayBoundaryHour: Int = 4,

    /** 保留幾天的紀錄，更舊的自動刪除。 */
    val statsRetentionDays: Int = 90,

    // ---- Mode B 調參 ----
    val rhythmWindowSeconds: Int = 90,
    val rhythmMinDensityPerMinute: Int = 18,
    /** CV 門檻乘以 100 存整數，避免 DataStore 存 float 的精度困擾。 */
    val rhythmMaxCvPercent: Int = 55,
    val rhythmSustainSeconds: Int = 60,
) {
    fun toEngineConfig(): EngineConfig = EngineConfig(
        thresholdMs = thresholdSeconds * 1000L,
        cooldownMs = cooldownSeconds * 1000L,
        graceMs = graceMinutes * 60_000L,
    )

    fun toRhythmConfig(): RhythmConfig = RhythmConfig(
        windowMs = rhythmWindowSeconds * 1000L,
        minDensityPerMinute = rhythmMinDensityPerMinute.toDouble(),
        maxCoefficientOfVariation = rhythmMaxCvPercent / 100.0,
        sustainMs = rhythmSustainSeconds * 1000L,
    )
}

object TargetApps {
    const val INSTAGRAM = "com.instagram.android"
    const val YOUTUBE = "com.google.android.youtube"
    const val TIKTOK = "com.zhiliaoapp.musically"
    const val TIKTOK_ALT = "com.ss.android.ugc.trill"
    const val FACEBOOK = "com.facebook.katana"
    const val X = "com.twitter.android"
    const val REDDIT = "com.reddit.frontpage"

    /** Prototype 的可選清單。spec 只要求 IG / YouTube，其餘是順手加的。 */
    val selectable: List<Pair<String, String>> = listOf(
        INSTAGRAM to "Instagram",
        YOUTUBE to "YouTube",
        TIKTOK to "TikTok",
        TIKTOK_ALT to "TikTok（國際版）",
        FACEBOOK to "Facebook",
        X to "X / Twitter",
        REDDIT to "Reddit",
    )

    fun labelOf(packageName: String?): String =
        selectable.firstOrNull { it.first == packageName }?.second ?: (packageName ?: "這個 app")
}
