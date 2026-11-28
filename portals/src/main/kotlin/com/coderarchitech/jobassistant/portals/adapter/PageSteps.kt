package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.portals.web.bridge.BridgeCommand
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeError
import com.coderarchitech.jobassistant.portals.web.bridge.BridgeResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** The manifest's steps and checks, as bridge commands on a [PageDriver]; used by [ScriptedPortalAdapter]. */
internal class PageSteps(
    private val manifest: AdapterManifest,
    private val driver: PageDriver,
    private val outcomeWaitMs: Long,
) {
    /**
     * Runs [steps]; the failure that stopped them, or null when all ran. Values named in [secrets] are typed as
     * secrets.
     */
    suspend fun run(
        steps: List<AdapterManifest.Step>,
        values: Map<String, String>,
        secrets: Set<String>,
    ): StepFailure? {
        var failure: StepFailure? = null
        for (step in steps) {
            failure = runStep(step, step.value?.let { values[it] }, step.value in secrets)
            if (failure != null) break
        }
        return failure
    }

    private suspend fun runStep(
        step: AdapterManifest.Step,
        value: String?,
        secret: Boolean,
    ): StepFailure? {
        val css = manifest.css(step.selector)
        val command =
            when (step.action) {
                "click" -> BridgeCommand.Click(css)
                "type" -> BridgeCommand.Type(css, value.orEmpty(), secret)
                "select" -> BridgeCommand.Select(css, value.orEmpty())
                else -> BridgeCommand.WaitFor(css, outcomeWaitMs)
            }
        val before = driver.generation
        val result = driver.send(command)
        // A click that navigates may answer, or lose its page first; either is fine if a new page arrives.
        val failure = if (step.navigates && result !is BridgeResult.Failed) null else failureOf(result, step.optional)
        return when {
            failure != null -> failure
            step.navigates && !driver.awaitNewPage(before) -> StepFailure.PAGE_NOT_LOADED
            else -> null
        }
    }

    /** Sends [command]; null when it worked (or found nothing and [optional]), else why it failed. */
    suspend fun perform(
        command: BridgeCommand,
        optional: Boolean = false,
    ): StepFailure? = failureOf(driver.send(command), optional)

    /** What [extraction] reads from the current page, or null when the page could not answer. */
    suspend fun extract(extraction: AdapterManifest.Extraction): JsonElement? {
        val fields = extraction.fields.mapValues { (_, f) -> BridgeCommand.Field(f.selector, f.attribute) }
        return (driver.send(BridgeCommand.Extract(fields, extraction.items)) as? BridgeResult.Done)?.value
    }

    /** True when the named selector shows a visible element now. */
    suspend fun isShowing(name: String): Boolean = query(manifest.css(name))?.flag("visible") == true

    /**
     * True when the named selector shows within the outcome wait, on this page or, if the page goes away, on the
     * one that replaces it (a form post that lands on a confirmation page).
     */
    suspend fun appearsAfter(
        name: String,
        generationBefore: Int,
    ): Boolean {
        val command = BridgeCommand.WaitFor(manifest.css(name), outcomeWaitMs)
        return when (driver.send(command)) {
            is BridgeResult.Done -> true
            is BridgeResult.Failed -> false
            else -> driver.awaitNewPage(generationBefore) && driver.send(command) is BridgeResult.Done
        }
    }

    /** Which of the named selectors shows first, within the outcome wait; null if none does. */
    suspend fun firstShowing(names: List<String>): String? {
        val any = names.joinToString(", ") { manifest.css(it) }
        val appeared = driver.send(BridgeCommand.WaitFor(any, outcomeWaitMs)) is BridgeResult.Done
        return if (appeared) names.firstOrNull { isShowing(it) } else null
    }

    /** True when a CAPTCHA or bot check shows: one the manifest names, or a known challenge frame. */
    suspend fun captchaShowing(): Boolean =
        query((manifest.captcha.map(manifest::css) + KNOWN_CHALLENGES).joinToString(", "))?.flag("visible") == true

    /** How many elements the named selector matches, or null when the page could not answer. */
    suspend fun count(name: String): Int? = query(manifest.css(name))?.number("count")

    private suspend fun query(css: String): JsonObject? =
        (driver.send(BridgeCommand.Query(css)) as? BridgeResult.Done)?.value as? JsonObject

    private companion object {
        /**
         * Challenge frames a portal does not list itself (task T3.16 widens this): reCAPTCHA's checkbox and
         * challenge frames, but not the invisible v3 badge many pages load, and hCaptcha, Cloudflare and Arkose.
         */
        const val KNOWN_CHALLENGES =
            "iframe[src*='recaptcha'][src*='bframe'], iframe[src*='recaptcha'][src*='anchor'][src*='size=normal'], " +
                "iframe[src*='hcaptcha.com'][src*='checkbox'], iframe[src*='hcaptcha.com'][src*='challenge'], " +
                "iframe[src*='challenges.cloudflare.com'], iframe[src*='arkoselabs']"
    }
}

private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

private val MISSING_ELEMENT = setOf(BridgeError.NOT_FOUND, BridgeError.NOT_VISIBLE)

/** Null when [result] is fine (or found nothing and [optional]), else why the step failed. */
private fun failureOf(
    result: BridgeResult,
    optional: Boolean,
): StepFailure? =
    when (result) {
        is BridgeResult.Done -> null
        is BridgeResult.Failed ->
            when {
                optional && result.error in MISSING_ELEMENT -> null
                result.error in MISSING_ELEMENT ||
                    result.error == BridgeError.NO_OPTION ||
                    result.error == BridgeError.TIMEOUT -> StepFailure.SELECTOR_MISSING
                else -> StepFailure.OUTCOME_UNKNOWN
            }
        BridgeResult.NoPage -> StepFailure.PAGE_NOT_LOADED
        BridgeResult.PageChanged, BridgeResult.NoReply -> StepFailure.OUTCOME_UNKNOWN
    }
