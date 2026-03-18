package mihon.desktop.domain

import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeCategoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopCategoryManagerTest {

    private fun manager(repo: FakeCategoryRepository = FakeCategoryRepository()) =
        DesktopCategoryManager(repo) to repo

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    fun `create inserts category with auto-incremented order`() = runBlocking<Unit> {
        val (mgr, repo) = manager()

        mgr.create("Action")
        mgr.create("Comedy")

        val all = repo.getAll()
        assertEquals(2, all.size)
        assertEquals("Action", all[0].name)
        assertEquals(0L, all[0].order)
        assertEquals("Comedy", all[1].name)
        assertEquals(1L, all[1].order)
    }

    @Test
    fun `create with blank name is rejected`() = runBlocking<Unit> {
        val (mgr, repo) = manager()

        val result = mgr.create("  ")

        assertTrue(result is DesktopCategoryManager.Result.Error)
        assertEquals(0, repo.getAll().size)
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    fun `rename updates category name`() = runBlocking<Unit> {
        val (mgr, repo) = manager()
        mgr.create("Old Name")
        val catId = repo.getAll().first().id

        mgr.rename(catId, "New Name")

        assertEquals("New Name", repo.get(catId)?.name)
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete removes category and reorders remaining`() = runBlocking<Unit> {
        val (mgr, repo) = manager()
        mgr.create("A") // order 0
        mgr.create("B") // order 1
        mgr.create("C") // order 2
        val bId = repo.getAll().first { it.name == "B" }.id

        mgr.delete(bId)

        val remaining = repo.getAll().sortedBy { it.order }
        assertEquals(2, remaining.size)
        assertEquals("A", remaining[0].name)
        assertEquals(0L, remaining[0].order)
        assertEquals("C", remaining[1].name)
        assertEquals(1L, remaining[1].order)
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    @Test
    fun `reorder moves category to new position`() = runBlocking<Unit> {
        val (mgr, repo) = manager()
        mgr.create("A") // order 0
        mgr.create("B") // order 1
        mgr.create("C") // order 2
        val cId = repo.getAll().first { it.name == "C" }.id

        mgr.reorder(cId, newIndex = 0)

        val ordered = repo.getAll().sortedBy { it.order }
        assertEquals(listOf("C", "A", "B"), ordered.map { it.name })
    }

    @Test
    fun `reorder non-existent category returns unchanged`() = runBlocking<Unit> {
        val (mgr, _) = manager()
        mgr.create("A")

        val result = mgr.reorder(categoryId = 999L, newIndex = 0)

        assertTrue(result is DesktopCategoryManager.Result.Unchanged)
    }

    // ── Get all ───────────────────────────────────────────────────────────────

    @Test
    fun `getAll returns categories sorted by order`() = runBlocking<Unit> {
        val (mgr, _) = manager()
        mgr.create("Zebra")
        mgr.create("Apple")

        val all = mgr.getAll()

        assertEquals(listOf("Zebra", "Apple"), all.map { it.name })
        assertEquals(listOf(0L, 1L), all.map { it.order })
    }
}
