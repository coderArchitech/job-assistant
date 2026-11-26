package com.coderarchitech.jobassistant.sources.dedupe

import com.coderarchitech.jobassistant.core.model.AtsType
import com.coderarchitech.jobassistant.sources.AshbySource
import com.coderarchitech.jobassistant.sources.GreenhouseSource
import com.coderarchitech.jobassistant.sources.LeverRegion
import com.coderarchitech.jobassistant.sources.LeverSource
import com.coderarchitech.jobassistant.sources.RawPosting
import com.coderarchitech.jobassistant.sources.normalize.JobNormalizer
import com.coderarchitech.jobassistant.sources.normalize.NormalizedPosting
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeduplicatorTest {
    private fun posting(
        source: String,
        id: String,
        company: String,
        title: String,
        url: String,
        ats: AtsType? = null,
        postedAt: String? = null,
    ): NormalizedPosting =
        JobNormalizer.normalize(
            RawPosting(
                externalId = id,
                title = title,
                company = company,
                url = url,
                atsType = ats,
                postedAt = postedAt?.let(Instant::parse),
            ),
            source,
        )

    /**
     * Task T2.11 Done when: a fixture set with known cross-source duplicates collapses to the expected count.
     * Twelve postings, six jobs.
     */
    private val fixture =
        listOf(
            // Job 1: one Stripe opening on its Greenhouse board and two remote boards, titled differently.
            posting(
                "remoteok",
                "ro-1",
                "Stripe, Inc.",
                "Sr. Android Engineer (Remote)",
                "https://remoteok.com/remote-jobs/1",
            ),
            posting(
                "greenhouse:stripe",
                "123",
                "Stripe",
                "Senior Android Engineer",
                "https://boards.greenhouse.io/stripe/jobs/123?gh_src=abc",
                ats = AtsType.GREENHOUSE,
            ),
            posting(
                "weworkremotely:remote-programming-jobs",
                "https://weworkremotely.com/remote-jobs/stripe-senior-android-engineer",
                "Stripe",
                "Senior Android Engineer - Remote",
                "https://weworkremotely.com/remote-jobs/stripe-senior-android-engineer",
            ),
            // Job 2: the same link with tracking added, under two different titles.
            posting(
                "remotive",
                "rv-9",
                "Acme",
                "Backend Engineer (Go)",
                "https://boards.greenhouse.io/acme/jobs/9?utm_source=remotive&utm_medium=feed",
            ),
            posting(
                "greenhouse:acme",
                "9",
                "Acme Corp",
                "Backend Engineer, Golang",
                "https://Boards.Greenhouse.io/acme/jobs/9/",
                ats = AtsType.GREENHOUSE,
            ),
            // Job 3: Lever listed it twice in one run; the later copy is kept, not stored as an alias.
            posting(
                "lever:zeta",
                "z-1",
                "Zeta",
                "Analyst II - SIEM/SecOps",
                "https://jobs.lever.co/zeta/z-1",
                AtsType.LEVER,
            ),
            posting(
                "lever:zeta",
                "z-1",
                "Zeta",
                "Analyst II - SIEM / SecOps",
                "https://jobs.lever.co/zeta/z-1",
                AtsType.LEVER,
            ),
            // Job 4: joined through a chain: A and B share a link, B and C share company and title.
            posting("remotive", "rv-4", "Globex", "Data Engineer", "https://globex.example/careers/4?utm_source=rv"),
            posting("remoteok", "ro-4", "Globex", "Data Engineer II", "https://globex.example/careers/4"),
            posting(
                "weworkremotely:remote-programming-jobs",
                "https://weworkremotely.com/remote-jobs/globex-data-engineer-ii",
                "Globex Inc",
                "Data Engineer II",
                "https://weworkremotely.com/remote-jobs/globex-data-engineer-ii",
            ),
            // Job 5 and not job 1: a different title at the same company, and the same title elsewhere are distinct.
            posting("remoteok", "ro-5", "Stripe", "Android Engineer", "https://remoteok.com/remote-jobs/5"),
            posting(
                "remoteok",
                "ro-6",
                "Stripe Payments Ltd",
                "Senior Android Engineer",
                "https://remoteok.com/remote-jobs/6",
            ),
        )

    @Test
    fun theFixtureCollapsesToTheKnownNumberOfJobs() {
        val groups = Deduplicator.collapse(fixture)

        assertEquals(6, groups.size)
        assertEquals("every posting but the repeated Lever one is kept", 11, groups.sumOf { 1 + it.aliases.size })
    }

    @Test
    fun theAtsCopyIsThePrimaryAndTheOthersAreAliases() {
        val groups = Deduplicator.collapse(fixture)

        val stripe = groups.first { group -> group.primary.posting.source == "greenhouse:stripe" }
        assertEquals(
            setOf("remoteok", "weworkremotely:remote-programming-jobs"),
            stripe.aliases
                .map {
                    it.posting.source
                }.toSet(),
        )
        val acme = groups.first { group -> group.primary.posting.source == "greenhouse:acme" }
        assertEquals(listOf("remotive"), acme.aliases.map { it.posting.source })
        val globex = groups.first { group -> group.primary.posting.companyKey == "globex" }
        assertEquals("joined through the chain", 2, globex.aliases.size)
        val zeta = groups.single { group -> group.primary.posting.source == "lever:zeta" }
        assertEquals("Analyst II - SIEM / SecOps", zeta.primary.posting.title)
        assertTrue(zeta.aliases.isEmpty())
    }

    @Test
    fun withoutAnAtsCopyTheEarliestPostedIsPrimary() {
        val groups =
            Deduplicator.collapse(
                listOf(
                    posting(
                        "remoteok",
                        "1",
                        "Initech",
                        "QA Lead",
                        "https://a.example/1",
                        postedAt = "2026-11-20T00:00:00Z",
                    ),
                    posting(
                        "remotive",
                        "2",
                        "Initech",
                        "QA Lead",
                        "https://b.example/2",
                        postedAt = "2026-11-18T00:00:00Z",
                    ),
                    posting("weworkremotely:x", "3", "Initech", "QA Lead", "https://c.example/3"),
                ),
            )

        assertEquals(
            "remotive",
            groups
                .single()
                .primary.posting.source,
        )
    }

    @Test
    fun canonicalUrlsDropWhatVariesBetweenLinksToOnePage() {
        val same =
            listOf(
                "https://boards.greenhouse.io/stripe/jobs/123",
                "https://boards.greenhouse.io/stripe/jobs/123/",
                "http://Boards.Greenhouse.IO/stripe/jobs/123?gh_src=x&utm_campaign=y#apply",
                "https://www.boards.greenhouse.io/stripe/jobs/123?UTM_SOURCE=z&fbclid=1",
            )
        assertEquals(1, same.map(DedupeKeys::canonicalUrl).distinct().size)
        assertEquals("https://boards.greenhouse.io/stripe/jobs/123", DedupeKeys.canonicalUrl(same[2]))
        assertEquals(
            "other parameters are kept, sorted",
            "https://jobs.example/view?a=1&id=7",
            DedupeKeys.canonicalUrl("https://jobs.example/view?id=7&a=1&lever-source=x"),
        )
        assertNotEquals(
            "the path is case-sensitive",
            DedupeKeys.canonicalUrl("https://jobs.lever.co/acme/ABC"),
            DedupeKeys.canonicalUrl("https://jobs.lever.co/acme/abc"),
        )
        assertEquals("not a URL is kept as text", "not a url", DedupeKeys.canonicalUrl(" not a url "))
        assertNotEquals(
            "a Greenhouse job id on a careers page names the job",
            DedupeKeys.canonicalUrl("https://acme.example/careers?gh_jid=1"),
            DedupeKeys.canonicalUrl("https://acme.example/careers?gh_jid=2"),
        )
    }

    @Test
    fun titleKeysIgnoreAbbreviationsPunctuationAndModeTags() {
        val same =
            listOf(
                "Sr. Android Engineer",
                "Senior Android Engineer (Remote)",
                "senior android engineer - Hybrid",
                "SR ANDROID ENGINEER",
            )
        assertEquals(listOf("senior android engineer"), same.map(DedupeKeys::titleKey).distinct())
        assertNotEquals(DedupeKeys.titleKey("Android Engineer II"), DedupeKeys.titleKey("Android Engineer"))
        assertEquals(64, DedupeKeys.dedupeHash("stripe", "Senior Android Engineer").length)
    }

    @Test
    fun decisionsAgainstStoredJobs() {
        val ats = KeyedPosting.of(fixture[1])
        val board = KeyedPosting.of(fixture[0])
        val boardRow = StoredJob(id = 7, source = "remoteok", aliasOfJobId = null, primarySource = "remoteok")
        val aliasRow = StoredJob(id = 9, source = "remotive", aliasOfJobId = 3, primarySource = "greenhouse:stripe")

        assertEquals(DedupeDecision.New, Deduplicator.decide(board, StoredMatches()))
        assertEquals(
            DedupeDecision.Update(7),
            Deduplicator.decide(board, StoredMatches(sameSourceAndId = boardRow, sameDedupeHash = listOf(aliasRow))),
        )
        assertEquals(
            "an alias points at the group's primary",
            DedupeDecision.Alias(3),
            Deduplicator.decide(board, StoredMatches(sameDedupeHash = listOf(aliasRow))),
        )
        assertEquals(
            "an ATS copy takes over from a job-board primary",
            DedupeDecision.NewPrimary(7),
            Deduplicator.decide(ats, StoredMatches(sameDedupeHash = listOf(boardRow))),
        )
        assertEquals(
            "but not from another ATS primary",
            DedupeDecision.Alias(3),
            Deduplicator.decide(ats, StoredMatches(sameCanonicalUrl = listOf(aliasRow))),
        )
        assertTrue(Deduplicator.isAtsSource("lever-eu:acme"))
    }

    @Test
    fun everyAtsSourceKeyIsRecognised() {
        val client = OkHttpClient()
        val keys =
            listOf(GreenhouseSource("stripe", "Stripe", client).key, AshbySource("Ashby", "Ashby", client).key) +
                LeverRegion.entries.map { LeverSource("acme", "Acme", client, region = it).key }
        assertTrue(keys.toString(), keys.all(Deduplicator::isAtsSource))
        assertTrue(!Deduplicator.isAtsSource("remoteok") && !Deduplicator.isAtsSource("weworkremotely:x"))
    }

    @Test
    fun keysDoNotMergeDifferentJobs() {
        val different =
            listOf(
                "C++ Engineer" to "C# Engineer",
                "Senior Engineer, Hybrid Cloud" to "Senior Engineer, Remote Access",
                "Android Engineer (Remote - US)" to "Android Engineer (Remote - EU)",
                "Fully-Remote Senior Engineer" to "Fully-Remote Staff Engineer",
            )
        for ((a, b) in different) assertNotEquals("$a vs $b", DedupeKeys.titleKey(a), DedupeKeys.titleKey(b))
        assertEquals(DedupeKeys.titleKey("Data Engineer"), DedupeKeys.titleKey("Data Engineer, Remote"))
        assertNotEquals(
            "a routing fragment names the page",
            DedupeKeys.canonicalUrl("https://careers.acme.com/#/jobs/123"),
            DedupeKeys.canonicalUrl("https://careers.acme.com/#/jobs/456"),
        )
        assertNotEquals(
            "a generic parameter may name the job",
            DedupeKeys.canonicalUrl("https://jobs.acme.example/apply?ref=1001"),
            DedupeKeys.canonicalUrl("https://jobs.acme.example/apply?ref=1002"),
        )
    }

    @Test
    fun aPostingMatchingTwoStoredGroupsJoinsTheAtsOne() {
        val board = KeyedPosting.of(fixture[0])
        val boardGroup = StoredJob(id = 2, source = "remoteok", aliasOfJobId = null, primarySource = "remoteok")
        val atsGroup =
            StoredJob(id = 5, source = "greenhouse:stripe", aliasOfJobId = null, primarySource = "greenhouse:stripe")

        assertEquals(
            DedupeDecision.Alias(5),
            Deduplicator.decide(
                board,
                StoredMatches(sameCanonicalUrl = listOf(boardGroup), sameDedupeHash = listOf(atsGroup)),
            ),
        )
        assertEquals(
            "an ATS posting joins an ATS group rather than taking over a board group",
            DedupeDecision.Alias(5),
            Deduplicator.decide(
                KeyedPosting.of(fixture[1]),
                StoredMatches(sameCanonicalUrl = listOf(boardGroup), sameDedupeHash = listOf(atsGroup)),
            ),
        )
    }
}
