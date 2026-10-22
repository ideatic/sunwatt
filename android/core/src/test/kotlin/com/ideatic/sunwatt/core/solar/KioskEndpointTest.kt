package com.ideatic.sunwatt.core.solar

import com.ideatic.sunwatt.core.services.HtmlEntities
import com.ideatic.sunwatt.core.services.KioskEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KioskEndpointTest {
    private fun parse(text: String): KioskEndpoint = assertNotNull(KioskEndpoint.parse(text), "should parse: $text")

    @Test
    fun kioskPageUrlWithFragment() {
        val e = parse("https://eu5.fusionsolar.example/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=AbC123")
        assertEquals("eu5.fusionsolar.example", e.host)
        assertEquals("AbC123", e.kk)
        assertEquals("https://eu5.fusionsolar.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=AbC123", e.apiURL)
    }

    @Test
    fun apiUrl() {
        assertEquals(KioskEndpoint("host.example", "XYZ"), parse("https://host.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=XYZ"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals("ABC", parse("  \n https://host.example/x.html#/kiosk?kk=ABC \t\n").kk)
    }

    @Test
    fun missingKk() {
        assertNull(KioskEndpoint.parse("https://host.example/x.html#/kiosk"))
        assertNull(KioskEndpoint.parse("https://host.example/x.html#/kiosk?other=1"))
        assertNull(KioskEndpoint.parse("https://host.example/rest/a?x=1"))
    }

    @Test
    fun emptyKk() {
        assertNull(KioskEndpoint.parse("https://host.example/x.html#/kiosk?kk="))
        assertNull(KioskEndpoint.parse("https://host.example/rest/a?kk="))
    }

    @Test
    fun noHost() {
        assertNull(KioskEndpoint.parse("https:///x.html#/kiosk?kk=ABC"))
        assertNull(KioskEndpoint.parse("https://"))
    }

    @Test
    fun httpAndSchemelessRejected() {
        assertNull(KioskEndpoint.parse("http://host.example/x.html#/kiosk?kk=ABC"))
        assertNull(KioskEndpoint.parse("host.example/x.html#/kiosk?kk=ABC"))
    }

    @Test
    fun garbage() {
        assertNull(KioskEndpoint.parse(""))
        assertNull(KioskEndpoint.parse("   "))
        assertNull(KioskEndpoint.parse("not a url at all"))
        assertNull(KioskEndpoint.parse("kk=ABC"))
    }

    @Test
    fun specialCharactersRoundTrip() {
        val original = KioskEndpoint("host.example", "a+b/c=d&e f%g")
        val url = original.apiURL
        assertTrue(url.contains("kk=a%2Bb%2Fc%3Dd%26e%20f%25g"))
        assertEquals(original, parse(url))
        // Same through the fragment form.
        assertEquals(original, parse("https://host.example/x.html#/kiosk?kk=a%2Bb%2Fc%3Dd%26e%20f%25g"))
    }

    // Hardening

    @Test
    fun descriptionsHideToken() {
        val e = KioskEndpoint("host.example", "SECRETKK")
        assertFalse(e.toString().contains("SECRETKK"))
        assertTrue(e.toString().contains("kk: ***"))
        assertTrue(e.toString().contains("host.example"))
    }

    @Test
    fun cacheIdIsTheSha256OfHostAndToken() {
        // printf 'host.example\nSECRETKK' | shasum -a 256
        val id = KioskEndpoint("host.example", "SECRETKK").cacheID
        assertEquals("64955cb361862a11d5e533832d75dffd8450e9212828db42dabd17faa9082acd", id)
        assertNotEqualsId(id, KioskEndpoint("host.example", "OTHERKK").cacheID)
        assertNotEqualsId(id, KioskEndpoint("other.example", "SECRETKK").cacheID)
        assertFalse(id.contains("SECRETKK"))
    }

    private fun assertNotEqualsId(a: String, b: String) = assertTrue(a != b)

    @Test
    fun hostIsLowercasedAndSchemeCaseInsensitive() {
        val e = parse("HTTPS://EU5.FusionSolar.Example/x.html#/kiosk?kk=AbC")
        assertEquals("eu5.fusionsolar.example", e.host)
        assertEquals("AbC", e.kk)
    }

    @Test
    fun explicitPortIsRejected() {
        assertNull(KioskEndpoint.parse("https://host.example:8443/x.html#/kiosk?kk=ABC"))
        assertNull(KioskEndpoint.parse("https://host.example:443/rest/a?kk=ABC"))
    }

    @Test
    fun userInfoIsRejected() {
        assertNull(KioskEndpoint.parse("https://user:pw@host.example/rest/a?kk=ABC"))
    }

    @Test
    fun trailingSlashAndExtraFragmentParameters() {
        assertEquals("ABC", parse("https://host.example/?kk=ABC").kk)
        assertEquals("ABC", parse("https://host.example/pvmswebsite/cloud.html/#/kiosk?kk=ABC").kk)
        assertEquals("X", parse("https://host.example/x.html#/kiosk?foo=1&kk=X").kk)
    }

    @Test
    fun duplicateKkTakesFirst() {
        assertEquals("FIRST", parse("https://host.example/rest/a?kk=FIRST&kk=SECOND").kk)
        assertEquals("FIRST", parse("https://host.example/x.html#/kiosk?kk=FIRST&kk=SECOND").kk)
        assertEquals("QUERY", parse("https://host.example/rest/a?kk=QUERY#/kiosk?kk=FRAGMENT").kk)
    }
}

class HtmlUnescapeTest {
    @Test
    fun namedEntities() {
        assertEquals("\"a\" & <b> 'c'", HtmlEntities.unescape("&quot;a&quot; &amp; &lt;b&gt; &apos;c&apos;"))
    }

    @Test
    fun decimalEntities() {
        assertEquals("'x\"á", HtmlEntities.unescape("&#39;x&#34;&#225;"))
    }

    @Test
    fun hexEntities() {
        assertEquals("'Aá", HtmlEntities.unescape("&#x27;&#X41;&#xe1;"))
    }

    @Test
    fun untouchedText() {
        assertEquals("plain text, no entities", HtmlEntities.unescape("plain text, no entities"))
        assertEquals("a & b &unknown; &#zz; &amp", HtmlEntities.unescape("a & b &unknown; &#zz; &amp"))
        assertEquals("", HtmlEntities.unescape(""))
    }

    @Test
    fun decodesOnce() {
        assertEquals("&quot;", HtmlEntities.unescape("&amp;quot;"))
    }

    @Test
    fun nonScalarsAreLeftAlone() {
        assertEquals("&#xD800;", HtmlEntities.unescape("&#xD800;"))
        assertEquals("&#1114112;", HtmlEntities.unescape("&#1114112;"))
        assertEquals("&#;", HtmlEntities.unescape("&#;"))
    }
}
