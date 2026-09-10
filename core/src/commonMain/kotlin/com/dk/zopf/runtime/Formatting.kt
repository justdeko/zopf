package com.dk.zopf.runtime

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun format(duration: Duration): String {
    val seconds = duration.toMillis() / 1000.0
    return if (seconds < 60) {
        String.format(Locale.ROOT, "%.1fs", seconds)
    } else {
        "${duration.toMinutes()}m ${String.format(Locale.ROOT, "%02d", duration.toSecondsPart())}s"
    }
}

private val TimeOfDay = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DateAndTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

fun stamp(
    at: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateAndTime.format(at.atZone(zone))

fun format(
    at: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val local = at.atZone(zone)
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) TimeOfDay.format(local) else stamp(at, zone)
}

fun money(usd: Double): String = String.format(Locale.ROOT, "$%.4f", usd)

fun spend(
    costUsd: Double?,
    tokensUsed: Int?,
): String? =
    when {
        costUsd != null -> money(costUsd)
        tokensUsed != null -> tokens(tokensUsed)
        else -> null
    }

fun tokens(count: Int): String =
    when {
        count < 1_000 -> "$count tokens"
        count < 1_000_000 -> String.format(Locale.ROOT, "%.1fk tokens", count / 1_000.0)
        else -> String.format(Locale.ROOT, "%.1fM tokens", count / 1_000_000.0)
    }
