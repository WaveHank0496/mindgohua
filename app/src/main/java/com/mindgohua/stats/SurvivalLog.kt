package com.mindgohua.stats

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 服務存活紀錄 —— spec §8 第 6 條的驗收工具。
 *
 * ## 為什麼需要它
 *
 * ColorOS 殺掉 foreground service 時**不會給任何提示**，使用者只會發現貓再也沒出現過。
 * 這是最糟的失敗模式，因為它是靜默的。
 *
 * 而要測出這件事，又不能靠 adb 盯著看 —— 因為手機必須**拔掉電源**才會進入
 * ColorOS 最兇的深度休眠狀態。插著 USB 測出來的存活時間是假的。
 *
 * 所以改成讓服務自己每分鐘蓋一次時間戳。下次服務啟動時比對上一次的時間戳，
 * 就能推算出「中間死了多久」。**觀測者不介入被觀測的系統。**
 *
 * ## 存了什麼
 *
 * 只有服務自己的時間戳與中斷次數，**與使用者行為無關**，
 * 不涉及 spec §0.1 想保護的那類資料。
 */
class SurvivalLog(private val context: Context) {

    private object Keys {
        /** 本次服務啟動的時間（wall clock）。 */
        val STARTED_AT = longPreferencesKey("started_at")

        /** 最後一次心跳（wall clock）。服務被殺時這個值就停在那裡。 */
        val LAST_HEARTBEAT = longPreferencesKey("last_heartbeat")

        /** 開機以來多久（elapsedRealtime），用來判斷中斷是不是因為重開機。 */
        val LAST_HEARTBEAT_UPTIME = longPreferencesKey("last_heartbeat_uptime")

        /** 累計中斷次數。 */
        val GAP_COUNT = intPreferencesKey("gap_count")

        /** 最近一次中斷的起訖與是否為重開機。 */
        val LAST_GAP_FROM = longPreferencesKey("last_gap_from")
        val LAST_GAP_TO = longPreferencesKey("last_gap_to")
        val LAST_GAP_WAS_REBOOT = intPreferencesKey("last_gap_was_reboot")

        /**
         * 已經就哪一次中斷提醒過使用者（存該次中斷的結束時間戳）。
         *
         * 需要持久化而不是放記憶體：服務被殺之後可能連續重啟失敗好幾輪，
         * 每輪都是一個新的行程，記憶體裡的旗標會跟著消失 ——
         * 結果就是同一次中斷把使用者轟炸好幾次。
         */
        val LAST_ALERTED_GAP_TO = longPreferencesKey("last_alerted_gap_to")
    }

    val state: Flow<SurvivalState> = context.survivalStore.data.map { prefs ->
        SurvivalState(
            startedAtMs = prefs[Keys.STARTED_AT] ?: 0L,
            lastHeartbeatMs = prefs[Keys.LAST_HEARTBEAT] ?: 0L,
            gapCount = prefs[Keys.GAP_COUNT] ?: 0,
            lastGapFromMs = prefs[Keys.LAST_GAP_FROM] ?: 0L,
            lastGapToMs = prefs[Keys.LAST_GAP_TO] ?: 0L,
            lastGapWasReboot = (prefs[Keys.LAST_GAP_WAS_REBOOT] ?: 0) == 1,
        )
    }

