package com.catgatekeeper.core

/**
 * 數「滑過了幾項」。
 *
 * ## 資料來源與隱私界線
 *
 * `AccessibilityEvent.TYPE_VIEW_SCROLLED` 除了時間戳之外，還帶三個**清單位置**欄位：
 *
 * - `fromIndex` / `toIndex`：目前可見範圍是第幾項到第幾項
 * - `itemCount`：這份清單總共幾項
 *
 * 這些是「座標」，不是「內容」—— 知道你滑到第 42 項，不知道第 42 項是什麼。
 * 取得它們**不需要** `canRetrieveWindowContent`，所以 spec §10
 * 「不抓節點樹 / 文字內容」的界線沒有被跨越。
 *
 * ## 為什麼這能約等於「幾部 Reels」
 *
 * Reels 是全螢幕 pager，一次一部，滑一次 `toIndex` 就 +1。
 * 首頁動態則是一般清單，`toIndex` 代表「滑過幾則貼文」。
 * 兩者都有意義，但**意義不同** —— 所以呼叫端要按 app 分開記，別混在一起加總。
 *
 * ## 重要：不保證每個 app 都填
 *
 * 這幾個欄位是選填的，很多 app 根本不設（會拿到 -1）。
 * 所以本類別對「沒有 index 的事件」一律回傳 0，並由 [sawIndexData] 回報
 * 「這個 app 到底有沒有給我們可用的資料」，讓 UI 能誠實顯示而不是編一個數字出來。
 */
class ItemAdvanceCounter(
    /** 單一事件最多採計幾項。超過視為清單重載造成的跳號，不採計。 */
    private val maxJumpPerEvent: Int = 5,
) {

    private var lastIndex: Int = UNSET
    private var lastItemCount: Int = UNSET

    /** 這個 app 曾經送出過可用的 index 資料嗎。用來分辨「沒滑」與「量不到」。 */
    var sawIndexData: Boolean = false
        private set

    var total: Int = 0
        private set

    /** 最近一次收到的原始值，給診斷畫面看。 */
    var lastFromIndex: Int = UNSET
        private set
    var lastToIndex: Int = UNSET
        private set
    var lastKnownItemCount: Int = UNSET
        private set

    /**
     * @return 這次事件新增了幾項（0 代表不採計）。
     */
    fun onScroll(fromIndex: Int, toIndex: Int, itemCount: Int): Int {
        lastFromIndex = fromIndex
        lastToIndex = toIndex
        lastKnownItemCount = itemCount

        // 這個 app 沒填 index，數不了。誠實回 0，不要用別的訊號硬湊一個數字。
        if (toIndex < 0 || itemCount <= 0) return 0
        sawIndexData = true

        // 清單總數變了 = 換了一份清單（切分頁、重新整理）。重新起算，不要把跳號算成滑動。
        if (itemCount != lastItemCount) {
            lastItemCount = itemCount
            lastIndex = toIndex
            return 0
        }

        if (lastIndex == UNSET) {
            lastIndex = toIndex
            return 0
        }

        val delta = toIndex - lastIndex
        lastIndex = toIndex

        return when {
            delta <= 0 -> 0                 // 往回滑不算，不然來回滑一則就能刷數字
            delta > maxJumpPerEvent -> 0    // 跳太多，多半是清單重載
            else -> {
                total += delta
                delta
            }
        }
    }

    /** 離開這個 app 時呼叫。清掉位置狀態，但保留累計值。 */
    fun onLeaveApp() {
        lastIndex = UNSET
        lastItemCount = UNSET
    }

    /** 換日或使用者清除紀錄時呼叫。 */
    fun resetTotal() {
        total = 0
    }

    companion object {
        const val UNSET = -1
    }
}
