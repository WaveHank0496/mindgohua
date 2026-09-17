package com.mindgohua.stats

/**
 * 判斷「服務被系統殺掉」這件事該不該主動告訴使用者。
 *
 * ## 為什麼需要這個檔案
 *
 * [SurvivalLog.onServiceStart] 早就偵測得到服務中斷，但它的結果目前只寫進
 * `Log.w(TAG, "偵測到服務中斷過")` —— 而 **logcat 只有插著 USB 線的開發者看得到**。
 *
 * 對真正的使用者來說，這個 app 的失效方式是：開關還是開的、設定都還在，
 * 但貓再也不出現，而且**沒有任何提示**。他不會來回報 bug，他只會解除安裝。
 *
 * 這個檔案負責把那個已經偵測到、卻沒人看得到的事實，變成一則通知。
 *
 * ## 為什麼是純函式
 *
 * 判斷邏輯不碰 Context、不碰 NotificationManager、不碰 Android。
 * 這讓「重開機不該被當成被殺」「短暫中斷不值得吵使用者」「同一次中斷不該提醒兩次」
 * 這些規則可以用一般單元測試釘住（見 `SurvivalAlertTest`）——
 * 而這些規則如果寫錯，代價是**騷擾使用者**或**該講的時候沒講**，兩種都很糟。
 *
 * 真正要碰系統的部分（發通知）留在 `GatekeeperService`。
 */
object SurvivalAlert {

    /**
     * 短於這個長度的中斷不提醒。
     *
     * [SurvivalLog.GAP_THRESHOLD_MS] 是 3 分鐘，那是「算不算中斷」的門檻；
     * 這裡的門檻更高，是「值不值得主動吵使用者」的門檻。
     *
     * 兩者要分開：偵測要敏感（做為驗收數據），提醒要保守（避免變成狼來了）。
     * 一次 5 分鐘的中斷使用者多半沒感覺，硬要通知只會讓他把通知關掉 ——
     * 那之後真正嚴重的中斷也一起看不到了。
     */
    const val ALERT_THRESHOLD_MS = 30 * 60_000L

    /**
     * 這次服務啟動要不要提醒使用者。
     *
     * @param hadGap [SurvivalLog.onServiceStart] 是否偵測到中斷。
     * @param gapDurationMs 中斷了多久。
     * @param wasReboot 是不是重開機造成的。
     * @param alreadyNotifiedGapTo 上次已經提醒過的那次中斷的結束時間戳。
     *   用來避免同一次中斷因為服務反覆重啟而重複提醒。
     * @param gapToMs 這次中斷的結束時間戳。
     */
    fun shouldNotify(
        hadGap: Boolean,
        gapDurationMs: Long,
        wasReboot: Boolean,
        alreadyNotifiedGapTo: Long,
        gapToMs: Long,
    ): Boolean {
        if (!hadGap) return false
        // 重開機不是系統「殺」掉服務，是使用者自己關機的正常結果。
        // 把它講成「被系統終止」會讓使用者跑去改一堆根本沒問題的設定。
        if (wasReboot) return false
        if (gapDurationMs < ALERT_THRESHOLD_MS) return false
        // 同一次中斷只提醒一次。服務被殺後可能連續重啟失敗好幾輪，
        // 每輪都提醒的話使用者會被同一件事轟炸。
        if (gapToMs <= alreadyNotifiedGapTo) return false
        return true
    }

    /**
     * 通知的內文。
     *
     * 刻意**不**寫成「發生錯誤」或「服務異常」—— 那會讓使用者以為 app 壞了。
     * 事實是系統把它關掉了，而這件事使用者自己可以修（放行背景執行）。
     * 所以文案要講清楚：發生了什麼、期間失去了什麼、可以怎麼辦。
     */
    fun message(gapDurationMs: Long): String {
        val text = formatDuration(gapDurationMs)
        return "系統把貓關掉了約 $text，這段期間沒有在看住你的 app。點一下看怎麼避免。"
    }

    /** 給通知用的簡短時間描述。刻意不顯示秒 —— 這裡要的是「嚴重程度」而不是精確度。 */
    internal fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days 天 $hours 小時"
            hours > 0 -> "$hours 小時 $minutes 分"
            else -> "$minutes 分"
        }
    }
}
