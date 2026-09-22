package app

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Paths

class Database(val path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
        }
        migrate()
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS meta(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS plants(
                    plant_id TEXT PRIMARY KEY,
                    code TEXT NOT NULL,
                    ordinal INTEGER NOT NULL DEFAULT -1,
                    genotype TEXT NOT NULL,
                    treatment TEXT NOT NULL,
                    batch TEXT NOT NULL,
                    reference_flag INTEGER NOT NULL DEFAULT 0
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS observations(
                    observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    plant_id TEXT NOT NULL REFERENCES plants(plant_id),
                    trait_id TEXT NOT NULL,
                    day INTEGER NOT NULL,
                    raw_value REAL NOT NULL,
                    quality TEXT NOT NULL DEFAULT 'OK',
                    camera TEXT NOT NULL,
                    replicate INTEGER NOT NULL DEFAULT 0
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS quality_corrections(
                    correction_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    observation_id INTEGER NOT NULL REFERENCES observations(observation_id),
                    previous_quality TEXT NOT NULL,
                    new_quality TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    corrected_at TEXT NOT NULL
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS camera_boundary(
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    switch_day INTEGER NOT NULL,
                    confirmed INTEGER NOT NULL DEFAULT 0,
                    confirmed_at TEXT
                )"""
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS analysis_runs(
                    run_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    label TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    design_version TEXT NOT NULL,
                    fixture_version TEXT NOT NULL,
                    preprocess_version TEXT NOT NULL,
                    result_json TEXT NOT NULL
                )"""
            )
        }
    }

    fun isSeeded(): Boolean =
        conn.prepareStatement("SELECT COUNT(*) FROM plants").use { ps ->
            ps.executeQuery().use { it.next() && it.getInt(1) > 0 }
        }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement(
            """INSERT INTO meta(key,value) VALUES(?,?)
               ON CONFLICT(key) DO UPDATE SET value=excluded.value"""
        ).use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    fun getMeta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    fun plants(): List<Plant> =
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT plant_id, code, ordinal, genotype, treatment, batch, reference_flag FROM plants ORDER BY reference_flag, plant_id"
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        Plant(
                            rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getInt(7) == 1
                        )
                    )
                }
            }
        }

    fun observations(): List<Observation> =
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT observation_id, plant_id, trait_id, day, raw_value, quality, camera, replicate
                   FROM observations ORDER BY plant_id, trait_id, day, replicate"""
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        Observation(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getInt(8)
                        )
                    )
                }
            }
        }

    fun getObservation(id: Long): Observation? =
        conn.prepareStatement(
            """SELECT observation_id, plant_id, trait_id, day, raw_value, quality, camera, replicate
               FROM observations WHERE observation_id=?"""
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) Observation(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                    rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getInt(8)
                ) else null
            }
        }

    fun correctQuality(id: Long, newQuality: String, reason: String): Observation? {
        val obs = getObservation(id) ?: return null
        val now = Instant.now().toString()
        conn.prepareStatement(
            "INSERT INTO quality_corrections(observation_id, previous_quality, new_quality, reason, corrected_at) VALUES(?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, id); ps.setString(2, obs.quality)
            ps.setString(3, newQuality); ps.setString(4, reason); ps.setString(5, now)
            ps.executeUpdate()
        }
        conn.prepareStatement("UPDATE observations SET quality=? WHERE observation_id=?").use { ps ->
            ps.setString(1, newQuality); ps.setLong(2, id); ps.executeUpdate()
        }
        return getObservation(id)
    }

    fun corrections(): List<QualityCorrectionDto> =
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT observation_id, previous_quality, new_quality, reason, corrected_at
                   FROM quality_corrections ORDER BY correction_id"""
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        QualityCorrectionDto(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)
                        )
                    )
                }
            }
        }

    fun boundary(defaultDay: Int): BoundaryDto {
        val row = conn.createStatement().executeQuery(
            "SELECT switch_day, confirmed, confirmed_at FROM camera_boundary WHERE id=1"
        ).use { rs -> if (rs.next()) Triple(rs.getInt(1), rs.getInt(2) == 1, rs.getString(3)) else null }
        return if (row == null) BoundaryDto(defaultDay, false, null)
        else BoundaryDto(row.first, row.second, row.third)
    }

    fun setBoundary(day: Int, confirmed: Boolean) {
        val now = if (confirmed) Instant.now().toString() else null
        conn.prepareStatement(
            """INSERT INTO camera_boundary(id, switch_day, confirmed, confirmed_at)
               VALUES(1,?,?,?)
               ON CONFLICT(id) DO UPDATE SET switch_day=excluded.switch_day,
                   confirmed=excluded.confirmed, confirmed_at=excluded.confirmed_at"""
        ).use { ps ->
            ps.setInt(1, day); ps.setInt(2, if (confirmed) 1 else 0)
            if (now == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, now)
            ps.executeUpdate()
        }
    }

    fun saveRun(label: String, versions: Versions, json: String): Long {
        conn.prepareStatement(
            """INSERT INTO analysis_runs(label, created_at, design_version, fixture_version, preprocess_version, result_json)
               VALUES(?,?,?,?,?,?)""",
            java.sql.Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, label)
            ps.setString(2, Instant.now().toString())
            ps.setString(3, versions.designVersion)
            ps.setString(4, versions.fixtureVersion)
            ps.setString(5, versions.preprocessVersion)
            ps.setString(6, json)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        error("run id unavailable")
    }

    fun runs(): List<AnalysisRunSummary> =
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT run_id, label, created_at, design_version, fixture_version, preprocess_version
                   FROM analysis_runs ORDER BY run_id"""
            ).use { rs ->
                buildList {
                    while (rs.next()) add(
                        AnalysisRunSummary(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)
                        )
                    )
                }
            }
        }

    fun runJson(id: Long): String? =
        conn.prepareStatement("SELECT result_json FROM analysis_runs WHERE run_id=?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun wipe() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM quality_corrections")
            st.executeUpdate("DELETE FROM analysis_runs")
            st.executeUpdate("DELETE FROM observations")
            st.executeUpdate("DELETE FROM plants")
            st.executeUpdate("DELETE FROM camera_boundary")
            st.executeUpdate("DELETE FROM meta")
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name IN ('observations','quality_corrections','analysis_runs')")
        }
    }

    companion object {
        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun readResourceOrFile(name: String): String {
            val res = Database::class.java.classLoader.getResourceAsStream(name)
            if (res != null) return res.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            return Files.readString(Paths.get(name), StandardCharsets.UTF_8)
        }
    }
}
