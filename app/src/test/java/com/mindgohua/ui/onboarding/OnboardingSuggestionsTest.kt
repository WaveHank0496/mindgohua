package com.mindgohua.ui.onboarding

import com.mindgohua.settings.TargetApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要看住哪些 app」那一步的排序與清理。
 *
 * 這裡守的是稽核發現的第 4 個問題：預設鎖死 Instagram，
 * 沒裝 IG 的人第一眼就看到紅色錯誤字。
 */
class OnboardingSuggestionsTest {

    @Test
    fun `只建議這台手機上真的裝了的 app`() {
        val installed = listOf(TargetApps.YOUTUBE, "com.example.calculator")
        val suggested = OnboardingSuggestions.rank(installed)
        assertTrue(TargetApps.YOUTUBE in suggested)
        assertFalse("沒裝的東西不該出現在建議清單裡", TargetApps.INSTAGRAM in suggested)
    }

    @Test
    fun `沒裝 Instagram 的人不會在第一眼看到 Instagram`() {
        val suggested = OnboardingSuggestions.rank(listOf(TargetApps.FACEBOOK))
        assertFalse(TargetApps.INSTAGRAM in suggested)
    }

    @Test
    fun `不在常見清單裡的 app 不會被主動建議`() {
        // 建議清單短才有意義。完整清單與搜尋框永遠在下面。
        assertTrue(OnboardingSuggestions.rank(listOf("com.example.calculator")).isEmpty())
    }

    @Test
    fun `建議的順序照常見程度而不是套件名`() {
        val installed = listOf(TargetApps.REDDIT, TargetApps.INSTAGRAM, TargetApps.YOUTUBE)
        val suggested = OnboardingSuggestions.rank(installed)
        assertEquals(
            listOf(TargetApps.INSTAGRAM, TargetApps.YOUTUBE, TargetApps.REDDIT),
            suggested,
        )
    }

    @Test
    fun `已經選了的排在最前面`() {
        // 使用者剛剛才勾的東西，不該在清單重組時掉到看不見的地方。
        val installed = listOf(TargetApps.INSTAGRAM, TargetApps.REDDIT)
        val suggested = OnboardingSuggestions.rank(installed, selected = setOf(TargetApps.REDDIT))
        assertEquals(TargetApps.REDDIT, suggested.first())
    }

    @Test
    fun `已經選了但不在常見清單裡的 app 也要留著`() {
        val installed = listOf("com.example.calculator", TargetApps.YOUTUBE)
        val suggested = OnboardingSuggestions.rank(installed, selected = setOf("com.example.calculator"))
        assertTrue("com.example.calculator" in suggested)
    }

    @Test
    fun `挑出已經勾選但手機上已經沒有的 app`() {
        val stale = OnboardingSuggestions.staleSelection(
            selected = setOf(TargetApps.INSTAGRAM, TargetApps.YOUTUBE),
            installedPackages = listOf(TargetApps.YOUTUBE),
        )
        assertEquals(listOf(TargetApps.INSTAGRAM), stale)
    }

    @Test
    fun `已安裝清單是空的時候什麼都不動`() {
        // 空清單有兩種可能：真的沒裝（不可能），或我們還沒查到／查失敗。
        // 分不出來時猜錯的代價是把使用者的設定清掉。
        val stale = OnboardingSuggestions.staleSelection(
            selected = setOf(TargetApps.INSTAGRAM),
            installedPackages = emptyList(),
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `全部都還在的話不會誤報`() {
        val stale = OnboardingSuggestions.staleSelection(
            selected = setOf(TargetApps.YOUTUBE),
            installedPackages = listOf(TargetApps.YOUTUBE, TargetApps.INSTAGRAM),
        )
        assertTrue(stale.isEmpty())
    }
}
