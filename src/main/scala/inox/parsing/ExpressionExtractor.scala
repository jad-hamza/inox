/* Copyright 2017 EPFL, Lausanne */

package inox
package parsing

trait ExpressionExtractors { self: Interpolator =>

  trait ExpressionExtractor extends Extractor { self: ExprIR.type => 

    implicit val implicitSymbols = symbols

    private case class State(local: Store, global: Store)

    private object State {
      def empty: State = State(Store.empty, Store.empty)
    }

    private type MatchObligation = State => Option[(Store, Match)]

    private def withBindings(bindings: (Seq[inox.Identifier], Seq[String]))(obligation: MatchObligation): MatchObligation = { (state: State) =>
      if (bindings._1.length == bindings._2.length) {
        val newLocal = bindings._1.zip(bindings._2).foldLeft(state.local) {
          case (currentStore, (id, name)) => currentStore.add(id, name)
        }

        obligation(State(newLocal, state.global))
      }
      else {
        None
      }
    }
    private def withBinding(pair: (inox.Identifier, String))(obligation: MatchObligation): MatchObligation = { (state: State) =>
      val (id, name) = pair
      val newLocal = state.local.add(id, name)
      
      obligation(State(newLocal, state.global))
    }
    private def toExprObl(pair: (trees.Expr, Expression)): MatchObligation = { (state: State) => 
      extractOne(pair._1, pair._2)(state)
    }
    private def toTypeObl(pair: (trees.Type, Type)): MatchObligation = { (state: State) => 
      val (tpe, template) = pair
      TypeIR.extract(tpe, template).map((state.global, _))
    }
    private def toOptTypeObl(pair: (trees.Type, Option[Type])): MatchObligation = { (state: State) =>
      val (tpe, optTemplateType) = pair

      if (optTemplateType.isEmpty) {
        Some((state.global, empty))
      }
      else {
        toTypeObl(tpe -> optTemplateType.get)(state)
      }
    }
    private def toExprObls(pair: (Seq[trees.Expr], Seq[Expression])): MatchObligation = { (state: State) =>
      pair match {
        case (Seq(), Seq()) => Some((state.global, empty))
        case (Seq(), _) => None
        case (_, Seq()) => None
        case (_, Seq(ExpressionSeqHole(i), templateRest @ _*)) => {
          val n = pair._1.length - templateRest.length

          if (n < 0) {
            None
          }
          else {
            val (matches, rest) = pair._1.splitAt(n)

            toExprObls(rest -> templateRest)(state) map {
              case (store, matchings) => (store, matching(i, matches) ++ matchings)
            }
          }
        }
        case (Seq(expr, exprRest @ _*), Seq(template, templateRest @ _*)) => for {
          (interGlobal, matchingsHead) <- extract(toExprObl(expr -> template))(state)
          (finalGlobal, matchingsRest) <- extract(toExprObls(exprRest -> templateRest))(State(state.local, interGlobal))
        } yield (finalGlobal, matchingsHead ++ matchingsRest)
      }
    }
    private def toTypeObls(pair: (Seq[trees.Type], Seq[Type])): MatchObligation = { (state: State) =>
      TypeIR.extractSeq(pair._1, pair._2).map((state.global, _))
    }
    private def toOptTypeObls(pair: (Seq[trees.Type], Seq[Option[Type]])): MatchObligation = { (state: State) =>
      val pairs = pair._1.zip(pair._2).collect {
        case (tpe, Some(template)) => toTypeObl(tpe -> template)
      }
      extract(pairs : _*)(state)
    }

    private def extract(pairs: MatchObligation*)(implicit state: State): Option[(Store, Match)] = {

      val zero: Option[(Store, Match)] = Some((state.global, empty))

      pairs.foldLeft(zero) {
        case (None, _) => None
        case (Some((globalAcc, matchingsAcc)), obligation) => {
          obligation(State(state.local, globalAcc)) map {
            case (newGlobal, extraMatchings) => (newGlobal, matchingsAcc ++ extraMatchings)
          }
        }
      }
    }

    private class Store(val inoxToIr: Map[inox.Identifier, String], val irToInox: Map[String, inox.Identifier]) {

