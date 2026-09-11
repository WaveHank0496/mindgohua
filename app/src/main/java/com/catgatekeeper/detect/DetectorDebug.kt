package com.catgatekeeper.detect

import com.catgatekeeper.settings.DetectionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 偵測層的即時狀態，給驗收與調參用。
 *
 * **隱私範圍**：這裡面只有「統計量」和「計數」——密度、變異係數、事件筆數。
 * 沒有時間戳序列、沒有 package name、沒有任何可還原成使用行為的東西。
 * 只存在記憶體，服務一停就清空（[DetectorDebugBus.clear]），不寫檔、不上傳。
 *
 * 之所以需要它：Mode B 的參數必須拿真實使用資料調（spec §4），
 * 但你滑 IG 的時候看不到設定頁。所以數字要能被送到看得到的地方——
 * 常駐通知（滑的當下看）和設定頁（滑完回來看）。
 */
data class DetectorDebug(
    val mode: DetectionMode,

    /** 目標 app 是否在前景。非目標 app 時偵測器什麼都不做。 */
    val inTargetApp: Boolean = false,

    /** 無障礙服務是否真的連上了（Mode B 專用）。 */
    val accessibilityConnected: Boolean = false,

    /** 自偵測啟動以來，從目標 app 收到的滑動事件總數。用來確認「管線通不通」。 */
    val totalScrollEvents: Int = 0,

    /** 統計窗口內的事件數。 */
    val sampleCount: Int = 0,

    val densityPerMinute: Double = 0.0,

    /** NaN 代表樣本不足、還不下判斷。 */
    val coefficientOfVariation: Double = Double.NaN,

    /** 目前是否符合「無意識刷動」的特徵（尚未計入持續時間要求）。 */
    val qualifying: Boolean = false,

    /** 符合特徵已持續幾秒。要達到設定的門檻才會真的觸發。 */
    val sustainedSeconds: Long = 0,

    /** 自偵測啟動以來觸發過幾次。 */
    val triggerCount: Int = 0,

    /**
     * true 代表這組數字是「離開目標 app 之前的最後一筆」，不是當下的。
     * 離開目標 app 時我們刻意**不把數字歸零**，否則你滑完 IG 回到設定頁只會看到一片 0。
     */
    val frozen: Boolean = false,

    // ---- 「滑過幾項」的診斷（見 ItemAdvanceCounter）----

    /**
     * 目標 app 到底有沒有在滑動事件裡填 index 欄位。
     * false 代表「量不到」而不是「你沒滑」—— 這兩件事一定要分得開，
     * 否則 UI 會顯示一個看起來很正常但其實是編出來的 0。
     */
    val indexDataAvailable: Boolean = false,

    /** 最近一次收到的原始 index 值，用來判斷這個 app 的清單語意。 */
    val lastToIndex: Int = -1,
    val lastItemCount: Int = -1,

    /** 本次偵測啟動以來，累計滑過幾項。 */
    val itemsAdvanced: Int = 0,
)

object DetectorDebugBus {

    private val _state = MutableStateFlow<DetectorDebug?>(null)

    /** null 代表偵測服務沒在跑。 */
    val state: StateFlow<DetectorDebug?> = _state.asStateFlow()

    fun publish(debug: DetectorDebug) {
        _state.value = debug
    }

    fun clear() {
        _state.value = null
    }
}
