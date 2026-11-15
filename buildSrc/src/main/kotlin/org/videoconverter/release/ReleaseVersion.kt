package org.videoconverter.release

/**
 * Version identity of a release, derived from its git tag.
 *
 * @property name the version name written into the manifest, the tag without its leading `v`
 * @property code the version code written into the manifest
 */
public data class ReleaseVersion(
    public val name: String,
    public val code: Int,
) {
    public companion object {
        /**
         * Parses a release tag such as `v0.2.0` into a version identity.
         *
         * The version name keeps the tag without its leading `v`, so `v0.2.0-rc1` becomes
         * `0.2.0-rc1`. The version code is `major * 10000 + minor * 100 + patch`, which keeps
         * the ordering of the releases and stays inside the Android limit.
         *
         * @param tag the release tag
         * @return the parsed version
         * @throws IllegalArgumentException when the tag does not hold one to three numeric parts
         */
        public fun parse(tag: String): ReleaseVersion {
            val trimmed = tag.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("The release tag is empty")
            }
            val name = trimmed.removePrefix("v").removePrefix("V")
            if (name.isEmpty()) {
                throw IllegalArgumentException("The release tag '$tag' has no version")
            }
            val core = name.substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.size > 3) {
                throw IllegalArgumentException(
                    "The release tag '$tag' holds ${parts.size} version parts, at most three are allowed",
                )
            }
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.any { !it.isDigit() }) {
                    throw IllegalArgumentException("The release tag '$tag' holds the non-numeric part '$part'")
                }
                part.toInt().also {
                    if (it > 99) {
                        throw IllegalArgumentException("The release tag '$tag' holds the part '$part' above 99")
                    }
                }
            }
            val major = numbers.getOrElse(0) { 0 }
            val minor = numbers.getOrElse(1) { 0 }
            val patch = numbers.getOrElse(2) { 0 }
            val code = major * 10000 + minor * 100 + patch
            if (code < 1) {
                throw IllegalArgumentException("The release tag '$tag' holds the version code 0, which is invalid")
            }
            return ReleaseVersion(name = name, code = code)
        }
    }
}
