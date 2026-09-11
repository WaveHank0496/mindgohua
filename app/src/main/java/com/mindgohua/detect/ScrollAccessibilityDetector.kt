package com.mindgohua.detect

import android.content.Context
import android.os.SystemClock
import com.mindgohua.core.ItemAdvanceCounter
import com.mindgohua.core.RhythmResult
import com.mindgohua.core.ScrollRhythmAnalyzer
import com.mindgohua.core.SessionEvent
import com.mindgohua.service.ScrollSignalBus
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mode B：Focus Mode（spec §1、§4）。
 *
 * 訊號來自 [com.mindgohua.service.ScrollWatchService]，而那個 service 只拿
 * `TYPE_VIEW_SCROLLED` 事件的 **package name + 時間戳**，
 * `canRetrieveWindowContent` 在 manifest 明確設為 false —— 節點樹、文字內容一概不碰。
 *
 * 這個 detector 本身仍然要做 Mode A 的前景判斷（它需要知道目標 app 在不在前景），
 * 所以內部組合一個 [UsageStatsDetector] 負責 session 邊界，自己只負責「無意識刷動」這個額外訊號。
 *
 * 演算法（[ScrollRhythmAnalyzer]）已實作並有單元測試，但**參數尚未用真實資料校準**，
 * 所以預設 Mode 仍是 A。校準流程見 TESTING.md。
 */
class ScrollAccessibilityDetector(
    context: Context,
    private val scope: CoroutineScope,
    private var settings: AppSettings,
) : Detector {

    override val mode: DetectionMode = DetectionMode.FOCUS

    /** session 邊界仍由前景事件決定；Mode B 只是多了一個觸發來源。 */
    private val foregroundDelegate = UsageStatsDetector(context, scope, settings, publishDebug = false)

    private val analyzer = ScrollRhythmAnalyzer(settings.toRhythmConfig())

    /** 每個 app 分開數：Reels 的「一項」是一部影片，首頁動態的「一項」是一則貼文，意義不同。 */
    private val counters = mutableMapOf<String, ItemAdvanceCounter>()

    /**
     * 滑過新項目時回報給服務層（由它決定要不要寫進磁碟、要不要更新 HUD）。
     * 偵測層本身不碰儲存 —— 它的職責只有「產生訊號」。
     */
    var onItemAdvance: ((packageName: String, delta: Int) -> Unit)? = null

    private var collectJob: Job? = null
    private var tickJob: Job? = null

    private var inTargetApp = false

    /**
     * 最後一筆分析結果。離開目標 app 時**刻意保留**不歸零 ——
     * 你滑完 IG 回到設定頁要看得到剛才的數字，否則沒辦法調參。
     */
    private var lastResult: RhythmResult? = null

    private var totalScrollEvents = 0
    private var triggerCount = 0

    override fun start(onSignal: (SessionEvent) -> Unit) {
        stop()

        foregroundDelegate.start { event ->
            if (event is SessionEvent.ForegroundChanged) {
                inTargetApp = event.isTarget
                // 離開目標 app 就把節奏窗口清空 —— 讀到的時間戳一筆都不留（spec §0.1）。
                // 注意清的是 analyzer 內的時間戳，不是 lastResult 那組統計量。
                if (!event.isTarget) {
                    analyzer.reset()
                    counters.values.forEach { it.onLeaveApp() }
                }
                publishDebug()
            }
            onSignal(event)
        }

        collectJob = scope.launch {
            ScrollSignalBus.scrollEvents.collect { signal ->
                // 行為自限：不是目標 app 的捲動，直接丟掉，連統計都不進。
                if (!inTargetApp) return@collect
                if (signal.packageName !in settings.targetPackages) return@collect

                totalScrollEvents++

                // 數「滑過幾項」。只用 index 欄位，不碰內容。
                val counter = counters.getOrPut(signal.packageName) { ItemAdvanceCounter() }
                val advanced = counter.onScroll(signal.fromIndex, signal.toIndex, signal.itemCount)
                if (advanced > 0) onItemAdvance?.invoke(signal.packageName, advanced)

                val result = analyzer.onScroll(signal.atElapsedMs)
                lastResult = result
                if (result.triggered) {
                    triggerCount++
                    onSignal(SessionEvent.UnconsciousScrollDetected(signal.atElapsedMs))
                }
                publishDebug()
            }
        }

        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                // 沒有新事件時也要讓窗口老化，否則停手後密度不會掉下來。
                // 不在目標 app 時不更新統計量，讓畫面上停留在離開前的最後一筆。
                if (inTargetApp) {
                    lastResult = analyzer.onTick(SystemClock.elapsedRealtime())
                }
                publishDebug()
            }
        }
    }

    override fun stop() {
        collectJob?.cancel(); collectJob = null
        tickJob?.cancel(); tickJob = null
        foregroundDelegate.stop()
        analyzer.reset()
        counters.clear()
        inTargetApp = false
        lastResult = null
        totalScrollEvents = 0
        triggerCount = 0
    }

    override fun onSettingsChanged(settings: AppSettings) {
        this.settings = settings
        foregroundDelegate.onSettingsChanged(settings)
        analyzer.config = settings.toRhythmConfig()
    }

    fun forceLeaveTarget(onSignal: (SessionEvent) -> Unit) {
        analyzer.reset()
        inTargetApp = false
        publishDebug()
        foregroundDelegate.forceLeaveTarget(onSignal)
    }

    private fun publishDebug() {
        val r = lastResult
        // 取「最近有動靜」的那個 counter 來顯示原始值；總數則是全部相加。
        val active = counters.values.lastOrNull { it.lastToIndex >= 0 }
        DetectorDebugBus.publish(
            DetectorDebug(
                mode = DetectionMode.FOCUS,
                inTargetApp = inTargetApp,
                accessibilityConnected = ScrollSignalBus.isServiceConnected,
                totalScrollEvents = totalScrollEvents,
                sampleCount = r?.sampleCount ?: 0,
                densityPerMinute = r?.densityPerMinute ?: 0.0,
                coefficientOfVariation = r?.coefficientOfVariation ?: Double.NaN,
                qualifying = r?.qualifying ?: false,
                sustainedSeconds = (r?.sustainedMs ?: 0L) / 1000,
                triggerCount = triggerCount,
                frozen = !inTargetApp && r != null,
                indexDataAvailable = counters.values.any { it.sawIndexData },
                lastToIndex = active?.lastToIndex ?: -1,
                lastItemCount = active?.lastKnownItemCount ?: -1,
                itemsAdvanced = counters.values.sumOf { it.total },
            )
        )
    }

    override fun debugState(): String {
        val r = lastResult ?: return "Mode B · 尚無資料"
        val cv = if (r.coefficientOfVariation.isNaN()) "—" else String.format("%.2f", r.coefficientOfVariation)
        return "Mode B · 密度 ${String.format("%.1f", r.densityPerMinute)}/分 · CV $cv · " +
            (if (r.qualifying) "持續 ${r.sustainedMs / 1000}s" else "未達標")
    }

    private companion object {
        const val TICK_MS = 2_000L
    }
}
