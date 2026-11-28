package com.coderarchitech.jobassistant.portals.web.bridge

/**
 * One step an adapter asks of the page (design, WebView runtime; task T3.07). Selectors are CSS selectors
 * evaluated in the portal's main frame. Values typed into the page never appear in [toString], so a command
 * can be logged.
 */
sealed interface BridgeCommand {
    /** The wire name the page script dispatches on. */
    val name: String

    /** True for steps that act on the page; the bridge paces these like a person would. */
    val isAction: Boolean get() = false

    /**
     * How many elements match [selector], whether any of them is visible, and the first one's text and
     * (non-secret) value.
     */
    data class Query(
        val selector: String,
    ) : BridgeCommand {
        override val name get() = "query"
    }

    /** Scrolls the first visible match of [selector] into view and clicks it. */
    data class Click(
        val selector: String,
    ) : BridgeCommand {
        override val name get() = "click"
        override val isAction get() = true
    }

    /**
     * Focuses [selector] and sets its value to [text], firing `input` and `change` as typing would. A [secret]
     * (a password or an OTP) marks the field, so its value never comes back in a query or a snapshot, even if
     * the page later shows it as plain text.
     */
    class Type(
        val selector: String,
        val text: String,
        val secret: Boolean = false,
    ) : BridgeCommand {
        override val name get() = "type"
        override val isAction get() = true

        override fun toString() = "Type(selector=$selector, text=<${text.length} chars>, secret=$secret)"
    }

    /** Chooses the option of the `<select>` at [selector] whose value or visible text is [option]. */
    data class Select(
        val selector: String,
        val option: String,
    ) : BridgeCommand {
        override val name get() = "select"
        override val isAction get() = true
    }

    /**
     * Puts a file into the `<input type=file>` at [selector], as choosing it in a picker would. The page gets
     * the bytes it would upload anyway; no picker opens.
     */
    class Upload(
        val selector: String,
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray,
    ) : BridgeCommand {
        override val name get() = "upload"
        override val isAction get() = true

        override fun toString() = "Upload(selector=$selector, fileName=$fileName, bytes=${bytes.size})"
    }

    /** Waits until [selector] matches a visible element, or fails with [BridgeError.TIMEOUT] after [timeoutMs]. */
    data class WaitFor(
        val selector: String,
        val timeoutMs: Long = DEFAULT_WAIT_MS,
    ) : BridgeCommand {
        init {
            require(timeoutMs in 1..MAX_WAIT_MS) { "A wait lasts 1 ms to $MAX_WAIT_MS ms" }
        }

        override val name get() = "waitFor"
    }

    /**
     * Reads named [fields] from the page (task T3.11). With [items], it reads them from each element [items]
     * matches, up to [MAX_ITEMS], and answers a list; without, it reads them once from the document and answers
     * one object. A field is the text of its element, or one of a few attributes (`href`, `src`, `datetime`,
     * `title`, `alt`, `aria-label`, `data-*`); links are absolute, without a fragment or parameters that may hold a
     * token. A field with no match, or an attribute outside that list, is null. Secrets are never read: a password
     * or one-time-code field reads as null.
     */
    data class Extract(
        val fields: Map<String, Field>,
        val items: String? = null,
    ) : BridgeCommand {
        init {
            require(fields.isNotEmpty()) { "Extract at least one field" }
        }

        override val name get() = "extract"
    }

    /** Where an [Extract] field comes from: [selector] within the item or document ("" is the item itself). */
    data class Field(
        val selector: String,
        val attribute: String? = null,
    )

    /**
     * The page's form fields, buttons and links with a selector for each, password values shown as
     * `{{password}}` (design, generic browser agent).
     */
    data object Snapshot : BridgeCommand {
        override val name get() = "snapshot"
    }

    /** Limits for [WaitFor]. */
    companion object {
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 60_000L

        /** The most items one [Extract] reads. */
        const val MAX_ITEMS = 200
    }
}

/** Why the page could not carry out a command. */
enum class BridgeError(
    /** The code the page script reports. */
    val code: String,
) {
    NOT_FOUND("not_found"),
    NOT_VISIBLE("not_visible"),
    NO_OPTION("no_option"),
    NOT_FILE_INPUT("not_file_input"),
    TIMEOUT("timeout"),
    BAD_COMMAND("bad_command"),

    /** A script error, or a code this app does not know. */
    PAGE_ERROR("page_error"),
    ;

    /** Codes reported by the page script. */
    companion object {
        /** The error for a page's [code]; unknown codes are [PAGE_ERROR]. */
        fun of(code: String?): BridgeError = entries.firstOrNull { it.code == code } ?: PAGE_ERROR
    }
}
