package com.mindgohua.detect

import com.mindgohua.core.SessionEvent
import com.mindgohua.settings.AppSettings
import com.mindgohua.settings.DetectionMode

/**
 * 偵測層抽象（spec §2）。
 *
 * 上游可以是 UsageStats、AccessibilityService，或未來任何東西；
 * 下游（Session Engine）只吃 [SessionEvent]，不知道訊號哪來的。
 * 這條界線是 Mode A / Mode B 能熱插拔的原因。
 *
 * 實作者的義務（spec §0.3「行為自限」）：
 * **只有目標 app 在前景時才做任何讀取，其他 app 一律 early return。**
 */
interface Detector {

    val mode: DetectionMode

    /** 開始偵測。[onSignal] 會被從背景執行緒呼叫，實作端自行處理執行緒切換。 */
    fun start(onSignal: (SessionEvent) -> Unit)

    fun stop()

    /** 使用者改了設定（目標 app、門檻、調參）。 */
    fun onSettingsChanged(settings: AppSettings)

    /** 給偵錯畫面看的一行狀態文字。不含任何使用者內容。 */
    fun debugState(): String = ""
}
