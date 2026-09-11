package com.mindgohua.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mindgohua.service.ScrollWatchService

/**
 * 權限狀態查詢與「帶使用者去開」的 intent（spec §3 的權限清單 + §7 的 ColorOS 引導）。
 *
 * 這裡每一個權限在 UI 上都必須配一段說明：要它做什麼、看得到什麼、看不到什麼。
 * 「先要到權限再說」是這類 app 最容易失去使用者信任的地方。
 */
object Permissions {

    /** PACKAGE_USAGE_STATS：特殊權限，只能引導使用者去系統設定手動開。 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlayIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** ColorOS 殺後台的第一道防線：把 app 移出電池最佳化名單。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    fun batteryOptimizationIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )

    /** Mode B 用。無障礙服務是否已啟用。 */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ScrollWatchService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * ColorOS「自啟動管理」（spec §7）。
     *
     * 這是 OEM 私有介面，沒有公開 API、也沒有保證存在的 component name，
     * 所以逐一試已知的 intent，全失敗就退回 app 詳細資訊頁，讓使用者自己找。
     * 這段本質上是脆弱的，ColorOS 改版就可能失效 —— UI 上一定要同時附文字步驟說明。
     */
    fun autoStartIntents(context: Context): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
        ),
        Intent().setComponent(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
        ),
        Intent().setComponent(
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
        ),
        Intent().setComponent(
            ComponentName(
                "com.oplus.battery",
                "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
            )
        ),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )

    /** 依序試，開得起來就停。 */
    fun openAutoStartSettings(context: Context): Boolean {
        for (intent in autoStartIntents(context)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = intent.resolveActivity(context.packageManager) != null
            if (resolvable && runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
