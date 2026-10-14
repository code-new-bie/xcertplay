package com.shilapi.xcertplay.update

/**
 * A release of this fork: `1.4.0-byd.11`. The upstream version comes first, then the fork's own
 * release number. A local build ("1.4.0-byd.1" from the build file) parses too, so whether a build
 * is a release comes from [UpdateBuild], not from its name.
 */
internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val build: Int,
) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.build })

    override fun toString(): String = "$major.$minor.$patch-byd.$build"

    companion object {
        private val PATTERN = Regex("""^v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})-byd\.(\d{1,6})$""")

        /** The version in a tag or version name, or null when it is not one of this fork's. */
        fun parse(text: String?): ReleaseVersion? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch, build) = match.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt(), build.toInt())
        }
    }
}
