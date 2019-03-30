package cc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import jdk.internal.org.objectweb.asm.ClassReader
import jdk.internal.org.objectweb.asm.Label
import jdk.internal.org.objectweb.asm.util.Textifier
import jdk.internal.org.objectweb.asm.util.TraceClassVisitor
import net.lingala.zip4j.core.ZipFile
import org.objectweb.asm.Opcodes.ASM5
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.PrintWriter
import java.nio.file.*
import java.nio.file.Files.readAllBytes
import java.nio.file.Files.walkFileTree
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import kotlin.system.exitProcess

fun main(args: Array<String>) = CC().main(args)

private val BooleanChoice: Array<Pair<String, Boolean>> = arrayOf(true.toString() to true, false.toString() to false)

private fun getSystem(param: String, default: Boolean) = (System.getProperty(param)?.toBoolean() ?: default)

class CC : CliktCommand() {
  private val compareOther: Boolean by option(help = "Compare Other").choice(*BooleanChoice).default(getSystem("compare.other", false))
  private val compareClasses: Boolean by option(help = "Compare Classes").choice(*BooleanChoice).default(true)
  private val compareMethodBodies: Boolean by option(help = "Compare Method Bodies").choice(*BooleanChoice).default(getSystem("compare.methods", false))
  private val ignoreLineNumbers: Boolean by option(help = "Ignore Line Numbers").choice(*BooleanChoice).default(true)

  private val first: Path by argument(help = "First dir/zip to analyze").path(exists = true, fileOkay = true, folderOkay = true)
  private val second: Path by argument(help = "Second dir/zip to analyze").path(exists = true, fileOkay = true, folderOkay = true)
  private val diff: Path by argument(help = "Dir for diff results").path(exists = false, fileOkay = false, folderOkay = true).default(Paths.get("diff"))

  override fun run() {
    val classMatcher = FileSystems.getDefault().getPathMatcher("glob:*.{class}")

    val orig: Path = getOrExtract(first)
    val inc: Path = getOrExtract(second)

    val diff = diff.toFile()
    diff.deleteRecursively()
    diff.mkdir()
    val diffClasses = File(diff, "classes")
    diffClasses.mkdir()

    val diffClassNames = ArrayList<String>()
    var allFiles = 0
    var classFiles = 0
    walkFileTree(orig, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes?): FileVisitResult {
        if (orig.relativize(dir).nameCount == 2) {
          status(dir)
        }
        return super.preVisitDirectory(dir, attrs)
      }

      override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
        val relativize = orig.relativize(path)

        val inInc = inc.resolve(relativize)
        allFiles++

        val fileName: Path? = path.fileName
        if (compareClasses && classMatcher.matches(fileName)) {
          classFiles++

          if (!inInc.toFile().exists()) tc("M $inInc")
          else {
            val o = decompile(path, compareMethodBodies, ignoreLineNumbers)
            val s = decompile(inInc, compareMethodBodies, ignoreLineNumbers)
            if (sortAndTrim(o) != sortAndTrim(s)) {
              val fn = fileName.toString()
              diffClassNames.add(fn)
              File(diff, "$fn.o.txt").writeText(o)
              File(diff, "$fn.i.txt").writeText(s)
              path.toFile().copyTo(File(diffClasses, "$fn.o.class"))
              inInc.toFile().copyTo(File(diffClasses, "$fn.i.class"))
              tc("D $inInc")
            }
          }
        } else if (compareOther) {
          if (!inInc.toFile().exists()) tc("M $inInc")
          else if (!Arrays.equals(readAllBytes(path), readAllBytes(inInc))) tc("D $inInc")
        }

        return FileVisitResult.CONTINUE
      }
    })

    tc("Total files: $allFiles")
    tc("Total class files: $classFiles")
    val error = diffClassNames.isNotEmpty()
    val severity = if (error) "ERROR" else "WARNING"
    val message = "${diffClassNames.size} different classes: " + diffClassNames.take(10).joinToString()
    tc(message, severity)
    if (error) {
      status(message, severity)
      error(1, message)
    } else {
      status("Compared $classFiles classes")
    }
  }
}

fun getOrExtract(path: Path): Path {
  if (!Files.exists(path)) error(2, "'$path' does not exists, expected directory or zip file")
  if (Files.isDirectory(path)) return path
  if (Files.isRegularFile(path) && arrayOf(".zip", ".jar").any { path.endsWith(it) }) {
    val dest = path.resolveSibling(path.fileName.toString().substringBeforeLast("."))
    extract(path, dest)
    return dest
  }
  error(2, "'$path' have unsupported archive type, expected directory or zip file")
}

fun error(code: Int, message: String): Nothing {
  System.err.println(message)
  exitProcess(code)
}

private fun sortAndTrim(o: String) = o
    .split("\n")
    .filter { !it.contains("private transient synthetic Lgroovy/lang/MetaClass; metaClass") }
    .filter { !it.contains("// access flags") }
    .filter { !it.contains("// signature") }
    .filter { !it.contains("// declaration") }
    .filter { !it.contains("synthetic ") }
    .filter { !it.contains("@Lkotlin/Metadata;") }
    .filter { !it.contains("    LOCALVARIABLE ") }
    .map { it.replace("  implements groovy/lang/GroovyObject", "") }
    .map { if (it.contains("implements") && it.contains(" groovy/lang/GroovyObject")) it.replace(" groovy/lang/GroovyObject", "") else it }
    .filter { !it.isEmpty() }
    .sorted()
    .joinToString("\n")

private fun tc(message: Any?, status: String = "WARNING") {
  println("##teamcity[message text='$message' status='$status']")
}

private fun status(message: Any?, status: String? = null) {
  println(
      if (status == null) "##teamcity[buildStatus text='$message']"
      else "##teamcity[buildStatus text='$message' status='$status']")
}

private fun decompile(path: Path?, compareMethodBodies: Boolean, ignoreLineNumbers: Boolean): String {
  FileInputStream(path?.toFile()).use { fileInputStream ->
    try {
      val classReader = ClassReader(fileInputStream)
      val byteArrayOutputStream = ByteArrayOutputStream()
      val traceClassVisitor = TraceClassVisitor(null, object : Textifier(ASM5) {
        override fun createTextifier(): Textifier {
         return object : Textifier(ASM5) {
           override fun getText(): MutableList<Any> {
             return if (compareMethodBodies) super.getText() else Arrays.asList()
           }

           override fun visitLineNumber(p0: Int, p1: Label?) {
             if (ignoreLineNumbers) return
             super.visitLineNumber(p0, p1)
           }
         }
        }

        override fun visitSource(file: String?, debug: String?) { // don't print debug info from Kotlin
        }

        override fun visitInnerClass(p0: String?, p1: String?, p2: String?, p3: Int) { // don't print public static abstract INNERCLASS *
        }
      }, PrintWriter(byteArrayOutputStream))
      classReader.accept(traceClassVisitor, ClassReader.SKIP_DEBUG and ClassReader.SKIP_CODE and ClassReader.SKIP_FRAMES)
      return byteArrayOutputStream.toString()
    }
    catch(e: Exception) {
      println(e.message)
      e.printStackTrace()
      return "<null>"
    }
  }
}

private fun extract(file: Path, dest: Path) {
  println("Deleting $dest ...")
  Files.delete(dest)
  println("Extracting $file ...")
  ZipFile(file.toAbsolutePath().toFile()).extractAll(dest.toAbsolutePath().toString())
  println("Done processing ${file.fileName}")
}
