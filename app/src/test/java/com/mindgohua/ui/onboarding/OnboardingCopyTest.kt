package com.mindgohua.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文案的守門測試。
 *
 * 這些規則改壞了不會有編譯錯誤、不會丟例外 ——
 * 只會讓一個不懂技術的人在某一頁上卡住，然後解除安裝。
 */
class OnboardingCopyTest {

    private val allSteps = OnboardingStep.entries.toList()

    @Test
    fun `每一個步驟都有標題內文與主要按鈕`() {
        allSteps.forEach { step ->
            val copy = OnboardingCopy.of(step)
            assertTrue("$step 沒有標題", copy.title.isNotBlank())
            assertTrue("$step 沒有內文", copy.body.isNotBlank())
            assertTrue("$step 沒有主要按鈕", copy.primary.isNotBlank())
        }
    }

    @Test
    fun `標題一律不出現工程術語`() {
        // 使用者第一眼看到的那行字，決定他要不要繼續讀下去。
        val jargon = listOf(
            "懸浮視窗", "使用情況存取權", "前景", "常駐", "門檻",
            "overlay", "Overlay", "Mode A", "Mode B", "權限",
        )
        allSteps.forEach { step ->
            val title = OnboardingCopy.of(step).title
            jargon.forEach { word ->
                assertFalse("$step 的標題出現了工程術語「$word」：$title", title.contains(word))
            }
        }
    }

    @Test
    fun `工程術語出現在內文時一定附上括號說明`() {
        // 括號裡放的要是他在系統設定頁上真的會看到的那幾個字 ——
        // 他需要的不是解釋，是對照。
        val overlay = OnboardingCopy.of(OnboardingStep.OVERLAY)
        assertTrue(
            "沒有告訴使用者這個開關在系統裡叫什麼",
            overlay.body.contains("顯示在其他應用程式上層"),
        )
        assertTrue("術語沒有放在括號裡", overlay.body.contains("（") && overlay.body.contains("）"))
    }

    @Test
    fun `要跳到系統設定的步驟都先預告會看到什麼`() {
        // Android 11 之後這兩個設定頁都不吃套件參數，一定會跳到一長串清單。
        // 做不到一鍵，就先把他會看到的東西講清楚 —— 放棄的原因是預期落差。
        listOf(OnboardingStep.OVERLAY, OnboardingStep.USAGE).forEach { step ->
            val copy = OnboardingCopy.of(step)
            assertNotNull("$step 沒有預告", copy.headsUp)
            val headsUp = copy.headsUp.orEmpty()
            assertTrue("$step 的預告沒提到會看到一份清單", headsUp.contains("清單"))
            assertTrue("$step 的預告沒告訴他要找哪個名字", headsUp.contains("mindgohua"))
        }
    }

    @Test
    fun `一鍵就能完成的通知權限不預告清單`() {
        // 通知是 runtime permission，系統直接跳對話框。
        // 預告一個不會發生的事只會製造不安。
        assertNull(OnboardingCopy.of(OnboardingStep.NOTIFICATION).headsUp)
    }

    @Test
    fun `去過設定頁卻沒開成時有話可說`() {
        listOf(OnboardingStep.OVERLAY, OnboardingStep.USAGE).forEach { step ->
            val retry = OnboardingCopy.of(step).retry
            assertNotNull("$step 沒有準備『看起來還沒打開』那一段", retry)
            assertTrue("$step 的補充說明沒給他再試一次的出路", retry.orEmpty().contains("再按一次"))
        }
    }

    @Test
    fun `每個權限步驟都給得起先跳過`() {
        // 把人關在一個他找不到開關的頁面裡，換來的唯一出口是解除安裝。
        allSteps.filter { it.permission != null }.forEach { step ->
            assertNotNull("$step 沒有給使用者出路", OnboardingCopy.of(step).secondary)
        }
    }

    @Test
    fun `跳過或拒絕之後都說明代價而不是指責`() {
        allSteps.filter { it.isSkippable }.forEach { step ->
            assertNotNull("$step 跳過之後沒有交代會怎樣", OnboardingCopy.of(step).afterSkip)
        }
    }

    @Test
    fun `歡迎頁會說接下來共有幾件事`() {
        // 數字要跟著實際步數走：已經開好兩個權限的人不該被告知還有 5 件事。
        assertTrue(OnboardingCopy.of(OnboardingStep.WELCOME, totalSteps = 3).body.contains("3 件事"))
        assertTrue(OnboardingCopy.of(OnboardingStep.WELCOME, totalSteps = 5).body.contains("5 件事"))
    }

    @Test
    fun `隱私說明排在權限之後而不是之前`() {
        // 使用者對「你看得到什麼」的疑慮，是被要求權限之後才產生的。
        // 在他還沒被要求任何東西以前就先解釋，等於替他發明一個他還沒有的擔心。
        val welcome = OnboardingCopy.of(OnboardingStep.WELCOME).body
        assertFalse("歡迎頁不該先講隱私", welcome.contains("網路權限"))
        assertTrue(
            "結束頁少了隱私總結",
            OnboardingCopy.of(OnboardingStep.DONE).body.contains("網路權限"),
        )
    }

    @Test
    fun `使用情況存取權那一頁同時講看得到與看不到什麼`() {
        val bullets = OnboardingCopy.of(OnboardingStep.USAGE).bullets
        assertTrue(bullets.any { it.contains("看得到") })
        assertTrue(bullets.any { it.contains("看不到") })
    }

    @Test
    fun `沒選 app 的提示是說明而不是責備`() {
        val text = OnboardingCopy.NEEDS_APP_SELECTION
        assertTrue(text.contains("至少要選一個"))
        assertFalse("不要出現錯誤字眼", text.contains("錯誤"))
    }

    @Test
    fun `自動取消勾選時會告訴使用者發生了什麼`() {
        val text = OnboardingCopy.removedUninstalled(listOf("Instagram"))
        assertTrue(text.contains("Instagram"))
        assertTrue(text.contains("取消勾選"))
    }

    @Test
    fun `結束頁會列出還沒做完的事以及去哪裡補`() {
        val text = OnboardingCopy.unfinished(listOf("讓貓能蓋在別的 app 上面"))
        assertTrue(text.contains("1 件事"))
        assertTrue(text.contains("設定頁"))
    }
}
