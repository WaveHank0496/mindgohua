package com.mindgohua.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.mindgohua.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mode B 的訊號來源。
 *
 * **這個 service 讀取的東西的完整清單：**
 *   1. 事件的 package name（判斷是不是目標 app）
 *   2. 事件發生的時間點
 *
 * 就這樣。沒有 `getSource()`、沒有 `rootInActiveWindow`、沒有 `event.text`。
 * manifest 裡 `canRetrieveWindowContent="false"`，系統層級就拿不到節點樹（spec §10）。
 *
 * 更進一步：[onServiceConnected] 會把 `serviceInfo.packageNames` 設成使用者選的目標 app，
 * 所以非目標 app 的事件**根本不會被送進這個行程**。這比在程式裡 early return 更硬 ——
 * 「行為自限」（spec §0.3）由系統代為執行，不是靠我們自律。
 *
 * 如果未來有人想在這裡加一行 `event.text` 來「順便判斷是不是 Reels」——
 * 那就違反了 spec §0 的核心原則，請不要。
 */
class ScrollWatchService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val pkg = event.packageName?.toString() ?: return
        ScrollSignalBus.emit(
            ScrollSignal(
                packageName = pkg,
                atElapsedMs = SystemClock.elapsedRealtime(),
                // 清單「位置」欄位：你滑到第幾項、總共幾項。
                // 這是座標不是內容 —— 取用它不需要 canRetrieveWindowContent，
                // 所以 spec §10 的界線沒有被跨越。很多 app 不填，會拿到 -1。
                fromIndex = event.fromIndex,
                toIndex = event.toIndex,
                itemCount = event.itemCount,
            )
        )
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScrollSignalBus.setConnected(true)

        // 使用者改了目標 app 清單就跟著改系統層的過濾條件。
        scope.launch {
            SettingsStore(applicationContext).settings
                .map { it.targetPackages }
                .distinctUntilChanged()
                .collect { targets -> applyPackageFilter(targets) }
        }
    }

    private fun applyPackageFilter(targets: Set<String>) {
        val info = serviceInfo ?: return
        // packageNames = null 代表「所有 app」，這正是我們要避免的。
        // 清單為空時填一個不存在的 package，讓過濾器擋掉全部。
        info.packageNames = if (targets.isEmpty()) {
            arrayOf("$packageName.__no_target__")
        } else {
            targets.toTypedArray()
        }
        runCatching { serviceInfo = info }
            .onFailure { Log.w(TAG, "套用 package 過濾失敗：${it.message}") }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ScrollSignalBus.setConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScrollSignalBus.setConnected(false)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ScrollWatchService"
    }
}

/**
 * 單一滑動事件。
 *
 * 欄位就這五個，全部都是「哪個 app、什麼時候、滑到第幾項」。
 * 沒有任何一個欄位裝得下畫面內容 —— 這是刻意的。
 * index 相關欄位在 app 沒填時是 -1。
 */
data class ScrollSignal(
    val packageName: String,
    val atElapsedMs: Long,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    val itemCount: Int = -1,
)

/**
 * AccessibilityService 與 GatekeeperService 之間的橋。
 *
 * 用 `replay = 0` 的 SharedFlow：沒有訂閱者時事件直接丟棄，不排隊、不留存。
 * 這符合「判斷完立即丟棄、不寫入任何持久化儲存」（spec §0.1）。
 */
object ScrollSignalBus {

    private val _scrollEvents = MutableSharedFlow<ScrollSignal>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val scrollEvents: SharedFlow<ScrollSignal> = _scrollEvents.asSharedFlow()

    @Volatile
    var isServiceConnected: Boolean = false
        private set

    fun emit(signal: ScrollSignal) {
        _scrollEvents.tryEmit(signal)
    }

    fun setConnected(connected: Boolean) {
        isServiceConnected = connected
    }
}
