package com.luverse.fetchy

internal fun automaticVideoSelector(quality: Int): String {
    require(quality in setOf(0, 360, 480, 720, 1080, 1440, 2160)) { "Invalid quality" }
    return "bestvideo+bestaudio/best"
}
internal fun automaticVideoSort(quality: Int): String {
    automaticVideoSelector(quality)
    return if (quality == 0) "res" else "res:$quality"
}

/** Same PK encoding as the pinned Instagram extractor; its IDs are shortcodes, not decimal PKs. */
internal fun instagramExtractorId(mediaId: String): String {
    val numeric = mediaId.substringBefore('_')
    if (!numeric.matches(Regex("[0-9]{1,30}"))) return ""
    var value = java.math.BigInteger(numeric); val digits = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    if (value.signum() == 0) return "A"
    val result = StringBuilder(); val base = java.math.BigInteger.valueOf(64)
    while (value.signum() > 0) { val part = value.divideAndRemainder(base); result.append(digits[part[1].toInt()]); value = part[0] }
    return result.reverse().toString()
}
