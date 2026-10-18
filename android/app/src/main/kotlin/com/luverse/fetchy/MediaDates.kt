package com.luverse.fetchy

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun sourceMediaDate(timestamp: Double, uploadDate: String = ""): Long? {
    if (timestamp.isFinite() && timestamp > 0 && timestamp < 253402300800.0) return (timestamp * 1000).toLong().takeIf { it > 0 }
    if (!uploadDate.matches(Regex("[0-9]{8}"))) return null
    return runCatching {
        SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
            .parse(uploadDate)?.time?.takeIf { it > 0 }
    }.getOrNull()
}

internal fun selectedMediaDate(mode: String, source: Long?, now: Long): Long {
    require(mode in setOf("download", "source")) { "Invalid file date setting" }
    return if (mode == "source" && source != null && source > 0) source else now
}
