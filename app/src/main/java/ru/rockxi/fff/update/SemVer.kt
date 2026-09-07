package ru.rockxi.fff.update

/** A SemVer 2.0.0 version. Build metadata does not affect precedence. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1

        preRelease.zip(other.preRelease).forEach { (left, right) ->
            val leftIsNumeric = left.all(Char::isDigit)
            val rightIsNumeric = right.all(Char::isDigit)
            val comparison = when {
                leftIsNumeric && rightIsNumeric -> {
                    compareValues(left.length, right.length).takeIf { it != 0 }
                        ?: left.compareTo(right)
                }
                leftIsNumeric -> -1
                rightIsNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    companion object {
        private val pattern = Regex(
            """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        fun parse(value: String): SemVer? {
            val match = pattern.matchEntire(value) ?: return null
            val preRelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                ?: emptyList()
            if (preRelease.any { it.length > 1 && it.first() == '0' && it.all(Char::isDigit) }) {
                return null
            }
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
                preRelease = preRelease,
            )
        }
    }
}
