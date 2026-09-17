package com.mindgohua.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

/**
 * 列出使用者可以挑選的 app。
 *
 * ## 為什麼不用 QUERY_ALL_PACKAGES
 *
 * Android 11（API 30）之後，app 預設看不到手機上其他 app 的存在。
 * 要看到全部，官方做法是宣告 `QUERY_ALL_PACKAGES` —— 但那是 Google Play
 * 的**列管敏感權限**，上架時必須填表說明用途，而且核准與否不在我們手上。
 *
 * 這個 app 的需求其實比「全部」小得多：使用者想看住的是**會拿來滑的 app**，
 * 而那些一定有桌面圖示。所以我們改成查詢「有 LAUNCHER 入口的 app」——
 * 用 manifest 的 `<queries><intent>` 宣告即可，不需要任何敏感權限。
 *
 * 代價是查不到沒有桌面圖示的東西（系統服務、背景元件），
 * 但那些本來就不是人會拿來滑的，對本 app 的用途沒有損失。
 *
 * ## 為什麼排序邏輯抽成純函式
 *
 * [sortForPicker] 和 [filterByQuery] 不碰 [Context]、不碰 PackageManager，
 * 只吃一個 [AppEntry] 清單。這讓「已選的要排前面」「搜尋要不分大小寫」
 * 這些會被使用者直接感受到的行為，可以用一般單元測試釘住
 * （見 `InstalledAppsTest`），不需要手機或模擬器。
 *
 * 這是這個專案一貫的做法：能被測試守住的保證，就不要只寫在註解裡。
 */
object InstalledApps {

    /**
     * 一個可挑選的 app。
     *
     * @param packageName 套件名，例如 `com.instagram.android`。這是唯一的識別。
     * @param label 顯示名稱，由系統提供，會跟著使用者的語言走。
     */
    data class AppEntry(
        val packageName: String,
        val label: String,
    )

    /**
     * 查詢手機上所有有桌面圖示的 app。
     *
     * 會排除本 app 自己 —— 讓使用者「看住 mindgohua」沒有意義，
     * 而且會造成 overlay 蓋住自己設定頁的詭異情況。
     *
     * 這個函式會碰 IPC（跨行程查詢），在低階機器上可能要數百毫秒，
     * **不要在主執行緒呼叫**。
     */
    fun query(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // flag 必須是 0，**不可以用 MATCH_DEFAULT_ONLY**。
        //
        // MATCH_DEFAULT_ONLY 的意思是「只回傳有宣告 CATEGORY_DEFAULT 的 activity」，
        // 而桌面圖示的入口宣告的是 CATEGORY_LAUNCHER —— 兩者是不同的 category，
        // 沒有任何規定要求 launcher activity 必須同時宣告 DEFAULT。
        //
        // 實測（OPPO Reno7）：系統對 MAIN/LAUNCHER 共解析出 185 個套件，
        // 但加上 MATCH_DEFAULT_ONLY 之後，Instagram、Threads、Facebook 等
        // 一大批 app 會**整個消失**，而且不會有任何錯誤 —— 清單看起來正常，
        // 只是少了東西。這是最難發現的一種 bug。
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrElse { emptyList() }

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                // 同一個 app 可能有多個 LAUNCHER 入口（例如雙開、工作設定檔），
                // 這裡只認套件名，後面 distinctBy 會收斂掉重複的。
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull()
                AppEntry(
                    packageName = pkg,
                    label = label?.takeIf { it.isNotBlank() } ?: pkg,
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    /**
     * 挑選頁的排序：**已選的排最前面**，其餘依顯示名稱排。
     *
     * 為什麼已選的要置頂：使用者裝了上百個 app 時，
     * 「我到底選了哪些」比「還能選哪些」更需要一眼看到。
     * 取消勾選也才不用再搜尋一次。
     *
     * 名稱排序刻意用 [String.lowercase] 比較，否則大寫開頭的英文 app
     * 會全部排在小寫之前，看起來像亂序。
     */
    fun sortForPicker(entries: List<AppEntry>, selected: Set<String>): List<AppEntry> =
        entries.sortedWith(
            compareByDescending<AppEntry> { it.packageName in selected }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )

    /**
     * 搜尋過濾。比對顯示名稱與套件名，皆不分大小寫。
     *
     * 會一併比對套件名，是因為有些 app 的顯示名稱跟使用者記得的不一樣
     * （例如 TikTok 的套件名是 `com.zhiliaoapp.musically`），
     * 而進階使用者可能直接輸入套件名來找。
     *
     * 空字串或只有空白時回傳原清單，不做任何過濾。
     */
    fun filterByQuery(entries: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }
}
