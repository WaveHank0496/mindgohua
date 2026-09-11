package com.catgatekeeper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.catgatekeeper.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 開機 / app 更新後把服務接回來。
 *
 * ColorOS 上這個 receiver 常常收不到 —— 除非使用者在「自啟動管理」放行本 app（spec §7）。
 * 收不到時使用者只會發現貓不見了，所以 onboarding 必須把那一步講清楚。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = SettingsStore(appContext).settings.first().enabled
                if (enabled) {
                    GatekeeperService.start(appContext)
                    Log.i(TAG, "開機後重新啟動服務")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "開機重啟失敗：${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
