package com.mindgohua.settings

import com.mindgohua.settings.InstalledApps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * app 挑選頁的排序與搜尋行為。
 *
 * 這兩件事使用者會直接感受到（「我選的跑哪去了」「搜尋找不到」），
 * 所以用測試釘住，而不是靠手動點一點確認。
 *
 * 這裡測的全是純函式 —— 不需要手機、不需要 PackageManager。
 * 真正碰系統的 [InstalledApps.query] 沒有被測到，那是刻意的：
 * 它只是把系統回傳的東西轉成 [AppEntry]，邏輯都在這兩個函式裡。
 */
class InstalledAppsTest {

    private fun entry(pkg: String, label: String) = AppEntry(pkg, label)

    // ---- 排序 ----

    @Test
    fun `已選的 app 會排在最前面`() {
        val entries = listOf(
            entry("com.a", "Apple"),
            entry("com.b", "Banana"),
            entry("com.c", "Cherry"),
        )
        val sorted = InstalledApps.sortForPicker(entries, setOf("com.c"))

        assertEquals("com.c", sorted.first().packageName)
    }

    @Test
    fun `多個已選的 app 之間仍依名稱排序`() {
        val entries = listOf(
            entry("com.z", "Zebra"),
            entry("com.a", "Apple"),
            entry("com.m", "Mango"),
        )
        val sorted = InstalledApps.sortForPicker(entries, setOf("com.z", "com.a"))

        // 前兩個是已選的，且 Apple 在 Zebra 之前
        assertEquals(listOf("com.a", "com.z"), sorted.take(2).map { it.packageName })
        assertEquals("com.m", sorted[2].packageName)
    }

    @Test
    fun `未選的 app 依顯示名稱排序且不分大小寫`() {
        // 若用預設的字串排序，大寫會全部排在小寫前面，看起來像亂序。
        val entries = listOf(
            entry("com.banana", "banana"),
            entry("com.apple", "Apple"),
            entry("com.cherry", "Cherry"),
        )
        val sorted = InstalledApps.sortForPicker(entries, emptySet())

        assertEquals(listOf("Apple", "banana", "Cherry"), sorted.map { it.label })
    }

    @Test
    fun `名稱相同時用套件名決定順序以避免順序飄移`() {
        // 同名 app 並不罕見（例如雙開、不同廠商的同名工具）。
        // 沒有第二層排序鍵的話，每次查詢的顯示順序可能不一樣，看起來像 bug。
        val entries = listOf(
            entry("com.zzz", "相機"),
            entry("com.aaa", "相機"),
        )
        val sorted = InstalledApps.sortForPicker(entries, emptySet())

        assertEquals(listOf("com.aaa", "com.zzz"), sorted.map { it.packageName })
    }

    @Test
    fun `空清單排序不會出錯`() {
        assertTrue(InstalledApps.sortForPicker(emptyList(), setOf("com.a")).isEmpty())
    }

    @Test
    fun `已選但不在清單中的套件不會憑空冒出來`() {
        // 使用者選了某個 app，之後把它解除安裝了 —— 設定裡還留著那個套件名。
        // 挑選頁不該因此生出一個不存在的項目。
        val entries = listOf(entry("com.a", "Apple"))
        val sorted = InstalledApps.sortForPicker(entries, setOf("com.已解除安裝"))

        assertEquals(1, sorted.size)
        assertEquals("com.a", sorted.first().packageName)
    }

    // ---- 搜尋 ----

    @Test
    fun `搜尋比對顯示名稱且不分大小寫`() {
        val entries = listOf(
            entry("com.instagram.android", "Instagram"),
            entry("com.google.android.youtube", "YouTube"),
        )

        assertEquals(1, InstalledApps.filterByQuery(entries, "insta").size)
        assertEquals(1, InstalledApps.filterByQuery(entries, "INSTA").size)
        assertEquals(1, InstalledApps.filterByQuery(entries, "InStA").size)
    }

    @Test
    fun `搜尋也比對套件名`() {
        // TikTok 的套件名跟顯示名完全不同，進階使用者可能直接打套件名。
        val entries = listOf(entry("com.zhiliaoapp.musically", "TikTok"))

        assertEquals(1, InstalledApps.filterByQuery(entries, "zhiliao").size)
    }

    @Test
    fun `空字串或純空白不過濾`() {
        val entries = listOf(
            entry("com.a", "Apple"),
            entry("com.b", "Banana"),
        )

        assertEquals(2, InstalledApps.filterByQuery(entries, "").size)
        assertEquals(2, InstalledApps.filterByQuery(entries, "   ").size)
    }

    @Test
    fun `搜尋前後空白會被忽略`() {
        val entries = listOf(entry("com.a", "Apple"))

        assertEquals(1, InstalledApps.filterByQuery(entries, "  apple  ").size)
    }

    @Test
    fun `找不到時回傳空清單而不是全部`() {
        // 這種錯很常見：過濾條件沒命中就「乾脆不過濾」，
        // 結果使用者搜尋一個不存在的字，卻看到全部 app。
        val entries = listOf(entry("com.a", "Apple"))

        assertTrue(InstalledApps.filterByQuery(entries, "找不到的東西").isEmpty())
    }

    @Test
    fun `搜尋支援中文名稱`() {
        val entries = listOf(
            entry("com.taobao.taobao", "淘寶"),
            entry("com.xingin.xhs", "小紅書"),
        )

        val result = InstalledApps.filterByQuery(entries, "小紅")
        assertEquals(1, result.size)
        assertEquals("com.xingin.xhs", result.first().packageName)
    }
}
