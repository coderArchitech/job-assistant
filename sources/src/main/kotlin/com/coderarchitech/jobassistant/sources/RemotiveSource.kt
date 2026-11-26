package com.coderarchitech.jobassistant.sources

import com.coderarchitech.jobassistant.core.model.SearchPreset
import com.coderarchitech.jobassistant.core.model.SourceCursor
import com.coderarchitech.jobassistant.core.network.NetworkJson
import com.coderarchitech.jobassistant.core.network.NoRetry
import com.coderarchitech.jobassistant.core.network.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * What Remotive's terms ask of anyone showing its jobs: link to the posting's Remotive page and name
 * Remotive as the source. Its terms also forbid passing its jobs on to other sites. Screens that show a
 * posting from [RemotiveSource.KEY] must show this credit.
 */
object RemotiveCredit {
    const val NAME = "Remotive"
    const val URL = "https://remotive.com"
}

/** Thrown instead of sending a request that would break a host's rate limit; nothing was sent. */
class SourceThrottledException(
    message: String,
) : IOException(message)

/**
 * Remotive's public feed: `GET remotive.com/api/remote-jobs`. No key is needed.
 *
 * Checked against Remotive's API documentation and the live feed. The documentation asks for a couple of
 * requests a day and no more than 4, and says more than 2 a minute are blocked; it also says jobs are
 * delayed by 24 hours. So this client asks for the whole feed in one request (about 300 KB), with no
 * search, because a request per keyword would use the day's allowance at once, and the preset's filters are
 * applied later.
 *
 * The rate is enforced here: at most [MAX_REQUESTS_PER_DAY] requests in any 24 hours, at least
 * [MIN_GAP] apart, and no new fetch until [MIN_INTERVAL] after the last successful one. A fetch that would
 * break a rule throws [SourceThrottledException] without sending anything. The request carries [NoRetry],
 * because a retry would count against the allowance, and every attempt counts, failed or not.
 *
 * Limits: the 6-hour rule comes from the cursor, so it holds across app restarts; the count of attempts is
 * kept in memory and starts again with the app process, so only repeated failures spread over restarts
 * could pass 4 in a day. Call [fetch] once per run, not once per preset, since the second call inside the
 * 6 hours is refused; the caller filters the one result for each preset.
 *
 * @param client the `NetworkGroup.JOB_SOURCES` client.
 */
class RemotiveSource(
    private val client: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
    baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : JobSource {
    override val key: String = KEY

    private val feedUrl = baseUrl.newBuilder().addPathSegments("api/remote-jobs").build()

    /** When each request this process sent was made, newest last. Guarded by itself. */
    private val attempts = ArrayDeque<Instant>()

    override suspend fun fetch(
        since: SourceCursor?,
        preset: SearchPreset,
    ): List<RawPosting> {
        reserveRequest(since)
        return parse(download())
    }

    /** Records a request, or throws if sending one now would break the rate. */
    private fun reserveRequest(since: SourceCursor?) {
        val now = clock.instant()
        synchronized(attempts) {
            val problem = rateProblem(since?.lastSuccessAt, now)
            if (problem != null) throw SourceThrottledException(problem)
            attempts.addLast(now)
        }
    }

    /** Why a request at [now] would break the rate, or null when it would not. Call with `attempts` held. */
    private fun rateProblem(
        lastSuccess: Instant?,
        now: Instant,
    ): String? {
        while (attempts.isNotEmpty() && Duration.between(attempts.first(), now) >= DAY) attempts.removeFirst()
        val last = attempts.lastOrNull()
        return when {
            lastSuccess != null && Duration.between(lastSuccess, now) < MIN_INTERVAL ->
                "Remotive is fetched every ${MIN_INTERVAL.toHours()} h at most; next ${lastSuccess + MIN_INTERVAL}"
            attempts.size >= MAX_REQUESTS_PER_DAY ->
                "Remotive allows $MAX_REQUESTS_PER_DAY requests a day; next at ${attempts.first() + DAY}"
            last != null && Duration.between(last, now) < MIN_GAP ->
                "Remotive allows 2 requests a minute; next at ${last + MIN_GAP}"
            else -> null
        }
    }

    private suspend fun download(): String =
        client
            .newCall(
                Request
                    .Builder()
                    .url(feedUrl)
                    .tag(NoRetry::class.java, NoRetry)
                    .build(),
            ).await()
            .use { response ->
                if (!response.isSuccessful) throw IOException("Remotive answered HTTP ${response.code}")
                response.readText()
            }

    /** Throws if the reply has no job list; unusable entries are skipped. */
    private fun parse(body: String): List<RawPosting> {
        val feed =
            try {
                NetworkJson.decodeFromString<FeedDto>(body)
            } catch (e: SerializationException) {
                throw IOException("Remotive sent a reply this app cannot read", e)
            }
        return feed.jobs.mapNotNull { element -> (element as? JsonObject)?.let(::decode)?.toPosting() }
    }

    // One odd entry must not lose the others.
    @Suppress("SwallowedException")
    private fun decode(element: JsonObject): JobDto? =
        try {
            NetworkJson.decodeFromJsonElement(JobDto.serializer(), element)
        } catch (e: SerializationException) {
            null
        }

    @Suppress("ReturnCount") // One early exit per required field reads better than a nested null check.
    private fun JobDto.toPosting(): RawPosting? {
        val postingId = id?.content.clean() ?: return null
        val postingTitle = title.clean() ?: return null
        val postingCompany = companyName.clean() ?: return null
        val postingUrl = url.clean()?.takeIf { it.toHttpUrlOrNull() != null } ?: return null
        return RawPosting(
            externalId = postingId,
            title = postingTitle,
            company = postingCompany,
            url = postingUrl,
            location = candidateRequiredLocation.clean(),
            description = description?.takeIf { it.isNotBlank() },
            postedAt = publicationDate.toInstant(),
            compensation = salary.clean(),
            workplaceType = "remote",
        )
    }

    /** Remotive sends no offset (`2026-11-24T10:31:32`); it is taken as UTC. */
    @Suppress("SwallowedException")
    private fun String?.toInstant(): Instant? =
        try {
            this?.let { OffsetDateTime.parse(it).toInstant() }
        } catch (e: DateTimeParseException) {
            try {
                this?.let { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) }
            } catch (e2: DateTimeParseException) {
                null
            }
        }

    @Serializable
    private class FeedDto(
        // Required: a 200 reply without a job list is not the feed, whatever else it says.
        val jobs: List<JsonElement>,
    )

    @Suppress("LongParameterList") // One field per part of Remotive's posting that is used.
    @Serializable
    private class JobDto(
        val id: JsonPrimitive? = null,
        val url: String? = null,
        val title: String? = null,
        @SerialName("company_name") val companyName: String? = null,
        @SerialName("publication_date") val publicationDate: String? = null,
        @SerialName("candidate_required_location") val candidateRequiredLocation: String? = null,
        val salary: String? = null,
        val description: String? = null,
    )

    companion object {
        /** The source key; there is one Remotive feed. */
        const val KEY = "remotive"

        /** Remotive advises at most 4 requests a day. */
        const val MAX_REQUESTS_PER_DAY = 4

        /** Remotive blocks more than 2 requests a minute. */
        val MIN_GAP: Duration = Duration.ofSeconds(30)

        /** A day's allowance spread out: 4 a day is one every 6 hours. */
        val MIN_INTERVAL: Duration = Duration.ofHours(6)

        private val DAY: Duration = Duration.ofHours(24)
        private const val DEFAULT_BASE_URL = "https://remotive.com/"
    }
}
