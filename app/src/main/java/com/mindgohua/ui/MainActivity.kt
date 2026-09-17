package com.mindgohua.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mindgohua.core.InterruptReason
import com.mindgohua.diagnostics.CrashLog
import com.mindgohua.overlay.OverlayController
import com.mindgohua.service.GatekeeperService
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import com.mindgohua.settings.InstalledApps
import com.mindgohua.settings.SettingsStore
import com.mindgohua.settings.TargetApps
import com.mindgohua.stats.DailyStatsStore
import com.mindgohua.stats.SurvivalLog
import com.mindgohua.util.OemSurvival
import com.mindgohua.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var stats: DailyStatsStore
    private lateinit var survival: SurvivalLog
    private lateinit var crashLog: CrashLog

    /**
     * 驗收用的預覽 overlay。刻意不走 GatekeeperService ——
     * 測試按鈕不該有機會啟動真正的偵測，或干擾正在跑的 session。
     * 用 applicationContext 建立，避免 Activity 被回收時 WindowManager 持有殘留 view。
     */
    private val previewOverlay by lazy { OverlayController(applicationContext) }

    /** 權限狀態不會用 Flow 通知，只能在每次回到前景時重新查一次。 */
    private val permissionState = MutableStateFlow(PermissionState())

    /** 當機紀錄的數量。同樣沒有 Flow 可訂閱，回到前景時重查。 */
    private val diagnosticsState = MutableStateFlow(DiagnosticsState())

    /**
     * 手機上可挑選的 app 清單。
     *
     * 在 onResume 重查，因為使用者很可能是「離開設定頁 → 去安裝一個新 app
     * → 回來想把它加進名單」。不重查的話他會找不到剛裝好的東西。
     */
    private val installedAppsState = MutableStateFlow(InstalledAppsState(loading = true))

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    /**
     * 匯出當機紀錄。
     *
     * 刻意用系統的「建立文件」選擇器，而不是分享選單：
     *  - 由使用者自己決定檔案存到哪裡，app 不經手、也看不到其他檔案
     *  - 不需要任何儲存空間權限
     *  - 沒有「直接傳給某個 app」這種捷徑
     *
     * 這跟「沒有網路權限」是同一個姿態：資料要離開這台手機，
     * 必須是使用者自己動手，而不是程式替他決定。
     */
    private val exportDiagnosticsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeDiagnosticsTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)
        stats = DailyStatsStore(applicationContext)
        survival = SurvivalLog(applicationContext)
        crashLog = CrashLog(applicationContext)
        val actions = buildActions()

        setContent {
            MindgohuaTheme {
                MainScreen(
                    settingsFlow = store.settings,
                    permissionsFlow = permissionState,
                    dailyTotalsFlow = stats.dailyTotals,
                    survivalFlow = survival.state,
                    diagnosticsFlow = diagnosticsState,
                    installedAppsFlow = installedAppsState,
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshDiagnostics()
        refreshInstalledApps()
        resumeServiceIfEnabled()
    }

    /**
     * 開關是開的就確保服務活著。
     *
     * 需要這個是因為服務可能在使用者不知情的狀況下消失：重新安裝 app、
     * 或 ColorOS 直接把它殺掉（spec §7）。那時設定裡 enabled 仍是 true，
     * 開關看起來是開的，實際上什麼都沒在跑 —— 這是最糟的失敗模式，因為它是靜默的。
     * 至少在使用者打開 app 的時候把它接回來。
     */
    private fun resumeServiceIfEnabled() {
        lifecycleScope.launch {
            if (store.settings.first().enabled) {
                runCatching { GatekeeperService.start(this@MainActivity) }
            }
        }
    }

    private fun refreshPermissions() {
        permissionState.value = PermissionState(
            usageAccess = Permissions.hasUsageAccess(this),
            overlay = Permissions.canDrawOverlay(this),
            notifications = Permissions.hasNotificationPermission(this),
            batteryUnrestricted = Permissions.isIgnoringBatteryOptimizations(this),
            accessibility = Permissions.isAccessibilityServiceEnabled(this),
            oem = Permissions.oemProfile(),
        )
    }

    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { crashLog.reports().size }
            diagnosticsState.value = DiagnosticsState(reportCount = count)
        }
    }

    /**
     * 重新查詢已安裝的 app。
     *
     * 一定要在 IO 執行緒：這是跨行程查詢，裝了幾百個 app 的手機上
     * 可能要數百毫秒，放在主執行緒會讓設定頁開啟時明顯卡一下。
     */
    private fun refreshInstalledApps() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                InstalledApps.query(this@MainActivity)
            }
            installedAppsState.value = InstalledAppsState(entries = entries, loading = false)
        }
    }

    private fun buildActions() = ScreenActions(
        onToggleEnabled = { enabled ->
            lifecycleScope.launch {
                store.setEnabled(enabled)
                if (enabled) GatekeeperService.start(this@MainActivity)
                else GatekeeperService.stop(this@MainActivity)
            }
        },
        onSettingsChange = { mutate ->
            lifecycleScope.launch { mutate(store) }
        },
        onOpenUsageAccess = { safeStart(Permissions.usageAccessIntent()) },
        onOpenOverlay = { safeStart(Permissions.overlayIntent(this)) },
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenBattery = { safeStart(Permissions.batteryOptimizationIntent(this)) },
        onOpenAccessibility = { safeStart(Permissions.accessibilitySettingsIntent()) },
        onOpenAutoStart = { Permissions.openAutoStartSettings(this) },
        onTestInterrupt = { showPreviewOverlay(InterruptReason.DURATION) },
        onTestFocusInterrupt = { showPreviewOverlay(InterruptReason.SCROLL_RHYTHM) },
        onClearStats = { lifecycleScope.launch { stats.clearAll() } },
        onResetSurvival = { lifecycleScope.launch { survival.reset() } },
        onExportDiagnostics = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            runCatching { exportDiagnosticsLauncher.launch("mindgohua-診斷紀錄-$stamp.txt") }
        },
        onClearDiagnostics = {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { crashLog.clear() }
                refreshDiagnostics()
            }
        },
    )

    /**
     * 把當機紀錄寫進使用者選定的位置。
     *
     * 失敗時不彈錯誤：這是診斷功能，不該在使用者已經遇到問題的時候
     * 再丟第二個錯誤訊息給他。寫不出去就是沒寫出去，紀錄仍留在本機。
     */
    private fun writeDiagnosticsTo(uri: Uri) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = crashLog.readAll()
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    private fun showPreviewOverlay(reason: InterruptReason) {
        lifecycleScope.launch {
            val current = store.settings.first()
            previewOverlay.show(
                elapsedMs = current.thresholdSeconds * 1000L,
                packageName = current.targetPackages.firstOrNull(),
                reason = reason,
                unlockDelaySeconds = current.unlockDelaySeconds,
                graceMinutes = current.graceMinutes,
                onDismiss = { previewOverlay.hide() },
            )
        }
    }

    override fun onDestroy() {
        previewOverlay.hide()
        super.onDestroy()
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}

