package com.catgatekeeper.stats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * 每日滑動計數的持久化儲存。
 *
 * ## 這個檔案推翻了 spec §0.1
 *
 * 原始 spec 寫「任何從畫面或事件讀到的資料，判斷完立即丟棄，不寫入任何持久化儲存」。
 * 使用者明確要求「記錄每一天滑了多少」，所以這條原則被**刻意放寬**，
 * 放寬的範圍必須寫清楚，不能含糊帶過：
 *
 * **會被寫進磁碟的東西的完整清單：**
 *   1. 日期（例如 2026-07-24）
 *   2. app 的 package name（例如 com.instagram.android）
 *   3. 一個整數（那天滑過幾項）
 *
 * **不會被寫進去的：** 時間戳序列、什麼時候滑的、滑了什麼、任何畫面內容。
 * 從這份資料**還原不出**你哪個時段在用手機，只知道「那天總共多少」。
 *
 * 而且仍然沒有 `INTERNET` 權限 —— 這些數字寫得進去，但出不了這台手機。
 *
 * 預設是關閉的（[com.catgatekeeper.settings.AppSettings.statsEnabled]），
 * 因為它改變了 app 的隱私姿態，應該由使用者主動開啟而不是預設承受。
 */
class DailyStatsStore(private val context: Context) {

    /**
     * key 格式：`c|<yyyy-MM-dd>|<package>`
     *
     * 用動態 key 而不是塞一坨 JSON，是因為這樣「刪掉某一天」和「列出所有天」
     * 都只是字串前綴操作，不需要反序列化整份資料。
     */
    private fun keyOf(date: LocalDate, packageName: String) =
        intPreferencesKey("$PREFIX$date$SEP$packageName")

    /** 累加。delta 為 0 時不寫入，避免無意義的磁碟 I/O。 */
    suspend fun add(date: LocalDate, packageName: String, delta: Int) {
        if (delta <= 0) return
        context.statsStore.edit { prefs ->
            val key = keyOf(date, packageName)
            prefs[key] = (prefs[key] ?: 0) + delta
        }
    }

    /** 某一天的各 app 計數。 */
    fun countsOn(date: LocalDate): Flow<Map<String, Int>> =
        context.statsStore.data.map { prefs -> parse(prefs).filter { it.date == date }.associate { it.packageName to it.count } }

    /** 給 UI 用的彙總：每天一列，含各 app 明細，新的在前。 */
    val dailyTotals: Flow<List<DayTotal>> =
        context.statsStore.data.map { prefs ->
            parse(prefs)
                .groupBy { it.date }
                .map { (date, rows) -> DayTotal(date, rows.associate { it.packageName to it.count }) }
                .sortedByDescending { it.date }
        }

    /** 刪掉比 [keepDays] 更舊的紀錄。留著沒用的舊資料只是徒增風險。 */
    suspend fun prune(today: LocalDate, keepDays: Int) {
        val cutoff = today.minusDays(keepDays.toLong())
        context.statsStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(PREFIX) }
                .filter { key ->
                    val date = runCatching { LocalDate.parse(key.name.removePrefix(PREFIX).substringBefore(SEP)) }.getOrNull()
                    date != null && date.isBefore(cutoff)
                }
                .forEach { prefs.remove(it) }
        }
    }

    /** 使用者按「清除所有紀錄」。 */
    suspend fun clearAll() {
        context.statsStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { prefs.remove(it) }
        }
    }

    private fun parse(prefs: Preferences): List<DayStat> =
        prefs.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(PREFIX)) return@mapNotNull null
            val body = key.name.removePrefix(PREFIX)
            val dateText = body.substringBefore(SEP)
            val pkg = body.substringAfter(SEP, missingDelimiterValue = "")
            if (pkg.isEmpty()) return@mapNotNull null
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@mapNotNull null
            DayStat(date, pkg, value as? Int ?: return@mapNotNull null)
        }

    private companion object {
        const val PREFIX = "c|"
        const val SEP = "|"
    }
}

data class DayStat(val date: LocalDate, val packageName: String, val count: Int)

data class DayTotal(val date: LocalDate, val perApp: Map<String, Int>) {
    val total: Int get() = perApp.values.sum()
}

/** 跟設定分開存：清除紀錄時不該把使用者的設定一起清掉。 */
private val Context.statsStore: DataStore<Preferences> by preferencesDataStore(name = "cat_gatekeeper_daily_stats")
