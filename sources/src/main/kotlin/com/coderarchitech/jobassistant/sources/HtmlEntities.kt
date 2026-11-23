package com.coderarchitech.jobassistant.sources

private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|lt|gt|quot|apos|nbsp|amp);")

private const val HEX_RADIX = 16
private const val FIRST_SURROGATE = 0xD800
private const val LAST_SURROGATE = 0xDFFF

/**
 * Undoes one level of HTML entity escaping, as Greenhouse applies to a posting's `content`
 * (`&lt;p&gt;` becomes `<p>`). Every entity is decoded in a single pass, so `&amp;lt;` becomes `&lt;`
 * and `&#38;lt;` becomes `&lt;`, never `<`. Numeric entities that are not real characters (zero, lone
 * surrogates, beyond U+10FFFF) are left as written.
 */
internal fun String.unescapeHtmlEntities(): String {
    if ('&' !in this) return this
    return ENTITY.replace(this) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#") -> numeric(name.drop(1)) ?: match.value
            else -> NAMED.getValue(name)
        }
    }
}

private val NAMED =
    mapOf("lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "amp" to "&")

private fun numeric(digits: String): String? {
    val hex = digits.startsWith("x") || digits.startsWith("X")
    val code = if (hex) digits.drop(1).toIntOrNull(HEX_RADIX) else digits.toIntOrNull()
    return code
        ?.takeIf { it != 0 && Character.isValidCodePoint(it) && it !in FIRST_SURROGATE..LAST_SURROGATE }
        ?.let { String(Character.toChars(it)) }
}
