package scala.scalajs.runtime

import scala.scalajs.js
import scala.scalajs.js.prim.{String => jsString}

/** Conversions of JavaScript stack traces to Java stack traces.
 */
object StackTrace {

  /** Captures browser-specific state recording the current stack trace.
   *  The state is stored as a magic field of the throwable, and will be used
   *  by `extract()` to create an Array[StackTraceElement].
   */
  def captureState(throwable: Throwable): Unit = {
    captureState(throwable, createException())
  }

  /** Creates a JS Error with the current stack trace state. */
  private def createException(): js.Any = {
    try {
      this.asInstanceOf[js.Dynamic].undef() // it does not exist, that's the point
    } catch {
      case js.JavaScriptException(e) => e
    }
  }

  /** Captures browser-specific state recording the stack trace of a JS error.
   *  The state is stored as a magic field of the throwable, and will be used
   *  by `extract()` to create an Array[StackTraceElement].
   */
  def captureState(throwable: Throwable, e: js.Any): Unit = {
    throwable.asInstanceOf[js.Dynamic].stackdata = e
  }

  /** Tests whether we're running under Rhino. */
  private lazy val isRhino: Boolean = {
    try {
      js.Dynamic.global.Packages.org.mozilla.javascript.JavaScriptException
      true
    } catch {
      case js.JavaScriptException(_) => false
    }
  }

  /** Extracts a throwable's stack trace from captured browser-specific state.
   *  If no stack trace state has been recorded, or if the state cannot be
   *  analyzed in meaningful way (because we don't know the browser), an
   *  empty array is returned.
   */
  def extract(throwable: Throwable): Array[StackTraceElement] =
    extract(throwable.asInstanceOf[js.Dynamic].stackdata)

  /** Extracts a stack trace from captured browser-specific stackdata.
   *  If no stack trace state has been recorded, or if the state cannot be
   *  analyzed in meaningful way (because we don't know the browser), an
   *  empty array is returned.
   */
  def extract(stackdata: js.Dynamic): Array[StackTraceElement] = {
    val lines = normalizeStackTraceLines(stackdata)
    normalizedLinesToStackTrace(lines)
  }

  /* Converts an array of frame entries in normalized form to a stack trace.
   * Each line must have either the format
   *   <functionName>@<fileName>:<lineNumber>:<columnNumber>
   * or
   *   <functionName>@<fileName>:<lineNumber>
   * For some reason, on some browsers, we sometimes have empty lines too.
   * In the rest of the function, we convert the non-empty lines into
   * StackTraceElements.
   */
  private def normalizedLinesToStackTrace(
      lines: js.Array[jsString]): Array[StackTraceElement] = {
    val NormalizedFrameLine = """^([^\@]*)\@(.*):([0-9]+)$""".re
    val NormalizedFrameLineWithColumn = """^([^\@]*)\@(.*):([0-9]+):([0-9]+)$""".re

    val result = new js.Array[StackTraceElement]
    var i = 0
    while (i < lines.length) {
      val line = lines(i)
      if (!(!line)) {
        val mtch1 = NormalizedFrameLineWithColumn.exec(line)
        if (mtch1 ne null) {
          val (className, methodName) = extractClassMethod(mtch1(1).get)
          result.push(STE(className, methodName, mtch1(2).get,
              mtch1(3).get.toInt, mtch1(4).get.toInt))
        } else {
          val mtch2 = NormalizedFrameLine.exec(line)
          if (mtch2 ne null) {
            val (className, methodName) = extractClassMethod(mtch2(1).get)
            result.push(
                STE(className, methodName, mtch2(2).get, mtch2(3).get.toInt))
          } else {
            result.push(STE("<jscode>", line, null, -1)) // just in case
          }
        }
      }
      i += 1
    }
    result
  }

