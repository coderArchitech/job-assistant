package com.coderarchitech.jobassistant.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema migration, in one place so the builder and the migration tests use the same list.
 *
 * To change the schema: raise [AgentDatabase.VERSION], add a `Migration(from, to)` here, build once so
 * Room exports `schemas/.../<version>.json`, and add `migrateNToM` to `MigrationTest`. CI fails if the
 * test is missing (`scripts/ci/check_migration_tests.py`). There is no destructive fallback: a missing
 * migration fails loudly rather than wiping the user's history.
 */
internal object AgentMigrations {
    /** Version 2 (T1.14): prompt-cache writes are billed above the base rate, so usage rows count them. */
    private val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE usage_record ADD COLUMN cacheWriteTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

    /** Every migration, oldest first. */
    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
