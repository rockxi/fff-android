package ru.rockxi.fff.ui.calories

/** Returns a checked GTIN-13 for EAN-8, UPC-A or EAN-13; never guesses other formats. */
internal fun normalizeFoodBarcode(raw: String?): String? {
    val code = raw?.trim() ?: return null
    if (code.length !in setOf(8, 12, 13) || code.any { it !in '0'..'9' }) return null
    val gtin13 = code.padStart(13, '0')
    val weightedSum = gtin13.dropLast(1).reversed().mapIndexed { index, digit ->
        (digit - '0') * if (index % 2 == 0) 3 else 1
    }.sum()
    val checkDigit = (10 - weightedSum % 10) % 10
    return gtin13.takeIf { it.last() - '0' == checkDigit }
}
