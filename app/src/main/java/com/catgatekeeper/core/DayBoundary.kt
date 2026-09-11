package com.catgatekeeper.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「一天」從幾點開始（使用者可設定）。
 *
 * 為什麼需要這個：午夜零點是很糟的分界。半夜 1 點還在滑的那段，
 * 心理上屬於「昨天的夜晚」，記到今天頭上會讓兩天的數字都失真。
 * 預設 4:00 —— 這也是多數睡眠 / 螢幕時間工具的慣例。
 *
 * 純函式，不碰 Android，可單元測試。
 */
object DayBoundary {

    /**
     * 某個時間點屬於哪一「天」。
     *
     * @param boundaryHour 一天的起點（0–23）。例如 4 代表 04:00 才換日，
     *                     所以 07/24 的 03:59 仍算 07/23。
     */
    fun dayOf(
        epochMillis: Long,
        boundaryHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val hour = boundaryHour.coerceIn(0, 23)
        return if (local.hour < hour) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** 下一次換日的時間點，UI 用來顯示「再過 X 小時歸零」。 */
    fun nextRolloverMillis(
        epochMillis: Long,
        boundaryHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val hour = boundaryHour.coerceIn(0, 23)
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val todayBoundary = local.toLocalDate().atStartOfDay(zone).plusHours(hour.toLong())
        val next = if (local.isBefore(todayBoundary)) todayBoundary else todayBoundary.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
}
