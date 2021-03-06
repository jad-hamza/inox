/* Copyright 2009-2018 EPFL, Lausanne */

package inox
package evaluators

trait EncodingEvaluator extends DeterministicEvaluator { self =>
  import program.trees._

  protected val encoder: transformers.ProgramTransformer { val sourceProgram: program.type }

  protected val underlying: DeterministicEvaluator {
    val program: encoder.targetProgram.type
  }

  lazy val context = underlying.context

  def eval(expr: Expr, model: program.Model): EvaluationResult = {
    val res = underlying.eval(encoder.encode(expr), model.encode(encoder))

    res match {
      case EvaluationResults.Successful(v) => EvaluationResults.Successful(encoder.decode(v))
      case EvaluationResults.RuntimeError(msg) => EvaluationResults.RuntimeError(msg)
      case EvaluationResults.EvaluatorError(msg) => EvaluationResults.EvaluatorError(msg)
    }
  }
}

object EncodingEvaluator {
  def apply(p: Program)
           (enc: transformers.ProgramTransformer { val sourceProgram: p.type })
           (ev: DeterministicEvaluator { val program: enc.targetProgram.type }) = {
    new {
      val program: p.type = p
    } with EncodingEvaluator {
      val encoder: enc.type = enc
      val underlying = ev
    }
  }
}
