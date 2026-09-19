package com.mindgohua.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引導流程的狀態機測試。
 *
 * 這裡守的全是**看不出來的錯**：畫面照樣會畫出來，只是把使用者卡在原地、
 * 或讓他一路走到最後才發現主開關仍然是灰的。沒有測試的話，
 * 這類改壞不會讓任何東西變紅。
 */
class OnboardingFlowTest {

    private val nothingGranted = OnboardingPermissions()
    private val allGranted = OnboardingPermissions(
        overlay = true,
        usageAccess = true,
        notifications = true,
    )

    private fun fresh(
        permissions: OnboardingPermissions = nothingGranted,
        targetCount: Int = 1,
    ) = OnboardingFlow.start(permissions, targetCount)

    /** 走到指定步驟（沿途需要的條件由呼叫端先準備好）。 */
    private fun OnboardingState.advanceTo(step: OnboardingStep): OnboardingState {
        var s = this
        var guard = 0
        while (s.step != step && guard++ < 20) {
            s = if (s.canAdvance) s.next() else s.skip()
        }
        assertEquals("走不到 $step", step, s.step)
        return s
    }

    // ---- 要不要顯示 ----

    @Test
    fun `第一次打開且什麼都還沒開時要顯示引導`() {
        assertTrue(OnboardingFlow.shouldShow(false, nothingGranted, targetCount = 0))
    }

    @Test
    fun `三個權限都開好且已經選過 app 時不顯示引導`() {
        // 更新版本之後才裝上引導的既有使用者。把他抓去重走一次五步流程，
        // 只會讓他覺得這個 app 把他的設定弄丟了。
        assertFalse(OnboardingFlow.shouldShow(false, allGranted, targetCount = 2))
    }

    @Test
    fun `權限都開好但一個 app 都沒選時仍要顯示引導`() {
        // 只看權限的話，這個人會直接掉進設定頁，
        // 對著一張「還沒有選任何 app」的紅字卡片自己想辦法。
        assertTrue(OnboardingFlow.shouldShow(false, allGranted, targetCount = 0))
    }

    @Test
    fun `已經走完引導就不再顯示 —— 即使權限被關掉`() {
        assertFalse(OnboardingFlow.shouldShow(true, nothingGranted, targetCount = 0))
    }

    // ---- 步驟清單 ----

    @Test
    fun `什麼都沒開時三個權限步驟都在流程裡`() {
        val steps = OnboardingFlow.stepsFor(nothingGranted)
        assertTrue(OnboardingStep.OVERLAY in steps)
        assertTrue(OnboardingStep.USAGE in steps)
        assertTrue(OnboardingStep.NOTIFICATION in steps)
    }

    @Test
    fun `已經開好的權限不會再被問一次`() {
        val steps = OnboardingFlow.stepsFor(OnboardingPermissions(overlay = true))
        assertFalse("懸浮視窗已經開好了，不該再帶他去一次", OnboardingStep.OVERLAY in steps)
        assertTrue(OnboardingStep.USAGE in steps)
    }

    @Test
    fun `預覽貓與選 app 永遠在流程裡`() {
        val steps = OnboardingFlow.stepsFor(allGranted)
        assertTrue(OnboardingStep.PICK_APPS in steps)
        assertTrue(OnboardingStep.PREVIEW_CAT in steps)
    }

    @Test
    fun `歡迎頁與結束頁不算進步數`() {
        assertEquals(5, fresh().totalSteps)
        assertEquals("歡迎頁不顯示進度", 0, fresh().stepNumber)
    }

    @Test
    fun `已經開好的權限不計入總步數`() {
        // 「還要我做幾件事」才是使用者想知道的數字，
        // 「共 5 步，其中一步已完成」是工程師的講法。
        assertEquals(4, fresh(OnboardingPermissions(overlay = true)).totalSteps)
    }

    @Test
    fun `進度編號跟著實際步驟走`() {
        val atPick = fresh().next()
        assertEquals(OnboardingStep.PICK_APPS, atPick.step)
        assertEquals(1, atPick.stepNumber)
        val atOverlay = atPick.next()
        assertEquals(OnboardingStep.OVERLAY, atOverlay.step)
        assertEquals(2, atOverlay.stepNumber)
    }

    // ---- 不能前進的情況 ----

    @Test
    fun `一個 app 都沒選時不能離開選 app 那一步`() {
        val s = fresh(targetCount = 0).next()
        assertEquals(OnboardingStep.PICK_APPS, s.step)
        assertEquals(OnboardingBlocker.NEEDS_APP_SELECTION, s.blocker)
        assertEquals("按下一步不該有任何動靜", s.step, s.next().step)
    }

    @Test
    fun `選了一個 app 之後就能前進`() {
        val s = fresh(targetCount = 0).next().withTargetCount(1)
        assertNull(s.blocker)
        assertEquals(OnboardingStep.OVERLAY, s.next().step)
    }

    @Test
    fun `缺權限時不能進到下一步`() {
        val s = fresh().advanceToOverlay()
        assertEquals(OnboardingBlocker.NEEDS_PERMISSION, s.blocker)
        assertEquals(OnboardingStep.OVERLAY, s.next().step)
    }

    @Test
    fun `選 app 那一步不給跳過`() {
        // 一個 app 都沒選就走完流程，等於帶著一個不會動的 app 離開。
        val s = fresh(targetCount = 0).next()
        assertFalse(s.step.isSkippable)
        assertEquals(OnboardingStep.PICK_APPS, s.skip().step)
    }

