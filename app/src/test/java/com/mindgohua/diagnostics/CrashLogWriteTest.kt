package com.mindgohua.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 當機紀錄的**寫入路徑**測試。
 *
 * ## 為什麼這組測試存在
 *
 * 「app 當掉時真的會寫出檔案嗎」原本看起來只能靠故意讓程式崩潰來驗，
 * 而正式版裡不該留一顆「讓 app 當機」的按鈕。
 *
 * 但那是個假問題：崩潰只是**觸發條件**，真正要驗的是檔案有沒有被正確寫出來、
 * 輪替有沒有生效、讀得回來嗎 —— 而這些全都不需要 Android。
 * 所以 [CrashLog] 把檔案操作抽成只吃 [File] 的函式，這裡用一般單元測試直接驗。
 *
 * 仍然驗不到的部分（誠實記錄）：
 *  - `Thread.setDefaultUncaughtExceptionHandler` 有沒有真的被系統呼叫
 *  - 匯出時寫進使用者選的位置（SAF）能不能成功
 * 這兩項需要實機，屬於手動驗收清單。
 */
class CrashLogWriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): File = File(temp.root, CrashLog.DIR_NAME)

    @Test
    fun `目錄不存在時會自動建立並寫出檔案`() {
        val target = dir()
        assertFalse("前提：目錄一開始不該存在", target.exists())

        val written = CrashLog.writeInto(target, "內容 A", CrashLog.stamp(1_758_000_000_000L))

        assertNotNull("應該要寫出檔案", written)
        assertTrue("目錄應該被建立", target.isDirectory)
        assertEquals("內容 A", written!!.readText())
        assertTrue("檔名應以 crash- 開頭", written.name.startsWith("crash-"))
        assertTrue("副檔名應為 .txt", written.name.endsWith(".txt"))
    }

    @Test
    fun `紀錄依時間由新到舊排列`() {
        val target = dir()
        val base = 1_758_000_000_000L
        // 刻意打亂寫入順序，確保排序靠的是時間戳而不是寫入次序。
        listOf(2L, 0L, 1L).forEach { offset ->
            CrashLog.writeInto(target, "第 $offset 份", CrashLog.stamp(base + offset * 60_000L))
        }

        val names = CrashLog.reportsIn(target).map { it.readText() }
        assertEquals(listOf("第 2 份", "第 1 份", "第 0 份"), names)
    }

    @Test
    fun `超過上限時刪掉最舊的、保留最新的`() {
        val target = dir()
        val base = 1_758_000_000_000L
        val total = CrashLog.MAX_REPORTS + 5
        repeat(total) { i ->
            CrashLog.writeInto(target, "第 $i 份", CrashLog.stamp(base + i * 60_000L))
        }

        val kept = CrashLog.reportsIn(target)
        assertEquals("應只保留 ${CrashLog.MAX_REPORTS} 份", CrashLog.MAX_REPORTS, kept.size)

        // 保留的必須是「最新的那幾份」，不能是隨便幾份。
        val expected = (total - 1 downTo total - CrashLog.MAX_REPORTS).map { "第 $it 份" }
        assertEquals(expected, kept.map { it.readText() })
    }

    @Test
    fun `readAll 會把多份紀錄串起來且不遺漏`() {
        val target = dir()
        val base = 1_758_000_000_000L
        repeat(3) { i ->
            CrashLog.writeInto(target, "內容-$i", CrashLog.stamp(base + i * 60_000L))
        }

        val all = CrashLog.readAllIn(target)
        repeat(3) { i ->
            assertTrue("串接結果遺漏了 內容-$i", all.contains("內容-$i"))
        }
        assertEquals("應有 2 條分隔線", 2, all.split(CrashLog.SEPARATOR).size - 1)
    }

    @Test
    fun `沒有任何紀錄時 readAll 回傳空字串而不是崩潰`() {
        // 目錄根本不存在 —— 這是 app 從未當機過的正常狀態，最常見的情況。
        assertEquals("", CrashLog.readAllIn(dir()))
        assertTrue(CrashLog.reportsIn(dir()).isEmpty())
    }

    @Test
    fun `clear 會清空所有紀錄`() {
        val target = dir()
        val base = 1_758_000_000_000L
        repeat(3) { i -> CrashLog.writeInto(target, "x", CrashLog.stamp(base + i * 60_000L)) }
        assertEquals(3, CrashLog.reportsIn(target).size)

        CrashLog.clearIn(target)

        assertTrue("清除後不該還有紀錄", CrashLog.reportsIn(target).isEmpty())
        assertEquals("", CrashLog.readAllIn(target))
    }

    /**
     * 寫入是在「app 正在當掉」的路徑上執行的。
     * 那條路徑上再丟一個例外，會蓋掉原本真正的當機原因 —— 比沒有紀錄更糟。
     */
    @Test
    fun `寫入失敗時回傳 null 而不是丟例外`() {
        // 用一個「同名檔案」擋住目錄的建立，模擬寫不進去的情況。
        val blocked = File(temp.root, "blocked")
        blocked.writeText("我是檔案，不是目錄")

        val result = CrashLog.writeInto(File(blocked, "sub"), "內容", CrashLog.stamp(0L))

        assertNull("寫不進去時應回傳 null", result)
    }

    @Test
    fun `檔名時間戳的字典序必須等於時間序`() {
        // reportsIn 依檔名排序，所以這個性質一旦被破壞，「最新的紀錄」就會排錯，
        // 而輪替會從錯的那一端開始刪 —— 刪掉最新的、留下最舊的。
        val earlier = CrashLog.stamp(1_758_000_000_000L)
        val later = CrashLog.stamp(1_758_000_060_000L)
        assertTrue("$earlier 應排在 $later 之前", earlier < later)
    }
}
