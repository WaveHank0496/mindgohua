package com.mindgohua.ui.onboarding

/**
 * 引導流程每一頁的**確切文案**。
 *
 * ## 為什麼文案是純資料，而不是直接寫在 Composable 裡
 *
 * 這個引導的成敗幾乎完全取決於這些字 —— 不是版面，不是動畫，
 * 而是「按下去之後會發生什麼」這一段有沒有先講清楚。
 *
 * 文案會被反覆修改，而修改時最容易不小心弄丟的，正是那些看不出來的規則：
 * 標題悄悄變回工程術語、某一頁的「先跳過」被刪掉、
 * 會跳到系統設定的步驟忘了附上預告。這些改動不會讓任何東西變紅。
 *
 * 所以文案放在這裡，讓 `OnboardingCopyTest` 可以把那些規則釘死。
 *
 * ## 寫給誰
 *
 * 一個台灣的一般人，不懂技術。工程詞可以出現，但**必須放在括號裡當註解**，
 * 而且括號裡要放他在系統設定頁上真的會看到的那幾個字 ——
 * 他需要的不是解釋，是對照。
 */
data class StepCopy(
    /** 大標。這裡不准出現工程術語。 */
    val title: String,

    /** 一段話講完這一步在做什麼、為什麼需要。 */
    val body: String,

    /** 條列補充。通常是「看得到什麼／看不到什麼」。 */
    val bullets: List<String> = emptyList(),

    /** 主要按鈕。 */
    val primary: String,

    /** 主要按鈕在動作做過一次之後的字（目前只有預覽貓那一頁用得到）。 */
    val primaryAfter: String? = null,

    /** 次要出路。權限步驟一定要有，不然就是把人關在頁面裡。 */
    val secondary: String? = null,

    /**
     * 送他去系統設定**之前**的預告。
     *
     * Android 11 開始，`ACTION_MANAGE_OVERLAY_PERMISSION` 與
     * `ACTION_USAGE_ACCESS_SETTINGS` 都不吃套件參數，一律只會跳到
     * 列著一堆 app 的最上層清單 —— 沒有「一鍵開啟」，也做不出來。
     *
     * 既然做不到，就在送他出去之前先說。使用者放棄的原因是預期落差，不是步驟數。
     */
    val headsUp: String? = null,

    /**
     * 去過系統設定、回來卻還是沒開成時補上的那一段。
     *
     * 刻意跟 [headsUp] 分開：第一次就把「清單是照名稱排的」講出來，
     * 是在回答他還沒遇到的問題；他繞了一圈回來之後才講，才是在解決他當下的困惑。
     */
    val retry: String? = null,

    /** 使用者拒絕或跳過之後，安撫並說明代價的那一句。 */
    val afterSkip: String? = null,
)

object OnboardingCopy {

