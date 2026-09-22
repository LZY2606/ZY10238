package app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeterminismTest {

    private fun freshDb(folder: File, name: String): Database {
        val path = File(folder, name).absolutePath
        File(path).delete()
        val db = Database(path)
        val (fixture, text) = Service.loadFixture()
        Service.seedIfEmpty(db, text, fixture)
        return db
    }

    @Test
    fun `reseed reproduces identical observations and versions`(@TempDir folder: File) {
        val db1 = freshDb(folder, "a.db")
        val first = db1.observations().map { it.rawValue }
        val (fixture, text) = Service.loadFixture()
        Service.reseed(db1, text, fixture)
        val second = db1.observations().map { it.rawValue }
        assertEquals(first, second)
        assertTrue(first.size > 300)

        val db2 = freshDb(folder, "b.db")
        assertEquals(first, db2.observations().map { it.rawValue })
    }

    @Test
    fun `same-time repeats are kept as distinct rows`(@TempDir folder: File) {
        val db = freshDb(folder, "c.db")
        val (fixture, _) = Service.loadFixture()
        val plant = db.plants().first { it.code == "P-001" }
        val rows = db.observations().filter {
            it.plantId == plant.plantId && it.traitId == "leaf_area" && it.day == 6
        }
        assertEquals(2, rows.size, "第6天必须保留两条独立重复观测")
        assertEquals(setOf("OK", "SUSPECT"), rows.map { it.quality }.toSet())
        assertEquals(1, rows.count { it.replicate == 1 })
    }
}
