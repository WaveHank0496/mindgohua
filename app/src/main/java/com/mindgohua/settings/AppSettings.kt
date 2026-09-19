package com.mindgohua.settings

import android.content.Context
import com.mindgohua.core.EngineConfig
import com.mindgohua.core.RhythmConfig

enum class DetectionMode {
    /** 只看「哪個 app 在前景、連續多久」，看不到畫面內容。 */
    PRIVACY,

    // 原本還有 FOCUS（讀捲動事件的時間戳判斷滑動節奏，需 AccessibilityService）。
    // v1 移除實作以避開 Play 的無障礙用途宣告審查 —— 見 AndroidManifest 的說明。
    //
    // 列舉本身保留成單一值而不是整個刪掉，是為了讓 SettingsStore 存的 "PRIVACY"
    // 字串、以及舊使用者磁碟上可能存著的 "FOCUS" 字串都還有地方可去
    // （SettingsStore 的 valueOf 失敗會安全退回 PRIVACY）。
}

/**
 * 使用者設定。**這裡只存設定，不存任何偵測資料**（spec §0.1、架構圖的 Settings Store）。
 * 沒有 session 紀錄、沒有使用時長歷史、沒有 app 使用統計 —— 一律不落地。
 */
data class AppSettings(
    val enabled: Boolean = false,
    val mode: DetectionMode = DetectionMode.PRIVACY,
    val targetPackages: Set<String> = setOf(TargetApps.INSTAGRAM),

    /**
     * 連續使用多久打斷（秒）。
     *
     * **預設 10 分鐘。這個值有研究依據，不是隨便訂的。**
     *
     * 大多數人要到連續使用 **10～20 分鐘之後**，才開始對自己的手機使用
     * 產生負面感受（Terzimehić & Aragon-Hahner 2022）；CHI 2025 的無限捲動
     * 介入研究據此把介入點放在 15 分鐘。
     *
     * 太早打斷，使用者還不覺得自己有問題 —— 他只會覺得這個 app 很煩，
     * 然後解除安裝。寧可漏掉幾次，也不要變成一個惹人厭的東西。
     *
     * 開發期間這個值曾經是 120 秒，那是為了不用真的滑十分鐘才看得到貓。
     * 那是開發便利，不是產品決定。
     */
    val thresholdSeconds: Int = 600,

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
     * 服務被系統殺掉時主動通知。
     *
     * **預設開啟**，這點跟其他設定相反。理由：這個 app 的失效方式是靜默的 ——
     * 開關還是開的、設定都還在，但貓再也不出現。使用者不會知道要去哪裡檢查，
     * 也不會來回報，他只會覺得這東西壞了然後解除安裝。
     *
     * 預設關閉等於預設讓使用者被蒙在鼓裡。但仍然留開關給覺得被打擾的人。
     */
    val survivalAlertEnabled: Boolean = true,

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

    /**
     * 是否顯示「進階與診斷」區。
     *
     * **預設關閉。** 底下那幾張卡片（調參滑桿、存活紀錄、手動測試、當機紀錄）
     * 是為了驗收這個 app 自己而存在的，不是給使用者的功能。
     *
     * 第一次打開這個 app 的人應該看到「我要看住哪些 app、滑多久打斷我」，
     * 而不是一個儀表板。存進設定而不是用 UI 狀態，是因為正在測試的人
     * 不該每次開 app 都要重新展開一次。
     */
    val advancedVisible: Boolean = false,

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

    /**
     * 幾個常見 app 的中文顯示名。
     *
     * **這不再是「可選清單」** —— 使用者現在可以挑手機上任何有桌面圖示的 app
     * （見 [InstalledApps]）。這份表只負責兩件事：
     *
     * 1. 當我們手上只有套件名、拿不到 PackageManager 時（例如 overlay 的
     *    繪製路徑），還能顯示一個人看得懂的名字。
     * 2. 幾個名稱容易混淆的（TikTok 國際版、X / Twitter）給一個明確的說法。
     *
     * 查不到就回傳套件名本身 —— 醜，但至少是真的，不會騙人。
     */
    private val knownLabels: Map<String, String> = mapOf(
        INSTAGRAM to "Instagram",
        YOUTUBE to "YouTube",
        TIKTOK to "TikTok",
        TIKTOK_ALT to "TikTok（國際版）",
        FACEBOOK to "Facebook",
        X to "X / Twitter",
        REDDIT to "Reddit",
    )

    /**
     * 套件名 → 顯示名稱。
     *
     * @param resolved 由呼叫端提供的名稱（通常來自 PackageManager，會跟著
     *   使用者的系統語言走）。有值就優先用它 —— 系統給的名字永遠比我們
     *   寫死的表準確，而且涵蓋所有 app，不只表裡這幾個。
     */
    fun labelOf(packageName: String?, resolved: String? = null): String =
        resolved?.takeIf { it.isNotBlank() }
            ?: knownLabels[packageName]
            ?: packageName
            ?: "這個 app"

    /**
     * 向系統問這個套件的顯示名稱。
     *
     * 為什麼需要它：[knownLabels] 只有七個常見 app，而使用者現在可以挑
     * 手機上**任何** app。名單外的一律退回顯示套件名 —— 於是 Threads 在
     * 常駐通知上會變成「com.instagram.barcelona：已連續 3 分鐘」，
     * 沒有人看得懂那是什麼。
     *
     * 設定頁沒有這個問題，因為它有 PackageManager 查來的清單可用
     * （見 `InstalledAppsState`）。問題只出在拿不到那份清單的地方：
     * 常駐通知與 overlay。
     *
     * 查詢會碰 IPC，結果由呼叫端自行快取（套件名→名稱的對應幾乎不會變）。
     */
    fun labelOf(context: Context, packageName: String?): String {
        if (packageName == null) return "這個 app"
        val resolved = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        return labelOf(packageName, resolved)
    }
}
