package com.mindgohua.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 當機紀錄（本機，永不外流）。
 *
 * ## 為什麼需要它
 *
 * 這個 app 刻意沒有 INTERNET 權限（spec §0.2），所以不能用 Crashlytics 那類服務。
 * 代價很直接：**app 在別人手機上爆掉時，開發者永遠不會知道。**
 * 使用者只會覺得「這東西壞了」然後解除安裝。
 *
 * 這個類別是那個代價的補償方案：當機時把堆疊寫進手機本機檔案，
 * 由使用者自己決定要不要匯出、要不要傳給開發者。
 * 資料的流向由使用者主動觸發，而不是程式偷偷送出去 —— 原則沒有被打破。
 *
 * ## 存了什麼、沒存什麼
 *
 * 寫入的欄位是**封閉清單**，只有 [format] 裡列出的那幾項：
 * 時間、app 版本、Android 版本、手機廠牌型號、執行緒名、堆疊追蹤。
 *
 * 刻意**不寫**：目標 app 清單、使用時數、每日計數、任何設定值。
 * 這些都屬於 spec §0 要保護的使用者行為資料，不該因為「方便除錯」就混進來。
 * 廠牌型號會寫，是因為這個 app 最大的失敗來源就是各家 OEM 的背景管制差異
 * （見 `docs/ROADMAP.md` M2），沒有這項資訊等於收到一份查不下去的報告。
 *
 * ## 存在哪裡
 *
 * `filesDir/diagnostics/`，也就是 app 私有目錄：其他 app 讀不到，
 * 解除安裝時跟著消失，不需要任何儲存空間權限。
 *
 * ## 為什麼檔案操作都在 companion object 裡
 *
 * 寫入、輪替、讀取、清除全部抽成只吃 [File] 的函式，不碰 [Context]。
 * 這讓它們可以用**一般單元測試**驗證（不需要手機、不需要模擬器），
 * 見 `CrashLogWriteTest`。
 *
 * 這一點很重要：原本看起來「要驗證就得讓 app 故意崩潰」，
 * 但崩潰只是觸發條件，真正要驗的是檔案有沒有被正確寫出來 ——
 * 而那件事根本不需要 Android。
 */
class CrashLog(private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR_NAME)

    /**
     * 掛上全域的未捕捉例外處理器。
     *
     * 關鍵細節：**一定要把原本的處理器接回去**。少了最後那一行，系統不會顯示
     * 「應用程式已停止運作」、也不會把行程收掉，app 會變成一個卡死但沒死透的殭屍，
     * 比直接當掉更糟。我們只是在死亡的路上順手記一筆，不是要阻止它死。
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 這裡面不論發生什麼都不能再丟例外，否則會蓋掉原本的當機原因。
            runCatching { write(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 由新到舊。 */
    fun reports(): List<File> = reportsIn(dir)

    fun hasReports(): Boolean = reports().isNotEmpty()

    /** 把所有紀錄串成一份可匯出的純文字。 */
    fun readAll(): String = readAllIn(dir)

    fun clear() = clearIn(dir)

    private fun write(thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val text = format(
            whenMs = System.currentTimeMillis(),
            appVersion = appVersion(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            threadName = thread.name,
            stackTrace = stack,
        )

        writeInto(dir, text, stamp(System.currentTimeMillis()))
    }

    private fun appVersion(): String = runCatching {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }
        "${pkg.versionName} ($code)"
    }.getOrElse { "unknown" }

    companion object {
        const val DIR_NAME = "diagnostics"

        /** 保留幾份當機紀錄。舊的通常已經修掉了，留著只是佔空間。 */
        const val MAX_REPORTS = 10

        /** 多份紀錄串接時的分隔線。 */
        internal val SEPARATOR = "\n\n${"=".repeat(60)}\n\n"

        private const val FILE_STAMP_PATTERN = "yyyyMMdd-HHmmss-SSS"
        private const val DISPLAY_STAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

        /** 檔名用的時間戳。排序靠它，所以格式必須是「字典序 == 時間序」。 */
        internal fun stamp(whenMs: Long): String =
            SimpleDateFormat(FILE_STAMP_PATTERN, Locale.US).format(Date(whenMs))

        /**
         * 產生一份當機報告的文字內容。
         *
         * 刻意抽成一個**沒有任何 Android 相依**的純函式，
         * 這樣「報告裡到底寫了什麼」可以被單元測試直接盯住
         * （見 `CrashLogFormatTest`）—— 這是這個專案一貫的做法：
         * 重要的保證要由會失敗的測試維持，不是由註解維持。
         */
        fun format(
            whenMs: Long,
            appVersion: String,
            androidRelease: String,
            sdkInt: Int,
            manufacturer: String,
            model: String,
            threadName: String,
            stackTrace: String,
        ): String = buildString {
            appendLine("mindgohua 當機紀錄")
            appendLine("此檔案只存在這台手機上。本 app 沒有網路權限，無法自行送出。")
            appendLine("-".repeat(60))
            appendLine("時間　　　：${SimpleDateFormat(DISPLAY_STAMP_PATTERN, Locale.US).format(Date(whenMs))}")
            appendLine("app 版本　：$appVersion")
            appendLine("Android　 ：$androidRelease (API $sdkInt)")
            appendLine("裝置　　　：$manufacturer $model")
            appendLine("執行緒　　：$threadName")
            appendLine("-".repeat(60))
            appendLine(stackTrace.trimEnd())
        }

        // ---- 以下純檔案操作，不依賴 Android，可用一般單元測試驗證 ----

        /** 由新到舊。檔名含時間戳，所以字典序倒排就是時間倒序。 */
        internal fun reportsIn(dir: File): List<File> =
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

        /**
         * 寫一份紀錄並順手輪替。
         *
         * @return 寫出的檔案；目錄建不起來或寫入失敗時回傳 null。
         *   這個函式是在「app 正在當掉」的路徑上被呼叫的，所以絕對不能丟例外。
         */
        internal fun writeInto(
            dir: File,
            text: String,
            stamp: String,
            keep: Int = MAX_REPORTS,
        ): File? = runCatching {
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, "crash-$stamp.txt")
            file.writeText(text)
            pruneIn(dir, keep)
            file
        }.getOrNull()

        /** 只留最近 [keep] 份。 */
        internal fun pruneIn(dir: File, keep: Int = MAX_REPORTS) {
            val files = reportsIn(dir)
            if (files.size <= keep) return
            files.drop(keep).forEach { runCatching { it.delete() } }
        }

        internal fun readAllIn(dir: File): String {
            val files = reportsIn(dir)
            if (files.isEmpty()) return ""
            return files.joinToString(SEPARATOR) { file ->
                runCatching { file.readText() }.getOrElse { e -> "（讀取失敗：$e）" }
            }
        }

        internal fun clearIn(dir: File) {
            runCatching { dir.listFiles()?.forEach { it.delete() } }
        }
    }
}
