package com.coderarchitech.jobassistant.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base for DAO tests: a fresh in-memory [AgentDatabase] per test, on Robolectric's SQLite. The SDK is
 * pinned because the offline runtime jar is the one for API 36 (see `core/data/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
abstract class DatabaseTest {
    protected lateinit var db: AgentDatabase

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    /**
     * Runs [block] on a throwaway file-backed database. Unlike the in-memory one, a file database has
     * several connections (WAL mode), so concurrent writers really race instead of queueing on one.
     */
    protected suspend fun <T> withFileDatabase(block: suspend (AgentDatabase) -> T): T {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "race-${System.nanoTime()}.db"
        val fileDb = Room.databaseBuilder(context, AgentDatabase::class.java, name).build()
        try {
            return block(fileDb)
        } finally {
            fileDb.close()
            context.deleteDatabase(name)
        }
    }

    /** Fails the test unless [block] is rejected by a database constraint (unique, foreign key). */
    protected suspend fun assertConstraintViolation(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: SQLiteConstraintException) {
            return
        }
        fail("Expected the database to reject this with a constraint violation")
    }
}
