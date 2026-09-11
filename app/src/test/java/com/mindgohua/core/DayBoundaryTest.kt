package com.mindgohua.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DayBoundaryTest {

    private val taipei: ZoneId = ZoneId.of("Asia/Taipei")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(taipei).toInstant().toEpochMilli()

    @Test
    fun `換日時間之後算今天`() {
        assertEquals(
            LocalDate.parse("2026-07-24"),
            DayBoundary.dayOf(at("2026-07-24T04:00"), boundaryHour = 4, zone = taipei),
        )
        assertEquals(
            LocalDate.parse("2026-07-24"),
            DayBoundary.dayOf(at("2026-07-24T23:59"), boundaryHour = 4, zone = taipei),
        )
    }

    @Test
    fun `熬夜到凌晨仍算前一天`() {
        // 這是整個換日設定存在的理由：凌晨 2 點還在滑，心理上是「昨天的夜晚」。
        assertEquals(
            LocalDate.parse("2026-07-23"),
            DayBoundary.dayOf(at("2026-07-24T02:00"), boundaryHour = 4, zone = taipei),
        )
        assertEquals(
            LocalDate.parse("2026-07-23"),
            DayBoundary.dayOf(at("2026-07-24T03:59"), boundaryHour = 4, zone = taipei),
        )
    }

    @Test
    fun `換日時間設為0等同午夜換日`() {
        assertEquals(
            LocalDate.parse("2026-07-24"),
            DayBoundary.dayOf(at("2026-07-24T00:00"), boundaryHour = 0, zone = taipei),
        )
        assertEquals(
            LocalDate.parse("2026-07-23"),
            DayBoundary.dayOf(at("2026-07-23T23:59"), boundaryHour = 0, zone = taipei),
        )
    }

    @Test
    fun `跨月與跨年都要正確`() {
        assertEquals(
            LocalDate.parse("2026-07-31"),
            DayBoundary.dayOf(at("2026-08-01T01:00"), boundaryHour = 4, zone = taipei),
        )
        assertEquals(
            LocalDate.parse("2025-12-31"),
            DayBoundary.dayOf(at("2026-01-01T03:00"), boundaryHour = 4, zone = taipei),
        )
    }

    @Test
    fun `下一次換日時間算得對`() {
        // 早上 10 點 → 下一次換日是隔天 4 點
        assertEquals(
            at("2026-07-25T04:00"),
            DayBoundary.nextRolloverMillis(at("2026-07-24T10:00"), boundaryHour = 4, zone = taipei),
        )
        // 凌晨 2 點（還算昨天）→ 下一次換日是今天 4 點，只剩兩小時
        assertEquals(
            at("2026-07-24T04:00"),
            DayBoundary.nextRolloverMillis(at("2026-07-24T02:00"), boundaryHour = 4, zone = taipei),
        )
    }

    @Test
    fun `越界的設定值會被夾到0到23而不是爆掉`() {
        // 99 會被夾成 23，所以 23:30 之後才算今天
        assertEquals(
            LocalDate.parse("2026-07-24"),
            DayBoundary.dayOf(at("2026-07-24T23:30"), boundaryHour = 99, zone = taipei),
        )
        assertEquals(
            LocalDate.parse("2026-07-23"),
            DayBoundary.dayOf(at("2026-07-24T12:00"), boundaryHour = 99, zone = taipei),
        )
        // 負數夾成 0
        assertEquals(
            LocalDate.parse("2026-07-24"),
            DayBoundary.dayOf(at("2026-07-24T00:30"), boundaryHour = -5, zone = taipei),
        )
    }
}
