package app

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class HttpTest {

    private fun configureTestApp() {
        val tmp = Files.createTempFile("pheno-http", ".db").also { Files.deleteIfExists(it) }
        val db = Database(tmp.toString())
        val (fx, text) = Service.loadFixture()
        Service.seedIfEmpty(db, text, fx)
        appEnv.db = db
        appEnv.fixture = fx
        appEnv.fixtureText = text
    }

    @Test
    fun `home page shows title and key endpoints respond`() = testApplication {
        application {
            configureTestApp()
            configure()
        }
        val html = client.get("/")
        if (html.status != HttpStatusCode.OK) {
            throw AssertionError("home status=${html.status} body=${html.bodyAsText()}")
        }
        assertTrue(html.bodyAsText().contains("表型生长轨工坊"))

        val state = client.get("/api/state")
        assertEquals(HttpStatusCode.OK, state.status)
        assertTrue(state.bodyAsText().contains("pheno-v1"))

        assertEquals(HttpStatusCode.OK, client.get("/api/offsets").status)

        val run = client.post("/api/runs") {
            contentType(ContentType.Application.Json)
            setBody("""{"label":"HTTP测试"}""")
        }
        assertEquals(HttpStatusCode.OK, run.status)
        assertTrue(run.bodyAsText().contains("created"))
    }

    @Test
    fun `invalid quality is rejected`() = testApplication {
        application {
            configureTestApp()
            configure()
        }
        val res = client.post("/api/quality?observationId=1") {
            contentType(ContentType.Application.Json)
            setBody("""{"quality":"WEIRD","reason":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `reference toggle flips camera identifiability`() = testApplication {
        application {
            configureTestApp()
            configure()
        }
        val before = client.get("/api/offsets").bodyAsText()
        assertTrue(before.contains("\"identifiable\": true"))
        val toggled = client.post("/api/reference-toggle") {
            contentType(ContentType.Application.Json)
            setBody("""{"excludeReferencePlants":true}""")
        }
        assertEquals(HttpStatusCode.OK, toggled.status)
        val after = client.get("/api/offsets").bodyAsText()
        if (!after.contains("没有重叠参照植株读数")) throw AssertionError("after: $after")
    }
}
