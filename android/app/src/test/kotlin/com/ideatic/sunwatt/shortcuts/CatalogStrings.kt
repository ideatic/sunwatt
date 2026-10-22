package com.ideatic.sunwatt.shortcuts

import com.ideatic.sunwatt.R
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [AnswerStrings] backed by the generated catalog (`build/generated/xcstrings/res`), so the tests check the real texts
 * of every language without Android. Plain comma-and-"and" joiner instead of the device's list formatter.
 */
class CatalogStrings(override val locale: Locale) : AnswerStrings {
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val texts: Map<String, String> = load(locale.language)

    override fun text(id: Int, vararg args: Any): String {
        val name = names[id] ?: error("no string with id $id")
        val template = texts[name] ?: texts.getValue(name)
        return String.format(locale, template, *args)
    }

    override fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " & " + items.last()
    }

    companion object {
        private val root = File("build/generated/xcstrings/res")

        private fun load(language: String): Map<String, String> {
            val dir = File(root, if (language == "en") "values" else "values-$language")
            val result = HashMap<String, String>()
            dir.listFiles { f -> f.name.startsWith("strings_") }!!.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i)
                    result[node.attributes.getNamedItem("name").nodeValue] = unescape(node.textContent)
                }
            }
            return result
        }

        /** Android string escapes: `\'`, `\"`, `\@`, `\?`, `\\`, `\n`. */
        private fun unescape(raw: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    i++
                    out.append(if (raw[i] == 'n') '\n' else raw[i])
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }
    }
}
