package com.luverse.fetchy

import org.junit.Assert.*
import org.junit.Test

class XCookieScopeTest {
    @Test fun repairsOnlyFirstPartyApiLoginCookies() {
        val rows = listOf("#HttpOnly_x.com\tFALSE\t/\tTRUE\t0\tauth_token\tfixture",
            "x.com\tFALSE\t/\tTRUE\t0\tct0\tcsrf", "x.com\tFALSE\t/\tTRUE\t0\tpreference\tvalue",
            "twitter.com\tFALSE\t/\tTRUE\t0\tauth_token\tlegacy")
        val fixed = xApiCookieRows(rows)
        assertEquals(rows, fixed.take(rows.size)); assertEquals(6, fixed.size)
        assertTrue(fixed[4].startsWith("#HttpOnly_api.x.com\tFALSE\t/\tTRUE\t0\tauth_token\t"))
        assertTrue(fixed[5].endsWith("\tct0\tcsrf")); assertEquals(fixed, xApiCookieRows(fixed))
    }
    @Test fun preservesImportedDomainScopeAndApiSpecificValues() {
        val domain = listOf(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\tscoped")
        assertEquals(domain, xApiCookieRows(domain))
        val specific = domain + "api.x.com\tFALSE\t/\tTRUE\t0\tauth_token\tapi"
        assertEquals(specific, xApiCookieRows(specific))
    }
}
