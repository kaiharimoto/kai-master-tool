package com.kaiharimoto.neue

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.kaiharimoto.mastertool.core.data.DatabaseDriverFactory
import com.kaiharimoto.mastertool.core.db.MasterToolDatabase
import java.util.Properties

/**
 * SQLite over JDBC, in the application rather than `:core`, so the driver and
 * its native library stay out of the APK. Handing SQLDelight the schema lets it
 * create a fresh database and migrate an old one with the same call — the
 * schema is the tablet's, version 3, under the same rules.
 */
class NeueDatabaseDriverFactory(private val path: String) : DatabaseDriverFactory {
    override fun create(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), MasterToolDatabase.Schema)
}
