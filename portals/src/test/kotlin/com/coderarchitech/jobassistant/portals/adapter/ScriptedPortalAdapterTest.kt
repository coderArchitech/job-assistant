package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeCommand
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeError
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The adapter's decisions against a fake page; the real pages run in the device test (T3.12). */
class ScriptedPortalAdapterTest {
    private val manifest =
        checkNotNull(
            AdapterManifest.from(
                Json.parseToJsonElement(File("src/androidTest/assets/adapter/sample.json").readText()).jsonObject,
            ),
        )

    /**
     * A page as sets of CSS selectors: [present] match an element, [shown] are also visible. [onClick] changes the
     * page; [extracted] answers Extract; every command is recorded.
     */
    private class FakePage : PageDriver {
        val present = mutableSetOf<String>()
        val shown = mutableSetOf<String>()
        val onClick = mutableMapOf<String, () -> BridgeResult>()
        var extracted: JsonElement = JsonArray(emptyList())
        val sent = mutableListOf<BridgeCommand>()
        val opened = mutableListOf<String>()
        var loads = true

        /** The next WaitFor finds its page gone, as when a click starts a form post. */
        var navigating = false
        override var generation = 1

        fun show(vararg css: String) {
            present += css
            shown += css
        }

        override suspend fun open(url: String): Boolean {
            opened += url
            if (loads) generation++
            return loads
        }

        override suspend fun awaitNewPage(after: Int) = generation > after

        override suspend fun send(command: BridgeCommand): BridgeResult {
            sent += command

            fun parts(css: String) = css.split(", ")
            return when (command) {
                is BridgeCommand.Query ->
                    BridgeResult.Done(
                        buildJsonObject {
                            put("count", parts(command.selector).count { it in present })
                            put("visible", parts(command.selector).any { it in shown })
                        },
                    )
                is BridgeCommand.WaitFor ->
                    when {
                        navigating -> {
                            navigating = false
                            generation++
                            BridgeResult.PageChanged
                        }
                        parts(command.selector).any { it in shown } -> done()
                        else -> BridgeResult.Failed(BridgeError.TIMEOUT, "")
                    }
                is BridgeCommand.Click ->
                    if (command.selector in shown) onClick[command.selector]?.invoke() ?: done() else missing()
                is BridgeCommand.Type, is BridgeCommand.Select, is BridgeCommand.Upload ->
                    if (selectorOf(command) in present) done() else missing()
                is BridgeCommand.Extract -> BridgeResult.Done(extracted)
                BridgeCommand.Snapshot -> done()
            }
        }

        private fun selectorOf(command: BridgeCommand) =
            when (command) {
                is BridgeCommand.Type -> command.selector
                is BridgeCommand.Select -> command.selector
                is BridgeCommand.Upload -> command.selector
                else -> ""
            }

        private fun done() = BridgeResult.Done(JsonPrimitive(true))

        private fun missing() = BridgeResult.Failed(BridgeError.NOT_FOUND, "")
    }

    private val page = FakePage()
    private var credentialReads = 0
    private val adapter =
        ScriptedPortalAdapter(
            "sample",
            manifest,
            page,
            { credentialReads++.let { PortalCredentials("asha", "fake-pass") } },
        )

    private fun signInForm() {
        page.show("#user", "#pass", "#signin")
    }

    @Test
    fun aLiveSessionIsReusedWithoutTouchingCredentials() =
        runTest {
            page.show("#account")

            assertEquals(LoginResult.LoggedIn, adapter.login())
            assertEquals(0, credentialReads)
            assertTrue(page.sent.none { it is BridgeCommand.Type })
        }

    @Test
    fun signingInTypesThePasswordAsASecret() =
        runTest {
            signInForm()
            page.onClick["#signin"] = {
                page.show("#account")
                BridgeResult.Done(JsonPrimitive(true))
            }

            assertEquals(LoginResult.LoggedIn, adapter.login())

            val typed = page.sent.filterIsInstance<BridgeCommand.Type>()
            assertEquals(listOf("#user" to false, "#pass" to true), typed.map { it.selector to it.secret })
            assertEquals(1, credentialReads)
        }

