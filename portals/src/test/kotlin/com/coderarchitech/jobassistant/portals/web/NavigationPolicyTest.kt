package com.coderarchitech.jobassistant.portals.web

import com.coderarchitech.jobassistant.portals.web.NavigationDecision.ALLOW
import com.coderarchitech.jobassistant.portals.web.NavigationDecision.BLOCK
import com.coderarchitech.jobassistant.portals.web.NavigationDecision.OPEN_OUTSIDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NavigationPolicyTest {
    private val naukri = NavigationPolicy(setOf("naukri.com"))

    @Test
    fun decisionsForEveryKindOfUrl() {
        val cases =
            mapOf(
                "https://www.naukri.com/mnjuser/profile" to ALLOW,
                "https://naukri.com/" to ALLOW,
                "https://login.naukri.com/nLogin/Login.php" to ALLOW,
                "https://accounts.google.com/o/oauth2/auth?x=1" to ALLOW,
                "https://jobs.lever.co/acme/123/apply" to ALLOW,
                "about:blank" to ALLOW,
                // Cleartext is not loaded, even for the portal's own host.
                "http://www.naukri.com/" to OPEN_OUTSIDE,
                "https://evil.example/steal" to OPEN_OUTSIDE,
                "https://naukri.com.evil.example/" to OPEN_OUTSIDE,
                "https://evilnaukri.com/" to OPEN_OUTSIDE,
                "https://acme.wd5.myworkdayjobs.com/en-US/careers/job/1" to OPEN_OUTSIDE,
                "https://user:pass@www.naukri.com/" to OPEN_OUTSIDE,
                "https://www.linkedin.com/in/someone" to BLOCK,
                "https://LINKEDIN.COM/jobs" to BLOCK,
                "https://lnkd.in/abc" to BLOCK,
                "https://media.licdn.com/x.png" to BLOCK,
                "file:///data/data/com.coderarchitech.jobassistant/files/vault" to BLOCK,
                "content://com.android.contacts/contacts" to BLOCK,
                "javascript:alert(1)" to BLOCK,
                "intent://scan/#Intent;scheme=zxing;end" to BLOCK,
                "data:text/html,<script>alert(1)</script>" to BLOCK,
                "not a url at all" to BLOCK,
            )
        val wrong = cases.filter { (url, expected) -> naukri.decide(url) != expected }
        assertEquals(wrong.keys.joinToString("\n") { "$it -> ${naukri.decide(it)}" }, 0, wrong.size)
    }

    @Test
    fun linkedInCanNeverBeAllowed() {
        for (host in listOf("linkedin.com", "www.linkedin.com", "*.linkedin.com")) {
            try {
                NavigationPolicy(setOf(host))
                fail("$host should be refused")
            } catch (expected: IllegalArgumentException) {
                // Refused, as CLAUDE.md rule 7 requires.
            }
        }
    }

    @Test
    fun subResourcesFromLinkedInAreBlocked() {
        assertTrue(naukri.isBlockedHost("https://platform.linkedin.com/in.js"))
        assertFalse(naukri.isBlockedHost("https://static.naukri.com/app.js"))
        assertFalse(naukri.isBlockedHost("not a url"))
    }

    @Test
    fun withoutSignInHostsOnlyThePortalIsAllowed() {
        val strict = NavigationPolicy(setOf("instahyre.com"), includeSignInAndAts = false)

        assertEquals(ALLOW, strict.decide("https://www.instahyre.com/"))
        assertEquals(OPEN_OUTSIDE, strict.decide("https://accounts.google.com/"))
    }
}
