package com.coderarchitech.jobassistant.core.security.log

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips secrets from text before it is logged (CLAUDE.md rule 2). Two layers:
 * 1. Patterns: AI API keys, `Authorization`, `x-api-key`, cookie headers, Bearer and Google OAuth
 *    tokens, and secret-named query parameters and JSON fields.
 * 2. Exact values from [SecretRegistry]: whatever the vault holds, such as portal passwords, which
 *    no pattern could recognise.
 *
 * Redaction is a backstop, not permission: code still never logs secrets or request bodies.
 */
@Singleton
class Redactor
    @Inject
    constructor(
        private val registry: SecretRegistry,
    ) {
        /** [text] with every recognised secret replaced by [REDACTED]. */
        fun redact(text: String): String {
            var out = registry.redact(text, REDACTED)
            for ((pattern, replacement) in PATTERNS) {
                out = pattern.replace(out, replacement)
            }
            return out
        }

        /** Shared values. */
        companion object {
            /** What a secret is replaced with. */
            const val REDACTED = "[REDACTED]"

            /**
             * A field or parameter name that holds a secret: any name containing one of these words,
             * so `accessToken`, `client_secret`, `session_token` and `otp_code` all count. Plain
             * `key` or `code` alone do not, because they name ordinary values (cache keys, status
             * codes) far more often.
             */
            private const val SECRET_NAME =
                "[A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|otp|passcode" +
                    "|verification[_-]?code|auth[_-]?code)[A-Za-z0-9_.-]*"

            /** A JSON value: a string with escapes, or anything unquoted up to the next delimiter. */
            private const val JSON_VALUE = "(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s]+)"

            /** Rules applied in order; each keeps the name of what it hides. */
            private val PATTERNS: List<Pair<Regex, String>> =
                listOf(
                    // Header lines, as OkHttp and most HTTP logs print them.
                    Regex(
                        "(?im)\\b(authorization|proxy-authorization|x-api-key|api-key|x-goog-api-key" +
                            "|cookie|set-cookie)(\\s*[:=]\\s*)[^\\r\\n]+",
                    ) to "$1$2$REDACTED",
                    // AI provider keys anywhere: sk-ant-..., sk-proj-..., sk-....
                    Regex("\\bsk-[A-Za-z0-9_-]{8,}") to REDACTED,
                    // Bearer tokens outside a header line; a real token is never a short word.
                    Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}") to "Bearer $REDACTED",
                    // Google OAuth access tokens.
                    Regex("\\bya29\\.[A-Za-z0-9._-]+") to REDACTED,
                    // JSON fields with secret names, quoted or not: "password":"...", "otp": 482139.
                    Regex("(?i)(\"$SECRET_NAME\"\\s*:\\s*)$JSON_VALUE") to "$1\"$REDACTED\"",
                    // Query or form parameters with secret names, also at the start of the text.
                    Regex("(?i)((?:^|[?&;\\s])$SECRET_NAME=)[^&\\s]+") to "$1$REDACTED",
                )
        }
    }
