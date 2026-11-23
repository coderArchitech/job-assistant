package com.coderarchitech.jobassistant.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration tests (task T1.04), on Robolectric so they run on every push. [MigrationTestHelper] builds a
 * database from the exported schema of an old version, and Room validates the migrated result against the
 * new one.
 *
 * Adding a schema version means adding a `migrateNToM` test here: create version N, insert rows, run the
 * migration with [MigrationTestHelper.runMigrationsAndValidate], then read the rows back. CI runs
 * `scripts/ci/check_migration_tests.py`, which fails when a schema version has no such test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AgentDatabase::class.java)

    /** Version 2 adds the cache-write token count to usage rows; existing rows keep their data and get 0. */
    @Test
    fun migrate1To2() {
        helper.createDatabase(DB_NAME, 1).apply {
            execSQL(
                "INSERT INTO usage_record (id, runId, provider, model, task, inputTokens, outputTokens, " +
                    "cachedTokens, estimatedCostMicros, recordedAt) " +
                    "VALUES (7, NULL, 'ANTHROPIC', 'm', 'SCORE', 1500, 200, 1000, 4200, 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 2, true, *AgentMigrations.ALL.toTypedArray())

        val columns = "inputTokens, outputTokens, cachedTokens, cacheWriteTokens, estimatedCostMicros"
        db.query("SELECT $columns FROM usage_record WHERE id = 7").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals(listOf(1500L, 200L, 1000L, 0L, 4200L), (0..4).map(it::getLong))
        }
        db.close()
    }

    /** Version 1 has nothing to migrate from; this proves its exported schema can build a database. */
    @Test
    fun createsVersion1() {
        helper.createDatabase(DB_NAME, 1).close()
    }

    /**
     * The newest exported schema is the one the entities produce: Room checks the file's tables against
     * the current entities when it opens it, so a schema change without a new version fails here.
     */
    @Test
    fun theCurrentSchemaMatchesTheEntities() {
        helper.createDatabase(DB_NAME, AgentDatabase.VERSION).close()

        val db = openCurrent()
        try {
            db.openHelper.writableDatabase
        } finally {
            db.close()
        }
    }

    @Test
    fun everyVersionStepHasAMigration() {
        val steps = AgentMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()

        for (from in 1 until AgentDatabase.VERSION) {
            assertEquals("a migration from $from to ${from + 1} is registered", true, (from to from + 1) in steps)
        }
    }

    @Test
    fun everyEarlierVersionMigratesToTheCurrentOne() {
        for (from in 1 until AgentDatabase.VERSION) {
            // Start each round from a fresh file, so an earlier round's result is not mistaken for version [from].
            File(DB_NAME).delete()
            helper.createDatabase(DB_NAME, from).close()
            helper
                .runMigrationsAndValidate(DB_NAME, AgentDatabase.VERSION, true, *AgentMigrations.ALL.toTypedArray())
                .close()
        }
    }

    private fun openCurrent(): AgentDatabase =
        Room
            .databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java, DB_NAME)
            .addMigrations(*AgentMigrations.ALL.toTypedArray())
            .build()

    private companion object {
        /**
         * An absolute path, not a bare name: Room's test helper hands its driver the resolved path, which a
         * bare name never equals, so Robolectric would reject the open.
         */
        val DB_NAME: String =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getDatabasePath("migration-test.db")
                .absolutePath
    }
}
