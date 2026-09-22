package app

import kotlinx.serialization.encodeToString

object Service {

    fun loadFixture(resourceName: String = "fixture.json"): Pair<Fixture, String> {
        val text = Database.readResourceOrFile(resourceName)
        val fixture = Engine.json.decodeFromString<Fixture>(text)
        return fixture to text
    }

    @Synchronized
    fun seedIfEmpty(db: Database, fixtureText: String, fixture: Fixture) {
        if (db.isSeeded()) return
        db.conn.autoCommit = false
        try {
            Generator.generate(db.conn, fixture)
            db.setMeta("fixture_version", fixture.fixtureVersion)
            db.setMeta("fixture_text", fixtureText)
            db.setBoundary(fixture.cameraSwitchDay, false)
            db.conn.commit()
        } catch (e: Exception) {
            db.conn.rollback()
            throw e
        } finally {
            db.conn.autoCommit = true
        }
    }

    @Synchronized
    fun reseed(db: Database, fixtureText: String, fixture: Fixture) {
        db.conn.autoCommit = false
        try {
            db.wipe()
            Generator.generate(db.conn, fixture)
            db.setMeta("fixture_version", fixture.fixtureVersion)
            db.setMeta("fixture_text", fixtureText)
            db.setBoundary(fixture.cameraSwitchDay, false)
            db.conn.commit()
        } catch (e: Exception) {
            db.conn.rollback()
            throw e
        } finally {
            db.conn.autoCommit = true
        }
    }

    fun exportReplay(db: Database, fixture: Fixture): ReplayBundle = ReplayBundle(
        fixture = fixture,
        qualityCorrections = db.corrections(),
        boundary = db.boundary(fixture.cameraSwitchDay),
        runs = db.runs()
    )

    fun exportReplayJson(db: Database, fixture: Fixture): String =
        Engine.json.encodeToString(exportReplay(db, fixture))
}
