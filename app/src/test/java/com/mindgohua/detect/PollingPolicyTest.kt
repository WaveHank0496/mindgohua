package com.mindgohua.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 輪詢節奏的規則。
 *
 * 這些規則寫錯不會拋例外、不會當機、CI 也不會變紅 —— 只會安靜地變糟：
 * 要嘛白白耗電，要嘛貓該出現的時候沒出現。所以用測試釘住。
 */
class PollingPolicyTest {

    @Test
    fun `螢幕關閉時完全停止輪詢`() {
        // 最大的一筆浪費。螢幕關著時使用者不可能在滑手機。
        assertNull(PollingPolicy.nextDelayMs(screenOn = false, inTargetApp = false))
    }

    @Test
    fun `螢幕關閉時即使原本在目標 app 也要停`() {
        // 使用者在 IG 裡直接按電源鍵鎖屏 —— 這時 inTargetApp 還是 true，
        // 但螢幕已經關了，繼續輪詢沒有任何意義。
        assertNull(PollingPolicy.nextDelayMs(screenOn = false, inTargetApp = true))
    }

    @Test
    fun `在目標 app 裡維持較短的間隔以保持計時準確`() {
        assertEquals(
            PollingPolicy.ACTIVE_INTERVAL_MS,
            PollingPolicy.nextDelayMs(screenOn = true, inTargetApp = true),
        )
    }

    @Test
    fun `螢幕開著但不在目標 app 時放慢輪詢`() {
        assertEquals(
            PollingPolicy.IDLE_INTERVAL_MS,
            PollingPolicy.nextDelayMs(screenOn = true, inTargetApp = false),
        )
    }

    @Test
    fun `放慢後的間隔必須真的比較長`() {
        // 防止之後有人調參數時把兩個值調反了 —— 那會變成「在目標 app 時反而比較鈍」。
        assertTrue(
            "IDLE 間隔必須大於 ACTIVE 間隔",
            PollingPolicy.IDLE_INTERVAL_MS > PollingPolicy.ACTIVE_INTERVAL_MS,
        )
    }

    @Test
    fun `閒置間隔不得長到影響最短的打斷門檻`() {
        // 設定頁允許的最短門檻是 30 秒。閒置間隔如果接近那個數字，
        // 使用者會發現「我明明滑很久了貓才出現」。
        // 這條測試是一道天花板，防止之後為了省電把間隔無限拉長。
        assertTrue(
            "閒置間隔不該超過最短門檻（30 秒）的五分之一",
            PollingPolicy.IDLE_INTERVAL_MS <= 6_000L,
        )
    }

    @Test
    fun `一天省下的查詢次數要有實際意義`() {
        // 典型情境：螢幕亮 8 小時，其中 2 小時在目標 app。
        val saved = PollingPolicy.querySavingsPerDay(screenOnHours = 8.0, targetAppHours = 2.0)

        // 原本一天固定 2 秒一次 = 43,200 次。
        assertTrue("省下的次數應該超過三萬次，實際 $saved", saved > 30_000)
    }

    @Test
    fun `完全不開螢幕的一天應該幾乎不查詢`() {
        val saved = PollingPolicy.querySavingsPerDay(screenOnHours = 0.0, targetAppHours = 0.0)

        // 24 小時 * 3600 秒 / 2 秒 = 43,200
        assertEquals(43_200L, saved)
    }

    @Test
    fun `整天都在目標 app 時不會謊報省下任何查詢`() {
        // 邊界：這種情況下新舊策略完全一樣，省下的必須是 0 而不是負數或虛報。
        val saved = PollingPolicy.querySavingsPerDay(screenOnHours = 24.0, targetAppHours = 24.0)

        assertEquals(0L, saved)
    }

    @Test
    fun `目標 app 時數超過螢幕開啟時數時不會算出負數`() {
        // 不合理的輸入不該讓這個函式產生看起來很厲害的假數字。
        val saved = PollingPolicy.querySavingsPerDay(screenOnHours = 2.0, targetAppHours = 10.0)

        assertTrue("省下的次數不該是負數，實際 $saved", saved >= 0)
    }
}
