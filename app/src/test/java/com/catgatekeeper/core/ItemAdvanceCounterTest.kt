package com.catgatekeeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 計數規則的測試。
 *
 * 這裡每一條「不算」的規則都是為了防止數字被灌水 ——
 * 一個會虛報的計數器比沒有計數器更糟，因為你會依據它做判斷。
 */
class ItemAdvanceCounterTest {

    @Test
    fun `一次滑一項就加一`() {
        val c = ItemAdvanceCounter()
        c.onScroll(0, 0, 100)   // 第一筆只建立基準，不計數
        assertEquals(1, c.onScroll(1, 1, 100))
        assertEquals(1, c.onScroll(2, 2, 100))
        assertEquals(2, c.total)
    }

    @Test
    fun `第一筆事件不計數只建立基準`() {
        val c = ItemAdvanceCounter()
        // 你打開 IG 時已經停在第 37 項，不該一口氣算你滑了 37 項
        assertEquals(0, c.onScroll(37, 37, 500))
        assertEquals(0, c.total)
    }

    @Test
    fun `往回滑不計數`() {
        val c = ItemAdvanceCounter()
        c.onScroll(5, 5, 100)
        c.onScroll(6, 6, 100)
        assertEquals(0, c.onScroll(5, 5, 100))
        assertEquals(0, c.onScroll(4, 4, 100))
        assertEquals(1, c.total)
    }

    @Test
    fun `來回滑同一項不會刷高數字`() {
        val c = ItemAdvanceCounter()
        c.onScroll(10, 10, 100)
        repeat(20) {
            c.onScroll(11, 11, 100)
            c.onScroll(10, 10, 100)
        }
        // 上去算 1 次，回來不算；再上去時是從 10 到 11，又算 1 次…
        // 重點是它反映真實的「向前滑動」次數，而不是被來回摩擦灌水成 40
        assertEquals(20, c.total)
    }

    @Test
    fun `清單總數改變視為換清單重新起算`() {
        val c = ItemAdvanceCounter()
        c.onScroll(0, 0, 100)
        c.onScroll(3, 3, 100)
        assertEquals(3, c.total)

        // 切到別的分頁，清單長度不一樣了 → 這一筆不該被算成「倒退 3 項」或「前進 50 項」
        assertEquals(0, c.onScroll(50, 50, 999))
        assertEquals(3, c.total)

        // 之後在新清單上繼續正常計數
        assertEquals(1, c.onScroll(51, 51, 999))
        assertEquals(4, c.total)
    }

    @Test
    fun `跳號太多不計數`() {
        val c = ItemAdvanceCounter(maxJumpPerEvent = 5)
        c.onScroll(0, 0, 1000)
        // 一個事件跳 200 項，多半是清單重載或跳轉，不是真的滑了 200 次
        assertEquals(0, c.onScroll(200, 200, 1000))
        assertEquals(0, c.total)
    }

    @Test
    fun `沒有index資料的app一律回0並標記量不到`() {
        val c = ItemAdvanceCounter()
        // 很多 app 不填這些欄位，會拿到 -1
        assertEquals(0, c.onScroll(-1, -1, -1))
        assertEquals(0, c.onScroll(-1, -1, -1))
        assertEquals(0, c.total)
        assertFalse("不該宣稱量得到", c.sawIndexData)
    }

    @Test
    fun `收到可用資料後才標記量得到`() {
        val c = ItemAdvanceCounter()
        c.onScroll(-1, -1, -1)
        assertFalse(c.sawIndexData)
        c.onScroll(0, 0, 50)
        assertTrue(c.sawIndexData)
    }

    @Test
    fun `離開app後回來不會把位置差算成滑動`() {
        val c = ItemAdvanceCounter()
        c.onScroll(0, 0, 100)
        c.onScroll(2, 2, 100)
        assertEquals(2, c.total)

        c.onLeaveApp()

        // 回來時已經在第 80 項（app 自己跳的），不該算成滑了 78 項
        assertEquals(0, c.onScroll(80, 80, 100))
        assertEquals(2, c.total)
        // 但累計值要留著
        assertEquals(2, c.total)
    }

    @Test
    fun `一個事件跨多項會照實加`() {
        val c = ItemAdvanceCounter()
        c.onScroll(0, 2, 100)
        // 快速滑動時一個事件可能跨 3 項，這是真的滑過去了，該算
        assertEquals(3, c.onScroll(3, 5, 100))
        assertEquals(3, c.total)
    }

    @Test
    fun `resetTotal只清累計不影響位置追蹤`() {
        val c = ItemAdvanceCounter()
        c.onScroll(0, 0, 100)
        c.onScroll(5, 5, 100)
        c.resetTotal()
        assertEquals(0, c.total)
        // 換日之後繼續從當前位置往下數，不該把已經在的位置重算一次
        assertEquals(1, c.onScroll(6, 6, 100))
        assertEquals(1, c.total)
    }
}
