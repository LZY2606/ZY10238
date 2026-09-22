package app

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class StateResponse(
    val title: String,
    val fixtureVersion: String,
    val versions: Versions,
    val boundary: BoundaryDto,
    val nPlants: Int,
    val nObservations: Int,
    val nCorrections: Int,
    val nRuns: Int,
    val genotypes: List<CodeLabel>,
    val treatments: List<CodeLabel>,
    val batches: List<BatchSpec>,
    val traits: List<TraitSpec>,
    val days: List<Int>
)

@Serializable
data class QualityUpdateRequest(val quality: String, val reason: String = "")

@Serializable
data class BoundaryRequest(val switchDay: Int, val confirmed: Boolean)

@Serializable
data class RunRequest(val label: String)

@Serializable
data class ImportRequest(val fixtureText: String)

@Serializable
data class ReferenceToggleRequest(val excludeReferencePlants: Boolean)

@Serializable
data class ToggleResponse(val status: String, val changed: Int)

@Serializable
data class RunResponse(val runId: Long, val status: String)

@Serializable
data class SimpleStatus(val status: String, val nPlants: Int = 0, val fixtureVersion: String? = null)

@Serializable
data class DesignResponse(val cells: List<DesignCell>, val missingCells: List<DesignGap>)

@Serializable
data class ObservationsResponse(val observations: List<ObservationDto>)

@Serializable
data class CoverageResponse(val coverage: List<CoverageRow>)

@Serializable
data class OffsetsResponse(
    val cameraOffsets: List<OffsetEstimate>,
    val batchOffsets: List<BatchOffsetEstimate>,
    val boundary: AnalysisMeta,
    val warnings: List<String>
)

@Serializable
data class RunsResponse(val runs: List<AnalysisRunSummary>)

class AppEnv {
    lateinit var db: Database
    lateinit var fixture: Fixture
    lateinit var fixtureText: String
}

val appEnv = AppEnv()