  /** Tries and extract the class name and method from the JS function name.
   *  The recognized patterns are
   *    ScalaJS.c.<encoded class name>.prototype.<encoded method name>
   *    ScalaJS.c.<encoded class name>.<encoded method name>
   *    ScalaJS.i.<encoded trait impl name>__<encoded method name>
   *    ScalaJS.m.<encoded module name>
   *  When the function name is none of those, the pair
   *    ("<jscode>", functionName)
   *  is returned, which will instruct StackTraceElement.toString() to only
   *  display the function name.
   */
  private def extractClassMethod(functionName: String): (String, String) = {
    val PatC = """^ScalaJS\.c\.([^\.]+)(?:\.prototype)?\.([^\.]+)$""".re
    val PatI = """^(?:Object\.)?ScalaJS\.i\.((?:_[^_]|[^_])+)__([^\.]+)$""".re
    val PatM = """^(?:Object\.)?ScalaJS\.m\.([^.\.]+)$""".re

    var isModule = false
    var mtch = PatC.exec(functionName)
    if (mtch eq null) {
      mtch = PatI.exec(functionName)
      if (mtch eq null) {
        mtch = PatM.exec(functionName)
        isModule = true
      }
    }

    if (mtch ne null) {
      val classEncoding = mtch(1).get + (if (isModule) "$" else "")
      val className = classEncoding.replace("_", ".").replace("$und", "_")

      val methodName = if (isModule) {
        "<clinit>" // that's how it would be reported on the JVM
      } else {
        val methodEncoding = mtch(2).get
        if (methodEncoding startsWith "init___") {
          "<init>"
        } else {
          val methodNameLen = methodEncoding.indexOf("__")
          if (methodNameLen < 0) methodEncoding
          else methodEncoding.substring(0, methodNameLen)
        }
      }
      (className, methodName)
    } else {
      ("<jscode>", functionName)
    }
  }

  private def STE(declaringClass: String, methodName: String,
      fileName: String, lineNumber: Int): StackTraceElement =
    new StackTraceElement(declaringClass, methodName, fileName, lineNumber)

  private def STE(declaringClass: String, methodName: String,
      fileName: String, lineNumber: Int, columnNumber: Int): StackTraceElement = {
    val ste = STE(declaringClass, methodName, fileName, lineNumber)

    // Store column in magic field
    ste.asInstanceOf[js.Dynamic].columnNumber = columnNumber

    ste
  }


  private implicit class StringRE(val s: String) extends AnyVal {
    def re: js.RegExp = new js.RegExp(s)
    def re(mods: String): js.RegExp = new js.RegExp(s, mods)
  }

  /* ---------------------------------------------------------------------------
   * Start copy-paste-translate from stacktrace.js
   *
   * From here on, most of the code has been copied from
   * https://github.com/stacktracejs/stacktrace.js
   * and translated to Scala.js almost literally, with some adaptations.
   *
   * Most comments -and lack thereof- have also been copied therefrom.
   */

  private def normalizeStackTraceLines(e: js.Dynamic): js.Array[jsString] = {
    /* You would think that we could test once and for all which "mode" to
     * adopt. But the format can actually differ for different exceptions
     * on some browsers, e.g., exceptions in Chrome there may or may not have
     * arguments or stack.
     */
    if (!e) {
      js.Array[jsString]()
    } else if (isRhino) {
      extractRhino(e)
    } else if (!(!e.arguments) && !(!e.stack)) {
      extractChrome(e)
    } else if (!(!e.stack) && !(!e.sourceURL)) {
      extractSafari(e)
    } else if (!(!e.stack) && !(!e.number)) {
      extractIE(e)
    } else if (!(!e.stack) && !(!e.fileName)) {
      extractFirefox(e)
    } else if (!(!e.message) && !(!e.`opera#sourceloc`)) {
      // e.message.indexOf("Backtrace:") > -1 -> opera9
      // 'opera#sourceloc' in e -> opera9, opera10a
      // !e.stacktrace -> opera9
      if (!e.stacktrace) {
        extractOpera9(e) // use e.message
      } else if ((e.message.indexOf("\n") > -1) &&
          (e.message.split("\n").length > e.stacktrace.split("\n").length)) {
        // e.message may have more stack entries than e.stacktrace
        extractOpera9(e) // use e.message
      } else {
        extractOpera10a(e) // use e.stacktrace
      }
    } else if (!(!e.message) && !(!e.stack) && !(!e.stacktrace)) {
      // e.stacktrace && e.stack -> opera10b
      if (e.stacktrace.indexOf("called from line") < 0) {
        extractOpera10b(e)
      } else {
        extractOpera11(e)
      }
    } else if (!(!e.stack) && !e.fileName) {
      /* Chrome 27 does not have e.arguments as earlier versions,
       * but still does not have e.fileName as Firefox */
      extractChrome(e)
    } else {
      extractOther(e)
    }
  }

