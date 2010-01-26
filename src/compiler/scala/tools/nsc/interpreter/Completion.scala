/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author Paul Phillips
 */

//
// TODO, if practical:
//
// 1) Types: val s: String = x.<tab> should only show members which result in a String.
//      Possible approach: evaluate buffer as if current identifier is 
// 2) Implicits: x.<tab> should show not only x's members but those of anything for which
//      there is an implicit conversion from x.
// 4) Imports: after import scala.collection.mutable._, HashMap should be among
//      my top level identifiers.
// 5) Caching: parsing the jars every startup seems wasteful, but experimentally
//      there is little to no gain from caching.

package scala.tools.nsc
package interpreter

import jline._
import java.net.URL
import java.util.{ List => JList }
import java.lang.reflect

trait ForwardingCompletion extends CompletionAware {
  def forwardTo: Option[CompletionAware]
  
  override def completions() = forwardTo map (_.completions()) getOrElse Nil
  override def follow(s: String) = forwardTo flatMap (_ follow s)
}

// REPL completor - queries supplied interpreter for valid
// completions based on current contents of buffer.
class Completion(repl: Interpreter) {
  self =>
  
  import repl.isInitialized
  
  private def asURLs(xs: List[String]) = xs map (x => io.File(x).toURL)
  private def classPath = (
    // compiler jars, scala-library.jar etc.
    (repl.compilerClasspath) :::
    // boot classpath, java.lang.* etc.
    (asURLs(repl.settings.bootclasspath.value split ':' toList))
  )
  
  // the unqualified vals/defs/etc visible in the repl
  val ids = new IdentCompletion(repl)
  // the top level packages we know about
  val pkgs = new PackageCompletion(classPath)
  // members of Predef
  val predef = new StaticCompletion("scala.Predef") {
    override def filterNotFunction(s: String) = (
      (s contains "2") ||
      (s startsWith "wrap") ||
      (s endsWith "Wrapper") ||
      (s endsWith "Ops")
    )
  }
  // members of scala.*
  val scalalang = new pkgs.SubCompletor("scala") with ForwardingCompletion {
    def forwardTo = pkgs follow "scala"
    val arityClasses = {
      val names = List("Tuple", "Product", "Function")
      val expanded = for (name <- names ; index <- 0 to 22 ; dollar <- List("", "$")) yield name + index + dollar
      
      Set(expanded: _*)
    }
    
    override def filterNotFunction(s: String) = {
      val parsed = new Parsed(s)
      
      (arityClasses contains parsed.unqualifiedPart) ||
      (s endsWith "Exception") ||
      (s endsWith "Error")
    }
  }
  // members of java.lang.*
  val javalang = new pkgs.SubCompletor("java.lang") with ForwardingCompletion {
    def forwardTo = pkgs follow "java.lang"
    import reflect.Modifier.isPublic
    private def existsAndPublic(s: String): Boolean = {
      val name = if (s contains ".") s else "java.lang." + s
      val clazz = classForName(name) getOrElse (return false)

      isPublic(clazz.getModifiers)
    }
    override def filterNotFunction(s: String) = {
      (s endsWith "Exception") || 
      (s endsWith "Error") ||
      (s endsWith "Impl") ||
      (s startsWith "CharacterData")
    }
    override def completions() = super.completions() filter existsAndPublic
  }
  val literals = new LiteralCompletion {
    val global = repl.compiler
    val parent = self
  }
  
  // the list of completion aware objects which should be consulted
  val topLevel: List[CompletionAware] = List(ids, pkgs, predef, scalalang, javalang, literals)
  def topLevelFor(buffer: String) = topLevel flatMap (_ completionsFor buffer)
  
  // chasing down results which won't parse
  def execute(line: String): Option[Any] = {
    val parsed = new Parsed(line)
    import parsed._
    
    if (!isQualified) None
    else {
      (ids executionFor buffer) orElse
      (pkgs executionFor buffer)
    }
  }

  // override if history is available
  def lastCommand: Option[String] = None
  
  // jline's entry point
  lazy val jline: ArgumentCompletor = {
    // TODO - refine the delimiters
    //
    //   public static interface ArgumentDelimiter {
    //       ArgumentList delimit(String buffer, int argumentPosition);
    //       boolean isDelimiter(String buffer, int pos);
    //   }    
    val delimiters = new ArgumentCompletor.AbstractArgumentDelimiter {
      // val delimChars = "(){},`; \t".toArray
      val delimChars = "{},`; \t".toArray
      def isDelimiterChar(s: String, pos: Int) = delimChars contains s.charAt(pos)
    }
    
    returning(new ArgumentCompletor(new JLineCompletion, delimiters))(_ setStrict false)
  }
  
  class JLineCompletion extends Completor {
    // For recording the buffer on the last tab hit
    private var lastTab: (String, String) = (null, null)    

    // Does this represent two consecutive tabs?
    def isConsecutiveTabs(buf: String) = (buf, lastCommand orNull) == lastTab
    
    // verbosity goes up with consecutive tabs
    // TODO - actually implement.
    private var verbosity = 0

    // This is jline's entry point for completion.
    override def complete(_buffer: String, cursor: Int, candidates: JList[String]): Int = {
      if (!isInitialized)
        return cursor

      // println("_buffer = %s, cursor = %d".format(_buffer, cursor))
      verbosity = if (isConsecutiveTabs(_buffer)) verbosity + 1 else 0
      lastTab = (_buffer, lastCommand orNull)
      
      // parse the command buffer
      val parsed = new Parsed(_buffer)
      import parsed._

      // modify in place and return the position
      topLevelFor(buffer) foreach (candidates add _)
      position
    }
  }
}
