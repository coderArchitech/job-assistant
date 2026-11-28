package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.core.model.ApplyMode

/**
 * What every portal does, so the pipeline treats them alike (PRD, portal adapter contract; task T3.11). Each
 * call drives the portal's page in the visible WebView runtime and returns a typed result; nothing is thrown for
 * an expected outcome. Applying runs only from the Agent at work screen (CLAUDE.md rule 4).
 */
interface PortalAdapter {
    /** The portal this adapter drives, such as `naukri`. */
    val portal: String

    /** Signs in, reusing the session when the portal still shows it as signed in. */
    suspend fun login(): LoginResult

    /** The job listings the portal shows for [query]. */
    suspend fun search(query: String): SearchResult

    /** The full posting at [url], one of the portal's own pages. */
    suspend fun getDetails(url: String): DetailsResult

    /** Fills the application for [request] and, in auto mode only, submits it. */
    suspend fun apply(request: ApplyRequest): ApplyResult

    /** Checks that the portal's key selectors still match, before a run uses it. */
    suspend fun healthCheck(): HealthResult
}

/** Why a portal step could not finish. */
enum class StepFailure {
    /** A selector the adapter relies on matched nothing: the portal changed. */
    SELECTOR_MISSING,

    /** The page did not load, or loaded something else. */
    PAGE_NOT_LOADED,

    /** The page went away before answering; the step's outcome is unknown. */
    OUTCOME_UNKNOWN,

    /** A CAPTCHA or bot check is showing; the user must handle it (rule 6). */
    CAPTCHA,

    /** The portal rejected the user name or password. */
    BAD_CREDENTIALS,

    /** No credentials are stored for this portal. */
    NO_CREDENTIALS,

    /** The WebView cannot host the bridge. */
    UNSUPPORTED,
}

/** The outcome of [PortalAdapter.login]. */
sealed interface LoginResult {
    data object LoggedIn : LoginResult

    /** The portal asked for a one-time code; the caller reads it (task T3.18) and types it. */
    data object OtpNeeded : LoginResult

    data class Failed(
        val reason: StepFailure,
    ) : LoginResult
}

/** One listing on a portal's search page. */
data class PortalListing(
    val externalId: String,
    val title: String,
    val company: String?,
    val location: String?,
    val url: String,
)

/** The outcome of [PortalAdapter.search]. */
sealed interface SearchResult {
    data class Found(
        val listings: List<PortalListing>,
    ) : SearchResult

    data class Failed(
        val reason: StepFailure,
    ) : SearchResult
}

/** A posting as the portal shows it, before the normalizer. */
data class PortalPosting(
    val url: String,
    val title: String,
    val company: String?,
    val location: String?,
    val description: String,
)

/** The outcome of [PortalAdapter.getDetails]. */
sealed interface DetailsResult {
    data class Found(
        val posting: PortalPosting,
    ) : DetailsResult

    data class Failed(
        val reason: StepFailure,
    ) : DetailsResult
}

/**
 * What to apply with. [answers] are keyed by the manifest's field names (`name`, `email`, ...). [beforeSubmit]
 * runs just before an auto-mode submit click and must record `SUBMITTING` (rule 5); if it returns false the
 * application is left ready for the user instead.
 */
class ApplyRequest(
    val url: String,
    val answers: Map<String, String>,
    val mode: ApplyMode,
    val resume: Resume? = null,
    val beforeSubmit: suspend () -> Boolean = { false },
) {
    /** The resume file to upload. */
    class Resume(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    override fun toString() = "ApplyRequest(url=$url, answers=${answers.keys}, mode=$mode)"
}

/** The outcome of [PortalAdapter.apply]. */
sealed interface ApplyResult {
    /** Submitted, and the portal showed its confirmation. */
    data object Submitted : ApplyResult

    /** Filled and waiting for the user's tap on Submit (the default, reviewed mode). */
    data object ReadyForUser : ApplyResult

    /** The portal already lists this application. */
    data object AlreadyApplied : ApplyResult

    /** Required fields had no answer; the job pauses until the user answers [fields]. */
    data class NeedsInput(
        val fields: List<String>,
    ) : ApplyResult

    /**
     * The step failed. After a submit click, [StepFailure.OUTCOME_UNKNOWN] means it may have gone through: the
     * caller checks by hand and never retries it (rule 5).
     */
    data class Failed(
        val reason: StepFailure,
    ) : ApplyResult
}

/** The outcome of [PortalAdapter.healthCheck]. */
sealed interface HealthResult {
    data object Healthy : HealthResult

    /** These named selectors no longer match; the portal is skipped until the adapter is fixed. */
    data class Degraded(
        val missing: List<String>,
    ) : HealthResult

    /** The check page could not be loaded; this says nothing about the selectors. */
    data class Unreachable(
        val reason: StepFailure,
    ) : HealthResult
}