    @Test
    fun signInOutcomesAreTold() =
        runTest {
            val cases =
                mapOf(
                    "#otp" to LoginResult.OtpNeeded,
                    ".login-error" to LoginResult.Failed(StepFailure.BAD_CREDENTIALS),
                    "iframe[src*='hcaptcha.com'][src*='checkbox']" to LoginResult.Failed(StepFailure.CAPTCHA),
                )
            for ((shows, expected) in cases) {
                val fresh = FakePage().apply { show("#user", "#pass", "#signin") }
                fresh.onClick["#signin"] = {
                    fresh.show(shows)
                    BridgeResult.Done(JsonPrimitive(true))
                }
                val result = ScriptedPortalAdapter("sample", manifest, fresh, { PortalCredentials("a", "b") }).login()
                assertEquals(shows, expected, result)
            }
        }

    @Test
    fun withoutCredentialsOrAPageNothingIsTyped() =
        runTest {
            signInForm()
            val noCredentials = ScriptedPortalAdapter("sample", manifest, page, { null })
            assertEquals(LoginResult.Failed(StepFailure.NO_CREDENTIALS), noCredentials.login())

            page.loads = false
            assertEquals(LoginResult.Failed(StepFailure.PAGE_NOT_LOADED), adapter.login())
            assertTrue(page.sent.none { it is BridgeCommand.Type })
        }

    @Test
    fun searchKeepsOnlyListingsOnThePortal() =
        runTest {
            page.extracted =
                Json.parseToJsonElement(
                    """[
                    {"id":"101","title":"Android Engineer","company":"Acme","location":"Bengaluru",
                     "url":"https://portal.example/assets/adapter/job.html?id=101"},
                    {"id":"101","title":"Android Engineer","url":"https://portal.example/assets/adapter/job.html?id=101"},
                    {"id":"103","title":"Sponsored","url":"https://ads.example/job"},
                    {"id":null,"title":"No id","url":"https://portal.example/x"}
                    ]""",
                )

            val result = adapter.search("android kotlin")

            assertEquals(
                SearchResult.Found(
                    listOf(
                        PortalListing(
                            "101",
                            "Android Engineer",
                            "Acme",
                            "Bengaluru",
                            "https://portal.example/assets/adapter/job.html?id=101",
                        ),
                    ),
                ),
                result,
            )
            assertEquals("https://portal.example/assets/adapter/search.html?q=android+kotlin", page.opened.single())
        }

    @Test
    fun detailsNeedATitleAndADescription() =
        runTest {
            val url = "https://portal.example/assets/adapter/job.html?id=101"
            page.extracted = Json.parseToJsonElement("""{"title":"Android Engineer","description":"Build it."}""")
            assertEquals(
                DetailsResult.Found(PortalPosting(url, "Android Engineer", null, null, "Build it.")),
                adapter.getDetails(url),
            )

            page.extracted = Json.parseToJsonElement("""{"title":"Android Engineer","description":null}""")
            assertEquals(DetailsResult.Failed(StepFailure.SELECTOR_MISSING), adapter.getDetails(url))

            assertEquals(
                DetailsResult.Failed(StepFailure.PAGE_NOT_LOADED),
                adapter.getDetails("https://ads.example/job"),
            )
        }

    private fun applyForm() {
        page.show("#apply", "#name", "#email", "#notice", "#cv", "#send")
    }

    private fun request(
        mode: ApplyMode = ApplyMode.REVIEWED,
        answers: Map<String, String> = mapOf("name" to "Asha Rao", "email" to "asha@example.com", "notice" to "30"),
        beforeSubmit: suspend () -> Boolean = { true },
    ) = ApplyRequest(
        "https://portal.example/assets/adapter/job.html?id=101",
        answers,
        mode,
        beforeSubmit = beforeSubmit,
    )

    @Test
    fun reviewedModeFillsTheFormAndNeverSubmits() =
        runTest {
            applyForm()

            assertEquals(ApplyResult.ReadyForUser, adapter.apply(request()))

            assertTrue(page.sent.none { it is BridgeCommand.Click && it.selector == "#send" })
            assertTrue(page.sent.any { it is BridgeCommand.Select && it.option == "30" })
        }

