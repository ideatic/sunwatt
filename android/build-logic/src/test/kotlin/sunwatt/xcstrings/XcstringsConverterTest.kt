package sunwatt.xcstrings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class XcstringsConverterTest {
    private fun jsonString(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** A catalog where every key has the same text in all six languages unless [overrides] says otherwise. */
    private fun catalog(
        vararg keys: String,
        overrides: Map<String, Map<String, String>> = emptyMap(),
        extra: String = "",
        prefix: String = "t",
    ): XcstringsCatalog {
        val body = keys.joinToString(",") { key ->
            val locs = XcstringsConverter.TRANSLATIONS.joinToString(",") { lang ->
                val v = overrides[key]?.get(lang) ?: key
                "\"$lang\":{\"stringUnit\":{\"state\":\"translated\",\"value\":${jsonString(v)}}}"
            }
            "${jsonString(key)}:{\"extractionState\":\"manual\",\"localizations\":{$locs}$extra}"
        }
        return XcstringsCatalog(prefix, "{\"sourceLanguage\":\"en\",\"strings\":{$body},\"version\":\"1.0\"}")
    }

    private fun english(key: String): String {
        val xml = XcstringsConverter.convert(listOf(catalog(key))).files.getValue("values/strings_t.xml")
        return Regex("<string name=\"[^\"]+\"[^>]*>(.*)</string>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
    }

    @Test fun apostrophe() = assertEquals("plant\\'s data", english("plant's data"))

    @Test fun quotes() = assertEquals("say \\\"hi\\\"", english("say \"hi\""))

    @Test fun curlyQuotesStayAsIs() = assertEquals("“What’s up?”", english("“What’s up?”"))

    @Test fun ampersandAndAngleBrackets() = assertEquals("A &amp; B &lt;c&gt;", english("A & B <c>"))

    @Test fun backslash() = assertEquals("a\\\\b", english("a\\b"))

    @Test fun literalPercent() = assertEquals("100%% sure", english("100% sure"))

    @Test fun doublePercentStaysOnePercent() = assertEquals("100%% sure", english("100%% sure"))

    @Test fun singlePlaceholderStaysUnindexed() {
        assertEquals("%s kW", english("%@ kW"))
        assertEquals("%d W", english("%lld W"))
        assertEquals("%.1f kW", english("%.1f kW"))
    }

    @Test fun mixedPlaceholdersBecomePositional() =
        assertEquals("%1\$s: %2\$d kW", english("%@: %lld kW"))

    @Test fun existingPositionalPlaceholdersKeepTheirIndex() =
        assertEquals("%2\$s then %1\$s", english("%2\$@ then %1\$@"))

    @Test fun singleExplicitPositionalStaysPositional() = assertEquals("%1\$s kW", english("%1\$@ kW"))

    @Test fun percentBeforeSpaceIsNotAFlag() = assertEquals("50%% done", english("50% done"))

    @Test fun leadingAt() = assertEquals("\\@home", english("@home"))

    @Test fun leadingQuestionMark() = assertEquals("\\?", english("?"))

    @Test fun newline() = assertEquals("one\\ntwo", english("one\ntwo"))

    @Test fun leadingAndTrailingSpaces() = assertEquals("\\u0020x\\u0020", english(" x "))

    @Test fun repeatedSpaces() = assertEquals("a \\u0020b", english("a  b"))

    @Test fun unicodeStaysAsIs() = assertEquals("Prix du courant · €/kWh – été", english("Prix du courant · €/kWh – été"))

    @Test fun nameFoldsAccentsAndLimitsWords() {
        assertEquals("electricite_tres_chere_aujourd_hui_ca", XcstringsConverter.slug("Électricité très chère aujourd'hui, ça va être long"))
        val name = XcstringsConverter.convert(listOf(catalog("Électricité très chère"))).names.single().name
        assertEquals("t_electricite_tres_chere", name)
    }

    @Test fun placeholdersAreNotPartOfTheName() {
        assertEquals("active_widgets", XcstringsConverter.slug("Active widgets: %lld"))
        assertEquals("kw_now_kwh_today", XcstringsConverter.slug("%@ kW now · %@ kWh today"))
    }

    @Test fun nameIsAtMost48CharsOfSlug() {
        val slug = XcstringsConverter.slug("Supercalifragilisticexpialidocious Pneumonoultramicroscopicsilicovolcanoconiosis x")
        assertTrue(slug.length <= 48, slug)
        assertFalse(slug.endsWith("_"))
    }

    @Test fun nameOfSymbolOnlyKeyIsNotEmpty() {
        assertEquals("t_text", XcstringsConverter.convert(listOf(catalog("€"))).names.single().name)
    }

    @Test fun collidingNamesBothGetAHashSuffix() {
        val names = XcstringsConverter.convert(listOf(catalog("Hello!", "Hello?", "Other"))).names
        val hello = names.filter { it.key.startsWith("Hello") }
        assertEquals(2, hello.size)
        assertEquals(2, hello.map { it.name }.toSet().size)
        hello.forEach { assertTrue(Regex("t_hello_[0-9a-f]{6}").matches(it.name), it.name) }
        assertEquals("t_other", names.single { it.key == "Other" }.name)
    }

    @Test fun outputIsDeterministicAndSorted() {
        val a = XcstringsConverter.convert(listOf(catalog("Zeta", "Alpha", "Mid")))
        val b = XcstringsConverter.convert(listOf(catalog("Mid", "Zeta", "Alpha")))
        assertEquals(a.files, b.files)
        assertEquals(listOf("t_alpha", "t_mid", "t_zeta"), a.names.map { it.name })
    }

    @Test fun allSevenLanguageFolders() {
        val files = XcstringsConverter.convert(listOf(catalog("Hi"))).files.keys
        assertEquals(
            setOf("values", "values-es", "values-pt", "values-fr", "values-de", "values-it", "values-nl").map { "$it/strings_t.xml" }.toSet(),
            files,
        )
    }

    @Test fun translationsAreUsedPerLanguage() {
        val c = catalog("Hi", overrides = mapOf("Hi" to mapOf("de" to "Hallo", "es" to "Hola")))
        val files = XcstringsConverter.convert(listOf(c)).files
        assertTrue(files.getValue("values-de/strings_t.xml").contains(">Hallo</string>"))
        assertTrue(files.getValue("values-es/strings_t.xml").contains(">Hola</string>"))
        assertTrue(files.getValue("values/strings_t.xml").contains(">Hi</string>"))
    }

    @Test fun englishLocalizationWinsOverTheKey() {
        val json = """{"sourceLanguage":"en","strings":{"key.name":{"localizations":{
            "en":{"stringUnit":{"state":"translated","value":"Real text"}},
            "es":{"stringUnit":{"state":"translated","value":"Texto"}},"pt":{"stringUnit":{"state":"translated","value":"Texto"}},
            "fr":{"stringUnit":{"state":"translated","value":"Texte"}},"de":{"stringUnit":{"state":"translated","value":"Text"}},
            "it":{"stringUnit":{"state":"translated","value":"Testo"}},"nl":{"stringUnit":{"state":"translated","value":"Tekst"}}}}}}"""
        val r = XcstringsConverter.convert(listOf(XcstringsCatalog("t", json)))
        assertEquals("t_real_text", r.names.single().name)
        assertEquals("key.name", r.names.single().key)
        assertTrue(r.files.getValue("values/strings_t.xml").contains(">Real text</string>"))
    }

    @Test fun missingTranslationNamesTableKeyAndLanguage() {
        val json = """{"sourceLanguage":"en","strings":{"Lonely":{"localizations":{
            "es":{"stringUnit":{"state":"translated","value":"a"}},"pt":{"stringUnit":{"state":"translated","value":"a"}},
            "fr":{"stringUnit":{"state":"translated","value":"a"}},"it":{"stringUnit":{"state":"translated","value":"a"}},
            "nl":{"stringUnit":{"state":"translated","value":"a"}}}}}}"""
        val e = assertThrows<XcstringsException> { XcstringsConverter.convert(listOf(XcstringsCatalog("core", json))) }
        val m = e.message!!
        assertTrue("core" in m && "Lonely" in m && "'de'" in m, m)
    }

    @Test fun variationsFailLoudly() {
        val c = catalog("Apples", extra = ",\"variations\":{\"plural\":{}}")
        val e = assertThrows<XcstringsException> { XcstringsConverter.convert(listOf(c)) }
        assertTrue("unsupported variations in Apples" in e.message!!, e.message)
    }

    @Test fun variationsInsideALocalizationFailLoudly() {
        val json = """{"sourceLanguage":"en","strings":{"Apples":{"localizations":{"es":{"variations":{"plural":{}}}}}}}"""
        val e = assertThrows<XcstringsException> { XcstringsConverter.convert(listOf(XcstringsCatalog("t", json))) }
        assertTrue("unsupported variations in Apples" in e.message!!, e.message)
    }

    @Test fun shouldTranslateFalseOnlyInDefaultValues() {
        val json = """{"sourceLanguage":"en","strings":{"SunWatt":{"shouldTranslate":false}}}"""
        val files = XcstringsConverter.convert(listOf(XcstringsCatalog("t", json))).files
        assertTrue(files.getValue("values/strings_t.xml").contains("<string name=\"t_sunwatt\" translatable=\"false\">SunWatt</string>"))
        assertFalse(files.getValue("values-de/strings_t.xml").contains("t_sunwatt"))
    }

    @Test fun catalogCommentAndKeyAreWrittenAsXmlComments() {
        val c = catalog("Hi -- there", extra = ",\"comment\":\"Short button\"")
        val xml = XcstringsConverter.convert(listOf(c)).files.getValue("values/strings_t.xml")
        assertTrue("<!-- Hi - - there -->" in xml, xml)
        assertTrue("<!-- Note: Short button -->" in xml, xml)
    }

    @Test fun duplicatePrefixIsRejected() {
        assertThrows<XcstringsException> { XcstringsConverter.convert(listOf(catalog("A"), catalog("B"))) }
    }
}
