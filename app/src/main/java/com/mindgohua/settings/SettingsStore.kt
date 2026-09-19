package com.mindgohua.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mindgohua_settings")

/**
 * 唯一一處寫入持久化儲存的地方，而且**只寫使用者設定**。
 * 偵測過程中讀到的任何東西（前景 app、時間戳）判斷完就丟，絕不經過這裡。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val MODE = stringPreferencesKey("mode")
        val TARGETS = stringSetPreferencesKey("target_packages")
        val THRESHOLD_SEC = intPreferencesKey("threshold_seconds")
        val COOLDOWN_SEC = intPreferencesKey("cooldown_seconds")
        val GRACE_MIN = intPreferencesKey("grace_minutes")
        val UNLOCK_DELAY_SEC = intPreferencesKey("unlock_delay_seconds")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_notification")
        val SURVIVAL_ALERT = booleanPreferencesKey("survival_alert_enabled")
        val STATS_ENABLED = booleanPreferencesKey("stats_enabled")
        val HUD_ENABLED = booleanPreferencesKey("hud_enabled")
        val DAY_BOUNDARY_HOUR = intPreferencesKey("day_boundary_hour")
        val STATS_RETENTION_DAYS = intPreferencesKey("stats_retention_days")
        val ADVANCED_VISIBLE = booleanPreferencesKey("advanced_visible")
        val RHYTHM_WINDOW_SEC = intPreferencesKey("rhythm_window_seconds")
        val RHYTHM_DENSITY = intPreferencesKey("rhythm_min_density_per_minute")
        val RHYTHM_CV_PERCENT = intPreferencesKey("rhythm_max_cv_percent")
        val RHYTHM_SUSTAIN_SEC = intPreferencesKey("rhythm_sustain_seconds")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            enabled = prefs[Keys.ENABLED] ?: defaults.enabled,
            mode = prefs[Keys.MODE]?.let { runCatching { DetectionMode.valueOf(it) }.getOrNull() } ?: defaults.mode,
            targetPackages = prefs[Keys.TARGETS] ?: defaults.targetPackages,
            // 讀出來也要夾。**寫入時夾過了不代表磁碟上的值一定合法** ——
            // 範圍改窄的時候（例如門檻從 10..7200 收成 30..3600），
            // 舊版存下來的值會原封不動地活在磁碟上，而且沒有任何地方會發現。
            //
            // 那種值造成的症狀全部是無聲的：grace 讀成 0 就等於冷靜期消失，
            // 貓可以連續跳；按鈕上還會寫「再滑 0 分鐘」。
            thresholdSeconds = (prefs[Keys.THRESHOLD_SEC] ?: defaults.thresholdSeconds)
                .clampTo(SettingLimits.THRESHOLD_SECONDS),
            cooldownSeconds = (prefs[Keys.COOLDOWN_SEC] ?: defaults.cooldownSeconds)
                .clampTo(SettingLimits.COOLDOWN_SECONDS),
            graceMinutes = (prefs[Keys.GRACE_MIN] ?: defaults.graceMinutes)
                .clampTo(SettingLimits.GRACE_MINUTES),
            unlockDelaySeconds = (prefs[Keys.UNLOCK_DELAY_SEC] ?: defaults.unlockDelaySeconds)
                .clampTo(SettingLimits.UNLOCK_DELAY_SECONDS),
            diagnosticsNotification = prefs[Keys.DIAGNOSTICS] ?: defaults.diagnosticsNotification,
            survivalAlertEnabled = prefs[Keys.SURVIVAL_ALERT] ?: defaults.survivalAlertEnabled,
            statsEnabled = prefs[Keys.STATS_ENABLED] ?: defaults.statsEnabled,
            hudEnabled = prefs[Keys.HUD_ENABLED] ?: defaults.hudEnabled,
            dayBoundaryHour = prefs[Keys.DAY_BOUNDARY_HOUR] ?: defaults.dayBoundaryHour,
            statsRetentionDays = prefs[Keys.STATS_RETENTION_DAYS] ?: defaults.statsRetentionDays,
            advancedVisible = prefs[Keys.ADVANCED_VISIBLE] ?: defaults.advancedVisible,
            rhythmWindowSeconds = prefs[Keys.RHYTHM_WINDOW_SEC] ?: defaults.rhythmWindowSeconds,
            rhythmMinDensityPerMinute = prefs[Keys.RHYTHM_DENSITY] ?: defaults.rhythmMinDensityPerMinute,
            rhythmMaxCvPercent = prefs[Keys.RHYTHM_CV_PERCENT] ?: defaults.rhythmMaxCvPercent,
            rhythmSustainSeconds = prefs[Keys.RHYTHM_SUSTAIN_SEC] ?: defaults.rhythmSustainSeconds,
        )
    }

    suspend fun setEnabled(value: Boolean) = edit { it[Keys.ENABLED] = value }
    suspend fun setMode(value: DetectionMode) = edit { it[Keys.MODE] = value.name }
    suspend fun setTargets(value: Set<String>) = edit { it[Keys.TARGETS] = value }
    // 範圍全部來自 SettingLimits —— 設定頁的對話框用的是同一份。
    // 這裡曾經跟 UI 不一致（門檻 store 是 10..7200、UI 是 30..3600），
    // 那種不一致不會當機，只會讓使用者輸入的數字悄悄變成別的。
    suspend fun setThresholdSeconds(value: Int) =
        edit { it[Keys.THRESHOLD_SEC] = value.clampTo(SettingLimits.THRESHOLD_SECONDS) }

    suspend fun setCooldownSeconds(value: Int) =
        edit { it[Keys.COOLDOWN_SEC] = value.clampTo(SettingLimits.COOLDOWN_SECONDS) }

    suspend fun setGraceMinutes(value: Int) =
        edit { it[Keys.GRACE_MIN] = value.clampTo(SettingLimits.GRACE_MINUTES) }

    suspend fun setUnlockDelaySeconds(value: Int) =
        edit { it[Keys.UNLOCK_DELAY_SEC] = value.clampTo(SettingLimits.UNLOCK_DELAY_SECONDS) }
    suspend fun setDiagnosticsNotification(value: Boolean) = edit { it[Keys.DIAGNOSTICS] = value }
    suspend fun setSurvivalAlertEnabled(value: Boolean) = edit { it[Keys.SURVIVAL_ALERT] = value }
    suspend fun setStatsEnabled(value: Boolean) = edit { it[Keys.STATS_ENABLED] = value }
    suspend fun setHudEnabled(value: Boolean) = edit { it[Keys.HUD_ENABLED] = value }
    suspend fun setDayBoundaryHour(value: Int) = edit { it[Keys.DAY_BOUNDARY_HOUR] = value.coerceIn(0, 23) }
    suspend fun setStatsRetentionDays(value: Int) = edit { it[Keys.STATS_RETENTION_DAYS] = value.coerceIn(1, 365) }
    suspend fun setAdvancedVisible(value: Boolean) = edit { it[Keys.ADVANCED_VISIBLE] = value }
    suspend fun setRhythmWindowSeconds(value: Int) = edit { it[Keys.RHYTHM_WINDOW_SEC] = value.coerceIn(15, 600) }
    suspend fun setRhythmDensity(value: Int) = edit { it[Keys.RHYTHM_DENSITY] = value.coerceIn(1, 200) }
    suspend fun setRhythmCvPercent(value: Int) = edit { it[Keys.RHYTHM_CV_PERCENT] = value.coerceIn(5, 200) }
    suspend fun setRhythmSustainSeconds(value: Int) = edit { it[Keys.RHYTHM_SUSTAIN_SEC] = value.coerceIn(5, 600) }

    suspend fun toggleTarget(packageName: String, selected: Boolean) = edit { prefs ->
        val current = prefs[Keys.TARGETS] ?: AppSettings().targetPackages
        prefs[Keys.TARGETS] = if (selected) current + packageName else current - packageName
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
