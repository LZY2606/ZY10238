package app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Properties

class Database(private val path: String) {
    val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val connection = connect()

    init {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
        }
        migrate()
        if (plantCount() == 0) reseed("initial fixture")
    }

    private fun connect(): Connection {
        val url = if (path == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$path"
        val properties = Properties().apply {
            set("foreign_keys", "true")
        }
        return DriverManager.getConnection(url, properties)
    }

    private fun migrate() {
        update(
            """
            create table if not exists design_versions(
              id integer primary key,
              created_at text not null,
              version_number integer not null,
              design_checksum text not null,
              raw_checksum text not null,
              snapshot_json text not null
            )
            """.trimIndent()
        )
        update("create table if not exists plants(id text primary key, batch text not null, position text not null, genotype text not null, treatment text not null, reference integer not null)")
        update("create table if not exists observations(id text primary key, plant_id text not null references plants(id), day integer not null, replicate text not null, camera_era text not null, leaf_area real not null, height real not null, canopy_width real not null, raw_quality text not null)")
        update("create table if not exists preprocessing_versions(id integer primary key autoincrement, created_at text not null, note text not null, override_count integer not null, data_checksum text not null)")
        update("create table if not exists quality_overrides(id integer primary key autoincrement, observation_id text not null references observations(id), quality text not null, reason text not null, created_at text not null, superseded_at text)")
        update("create table if not exists boundaries(batch text primary key, boundary_day integer not null, confirmed integer not null, confirmed_at text)")
        update("create table if not exists analysis_runs(id integer primary key autoincrement, created_at text not null, design_version_id integer not null, preprocessing_version_id integer not null, trait text not null, target_day integer not null, excluded_plant_ids text not null, result_json text not null)")
        update("create table if not exists operation_logs(id integer primary key autoincrement, created_at text not null, action text not null, detail text not null)")
        update("create unique index if not exists ux_design_versions on design_versions(version_number)")
        update("create index if not exists ix_observations_plant on observations(plant_id, day)")
        update("create index if not exists ix_quality_overrides_obs on quality_overrides(observation_id, superseded_at)")
    }

    fun reseed(reason: String): DataChecksum {
        val plants = Fixture.plants()
        val observations = Fixture.observations(plants)
        val checksum = checksum(plants, observations)
        connection.autoCommit = false
        try {
            update("delete from analysis_runs")
            update("delete from quality_overrides")
            update("delete from preprocessing_versions")
            update("delete from boundaries")
            update("delete from observations")
            update("delete from plants")
            update("delete from design_versions")
            plants.forEach { plant ->
                update(
                    "insert into plants(id,batch,position,genotype,treatment,reference) values(?,?,?,?,?,?)",
                    plant.id, plant.batch, plant.position, plant.genotype, plant.treatment, if (plant.reference) 1 else 0
                )
            }
            observations.forEach { observation ->
                update(
                    "insert into observations(id,plant_id,day,replicate,camera_era,leaf_area,height,canopy_width,raw_quality) values(?,?,?,?,?,?,?,?,?)",
                    observation.id, observation.plantId, observation.day, observation.replicate, observation.cameraEra,
                    observation.leafArea, observation.height, observation.canopyWidth, observation.rawQuality
                )
            }
            Fixture.boundaries().forEach { boundary ->
                update(
                    "insert into boundaries(batch,boundary_day,confirmed,confirmed_at) values(?,?,?,?)",
                    boundary.batch, boundary.boundaryDay, 0, null
                )
            }
            val snapshot = json.encodeToString(DesignSnapshot(plants, observations))
            update(
                "insert into design_versions(id,created_at,version_number,design_checksum,raw_checksum,snapshot_json) values(?,?,?,?,?,?)",
                Fixture.DESIGN_VERSION, now(), Fixture.DESIGN_VERSION, checksum.designChecksum, checksum.rawChecksum, snapshot
            )
            update(
                "insert into preprocessing_versions(id,created_at,note,override_count,data_checksum) values(?,?,?,?,?)",
                Fixture.initialPreprocessingVersion, now(), "固定 fixture 的初始质量口径", 0, json.encodeToString(checksum)
            )
            logLocked("REIMPORT", reason)
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
        return checksum
    }

    fun plants(): List<Plant> = query("select * from plants order by id") { getPlant(it) }.sortedBy { it.id }

    fun observations(): List<Observation> = query("select * from observations order by plant_id, day, replicate") { getObservation(it) }

    fun observation(id: String): Observation? =
        query("select * from observations where id = ?", id) { getObservation(it) }.singleOrNull()

    fun boundaries(): List<Boundary> = query("select * from boundaries order by batch") {
        Boundary(it.getString("batch"), it.getInt("boundary_day"), it.getInt("confirmed") == 1, it.getString("confirmed_at"))
    }

    fun boundary(batch: String): Boundary? =
        query("select * from boundaries where batch = ?", batch) {
            Boundary(it.getString("batch"), it.getInt("boundary_day"), it.getInt("confirmed") == 1, it.getString("confirmed_at"))
        }.singleOrNull()

    fun setBoundary(batch: String, day: Int) {
        val timestamp = now()
        update("update boundaries set boundary_day = ?, confirmed = 1, confirmed_at = ? where batch = ?", day, timestamp, batch)
        log("CONFIRM_BOUNDARY", "batch=$batch boundaryDay=$day")
    }

    fun setQuality(observationId: String, quality: String, reason: String): Int {
        require(observation(observationId) != null) { "观测不存在" }
        require(quality in setOf("good", "questionable", "bad")) { "质量状态必须是 good、questionable 或 bad" }
        val timestamp = now()
        update("update quality_overrides set superseded_at = ? where observation_id = ? and superseded_at is null", timestamp, observationId)
        update(
            "insert into quality_overrides(observation_id,quality,reason,created_at,superseded_at) values(?,?,?,?,null)",
            observationId, quality, reason, timestamp
        )
        return newPreprocessingVersion("observation=$observationId quality=$quality reason=$reason")
    }

    private fun newPreprocessingVersion(note: String): Int {
        val checksum = checksum(plants(), observations())
        val overrideCount = query("select count(*) as c from quality_overrides where superseded_at is null") { it.getInt("c") }.single()
        update(
            "insert into preprocessing_versions(created_at,note,override_count,data_checksum) values(?,?,?,?)",
            now(), note, overrideCount, json.encodeToString(checksum)
        )
        val id = query("select last_insert_rowid() as id") { it.getInt("id") }.single()
        log("PREPROCESSING_VERSION", "version=$id $note")
        return id
    }

    fun latestPreprocessingVersion(): Int =
        query("select coalesce(max(id), ?) as id from preprocessing_versions", Fixture.initialPreprocessingVersion) { it.getInt("id") }.single()

    fun designVersionId(): Int = query("select max(id) as id from design_versions") { it.getInt("id") }.single()

    fun effectiveObservations(): List<EffectiveObservation> {
        val observations = observations().associateBy { it.id }
        val qualities = query(
            "select observation_id, quality from quality_overrides where superseded_at is null order by id"
        ) { it.getString("observation_id") to it.getString("quality") }.toMap()
        return observations.values.sortedWith(compareBy({ it.plantId }, { it.day }, { it.replicate })).map { observation ->
            val quality = qualities[observation.id]
            EffectiveObservation(
                observation = observation,
                quality = quality ?: observation.rawQuality,
                qualitySource = if (quality == null) "raw" else "corrected",
                adjustedLeafArea = observation.leafArea,
                adjustedHeight = observation.height,
                adjustedCanopyWidth = observation.canopyWidth,
            )
        }
    }

    fun saveRun(response: AnalysisResponse, request: AnalysisRequest): Long {
        val timestamp = now()
        val serialized = json.encodeToString(response)
        update(
            "insert into analysis_runs(created_at,design_version_id,preprocessing_version_id,trait,target_day,excluded_plant_ids,result_json) values(?,?,?,?,?,?,?)",
            timestamp, response.designVersionId, response.preprocessingVersionId, response.trait, response.targetDay,
            json.encodeToString(request.excludedPlantIds.sorted()), serialized
        )
        val id = query("select last_insert_rowid() as id") { it.getLong("id") }.single()
        log("ANALYSIS_RUN", "run=$id trait=${response.trait} targetDay=${response.targetDay}")
        return id
    }

    fun runs(): List<RunSummary> = query("select * from analysis_runs order by id desc") {
        val result = json.decodeFromString<AnalysisResponse>(it.getString("result_json"))
        RunSummary(
            id = it.getLong("id"),
            createdAt = it.getString("created_at"),
            designVersionId = it.getInt("design_version_id"),
            preprocessingVersionId = it.getInt("preprocessing_version_id"),
            trait = it.getString("trait"),
            targetDay = it.getInt("target_day"),
            excludedPlantIds = json.decodeFromString(it.getString("excluded_plant_ids")),
            result = result
        )
    }

    fun logs(): List<OperationLog> = query("select * from operation_logs order by id") {
        OperationLog(it.getLong("id"), it.getString("created_at"), it.getString("action"), it.getString("detail"))
    }

    fun checksum(): DataChecksum = checksum(plants(), observations())

    private fun plantCount(): Int = query("select count(*) as c from plants") { it.getInt("c") }.singleOrNull() ?: 0

    private fun log(action: String, detail: String) =
        update("insert into operation_logs(created_at,action,detail) values(?,?,?)", now(), action, detail)

    private fun logLocked(action: String, detail: String) {
        connection.prepareStatement("insert into operation_logs(created_at,action,detail) values(?,?,?)").use { statement ->
            statement.setString(1, now())
            statement.setString(2, action)
            statement.setString(3, detail)
            statement.execute()
        }
    }

    private fun getPlant(resultSet: ResultSet) = Plant(
        id = resultSet.getString("id"),
        batch = resultSet.getString("batch"),
        position = resultSet.getString("position"),
        genotype = resultSet.getString("genotype"),
        treatment = resultSet.getString("treatment"),
        reference = resultSet.getInt("reference") == 1,
    )

    private fun getObservation(resultSet: ResultSet) = Observation(
        id = resultSet.getString("id"),
        plantId = resultSet.getString("plant_id"),
        day = resultSet.getInt("day"),
        replicate = resultSet.getString("replicate"),
        cameraEra = resultSet.getString("camera_era"),
        leafArea = resultSet.getDouble("leaf_area"),
        height = resultSet.getDouble("height"),
        canopyWidth = resultSet.getDouble("canopy_width"),
        rawQuality = resultSet.getString("raw_quality"),
    )

    private fun update(sql: String, vararg parameters: Any?) {
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun <T> query(sql: String, vararg parameters: Any?, mapper: (ResultSet) -> T): List<T> {
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { resultSet ->
                val result = mutableListOf<T>()
                while (resultSet.next()) result += mapper(resultSet)
                return result
            }
        }
    }

    private fun checksum(plants: List<Plant>, observations: List<Observation>): DataChecksum {
        val designText = plants.joinToString("\n") { listOf(it.id, it.batch, it.position, it.genotype, it.treatment, it.reference).joinToString("|") }
        val observationText = observations.joinToString("\n") {
            listOf(it.id, it.plantId, it.day, it.replicate, it.cameraEra, it.leafArea, it.height, it.canopyWidth, it.rawQuality).joinToString("|")
        }
        return DataChecksum(
            designChecksum = sha256(designText),
            rawChecksum = sha256(observationText),
            plantCount = plants.size,
            rawObservationCount = observations.size,
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun now(): String = Instant.now().toString()
}