  private def extractRhino(e: js.Dynamic): js.Array[jsString] = {
    (e.stack.asInstanceOf[js.UndefOr[jsString]]).getOrElse[jsString]("")
      .replace("""^\s+at\s+""".re("gm"), "") // remove 'at' and indentation
      .replace("""^(.+?)(?: \((.+)\))?$""".re("gm"), "$2@$1")
      .replace("""\r\n?""".re("gm"), "\n") // Rhino has platform-dependent EOL's
      .split("\n")
  }

  private def extractChrome(e: js.Dynamic): js.Array[jsString] = {
    (e.stack.asInstanceOf[jsString] + "\n")
      .replace("""^[\s\S]+?\s+at\s+""".re, " at ") // remove message
      .replace("""^\s+(at eval )?at\s+""".re("gm"), "") // remove 'at' and indentation
      .replace("""^([^\(]+?)([\n])""".re("gm"), "{anonymous}() ($1)$2") // see note
      .replace("""^Object.<anonymous>\s*\(([^\)]+)\)""".re("gm"), "{anonymous}() ($1)")
      .replace("""^(.+) \((.+)\)$""".re("gm"), "$1@$2")
      .split("\n")
      .slice(0, -1)

    /* Note: there was a $ next to the \n here in the original code, but it
     * chokes with method names with $'s, which are generated often by Scala.js.
     */
  }

  private def extractFirefox(e: js.Dynamic): js.Array[jsString] = {
    (e.stack.asInstanceOf[jsString])
      .replace("""(?:\n@:0)?\s+$""".re("m"), "")
      .replace("""^(?:\((\S*)\))?@""".re("gm"), "{anonymous}($1)@")
      .split("\n")
  }

  private def extractIE(e: js.Dynamic): js.Array[jsString] = {
    (e.stack.asInstanceOf[jsString])
      .replace("""^\s*at\s+(.*)$""".re("gm"), "$1")
      .replace("""^Anonymous function\s+""".re("gm"), "{anonymous}() ")
      .replace("""^(.+)\s+\((.+)\)$""".re("gm"), "$1@$2")
      .split("\n")
      .slice(1)
  }

  private def extractSafari(e: js.Dynamic): js.Array[jsString] = {
    (e.stack.asInstanceOf[jsString])
      .replace("""\[native code\]\n""".re("m"), "")
      .replace("""^(?=\w+Error\:).*$\n""".re("m"), "")
      .replace("""^@""".re("gm"), "{anonymous}()@")
      .split("\n")
  }

  private def extractOpera9(e: js.Dynamic): js.Array[jsString] = {
    // "  Line 43 of linked script file://localhost/G:/js/stacktrace.js\n"
    // "  Line 7 of inline#1 script in file://localhost/G:/js/test/functional/testcase1.html\n"
    val lineRE = """Line (\d+).*script (?:in )?(\S+)""".re("i")
    val lines = (e.message.asInstanceOf[jsString]).split("\n")
    val result = new js.Array[jsString]

    var i = 2
    val len = lines.length.toInt
    while (i < len) {
      val mtch = lineRE.exec(lines(i))
      if (mtch ne null) {
        result.push("{anonymous}()@" + mtch(2).get + ":" + mtch(1).get
            /* + " -- " + lines(i+1).replace("""^\s+""".re, "") */)
      }
      i += 2
    }

    result
  }

