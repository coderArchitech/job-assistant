package com.coderarchitech.jobassistant.core.data.db

import androidx.room.migration.Migration

/**
 * Every schema migration, in one place so the builder and the migration tests use the same list.
 *
 * To change the schema: raise [AgentDatabase.VERSION], add a `Migration(from, to)` here, build once so
 * Room exports `schemas/.../<version>.json`, and add `migrateNToM` to `MigrationTest`. CI fails if the
 * test is missing (`scripts/ci/check_migration_tests.py`). There is no destructive fallback: a missing
 * migration fails loudly rather than wiping the user's history.
 */
internal object AgentMigrations {
    /** Empty while the schema is at version 1. */
    val ALL: List<Migration> = emptyList()
}
