package welder
package parsing

import scala.util.parsing.combinator._
import scala.util.parsing.combinator.syntactical._
import scala.util.parsing.combinator.token._

import inox.{InoxProgram, FreshIdentifier}

import welder.parsing._

class ExpressionParser(program: InoxProgram) extends TypeParser(program) { self =>

  import lexical.{Identifier => _, Quantifier => _, _}

  val eir = new ExprIR(program)
  
  import eir._
  import eir.program.trees

  lazy val expression: Parser[Expression] = (greedyRight | operatorExpr) withFailureMessage "Expression expected."
  lazy val nonOperatorExpr: Parser[Expression] = withPrefix(greedyRight | selectionExpr)

  lazy val selectableExpr: Parser[Expression] = withApplication {
    invocationExpr | literalExpr | variableExpr | literalSetLikeExpr | tupleOrParensExpr
  }

  def withApplication(exprParser: Parser[Expression]): Parser[Expression] =
    for {
      expr <- exprParser
      argss <- rep(arguments) 
    } yield {
      argss.foldLeft(expr) {
        case (acc, args) => Application(acc, args)
      }
    }

  lazy val prefix: Parser[Expression => Expression] = unaryOps.map({
    (op: String) => elem(Operator(op)) ^^^ { (x: Expression) => Operation(op, Seq(x)) }
  }).reduce(_ | _)

  def withPrefix(exprParser: Parser[Expression]): Parser[Expression] =
    for {
      pres <- rep(prefix)
      expr <- exprParser
    } yield {
      pres.foldRight(expr) {
        case (pre, acc) => pre(acc)
      }
    }

  lazy val selectionExpr: Parser[Expression] = {
      
    val selector = for {
      i <- selectorIdentifier
      targs <- opt(typeArguments)
      argss <- rep(arguments) 
    } yield { (expr: Expression) =>
      val zero: Expression = if (targs.isDefined) {
        TypeApplication(Selection(expr, i), targs.get)
      } else {
        Selection(expr, i)
      } 

      argss.foldLeft(zero) {
        case (acc, args) => Application(acc, args)
      }
    }

    selectableExpr ~ rep(kw(".") ~> selector) ^^ {
      case expr ~ funs => funs.foldLeft(expr) {
        case (acc, f) => f(acc)
      }
    }
  } withFailureMessage "Expression expected."

  lazy val selectorIdentifier: Parser[Field] = acceptMatch("Selector expected.", {
    case lexical.Identifier(name) => FieldName(name)
    case RawIdentifier(i) => FieldIdentifier(i)
  })

  lazy val greedyRight: Parser[Expression] = quantifierExpr | ifExpr | letExpr

  lazy val ifExpr: Parser[Expression] = for {
    _ <- kw("if")
    c <- parensExpr
    t <- expression
    _ <- kw("else")
    e <- expression
  } yield Operation("IfThenElse", Seq(c, t, e))

  lazy val letExpr: Parser[Expression] = for {
    _  <- kw("let")
    bs <- rep1sep(for {
        v <- valDef
        _ <- kw("=")
        e <- expression
      } yield (v._1, v._2, e), p(',')) 
    _  <- kw("in")
    bd <- expression
  } yield Let(bs, bd)

  lazy val literalExpr: Parser[Expression] = acceptMatch("Literal expected.", {
    case Keyword("true")  => BooleanLiteral(true)
    case Keyword("false") => BooleanLiteral(false)
    case StringLit(s) => StringLiteral(s)
    case NumericLit(n) => NumericLiteral(n)
    case RawExpr(e) => EmbeddedExpr(e)
  }) ^^ (Literal(_))

  lazy val variableExpr: Parser[Expression] = identifier ^^ (Variable(_))

  lazy val identifier: Parser[Identifier] = acceptMatch("Identifier expected.", {
    case lexical.Identifier(name) => IdentifierName(name)
    case RawIdentifier(i) => IdentifierIdentifier(i)
  })

  lazy val parensExpr: Parser[Expression] = 
    (p('(') ~> expression <~ p(')'))

  lazy val tupleOrParensExpr: Parser[Expression] =
    p('(') ~> rep1sep(expression, p(',')) <~ p(')') ^^ {
      case Seq(e) => e
      case es => Operation("Tuple", es)
    }

  def repsepOnce[A, B](parser: Parser[A], sep: Parser[Any], once: Parser[B]): Parser[(Option[B], Seq[A])] = {
    opt(rep1sepOnce(parser, sep, once)) ^^ {
      case None => (None, Seq())
      case Some(t) => t
    }
  }

