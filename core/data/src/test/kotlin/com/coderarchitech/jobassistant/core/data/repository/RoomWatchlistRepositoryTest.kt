package com.coderarchitech.jobassistant.core.data.repository

import com.coderarchitech.jobassistant.core.data.db.DatabaseTest
import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWatchlistRepositoryTest : DatabaseTest() {
    private fun repository() = RoomWatchlistRepository(db.companyWatchlistDao())

    private fun company(
        name: String,
        board: String,
        ats: AtsType = AtsType.GREENHOUSE,
        id: Long = 0,
    ) = CompanyWatchlistEntry(id, name, ats, board)

    @Test
    fun companiesAreSavedTrimmedAndListedByName() =
        runBlocking {
            val repo = repository()
            repo.save(company("  Stripe ", " stripe "))
            repo.save(company("Ashby", "Ashby", AtsType.ASHBY))

            val all = repo.observeAll().first()

            assertEquals(listOf("Ashby", "Stripe"), all.map { it.name })
            assertEquals("stripe", all.last().boardSlug)
        }

    @Test
    fun theSameBoardIsADuplicateWhateverItsCase() =
        runBlocking {
            val repo = repository()
            val first = repo.save(company("Stripe", "stripe")) as WatchlistSave.Saved

            assertEquals(WatchlistSave.Duplicate, repo.save(company("Stripe again", "Stripe")))
            assertTrue(
                "another ATS is another board",
                repo.save(company("Stripe", "stripe", AtsType.LEVER)) is WatchlistSave.Saved,
            )
            assertEquals(
                "a company may be saved again under its own board",
                WatchlistSave.Saved(first.id),
                repo.save(company("Stripe, Inc.", "STRIPE", id = first.id)),
            )
        }

    @Test
    fun aRemovedCompanyIsGone() =
        runBlocking {
            val repo = repository()
            val saved = repo.save(company("Stripe", "stripe")) as WatchlistSave.Saved

            repo.delete(company("Stripe", "stripe", id = saved.id))

            assertTrue(repo.observeAll().first().isEmpty())
        }
}