data class PermissionState(
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val accessibility: Boolean = false,
    /**
     * 這台手機的廠牌對策。決定「讓貓活著」那張卡要顯示哪家的操作步驟。
     * 預設給保守的 GENERIC，而不是 OPPO —— 預設值也是一種假設，
     * 而「假設每個人都用 OPPO」正是這次要修掉的問題。
     */
    val oem: OemSurvival.OemProfile = OemSurvival.GENERIC,
)

/** 本機診斷紀錄的狀態。目前只有數量 —— 內容不進記憶體，要看就匯出。 */
data class DiagnosticsState(
    val reportCount: Int = 0,
)

/**
 * 可挑選的 app 清單。
 *
 * [loading] 與「查到空清單」是兩件不同的事，必須分開：
 * 前者該顯示「正在讀取」，後者該顯示「讀不到」。
 * 用同一個空清單表達兩種狀態，使用者會在載入的瞬間看到錯誤訊息。
 */
data class InstalledAppsState(
    val entries: List<InstalledApps.AppEntry> = emptyList(),
    val loading: Boolean = false,
) {
    private val byPackage: Map<String, String> by lazy {
        entries.associate { it.packageName to it.label }
    }

    /** 查不到就退回 [TargetApps] 的內建表，再查不到就顯示套件名本身。 */
    fun labelOf(packageName: String): String =
        TargetApps.labelOf(packageName, byPackage[packageName])

    /**
     * 這個套件現在還在不在這台手機上。
     *
     * 載入中一律回報 true —— 否則清單會在載入的那一瞬間
     * 把所有已選項目標成「已不在這台手機上」，那是假警報。
     */
    fun isInstalled(packageName: String): Boolean =
        loading || packageName in byPackage
}

data class ScreenActions(
    val onToggleEnabled: (Boolean) -> Unit,
    val onSettingsChange: (suspend (SettingsStore) -> Unit) -> Unit,
    val onOpenUsageAccess: () -> Unit,
    val onOpenOverlay: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onOpenBattery: () -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onOpenAutoStart: () -> Unit,
    val onTestInterrupt: () -> Unit,
    val onTestFocusInterrupt: () -> Unit,
    val onClearStats: () -> Unit,
    val onResetSurvival: () -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onClearDiagnostics: () -> Unit,
)

/** 開關能不能打開：Mode A 需要 usage access + overlay；Mode B 多要無障礙。 */
fun AppSettings.missingRequirements(state: PermissionState): List<String> = buildList {
    if (!state.overlay) add("懸浮視窗權限")
    if (!state.usageAccess) add("使用情況存取權")
    if (mode == DetectionMode.FOCUS && !state.accessibility) add("無障礙服務")
    if (targetPackages.isEmpty()) add("至少選一個目標 app")
}
