package com.mindgohua.ui

/**
 * 時間輸入的解析與驗證。
 *
 * ## 為什麼需要這個檔案
 *
 * 設定頁原本只能用滑桿調整門檻。實際用起來有一個很嚴重的問題：
 * **整張設定頁本身是可以上下捲動的，而滑桿橫躺在上面。**
 * 使用者想往下捲去看別的設定時，手指很容易落在滑桿上 ——
 * 於是「連續使用超過 10 分鐘」在他完全沒察覺的情況下被拖成 0 分鐘。
 *
 * 這不是小瑕疵：門檻被改成 0 代表**這個 app 會變成一進 IG 就跳貓**，
 * 或者（另一個方向）永遠不跳。而使用者不會知道發生了什麼事。
 *
 * 第一版的修法是「滑桿保留，數字加一個 ✎ 可以點」。實際用下來滑桿還是在那裡，
 * 誤觸的結構沒有消失 —— 只是多了一條逃生路線。所以第二版直接把滑桿拿掉。
 *
 * 這跟業界的做法也一致：Nielsen Norman Group 的結論是滑桿只適合
 * 「大概就好」的值，**只要確切數字重要，滑桿就不該用**；而 Material 3 的
 * time picker 本身就內建鍵盤輸入模式。時間這種東西沒有「大概 10 分鐘」。
 *
 * ## 為什麼解析邏輯要抽出來
 *
 * 「使用者打了什麼」到「存進設定的數字」之間有一堆會出錯的情況：
 * 空字串、非數字、負數、超出範圍、前後空白、全形數字。
 * 這些全部寫在 composable 裡就沒辦法測 —— 而它們錯了不會當機，
 * 只會讓設定悄悄變成一個奇怪的值。
 */
object DurationInput {

    /**
     * 把使用者輸入的文字解析成一個合法的秒數。
     *
     * @param text 使用者打的內容。
     * @param minValue 允許的最小值。
     * @param maxValue 允許的最大值。
     * @return 合法的值；無法解析時回傳 null（呼叫端應該保留原值，而不是塞 0）。
     *
     * 刻意**不**把無法解析的輸入當成 0：那正是這次要修掉的那種
     * 「使用者沒想改，值卻變了」的情況。看不懂就不要動。
     */
    fun parse(text: String, minValue: Int, maxValue: Int): Int? {
        val cleaned = text.trim()
            // 全形數字轉半形。手機中文輸入法很容易打出全形數字，
            // 而使用者完全看不出差別，只會覺得「我明明有打字卻沒反應」。
            .map { ch -> if (ch in '０'..'９') ('0' + (ch - '０')) else ch }
            .joinToString("")

        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it.isDigit() }) return null

        val value = cleaned.toIntOrNull() ?: return null
        return value.coerceIn(minValue, maxValue)
    }

    /**
     * 把「分」與「秒」兩個欄位合成總秒數。
     *
     * 分開兩個欄位是因為門檻的範圍是 30 秒～60 分鐘 ——
     * 要使用者輸入「600 秒」而不是「10 分鐘」很不友善。
     *
     * 任一欄無法解析就回傳 null；兩欄都空也是 null。
     */
    fun parseMinutesSeconds(
        minutesText: String,
        secondsText: String,
        minTotalSeconds: Int,
        maxTotalSeconds: Int,
    ): Int? {
        val minutes = if (minutesText.isBlank()) 0 else parse(minutesText, 0, 999) ?: return null
        val seconds = if (secondsText.isBlank()) 0 else parse(secondsText, 0, 59) ?: return null
        if (minutesText.isBlank() && secondsText.isBlank()) return null

        val total = minutes * 60 + seconds
        return total.coerceIn(minTotalSeconds, maxTotalSeconds)
    }

    /**
     * 把秒數格式化成人看得懂的字串。
     *
     * 刻意不顯示「10 分 0 秒」這種尾巴 —— 設定頁上每一個多餘的 0
     * 都會讓人多花半秒去確認自己有沒有看錯。
     */
    fun format(totalSeconds: Int): String {
        val total = totalSeconds.coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return when {
            minutes == 0 -> "$seconds 秒"
            seconds == 0 -> "$minutes 分"
            else -> "$minutes 分 $seconds 秒"
        }
    }
}
