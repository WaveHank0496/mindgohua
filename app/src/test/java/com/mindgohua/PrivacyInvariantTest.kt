package com.mindgohua

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec §0 的核心原則是「不可違背」的，所以把它們變成會失敗的測試，而不是註解裡的君子協定。
 *
 * 有人哪天為了「加個崩潰回報」而動到這幾個地方，CI 就會擋下來。
 *
 * 單元測試的工作目錄是 module 目錄（app/），所以相對路徑指得到原始碼。
 */
class PrivacyInvariantTest {

    private val manifest = File("src/main/AndroidManifest.xml")
    private val accessibilityConfig = File("src/main/res/xml/accessibility_service_config.xml")
    private val buildFile = File("build.gradle.kts")

    @Test
    fun `manifest 不得宣告 INTERNET 權限`() {
        assertTrue("找不到 AndroidManifest.xml", manifest.exists())
        val text = manifest.readText()

        // INTERNET 只允許以 tools:node="remove" 的形式出現 —— 那一行的作用是
        // 叫 manifest merger 把它拔掉，是強制手段，不是宣告。
        Regex("""<uses-permission[^>]*android:name="android\.permission\.INTERNET"[^>]*/>""")
            .findAll(text)
            .forEach { match ->
                assertTrue(
                    "違反 spec §0.2：宣告了 INTERNET 權限。" +
                        "唯一允許的形式是 tools:node=\"remove\"。\n${match.value}",
                    match.value.contains("tools:node=\"remove\""),
                )
            }

        assertFalse(
            "違反 spec §0.2：不得宣告 ACCESS_NETWORK_STATE",
            text.contains("\"android.permission.ACCESS_NETWORK_STATE\""),
        )
    }

    @Test
    fun `必須主動移除任何函式庫注入的 INTERNET 權限`() {
        // 這道防線擋的是原始碼掃描看不見的東西：
        // 第三方函式庫可以透過 manifest 合併把 INTERNET 加進最終的 AAB，
        // 而「只讀 src/main/AndroidManifest.xml」的測試會全程顯示綠燈。
        //
        // 少了這一行，「本 app 無法連網」就從系統強制降級成君子協定。
        val text = manifest.readText()
        assertTrue(
            "少了 <uses-permission android:name=\"android.permission.INTERNET\" " +
                "tools:node=\"remove\" /> —— 沒有它，任何函式庫都能讓這個 app 連網",
            Regex("""android:name="android\.permission\.INTERNET"[^>]*tools:node="remove"""")
                .containsMatchIn(text),
        )
    }

    @Test
    fun `不得宣告任何無障礙服務`() {
        // 這條取代了原本「無障礙服務不得讀取視窗內容」的測試 —— v1 直接把整個
        // AccessibilityService 移除，所以要守的不再是「它有沒有規矩」，
        // 而是「它根本不存在」。
        //
        // 為什麼值得用測試釘住：Play 掃的是上傳的 AAB 裡的**合併後** manifest，
        // 不是 app 的畫面。把功能從 UI 隱藏起來對掃描結果沒有任何影響 ——
        // 只要宣告還在，就會觸發無障礙用途宣告，而那是拒絕率最高的類別之一。
        val text = manifest.readText()
        assertFalse(
            "manifest 宣告了 AccessibilityService —— v1 不該有，見 AndroidManifest 裡的說明",
            text.contains("android.accessibilityservice.AccessibilityService"),
        )
        assertFalse(
            "manifest 宣告了 BIND_ACCESSIBILITY_SERVICE",
            text.contains("BIND_ACCESSIBILITY_SERVICE"),
        )
    }

    @Test
    fun `不得備份任何資料 —— 包含 Android 11`() {
        // android:dataExtractionRules 只有 Android 12 以上才生效，
        // 而本專案 minSdk 是 30（Android 11）。少了 fullBackupContent，
        // 「設定不出這台手機」這個承諾在 Android 11 使用者身上是破的。
        //
        // allowBackup 已經是 false，這裡是第二道防線 ——
        // 隱私保證不該只靠單一個布林值撐著。
        val text = manifest.readText()

        assertTrue(
            "違反 spec §0：allowBackup 必須明確為 false",
            text.contains("android:allowBackup=\"false\""),
        )
        assertTrue(
            "Android 12 以上的備份規則遺失",
            text.contains("android:dataExtractionRules="),
        )
        assertTrue(
            "Android 11 的備份規則遺失 —— dataExtractionRules 對 API 30 無效",
            text.contains("android:fullBackupContent="),
        )

        // 兩份規則都必須真的排除所有 domain，不能只是存在而內容是空的。
        listOf(
            File("src/main/res/xml/data_extraction_rules.xml"),
            File("src/main/res/xml/backup_rules.xml"),
        ).forEach { rules ->
            assertTrue("找不到 ${rules.name}", rules.exists())
            val body = rules.readText()
            listOf("root", "database", "sharedpref", "file", "external").forEach { domain ->
                assertTrue(
                    "${rules.name} 沒有排除 domain=$domain",
                    body.contains("domain=\"$domain\""),
                )
            }
        }
    }

    @Test
    fun `隱私文案不得宣稱做不到的事`() {
        // 為什麼這條值得用測試守住：
        //
        // 這幾句話寫在設定頁的「這個 app 看得到什麼」卡片上，是最容易被
        // 原封不動複製進隱私政策與商店說明的那種句子。而 Play 會比對
        // 商店說明、app 內文案、Data Safety 表單三者是否一致 ——
        // 不一致時被抓的通常是文案，而那是會下架的等級。
        //
        // 這不是假想的風險。移除 Mode B 的時候，兩句「曾經為真」的話
        // 在沒有任何人動到它們的情況下變成了不實陳述，而 146 個測試
        // 一個都沒紅。是外部審查才發現的。
        val screen = File("src/main/java/com/mindgohua/ui/MainScreen.kt")
        assertTrue("找不到 MainScreen.kt", screen.exists())
        val code = screen.readLines()
            .map { it.substringBefore("//") }
            .joinToString("\n")

        assertFalse(
            "「一律略過不讀」只對已移除的 Mode B 成立。Mode A 走訪系統送來的" +
                "所有前景事件才丟棄非目標的那些 —— 「讀了才丟」不是「沒讀」。",
            code.contains("略過不讀"),
        )
        assertFalse(
            "「不寫入任何檔案」與事實不符：SettingsStore 持續在寫，" +
                "SurvivalLog 每 60 秒寫一次，CrashLog 當機時寫。",
            code.contains("不寫入任何檔案"),
        )
        assertFalse(
            "設定頁不該再提到已經不存在的無障礙服務。",
            code.contains("無障礙"),
        )
    }

    @Test
    fun `不得引入需要網路的函式庫`() {
        // 只看實際的相依宣告，註解裡提到這些名字（例如「不要用 analytics SDK」）不算。
        val text = buildFile.readLines()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .lowercase()
        val banned = listOf(
            "retrofit", "okhttp", "ktor", "volley",
            "firebase", "crashlytics", "analytics",
            "com.google.android.gms",
        )
        val found = banned.filter { it in text }
        assertTrue("違反 spec §9：引入了需要網路的相依 $found", found.isEmpty())
    }
}