    /**
     * @param totalSteps 「接下來有幾件事」。會跟著實際步數變 ——
     *   已經開好兩個權限的人看到的是 3 件事，不是 5 件。
     */
    fun of(step: OnboardingStep, totalSteps: Int = 5): StepCopy = when (step) {

        OnboardingStep.WELCOME -> StepCopy(
            title = "一隻貓，負責在你滑太久的時候擋住你",
            body = "你設定一個時間，例如 15 分鐘。當你在 Instagram、YouTube 這類 app 裡" +
                "連續待超過那個時間，會有一隻貓蓋住整個畫面，問你要不要繼續。\n\n" +
                "接下來有 $totalSteps 件事要設定，大概兩分鐘。",
            primary = "好，開始設定",
            // 這條出路一定要留著。沒有它，一個找不到系統開關的人就只剩解除安裝這個出口。
            secondary = "我自己來（跳過引導）",
        )

        OnboardingStep.PICK_APPS -> StepCopy(
            title = "你想被擋住的是哪幾個 app？",
            body = "先挑一個最花你時間的就好，之後隨時能加。\n" +
                "沒有勾到的 app，這個程式完全不會去理它。",
            primary = "下一步",
        )

        OnboardingStep.OVERLAY -> StepCopy(
            title = "讓貓能蓋在別的 app 上面",
            body = "貓要能出現在你剛剛選的那些 app 畫面上，需要你去系統設定裡打開一個開關" +
                "（系統裡叫「顯示在其他應用程式上層」，有些手機寫「懸浮視窗」）。",
            bullets = listOf(
                "它只讓這個程式「畫東西在上面」，讀不到畫面上的任何內容。",
            ),
            primary = "去打開這個開關",
            secondary = "先跳過這一步",
            headsUp = "按下去之後，手機會跳到一個列著很多 app 的清單。\n" +
                "往下找到「mindgohua」，點它，把開關打開，然後按返回鍵回來 ——\n" +
                "回到這裡會自動偵測，你不用再按任何東西。",
            retry = "看起來還沒打開。那個清單是照 app 名稱排的，往下捲找「mindgohua」。" +
                "不確定的話再按一次，我再帶你去一次。",
            afterSkip = "沒關係。沒有這個開關，貓就沒辦法出現 —— " +
                "之後在設定頁的「權限」那一區隨時可以補。",
        )

        OnboardingStep.PREVIEW_CAT -> StepCopy(
            title = "先看看牠長什麼樣",
            body = "不用真的滑滿十五分鐘。按一下，貓現在就出現，" +
                "你可以先確認牠蓋得住畫面、按鈕也按得到。",
            primary = "讓貓出現一次",
            primaryAfter = "看過了，下一步",
            secondary = "跳過",
            afterSkip = "剛剛那個開關還沒打開，所以現在叫不出貓。" +
                "之後在設定頁補上就能在這裡試。",
        )

        OnboardingStep.USAGE -> StepCopy(
            title = "讓它知道你現在正在用哪個 app",
            body = "這個程式要能分辨你是在 Instagram 裡面，還是在別的地方 ——" +
                "這需要一個叫「使用情況存取權」的權限。",
            bullets = listOf(
                "它看得到：哪個 app 現在在最上面、你在裡面待了多久",
                "它看不到：你在裡面看了什麼、按了什麼、跟誰聊天",
            ),
            primary = "去打開這個開關",
            secondary = "先跳過這一步",
            headsUp = "一樣會跳到一個列著手機上所有 app 的清單" +
                "（有些手機叫「有權存取使用情況」）。\n" +
                "找到「mindgohua」，點它，把開關打開，再按返回鍵回來。",
            retry = "看起來還沒打開。清單很長，可以先找找看有沒有搜尋按鈕，" +
                "或往下捲找「mindgohua」。不確定的話再按一次。",
            afterSkip = "沒關係。沒有這個權限，程式沒辦法知道你正在用哪個 app，" +
                "之後在設定頁隨時可以補。",
        )

        OnboardingStep.NOTIFICATION -> StepCopy(
            title = "一則不會響的通知",
            body = "Android 規定：程式要一直在背景跑，就必須在通知欄留一則通知。" +
                "這是系統的要求，不是我們想通知你什麼。這則通知不會響，也不會震動。",
            primary = "允許通知",
            secondary = "不給也可以",
            // 這一步是唯一真正「一鍵」的：系統直接跳對話框，不會跳到任何清單。
            // 所以刻意不給 headsUp —— 預告一個不會發生的事只會製造不安。
            retry = "剛剛那個對話框如果按到了拒絕，就先這樣吧，不影響主要功能。",
            afterSkip = "沒關係。少了那則通知，程式還是會跑，" +
                "只是萬一它被系統關掉，你會比較晚發現。",
        )

        OnboardingStep.DONE -> StepCopy(
            title = "好了，可以把貓放出來了",
            // 隱私的總結放在這裡，而不是流程一開始。
            // 使用者對「你看得到什麼」的疑慮，是在被要求權限**之後**才產生的；
            // 在他還沒被要求任何東西以前就先解釋，等於替他發明一個他還沒有的擔心。
            body = "剛剛那幾個權限，這個程式實際拿來做的事只有一件：" +
                "看哪個 app 在最上面、待了多久，然後決定要不要把貓放出來。" +
                "判斷全部在這台手機上完成、當場丟掉，不會寫成檔案。\n\n" +
                "這個 app 沒有跟系統要網路權限 —— 就算程式想把東西傳出去，" +
                "系統也不會讓它連線。",
            primary = "開始看住我",
            secondary = "先不要，我再看看",
        )
    }

    /** 選 app 那一步一個都沒選時，按下一步要說的話。不是錯誤，是說明。 */
    const val NEEDS_APP_SELECTION = "至少要選一個，不然這個程式不會做任何事。"

    /** 已勾選但這台手機上沒有的 app，被自動取消勾選時的說明。 */
    fun removedUninstalled(labels: List<String>): String =
        "${labels.joinToString("、")}沒有裝在這支手機上，已經幫你取消勾選。"

    /** 結束頁上，列出還沒做完的事。 */
    fun unfinished(titles: List<String>): String =
        "還有 ${titles.size} 件事沒做完：${titles.joinToString("、")}。" +
            "在設定頁的「權限」那一區隨時可以補。"
}