  def rep1sepOnce[A, B](parser: Parser[A], sep: Parser[Any], once: Parser[B]): Parser[(Option[B], Seq[A])] =
    { 
      for {
        a <- parser
        o <- opt(sep ~> rep1sepOnce(parser, sep, once))
      } yield o match {
        case None => (None, Seq(a))
        case Some((ob, as)) => (ob, a +: as)
      }
    } | {
      for {
        b <- once
        o <- opt(sep ~> rep1sep(parser, sep))
      } yield o match {
        case None => (Some(b), Seq())
        case Some(as) => (Some(b), as)
      }
    }


  lazy val literalSetLikeExpr: Parser[Expression] =
    p('{') ~> repsepOnce(expression, p(','), defaultMap) <~ p('}') ^^ {
      case (None, as) => Operation("Set", as)
      case (Some((d, None)), as) => Operation("Map", d +: as)
      case (Some((d, Some(t))), as) => TypeApplication(Operation("Map", d +: as), Seq(t))
    }

  lazy val defaultMap: Parser[(Expression, Option[Type])] =
    for {
      _ <- elem(Operator("*"))
      ot <- opt(p(':') ~> inoxType)
      _ <- elem(Operator("->"))
      e <- expression
    } yield (e, ot)

  lazy val fdTable = symbols.functions.keys.toSet

  lazy val cstrTable = symbols.adts.toSeq.collect({
    case (i, cstr: trees.ADTConstructor) => i
  }).toSet

  val symbolTable = fdTable ++ cstrTable

  lazy val symbol: Parser[Expression] = acceptMatch("Symbol expected.", {
    case lexical.Identifier(name) if eir.bi.names.contains(name) => Literal(Name(name))
    case lexical.Identifier(name) if symbolTable.map(_.name).contains(name) => Literal(Name(name))
    case RawIdentifier(i) if symbolTable.contains(i) => Literal(EmbeddedIdentifier(i))
  })

  lazy val arguments: Parser[List[Expression]] = 
    (p('(') ~> repsep(expression, p(',')) <~ p(')')) |
    (p('{') ~> (expression ^^ (List(_))) <~ p('}'))

  lazy val invocationExpr: Parser[Expression] = for {
    sb <- symbol
    otps <- opt(typeArguments)
    args <- arguments
  } yield otps match {
    case Some(tps) => Application(TypeApplication(sb, tps), args)
    case None => Application(sb, args)
  }

  lazy val quantifier: Parser[Quantifier] = acceptMatch("Quantifier expected.", {
    case lexical.Quantifier("forall") => Forall
    case lexical.Quantifier("exists") => Exists
    case lexical.Quantifier("lambda") => Lambda
    case lexical.Quantifier("choose") => Choose
  })

  lazy val valDef: Parser[(Identifier, Option[trees.Type])] = for {
    i <- identifier
    otype <- opt(p(':') ~> inoxType)
  } yield (i, otype)

  def quantifierExpr: Parser[Expression] = for {
    q <- quantifier
    vds <- rep1sep(valDef, p(','))
    _ <- p('.')
    e <- expression
  } yield Abstraction(q, vds, e)

  lazy val operatorExpr: Parser[Expression] = {

    def withPrio(oneOp: Parser[(Expression, Expression) => Expression], lessPrio: Parser[Expression], assoc: Assoc) = {
      assoc match {
        case LeftAssoc => {
          chainl1(lessPrio, oneOp)
        }
        case RightAssoc => {
          lessPrio ~ rep(oneOp ~ lessPrio) ^^ {
            case first ~ opsAndExprs => {
              if (opsAndExprs.isEmpty) {
                first
              }
              else {
                val (ops, exprs) = opsAndExprs.map({ case a ~ b => (a, b) }).unzip
                val exprsAndOps = (first +: exprs).zip(ops)
                val last = exprs.last

                exprsAndOps.foldRight(last) {
                  case ((expr, op), acc) => op(expr, acc)
                }
              }
            }
          }
        }
      }
    }

    val zero = nonOperatorExpr

    opTable.foldLeft(zero) {
      case (lessPrio, (ops, assoc)) => {
        val oneOp = ops.map({
          case op => elem(Operator(op)) ^^^ { (a: Expression, b: Expression) => Operation(op, Seq(a, b)) }
        }).reduce(_ | _)

        withPrio(oneOp, lessPrio, assoc)
      }
    }
  }

  lazy val typeArguments: Parser[List[Type]] = p('[') ~> rep1sep(inoxType, p(',')) <~ p(']')

  lazy val inoxValDef: Parser[trees.ValDef] = for {
    i <- identifier
    _ <- p(':')
    t <- inoxType
  } yield i match {
    case IdentifierIdentifier(v) => trees.ValDef(v, t)
    case IdentifierName(n) => trees.ValDef(FreshIdentifier(n), t)
  }
}