package com.catgatekeeper

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
        assertFalse(
            "違反 spec §0.2：不得宣告 INTERNET 權限",
            text.contains("\"android.permission.INTERNET\""),
        )
        assertFalse(
            "違反 spec §0.2：不得宣告 ACCESS_NETWORK_STATE",
            text.contains("\"android.permission.ACCESS_NETWORK_STATE\""),
        )
    }

    @Test
    fun `無障礙服務不得具備讀取視窗內容的能力`() {
        val text = accessibilityConfig.readText()
        assertTrue(
            "違反 spec §10：canRetrieveWindowContent 必須明確為 false",
            text.contains("android:canRetrieveWindowContent=\"false\""),
        )
        assertFalse(
            "違反 spec §10：不得監聽捲動以外的事件型別",
            text.contains("typeWindowContentChanged") || text.contains("typeAllMask"),
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
