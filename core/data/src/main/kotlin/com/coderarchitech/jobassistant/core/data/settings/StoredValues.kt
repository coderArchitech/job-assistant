package com.coderarchitech.jobassistant.core.data.settings

// Helpers for reading values back from settings.pb without throwing.

/** The stored value when it was written and is not negative, otherwise the field's default. */
internal fun <T> nonNegativeOr(
    present: Boolean,
    stored: T,
    default: T,
): T where T : Number, T : Comparable<T> = if (present && stored.toLong() >= 0) stored else default

internal inline fun <reified E : Enum<E>> enumByName(
    name: String,
    default: E,
): E = enumValues<E>().firstOrNull { it.name == name } ?: default

internal inline fun <T> validOrDefault(
    default: T,
    build: () -> T,
): T =
    try {
        build()
    } catch (_: IllegalArgumentException) {
        default
    }
