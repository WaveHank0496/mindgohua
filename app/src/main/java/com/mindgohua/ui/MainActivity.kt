package com.mindgohua.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mindgohua.core.InterruptReason
import com.mindgohua.overlay.OverlayController
import com.mindgohua.service.GatekeeperService
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode
import com.mindgohua.settings.SettingsStore
import com.mindgohua.stats.DailyStatsStore
import com.mindgohua.stats.SurvivalLog
import com.mindgohua.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var stats: DailyStatsStore
    private lateinit var survival: SurvivalLog

    /**
     * 驗收用的預覽 overlay。刻意不走 GatekeeperService ——
     * 測試按鈕不該有機會啟動真正的偵測，或干擾正在跑的 session。
     * 用 applicationContext 建立，避免 Activity 被回收時 WindowManager 持有殘留 view。
     */
    private val previewOverlay by lazy { OverlayController(applicationContext) }

    /** 權限狀態不會用 Flow 通知，只能在每次回到前景時重新查一次。 */
    private val permissionState = MutableStateFlow(PermissionState())

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(applicationContext)
        stats = DailyStatsStore(applicationContext)
        survival = SurvivalLog(applicationContext)
        val actions = buildActions()

        setContent {
            MindgohuaTheme {
                MainScreen(
                    settingsFlow = store.settings,
                    permissionsFlow = permissionState,
                    dailyTotalsFlow = stats.dailyTotals,
                    survivalFlow = survival.state,
                    actions = actions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
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
        )
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
    )

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
)

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
)

/** 開關能不能打開：Mode A 需要 usage access + overlay；Mode B 多要無障礙。 */
fun AppSettings.missingRequirements(state: PermissionState): List<String> = buildList {
    if (!state.overlay) add("懸浮視窗權限")
    if (!state.usageAccess) add("使用情況存取權")
    if (mode == DetectionMode.FOCUS && !state.accessibility) add("無障礙服務")
    if (targetPackages.isEmpty()) add("至少選一個目標 app")
}
