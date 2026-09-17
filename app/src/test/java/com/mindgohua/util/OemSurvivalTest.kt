package com.mindgohua.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 廠牌判斷的對照規則。
 *
 * 這件事沒辦法用「拿手機試試看」來驗證 —— 我們手上只有一支 OPPO。
 * 但規則本身是純資料比對，可以完整測試：這正是把它抽成純函式的理由。
 *
 * 這裡守住的是一個很容易寫錯、而且錯了不會有人發現的地方：
 * 使用者的手機被判成錯的廠牌，只會看到一段對不上的操作說明，
 * 然後放棄 —— 不會有人回報。
 */
class OemSurvivalTest {

    @Test
    fun `OPPO 的各種寫法都對應到同一份對策`() {
        // Build.MANUFACTURER 的大小寫沒有標準，同廠商不同機型可能不一樣。
        assertEquals("oppo", OemSurvival.profileFor("OPPO").id)
        assertEquals("oppo", OemSurvival.profileFor("oppo").id)
        assertEquals("oppo", OemSurvival.profileFor("Oppo").id)
    }

    @Test
    fun `realme 與 OPLUS 都算 OPPO 體系`() {
        // realme 是 OPPO 旗下，用同一套 ColorOS 底層。
        assertEquals("oppo", OemSurvival.profileFor("realme").id)
        assertEquals("oppo", OemSurvival.profileFor("OPLUS").id)
    }

    @Test
    fun `Redmi 與 POCO 都對應到小米`() {
        // 這是最容易漏的一組：Redmi 手機的 MANUFACTURER 常常不是 Xiaomi。
        assertEquals("xiaomi", OemSurvival.profileFor("Xiaomi").id)
        assertEquals("xiaomi", OemSurvival.profileFor("Redmi").id)
        assertEquals("xiaomi", OemSurvival.profileFor("POCO").id)
    }

    @Test
    fun `iQOO 對應到 vivo`() {
        assertEquals("vivo", OemSurvival.profileFor("vivo").id)
        assertEquals("vivo", OemSurvival.profileFor("iQOO").id)
    }

    @Test
    fun `honor 對應到華為體系`() {
        assertEquals("huawei", OemSurvival.profileFor("HUAWEI").id)
        assertEquals("huawei", OemSurvival.profileFor("HONOR").id)
    }

    @Test
    fun `Samsung 與 OnePlus 各自對應`() {
        assertEquals("samsung", OemSurvival.profileFor("samsung").id)
        assertEquals("oneplus", OemSurvival.profileFor("OnePlus").id)
    }

    @Test
    fun `Pixel 與 Sony 被判定為近原生且不需要自啟動管理`() {
        // 對這些手機顯示「去手機管家找自啟動管理」，使用者只會找不到。
        val google = OemSurvival.profileFor("Google")
        val sony = OemSurvival.profileFor("Sony")

        assertEquals("near_stock", google.id)
        assertEquals("near_stock", sony.id)
        assertFalse(google.needsAutoStart)
        assertFalse(sony.needsAutoStart)
    }

    @Test
    fun `認不出來的廠牌退回保守預設並仍提醒自啟動`() {
        // 漏報（該提醒卻沒提醒）會讓 app 靜默失效；
        // 誤報只是多一段文字。所以未知廠牌一律假設有管制。
        val unknown = OemSurvival.profileFor("某個沒聽過的牌子")

        assertEquals("generic", unknown.id)
        assertTrue(unknown.needsAutoStart)
    }

    @Test
    fun `null 或空字串不會讓程式爆掉`() {
        assertEquals("generic", OemSurvival.profileFor(null).id)
        assertEquals("generic", OemSurvival.profileFor("").id)
        assertEquals("generic", OemSurvival.profileFor("   ").id)
    }

    @Test
    fun `每一份對策都必須有文字步驟`() {
        // intent 一定會有失敗的時候（改版、地區版本、機型差異），
        // 文字步驟是使用者最後的退路，不能是空的。
        val all = listOf(
            OemSurvival.OPPO, OemSurvival.XIAOMI, OemSurvival.VIVO,
            OemSurvival.HUAWEI, OemSurvival.SAMSUNG, OemSurvival.ONEPLUS,
            OemSurvival.ASUS, OemSurvival.NEAR_STOCK, OemSurvival.GENERIC,
        )
        all.forEach { profile ->
            assertTrue(
                "${profile.id} 沒有文字步驟，intent 失敗時使用者會卡住",
                profile.autoStartSteps.isNotEmpty(),
            )
        }
    }

    // 注意：這裡**不測** [OemSurvival.intentsFor]。
    //
    // 它會 new 出 android.content.Intent，而純 JVM 單元測試裡的 Android 類別
    // 只是沒有實作的空殼，呼叫任何方法都會丟
    // 「Method setComponent in android.content.Intent not mocked」。
    //
    // 這不是那個函式有問題，是「該用什麼測試驗證什麼」的問題：
    // intentsFor 只是把下面這份純資料逐筆包成 Intent，真正會寫錯的是資料本身
    // （打錯 component 名稱、漏了某個廠牌），而那可以直接測。

    @Test
    fun `有 component 的廠牌都必須成對填寫套件名與類別名`() {
        // component 是 "套件名 to 類別名"，任一邊空白都會產生一個
        // 永遠開不起來的 intent —— 而且不會報錯，只是按鈕按下去沒反應。
        val all = listOf(
            OemSurvival.OPPO, OemSurvival.XIAOMI, OemSurvival.VIVO,
            OemSurvival.HUAWEI, OemSurvival.SAMSUNG, OemSurvival.ONEPLUS,
            OemSurvival.ASUS,
        )
        all.forEach { profile ->
            profile.components.forEach { (pkg, cls) ->
                assertTrue("${profile.id} 有空白的套件名", pkg.isNotBlank())
                assertTrue("${profile.id} 有空白的類別名", cls.isNotBlank())
                assertTrue(
                    "${profile.id} 的類別名 $cls 應該以套件名開頭，否則多半是打錯了",
                    cls.startsWith(pkg.substringBeforeLast('.')) || cls.contains('.'),
                )
            }
        }
    }

    @Test
    fun `近原生系統沒有專屬設定頁所以不列任何 component`() {
        // 硬給一個開不起來的 component，只會讓「開啟」按鈕按下去沒反應。
        assertTrue(OemSurvival.NEAR_STOCK.components.isEmpty())
        assertTrue(OemSurvival.GENERIC.components.isEmpty())
    }
}
