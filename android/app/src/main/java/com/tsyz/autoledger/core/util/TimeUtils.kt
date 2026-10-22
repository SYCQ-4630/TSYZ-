package com.tsyz.autoledger.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeUtils {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun monthKey(timeMillis: Long): String =
        YearMonth.from(Instant.ofEpochMilli(timeMillis).atZone(zone())).toString()

    fun currentMonthKey(): String = YearMonth.now(zone()).toString()

    fun monthStart(month: YearMonth): Long =
        month.atDay(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun monthEndExclusive(month: YearMonth): Long =
        month.plusMonths(1).atDay(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun monthRange(month: YearMonth): LongRange =
        monthStart(month) until monthEndExclusive(month)

    fun formatDateTime(timeMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone()).format(dateTimeFormatter)

    fun formatDate(timeMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone()).format(dateFormatter)

    fun formatTime(timeMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), zone()).format(timeFormatter)

    fun formatMonth(monthKey: String): String = monthKey

    fun dayKey(timeMillis: Long): String = LocalDate.ofInstant(Instant.ofEpochMilli(timeMillis), zone()).toString()

    fun parseDateOrNow(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            LocalDate.parse(value.trim()).atStartOfDay(zone()).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(zone()).toInstant().toEpochMilli()
            }.getOrDefault(System.currentTimeMillis())
        }
    }

    fun nextMonthlyRun(after: Long = System.currentTimeMillis()): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone())
        var target = now.toLocalDate().withDayOfMonth(1).atTime(9, 0)
        if (!target.isAfter(now)) {
            target = now.toLocalDate().plusMonths(1).withDayOfMonth(1).atTime(9, 0)
        }
        return target.atZone(zone()).toInstant().toEpochMilli()
    }

    fun nextMonthlyFallback(after: Long = System.currentTimeMillis()): Long {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone())
        var target = now.toLocalDate().withDayOfMonth(1).atTime(10, 0)
        if (!target.isAfter(now)) {
            target = now.toLocalDate().plusMonths(1).withDayOfMonth(1).atTime(10, 0)
        }
        return target.atZone(zone()).toInstant().toEpochMilli()
    }

    fun startOfDay(timeMillis: Long): Long =
        Instant.ofEpochMilli(timeMillis).atZone(zone()).toLocalDate()
            .atStartOfDay(zone()).toInstant().toEpochMilli()

    fun endOfToday(): Long =
        LocalDate.now(zone()).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
}
