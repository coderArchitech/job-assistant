package com.coderarchitech.jobassistant.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for the stored format of `agent.db` that no DAO test would notice. */
class SchemaGuardsTest : DatabaseTest() {
    /** Every (table, column) in the database, read from SQLite itself. */
    private fun allColumns(): List<Pair<String, String>> {
        val columns = mutableListOf<Pair<String, String>>()
        db.openHelper.readableDatabase
            .query(
                "SELECT m.name, p.name FROM sqlite_master m JOIN pragma_table_info(m.name) p " +
                    "WHERE m.type = 'table' AND m.name NOT LIKE 'sqlite_%' AND m.name NOT LIKE 'room_%' " +
                    "AND m.name != 'android_metadata'",
            ).use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(0) to cursor.getString(1)
            }
        return columns
    }

    /** CLAUDE.md rule 3: secrets live only in the vault, so no column may be named for one. */
    @Test
    fun noColumnIsNamedLikeASecret() {
        val secretWords = listOf("password", "passwd", "secret", "token", "cookie", "otp", "apikey", "credential")

        val columns = allColumns()
        assertTrue("Expected to read the schema", columns.size > 100)
        // AI token counts, not credentials. Anything else that matches needs a review, not a new entry.
        val tokenCounts =
            setOf(
                "usage_record" to "inputTokens",
                "usage_record" to "outputTokens",
                "usage_record" to "cachedTokens",
                "usage_record" to "cacheWriteTokens",
            )
        val offenders =
            columns.filter { column ->
                column !in tokenCounts && secretWords.any { column.second.lowercase().contains(it) }
            }

        assertEquals("Secrets must go in the vault, not Room", emptyList<Pair<String, String>>(), offenders)
    }

    @Test
    fun theDatabaseHoldsExactlyTheDesignTables() {
        val tables = allColumns().map { it.first }.toSet()

        assertEquals(
            setOf(
                "profile",
                "search_preset",
                "company_watchlist",
                "source_cursor",
                "portal",
                "job",
                "match_result",
                "draft",
                "decision",
                "application",
                "outreach_message",
                "run",
                "usage_record",
                "daily_report",
                "cap_usage",
            ),
            tables,
        )
    }
}
