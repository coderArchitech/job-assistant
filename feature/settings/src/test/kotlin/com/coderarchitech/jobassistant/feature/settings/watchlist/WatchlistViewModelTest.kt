package com.coderarchitech.jobassistant.feature.settings.watchlist

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.core.model.CompanyWatchlistEntry
import com.coderarchitech.jobassistant.sources.BoardCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistViewModelTest {
    private val repository = FakeWatchlist()
    private val checker =
        FakeChecker(
            mapOf(
                (AtsType.GREENHOUSE to "stripe") to BoardCheck.Valid(512),
                (AtsType.LEVER to "leverdemo") to BoardCheck.Valid(10),
                (AtsType.ASHBY to "Ashby") to BoardCheck.Valid(76),
            ),
        )
    private lateinit var viewModel: WatchlistViewModel

    @Before
    fun start() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = WatchlistViewModel(repository, checker)
    }

    @After
    fun stop() = Dispatchers.resetMain()

    private fun state() = viewModel.uiState.value

    private fun add(
        name: String,
        ats: AtsType,
        board: String,
        check: Boolean = true,
    ) {
        viewModel.startAdding()
        viewModel.onNameChange(name)
        viewModel.onAtsChange(ats)
        viewModel.onBoardChange(board)
        if (check) viewModel.checkEditorBoard()
        viewModel.save()
    }

    /** Task T2.09 Done when: a user adds a Greenhouse, a Lever and an Ashby company and each check passes. */
    @Test
    fun aGreenhouseALeverAndAnAshbyCompanyAreAddedAndEachCheckPasses() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            add("Stripe", AtsType.GREENHOUSE, "stripe")
            add("Lever Demo", AtsType.LEVER, "leverdemo")
            add("Ashby", AtsType.ASHBY, "Ashby")

            val rows = state().companies
            assertEquals(listOf("Ashby", "Lever Demo", "Stripe"), rows.map { it.entry.name })
            assertEquals(setOf(AtsType.GREENHOUSE, AtsType.LEVER, AtsType.ASHBY), rows.map { it.entry.atsType }.toSet())
            assertTrue("each check passed", rows.all { it.check is CheckState.Found })
            assertNull("the form closed", state().editor)
        }

    @Test
    fun aFormWithMistakesIsNotSavedAndSaysWhy() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            viewModel.startAdding()
            viewModel.onBoardChange("not a board/../x")

            viewModel.save()

            val editor = state().editor!!
            assertTrue(editor.showErrors && editor.nameMissing && editor.boardInvalid)
            assertTrue(repository.rows.value.isEmpty())
            assertTrue("no request for a malformed board", checker.asked.isEmpty())
        }

    @Test
    fun theSameBoardTwiceIsRefused() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            add("Stripe", AtsType.GREENHOUSE, "stripe", check = false)

            add("Stripe again", AtsType.GREENHOUSE, "stripe", check = false)

            assertTrue(state().editor!!.duplicate)
            assertEquals(1, repository.rows.value.size)
        }

    @Test
    fun changingTheBoardForgetsTheEarlierCheckAndALateResultIsIgnored() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
            viewModel.startAdding()
            viewModel.onBoardChange("stripe")
            checker.gate = CompletableDeferred()
            viewModel.checkEditorBoard()
            assertEquals(CheckState.Checking, state().editor!!.check)

            viewModel.onBoardChange("stripe-eu")
            checker.gate!!.complete(Unit)

            assertEquals("the result was for another board", CheckState.NotChecked, state().editor!!.check)
        }

    @Test
    fun aRemovedCompanyComesBackWithUndo() =
        runTest {
            val stripe = CompanyWatchlistEntry(1, "Stripe", AtsType.GREENHOUSE, "stripe")
            repository.rows.value = listOf(stripe)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.remove(stripe)
            assertEquals(WatchlistMessage.Removed("Stripe"), state().message)
            assertTrue(repository.rows.value.isEmpty())

            viewModel.undoRemove()
            assertEquals(listOf("Stripe"), repository.rows.value.map { it.name })
        }

    @Test
    fun aRowCheckAndPausingUpdateTheList() =
        runTest {
            val acme = CompanyWatchlistEntry(1, "Acme", AtsType.LEVER, "acme")
            repository.rows.value = listOf(acme)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.check(acme)
            viewModel.setEnabled(acme, enabled = false)

            val row =
                viewModel.uiState
                    .first {
                        it.companies
                            .firstOrNull()
                            ?.entry
                            ?.enabled == false
                    }.companies
                    .single()
            assertEquals(CheckState.NotFound, row.check)
        }
}
