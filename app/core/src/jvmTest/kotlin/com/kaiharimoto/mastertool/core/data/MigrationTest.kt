package com.kaiharimoto.mastertool.core.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upgrading an install that already exists.
 *
 * Both driver factories hand `MasterToolDatabase.Schema` to the driver, so a
 * device that already has the app is migrated in place on next launch. If a new
 * table is added to a `.sq` file without a matching `.sqm`, the schema version
 * does not move, no migration runs, and the first query against the new table
 * throws — on every existing install, and only on those. A fresh install would
 * look perfectly fine, which is exactly what makes it easy to ship.
 *
 * These tests are the reason `verifyMigrations` is not switched on in
 * `build.gradle.kts`: that check wants a checked-in `.db` snapshot of every old
 * schema and this plugin version registers no task to produce one, whereas this
 * runs in the `:core` test job on every push.
 */
class MigrationTest {

    /** The schema exactly as version 1 shipped it. Never edit — history is fixed. */
    private val version1 = listOf(
        """
        CREATE TABLE cardEntity (
            id              INTEGER NOT NULL PRIMARY KEY,
            name            TEXT    NOT NULL,
            type            TEXT    NOT NULL,
            frameType       TEXT    NOT NULL,
            description     TEXT    NOT NULL DEFAULT '',
            race            TEXT,
            attribute       TEXT    NOT NULL DEFAULT 'UNKNOWN',
            atk             INTEGER,
            def             INTEGER,
            level           INTEGER,
            linkValue       INTEGER,
            linkMarkers     TEXT    NOT NULL DEFAULT '',
            pendulumScale   INTEGER,
            archetype       TEXT,
            imageUrl        TEXT,
            imageUrlSmall   TEXT,
            tcgBanStatus    TEXT    NOT NULL DEFAULT 'UNLIMITED',
            ocgBanStatus    TEXT    NOT NULL DEFAULT 'UNLIMITED',
            alternateIds    TEXT    NOT NULL DEFAULT ''
        )
        """.trimIndent(),
        "CREATE INDEX cardEntity_name ON cardEntity(name)",
        "CREATE INDEX cardEntity_archetype ON cardEntity(archetype)",
        """
        CREATE TABLE cardSyncState (
            id              INTEGER NOT NULL PRIMARY KEY,
            lastSyncEpochMs INTEGER NOT NULL,
            cardCount       INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE deckEntity (
            id                TEXT    NOT NULL PRIMARY KEY,
            name              TEXT    NOT NULL,
            main              TEXT    NOT NULL DEFAULT '',
            extra             TEXT    NOT NULL DEFAULT '',
            side              TEXT    NOT NULL DEFAULT '',
            notes             TEXT    NOT NULL DEFAULT '',
            extendedJson      TEXT,
            createdAtEpochMs  INTEGER NOT NULL,
            updatedAtEpochMs  INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX deckEntity_updatedAt ON deckEntity(updatedAtEpochMs)",
    )

    private fun versionOneDatabase(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties()).also { driver ->
            version1.forEach { driver.execute(null, it, 0) }
        }

    private fun SqlDriver.column(sql: String): List<String> = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows += cursor.getString(0).orEmpty()
            QueryResult.Value(rows)
        },
        parameters = 0,
    ).value

    private fun SqlDriver.objects(): List<String> =
        column("SELECT type || ' ' || name FROM sqlite_master ORDER BY type, name")

    private fun SqlDriver.tables(): List<String> =
        column("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    private fun SqlDriver.columnsOf(table: String): List<String> =
        column("SELECT name FROM pragma_table_info('$table') ORDER BY name")

    @Test
    fun addingATableMovedTheSchemaVersion() {
        // The whole failure mode in one assertion: a new table with no migration
        // file leaves this at 1 and nothing upgrades.
        // 3, not 2: v1.1.0 shipped from a branch whose history stamped devices
        // at user_version 3, so the version can never fall below that again.
        // 4 from 1.1.0 (Phase B): each card's release data, `migrations/3.sqm`.
        assertEquals(4L, MasterToolDatabase.Schema.version)
    }

    @Test
    fun migratingVersionOneProducesTheCurrentSchema() {
        val migrated = versionOneDatabase()
        MasterToolDatabase.Schema.migrate(migrated, 1L, MasterToolDatabase.Schema.version)

        val fresh = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties(),
            MasterToolDatabase.Schema,
        )

        assertEquals(fresh.objects(), migrated.objects())
        fresh.tables().forEach { table ->
            assertEquals(fresh.columnsOf(table), migrated.columnsOf(table), "columns of $table")
        }
    }

    @Test
    fun anUpgradedInstallKeepsItsDecksAndCanStorePreferences() {
        val driver = versionOneDatabase()

        // A deck saved by the version that is already on the device.
        driver.execute(
            null,
            """
            INSERT INTO deckEntity(id, name, main, extra, side, notes, extendedJson,
                                   createdAtEpochMs, updatedAtEpochMs)
            VALUES ('deck-1', 'Existing Deck', '14558127,14558127', '', '', '', NULL, 1, 1)
            """.trimIndent(),
            0,
        )

        MasterToolDatabase.Schema.migrate(driver, 1L, MasterToolDatabase.Schema.version)
        val database = MasterToolDatabase(driver)

        val deck = database.deckQueries.selectById("deck-1").executeAsOne()
        assertEquals("Existing Deck", deck.name)
        assertEquals("14558127,14558127", deck.main)

        database.preferenceQueries.upsert("deckbuilder.ui", "{}")
        assertEquals("{}", database.preferenceQueries.selectByKey("deckbuilder.ui").executeAsOne())
        assertTrue(driver.tables().contains("preferenceEntity"))
    }

    @Test
    fun aPoolStoredBefore110ReadsWithItsReleaseDataUnknown() {
        // A version-3 install: the schema as 1.0.99 shipped it, with one card in its pool.
        val driver = versionOneDatabase()
        MasterToolDatabase.Schema.migrate(driver, 1L, 3L)
        driver.execute(
            null,
            """
            INSERT INTO cardEntity(id, name, type, frameType, description, race, attribute, atk, def, level, linkValue,
                                   linkMarkers, pendulumScale, archetype, imageUrl, imageUrlSmall, tcgBanStatus,
                                   ocgBanStatus, alternateIds)
            VALUES (14558127, 'Ash Blossom & Joyous Spring', 'Tuner Monster', 'effect', '', 'Zombie', 'FIRE', 0, 1800,
                    3, NULL, '', NULL, NULL, NULL, NULL, 'UNLIMITED', 'SEMI_LIMITED', '14558127,14558128')
            """.trimIndent(),
            0,
        )

        MasterToolDatabase.Schema.migrate(driver, 3L, MasterToolDatabase.Schema.version)
        val row = MasterToolDatabase(driver).cardQueries.selectAll().executeAsList().single()
        val card = CardMapper.toDomain(row)
        assertEquals("Ash Blossom & Joyous Spring", card.name)
        assertEquals(listOf(14558127, 14558128), card.alternateIds.map { it.value })
        assertEquals(null, card.tcgDate)
        assertEquals(emptyList(), card.formats)
        assertEquals(null, card.genesysPoints)
    }
}
