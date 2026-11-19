package app.cinema.player

/**
 * Compare Android display versions, including test builds such as 0.4.42-rc1.
 * Stable versions sort after their pre-releases of the same numeric version.
 * Unrecognized versions are never assumed to be safe updates.
 */
internal object AppVersionComparator {
    private val pattern = Regex(
        """^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+))?$""",
        RegexOption.IGNORE_CASE
    )

    private data class Version(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val stage: Int,
        val sequence: Long
    )

    private fun parse(raw: String): Version? {
        val match = pattern.matchEntire(raw.trim()) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        val suffix = match.groupValues[4].lowercase()
        val stage = when (suffix) {
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> 3
        }
        val sequence = if (suffix.isEmpty()) 0L
            else match.groupValues[5].toLongOrNull() ?: return null
        return Version(major, minor, patch, stage, sequence)
    }

    /** Null means at least one version has an unsupported format. */
    fun compare(a: String, b: String): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        return left.major.compareTo(right.major).takeIf { it != 0 }
            ?: left.minor.compareTo(right.minor).takeIf { it != 0 }
            ?: left.patch.compareTo(right.patch).takeIf { it != 0 }
            ?: left.stage.compareTo(right.stage).takeIf { it != 0 }
            ?: left.sequence.compareTo(right.sequence)
    }

    fun isNewer(candidate: String, installed: String): Boolean =
        compare(candidate, installed)?.let { it > 0 } == true
}