  private def extractOpera10a(e: js.Dynamic): js.Array[jsString] = {
    // "  Line 27 of linked script file://localhost/G:/js/stacktrace.js\n"
    // "  Line 11 of inline#1 script in file://localhost/G:/js/test/functional/testcase1.html: In function foo\n"
    val lineRE = """Line (\d+).*script (?:in )?(\S+)(?:: In function (\S+))?$""".re("i")
    val lines = (e.stacktrace.asInstanceOf[jsString]).split("\n")
    val result = new js.Array[jsString]

    var i = 0
    val len = lines.length.toInt
    while (i < len) {
      val mtch = lineRE.exec(lines(i))
      if (mtch ne null) {
        val fnName = mtch(3).getOrElse("{anonymous}")
        result.push(fnName + "()@" + mtch(2).get + ":" + mtch(1).get
            /* + " -- " + lines(i+1).replace("""^\s+""".re, "")*/)
      }
      i += 2
    }

    result
  }

  private def extractOpera10b(e: js.Dynamic): js.Array[jsString] = {
    // "<anonymous function: run>([arguments not available])@file://localhost/G:/js/stacktrace.js:27\n" +
    // "printStackTrace([arguments not available])@file://localhost/G:/js/stacktrace.js:18\n" +
    // "@file://localhost/G:/js/test/functional/testcase1.html:15"
    val lineRE = """^(.*)@(.+):(\d+)$""".re
    val lines = (e.stacktrace.asInstanceOf[jsString]).split("\n")
    val result = new js.Array[jsString]

    var i = 0
    val len = lines.length.toInt
    while (i < len) {
      val mtch = lineRE.exec(lines(i))
      if (mtch ne null) {
        val fnName = mtch(1).fold("global code")(_ + "()")
        result.push(fnName + "@" + mtch(2).get + ":" + mtch(3).get)
      }
      i += 1
    }

    result
  }

  private def extractOpera11(e: js.Dynamic): js.Array[jsString] = {
    val lineRE = """^.*line (\d+), column (\d+)(?: in (.+))? in (\S+):$""".re
    val lines = (e.stacktrace.asInstanceOf[jsString]).split("\n")
    val result = new js.Array[jsString]

    var i = 0
    val len = lines.length.toInt
    while (i < len) {
      val mtch = lineRE.exec(lines(i))
      if (mtch ne null) {
        val location = mtch(4).get + ":" + mtch(1).get + ":" + mtch(2).get
        val fnName0 = mtch(2).getOrElse("global code")
        val fnName = (fnName0: jsString)
          .replace("""<anonymous function: (\S+)>""".re, "$1")
          .replace("""<anonymous function>""".re, "{anonymous}")
        result.push(fnName + "@" + location
            /* + " -- " + lines(i+1).replace("""^\s+""".re, "")*/)
      }
      i += 2
    }

    result
  }

  private def extractOther(e: js.Dynamic): js.Array[jsString] = {
    js.Array()
  }

  /* End copy-paste-translate from stacktrace.js
   * ---------------------------------------------------------------------------
   */

  /**
   *  Implicit class to access magic column element created in STE
   */
  implicit class ColumnStackTraceElement(ste: StackTraceElement) {
    def getColumnNumber: Int = {
      val num = ste.asInstanceOf[js.Dynamic].columnNumber
      if (!(!num)) num.asInstanceOf[Int]
      else -1 // Not very Scala-ish, but consistent with StackTraceElemnt
    }
  }

}
