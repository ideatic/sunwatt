package sunwatt.xcstrings

import java.io.File

/**
 * Command line entry point, for use without Gradle.
 * `XcstringsCli <appleDir> <outDir>` writes the resources; `XcstringsCli <appleDir> --names` prints `name<TAB>key`.
 */
object XcstringsCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: XcstringsCli <appleDir> (<outDir> | --names)")
            kotlin.system.exitProcess(2)
        }
        val apple = File(args[0])
        val result = XcstringsConverter.convert(
            DEFAULT_CATALOGS.map { (prefix, path) ->
                val f = apple.resolve(path)
                XcstringsCatalog(prefix, f.readText(Charsets.UTF_8), f.name)
            },
        )
        if (args[1] == "--names") {
            result.names.forEach { println("${it.name}\t${it.key}") }
            return
        }
        for ((path, content) in result.files) {
            val f = File(args[1]).resolve(path)
            f.parentFile.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }
    }
}