fun Application.configure() {
    val fixture = appEnv.fixture
    val fixtureText = appEnv.fixtureText
    val db = appEnv.db
    install(ContentNegotiation) { json(Engine.json) }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request")))
            }
            exception<Throwable> { call, cause ->
                cause.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "internal error")))
            }
        }

        routing {
            staticResources("/static", "static")

            get("/") {
                val html = Database.readResourceOrFile("static/index.html")
                call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            get("/api/state") {
                val versions = Engine.versions(db, fixture, fixtureText)
                call.respond(
                    StateResponse(
                        title = "表型生长轨工坊",
                        fixtureVersion = fixture.fixtureVersion,
                        versions = versions,
                        boundary = db.boundary(fixture.cameraSwitchDay),
                        nPlants = db.plants().size,
                        nObservations = db.observations().size,
                        nCorrections = db.corrections().size,
                        nRuns = db.runs().size,
                        genotypes = fixture.genotypes,
                        treatments = fixture.treatments,
                        batches = fixture.batches,
                        traits = fixture.traits,
                        days = fixture.days
                    )
                )
            }

            get("/api/design") {
                val plants = db.plants()
                val cells = fixture.cells.map { cell ->
                    val members = plants.filter {
                        it.genotype == cell.genotype && it.treatment == cell.treatment
                    }
                    DesignCell(
                        cell.genotype, cell.treatment,
                        members.map { it.batch }.distinct().sorted(),
                        members.map { PlantSummary(it.plantId, it.code, it.batch, it.reference) }
                    )
                }
                call.respond(DesignResponse(cells, Engine.run {
                    fixture.genotypes.flatMap { g ->
                        fixture.treatments.mapNotNull { t ->
                            if (fixture.cells.none { it.genotype == g.id && it.treatment == t.id })
                                DesignGap(g.id, t.id, "设计空缺：${g.id} × ${t.id} 无观测") else null
                        }
                    }
                }))
            }

            get("/api/observations") {
                val plantId = call.request.queryParameters["plantId"]
                val traitId = call.request.queryParameters["traitId"]
                val plants = db.plants().associateBy { it.plantId }
                var obs = db.observations()
                if (plantId != null) obs = obs.filter { it.plantId == plantId }
                if (traitId != null) obs = obs.filter { it.traitId == traitId }
                val dtos = obs.map { o ->
                    val p = plants[o.plantId]!!
                    val t = fixture.traits.first { it.id == o.traitId }
                    ObservationDto(
                        o.observationId, p.plantId, p.code, p.genotype, p.treatment,
                        p.batch, p.reference, o.traitId, t.label, t.unit, o.day,
                        o.rawValue, null, o.quality, o.camera, o.replicate
                    )
                }
                call.respond(ObservationsResponse(dtos))
            }

            get("/api/coverage") {
                val preview = Engine.analyze(db, fixture, "preview")
                call.respond(CoverageResponse(preview.coverage))
            }

            get("/api/offsets") {
                val preview = Engine.analyze(db, fixture, "preview")
                call.respond(
                    OffsetsResponse(
                        preview.cameraOffsets,
                        preview.batchOffsets,
                        preview.meta,
                        preview.warnings
                    )
                )
            }

            get("/api/analysis/preview") {
                call.respond(Engine.analyze(db, fixture, "preview"))
            }

            post("/api/quality") {
                val req = call.receive<QualityUpdateRequest>()
                require(req.quality in listOf("OK", "SUSPECT", "EXCLUDED")) { "quality 必须为 OK/SUSPECT/EXCLUDED" }
                val id = call.request.queryParameters["observationId"]?.toLongOrNull()
                    ?: throw IllegalArgumentException("缺少 observationId")
                val updated = db.correctQuality(id, req.quality, req.reason.ifBlank { "页面修正" })
                    ?: throw IllegalArgumentException("观测不存在")
                call.respond(mapOf("status" to "ok", "quality" to updated.quality))
            }

            post("/api/boundary") {
                val req = call.receive<BoundaryRequest>()
                require(req.switchDay in fixture.days) { "换机日必须落在成像日内" }
                db.setBoundary(req.switchDay, req.confirmed)
                call.respond(db.boundary(fixture.cameraSwitchDay))
            }

            post("/api/runs") {
                val req = call.receive<RunRequest>()
                val result = Engine.analyze(db, fixture, req.label.ifBlank { "分析运行" })
                val versions = Engine.versions(db, fixture, db.getMeta("fixture_text")!!)
                val id = db.saveRun(req.label.ifBlank { "分析运行" }, versions, Engine.toJson(result))
                call.respond(RunResponse(id, "created"))
            }

            get("/api/runs") {
                call.respond(RunsResponse(db.runs()))
            }

            get("/api/runs/{id}") {
                val id = call.parameters["id"]!!.toLong()
                val json = db.runJson(id) ?: throw IllegalArgumentException("运行不存在")
                call.respondText(json, ContentType.Application.Json)
            }

            get("/api/runs/{id}/export") {
                val id = call.parameters["id"]!!.toLong()
                val json = db.runJson(id) ?: throw IllegalArgumentException("运行不存在")
                val result = Engine.json.decodeFromString<AnalysisResult>(json)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=pheno-run-$id.csv"
                )
                call.respondText(CsvExport.runCsv(result), ContentType.Text.CSV)
            }

            get("/api/replay") {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=pheno-replay.json"
                )
                call.respondText(Service.exportReplayJson(db, fixture), ContentType.Application.Json)
            }

            post("/api/reseed") {
                Service.reseed(db, fixtureText, fixture)
                call.respond(SimpleStatus("reseeded", nPlants = db.plants().size))
            }

            post("/api/import-fixture") {
                val req = call.receive<ImportRequest>()
                val imported = Engine.json.decodeFromString<Fixture>(req.fixtureText)
                val text = req.fixtureText
                Service.reseed(db, text, imported)
                call.respond(SimpleStatus("imported", fixtureVersion = imported.fixtureVersion))
            }

            post("/api/reference-toggle") {
                // 验收辅助：把所有重叠参照植株标记为 EXCLUDED，模拟移除参照后偏移不可识别。
                val req = call.receive<ReferenceToggleRequest>()
                val plants = db.plants().filter { it.reference }
                var n = 0
                for (p in plants) {
                    val obs = db.observations().filter {
                        it.plantId == p.plantId &&
                            it.day in fixture.cameraOverlapDays
                    }
                    for (o in obs) {
                        val target = if (req.excludeReferencePlants) "EXCLUDED" else "OK"
                        if (o.quality != target) {
                            db.correctQuality(o.observationId, target,
                                if (req.excludeReferencePlants) "验收：移除重叠参照" else "验收：恢复参照")
                            n++
                        }
                    }
                }
                call.respond(ToggleResponse("ok", n))
            }
        }
}

fun startServer(port: Int, dbPath: String) {
    val (fixture, fixtureText) = Service.loadFixture()
    val db = Database(dbPath)
    Service.seedIfEmpty(db, fixtureText, fixture)
    appEnv.db = db
    appEnv.fixture = fixture
    appEnv.fixtureText = fixtureText
    embeddedServer(Netty, port = port, host = "127.0.0.1") {
        configure()
    }.start(wait = true)
}

fun defaultDbPath(): String {
    val dir = File("data")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "pheno.db").absolutePath
}
