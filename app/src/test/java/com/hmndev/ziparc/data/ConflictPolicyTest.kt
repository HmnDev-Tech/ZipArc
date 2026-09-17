package com.hmndev.ziparc.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictPolicyTest {

    private fun src(vararg names: String): List<File> = names.map { File("/src", it) }

    @Test
    fun noConflictCopiesEverything() {
        val plan = buildCopyPlan(src("a.txt", "b.txt"), emptySet(), ConflictPolicy.REPLACE)

        assertEquals(listOf("a.txt", "b.txt"), plan.map { it.destName })
    }

    @Test
    fun replaceKeepsOriginalName() {
        val plan = buildCopyPlan(src("a.txt"), setOf("a.txt"), ConflictPolicy.REPLACE)

        assertEquals(listOf("a.txt"), plan.map { it.destName })
    }

    @Test
    fun skipDropsConflictingEntries() {
        val plan = buildCopyPlan(src("a.txt", "b.txt"), setOf("a.txt"), ConflictPolicy.SKIP)

        assertEquals(listOf("b.txt"), plan.map { it.destName })
    }

    @Test
    fun keepBothRenamesWithCounter() {
        val plan = buildCopyPlan(src("a.txt"), setOf("a.txt", "a (1).txt"), ConflictPolicy.KEEP_BOTH)

        assertEquals(listOf("a (2).txt"), plan.map { it.destName })
    }

    @Test
    fun keepBothPreservesExtensionAndDirectoryName() {
        assertEquals("dir (1)", uniqueName("dir", setOf("dir")))
        assertEquals("archive.tar (1).gz", uniqueName("archive.tar.gz", setOf("archive.tar.gz")))
    }

    @Test
    fun keepBothAvoidsDuplicatesWithinOneBatch() {
        val plan = buildCopyPlan(src("a.txt", "a.txt"), setOf("a.txt"), ConflictPolicy.KEEP_BOTH)

        assertEquals(listOf("a (1).txt", "a (2).txt"), plan.map { it.destName })
    }

    @Test
    fun detectsConflicts() {
        assertEquals(listOf("a.txt"), conflictsAmong(setOf("a.txt", "b.txt"), listOf("a.txt", "c.txt")))
        assertTrue(conflictsAmong(setOf("z"), listOf("a")).isEmpty())
    }

    @Test
    fun extractsTopLevelNames() {
        val names = topLevelNames(listOf("docs/readme.md", "docs/img/a.png", "root.txt", "/slash.txt"))

        assertEquals(listOf("docs", "root.txt", "slash.txt"), names)
    }
}