    /**
     * 服務啟動時呼叫。若距離上次心跳超過 [GAP_THRESHOLD_MS]，就記錄一次中斷。
     *
     * @return 這次啟動偵測到的中斷資訊。
     *
     * 回傳型別從原本的 `Boolean` 改成 [GapInfo]：只知道「有沒有中斷」不足以
     * 決定要不要提醒使用者 —— 還需要知道中斷多久（短的不值得吵人）、
     * 是不是重開機（那不是被殺），以及結束時間戳（避免同一次重複提醒）。
     */
    suspend fun onServiceStart(nowMs: Long = System.currentTimeMillis()): GapInfo {
        var info = GapInfo()
        context.survivalStore.edit { prefs ->
            val lastBeat = prefs[Keys.LAST_HEARTBEAT] ?: 0L
            val nowUptime = SystemClock.elapsedRealtime()

            if (lastBeat > 0 && nowMs - lastBeat > GAP_THRESHOLD_MS) {
                // 開機時間比中斷長度還短 → 中間重開機了，不算 ColorOS 主動殺的。
                val wasReboot = nowUptime < (nowMs - lastBeat)
                prefs[Keys.GAP_COUNT] = (prefs[Keys.GAP_COUNT] ?: 0) + 1
                prefs[Keys.LAST_GAP_FROM] = lastBeat
                prefs[Keys.LAST_GAP_TO] = nowMs
                prefs[Keys.LAST_GAP_WAS_REBOOT] = if (wasReboot) 1 else 0
                info = GapInfo(
                    detected = true,
                    durationMs = nowMs - lastBeat,
                    wasReboot = wasReboot,
                    gapToMs = nowMs,
                    alreadyAlertedGapTo = prefs[Keys.LAST_ALERTED_GAP_TO] ?: 0L,
                )
            }

            prefs[Keys.STARTED_AT] = nowMs
            prefs[Keys.LAST_HEARTBEAT] = nowMs
            prefs[Keys.LAST_HEARTBEAT_UPTIME] = nowUptime
        }
        return info
    }

    /** 記下「這次中斷已經提醒過了」，避免服務反覆重啟時重複轟炸使用者。 */
    suspend fun markAlerted(gapToMs: Long) {
        context.survivalStore.edit { prefs ->
            prefs[Keys.LAST_ALERTED_GAP_TO] = gapToMs
        }
    }

    suspend fun heartbeat(nowMs: Long = System.currentTimeMillis()) {
        context.survivalStore.edit { prefs ->
            prefs[Keys.LAST_HEARTBEAT] = nowMs
            prefs[Keys.LAST_HEARTBEAT_UPTIME] = SystemClock.elapsedRealtime()
        }
    }

    /** 使用者主動關閉服務時呼叫 —— 那不是「被殺」，不該算成中斷。 */
    suspend fun onCleanStop(nowMs: Long = System.currentTimeMillis()) {
        context.survivalStore.edit { prefs ->
            prefs[Keys.STARTED_AT] = 0L
            prefs[Keys.LAST_HEARTBEAT] = 0L
            prefs[Keys.LAST_HEARTBEAT_UPTIME] = 0L
        }
    }

    suspend fun reset() {
        context.survivalStore.edit { it.clear() }
    }

    companion object {
        /** 心跳間隔。 */
        const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** 超過這個時間沒心跳才算「中斷」，留一點緩衝避免把排程延遲誤判成死亡。 */
        const val GAP_THRESHOLD_MS = 3 * 60_000L
    }
}

/**
 * 服務啟動時偵測到的中斷資訊。
 *
 * [alreadyAlertedGapTo] 帶著「上次已提醒過哪一次中斷」一起回來，是為了讓
 * 判斷邏輯（[SurvivalAlert.shouldNotify]）可以是一個不碰儲存層的純函式。
 */
data class GapInfo(
    val detected: Boolean = false,
    val durationMs: Long = 0L,
    val wasReboot: Boolean = false,
    val gapToMs: Long = 0L,
    val alreadyAlertedGapTo: Long = 0L,
)

data class SurvivalState(
    val startedAtMs: Long,
    val lastHeartbeatMs: Long,
    val gapCount: Int,
    val lastGapFromMs: Long,
    val lastGapToMs: Long,
    val lastGapWasReboot: Boolean,
) {
    val isTracking: Boolean get() = startedAtMs > 0

    /** 本次連續存活多久。 */
    fun survivedMs(nowMs: Long): Long =
        if (startedAtMs > 0) (nowMs - startedAtMs).coerceAtLeast(0L) else 0L

    val lastGapDurationMs: Long get() = (lastGapToMs - lastGapFromMs).coerceAtLeast(0L)
}

private val Context.survivalStore: DataStore<Preferences> by preferencesDataStore(name = "mindgohua_survival")
