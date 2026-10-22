package sunwatt.xcstrings

import java.security.MessageDigest
import java.text.Normalizer

/** One `.xcstrings` file to convert. [prefix] is the resource name prefix and the table name. */
class XcstringsCatalog(
    val prefix: String,
    val json: String,
    /** Shown in the generated file header and in errors, for example `Core.xcstrings`. */
    val sourceName: String = "$prefix.xcstrings",
)

/** One generated string: resource [name] in table [table] for the catalog [key]. */
data class XcstringsName(val table: String, val name: String, val key: String)

class XcstringsResult(
    /** Relative path (`values-es/strings_core.xml`) to file content. Sorted. */
    val files: Map<String, String>,
    /** Every generated string, sorted by table and name. */
    val names: List<XcstringsName>,
)

/** The catalogs cannot be converted without losing data or texts. The message lists every problem. */
class XcstringsException(message: String) : RuntimeException(message)

/**
 * Converts Apple string catalogs into Android `strings.xml` files.
 * Pure: no Gradle types, no file access. Same input gives the same output.
 */
object XcstringsConverter {
    val TRANSLATIONS = listOf("es", "pt", "fr", "de", "it", "nl")
    const val SOURCE_LANGUAGE = "en"

    private const val MAX_WORDS = 6
    private const val MAX_SLUG = 48

    fun convert(catalogs: List<XcstringsCatalog>): XcstringsResult {
        val errors = mutableListOf<String>()
        val files = sortedMapOf<String, String>()
        val names = mutableListOf<XcstringsName>()

        val prefixes = catalogs.map { it.prefix }
        prefixes.groupBy { it }.filter { it.value.size > 1 }.keys.forEach {
            errors += "table prefix '$it' is used by more than one catalog"
        }

        for (catalog in catalogs) {
            val entries = readCatalog(catalog, errors)
            for (entry in entries) names += XcstringsName(catalog.prefix, entry.name, entry.key)
            val fileName = "strings_${catalog.prefix}.xml"
            files["values/$fileName"] = render(catalog, entries, null)
            for (lang in TRANSLATIONS) {
                files["values-$lang/$fileName"] = render(catalog, entries, lang)
            }
        }

        if (errors.isNotEmpty()) {
            throw XcstringsException(
                "xcstrings conversion failed (${errors.size} problem(s)):\n" +
                    errors.joinToString("\n") { "  - $it" },
            )
        }
        names.sortWith(compareBy({ it.table }, { it.name }))
        return XcstringsResult(files, names)
    }

    // region Reading

