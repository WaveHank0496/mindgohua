package com.mindgohua.detect

/**
 * 輪詢節奏的決策。
 *
 * ## 問題
 *
 * Mode A 的偵測方式是「每隔一段時間問系統：現在最前面是哪個 app」。
 * 原本這個間隔是固定的 2 秒 —— 不管螢幕是不是關的、不管使用者是不是
 * 正在目標 app 裡面，一律 2 秒問一次。
 *
 * 實際上絕大多數時間這些查詢都是白做的：
 * - **螢幕關著**：使用者不可能在滑手機，卻仍然每 2 秒問一次。
 *   一天關螢幕 16 小時 ≈ 28,800 次完全沒有意義的查詢。
 * - **不在目標 app**：使用者在看地圖、在打字、在講電話。這時只需要知道
 *   「他什麼時候切進目標 app」，而那件事晚幾秒知道完全沒差 ——
 *   因為打斷的門檻本來就是分鐘級的。
 *
 * ## 做法
 *
 * 分三段：
 *
 * | 狀態 | 間隔 | 理由 |
 * |---|---|---|
 * | 螢幕關閉 | 完全停止 | 不可能在滑 |
 * | 螢幕開、不在目標 app | [IDLE_INTERVAL_MS] | 只需要知道何時切進來 |
 * | 螢幕開、在目標 app | [ACTIVE_INTERVAL_MS] | 計時要準 |
 *
 * 在目標 app 裡仍然維持 2 秒，是因為那時的數字會直接顯示給使用者看
 * （診斷通知、HUD），而且 session 邊界的判斷需要這個精度。
 * **省電不能省到讓功能變得不準。**
 *
 * ## 為什麼是純函式
 *
 * 這裡不碰 Context、不碰 PowerManager、不碰任何 Android 類別，
 * 只吃兩個布林值、回傳一個數字。這讓「螢幕關了要停」「不在目標 app 要放慢」
 * 這些規則可以用一般單元測試釘住（見 `PollingPolicyTest`）——
 * 而這些規則寫錯的後果是**耗電**或**貓該出現時沒出現**，兩種都不會拋例外，
 * 只會安靜地變糟。
 */
object PollingPolicy {

    /** 在目標 app 裡面時的輪詢間隔。要夠準，因為這個數字會顯示給使用者看。 */
    const val ACTIVE_INTERVAL_MS = 2_000L

    /**
     * 螢幕開著、但不在目標 app 時的輪詢間隔。
     *
     * 6 秒是刻意選的折衷：使用者切進 IG 之後最慢 6 秒會被發現，
     * 而打斷門檻最短是 30 秒、預設 10 分鐘 —— 晚 6 秒開始計時，
     * 對「連續使用多久」的判斷影響小到使用者感覺不出來。
     *
     * 換來的是這段時間的查詢次數直接砍成三分之一。
     */
    const val IDLE_INTERVAL_MS = 6_000L

    /**
     * 這一輪該睡多久；回傳 null 代表**完全停止輪詢**。
     *
     * @param screenOn 螢幕是否亮著。
     * @param inTargetApp 目前前景是否為使用者選定的目標 app。
     */
    fun nextDelayMs(screenOn: Boolean, inTargetApp: Boolean): Long? = when {
        !screenOn -> null
        inTargetApp -> ACTIVE_INTERVAL_MS
        else -> IDLE_INTERVAL_MS
    }

    /**
     * 相對於「固定 2 秒」，這樣安排一天能少做幾次查詢。
     *
     * 只用來在文件與設定頁上說明效益，不影響任何行為。
     * 做成函式而不是寫死一個數字，是因為上面的間隔之後可能會調整 ——
     * 寫死的數字會悄悄變成謊言。
     *
     * @param screenOnHours 一天螢幕亮著幾小時。
     * @param targetAppHours 其中有幾小時是在目標 app 裡。
     */
    fun querySavingsPerDay(screenOnHours: Double, targetAppHours: Double): Long {
        // 螢幕開著的時數不可能超過一天；不合理的輸入不該讓這個函式
        // 產生看起來很厲害的假數字。
        val awakeHours = screenOnHours.coerceIn(0.0, 24.0)
        val activeHours = targetAppHours.coerceIn(0.0, awakeHours)
        val idleHours = awakeHours - activeHours

        fun countsIn(hours: Double, intervalMs: Long): Double =
            hours * 3_600_000.0 / intervalMs

        val before = countsIn(24.0, ACTIVE_INTERVAL_MS)
        // 螢幕關著的時數完全不查詢，所以根本不列入 after。
        //
        // 原本這裡寫的是 countsIn(screenOffHours, Long.MAX_VALUE)，想表達
        // 「間隔無限大 = 查詢 0 次」—— 但那算出來是一個極小的非零值，
        // 讓「完全不開螢幕」的答案從 43200 變成 43199。
        //
        // 教訓：想表達零就寫零，不要拿一個很大的數去逼近它。
        // 這種錯不會爆炸，只會讓數字安靜地說謊。
        val after = countsIn(idleHours, IDLE_INTERVAL_MS) +
            countsIn(activeHours, ACTIVE_INTERVAL_MS)
        return (before - after).toLong().coerceAtLeast(0L)
    }
}
