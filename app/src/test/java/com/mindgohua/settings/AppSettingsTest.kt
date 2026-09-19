package com.mindgohua.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 預設值的守門測試。
 *
 * 這些值決定「一個從來沒動過設定的人，拿到的是什麼樣的 app」。
 *
 * 改錯它們不會有編譯錯誤、不會丟例外、不會有任何東西變紅 ——
 * 只會讓每一個新使用者拿到一個跟我們想給的不一樣的東西，
 * 而且我們不會發現。正是需要測試釘死的那種值。
 */
class AppSettingsTest {

    private val defaults = AppSettings()

    // ---- 隱私姿態 ----

    @Test
    fun `預設不把任何使用統計寫進磁碟`() {
        // spec §0.1 的「不落地」原則。開啟統計是使用者的主動選擇，不是預設。
        assertFalse("statsEnabled 預設必須是 false", defaults.statsEnabled)
    }

    @Test
    fun `預設是隱私模式而不是需要無障礙權限的模式`() {
        // Mode B 要無障礙服務。預設就要求那個權限，等於預設嚇跑使用者。
        assertEquals(DetectionMode.PRIVACY, defaults.mode)
    }

    @Test
    fun `預設不啟動`() {
        // 裝完就自動開始監看是不禮貌的。使用者要自己按下那個開關。
        assertFalse(defaults.enabled)
    }

    @Test
    fun `診斷通知預設關閉`() {
        // 它每 2 秒重畫一次通知，是調參用的東西。
        assertFalse(defaults.diagnosticsNotification)
    }

    // ---- 不該安靜失敗 ----

    @Test
    fun `服務被系統殺掉的提醒預設開啟`() {
        // 這一條跟其他設定相反，是刻意的。理由見 AppSettings 的註解：
        // 這個 app 的失效方式是靜默的 —— 開關還開著、設定都還在，但貓再也不出現。
        // 預設關閉等於預設讓使用者被蒙在鼓裡。
        assertTrue(defaults.survivalAlertEnabled)
    }

    // ---- 第一次打開這個 app 看到什麼 ----

    @Test
    fun `進階與診斷面板預設收起來`() {
        assertFalse(
            "調參滑桿跟當機紀錄不該是新使用者看到的第一個東西",
            defaults.advancedVisible,
        )
    }

    // ---- 單位換算 ----
    // 這裡錯了不會當機，只會讓門檻整整差 60 倍。

    @Test
    fun `EngineConfig 的秒與分都換算成毫秒`() {
        val settings = AppSettings(
            thresholdSeconds = 600,
            cooldownSeconds = 10,
            graceMinutes = 5,
        )
        val config = settings.toEngineConfig()
        assertEquals(600_000L, config.thresholdMs)
        assertEquals(10_000L, config.cooldownMs)
        assertEquals(300_000L, config.graceMs)
    }

    @Test
    fun `RhythmConfig 把 CV 百分比換回小數`() {
        // 存成整數是為了避開 DataStore 的 float 精度問題，讀出來要記得除回去。
        val config = AppSettings(rhythmMaxCvPercent = 55).toRhythmConfig()
        assertEquals(0.55, config.maxCoefficientOfVariation, 0.0001)
    }

    @Test
    fun `RhythmConfig 的秒換算成毫秒`() {
        val config = AppSettings(rhythmWindowSeconds = 90, rhythmSustainSeconds = 60).toRhythmConfig()
        assertEquals(90_000L, config.windowMs)
        assertEquals(60_000L, config.sustainMs)
    }
}
