/* Copyright 2009-2016 EPFL, Lausanne */

package inox
package transformers

/** A [[Collector]] that collects path conditions */
trait CollectorWithPC extends TransformerWithPC with Collector {
  type Env = symbols.Path
  lazy val initEnv = symbols.Path.empty
}

object CollectorWithPC {
  def apply[T](t: ast.Trees)
              (s: t.Symbols)
              (f: PartialFunction[(t.Expr, s.Path), T]):
               CollectorWithPC { type Result = T; val trees: t.type; val symbols: s.type } = {
    new CollectorWithPC {
      type Result = T
      val trees: t.type = t
      val symbols: s.type = s

      private val fLifted = f.lift

      protected def step(e: t.Expr, env: s.Path): List[T] = {
        fLifted((e, env)).toList
      }
    }
  }

  def apply[T](p: Program)
              (f: PartialFunction[(p.trees.Expr, p.symbols.Path), T]):
               CollectorWithPC { type Result = T; val trees: p.trees.type; val symbols: p.symbols.type } = {
    apply(p.trees)(p.symbols)(f)
  }
}