    @Test
    fun autoModeSubmitsOnlyAfterSubmittingIsRecorded() =
        runTest {
            applyForm()
            var recorded = false
            page.onClick["#send"] = {
                assertTrue("SUBMITTING is written before the click", recorded)
                page.show("#thanks")
                BridgeResult.Done(JsonPrimitive(true))
            }

            val result =
                adapter.apply(
                    request(mode = ApplyMode.AUTO) {
                        recorded = true
                        true
                    },
                )

            assertEquals(ApplyResult.Submitted, result)
        }

    @Test
    fun autoModeLeavesItForTheUserWhenSubmittingCannotBeRecorded() =
        runTest {
            applyForm()

            assertEquals(ApplyResult.ReadyForUser, adapter.apply(request(mode = ApplyMode.AUTO) { false }))
            assertTrue(page.sent.none { it is BridgeCommand.Click && it.selector == "#send" })
        }

    @Test
    fun aSubmitThatIsNeverConfirmedIsAnUnknownOutcome() =
        runTest {
            applyForm()
            page.onClick["#send"] = { BridgeResult.PageChanged }

            assertEquals(ApplyResult.Failed(StepFailure.OUTCOME_UNKNOWN), adapter.apply(request(mode = ApplyMode.AUTO)))
        }

    @Test
    fun aConfirmationOnThePageTheFormLandsOnCounts() =
        runTest {
            applyForm()
            page.onClick["#send"] = {
                page.navigating = true
                page.show("#thanks")
                BridgeResult.Done(JsonPrimitive(true))
            }

            assertEquals(ApplyResult.Submitted, adapter.apply(request(mode = ApplyMode.AUTO)))
        }

    @Test
    fun aClickThatMayHaveRunIsNeverTakenAsUnsent() =
        runTest {
            applyForm()
            page.onClick["#send"] = { BridgeResult.Failed(BridgeError.PAGE_ERROR, "click threw after dispatch") }

            assertEquals(ApplyResult.Failed(StepFailure.OUTCOME_UNKNOWN), adapter.apply(request(mode = ApplyMode.AUTO)))
        }

    @Test
    fun aChallengeMidWayIsHandedToTheUser() =
        runTest {
            // The sign-in click brings up a challenge instead of any outcome.
            signInForm()
            page.onClick["#signin"] = {
                page.show("iframe[src*='challenges.cloudflare.com']")
                BridgeResult.Done(JsonPrimitive(true))
            }
            assertEquals(LoginResult.Failed(StepFailure.CAPTCHA), adapter.login())

            // A form field is hidden under a challenge that the Apply click brought up.
            val form = FakePage().apply { show("#apply", "#name", "#send") }
            form.onClick["#apply"] = {
                form.show("iframe[src*='arkoselabs']")
                BridgeResult.Done(JsonPrimitive(true))
            }
            val applying = ScriptedPortalAdapter("sample", manifest, form, { null })
            assertEquals(ApplyResult.Failed(StepFailure.CAPTCHA), applying.apply(request()))
        }

    @Test
    fun missingAnswersAndPastApplicationsStopBeforeAnythingIsTyped() =
        runTest {
            applyForm()
            assertEquals(
                ApplyResult.NeedsInput(listOf("email")),
                adapter.apply(request(answers = mapOf("name" to "Asha"))),
            )

            page.show(".applied-badge")
            assertEquals(ApplyResult.AlreadyApplied, adapter.apply(request()))
            assertTrue(page.sent.none { it is BridgeCommand.Type })
        }

    @Test
    fun aMissingRequiredFieldIsASelectorProblem() =
        runTest {
            page.show("#apply", "#name", "#send")

            assertEquals(ApplyResult.Failed(StepFailure.SELECTOR_MISSING), adapter.apply(request()))
        }

    @Test
    fun theHealthCheckNamesWhatNoLongerMatches() =
        runTest {
            page.show("#account", "#search")
            assertEquals(HealthResult.Healthy, adapter.healthCheck())

            page.present -= "#search"
            assertEquals(HealthResult.Degraded(listOf("searchBox")), adapter.healthCheck())

            page.loads = false
            assertEquals(HealthResult.Unreachable(StepFailure.PAGE_NOT_LOADED), adapter.healthCheck())
            assertFalse(page.sent.any { it is BridgeCommand.Click })
        }
}
