/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers
package smtlib

import _root_.smtlib.trees.Terms.{Identifier => SMTIdentifier, _}
import _root_.smtlib.trees.Commands._
import _root_.smtlib.interpreters.CVC4Interpreter
import _root_.smtlib.theories._
import _root_.smtlib.theories.experimental._

trait CVC5Target extends CVCTarget {
  import context._
  import program._
  import program.trees._
  import program.symbols._

  def targetName = "cvc5"

  protected lazy val interpreter = {
    val opts = interpreterOpts
    reporter.debug("Invoking solver with "+opts.mkString(" "))
    new CVC4Interpreter(targetName, opts.toArray)
  }
}
