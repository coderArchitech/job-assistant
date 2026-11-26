package com.coderarchitech.jobassistant.sources.dedupe

import com.coderarchitech.jobassistant.sources.LeverRegion
import com.coderarchitech.jobassistant.sources.normalize.NormalizedPosting
import java.time.Instant

/** A posting with the keys the deduplicator compares. */
data class KeyedPosting(
    val posting: NormalizedPosting,
    val canonicalUrl: String,
    val dedupeHash: String,
) {
    /** Greenhouse, Lever and Ashby copies have the cleanest apply path, so they are kept as the primary. */
    val isAts: Boolean get() = posting.atsType != null || Deduplicator.isAtsSource(posting.source)

    override fun toString(): String = "KeyedPosting(${posting.source}/${posting.externalId})"

    companion object {
        fun of(posting: NormalizedPosting) =
            KeyedPosting(
                posting = posting,
                canonicalUrl = DedupeKeys.canonicalUrl(posting.url),
                dedupeHash = DedupeKeys.dedupeHash(posting.companyKey, posting.title),
            )
    }
}

/** Postings that are one job: the [primary] copy is stored as the job, the [aliases] point at it. */
data class DuplicateGroup(
    val primary: KeyedPosting,
    val aliases: List<KeyedPosting>,
)

/** A stored job that a new posting may duplicate. */
data class StoredJob(
    val id: Long,
    val source: String,
    /** Set when this row is itself an alias; the group's primary is then that job. */
    val aliasOfJobId: Long?,
    /** The source of the group's primary row (this row's own source when it is the primary). */
    val primarySource: String,
)

/** The stored jobs found by each of the three keys, as the DAO's lookups return them. */
data class StoredMatches(
    val sameSourceAndId: StoredJob? = null,
    val sameCanonicalUrl: List<StoredJob> = emptyList(),
    val sameDedupeHash: List<StoredJob> = emptyList(),
)

/** What to do with a new posting, given what is stored. */
sealed interface DedupeDecision {
    /** Nothing like it is stored: insert it as a new job. */
    data object New : DedupeDecision

    /** The same posting from the same source: update job [jobId] with the fresh data. */
    data class Update(
        val jobId: Long,
    ) : DedupeDecision

    /** Another copy of job [primaryJobId]: store it with `aliasOfJobId` set to that job. */
    data class Alias(
        val primaryJobId: Long,
    ) : DedupeDecision

    /**
     * An ATS copy of job [replacesJobId], which came from a job board: store this one as the primary and point
     * that job and its aliases at it.
     */
    data class NewPrimary(
        val replacesJobId: Long,
    ) : DedupeDecision
}

/**
 * Collapses duplicate postings (design, Deduplication), in order: the same source and external id is the
 * same posting; the same canonical URL is the same job; the same `dedupeHash` (company and title) is the
 * same job. Pure and thread-safe; storing the result is the pipeline's job.
 */
object Deduplicator {
    // Lever's come from its regions, so a new region is covered; DeduplicatorTest checks every ATS source's key.
    private val ATS_PREFIXES = listOf("greenhouse:", "ashby:") + LeverRegion.entries.map { "${it.keyPrefix}:" }

    /** True for a source key of an ATS board (`greenhouse:stripe`), whose copy is preferred as the primary. */
    fun isAtsSource(source: String): Boolean = ATS_PREFIXES.any { source.startsWith(it) }

    /**
     * One group per job in [postings]. A posting repeated by the same source keeps its last copy, as the
     * freshest; others that match by URL or hash become aliases of the best copy: an ATS one first, then the
     * earliest posted, then the first in [postings].
     */
    fun collapse(postings: List<NormalizedPosting>): List<DuplicateGroup> {
        val latest =
            postings
                .withIndex()
                .associateBy { (_, p) ->
                    p.source to p.externalId
                }.values
                .sortedBy { it.index }
        val keyed = latest.map { KeyedPosting.of(it.value) }
        val groups = UnionFind(keyed.size)
        unionBy(keyed, groups) { it.canonicalUrl }
        unionBy(keyed, groups) { it.dedupeHash }
        return keyed.indices
            .groupBy(groups::find)
            .values
            .sortedBy { it.min() }
            .map { members ->
                val ordered = members.map { keyed[it] }
                val primary = ordered.minWith(PRIMARY_ORDER)
                DuplicateGroup(primary, ordered.filter { it !== primary })
            }
    }

    /**
     * The decision for a new [posting] against the stored jobs it matches. When its URL and hash point at
     * different stored groups, it joins the best of them (one with an ATS primary, else the oldest); merging
     * those groups with each other is left alone, since they were judged different jobs when stored.
     */
    fun decide(
        posting: KeyedPosting,
        stored: StoredMatches,
    ): DedupeDecision {
        val same = stored.sameSourceAndId
        val target = (stored.sameCanonicalUrl + stored.sameDedupeHash).minWithOrNull(STORED_ORDER)
        return when {
            same != null -> DedupeDecision.Update(same.id)
            target == null -> DedupeDecision.New
            posting.isAts && !isAtsSource(target.primarySource) -> DedupeDecision.NewPrimary(target.groupId)
            else -> DedupeDecision.Alias(target.groupId)
        }
    }

    /** The id of the group's primary row. */
    private val StoredJob.groupId: Long get() = aliasOfJobId ?: id

    /** Which stored group a posting joins: one with an ATS primary, else the oldest. */
    private val STORED_ORDER =
        compareByDescending<StoredJob> { isAtsSource(it.primarySource) }.thenBy { it.groupId }

    private val PRIMARY_ORDER =
        compareByDescending<KeyedPosting> { it.isAts }
            .thenBy { it.posting.postedAt ?: Instant.MAX }

    private fun unionBy(
        keyed: List<KeyedPosting>,
        groups: UnionFind,
        key: (KeyedPosting) -> String,
    ) {
        keyed.indices.groupBy { key(keyed[it]) }.values.forEach { same ->
            same.drop(1).forEach { groups.union(same.first(), it) }
        }
    }

    /** Disjoint sets over indices, so a posting matching two groups by different keys joins them. */
    private class UnionFind(
        size: Int,
    ) {
        private val parent = IntArray(size) { it }

        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            parent[i] = root
            return root
        }

        fun union(
            a: Int,
            b: Int,
        ) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
    }
}
