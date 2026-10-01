package xyz.botolog.ghostify.update

/**
 * A parsed semantic version, used to decide whether a published GitHub release is
 * newer than the installed build.
 *
 * The project publishes releases tagged `vMAJOR.MINOR.PATCH` (optionally with a
 * `-prerelease` suffix and `+build` metadata) while the installed build reports both a
 * `versionName` and an Android `versionCode` that use unrelated numbering schemes.
 * Comparing those two numbers directly is unreliable, so availability is decided on
 * the version names and the numeric codes are only a fallback.
 *
 * Comparison follows semantic-versioning precedence: numeric components first
 * (missing components count as `0`, so `1.0` equals `1.0.0`), then prerelease
 * precedence where a release outranks any prerelease of the same numbers. Build
 * metadata is ignored.
 *
 * @property numbers the numeric components, most significant first.
 * @property prerelease the prerelease identifiers, empty for a stable release.
 */
internal data class SemanticVersion(
    val numbers: List<Long>,
    val prerelease: List<String>,
) : Comparable<SemanticVersion> {

    companion object {

        private val PATTERN = Regex(
            """^v?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?(?:\+[0-9A-Za-z.-]+)?$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parses a version string such as `v1.2.3`, `1.2`, `2` or `0.5.0-beta.1+7`.
         *
         * @param raw the version string, possibly `null`.
         * @return the parsed version, or `null` when [raw] is absent, blank or not a
         *   recognizable version.
         */
        fun parse(raw: String?): SemanticVersion? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = PATTERN.matchEntire(text) ?: return null

            val numbers = match.groupValues[1].split('.').map { part ->
                part.toLongOrNull() ?: return null
            }
            val prereleaseGroup = match.groupValues[2]
            val prerelease = if (prereleaseGroup.isEmpty()) {
                emptyList()
            } else {
                val identifiers = prereleaseGroup.split('.')
                if (identifiers.any { it.isEmpty() }) return null
                identifiers
            }
            return SemanticVersion(numbers, prerelease)
        }
    }

    override fun compareTo(other: SemanticVersion): Int {
        val count = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until count) {
            val mine = numbers.getOrElse(i) { 0L }
            val theirs = other.numbers.getOrElse(i) { 0L }
            if (mine != theirs) return mine.compareTo(theirs)
        }

        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }

        val shared = minOf(prerelease.size, other.prerelease.size)
        for (i in 0 until shared) {
            val result = compareIdentifiers(prerelease[i], other.prerelease[i])
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    private fun compareIdentifiers(mine: String, theirs: String): Int {
        val mineNumber = mine.toLongOrNull()
        val theirsNumber = theirs.toLongOrNull()
        return when {
            mineNumber != null && theirsNumber != null -> mineNumber.compareTo(theirsNumber)
            mineNumber != null -> -1
            theirsNumber != null -> 1
            else -> mine.compareTo(theirs)
        }
    }
}
