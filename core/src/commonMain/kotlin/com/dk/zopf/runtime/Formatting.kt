package com.dk.zopf.runtime

import java.time.Duration
import java.util.Locale

fun format(duration: Duration): String {
    val seconds = duration.toMillis() / 1000.0
    return if (seconds < 60) {
        String.format(Locale.ROOT, "%.1fs", seconds)
    } else {
        "${duration.toMinutes()}m ${String.format(Locale.ROOT, "%02d", duration.toSecondsPart())}s"
    }
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
