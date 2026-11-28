package com.coderarchitech.jobassistant.portals.adapter

import com.coderarchitech.jobassistant.portals.web.PortalSession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * A portal adapter as the config host publishes it (design, Portal adapters; task T3.11): the portal's origins,
 * its pages, named CSS selectors, the login steps, what to read from search and job pages, the application form
 * and the health check. It is data, not code: every step is one of the bridge's typed commands, so a release can
 * change what is clicked and read but cannot run a script of its own.
 *
 * Selector names used anywhere must be defined in [selectors]; [validate] checks this and every other rule
 * before an adapter is used, so a release that breaks one is refused like a bad signature.
 */
@Serializable
data class AdapterManifest(
    /** The portal's own HTTPS origins, canonical; the bridge and every page this adapter opens stay on them. */
    val origins: List<String>,
    val urls: Urls,
    /** Named CSS selectors. */
    val selectors: Map<String, String>,
    val login: Login,
    val search: Extraction,
    val details: Extraction,
    val apply: Apply,
    val health: Health,
    /** Selector names that show a CAPTCHA or bot check; known challenge frames are always checked too. */
    val captcha: List<String> = emptyList(),
) {
    /** Where the adapter's pages are. [search] holds `{query}` where the search words go. */
    @Serializable
    data class Urls(
        val login: String,
        val search: String,
        val health: String,
    )

    /** One step: a bridge command on a named selector. */
    @Serializable
    data class Step(
        @SerialName("do") val action: String,
        val selector: String,
        /** For `type` and `select`: which value to put in (`username`, `password`, or an answer's name). */
        val value: String? = null,
        /** The step may find nothing (a cookie banner that is not always there); it is then skipped. */
        val optional: Boolean = false,
        /** The step loads a new page; the next step waits for it. */
        val navigates: Boolean = false,
    )

    /** How to sign in, and how to tell what happened. */
    @Serializable
    data class Login(
        val steps: List<Step>,
        /** Shown only when signed in. */
        val loggedIn: String,
        /** Shown when the portal asks for a one-time code. */
        val otp: String? = null,
        /** Shown when the portal rejects the user name or password. */
        val badCredentials: String? = null,
    )

    /** What to read: [fields] from each element [items] matches, or from the page once when [items] is null. */
    @Serializable
    data class Extraction(
        val items: String? = null,
        val fields: Map<String, Field>,
    )

    /** A raw CSS selector within the item ("" for the item itself), and an attribute or, by default, the text. */
    @Serializable
    data class Field(
        val selector: String,
        val attribute: String? = null,
    )

    /** One input of the application form. */
    @Serializable
    data class FormField(
        val selector: String,
        val required: Boolean = false,
        /** `type` for text inputs, `select` for drop-downs. */
        val kind: String = "type",
    )

    /** The application form. */
    @Serializable
    data class Apply(
        /** Steps that open the form from the job page, such as clicking "Apply". */
        val open: List<Step> = emptyList(),
        /** The form's inputs, keyed by answer name. */
        val fields: Map<String, FormField>,
        val resume: String? = null,
        val resumeRequired: Boolean = false,
        val submit: String,
        /** Shown once the portal has the application. */
        val confirmation: String,
        /** Shown when the portal already has an application for this job. */
        val alreadyApplied: String? = null,
    )

    /** What must match on the [Urls.health] page for the adapter to be trusted. */
    @Serializable
    data class Health(
        val selectors: List<String>,
    )

    /** The CSS for the selector named [name]; [validate] guarantees every name used is defined. */
    fun css(name: String): String = selectors.getValue(name)

    /** True when [url] is an HTTPS page on one of [origins]. */
    fun isOwnPage(url: String): Boolean {
        val origin = ORIGIN_OF.find(url)?.value?.lowercase(Locale.ROOT)
        return origin != null && origin in origins
    }

    /** Every rule this manifest breaks; empty when it can be used. */
    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (origins.isEmpty() || runCatching { PortalSession("check", origins) }.isFailure) {
            problems += "origins must be canonical https origins"
        }
        // The part before {query} must already be a page of the portal, so the words can never pick the host.
        listOf(urls.login, urls.health, urls.search.substringBefore(QUERY)).filterNot(::isOwnPage).forEach {
            problems += "url not on the portal's origins: $it"
        }
        if (QUERY !in urls.search) problems += "search url needs $QUERY"
        val usedNames =
            (login.steps + apply.open).map { it.selector } +
                listOfNotNull(login.loggedIn, login.otp, login.badCredentials, apply.resume, apply.submit) +
                listOfNotNull(apply.confirmation, apply.alreadyApplied) +
                apply.fields.values.map { it.selector } +
                health.selectors + captcha
        usedNames.filterNot(selectors::containsKey).distinct().forEach { problems += "undefined selector: $it" }
        problems += stepProblems()
        problems += extractionProblems()
        apply.fields.values
            .filterNot { it.kind in FORM_KINDS }
            .forEach { problems += "unknown field kind: ${it.kind}" }
        if (selectors.values.any { it.isBlank() }) problems += "blank selector"
        listOf("id", "title", "url").filterNot(search.fields::containsKey).forEach {
            problems +=
                "search needs field $it"
        }
        if (search.items.isNullOrBlank()) problems += "search needs items"
        listOf("title", "description").filterNot(details.fields::containsKey).forEach {
            problems +=
                "details needs field $it"
        }
        if (health.selectors.isEmpty()) problems += "health check needs selectors"
        return problems
    }

    /** Steps never click Submit (only `apply` does, after `SUBMITTING`), and only `type` carries a value. */
    private fun stepProblems(): List<String> {
        val problems = mutableListOf<String>()
        val steps = login.steps + apply.open
        steps.filterNot { it.action in ACTIONS }.forEach { problems += "unknown step: ${it.action}" }
        val submitCss = selectors[apply.submit]
        steps
            .filter { it.selector == apply.submit || (submitCss != null && selectors[it.selector] == submitCss) }
            .forEach { problems += "a step may not use the submit selector: ${it.selector}" }
        login.steps
            .filter { it.value != null && (it.action != "type" || it.value !in LOGIN_VALUES) }
            .forEach { problems += "a login value goes only in a type step, as username or password: ${it.selector}" }
        apply.open.filter { it.value != null }.forEach {
            problems +=
                "a step that opens the form has no value: ${it.selector}"
        }
        return problems
    }

    /** Extractions read only text or the attributes the bridge allows. */
    private fun extractionProblems(): List<String> =
        (search.fields.values + details.fields.values)
            .mapNotNull { it.attribute }
            .filterNot(READABLE_ATTRIBUTE::matches)
            .map { "attribute not readable: $it" }

    /**
     * True when every one of this manifest's origins belongs to [portal], so a release signed for one portal can
     * never point the adapter anywhere else.
     */
    fun isFor(portal: PortalSession): Boolean = origins.all { it in portal.origins }

    /** Reading manifests. */
    companion object {
        /** Where the search words go in [Urls.search]. */
        const val QUERY = "{query}"
        private val ACTIONS = setOf("click", "type", "select", "waitFor")
        private val LOGIN_VALUES = setOf("username", "password")
        private val READABLE_ATTRIBUTE = Regex("href|src|datetime|title|alt|aria-label|data-[a-z0-9-]+")
        private val FORM_KINDS = setOf("type", "select")
        private val ORIGIN_OF = Regex("^https://[^/?#@:]+(?=[/?#]|$)")
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        /** The manifest in a config document's [body], or null when it is not a valid one. */
        fun from(body: JsonObject): AdapterManifest? =
            try {
                JSON.decodeFromJsonElement(serializer(), body).takeIf { it.validate().isEmpty() }
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
