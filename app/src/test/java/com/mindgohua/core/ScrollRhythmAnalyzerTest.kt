package com.mindgohua.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * spec §4 演算法的測試。重點在兩件事：
 *  1. 「規律密集持續」的節奏會觸發，「有停頓、長短混雜」的不會。
 *  2. 增量維護的標準差（running sum / sum of squares）算出來的 CV
 *     要和老老實實重算一遍的結果一致 —— 這是 O(1) 優化最容易出錯的地方。
 */
class ScrollRhythmAnalyzerTest {

    private val config = RhythmConfig(
        windowMs = 90_000L,
        minDensityPerMinute = 18.0,
        maxCoefficientOfVariation = 0.55,
        sustainMs = 60_000L,
        minIntervals = 10,
    )

    @Test
    fun `完全規律的高頻滑動在持續足夠久後觸發`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var t = 0L
        var firstTriggerAt: Long? = null

        while (t <= 120_000L) {
            val result = analyzer.onScroll(t)
            if (result.triggered && firstTriggerAt == null) firstTriggerAt = t
            t += 500L
        }

        assertTrue("規律刷動應該要觸發", firstTriggerAt != null)
        // 推導：密度門檻 18/分 × 1.5 分窗口 = 需要 27 個事件，即 t = 13s 才開始符合特徵；
        // 再持續 60s → 73s 觸發。留一點餘裕避免測試對常數過度敏感。
        assertTrue("觸發太早：$firstTriggerAt", firstTriggerAt!! >= 70_000L)
        assertTrue("觸發太晚：$firstTriggerAt", firstTriggerAt <= 76_000L)
    }

    @Test
    fun `樣本不足時不下判斷`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var last: RhythmResult? = null
        for (i in 0 until 5) {
            last = analyzer.onScroll(i * 500L)
        }
        assertFalse(last!!.qualifying)
        assertFalse(last.triggered)
    }

    @Test
    fun `有停頓的斷續使用不會觸發`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        // 模擬「看一則、停下來讀、再滑」：間隔在 0.4 秒到 12 秒之間跳
        val rng = Random(42)
        var t = 0L
        var triggered = false
        repeat(400) {
            val result = analyzer.onScroll(t)
            if (result.triggered) triggered = true
            t += if (rng.nextInt(3) == 0) rng.nextLong(6_000, 12_000) else rng.nextLong(400, 900)
        }
        // 值得注意：這種使用模式的「密度」其實會超過門檻（約 18.7 次/分），
        // 擋下來的是變異係數（約 1.26 ≫ 0.55）。這正是 spec §4 要同時看兩個特徵的原因 ——
        // 只看密度會把「認真但滑很快」的人也一起打斷。
        assertFalse("斷續使用被誤判成無意識刷動", triggered)
    }

    @Test
    fun `停手之後密度會隨窗口老化掉下來`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var t = 0L
        repeat(100) {
            analyzer.onScroll(t)
            t += 500L
        }
        assertTrue(analyzer.onTick(t).qualifying)

        // 停手 2 分鐘，窗口內應該一筆都不剩
        val after = analyzer.onTick(t + 120_000L)
        assertFalse(after.qualifying)
        assertEquals(0, after.sampleCount)
        assertEquals(0.0, after.densityPerMinute, 1e-9)
    }

    @Test
    fun `增量標準差與直接重算的結果一致`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        val rng = Random(7)
        val all = mutableListOf<Long>()
        var t = 0L

        // 跑得夠久，確保窗口有反覆被 evict
        repeat(500) {
            all += t
            analyzer.onScroll(t)
            t += rng.nextLong(300, 2_500)
        }

        val now = all.last()
        val result = analyzer.onTick(now)

        val inWindow = all.filter { it >= now - config.windowMs }
        val intervals = inWindow.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = intervals.average()
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        val expectedCv = sqrt(variance) / mean

        assertEquals(inWindow.size, result.sampleCount)
        assertEquals(expectedCv, result.coefficientOfVariation, 1e-6)
    }

    @Test
    fun `觸發後會重新起算不會每個事件都觸發`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var t = 0L
        val triggers = mutableListOf<Long>()
        while (t <= 200_000L) {
            if (analyzer.onScroll(t).triggered) triggers += t
            t += 500L
        }
        // 200 秒內最多兩三次，而不是每 0.5 秒一次
        assertTrue("觸發次數過多：${triggers.size}", triggers.size in 1..3)
    }

    @Test
    fun `改參數會清空狀態`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var t = 0L
        repeat(50) {
            analyzer.onScroll(t)
            t += 500L
        }
        analyzer.config = config.copy(minDensityPerMinute = 5.0)
        assertEquals(0, analyzer.onTick(t).sampleCount)
    }

    @Test
    fun `時鐘倒退不會污染統計`() {
        val analyzer = ScrollRhythmAnalyzer(config)
        var t = 100_000L
        repeat(30) {
            analyzer.onScroll(t)
            t += 500L
        }
        val result = analyzer.onScroll(1_000L)
        assertEquals(1, result.sampleCount)
        assertFalse(result.qualifying)
    }
}
