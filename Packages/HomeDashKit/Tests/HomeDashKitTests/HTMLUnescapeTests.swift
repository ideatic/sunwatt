import Testing
@testable import HomeDashKit

@Suite struct HTMLUnescapeTests {
    @Test func namedEntities() {
        #expect(HTMLEntities.unescape("&quot;a&quot; &amp; &lt;b&gt; &apos;c&apos;") == "\"a\" & <b> 'c'")
    }

    @Test func decimalEntities() {
        #expect(HTMLEntities.unescape("&#39;x&#34;&#225;") == "'x\"á")
    }

    @Test func hexEntities() {
        #expect(HTMLEntities.unescape("&#x27;&#X41;&#xe1;") == "'Aá")
    }

    @Test func untouchedText() {
        #expect(HTMLEntities.unescape("plain text, no entities") == "plain text, no entities")
        #expect(HTMLEntities.unescape("a & b &unknown; &#zz; &amp") == "a & b &unknown; &#zz; &amp")
        #expect(HTMLEntities.unescape("") == "")
    }

    @Test func decodesOnce() {
        #expect(HTMLEntities.unescape("&amp;quot;") == "&quot;")
    }
}
