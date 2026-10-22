package sunwatt.xcstrings

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class XcstringsPluginTest {
    @Test
    fun appliesAndRegistersTasks(@TempDir dir: File) {
        val project = ProjectBuilder.builder().withProjectDir(dir.resolve("android")).build()
        project.pluginManager.apply("sunwatt.xcstrings")
        assertTrue(project.tasks.findByName("generateXcstringsResources") is GenerateXcstringsTask)
        assertTrue(project.tasks.findByName("printXcstringsNames") is PrintXcstringsNamesTask)
    }

    @Test
    fun taskWritesTheResourcesOfTheConfiguredCatalogs(@TempDir dir: File) {
        val catalog = dir.resolve("Tiny.xcstrings")
        val langs = XcstringsConverter.TRANSLATIONS.joinToString(",") {
            "\"$it\":{\"stringUnit\":{\"state\":\"translated\",\"value\":\"x $it\"}}"
        }
        catalog.writeText("{\"sourceLanguage\":\"en\",\"strings\":{\"Hello\":{\"localizations\":{$langs}}}}")

        val project = ProjectBuilder.builder().withProjectDir(dir.resolve("android")).build()
        project.pluginManager.apply("sunwatt.xcstrings")
        val extension = project.extensions.getByType(XcstringsExtension::class.java)
        extension.catalogs.set(mapOf("tiny" to project.layout.projectDirectory.file(catalog.absolutePath)))

        val task = project.tasks.getByName("generateXcstringsResources") as GenerateXcstringsTask
        task.generate()
        val out = task.outputDir.get().asFile
        assertEquals(7, out.listFiles()!!.size)
        assertTrue(out.resolve("values-nl/strings_tiny.xml").readText().contains("<string name=\"tiny_hello\">x nl</string>"))
    }
}
