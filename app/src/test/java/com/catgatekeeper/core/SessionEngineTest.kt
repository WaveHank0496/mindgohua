package com.catgatekeeper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session Engine 的驗收測試。對應 spec §8 的第 2、3、4 條。
 * 不需要實機、不需要 Android SDK —— `./gradlew test` 就能跑。
 */
class SessionEngineTest {

    private val config = EngineConfig(
        thresholdMs = 120_000L,   // 2 分鐘
        cooldownMs = 10_000L,     // 10 秒
        graceMs = 300_000L,       // 5 分鐘
    )

    private fun engine() = SessionEngine(config)

    private fun List<EngineEffect>.interrupt(): EngineEffect.Interrupt? =
        filterIsInstance<EngineEffect.Interrupt>().firstOrNull()

    // ---- 驗收 2：連續停留超過門檻 → 打斷 ----

    @Test
    fun `連續使用超過門檻會觸發打斷`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(isTarget = true, packageName = "ig", atMs = 0))

        assertEquals(null, e.process(SessionEvent.Tick(119_000)).interrupt())

        val fired = e.process(SessionEvent.Tick(120_000)).interrupt()
        assertTrue(fired != null)
        assertEquals(120_000L, fired!!.elapsedMs)
        assertEquals(InterruptReason.DURATION, fired.reason)
    }

    @Test
    fun `打斷只會發一次不會每秒重複`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        assertTrue(e.process(SessionEvent.Tick(120_000)).interrupt() != null)
        assertEquals(null, e.process(SessionEvent.Tick(122_000)).interrupt())
        assertEquals(null, e.process(SessionEvent.Tick(180_000)).interrupt())
    }

    // ---- 驗收 3：短暫切出再切回，計時不歸零 ----

    @Test
    fun `冷卻期內切回來計時繼續累計`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        // 滑了 90 秒後切出去
        e.process(SessionEvent.ForegroundChanged(false, null, 90_000))
        // 5 秒後切回來（< 10 秒冷卻）
        e.process(SessionEvent.ForegroundChanged(true, "ig", 95_000))

        // 只要再 30 秒就滿 120 秒，而不是重新從 0 開始
        val fired = e.process(SessionEvent.Tick(125_000)).interrupt()
        assertTrue("冷卻期內回來應該續計，卻沒有觸發", fired != null)
        assertEquals(120_000L, fired!!.elapsedMs)
    }

    @Test
    fun `超過冷卻才回來會開新的session`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.ForegroundChanged(false, null, 90_000))
        // 30 秒後才回來（> 10 秒冷卻）→ 前面 90 秒作廢
        e.process(SessionEvent.ForegroundChanged(true, "ig", 120_000))

        assertEquals(null, e.process(SessionEvent.Tick(150_000)).interrupt())
        assertEquals(30_000L, e.snapshot(150_000).elapsedMs)

        // 從 120_000 起算滿 2 分鐘才觸發
        assertTrue(e.process(SessionEvent.Tick(240_000)).interrupt() != null)
    }

    @Test
    fun `冷卻結束後session歸零`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.ForegroundChanged(false, null, 60_000))
        e.process(SessionEvent.Tick(75_000)) // 超過 10 秒冷卻

        val snap = e.snapshot(75_000)
        assertEquals(false, snap.inSession)
        assertEquals(0L, snap.elapsedMs)
    }

    // ---- 驗收 4：解除後 GRACE 期內不再打斷 ----

    @Test
    fun `解除後在GRACE期內不會再打斷`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        assertTrue(e.process(SessionEvent.Tick(120_000)).interrupt() != null)

        e.process(SessionEvent.InterruptDismissed(120_000, DismissChoice.SNOOZE))

        // 又滑了 2 分鐘，但還在 5 分鐘 GRACE 內 → 不打斷
        assertEquals(null, e.process(SessionEvent.Tick(240_000)).interrupt())
        // GRACE 到期（120_000 + 300_000 = 420_000）之後才會再打斷
        assertEquals(null, e.process(SessionEvent.Tick(419_000)).interrupt())
        assertTrue(e.process(SessionEvent.Tick(420_000)).interrupt() != null)
    }

    @Test
    fun `解除後計時重新起算`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.Tick(120_000))
        e.process(SessionEvent.InterruptDismissed(120_000, DismissChoice.SNOOZE))

        assertEquals(30_000L, e.snapshot(150_000).elapsedMs)
    }

    // ---- overlay 生命週期 ----

    @Test
    fun `離開目標app時overlay要收掉`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.Tick(120_000))

        val effects = e.process(SessionEvent.ForegroundChanged(false, null, 121_000))
        assertTrue(effects.contains(EngineEffect.HideInterrupt))
        assertEquals(false, e.snapshot(121_000).interruptShowing)
    }

    // ---- 目標 app 之間互跳 ----

    @Test
    fun `IG跳到YouTube算同一段連續使用`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.ForegroundChanged(true, "youtube", 60_000))

        val fired = e.process(SessionEvent.Tick(120_000)).interrupt()
        assertTrue(fired != null)
        assertEquals("youtube", fired!!.packageName)
    }

    // ---- Mode B 訊號走同一套下游 ----

    @Test
    fun `Mode B的無意識刷動訊號可直接觸發打斷`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))

        // 才 30 秒，遠不到 2 分鐘門檻，但偵測器說這是無意識刷動
        val fired = e.process(SessionEvent.UnconsciousScrollDetected(30_000)).interrupt()
        assertTrue(fired != null)
        assertEquals(InterruptReason.SCROLL_RHYTHM, fired!!.reason)
    }

    @Test
    fun `不在目標app時Mode B訊號不觸發`() {
        val e = engine()
        assertEquals(null, e.process(SessionEvent.UnconsciousScrollDetected(30_000)).interrupt())
    }

    @Test
    fun `GRACE期內Mode B訊號也不觸發`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.Tick(120_000))
        e.process(SessionEvent.InterruptDismissed(120_000, DismissChoice.STOP))

        assertEquals(null, e.process(SessionEvent.UnconsciousScrollDetected(200_000)).interrupt())
    }

    // ---- 設定熱套用 ----

    @Test
    fun `門檻改小之後下一個tick就生效`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.Tick(60_000))
        assertEquals(null, e.process(SessionEvent.Tick(61_000)).interrupt())

        e.process(SessionEvent.ConfigChanged(config.copy(thresholdMs = 30_000L)))
        assertTrue(e.process(SessionEvent.Tick(62_000)).interrupt() != null)
    }

    @Test
    fun `服務停止會收掉overlay並清空session`() {
        val e = engine()
        e.process(SessionEvent.ForegroundChanged(true, "ig", 0))
        e.process(SessionEvent.Tick(120_000))

        val effects = e.process(SessionEvent.Stopped)
        assertTrue(effects.contains(EngineEffect.HideInterrupt))
        assertEquals(false, e.snapshot(120_000).inSession)
    }
}
