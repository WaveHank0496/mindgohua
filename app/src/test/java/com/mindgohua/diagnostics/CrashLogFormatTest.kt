package com.mindgohua.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 當機紀錄的內容保證。
 *
 * 這組測試存在的理由跟 [com.mindgohua.PrivacyInvariantTest] 一樣：
 * 「當機報告裡不會夾帶使用者行為資料」如果只寫在註解裡，遲早有人為了方便除錯
 * 就把設定值、目標 app 清單、使用時數塞進去，而且不會有人發現。
 */
class CrashLogFormatTest {

    private fun sample(stack: String = "java.lang.IllegalStateException: boom\n\tat com.mindgohua.Foo.bar(Foo.kt:42)") =
        CrashLog.format(
            whenMs = 1_758_000_000_000L,
            appVersion = "0.1-prototype (1)",
            androidRelease = "13",
            sdkInt = 33,
            manufacturer = "OPPO",
            model = "CPH2371",
            threadName = "main",
            stackTrace = stack,
        )

    @Test
    fun `報告必須包含查錯所需的欄位`() {
        val text = sample()
        listOf("0.1-prototype (1)", "API 33", "OPPO", "CPH2371", "main", "IllegalStateException")
            .forEach { assertTrue("報告缺少必要欄位：$it", text.contains(it)) }
    }

    @Test
    fun `報告必須說明它不會自己外流`() {
        // 使用者打開這個檔案時，第一眼就要知道這東西沒有被偷偷送走。
        assertTrue(sample().contains("沒有網路權限"))
    }

    @Test
    fun `完整的堆疊追蹤必須保留`() {
        val stack = (1..50).joinToString("\n") { "\tat com.mindgohua.Frame$it.run(Frame$it.kt:$it)" }
        val text = sample("java.lang.RuntimeException: deep\n$stack")
        assertTrue("堆疊被截斷了", text.contains("Frame50"))
    }

    /**
     * 原始碼層級的不變量：[CrashLog] 不得碰任何存有使用者資料的類別。
     *
     * 這比檢查輸出字串更有力 —— 輸出只驗得到「這一份報告」沒夾帶資料，
     * 掃原始碼驗得到「它根本拿不到那些資料」。
     */
    @Test
    fun `CrashLog 不得相依於任何使用者資料來源`() {
        val source = File("src/main/java/com/mindgohua/diagnostics/CrashLog.kt")
        assertTrue("找不到 CrashLog.kt", source.exists())
        val code = source.readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")

        val forbidden = listOf(
            "SettingsStore", "AppSettings", "DailyStatsStore", "TargetApps",
            "SurvivalLog", "DetectorDebug",
        )
        val found = forbidden.filter { it in code }
        assertTrue("當機紀錄不得接觸使用者資料來源，卻引用了 $found", found.isEmpty())
    }

    @Test
    fun `當機紀錄不得寫到 app 私有目錄以外的地方`() {
        val code = File("src/main/java/com/mindgohua/diagnostics/CrashLog.kt").readText()
        // 外部儲存空間是其他 app 讀得到的地方，紀錄不該出現在那裡。
        listOf("getExternalFilesDir", "getExternalStorageDirectory", "Environment.DIRECTORY")
            .forEach { assertFalse("當機紀錄不得寫入外部儲存空間（$it）", code.contains(it)) }
    }
}
