package com.mindgohua.detect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mindgohua.core.SessionEvent
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mode A：Privacy Mode（spec §1）。
 *
 * 資料來源只有 [UsageStatsManager.queryEvents] —— 事件流裡只有
 * 「哪個 package 進入/離開前景、發生時間」。**看不到畫面上的任何內容**：
 * 分不出你在滑 Reels 還是在回訊息，只能知道你在這個 app 待了多久。
 *
 * 讀到的 package name 判斷完就丟，只把「是不是目標 app」這個 boolean 往下游送。
 */
class UsageStatsDetector(
    context: Context,
    private val scope: CoroutineScope,
    private var settings: AppSettings,
    /**
     * Mode B 內部會組合一個本類別當作前景判斷，那時 debug 由外層統一發佈，
     * 這裡就不要跟著發、免得兩邊互相覆蓋。
     */
    private val publishDebug: Boolean = true,
) : Detector {

    override val mode: DetectionMode = DetectionMode.PRIVACY

    private val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private var pollJob: Job? = null

    /**
     * 留著 [start] 傳進來的回呼，螢幕重新亮起時要用它把輪詢接回來。
     * 這是唯一需要跨越「暫停/恢復」保存的東西。
     */
    private var signalSink: ((SessionEvent) -> Unit)? = null

    /** 螢幕是否亮著。預設 true —— 服務啟動時螢幕通常是亮的（使用者剛按下開關）。 */
    @Volatile
    private var screenOn: Boolean = true

    /** 上次查詢到的時間點（wall clock），下次從這裡接著查。 */
    private var lastQueryEndWallMs: Long = 0L

    /** 目前判定的前景 package。這是**進程內的暫時變數**，不寫入任何儲存。 */
    private var foregroundPackage: String? = null

    private var lastReportedIsTarget: Boolean? = null

    override fun start(onSignal: (SessionEvent) -> Unit) {
        stop()
        signalSink = onSignal
        lastQueryEndWallMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        startPolling(onSignal)
    }

    private fun startPolling(onSignal: (SessionEvent) -> Unit) {
        if (pollJob != null) return
        pollJob = scope.launch {
            // 開機先看一眼目前誰在前景，否則要等到下一次切換才知道。
            primeCurrentForeground(onSignal)
            while (isActive) {
                pump(onSignal)
                onSignal(SessionEvent.Tick(nowElapsed()))
                emitDebug()

                // 間隔依狀態而變，不再是固定的 2 秒。null 代表螢幕已關 ——
                // 這時直接結束迴圈，等 setScreenOn(true) 再重新啟動，
                // 而不是用一個長 delay 空轉（那仍然會週期性喚醒行程）。
                val delayMs = PollingPolicy.nextDelayMs(
                    screenOn = screenOn,
                    inTargetApp = lastReportedIsTarget == true,
                ) ?: break
                delay(delayMs)
            }
        }
    }

    /**
     * 螢幕關閉時**完全停掉輪詢**，亮起時重新開始。
     *
     * 這是這個 app 最大的一筆無謂耗電：原本不論螢幕開關都固定每 2 秒查一次，
     * 一天關螢幕 16 小時就是約 28,800 次毫無意義的跨行程查詢。
     *
     * 注意這裡只負責「要不要查」。「螢幕關了要結束 session」是另一件事，
     * 由 GatekeeperService 呼叫 [forceLeaveTarget] 處理 —— 兩者不能混為一談，
     * 否則螢幕一關，計時狀態會因為輪詢停止而卡在原地而不是歸零。
     */
    override fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        if (on) {
            // 停掉的期間沒有查詢，事件會累積在系統那邊。
            // 從現在往回看一小段，避免把剛剛的切換整個漏掉。
            lastQueryEndWallMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
            signalSink?.let { startPolling(it) }
        } else {
            pollJob?.cancel()
            pollJob = null
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        signalSink = null
        foregroundPackage = null
        lastReportedIsTarget = null
    }

    override fun onSettingsChanged(settings: AppSettings) {
        val targetsChanged = settings.targetPackages != this.settings.targetPackages
        this.settings = settings
        if (targetsChanged) {
            // 目標清單變了，下一輪重新判斷 target-ness。
            lastReportedIsTarget = null
        }
    }

    override fun debugState(): String = "Mode A · 前景是否為目標：${isInTarget()}"

    private fun isInTarget(): Boolean =
        foregroundPackage?.let { it in settings.targetPackages } ?: false

    private fun emitDebug() {
        if (!publishDebug) return
        DetectorDebugBus.publish(
            DetectorDebug(mode = DetectionMode.PRIVACY, inTargetApp = isInTarget()),
        )
    }

    /**
     * 外部（例如螢幕關閉）強制標記「已離開目標 app」。
     * 螢幕關掉時 UsageEvents 不一定會送出 ACTIVITY_PAUSED，必須由服務端補這一刀，
     * 否則手機躺在口袋裡也會被算成一直在滑。
     */
    fun forceLeaveTarget(onSignal: (SessionEvent) -> Unit) {
        foregroundPackage = null
        if (lastReportedIsTarget != false) {
            lastReportedIsTarget = false
            onSignal(SessionEvent.ForegroundChanged(isTarget = false, packageName = null, atMs = nowElapsed()))
        }
        emitDebug()
    }

    private fun primeCurrentForeground(onSignal: (SessionEvent) -> Unit) {
        val now = System.currentTimeMillis()
        val events = runCatching { usageStats.queryEvents(now - INITIAL_LOOKBACK_MS, now) }.getOrNull() ?: return
        var latestPkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
            }
        }
        foregroundPackage = latestPkg
        report(latestPkg, nowElapsed(), onSignal)
        lastQueryEndWallMs = now
    }

    private fun pump(onSignal: (SessionEvent) -> Unit) {
        val now = System.currentTimeMillis()
        val begin = lastQueryEndWallMs.coerceAtMost(now)
        val events = runCatching { usageStats.queryEvents(begin, now) }
            .onFailure { Log.w(TAG, "queryEvents 失敗（多半是還沒授權 PACKAGE_USAGE_STATS）") }
            .getOrNull()
        lastQueryEndWallMs = now
        if (events == null) return

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    foregroundPackage = pkg
                    report(pkg, toElapsed(event.timeStamp), onSignal)
                }

                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    if (pkg == foregroundPackage) {
                        // 只知道它走了，還不知道誰接手。先當作「不在目標 app」，
                        // 下一個 ACTIVITY_RESUMED 會馬上更正。
                        foregroundPackage = null
                        report(null, toElapsed(event.timeStamp), onSignal)
                    }
                }
            }
        }
    }

    private fun report(pkg: String?, atMs: Long, onSignal: (SessionEvent) -> Unit) {
        val isTarget = pkg != null && pkg in settings.targetPackages
        // spec §0.3 行為自限：非目標 app 就到此為止，不做任何進一步處理。
        // 目標 app 之間互跳（IG → YouTube）不重發事件，由 Session Engine 當成同一段連續使用。
        if (isTarget == lastReportedIsTarget) return
        lastReportedIsTarget = isTarget
        onSignal(
            SessionEvent.ForegroundChanged(
                isTarget = isTarget,
                packageName = if (isTarget) pkg else null,
                atMs = atMs,
            )
        )
    }

    /** UsageEvents 給的是 wall clock；Session Engine 要單調時鐘，這裡換算。 */
    private fun toElapsed(wallMs: Long): Long =
        (wallMs + (SystemClock.elapsedRealtime() - System.currentTimeMillis())).coerceAtLeast(0L)

    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "UsageStatsDetector"

        // 輪詢間隔已移到 PollingPolicy —— 它會依螢幕狀態與是否在目標 app 而變，
        // 不再是一個固定值。
        const val INITIAL_LOOKBACK_MS = 60_000L
    }
}
