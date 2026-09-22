package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    val arguments = args.toList()
    val port = arguments.zipWithNext().firstOrNull { it.first == "--port" }?.second?.toIntOrNull() ?: 5578
    val databasePath = arguments.zipWithNext().firstOrNull { it.first == "--db" }?.second ?: "data/phenotype.sqlite"
    File(databasePath).parentFile?.mkdirs()
    val server = buildServer(port, Database(databasePath))
    server.start(wait = true)
}

fun buildServer(port: Int, database: Database) = embeddedServer(CIO, port = port, host = "127.0.0.1") {
    configureApp(database)
}

fun Application.configureApp(database: Database) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = true })
    }
    val analysis = AnalysisService(database)

    routing {
        get("/") {
            val html = object {}::class.java.getResourceAsStream("/web/index.html")?.bufferedReader()?.use { it.readText() }
                ?: error("index.html missing")
            call.respondText(html, ContentType.Text.Html)
        }
        get("/app.js") {
            val javascript = object {}::class.java.getResourceAsStream("/web/app.js")?.bufferedReader()?.use { it.readText() }
                ?: error("app.js missing")
            call.respondText(javascript, ContentType.Text.JavaScript)
        }

        get("/api/bootstrap") {
            call.respond(
                Bootstrap(
                    plants = database.plants(),
                    observations = database.observations(),
                    effectiveObservations = database.effectiveObservations(),
                    boundaries = database.boundaries(),
                    designVersionId = database.designVersionId(),
                    preprocessingVersionId = database.latestPreprocessingVersion(),
                    checksum = database.checksum(),
                    runs = database.runs(),
                )
            )
        }

        post("/api/analysis") {
            val request = call.receive<AnalysisRequest>()
            val result = analysis.analyze(request)
            val runId = if (request.persist) database.saveRun(result, request) else null
            call.respond(result.copy(runId = runId))
        }

        get("/api/runs") { call.respond(database.runs()) }
        get("/api/runs/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            val run = database.runs().firstOrNull { it.id == id }
            if (run == null) call.respond(HttpStatusCode.NotFound, mapOf("error" to "运行不存在")) else call.respond(run)
        }
        get("/api/runs/{id}/export") {
            val id = call.parameters["id"]?.toLongOrNull()
            val run = database.runs().firstOrNull { it.id == id }
            if (run == null) {
                call.respond(HttpStatusCode.NotFound, "运行不存在")
            } else {
                call.response.headers.append("Content-Disposition", "attachment; filename=analysis-run-${run.id}.json")
                call.respondText(database.json.encodeToString(run), ContentType.Application.Json)
            }
        }

        get("/api/plants/{id}/observations") {
            val plantId = call.parameters["id"].orEmpty()
            val observations = database.observations().filter { it.plantId == plantId }
            val effective = database.effectiveObservations().filter { it.observation.plantId == plantId }
            call.respond(mapOf("observations" to observations, "effectiveObservations" to effective))
        }

        post("/api/observations/{id}/quality") {
            val id = call.parameters["id"].orEmpty()
            val request = call.receive<QualityRequest>()
            try {
                val version = database.setQuality(id, request.quality, request.reason)
                call.respond(mapOf("preprocessingVersionId" to version))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "请求无效")))
            }
        }

        post("/api/batches/{batch}/boundary") {
            val batch = call.parameters["batch"].orEmpty()
            val request = call.receive<BoundaryRequest>()
            if (database.boundary(batch) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "批次不存在"))
            } else if (request.boundaryDay !in 1..30) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "换机日必须在 1 到 30 之间"))
            } else {
                database.setBoundary(batch, request.boundaryDay)
                val updated = requireNotNull(database.boundary(batch))
                call.respond(updated)
            }
        }

        post("/api/admin/reimport") {
            val checksum = database.reseed("用户清空并重新导入固定 fixture")
            call.respond(ReimportResponse(checksum, "已清空业务数据并从固定 fixture 重新导入"))
        }

        get("/api/logs/export") {
            call.response.headers.append("Content-Disposition", "attachment; filename=operation-logs.json")
            call.respondText(database.json.encodeToString(database.logs()), ContentType.Application.Json)
        }
        get("/api/logs") { call.respond(database.logs()) }
    }
}
