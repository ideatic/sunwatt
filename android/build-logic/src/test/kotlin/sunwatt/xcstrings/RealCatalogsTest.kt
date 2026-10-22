package sunwatt.xcstrings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Runs the converter on the real Apple catalogs of this repository. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealCatalogsTest {
    private val languages = listOf("values") + XcstringsConverter.TRANSLATIONS.map { "values-$it" }

    private lateinit var appleDir: File
    private lateinit var result: XcstringsResult

    private fun findApple(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val apple = dir.resolve("apple")
            if (apple.resolve(DEFAULT_CATALOGS.getValue("core")).isFile) return apple
            dir = dir.parentFile
        }
        error("apple/ catalogs not found above ${File("").absolutePath}; run the tests inside the sunwatt repository")
    }

    @BeforeAll
    fun convertAll() {
        appleDir = findApple()
        result = XcstringsConverter.convert(
            DEFAULT_CATALOGS.map { (prefix, path) ->
                val f = appleDir.resolve(path)
                assertTrue(f.isFile, "missing catalog $f")
                XcstringsCatalog(prefix, f.readText(Charsets.UTF_8), f.name)
            },
        )
    }

    /** Resource name to value of one generated file. */
    private fun read(lang: String, table: String): Map<String, String> {
        val text = result.files["$lang/strings_$table.xml"] ?: error("no file $lang/strings_$table.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    @Test
    fun keyCounts() {
        // Intents was 37 in git HEAD and 35 in the working tree when this was written (two keys removed).
        val expected = mapOf("core" to setOf(24), "widgets" to setOf(28), "app" to setOf(191), "intents" to setOf(35, 37), "watch" to setOf(7), "widgetext" to setOf(16))
        for ((table, counts) in expected) {
            val perLang = languages.map { read(it, table).size }
            assertTrue(perLang.all { it in counts }, "$table: $perLang, expected one of $counts")
            assertEquals(1, perLang.toSet().size, "$table: all languages must have the same count: $perLang")
        }
    }

    @Test
    fun nonEnglishFilesHaveTheSameNamesAsDefault() {
        for (table in DEFAULT_CATALOGS.keys) {
            val base = read("values", table).keys
            for (lang in languages.drop(1)) assertEquals(base, read(lang, table).keys, "$lang/$table")
        }
    }

    @Test
    fun namesAreUniqueAndValidResourceNames() {
        val names = result.names.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { Regex("[a-z][a-z0-9_]*").matches(it) })
    }

    @Test
    fun placeholdersMatchTheEnglishOnes() {
        val placeholder = Regex("%(\\d+\\$)?[-+#0]*\\d*(\\.\\d+)?[sdfeg]")
        fun types(v: String) = placeholder.findAll(v).map { it.value.last() }.sorted().toList()
        for (table in DEFAULT_CATALOGS.keys) {
            val english = read("values", table)
            for (lang in languages) {
                for ((name, value) in read(lang, table)) {
                    assertEquals(types(english.getValue(name)), types(value), "$lang/$name")
                    val found = placeholder.findAll(value).toList()
                    if (found.size > 1) {
                        assertTrue(found.all { it.groupValues[1].isNotEmpty() }, "unindexed placeholder in $lang/$name: $value")
                    }
                    // No stray single % left: every % is a placeholder or %%.
                    assertTrue(!Regex("%(?!%|(\\d+\\$)?[-+#0]*\\d*(\\.\\d+)?[sdfeg])").containsMatchIn(value.replace("%%", "")), "$lang/$name: $value")
                }
            }
        }
    }

    @Test
    fun placeholderCountMatchesTheCatalogKey() {
        val keyCounts = result.names.groupBy { it.table }
        for ((table, list) in keyCounts) {
            val english = read("values", table)
            for (n in list) {
                val inKey = Regex("%@|%lld|%ld|%d|%\\d+\\$@").findAll(n.key).count()
                val inXml = Regex("%(\\d+\\$)?[sd]").findAll(english.getValue(n.name)).count()
                // Keys with an "en" localization may differ from the key; none exist today.
                assertEquals(inKey, inXml, "$table/${n.key}")
            }
        }
    }
}
