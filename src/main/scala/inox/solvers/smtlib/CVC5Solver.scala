/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package solvers
package smtlib

import inox.OptionParsers._

object optCVC5Options extends SetOptionDef[String] {
  val name = "solver:cvc5"
  val default = Set[String]()
  val elementParser = stringParser
  val usageRhs = "<cvc5-opt>"
}

trait CVC5Solver extends SMTLIBSolver with CVC5Target {
  import context._
  import program.trees._
  import SolverResponses._

  def interpreterOpts = {
    Seq(
      "-q",
      "--produce-models",
      "--incremental",
      "--print-success",
      "--lang", "smt2.6"
    ) ++ options.findOptionOrDefault(optCVC4Options)
  }

  override def checkAssumptions(config: Configuration)(assumptions: Set[Expr]) = {
    push()
    for (cl <- assumptions) assertCnstr(cl)
    val res: SolverResponse[Model, Assumptions] = check(Model min config)
    pop()

    config.cast(res match {
      case Unsat if config.withUnsatAssumptions =>
        UnsatWithAssumptions(Set.empty)
      case _ => res
    })
  }
}
