package com.hmndev.ziparc.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafGrantsLogicTest {

    @Test
    fun splitTreeDocIdParsesRootIdAndRelPath() {
        assertEquals("primary" to "", SafGrants.splitTreeDocId("primary:"))
        assertEquals("primary" to "Download", SafGrants.splitTreeDocId("primary:Download"))
        assertEquals("primary" to "Download/sub", SafGrants.splitTreeDocId("primary:Download/sub"))
        assertEquals(
            "0123-4567" to "Files/nested",
            SafGrants.splitTreeDocId("0123-4567:Files/nested")
        )
        assertEquals("primary" to "", SafGrants.splitTreeDocId("primary:/"))
    }

    @Test
    fun splitTreeDocIdRejectsMalformed() {
        assertNull(SafGrants.splitTreeDocId("no-colon"))
        assertNull(SafGrants.splitTreeDocId(":nospace-root"))
        assertNull(SafGrants.splitTreeDocId(""))
    }

    private class FakeVolume(
        val id: String,
        val rootName: String,
        val isPrimary: Boolean
    )

    private fun matchRootId(rootId: String, volumes: List<FakeVolume>): FakeVolume? {
        if (rootId.equals("primary", ignoreCase = true)) {
            return volumes.firstOrNull { it.isPrimary }
        }
        val rid = rootId.lowercase()
        return volumes.firstOrNull { v ->
            !v.isPrimary && (v.id.lowercase() == "vol-$rid" || v.rootName.lowercase() == rid)
        }
    }

    @Test
    fun primaryRootIdMapsToPrimaryVolumeCaseInsensitive() {
        val volumes = listOf(
            FakeVolume("primary", "emulated", true),
            FakeVolume("vol-0123-4567", "0123-4567", false)
        )
        assertEquals(volumes[0], matchRootId("primary", volumes))
        assertEquals(volumes[0], matchRootId("PRIMARY", volumes))
    }

    @Test
    fun uuidRootIdMatchesByVolumeIdOrRootNameExact() {
        val volumes = listOf(
            FakeVolume("primary", "emulated", true),
            FakeVolume("vol-0123-4567", "0123-4567", false)
        )
        assertEquals(volumes[1], matchRootId("0123-4567", volumes))
        assertNull(matchRootId("FFFF-9999", volumes))
    }

    @Test
    fun substringRootIdDoesNotMatchSuperstringVolume() {
        val volumes = listOf(
            FakeVolume("primary", "emulated", true),
            FakeVolume("vol-1234-5678", "1234-5678", false)
        )
        assertNull(matchRootId("1234-56", volumes))
    }

    @Test
    fun relativeToBaseComputesPathUnderGrantedSubtree() {
        val volumeRoot = "/storage/emulated/0"
        val base = volumeRoot + "/Download"
        assertEquals("a.txt", relativeTo(base, base + "/a.txt"))
        assertEquals("sub/b.zip", relativeTo(base, base + "/sub/b.zip"))
        assertEquals("", relativeTo(base, base))
        assertNull(relativeTo(base, volumeRoot + "/DCIM/photo.jpg"))
    }

    private fun relativeTo(base: String, target: String): String? {
        val clean = base.trimEnd('/')
        return when {
            target == clean -> ""
            target.startsWith(clean + "/") -> target.substring(clean.length + 1)
            else -> null
        }
    }
}