    // ---- 從設定頁回來 ----

    @Test
    fun `從設定頁回來偵測到權限已開就自動前進`() {
        // 這是整個引導的價值所在。refreshPermissions 本來就在跑，
        // 缺的一直是「偵測到之後自動往下走」。
        val s = fresh().advanceToOverlay().markSettingsOpened()
        val back = s.withPermissions(OnboardingPermissions(overlay = true))
        assertEquals(OnboardingStep.PREVIEW_CAT, back.step)
    }

    @Test
    fun `從設定頁回來但權限還是沒開時留在原地`() {
        val s = fresh().advanceToOverlay().markSettingsOpened()
        val back = s.withPermissions(nothingGranted)
        assertEquals(OnboardingStep.OVERLAY, back.step)
        assertTrue("要顯示『看起來還沒打開』那段補充說明", back.returnedWithoutGranting)
    }

    @Test
    fun `還沒送他去過設定頁時不顯示補充說明`() {
        // 第一次就講「清單是照名稱排的」，是在回答他還沒遇到的問題。
        assertFalse(fresh().advanceToOverlay().returnedWithoutGranting)
    }

    @Test
    fun `中途順手開好的後面那個權限不會再問一次`() {
        val s = fresh().advanceToOverlay().markSettingsOpened()
        // 使用者在系統設定裡一次把兩個都開了
        val back = s.withPermissions(OnboardingPermissions(overlay = true, usageAccess = true))
        assertEquals(OnboardingStep.PREVIEW_CAT, back.step)
        assertEquals("使用情況存取權已經開好，不該再帶他看一次", OnboardingStep.NOTIFICATION, back.next().step)
    }

    // ---- 中途被撤銷 ----

    @Test
    fun `先前開好的權限被關掉會退回那一步`() {
        val s = fresh()
            .advanceToOverlay()
            .withPermissions(OnboardingPermissions(overlay = true)) // 自動前進到預覽
        assertEquals(OnboardingStep.PREVIEW_CAT, s.step)

        val revoked = s.withPermissions(nothingGranted)
        assertEquals("不退回去的話，他會走到最後才發現開關還是打不開", OnboardingStep.OVERLAY, revoked.step)
    }

    @Test
    fun `自己跳過的那一步不會被硬拉回來`() {
        val s = fresh().advanceToOverlay().skip()
        assertEquals(OnboardingStep.PREVIEW_CAT, s.step)
        // 權限仍然是關的，但他已經表達過意願了
        val after = s.withPermissions(nothingGranted)
        assertEquals(OnboardingStep.PREVIEW_CAT, after.step)
    }

    @Test
    fun `結束之後權限再怎麼變都不影響引導`() {
        // 引導不該從墳墓裡爬出來；之後由設定頁的權限卡片負責。
        val done = fresh(permissions = allGranted, targetCount = 1)
            .advanceTo(OnboardingStep.DONE)
            .next()
        assertTrue(done.finished)
        val after = done.withPermissions(nothingGranted)
        assertTrue(after.finished)
        assertEquals(OnboardingStep.DONE, after.step)
    }

    // ---- 跳過 ----

    @Test
    fun `跳過懸浮視窗之後可以繼續往下走`() {
        val s = fresh().advanceToOverlay().skip()
        assertEquals(OnboardingStep.PREVIEW_CAT, s.step)
        assertTrue(OnboardingStep.OVERLAY in s.skipped)
    }

    @Test
    fun `跳過懸浮視窗的人在預覽那一步叫不出貓`() {
        val s = fresh().advanceToOverlay().skip()
        assertFalse(s.canPreviewCat)
        assertTrue("叫不出貓也要能往下走", s.canAdvance)
    }

    @Test
    fun `結束頁列出還沒做完的事`() {
        val s = fresh()
            .advanceToOverlay()
            .skip()
            .advanceTo(OnboardingStep.DONE)
        assertTrue(OnboardingStep.OVERLAY in s.unfinishedSkips)
        assertTrue(OnboardingStep.USAGE in s.unfinishedSkips)
    }

    @Test
    fun `跳過之後又自己去開好的權限不算沒做完`() {
        val s = fresh()
            .advanceToOverlay()
            .skip()
            .withPermissions(OnboardingPermissions(overlay = true))
        assertFalse(OnboardingStep.OVERLAY in s.unfinishedSkips)
    }

    // ---- 前後移動與放棄 ----

    @Test
    fun `按上一步回到前一頁`() {
        val s = fresh().next()
        assertEquals(OnboardingStep.WELCOME, s.back().step)
    }

    @Test
    fun `在第一頁按上一步不會出事`() {
        val s = fresh()
        assertFalse(s.canGoBack)
        assertEquals(OnboardingStep.WELCOME, s.back().step)
    }

    @Test
    fun `從歡迎頁直接放棄會結束整個引導`() {
        // 這條出路一定要留著。沒有它，找不到系統開關的人就只剩解除安裝。
        assertTrue(fresh().abandon().finished)
    }

    @Test
    fun `走到最後一頁再按下一步就結束`() {
        val s = fresh(permissions = allGranted, targetCount = 1).advanceTo(OnboardingStep.DONE)
        assertFalse(s.finished)
        assertTrue(s.next().finished)
    }

    private fun OnboardingState.advanceToOverlay(): OnboardingState = advanceTo(OnboardingStep.OVERLAY)
}
