package sunwatt.xcstrings

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.DefaultTask
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Where the Apple catalogs are and which table prefix each one gets. */
abstract class XcstringsExtension {
    /** Table prefix to catalog file. */
    abstract val catalogs: MapProperty<String, RegularFile>
}

/** The Apple catalogs that feed the Android app (see `FEATURES.md`, "Texts"). */
internal val DEFAULT_CATALOGS = linkedMapOf(
    "core" to "Packages/SunWattKit/Sources/SunWattKit/Resources/Core.xcstrings",
    "widgets" to "Packages/SunWattKit/Sources/SunWattKit/Resources/Widgets.xcstrings",
    "app" to "Packages/SunWattKit/Sources/SunWattKit/Resources/App.xcstrings",
    "intents" to "Packages/SunWattKit/Sources/SunWattKit/Resources/Intents.xcstrings",
    "watch" to "Packages/SunWattKit/Sources/SunWattKit/Resources/Watch.xcstrings",
    "widgetext" to "Widgets/Resources/Localizable.xcstrings",
)

/** Shared inputs of the tasks that read the catalogs. */
abstract class XcstringsBaseTask : DefaultTask() {
    @get:Internal
    abstract val catalogs: MapProperty<String, RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogFiles: ConfigurableFileCollection

    /** Prefix to file name, so a change of the mapping reruns the task. */
    @get:Input
    abstract val catalogNames: MapProperty<String, String>

    protected fun convert(): XcstringsResult {
        val inputs = catalogs.get().toSortedMap().map { (prefix, file) ->
            val f = file.asFile
            if (!f.isFile) throw XcstringsException("catalog for '$prefix' not found: $f")
            XcstringsCatalog(prefix, f.readText(Charsets.UTF_8), f.name)
        }
        return XcstringsConverter.convert(inputs)
    }
}

/** Writes `values-xx/strings_<table>.xml` files from the catalogs. */
@CacheableTask
abstract class GenerateXcstringsTask : XcstringsBaseTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val result = convert()
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for ((path, content) in result.files) {
            val file = out.resolve(path)
            file.parentFile.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        }
    }
}

/** Prints `name<TAB>key` for every generated string. */
abstract class PrintXcstringsNamesTask : XcstringsBaseTask() {
    @TaskAction
    fun print() {
        convert().names.forEach { println("${it.name}\t${it.key}") }
    }
}

/** Texts only the Android app uses, in the same format and with the same 7 languages (DECISIONS D37). */
internal const val ANDROID_CATALOG = "strings/Android.xcstrings"

class XcstringsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("xcstrings", XcstringsExtension::class.java)
        val appleDir = project.rootProject.layout.projectDirectory.dir("../apple")
        val androidCatalog = project.rootProject.layout.projectDirectory.file(ANDROID_CATALOG)
        extension.catalogs.convention(
            project.provider { DEFAULT_CATALOGS.mapValues { appleDir.file(it.value) } + ("android" to androidCatalog) },
        )

        fun XcstringsBaseTask.wire() {
            catalogs.set(extension.catalogs)
            catalogFiles.from(extension.catalogs.map { it.values })
            catalogNames.set(extension.catalogs.map { m -> m.mapValues { it.value.asFile.name } })
        }

        val generate = project.tasks.register("generateXcstringsResources", GenerateXcstringsTask::class.java) {
            it.group = "sunwatt"
            it.description = "Generates Android string resources from the Apple .xcstrings catalogs."
            it.wire()
            it.outputDir.set(project.layout.buildDirectory.dir("generated/xcstrings/res"))
        }
        project.tasks.register("printXcstringsNames", PrintXcstringsNamesTask::class.java) {
            it.group = "sunwatt"
            it.description = "Prints the resource name and catalog key of every generated string."
            it.wire()
        }

        project.pluginManager.withPlugin("com.android.application") {
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
                .onVariants { variant ->
                    variant.sources.res?.addGeneratedSourceDirectory(generate, GenerateXcstringsTask::outputDir)
                }
        }
    }
}
