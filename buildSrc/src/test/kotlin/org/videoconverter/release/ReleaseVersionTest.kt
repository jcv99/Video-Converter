package org.videoconverter.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseVersionTest {

    @Test
    fun parsesTagWithVersionPrefix() {
        val version = ReleaseVersion.parse("v0.2.0")

        assertEquals("0.2.0", version.name)
        assertEquals(200, version.code)
    }

    @Test
    fun parsesTagWithoutVersionPrefix() {
        val version = ReleaseVersion.parse("0.2.0")

        assertEquals("0.2.0", version.name)
        assertEquals(200, version.code)
    }

    @Test
    fun parsesUpperCaseVersionPrefix() {
        val version = ReleaseVersion.parse("V1.2.3")

        assertEquals("1.2.3", version.name)
        assertEquals(10203, version.code)
    }

    @Test
    fun parsesTagWithSurroundingWhitespace() {
        val version = ReleaseVersion.parse("  v1.2.3  ")

        assertEquals("1.2.3", version.name)
        assertEquals(10203, version.code)
    }

    @Test
    fun parsesTagWithMissingParts() {
        val minor = ReleaseVersion.parse("v0.2")
        val major = ReleaseVersion.parse("v1")

        assertEquals("0.2", minor.name)
        assertEquals(200, minor.code)
        assertEquals("1", major.name)
        assertEquals(10000, major.code)
    }

    @Test
    fun keepsPreReleaseSuffixInName() {
        val version = ReleaseVersion.parse("v0.2.0-rc1")

        assertEquals("0.2.0-rc1", version.name)
        assertEquals(200, version.code)
    }

    @Test
    fun keepsBuildMetadataInName() {
        val version = ReleaseVersion.parse("v0.2.0+build5")

        assertEquals("0.2.0+build5", version.name)
        assertEquals(200, version.code)
    }

    @Test
    fun rejectsEmptyTag() {
        val error = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("   ") }

        assertEquals("The release tag is empty", error.message)
    }

    @Test
    fun rejectsTagWithoutNumbers() {
        val error = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("release") }

        assertEquals("The release tag 'release' holds the non-numeric part 'release'", error.message)
    }

    @Test
    fun rejectsTagWithTooManyParts() {
        val error = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("v1.2.3.4") }

        assertEquals("The release tag 'v1.2.3.4' holds 4 version parts, at most three are allowed", error.message)
    }

    @Test
    fun rejectsTagWithPartAboveNinetyNine() {
        val error = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("v0.100.0") }

        assertEquals("The release tag 'v0.100.0' holds the part '100' above 99", error.message)
    }

    @Test
    fun rejectsTagWithVersionCodeZero() {
        val error = assertThrows(IllegalArgumentException::class.java) { ReleaseVersion.parse("v0.0.0") }

        assertEquals("The release tag 'v0.0.0' holds the version code 0, which is invalid", error.message)
    }
}
