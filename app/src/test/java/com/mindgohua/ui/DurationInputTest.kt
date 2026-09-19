package com.mindgohua.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 時間輸入的解析規則。
 *
 * 這些規則錯了不會當機，只會讓設定悄悄變成一個奇怪的值 ——
 * 而使用者不會知道發生了什麼事。正是需要測試守住的那種東西。
 */
class DurationInputTest {

    // ---- 基本解析 ----

    @Test
    fun `一般數字正常解析`() {
        assertEquals(10, DurationInput.parse("10", 0, 60))
    }

    @Test
    fun `前後空白會被忽略`() {
        assertEquals(10, DurationInput.parse("  10  ", 0, 60))
    }

    @Test
    fun `全形數字也要看得懂`() {
        // 手機中文輸入法很容易打出全形數字，而使用者完全看不出差別，
        // 只會覺得「我明明有打字卻沒反應」。
        assertEquals(10, DurationInput.parse("１０", 0, 60))
    }

    // ---- 無法解析時不可以亂塞值 ----

    @Test
    fun `空字串回傳 null 而不是 0`() {
        // 這是這次要修掉的 bug 的核心：看不懂就不要動，不要塞 0。
        assertNull(DurationInput.parse("", 0, 60))
    }

    @Test
    fun `純空白回傳 null`() {
        assertNull(DurationInput.parse("   ", 0, 60))
    }

    @Test
    fun `非數字回傳 null`() {
        assertNull(DurationInput.parse("abc", 0, 60))
        assertNull(DurationInput.parse("10分鐘", 0, 60))
    }

    @Test
    fun `負號被視為非數字而不是負值`() {
        assertNull(DurationInput.parse("-5", 0, 60))
    }

    @Test
    fun `小數點被視為非數字`() {
        assertNull(DurationInput.parse("1.5", 0, 60))
    }

    // ---- 範圍限制 ----

    @Test
    fun `超過上限會被夾到上限`() {
        assertEquals(60, DurationInput.parse("999", 0, 60))
    }

    @Test
    fun `低於下限會被夾到下限`() {
        assertEquals(30, DurationInput.parse("5", 30, 3600))
    }

    // ---- 分秒合成 ----

    @Test
    fun `分與秒合成總秒數`() {
        assertEquals(630, DurationInput.parseMinutesSeconds("10", "30", 30, 3600))
    }

    @Test
    fun `只填分鐘時秒數算 0`() {
        assertEquals(600, DurationInput.parseMinutesSeconds("10", "", 30, 3600))
    }

    @Test
    fun `只填秒數時分鐘算 0`() {
        assertEquals(45, DurationInput.parseMinutesSeconds("", "45", 30, 3600))
    }

    @Test
    fun `兩欄都空回傳 null`() {
        // 使用者打開對話框又什麼都沒填就按確定 —— 不該把門檻改成 0。
        assertNull(DurationInput.parseMinutesSeconds("", "", 30, 3600))
    }

    @Test
    fun `任一欄無法解析就整個回傳 null`() {
        assertNull(DurationInput.parseMinutesSeconds("abc", "30", 30, 3600))
        assertNull(DurationInput.parseMinutesSeconds("10", "xyz", 30, 3600))
    }

    @Test
    fun `秒數超過 59 會被夾到 59 而不是進位`() {
        // 進位比較「聰明」，但會讓使用者看到自己沒輸入的數字。
        // 夾住至少是可預期的。
        assertEquals(659, DurationInput.parseMinutesSeconds("10", "99", 30, 3600))
    }

    @Test
    fun `合成後低於下限會被夾到下限`() {
        assertEquals(30, DurationInput.parseMinutesSeconds("0", "5", 30, 3600))
    }

    @Test
    fun `合成後超過上限會被夾到上限`() {
        assertEquals(3600, DurationInput.parseMinutesSeconds("999", "0", 30, 3600))
    }
}