      override def toString = inoxToIr.toString + "\n" + irToInox.toString

      def get(id: inox.Identifier): Option[String] = inoxToIr.get(id)
      def get(name: String): Option[inox.Identifier] = irToInox.get(name)

      def add(id: inox.Identifier, name: String): Store = {

        val optOldName = inoxToIr.get(id)
        val optOldId = irToInox.get(name)

        val newIrToInox = optOldName match {
          case None => irToInox + ((name -> id))
          case Some(oldName) => irToInox - oldName + ((name -> id))
        }

        val newInoxToIR = optOldId match {
          case None => inoxToIr + ((id -> name))
          case Some(oldId) => inoxToIr - oldId + ((id -> name))
        }
        
        new Store(newInoxToIR, newIrToInox)
      }
    }

    private object Store {
      val empty = new Store(Map(), Map())
    }

    def extract(expr: trees.Expr, template: Expression): Option[Match] = extract(toExprObl(expr -> template))(State.empty).map(_._2)

    private def extractOne(expr: trees.Expr, template: Expression)(implicit state: State): Option[(Store, Match)] = {

      val store = state.global
      val success = Some((store, empty))

      template match {
        case ExpressionHole(index) =>
          return Some((store, Map(index -> expr)))
        case TypeAnnotationOperation(templateInner, templateType) =>
          return extract(toTypeObl(expr.getType -> templateType), toExprObl(expr -> templateInner))
        case _ => ()
      }

      expr match {

        // Variables

        case trees.Variable(inoxId, _, _) => template match {
          case Variable(id) => {
            val name = id.getName
            (state.local.get(name), state.local.get(inoxId)) match {
              case (Some(`inoxId`), Some(`name`)) => success  // Locally bound identifier.
              case (None, None) => (store.get(name), store.get(inoxId)) match {
                case (Some(`inoxId`), Some(`name`)) => success  // Globally bound identifier.
                case (None, None) => Some((store.add(inoxId, name), empty)) // Free identifier. We recorder it in the global store.
                case _ => fail
              }
              case _ => fail
            }
          }
          case _ => fail
        }

        // Control structures.

        case trees.IfExpr(cond, thenn, elze) => template match {
          case Operation("IfThenElse", Seq(templateCond, templateThenn, templateElze)) =>
            extract(toExprObl(cond -> templateCond), toExprObl(thenn -> templateThenn), toExprObl(elze -> templateElze))
          case _ => fail
        }

        case trees.Assume(pred, body) => template match {
          case Operation("Assume", Seq(templatePred, templateBody)) =>
            extract(toExprObl(pred -> templatePred), toExprObl(body -> templateBody))
          case _ => fail
        }

        case trees.Let(vd, value, body) => template match {
          case Let(Seq((templateId, optTemplateType, templateValue), rest @ _*), templateBody) => {

            val templateRest = rest match {
              case Seq() => templateBody
              case _ => Let(rest, templateBody)
            }

            extract(toExprObl(value -> templateValue), toOptTypeObl(vd.tpe -> optTemplateType), withBinding(vd.id -> templateId.getName)(toExprObl(body -> templateRest)))
          }
          case _ => fail
        }

        case trees.Lambda(args, body) => template match {
          case Abstraction(Lambda, templateArgs, templateBody) =>
            extract(
              toOptTypeObls(args.map(_.tpe) -> templateArgs.map(_._2)), 
              withBindings(args.map(_.id) -> templateArgs.map(_._1.getName))(toExprObl(body -> templateBody)))
          case _ => fail
        }

        case trees.Forall(args, body) => template match {
          case Abstraction(Forall, templateArgs, templateBody) =>
            extract(
              toOptTypeObls(args.map(_.tpe) -> templateArgs.map(_._2)), 
              withBindings(args.map(_.id) -> templateArgs.map(_._1.getName))(toExprObl(body -> templateBody)))
          case _ => fail
        }

        case trees.Choose(arg, pred) => template match {
          case Abstraction(Choose, Seq((id, optTemplateType), rest @ _*), templatePred) => {
            val templateRest = rest match {
              case Seq() => templatePred
              case _ => Abstraction(Choose, rest, templatePred)
            }

            extract(toOptTypeObl(arg.tpe -> optTemplateType), withBinding(arg.id -> id.getName)(toExprObl(pred -> templateRest)))
          }
          case _ => fail
        }

        // Functions.

        case trees.Application(callee, args) => template match {
          case Application(templateCallee, templateArgs) =>
            extract(toExprObl(callee -> templateCallee), toExprObls(args -> templateArgs))
          case _ => fail
        }

        case trees.FunctionInvocation(id, tpes, args) => template match {
          case Application(TypedFunDef(fd, optTemplatesTypes), templateArgs) if (id == fd.id) => {
            optTemplatesTypes match {
              case None => extract(toExprObls(args -> templateArgs))
              case Some(templateTypes) => extract(toExprObls(args -> templateArgs), toTypeObls(tpes -> templateTypes))
              case _ => fail
            }
          }
          case Application(TypeApplication(ExpressionHole(index), templateTypes), templateArgs) => for {
            (store, matchings) <- extract(toTypeObls(tpes -> templateTypes), toExprObls(args -> templateArgs))
          } yield (store, matching(index, id) ++ matchings)
          case Application(ExpressionHole(index), templateArgs) => for {
            (store, matchings) <- extract(toExprObls(args -> templateArgs))
          } yield (store, matching(index, id) ++ matchings)
          case _ => fail
        }

        // ADTs.

        case trees.ADT(trees.ADTType(id, tpes), args) => template match {
          case Application(TypedConsDef(cons, optTemplatesTypes), templateArgs) if (id == cons.id) => {
            optTemplatesTypes match {
              case None => extract(toExprObls(args -> templateArgs))
              case Some(templateTypes) => extract(toExprObls(args -> templateArgs), toTypeObls(tpes -> templateTypes))
              case _ => fail
            }
          }
          case Application(TypeApplication(ExpressionHole(index), templateTypes), templateArgs) => for {
            (store, matchings) <- extract(toTypeObls(tpes -> templateTypes), toExprObls(args -> templateArgs))
          } yield (store, matching(index, id) ++ matchings)
          case Application(ExpressionHole(index), templateArgs) => for {
            (store, matchings) <- extract(toExprObls(args -> templateArgs))
          } yield (store, matching(index, id) ++ matchings)
          case _ => fail
        }

        case trees.ADTSelector(adt, selector) => template match {
          case Selection(adtTemplate, FieldHole(index)) => for {
            (store, matchings) <- extract(toExprObl(adt -> adtTemplate))
          } yield (store, matching(index, selector) ++ matchings)
          case Selection(adtTemplate, Field((cons, vd))) if (vd.id == selector) =>  // TODO: Handle selectors with the same name.
            extract(toExprObl(adt -> adtTemplate))
          case _ => fail
        }

        // Instance checking and casting.

        case trees.AsInstanceOf(inner, tpe) => template match {
          case AsInstanceOfOperation(templateInner, templateType) =>
            extract(toExprObl(inner -> templateInner), toTypeObl(tpe -> templateType))
          case _ => fail
        }

        case trees.IsInstanceOf(inner, tpe) => template match {
          case IsInstanceOfOperation(templateInner, templateType) =>
            extract(toExprObl(inner -> templateInner), toTypeObl(tpe -> templateType))
          case _ => fail
        }

        // Various.

        case trees.CharLiteral(char) => template match {
          case Literal(CharLiteral(`char`)) => success
          case _ => fail 
        }

        case trees.UnitLiteral() => template match {
          case Literal(UnitLiteral) => success
          case _ => fail
        }

        case trees.Equals(left, right) => template match {
          case Operation("==", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        // Booleans.

        case trees.BooleanLiteral(bool) => template match {
          case Literal(BooleanLiteral(`bool`)) => success
          case _ => fail
        }
        
        case trees.And(exprs) => template match {
          case BooleanAndOperation(templates) =>
            extract(toExprObls(exprs -> templates))
          case _ => fail
        }

        case trees.Or(exprs) => template match {
          case BooleanOrOperation(templates) =>
            extract(toExprObls(exprs -> templates))
          case _ => fail
        }

        case trees.Implies(left, right) => template match {
          case Operation("==>", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.Not(inner) => template match {
          case Operation("!", Seq(templateInner)) => extract(toExprObl(inner -> templateInner))
          case _ => fail
        }

        // Strings.

        case trees.StringLiteral(string) => template match {
          case Literal(StringLiteral(`string`)) => success
          case _ => fail
        }

        case trees.StringConcat(left, right) => template match {
          case ConcatenateOperation(templateLeft, templateRight) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.SubString(string, from, to) => template match {
          case SubstringOperation(templateString, templateFrom, templateTo) =>
            extract(toExprObl(string -> templateString), toExprObl(from -> templateFrom), toExprObl(to -> templateTo))
          case _ => fail
        }

        case trees.StringLength(string) => template match {
          case StringLengthOperation(templateString) => extract(toExprObl(string -> templateString))
          case _ => fail
        }

        // Numbers.

        case trees.IntegerLiteral(value) => template match {
          case Literal(NumericLiteral(string)) if (scala.util.Try(BigInt(string)).toOption == Some(value)) => success
          case _ => fail
        }

        case trees.FractionLiteral(numerator, denominator) => template match {
          case Literal(NumericLiteral(string)) if { val (n, d) = Utils.toFraction(string); n * denominator == d * numerator } => success
          case _ => fail
        }

        case trees.Plus(left, right) => template match {
          case Operation("+", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.Minus(left, right) => template match {
          case Operation("-", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.Times(left, right) => template match {
          case Operation("*", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.Division(left, right) => template match {
          case Operation("/", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.UMinus(inner) => template match {
          case Operation("-", Seq(templateInner)) => extract(toExprObl(inner -> templateInner))
          case _ => fail
        }

        case trees.Remainder(left, right) => template match {
          case Operation("%", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.Modulo(left, right) => template match {
          case Operation("mod", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.LessThan(left, right) => template match {
          case Operation("<", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.GreaterThan(left, right) => template match {
          case Operation(">", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.LessEquals(left, right) => template match {
          case Operation("<=", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.GreaterEquals(left, right) => template match {
          case Operation(">=", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        // Bit vectors.

        case trees.BVLiteral(value, _) => template match {
          case Literal(NumericLiteral(string)) if (scala.util.Try(BigInt(string)).toOption == Some(value)) => success
          case _ => fail
        }

        case trees.BVNot(inner) => template match {
          case Operation("~", Seq(templateInner)) => extract(toExprObl(inner -> templateInner))
          case _ => fail
        }

        case trees.BVOr(left, right) => template match {
          case Operation("|", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
        }

        case trees.BVAnd(left, right) => template match {
          case Operation("&", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.BVXor(left, right) => template match {
          case Operation("^", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.BVShiftLeft(left, right) => template match {
          case Operation("<<", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.BVAShiftRight(left, right) => template match {
          case Operation(">>", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        case trees.BVLShiftRight(left, right) => template match {
          case Operation(">>>", Seq(templateLeft, templateRight)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight))
          case _ => fail
        }

        // Tuples.

        case trees.Tuple(exprs) => template match {
          case Operation("Tuple", templates) =>
            extract(toExprObls(exprs -> templates))
          case _ => fail
        }

        case trees.TupleSelect(inner, index) => template match {
          case Selection(templateInner, TupleField(`index`)) => extract(toExprObl(inner -> templateInner))
          case _ => fail
        }

        // Sets.

        case trees.FiniteSet(elements, tpe) => template match {
          case SetConstruction(templatesElements, optTemplateType) =>
            extract(toExprObls(elements -> templatesElements), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.SetAdd(set, element) => (set.getType(symbols), template) match {
          case (trees.SetType(tpe), SetAddOperation(templateSet, templateElement, optTemplateType)) =>
            extract(toExprObl(set -> templateSet), toExprObl(element -> templateElement), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.ElementOfSet(element, set) => (set.getType(symbols), template) match {
          case (trees.SetType(tpe), ContainsOperation(templateSet, templateElement, optTemplateType)) =>
            extract(toExprObl(set -> templateSet), toExprObl(element -> templateElement), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.SubsetOf(left, right) => (left.getType(symbols), template) match {
          case (trees.SetType(tpe), SubsetOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.SetIntersection(left, right) => (left.getType(symbols), template) match {
          case (trees.SetType(tpe), SetIntersectionOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.SetDifference(left, right) => (left.getType(symbols), template) match {
          case (trees.SetType(tpe), SetDifferenceOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        // Bags.

        case trees.FiniteBag(mappings, tpe) => template match {
          case BagConstruction(Bindings(Seq(), templateMappings), optTemplateType) => {
            val (keys, values) = mappings.unzip
            val (templatesKeys, templatesValues) = templateMappings.unzip

            extract(toExprObls(keys -> templatesKeys), toExprObls(values -> templatesValues), toOptTypeObl(tpe -> optTemplateType))
          }
          case _ => fail
        }

        case trees.BagAdd(bag, element) => (bag.getType(symbols), template) match {
          case (trees.BagType(tpe), BagAddOperation(templateBag, templateElement, optTemplateType)) =>
            extract(toExprObl(bag -> templateBag), toExprObl(element -> templateElement), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.MultiplicityInBag(element, bag) => (bag.getType, template) match {
          case (trees.BagType(tpe), BagMultiplicityOperation(templateBag, templateElement, optTemplateType)) =>
            extract(toExprObl(element -> templateElement), toExprObl(bag -> templateBag), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.BagIntersection(left, right) => (left.getType, template) match {
          case (trees.BagType(tpe), BagIntersectionOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.BagUnion(left, right) => (left.getType, template) match {
          case (trees.BagType(tpe), BagUnionOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        case trees.BagDifference(left, right) => (left.getType, template) match {
          case (trees.BagType(tpe), BagDifferenceOperation(templateLeft, templateRight, optTemplateType)) =>
            extract(toExprObl(left -> templateLeft), toExprObl(right -> templateRight), toOptTypeObl(tpe -> optTemplateType))
          case _ => fail
        }

        // Maps.

        case trees.FiniteMap(pairs, default, keyType, valueType) => template match {
          case MapConstruction(templateDefault, Bindings(Seq(), templatesPairs), optTemplatesTypes) => {

            val (optTemplateKeyType, optTemplateValueType) = optTemplatesTypes match {
              case Some(Right((k, v))) => (Some(k), Some(v))
              case Some(Left(k)) => (Some(k), None)
              case None => (None, None) 
            }

            val (keys, values) = pairs.unzip
            val (templatesKeys, templatesValues) = templatesPairs.unzip

            extract(toExprObls(keys -> templatesKeys), toExprObls(values -> templatesValues), 
              toOptTypeObl(keyType -> optTemplateKeyType), toOptTypeObl(valueType -> optTemplateValueType), toExprObl(default -> templateDefault))
          }
          case _ => fail
        }

        case trees.MapApply(map, key) => (map.getType, template) match {
          case (trees.MapType(keyType, valueType), MapApplyOperation(templateMap, templateKey, optTemplatesTypes)) => {
            val (optTemplateKeyType, optTemplateValueType) = optTemplatesTypes match {
              case Some((k, v)) => (Some(k), Some(v))
              case None => (None, None)
            }

            extract(toExprObl(map -> templateMap), toExprObl(key -> templateKey),
              toOptTypeObl(keyType -> optTemplateKeyType), toOptTypeObl(valueType -> optTemplateValueType))
          }
          case _ => fail
        }

        case trees.MapUpdated(map, key, value) => (map.getType, template) match {
          case (trees.MapType(keyType, valueType), MapUpdatedOperation(templateMap, templateKey, templateValue, optTemplatesTypes)) => {
            val (optTemplateKeyType, optTemplateValueType) = optTemplatesTypes match {
              case Some((k, v)) => (Some(k), Some(v))
              case None => (None, None)
            }

            extract(toExprObl(map -> templateMap), toExprObl(key -> templateKey), toOptTypeObl(keyType -> optTemplateKeyType), 
              toExprObl(value -> templateValue), toOptTypeObl(valueType -> optTemplateValueType))
          }
          case _ => fail
        }

        case _ => fail
      }
    }
  }
}