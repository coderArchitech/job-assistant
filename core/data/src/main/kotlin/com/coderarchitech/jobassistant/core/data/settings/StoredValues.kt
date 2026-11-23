package com.coderarchitech.jobassistant.core.data.settings

import timber.log.Timber

// Helpers for reading values back from settings.pb without throwing. A value that was stored but
// is invalid falls back to its default and is logged by field name only, never by value (T1.09).

/** The stored value when it was written and is not negative, otherwise the field's default. */
internal fun <T> nonNegativeOr(
    field: String,
    present: Boolean,
    stored: T,
    default: T,
): T where T : Number, T : Comparable<T> =
    when {
        !present -> default
        stored.toLong() >= 0 -> stored
        else -> default.also { logFallback(field) }
    }

/** The enum constant named [name], or [default] when the name is empty (unset) or unknown. */
internal inline fun <reified E : Enum<E>> enumByName(
    field: String,
    name: String,
    default: E,
): E =
    enumValues<E>().firstOrNull { it.name == name }
        ?: default.also { if (name.isNotEmpty()) logFallback(field) }

/** [build]'s value, or [default] when the stored values break the type's own rules. */
internal inline fun <T> validOrDefault(
    field: String,
    default: T,
    build: () -> T,
): T =
    try {
        build()
    } catch (_: IllegalArgumentException) {
        default.also { logFallback(field) }
    }

/** Logs that a stored setting was invalid and its default is used; never logs the value. */
internal fun logFallback(field: String) {
    Timber.w("settings.pb: stored %s is invalid; using the default", field)
}
