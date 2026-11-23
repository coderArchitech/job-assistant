package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.core.security.scan.ScanResult
import com.coderarchitech.jobassistant.core.security.scan.SecretScanner
import timber.log.Timber

/**
 * Stops a request that contains a secret before it leaves the phone (CLAUDE.md rule 1, PRD: passwords,
 * OTPs and cookies never reach a prompt). It scans the whole request, meaning the system prompt, the
 * context and the input, for every value in the vault (portal passwords and the AI keys) and for the
 * last OTP. A match, or a vault that cannot be read, returns [LlmResult.Blocked] and the wrapped
 * provider is never called. Wrap every provider with it, outermost, so no other layer can send first.
 *
 * Only prompts are scanned. [validateKey] and [listModels] send the key itself as the credential and no
 * prompt text.
 */
class GuardedLlmProvider(
    private val delegate: LlmProvider,
    private val scanner: SecretScanner,
) : LlmProvider by delegate {
    override suspend fun <T> generate(request: LlmRequest<T>): LlmResult<T> =
        when (val scan = scanner.scan(request.allText())) {
            ScanResult.Clean -> delegate.generate(request)
            is ScanResult.SecretFound -> {
                // A security event: names the task and whose secret, never the value.
                Timber.w(
                    "Blocked an AI request for task %s: it contained a stored secret (%s)",
                    request.task,
                    scan.source,
                )
                LlmResult.Blocked(BlockReason.SECRET_IN_PROMPT)
            }
            ScanResult.Unverifiable -> {
                Timber.w("Blocked an AI request for task %s: the vault could not be checked for secrets", request.task)
                LlmResult.Blocked(BlockReason.SECRET_CHECK_UNAVAILABLE)
            }
        }
}

/** Every piece of prompt text in the request. */
private fun LlmRequest<*>.allText(): List<String> =
    buildList {
        add(system)
        (context + input).forEach { part ->
            when (part) {
                is Part.Text -> add(part.text)
            }
        }
    }
