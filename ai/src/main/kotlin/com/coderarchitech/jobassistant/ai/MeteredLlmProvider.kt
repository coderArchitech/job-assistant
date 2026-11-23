package com.coderarchitech.jobassistant.ai

import com.coderarchitech.jobassistant.ai.FallbackPricing.priceOrFallback
import com.coderarchitech.jobassistant.core.model.AiProvider
import com.coderarchitech.jobassistant.core.model.AiTask
import com.coderarchitech.jobassistant.core.model.BudgetSource
import com.coderarchitech.jobassistant.core.model.UsageLedger
import com.coderarchitech.jobassistant.core.model.UsageRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Which model [LlmProvider] uses for a task: the user's choice for it, or the provider's default. */
fun interface ModelResolver {
    /** The model id the provider will use for [task]. */
    suspend fun modelFor(
        provider: AiProvider,
        task: AiTask,
    ): String
}

/** Put in the coroutine context of a run's AI calls so their usage rows link to that run. */
class UsageRunId(
    val runId: Long,
) : AbstractCoroutineContextElement(UsageRunId) {
    /** Key for the coroutine context. */
    companion object Key : CoroutineContext.Key<UsageRunId>
}

/**
 * Records usage for every call and refuses a call that could push spend past the budget (design: AI
 * provider layer, usage and budget).
 *
 * Before a call it adds the call's worst case (input tokens estimated from the prompt, plus
 * `maxOutputTokens`, at list price) and the worst cases of calls still in flight to this month's spend,
 * and refuses with [BlockReason.BUDGET_EXCEEDED] if that passes the monthly cap. The generic browser
 * agent also has a daily budget that its calls are checked against. If the budget or the spend cannot
 * be read, the call is refused with [BlockReason.BUDGET_UNAVAILABLE]: it fails closed, never assuming a
 * budget. After a call it writes a usage row, priced from the model that answered.
 *
 * Wrap the provider inside [GuardedLlmProvider] so secrets are checked first.
 */
@Suppress("LongParameterList") // Each collaborator is a separate seam the tests replace.
class MeteredLlmProvider(
    private val delegate: LlmProvider,
    private val ledger: UsageLedger,
    private val budgets: BudgetSource,
    private val prices: PriceTable,
    private val models: ModelResolver,
    private val clock: Clock = Clock.systemDefaultZone(),
) : LlmProvider by delegate {
    // Worst cases of calls that passed the check but have not ended yet. Without this, two calls started
    // together could each pass the check and together pass the cap. Atomic, so a cancelled call can
    // always release its share (a suspending release would be skipped by the cancellation).
    private val checkLock = Mutex()
    private val inFlightMicros = AtomicLong(0)
    private val inFlightBrowserMicros = AtomicLong(0)

    override suspend fun <T> generate(request: LlmRequest<T>): LlmResult<T> =
        when (val reservation = reserve(request)) {
            is Reservation.Refused -> LlmResult.Blocked(reservation.reason)
            is Reservation.Held ->
                try {
                    val result = delegate.generate(request)
                    record(request.task, result)
                    result
                } finally {
                    release(request.task, reservation.worstCase)
                }
        }

    private fun release(
        task: AiTask,
        worstCase: Long,
    ) {
        inFlightMicros.addAndGet(-worstCase)
        if (task == AiTask.BROWSER_STEP) inFlightBrowserMicros.addAndGet(-worstCase)
    }

    private sealed interface Reservation {
        /** The call fits the budget and is counted as in flight until it ends. */
        data class Held(
            val worstCase: Long,
        ) : Reservation

        data class Refused(
            val reason: BlockReason,
        ) : Reservation
    }

    @Suppress("TooGenericExceptionCaught") // Any failure to read the budget or the spend must fail closed.
    private suspend fun reserve(request: LlmRequest<*>): Reservation =
        checkLock.withLock {
            try {
                val model = models.modelFor(delegate.id, request.task)
                val price = prices.priceOrFallback(delegate.id, model)
                val worstCase = price.worstCase(estimateInputTokens(request), request.maxOutputTokens.toLong())
                val reason = refusalFor(request.task, worstCase)
                if (reason != null) return@withLock Reservation.Refused(reason)
                inFlightMicros.addAndGet(worstCase)
                if (request.task == AiTask.BROWSER_STEP) inFlightBrowserMicros.addAndGet(worstCase)
                Reservation.Held(worstCase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not read the AI budget; refusing the call")
                Reservation.Refused(BlockReason.BUDGET_UNAVAILABLE)
            }
        }

    private suspend fun refusalFor(
        task: AiTask,
        worstCase: Long,
    ): BlockReason? {
        val budget = budgets.budget()
        val now = clock.instant()
        val monthSpend = ledger.spentMicrosSince(startOfMonth(now))
        val overMonth = monthSpend + inFlightMicros.get() + worstCase > budget.monthlyCapMicros
        val overDay =
            task == AiTask.BROWSER_STEP &&
                dailyBrowserSpend(now) + inFlightBrowserMicros.get() + worstCase > budget.browserAgentDailyMicros
        return if (overMonth || overDay) BlockReason.BUDGET_EXCEEDED else null
    }

    /** Writes the usage row for any result that reports tokens. A failure is logged, never thrown. */
    @Suppress("TooGenericExceptionCaught") // The call is already paid for; a ledger failure must not hide its result.
    private suspend fun record(
        task: AiTask,
        result: LlmResult<*>,
    ) {
        val (usage, reportedModel) = usageOf(result) ?: return
        try {
            val model = reportedModel ?: models.modelFor(delegate.id, task)
            ledger.record(
                UsageRecord(
                    id = 0,
                    runId = coroutineContext[UsageRunId]?.runId,
                    provider = delegate.id,
                    model = model,
                    task = task,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    cachedTokens = usage.cacheReadInputTokens,
                    cacheWriteTokens = usage.cacheWriteInputTokens,
                    estimatedCostMicros = prices.priceOrFallback(delegate.id, model).cost(usage),
                    recordedAt = clock.instant(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Could not record AI usage for task %s", task)
        }
    }

    private fun usageOf(result: LlmResult<*>): Pair<TokenUsage, String?>? =
        when (result) {
            is LlmResult.Success -> result.usage to result.model
            is LlmResult.Refused -> result.usage to null
            is LlmResult.Truncated -> result.usage to null
            is LlmResult.InvalidOutput -> result.usage to null
            else -> null
        }

    private fun startOfMonth(now: Instant): Instant =
        now
            .atZone(clock.zone)
            .toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(clock.zone)
            .toInstant()

    private suspend fun dailyBrowserSpend(now: Instant): Long =
        ledger.spentMicrosSince(startOfDay(now), AiTask.BROWSER_STEP)

    private fun startOfDay(now: Instant): Instant =
        now
            .atZone(clock.zone)
            .toLocalDate()
            .atStartOfDay(clock.zone)
            .toInstant()

    private fun estimateInputTokens(request: LlmRequest<*>): Long {
        // One token per two characters over-counts English (about four) and still covers other scripts.
        // The output schema is sent with the prompt and billed as input.
        val chars =
            request.schema.jsonSchema
                .toString()
                .length +
                request.system.length +
                (request.context + request.input).sumOf {
                    when (it) {
                        is Part.Text -> it.text.length
                    }
                }
        return (chars + 1L) / CHARS_PER_TOKEN
    }

    private companion object {
        const val CHARS_PER_TOKEN = 2
    }
}
