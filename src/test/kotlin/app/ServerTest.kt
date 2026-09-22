package app

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun homePageShowsWorkshopTitle() = testApplication {
        val database = Database(":memory:")
        application { configureApp(database) }
        val response = client.get("/")
        assertTrue(response.bodyAsText().contains("表型生长轨工坊"))
    }
}
