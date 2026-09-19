package com.mindgohua.ui.onboarding

import com.mindgohua.settings.TargetApps

/**
 * 「要看住哪些 app」這一步的排序與清理 —— **純函式，不碰 PackageManager**。
 *
 * ## 要解決的問題
 *
 * 稽核指出：預設鎖死 Instagram，沒裝 IG 的人第一眼就看到紅色錯誤字。
 * 那行紅字說的是實話（那個套件確實不在這台手機上），但它指責的是使用者，
 * 而錯的其實是我們的預設值。
 *
 * 這裡用兩個純函式處理：
 *
 *  - [rank]：只建議**這台手機上真的有的** app。沒裝 IG 的人根本不會看到 IG。
 *  - [staleSelection]：把「已經勾選、但已經不在這台手機上」的套件挑出來，
 *    讓 UI 靜默取消勾選並說明一句，而不是留一行紅字讓使用者自己想辦法。
 *
 * ## 為什麼吃的是套件名字串而不是 `InstalledApps.AppEntry`
 *
 * 為了讓這一層跟 `settings` 套件完全脫鉤，測試時不需要載入任何
 * 會碰到 `Context` 的類別。顯示名稱交給 UI 那邊的 `InstalledAppsState.labelOf`。
 */
object OnboardingSuggestions {

    /**
     * 「會被拿來一直滑」的常見 app，依台灣一般使用者的常見程度排序。
     *
     * 這份清單**只影響排序**，不影響使用者能選什麼 —— 完整清單永遠在下面，
     * 搜尋框也永遠在。它存在的唯一理由是：多數人裝了上百個 app，
     * 第一次打開時攤開一整面清單，他會直接關掉。
     */
    val COMMON: List<String> = listOf(
        TargetApps.INSTAGRAM,
        THREADS,
        TargetApps.YOUTUBE,
        TargetApps.TIKTOK,
        TargetApps.TIKTOK_ALT,
        TargetApps.FACEBOOK,
        TargetApps.X,
        LINE,
        XIAOHONGSHU,
        TargetApps.REDDIT,
    )

    /**
     * 要優先顯示的建議清單：常見清單與「真的裝了」取交集，順序照 [COMMON]。
     *
     * 已經選了的一律保留在最前面，即使它不在常見清單裡 ——
     * 使用者剛剛才勾的東西，不該在下一次重組清單時消失到看不見的地方。
     */
    fun rank(installedPackages: Collection<String>, selected: Set<String> = emptySet()): List<String> {
        val installed = installedPackages.toSet()
        val head = selected.filter { it in installed }.sorted()
        val rest = COMMON.filter { it in installed && it !in selected }
        return head + rest
    }

    /**
     * 已經勾選、但這台手機上已經沒有的套件。
     *
     * **已安裝清單是空的時候一律回傳空**。空清單有兩種可能：
     * 使用者真的沒裝任何東西（不可能），或是我們還沒查到／查失敗。
     * 分不出來的時候就什麼都不要動 —— 因為這個函式的結果會拿去
     * 取消使用者的勾選，猜錯的代價是把他的設定清掉。
     */
    fun staleSelection(selected: Set<String>, installedPackages: Collection<String>): List<String> {
        if (installedPackages.isEmpty()) return emptyList()
        val installed = installedPackages.toSet()
        return selected.filter { it !in installed }.sorted()
    }
}

/** Threads。不在 [TargetApps] 的表裡，但它正是這個 app 最典型的使用情境。 */
private const val THREADS = "com.instagram.barcelona"

/** LINE。台灣使用者幾乎人人都有，而且貼文串一樣會讓人滑很久。 */
private const val LINE = "jp.naver.line.android"

/** 小紅書。 */
private const val XIAOHONGSHU = "com.xingin.xhs"
