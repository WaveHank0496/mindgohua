package com.mindgohua.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mindgohua.R
import com.mindgohua.core.DismissChoice
import com.mindgohua.core.DayBoundary
import com.mindgohua.core.EngineEffect
import com.mindgohua.core.SessionEngine
import com.mindgohua.core.SessionEvent
import com.mindgohua.detect.Detector
import com.mindgohua.detect.DetectorDebug
import com.mindgohua.detect.DetectorDebugBus
import com.mindgohua.detect.ScrollAccessibilityDetector
import com.mindgohua.detect.UsageStatsDetector
import com.mindgohua.overlay.CounterHud
import com.mindgohua.overlay.OverlayController
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import com.mindgohua.settings.SettingsStore
import com.mindgohua.settings.TargetApps
import com.mindgohua.stats.DailyStatsStore
import com.mindgohua.stats.GapInfo
import com.mindgohua.stats.SurvivalAlert
import com.mindgohua.stats.SurvivalLog
import com.mindgohua.ui.MainActivity
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 常駐的 Foreground Service：接偵測層、跑 Session Engine、驅動 overlay。
 *
 * 為什麼一定要 foreground service：ColorOS 對背景行程極兇（spec §7），
 * 沒有常駐通知的話幾分鐘內就會被回收。這是 Android 的規則，不是我們想常駐。
 *
 * 服務本身**不持有任何偵測資料**。Session Engine 的狀態只有「這段連續使用累計幾毫秒」，
 * 服務停止即消失，不寫檔、不上傳（沒有 INTERNET 權限，也上傳不了）。
 */
class GatekeeperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settingsStore: SettingsStore
    private lateinit var statsStore: DailyStatsStore
    private lateinit var survivalLog: SurvivalLog
    private var heartbeatJob: Job? = null
    private lateinit var overlay: OverlayController
    private lateinit var hud: CounterHud

    /**
     * 今日計數的記憶體快取。
     *
     * 不是每滑一項就寫磁碟 —— 那是每秒好幾次的 I/O。改成累積在這裡，
     * 定期 flush（[FLUSH_INTERVAL_MS]）以及在離開目標 app / 服務停止時強制寫入。
     */
    private val pendingCounts = mutableMapOf<String, Int>()
    private var todayTotal: Int = 0
    private var currentDay: LocalDate? = null
    private var flushJob: Job? = null

    private var engine: SessionEngine? = null
    private var detector: Detector? = null
    private var settings: AppSettings = AppSettings()
    private var settingsJob: Job? = null

    /** 常駐通知上次顯示的文字。相同就不重發，否則每 2 秒 notify 一次很浪費電。 */
    @Volatile
    private var lastNotificationText: String? = null

    /**
     * 螢幕關掉時要主動結束 session。
     * UsageEvents 在鎖屏時不保證送出 ACTIVITY_PAUSED，少了這一刀，
     * 手機放口袋也會被算成一直在滑，然後解鎖瞬間跳出一隻貓。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    when (val d = detector) {
                        is UsageStatsDetector -> d.forceLeaveTarget(::handleSignal)
                        is ScrollAccessibilityDetector -> d.forceLeaveTarget(::handleSignal)
                        else -> Unit
                    }
                    hideOverlayOnMain()
                    // 先結束 session、再停輪詢。順序不能反 ——
                    // 輪詢先停的話，forceLeaveTarget 之後就沒有下一輪把狀態送出去。
                    detector?.setScreenOn(false)
                }

                Intent.ACTION_SCREEN_ON -> detector?.setScreenOn(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        statsStore = DailyStatsStore(applicationContext)
        survivalLog = SurvivalLog(applicationContext)
        overlay = OverlayController(applicationContext)
        hud = CounterHud(applicationContext)
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                // 新增 SCREEN_ON：螢幕關閉時輪詢會完全停止，沒有這個就再也不會恢復。
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(notificationText(idle = true))

        if (intent?.action == ACTION_STOP) {
            stopEverything(userInitiated = true)
            stopSelf()
            return START_NOT_STICKY
        }

        startHeartbeat()

        if (settingsJob == null) {
            settingsJob = scope.launch {
                // 設定改了就熱套用，不用重啟服務。
                settingsStore.settings.distinctUntilChanged().collect { updated ->
                    val previous = settings
                    val wasEnabled = settings.enabled && detector != null
                    val modeChanged = updated.mode != settings.mode
                    settings = updated
                    when {
                        !updated.enabled -> {
                            stopEverything(userInitiated = true)
                            stopSelf()
                        }

                        !wasEnabled || modeChanged -> startDetection(updated)

                        else -> {
                            detector?.onSettingsChanged(updated)
                            engine?.process(SessionEvent.ConfigChanged(updated.toEngineConfig()))
                            if (statsSettingsChanged(previous, updated)) startCountingIfNeeded(updated)
                        }
                    }
                }
            }
        }

        // START_STICKY：被系統殺掉後盡量重啟。在 ColorOS 上這不保證有效，
        // 真正的解法是引導使用者開自啟動與電池白名單（見 README 的存活測試）。
        return START_STICKY
    }

    private fun startDetection(settings: AppSettings) {
        detector?.stop()
        engine = SessionEngine(settings.toEngineConfig())
        detector = when (settings.mode) {
            DetectionMode.PRIVACY -> UsageStatsDetector(applicationContext, scope, settings)
            DetectionMode.FOCUS -> ScrollAccessibilityDetector(applicationContext, scope, settings).apply {
                onItemAdvance = { pkg, delta -> onItemsAdvanced(pkg, delta) }
            }
        }
        detector?.start(::handleSignal)
        startCountingIfNeeded(settings)
        Log.i(TAG, "偵測啟動：mode=${settings.mode}")
    }

    /**
     * 存活心跳（spec §8 第 6 條的驗收工具）。
     *
     * 每分鐘蓋一次時間戳。被 ColorOS 殺掉時時間戳就停住，
     * 下次啟動一比對就知道死了多久 —— 不需要 adb 盯著，
     * 手機也才能拔掉電源進入真正的深度休眠。
     */
    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            val gap = survivalLog.onServiceStart()
            if (gap.detected) {
                Log.w(TAG, "偵測到服務中斷過：${gap.durationMs} ms，重開機=${gap.wasReboot}")
                maybeAlertUser(gap)
            }
            while (isActive) {
                delay(SurvivalLog.HEARTBEAT_INTERVAL_MS)
                survivalLog.heartbeat()
            }
        }
    }

    /**
     * 服務被系統殺掉時主動通知使用者。
     *
     * 在此之前，這個事實只寫進 `Log.w` —— 而 logcat 只有插著 USB 線的開發者
     * 看得到。對真正的使用者來說，這個 app 的失效方式是：開關還是開的、
     * 設定都還在，但貓再也不出現，而且沒有任何提示。
     *
     * 判斷「該不該提醒」的規則全部在 [SurvivalAlert]（純函式、有單元測試），
     * 這裡只負責把結果變成一則通知。
     */
    private suspend fun maybeAlertUser(gap: GapInfo) {
        if (!settings.survivalAlertEnabled) return
        val should = SurvivalAlert.shouldNotify(
            hadGap = gap.detected,
            gapDurationMs = gap.durationMs,
            wasReboot = gap.wasReboot,
            alreadyNotifiedGapTo = gap.alreadyAlertedGapTo,
            gapToMs = gap.gapToMs,
        )
        if (!should) return

        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(ALERT_NOTIFICATION_ID, buildAlertNotification(SurvivalAlert.message(gap.durationMs)))
        }.onFailure { Log.w(TAG, "中斷提醒發送失敗：${it.message}") }

        // 不論通知有沒有成功送出去都要記 —— 失敗多半是權限被關掉，
        // 那種情況重試一百次也不會成功，只會在每次重啟時多做一次白工。
        survivalLog.markAlerted(gap.gapToMs)
    }

    /**
     * 中斷提醒的通知。
     *
     * 跟常駐通知的差別：**不是 ongoing**（使用者要能滑掉）、**不靜音**、
     * 用較高的重要性，而且 [NotificationCompat.setAutoCancel] 讓它點完就消失。
     * 常駐通知刻意設計成不打擾人，但這一則的目的正好相反 —— 它必須被看見。
     */
    private fun buildAlertNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("貓剛才沒在看著")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    // ---- 每日計數（spec §0.1 的例外，見 DailyStatsStore 的說明）----

    private fun startCountingIfNeeded(settings: AppSettings) {
        flushJob?.cancel()
        if (!settings.statsEnabled || settings.mode != DetectionMode.FOCUS) {
            hideHud()
            return
        }
        flushJob = scope.launch {
            currentDay = today()
            todayTotal = statsStore.countsOn(currentDay!!).first().values.sum()
            statsStore.prune(currentDay!!, settings.statsRetentionDays)
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushCounts()
            }
        }
    }

    private fun onItemsAdvanced(packageName: String, delta: Int) {
        if (!settings.statsEnabled) return
        synchronized(pendingCounts) {
            pendingCounts[packageName] = (pendingCounts[packageName] ?: 0) + delta
            todayTotal += delta
        }
        if (settings.hudEnabled) hud.update(todayTotal)
    }

    private suspend fun flushCounts() {
        val day = today()
        // 跨過換日時間 → 先把累積的寫進「昨天」，再把今日計數歸零。
        val writeDay = currentDay ?: day
        val snapshot = synchronized(pendingCounts) {
            val copy = pendingCounts.toMap()
            pendingCounts.clear()
            copy
        }
        snapshot.forEach { (pkg, count) -> statsStore.add(writeDay, pkg, count) }

        if (day != currentDay) {
            currentDay = day
            todayTotal = statsStore.countsOn(day).first().values.sum()
            if (settings.hudEnabled && hud.isShowing) hud.update(todayTotal)
            statsStore.prune(day, settings.statsRetentionDays)
            Log.i(TAG, "換日，今日計數重新起算")
        }
    }

    private fun today(): LocalDate =
        DayBoundary.dayOf(System.currentTimeMillis(), settings.dayBoundaryHour)

    private fun showHudIfNeeded() {
        if (!settings.hudEnabled || !settings.statsEnabled) return
        if (settings.mode != DetectionMode.FOCUS) return
        scope.launch(Dispatchers.Main) { hud.show(todayTotal) }
    }

    private fun hideHud() {
        scope.launch(Dispatchers.Main) { hud.hide() }
    }

    /**
     * 偵測層的所有訊號都走這裡進 Session Engine，再把 effect 兌現成畫面動作。
     *
     * `@Synchronized` 是必要的：訊號來自至少四個地方（Mode A 的輪詢 coroutine、
     * Mode B 的 scroll collector 與其內部的前景輪詢、螢幕關閉的 BroadcastReceiver、
     * overlay 解除的 Main thread），而 [SessionEngine] 是個沒有加鎖的狀態機。
     */
    @Synchronized
    private fun handleSignal(event: SessionEvent) {
        // HUD 只在目標 app 前景時出現；離開時順便把累積的計數寫進磁碟，
        // 免得服務被 ColorOS 殺掉時把最後那段滑動弄丟。
        if (event is SessionEvent.ForegroundChanged) {
            if (event.isTarget) {
                showHudIfNeeded()
            } else {
                hideHud()
                if (settings.statsEnabled) scope.launch { flushCounts() }
            }
        }

        val effects = engine?.process(event) ?: return
        for (effect in effects) {
            when (effect) {
                is EngineEffect.Interrupt -> showOverlay(effect)
                EngineEffect.HideInterrupt -> hideOverlayOnMain()
                is EngineEffect.SessionUpdated -> updateNotification(effect.elapsedMs, effect.packageName)
            }
        }
    }

    private fun showOverlay(effect: EngineEffect.Interrupt) {
        scope.launch(Dispatchers.Main) {
            overlay.show(
                elapsedMs = effect.elapsedMs,
                packageName = effect.packageName,
                reason = effect.reason,
                unlockDelaySeconds = settings.unlockDelaySeconds,
                graceMinutes = settings.graceMinutes,
                onDismiss = { choice -> onOverlayDismissed(choice) },
            )
        }
    }

    private fun onOverlayDismissed(choice: DismissChoice) {
        if (engine == null) {
            // 測試用的 overlay：沒有 session 在跑，直接收掉就好。
            hideOverlayOnMain()
            return
        }
        handleSignal(SessionEvent.InterruptDismissed(SystemClock.elapsedRealtime(), choice))
    }

    /**
     * 收掉 overlay。**必須是 `Main.immediate`，不能是 `Main`。**
     *
     * 差別在服務被銷毀的那條路上會要命：`onDestroy` 本身就跑在主執行緒，
     * 它先呼叫 `stopEverything()`（裡面排了一個 hide），再呼叫 `scope.cancel()`。
     * 用 `Dispatchers.Main` 的話那個 hide 只是被「排進佇列」，
     * 接著就被同步取消 —— **`removeView` 永遠不會執行**。
     *
     * 而留在畫面上的那張 view 吃掉所有觸控、攔截返回鍵、還帶
     * `FLAG_KEEP_SCREEN_ON`，上面兩顆按鈕也是死的（engine 已經是 null）。
     * 使用者唯一的出路是「強制停止」或重開機。
     *
     * `immediate` 在已經身處主執行緒時直接同步執行，這條路就被封死了。
     */
    private fun hideOverlayOnMain() {
        scope.launch(Dispatchers.Main.immediate) { overlay.hide() }
    }

    private fun statsSettingsChanged(a: AppSettings, b: AppSettings): Boolean =
        a.statsEnabled != b.statsEnabled ||
            a.hudEnabled != b.hudEnabled ||
            a.dayBoundaryHour != b.dayBoundaryHour ||
            a.statsRetentionDays != b.statsRetentionDays

    /**
     * @param userInitiated 使用者主動關掉開關。這種停止**不算**「被殺」，
     *   不該污染存活紀錄 —— 否則你自己關一次就會多出一筆假的中斷。
     *   系統把行程殺掉時本方法根本不會被呼叫，心跳自然斷掉，那才是真的中斷。
     */
    private fun stopEverything(userInitiated: Boolean = false) {
        flushJob?.cancel()
        flushJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (userInitiated) {
            CoroutineScope(Dispatchers.IO).launch { survivalLog.onCleanStop() }
        }
        // 停止前把還沒寫入的計數存起來，不然使用者剛滑的那幾十項就沒了。
        if (settings.statsEnabled) {
            val snapshot = synchronized(pendingCounts) {
                val copy = pendingCounts.toMap(); pendingCounts.clear(); copy
            }
            if (snapshot.isNotEmpty()) {
                val day = currentDay ?: today()
                // 用獨立 scope：此時 service 的 scope 隨時可能被取消。
                CoroutineScope(Dispatchers.IO).launch {
                    snapshot.forEach { (pkg, count) -> statsStore.add(day, pkg, count) }
                }
            }
        }
        hideHud()
        detector?.stop()
        detector = null
        engine?.process(SessionEvent.Stopped)
        engine = null
        // 診斷數字只存在記憶體，服務停掉就一起消失。
        DetectorDebugBus.clear()
        lastNotificationText = null
        hideOverlayOnMain()
    }

    override fun onDestroy() {
        stopEverything()
        runCatching { unregisterReceiver(screenReceiver) }
        // 最後一道保險：scope 取消之後就不會再有任何 coroutine 執行，
        // 所以在那之前同步把 view 拔掉。onDestroy 本來就在主執行緒。
        // 貓留在畫面上是這個 app 唯一會逼使用者重開機的失敗方式，
        // 值得用兩行重複的程式碼把它封死。
        runCatching { overlay.hide() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 常駐通知 ----

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 套件名 → 顯示名稱，附一層快取。
     *
     * 需要快取是因為常駐通知在計時期間會反覆重畫，每次都去問 PackageManager
     * 等於在固定頻率做 IPC。而套件名對應的名稱幾乎不會變 —— 只有使用者
     * 重新安裝或改語言時才會，那兩種情況服務本來就會重啟。
     */
    private val labelCache = mutableMapOf<String, String>()

    private fun appLabel(packageName: String?): String {
        if (packageName == null) return TargetApps.labelOf(null)
        return labelCache.getOrPut(packageName) {
            TargetApps.labelOf(this, packageName)
        }
    }

    private fun updateNotification(elapsedMs: Long, packageName: String?) {
        val diagnostics = DetectorDebugBus.state.value?.takeIf { settings.diagnosticsNotification }
        // 只顯示到分鐘：秒數會讓通知每 2 秒重畫一次，看不出差別卻一直在耗電。
        // 診斷模式例外 —— 那時候「每 2 秒更新」正是我們要的。
        val text = when {
            diagnostics != null -> formatDiagnostics(diagnostics, elapsedMs, packageName)
            elapsedMs <= 0 -> notificationText(idle = true)
            else -> {
                val minutes = elapsedMs / 60_000
                val label = appLabel(packageName)
                if (minutes > 0) "$label：已連續 $minutes 分鐘" else "$label：計時中"
            }
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notificationText(idle: Boolean): String =
        if (idle) "貓在待命，沒有在讀你的畫面" else "監看中"

    /**
     * 診斷模式的通知文字。
     * 目的是讓你「一邊滑 IG 一邊下拉通知欄」就能看到判斷依據，不用切回設定頁。
     *
     * Mode A 顯示秒數與門檻：平常的通知只到分鐘（免得每 2 秒重畫一次耗電），
     * 但驗收 session 邊界（切出去再切回來計時有沒有被洗掉）時，非看到秒不可。
     */
    private fun formatDiagnostics(d: DetectorDebug, elapsedMs: Long, packageName: String?): String {
        if (d.mode != DetectionMode.FOCUS) {
            val threshold = settings.thresholdSeconds
            return when {
                elapsedMs > 0 -> "${appLabel(packageName)} ${elapsedMs / 1000} 秒 / 門檻 $threshold 秒"
                d.inTargetApp -> "計時中 0 秒 / 門檻 $threshold 秒"
                else -> "不在目標 app · 計時已歸零"
            }
        }
        if (!d.accessibilityConnected) return "診斷：無障礙服務未連線"
        if (!d.inTargetApp) return "診斷：不在目標 app（事件累計 ${d.totalScrollEvents}）"

        // 一律用 Locale.US 格式化數字。不指定 Locale 的話會跟著系統語言走，
        // 而在阿拉伯文等語系下會輸出非 ASCII 的數字字元（٠١٢…），
        // 讓這串診斷文字變得完全看不懂。這裡是給人讀數值用的，不是在地化內容。
        val cv = if (d.coefficientOfVariation.isNaN()) "—" else String.format(Locale.US, "%.2f", d.coefficientOfVariation)
        val status = if (d.qualifying) "符合 ${d.sustainedSeconds}s" else "未達標"
        return "密度 ${String.format(Locale.US, "%.0f", d.densityPerMinute)}/分 · CV $cv · $status · 觸發 ${d.triggerCount}"
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("mindgohua")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // 中斷提醒用獨立頻道。常駐通知是 IMPORTANCE_LOW 且靜音的
        // （它只是 Android 的形式要求，不該一直吵人），但「服務被系統殺掉」
        // 是使用者真的需要看到的事 —— 共用頻道會讓它一起被壓低到看不見。
        // 分開也讓使用者能只關掉其中一種。
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.notif_alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_alert_channel_desc)
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(alertChannel)
    }

    companion object {
        private const val TAG = "GatekeeperService"
        private const val CHANNEL_ID = "mindgohua_ongoing"
        private const val NOTIFICATION_ID = 1001

        /** 中斷提醒。ID 必須與常駐通知不同，否則兩者會互相覆蓋。 */
        private const val ALERT_CHANNEL_ID = "mindgohua_alert"
        private const val ALERT_NOTIFICATION_ID = 1002

        /** 每日計數多久寫一次磁碟。太密會一直做 I/O，太疏會在服務被殺時丟資料。 */
        private const val FLUSH_INTERVAL_MS = 15_000L

        const val ACTION_STOP = "com.mindgohua.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GatekeeperService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatekeeperService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
        }
    }
}
