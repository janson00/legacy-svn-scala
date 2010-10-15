/* NSC -- new Scala compiler
 * Copyright 2005-2010 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package util

import scala.collection.mutable.HashMap

trait FreshNameCreator {
  /** Do not call before after type checking ends.
   *  PP: I think that directive needs to lose a word somewhere.
   */
  def newName(): String
  def newName(prefix: String): String

  @deprecated("use newName(prefix)")
  def newName(pos: util.Position, prefix: String): String = newName(prefix)
  @deprecated("use newName()")
  def newName(pos: util.Position): String = newName()
}

object FreshNameCreator {  
  class Default extends FreshNameCreator {
    protected var counter = 0
    protected val counters = new HashMap[String, Int]

    /**
     * Create a fresh name with the given prefix. It is guaranteed
     * that the returned name has never been returned by a previous
     * call to this function (provided the prefix does not end in a digit).
     */
    def newName(prefix: String): String = {
      val safePrefix = prefix.replace('<', '$').replace('>', '$')
      val count = counters.getOrElse(safePrefix, 0) + 1
      counters(safePrefix) = count
      safePrefix + count
    }
    def newName(): String = {
      counter += 1
      "$" + counter + "$"
    }
  }
}
