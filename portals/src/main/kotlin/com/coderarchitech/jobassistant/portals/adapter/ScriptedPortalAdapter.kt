package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.core.model.ApplyMode
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeCommand
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeError
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * A [PortalAdapter] that runs a signed [AdapterManifest] over the bridge (design, Portal adapters; task T3.11).
 *
 * Every step is a typed bridge command on one of the manifest's selectors. A page outside the portal's origins
 * is never opened. Sign-in checks for an existing session first, so a signed-in portal never touches the
 * credentials. Applying stops before Submit unless the request is in auto mode and its `beforeSubmit` records
 * `SUBMITTING` first (rule 5); a submit whose result the page never confirms is reported as an unknown outcome,
 * never retried. A CAPTCHA or bot check ends the step for the user to handle (rule 6).
 */
class ScriptedPortalAdapter(
    override val portal: String,
    private val manifest: AdapterManifest,
    private val driver: PageDriver,
    private val credentials: CredentialSource,
    outcomeWaitMs: Long = OUTCOME_WAIT_MS,
) : PortalAdapter {
    private val page = PageSteps(manifest, driver, outcomeWaitMs)

    override suspend fun login(): LoginResult =
        when {
            !driver.open(manifest.urls.login) -> LoginResult.Failed(StepFailure.PAGE_NOT_LOADED)
            page.captchaShowing() -> LoginResult.Failed(StepFailure.CAPTCHA)
            page.isShowing(manifest.login.loggedIn) -> LoginResult.LoggedIn
            else -> credentials.credentials()?.let { signIn(it) } ?: LoginResult.Failed(StepFailure.NO_CREDENTIALS)
        }

    private suspend fun signIn(creds: PortalCredentials): LoginResult {
        val values = mapOf("username" to creds.username, "password" to creds.password)
        val failure = page.run(manifest.login.steps, values, secrets = setOf("password"))
        val login = manifest.login
        val shown =
            if (failure ==
                null
            ) {
                page.firstShowing(listOfNotNull(login.loggedIn, login.otp, login.badCredentials))
            } else {
                null
            }
        return when {
            // A challenge that appeared mid-way is the user's to solve, whatever else went wrong (rule 6).
            page.captchaShowing() -> LoginResult.Failed(StepFailure.CAPTCHA)
            failure != null -> LoginResult.Failed(failure)
            shown == login.loggedIn -> LoginResult.LoggedIn
            shown != null && shown == login.otp -> LoginResult.OtpNeeded
            shown != null && shown == login.badCredentials -> LoginResult.Failed(StepFailure.BAD_CREDENTIALS)
            else -> LoginResult.Failed(StepFailure.OUTCOME_UNKNOWN)
        }
    }

    override suspend fun search(query: String): SearchResult {
        val url = manifest.urls.search.replace(AdapterManifest.QUERY, URLEncoder.encode(query.trim(), "UTF-8"))
        val opened = driver.open(url)
        val captcha = opened && page.captchaShowing()
        val rows = if (opened && !captcha) page.extract(manifest.search) as? JsonArray else null
        return when {
            !opened -> SearchResult.Failed(StepFailure.PAGE_NOT_LOADED)
            captcha -> SearchResult.Failed(StepFailure.CAPTCHA)
            rows == null -> SearchResult.Failed(StepFailure.SELECTOR_MISSING)
            else -> SearchResult.Found(rows.mapNotNull(manifest::listingOf).distinctBy { it.externalId })
        }
    }

    override suspend fun getDetails(url: String): DetailsResult {
        val opened = manifest.isOwnPage(url) && driver.open(url)
        val captcha = opened && page.captchaShowing()
        val fields = if (opened && !captcha) page.extract(manifest.details) as? JsonObject else null
        val title = fields?.text("title")
        val description = fields?.text("description")
        return when {
            !opened -> DetailsResult.Failed(StepFailure.PAGE_NOT_LOADED)
            captcha -> DetailsResult.Failed(StepFailure.CAPTCHA)
            fields == null || title == null || description == null -> DetailsResult.Failed(StepFailure.SELECTOR_MISSING)
            else ->
                DetailsResult.Found(
                    PortalPosting(url, title, fields.text("company"), fields.text("location"), description),
                )
        }
    }

    override suspend fun apply(request: ApplyRequest): ApplyResult {
        val form = manifest.apply
        val missing =
            form.fields.filter { (name, field) -> field.required && request.answers[name].isNullOrBlank() }.keys +
                listOfNotNull("resume".takeIf { form.resumeRequired && request.resume == null })
        return when {
            !manifest.isOwnPage(
                request.url,
            ) ||
                !driver.open(request.url) -> ApplyResult.Failed(StepFailure.PAGE_NOT_LOADED)
            page.captchaShowing() -> ApplyResult.Failed(StepFailure.CAPTCHA)
            form.alreadyApplied?.let { page.isShowing(it) } == true -> ApplyResult.AlreadyApplied
            missing.isNotEmpty() -> ApplyResult.NeedsInput(missing.sorted())
            else -> fill(request)?.let { failedOrCaptcha(it) } ?: finish(request)
        }
    }

    /** Opens the form and fills every answered field and the resume; the failure that stopped it, or null. */
    private suspend fun fill(request: ApplyRequest): StepFailure? {
        val form = manifest.apply
        var failure = page.run(form.open, emptyMap(), emptySet())
        val answered =
            form.fields.mapNotNull { (name, field) ->
                request.answers[name]?.takeIf { it.isNotBlank() }?.let { field to it }
            }
        for ((field, answer) in answered) {
            if (failure != null) break
            val css = manifest.css(field.selector)
            val command =
                if (field.kind ==
                    SELECT
                ) {
                    BridgeCommand.Select(css, answer)
                } else {
                    BridgeCommand.Type(css, answer)
                }
            failure = page.perform(command, optional = !field.required)
        }
        val resume = request.resume
        if (failure == null && form.resume != null && resume != null) {
            val upload = BridgeCommand.Upload(manifest.css(form.resume), resume.fileName, resume.mimeType, resume.bytes)
            failure = page.perform(upload, optional = !form.resumeRequired)
        }
        return failure
    }

    /** A failure mid-form, unless a challenge appeared, which is the user's to solve (rule 6). */
    private suspend fun failedOrCaptcha(failure: StepFailure): ApplyResult =
        ApplyResult.Failed(if (page.captchaShowing()) StepFailure.CAPTCHA else failure)

    private suspend fun finish(request: ApplyRequest): ApplyResult =
        when {
            page.captchaShowing() -> ApplyResult.Failed(StepFailure.CAPTCHA)
            request.mode != ApplyMode.AUTO || !request.beforeSubmit() -> ApplyResult.ReadyForUser
            else -> submit()
        }

    /**
     * Clicks Submit, already recorded as `SUBMITTING` by the caller, and waits for the confirmation, on this page or
     * the one the form lands on. Only a Submit the page reports it could not find was certainly never clicked;
     * anything else that is not a confirmation is an unknown outcome, never retried (rule 5).
     */
    private suspend fun submit(): ApplyResult {
        val before = driver.generation
        val click = driver.send(BridgeCommand.Click(manifest.css(manifest.apply.submit)))
        val neverClicked = click is BridgeResult.Failed && click.error in NOT_CLICKED
        val confirmed = !neverClicked && page.appearsAfter(manifest.apply.confirmation, before)
        return when {
            neverClicked -> ApplyResult.Failed(StepFailure.SELECTOR_MISSING)
            confirmed -> ApplyResult.Submitted
            // After a click, a challenge is still the user's; the application may already be in, so no retry.
            page.captchaShowing() -> ApplyResult.Failed(StepFailure.CAPTCHA)
            else -> ApplyResult.Failed(StepFailure.OUTCOME_UNKNOWN)
        }
    }

    override suspend fun healthCheck(): HealthResult {
        val opened = driver.open(manifest.urls.health)
        val missing = if (opened) manifest.health.selectors.filter { (page.count(it) ?: 0) == 0 } else emptyList()
        return when {
            !opened -> HealthResult.Unreachable(StepFailure.PAGE_NOT_LOADED)
            missing.isEmpty() -> HealthResult.Healthy
            else -> HealthResult.Degraded(missing)
        }
    }

    private companion object {
        /** How long to wait for the page to show the outcome of a step. */
        const val OUTCOME_WAIT_MS = 15_000L
        const val SELECT = "select"

        /** Bridge errors that mean the click never ran. */
        val NOT_CLICKED = setOf(BridgeError.NOT_FOUND, BridgeError.NOT_VISIBLE, BridgeError.BAD_COMMAND)
    }
}

/** A listing from one extracted row; rows without an id, a title or a link on the portal are skipped. */
private fun AdapterManifest.listingOf(row: JsonElement): PortalListing? {
    val fields = row as? JsonObject
    val id = fields?.text("id")
    val title = fields?.text("title")
    val link = fields?.text("url")?.takeIf(::isOwnPage)
    return if (id != null && title != null && link != null) {
        PortalListing(id, title, fields.text("company"), fields.text("location"), link)
    } else {
        null
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
