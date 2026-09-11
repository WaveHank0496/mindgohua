package com.mindgohua.core

/**
 * Mode B 的滑動節奏分析（spec §4 第一版方法）。
 *
 * 輸入只有一串**時間戳**。沒有座標、沒有滑動距離、沒有畫面內容 —— 這是 spec §10 的硬界線。
 *
 * 原理：無意識刷動的特徵是「間隔短、變異小、密集、持續」；
 * 有意識使用則間隔長短混雜、有明顯停頓（讀了一則、回了一句、想了一下）。
 *
 * 實作：
 *  - ring buffer 只保留最近 W 秒內的時間戳。
 *  - 用 running sum / running sum of squares 增量維護標準差，
 *    所以每個事件更新是 O(1) 均攤，完全不吃電（spec §4 的複雜度要求）。
 *  - 密度 = 窗口內事件數換算成「每分鐘幾次」。
 *  - 變異 = 相鄰間隔的變異係數 CV = 標準差 / 平均。越小代表越規律。
 *  - 兩個條件同時成立**並持續 T 秒**才觸發。
 *
 * 和 SessionEngine 一樣是純邏輯、不碰 Android，時間由呼叫端傳入。
 */
class ScrollRhythmAnalyzer(config: RhythmConfig) {

    var config: RhythmConfig = config
        set(value) {
            field = value
            reset()
        }

    /** 窗口內的滑動時間戳（遞增）。 */
    private val timestamps = ArrayDeque<Long>()

    /** 相鄰間隔的 running sum / sum of squares，用來 O(1) 算標準差。 */
    private var intervalSum = 0.0
    private var intervalSumSq = 0.0
    private var intervalCount = 0

    /** 「符合無意識特徵」這個狀態是從什麼時候開始持續的。null = 目前不符合。 */
    private var qualifyingSinceMs: Long? = null

    fun reset() {
        timestamps.clear()
        intervalSum = 0.0
        intervalSumSq = 0.0
        intervalCount = 0
        qualifyingSinceMs = null
    }

    /**
     * 收到一個新的滑動事件。回傳當下的分析結果。
     * [RhythmResult.triggered] 為 true 時代表「已判定無意識刷動且持續達 T 秒」。
     */
    fun onScroll(atMs: Long): RhythmResult {
        // 時間倒退（極少見，例如時鐘被改）→ 整個重來，不要讓 running sum 被污染。
        val last = timestamps.lastOrNull()
        if (last != null && atMs < last) {
            reset()
        }

        timestamps.lastOrNull()?.let { prev ->
            val interval = (atMs - prev).toDouble()
            intervalSum += interval
            intervalSumSq += interval * interval
            intervalCount++
        }
        timestamps.addLast(atMs)

        evictOlderThan(atMs)
        return evaluate(atMs)
    }

    /**
     * 沒有新事件時也要呼叫（心跳），否則使用者停止滑動後，
     * 窗口裡的舊事件不會過期，密度會一直維持在高點。
     */
    fun onTick(atMs: Long): RhythmResult {
        evictOlderThan(atMs)
        return evaluate(atMs)
    }

    private fun evictOlderThan(nowMs: Long) {
        val cutoff = nowMs - config.windowMs
        while (timestamps.size >= 2 && timestamps.first() < cutoff) {
            val oldest = timestamps.removeFirst()
            val next = timestamps.first()
            val interval = (next - oldest).toDouble()
            intervalSum -= interval
            intervalSumSq -= interval * interval
            intervalCount--
        }
        if (timestamps.size == 1 && timestamps.first() < cutoff) {
            timestamps.clear()
            intervalSum = 0.0
            intervalSumSq = 0.0
            intervalCount = 0
        }
        if (intervalCount == 0) {
            // 浮點誤差歸零，避免長時間累積漂移。
            intervalSum = 0.0
            intervalSumSq = 0.0
        }
    }

    private fun evaluate(nowMs: Long): RhythmResult {
        val windowMinutes = config.windowMs / 60_000.0
        val density = if (windowMinutes > 0) timestamps.size / windowMinutes else 0.0

        val mean = if (intervalCount > 0) intervalSum / intervalCount else 0.0
        val variance = if (intervalCount > 0) {
            (intervalSumSq / intervalCount - mean * mean).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val cv = if (mean > 0.0) Math.sqrt(variance) / mean else Double.MAX_VALUE

        val hasEnoughData = intervalCount >= config.minIntervals
        val qualifies = hasEnoughData &&
            density >= config.minDensityPerMinute &&
            cv <= config.maxCoefficientOfVariation

        var triggered = false
        var sustainedMs = 0L

        if (qualifies) {
            val since = qualifyingSinceMs ?: nowMs.also { qualifyingSinceMs = it }
            sustainedMs = nowMs - since
            if (sustainedMs >= config.sustainMs) {
                triggered = true
                // 重新起算，避免在同一段刷動裡每個事件都觸發一次。
                // 真正的「多久不要再打斷」由 SessionEngine 的 GRACE 負責。
                qualifyingSinceMs = nowMs
            }
        } else {
            qualifyingSinceMs = null
        }

        return RhythmResult(
            triggered = triggered,
            densityPerMinute = density,
            coefficientOfVariation = if (hasEnoughData) cv else Double.NaN,
            sampleCount = timestamps.size,
            sustainedMs = sustainedMs,
            qualifying = qualifies,
        )
    }
}

/**
 * 調參用（spec §4：「做成可調參數，別寫死」）。
 * 預設值是憑常識抓的起點，**必須拿真實使用資料反覆測**再調。
 */
data class RhythmConfig(
    /** 統計窗口寬度 W。 */
    val windowMs: Long = 90_000L,
    /** 密度門檻：每分鐘至少幾次滑動。 */
    val minDensityPerMinute: Double = 18.0,
    /** 變異門檻：CV 低於此值代表節奏規律。 */
    val maxCoefficientOfVariation: Double = 0.55,
    /** 條件需連續成立多久 T。 */
    val sustainMs: Long = 60_000L,
    /** 樣本太少時不下判斷，避免剛開始滑就誤判。 */
    val minIntervals: Int = 10,
)

data class RhythmResult(
    val triggered: Boolean,
    val densityPerMinute: Double,
    val coefficientOfVariation: Double,
    val sampleCount: Int,
    val sustainedMs: Long,
    val qualifying: Boolean,
)
