package com.mindgohua.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「該不該提醒使用者服務被殺掉」的判斷規則。
 *
 * 這裡守住的東西兩邊都很貴：
 * - 該提醒卻沒提醒 → app 靜默失效，使用者不會回報，直接解除安裝
 * - 不該提醒卻提醒 → 變成狼來了，使用者把通知關掉，之後真的出事也看不到
 *
 * 所以每一條規則都要有測試，而不是靠註解。
 */
class SurvivalAlertTest {

    private val oneHour = 60 * 60_000L

    @Test
    fun `沒有中斷就不提醒`() {
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = false,
                gapDurationMs = oneHour,
                wasReboot = false,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `長時間被系統殺掉要提醒`() {
        assertTrue(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = oneHour,
                wasReboot = false,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `重開機造成的中斷不提醒`() {
        // 重開機是使用者自己關機的正常結果，不是系統殺掉服務。
        // 講成「被系統終止」會讓使用者跑去改一堆根本沒問題的設定。
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = oneHour * 8,
                wasReboot = true,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `短暫中斷不提醒以免變成狼來了`() {
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = 5 * 60_000L,
                wasReboot = false,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `剛好達到門檻要提醒`() {
        // 邊界值：用 >= 還是 > 決定了門檻的意義，寫錯不會有人發現。
        assertTrue(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = SurvivalAlert.ALERT_THRESHOLD_MS,
                wasReboot = false,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `差一毫秒不到門檻就不提醒`() {
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = SurvivalAlert.ALERT_THRESHOLD_MS - 1,
                wasReboot = false,
                alreadyNotifiedGapTo = 0,
                gapToMs = 1000,
            )
        )
    }

    @Test
    fun `同一次中斷不會重複提醒`() {
        // 服務被殺後可能連續重啟失敗好幾輪，每輪都提醒會轟炸使用者。
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = oneHour,
                wasReboot = false,
                alreadyNotifiedGapTo = 5000,
                gapToMs = 5000,
            )
        )
    }

    @Test
    fun `比上次更新的中斷仍然要提醒`() {
        // 「不重複提醒」不能變成「以後都不提醒」。
        assertTrue(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = oneHour,
                wasReboot = false,
                alreadyNotifiedGapTo = 5000,
                gapToMs = 6000,
            )
        )
    }

    @Test
    fun `舊於上次提醒的中斷不會倒退提醒`() {
        assertFalse(
            SurvivalAlert.shouldNotify(
                hadGap = true,
                gapDurationMs = oneHour,
                wasReboot = false,
                alreadyNotifiedGapTo = 9000,
                gapToMs = 4000,
            )
        )
    }

    // ---- 文案 ----

    @Test
    fun `時間描述在分鐘範圍內只講分鐘`() {
        assertEquals("45 分", SurvivalAlert.formatDuration(45 * 60_000L))
    }

    @Test
    fun `超過一小時要講小時與分鐘`() {
        assertEquals("2 小時 30 分", SurvivalAlert.formatDuration(150 * 60_000L))
    }

    @Test
    fun `超過一天要講天數`() {
        assertEquals("1 天 2 小時", SurvivalAlert.formatDuration(26 * 60 * 60_000L))
    }

    @Test
    fun `通知內文要說明失去了什麼而不是只說發生錯誤`() {
        val text = SurvivalAlert.message(oneHour)

        // 不可以讓使用者以為是 app 壞了 —— 事實是系統關掉它，而他自己可以修。
        assertFalse("不該用『錯誤』字眼", text.contains("錯誤"))
        assertTrue("要說明失去了什麼", text.contains("沒有在看住"))
        assertTrue("要包含中斷時長", text.contains("1 小時"))
    }
}