    private class Entry(
        val key: String,
        val name: String,
        val english: String,
        val translatable: Boolean,
        val comment: String?,
        val translations: Map<String, String>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun readCatalog(catalog: XcstringsCatalog, errors: MutableList<String>): List<Entry> {
        val table = catalog.prefix
        val root = try {
            Json.parse(catalog.json) as? Map<String, Any?>
        } catch (e: IllegalArgumentException) {
            errors += "$table: ${catalog.sourceName}: ${e.message}"
            return emptyList()
        }
        if (root == null) {
            errors += "$table: ${catalog.sourceName} is not a JSON object"
            return emptyList()
        }
        if (root["sourceLanguage"] != SOURCE_LANGUAGE) {
            errors += "$table: sourceLanguage must be '$SOURCE_LANGUAGE', found '${root["sourceLanguage"]}'"
        }
        val strings = root["strings"] as? Map<String, Any?> ?: emptyMap()

        class Raw(val key: String, val english: String, val translatable: Boolean, val comment: String?, val translations: Map<String, String>)

        val raws = mutableListOf<Raw>()
        for ((key, value) in strings) {
            val item = value as? Map<String, Any?> ?: emptyMap()
            val localizations = item["localizations"] as? Map<String, Any?> ?: emptyMap()
            val unsupported = item.containsKey("variations") || item.containsKey("substitutions") ||
                localizations.values.any {
                    val l = it as? Map<String, Any?>
                    l == null || l.containsKey("variations") || l.containsKey("substitutions") || !l.containsKey("stringUnit")
                }
            if (unsupported) {
                errors += "$table: unsupported variations in $key"
                continue
            }
            fun text(lang: String): String? =
                ((localizations[lang] as? Map<String, Any?>)?.get("stringUnit") as? Map<String, Any?>)?.get("value") as? String

            val translatable = item["shouldTranslate"] != false
            val english = text(SOURCE_LANGUAGE) ?: key
            val translations = mutableMapOf<String, String>()
            if (translatable) {
                for (lang in TRANSLATIONS) {
                    val t = text(lang)
                    if (t == null) errors += "$table: key '$key' has no '$lang' translation" else translations[lang] = t
                }
            }
            raws += Raw(key, english, translatable, item["comment"] as? String, translations)
        }

        // Names: slug of the English text; any clash inside the table suffixes all clashing keys.
        val base = raws.map { "${table}_${slug(it.english)}" }
        val clashes = base.groupBy { it }.filter { it.value.size > 1 }.keys
        val named = raws.mapIndexed { i, r ->
            val name = if (base[i] in clashes) "${base[i]}_${sha1(r.key).take(6)}" else base[i]
            Entry(r.key, name, r.english, r.translatable, r.comment, r.translations)
        }
        named.groupBy { it.name }.filter { it.value.size > 1 }.forEach { (name, list) ->
            errors += "$table: resource name '$name' is used by ${list.size} keys even with a hash suffix"
        }
        return named.sortedBy { it.name }
    }

    internal fun slug(english: String): String {
        val folded = Normalizer.normalize(PLACEHOLDER.replace(english, " "), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ß", "ss")
            .replace("ø", "o").replace("Ø", "O")
            .replace("æ", "ae").replace("Æ", "AE")
            .replace("œ", "oe").replace("Œ", "OE")
            .replace("ł", "l").replace("Ł", "L")
            .lowercase()
        val words = folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val slug = words.take(MAX_WORDS).joinToString("_").take(MAX_SLUG).trimEnd('_')
        return slug.ifEmpty { "text" }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // endregion

    // region Writing

    private fun render(catalog: XcstringsCatalog, entries: List<Entry>, lang: String?): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<!-- Generated from ").append(xmlComment(catalog.sourceName))
            .append(" by sunwatt.xcstrings. Do not edit. -->\n")
        sb.append("<resources>\n")
        for (e in entries) {
            val text = if (lang == null) e.english else e.translations[lang] ?: continue
            sb.append("    <!-- ").append(xmlComment(e.key)).append(" -->\n")
            if (e.comment != null) sb.append("    <!-- Note: ").append(xmlComment(e.comment)).append(" -->\n")
            sb.append("    <string name=\"").append(e.name).append('"')
            if (!e.translatable) sb.append(" translatable=\"false\"")
            sb.append('>').append(androidValue(text)).append("</string>\n")
        }
        sb.append("</resources>\n")
        return sb.toString()
    }

    private fun xmlComment(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().replace("--", "- -").trimEnd('-')

    // endregion

    // region Text conversion

    private val PLACEHOLDER = Regex("%(?:(\\d+)\\$)?([-+#0]*)(\\d*)(?:\\.(\\d+))?(lld|llu|ld|lu|d|i|u|@|f|e|g|s)|%%|%")

    /** Placeholders, `%` and Android escaping in one pass. */
    internal fun androidValue(text: String): String {
        val matches = PLACEHOLDER.findAll(text).toList()
        val real = matches.filter { it.groupValues[5].isNotEmpty() }
        val positional = real.size > 1 || real.any { it.groupValues[1].isNotEmpty() }

        val out = StringBuilder()
        var last = 0
        var next = 1
        for (m in matches) {
            out.append(escape(text.substring(last, m.range.first), out.isEmpty()))
            last = m.range.last + 1
            val conv = m.groupValues[5]
            if (conv.isEmpty()) {
                out.append("%%")
                continue
            }
            val explicit = m.groupValues[1]
            val index = if (explicit.isNotEmpty()) explicit.toInt() else next++
            out.append('%')
            if (positional) out.append(index).append('$')
            out.append(m.groupValues[2]).append(m.groupValues[3])
            if (m.groupValues[4].isNotEmpty()) out.append('.').append(m.groupValues[4])
            out.append(
                when (conv) {
                    "@", "s" -> 's'
                    "lld", "llu", "ld", "lu", "d", "i", "u" -> 'd'
                    else -> conv[0] // f, e, g
                },
            )
        }
        out.append(escape(text.substring(last), out.isEmpty()))
        return preserveSpaces(out.toString())
    }

    /** Escapes one literal part for a `strings.xml` value. [atStart] is true for the start of the value. */
    private fun escape(part: String, atStart: Boolean): String {
        val sb = StringBuilder()
        for ((i, c) in part.withIndex()) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                '@', '?' -> {
                    if (atStart && i == 0) sb.append('\\')
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Android trims unquoted text and folds runs of spaces: keep leading, trailing and repeated spaces. */
    private fun preserveSpaces(escaped: String): String {
        val sb = StringBuilder()
        for ((i, c) in escaped.withIndex()) {
            val keep = c == ' ' && (i == 0 || i == escaped.length - 1 || escaped[i - 1] == ' ')
            if (keep) sb.append("\\u0020") else sb.append(c)
        }
        return sb.toString()
    }

    // endregion
}
