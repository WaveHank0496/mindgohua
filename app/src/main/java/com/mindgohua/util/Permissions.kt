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

    /**
     * 電池最佳化的**清單頁**，讓使用者自己找到本 app 放行。
     *
     * 刻意**不用** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ——
     * 那個 intent 需要宣告 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 權限，
     * 而 Google Play 對那個權限有一份明確的可接受用途白名單
     * （鬧鐘、VoIP、裝置自動化…），「螢幕使用時間管理」不在上面。
     * 為了省使用者兩次點擊而冒著整個 app 被拒絕的風險，不划算。
     *
     * 代價只有：使用者要自己在清單裡找到 mindgohua。
     * 換來的是 manifest 少一條高風險權限。
     *
     * 注意 [isIgnoringBatteryOptimizations] 查詢狀態**本來就不需要任何權限**，
     * 所以設定頁上的 ✔ / ！ 燈號完全不受影響。
     */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    // 原本這裡有 isAccessibilityServiceEnabled() 與 accessibilitySettingsIntent()，
    // 服務於 Mode B。v1 連同 AccessibilityService 一起移除 —— 這個 app 現在
    // 不會、也無法要求無障礙權限。

    /** 這台手機的廠牌對策。見 [OemSurvival]。 */
    fun oemProfile(): OemSurvival.OemProfile = OemSurvival.profileFor(Build.MANUFACTURER)

    /**
     * 「自啟動管理」設定頁（spec §7）。
     *
     * 這是 OEM 私有介面，沒有公開 API、也沒有保證存在的 component name，
     * 所以逐一試已知的 intent，全失敗就退回 app 詳細資訊頁，讓使用者自己找。
     *
     * **原本這裡只寫死 OPPO 的四個 component**，因為開發機是 ColorOS。
     * 對小米 / vivo / 華為 / Samsung 的使用者來說，「開啟」按鈕按下去
     * 只會跳到一個不相干的頁面，或什麼都不做 —— 而他們不會知道原因。
     * 現在改由 [OemSurvival] 依 [Build.MANUFACTURER] 給出對應清單。
     *
     * 這段本質上仍然是脆弱的，任何一家 OEM 改版都可能失效 ——
     * 所以 UI 上一定要同時附**文字步驟**，那才是使用者真正的退路。
     */
    fun autoStartIntents(context: Context): List<Intent> =
        OemSurvival.intentsFor(oemProfile()) +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))

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
