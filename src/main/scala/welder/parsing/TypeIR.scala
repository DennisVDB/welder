package welder
package parsing

import inox._

import scala.util.parsing.input.Position

trait TypeIR extends IR {

  val trees: inox.ast.Trees
  val symbols: trees.Symbols

  type Identifier = Nothing
  type Type = Nothing
  type Field = Nothing
  type Quantifier = Nothing

  sealed abstract class Value
  case class Name(name: String) extends Value { override def toString = name }
  case class EmbeddedType(tpe: trees.Type) extends Value { override def toString = tpe.toString }
  case class EmbeddedIdentifier(id: inox.Identifier) extends Value { override def toString = id.toString }

  sealed abstract class Operator
  case object Group extends Operator
  case object Tuple extends Operator
  case object Arrow extends Operator

  object BVType {
    def unapply(name: String): Option[trees.Type] = {
      if (name.startsWith("Int")) {
        scala.util.Try(name.drop(3).toInt).toOption.filter(_ > 0).map(trees.BVType(_))
      }
      else {
        None
      }
    }
  }

  lazy val basic: Map[Value, trees.Type] = Seq(
    "Boolean" -> trees.BooleanType,
    "BigInt"  -> trees.IntegerType,
    "Char"    -> trees.CharType,
    "Int"     -> trees.Int32Type,
    "Real"    -> trees.RealType,
    "String"  -> trees.StringType,
    "Unit"    -> trees.UnitType).map({ case (n, v) => Name(n) -> v }).toMap

  lazy val parametric: Map[Value, (Int, Seq[trees.Type] => trees.Type)] =
    (primitives ++ adts).toMap

  lazy val primitives = Seq(
    "Set" -> (1, (ts: Seq[trees.Type]) => trees.SetType(ts.head)),
    "Map" -> (2, (ts: Seq[trees.Type]) => trees.MapType(ts(0), ts(1))),
    "Bag" -> (1, (ts: Seq[trees.Type]) => trees.BagType(ts.head))).map({ case (n, v) => Name(n) -> v })

  lazy val adts = symbols.adts.toSeq.flatMap({
    case (i, d) => {
      val f = (d.tparams.length, (ts: Seq[trees.Type]) => trees.ADTType(i, ts))

      Seq(
        Name(i.name) -> f,
        EmbeddedIdentifier(i) -> f)
    }
  })

  import Utils.{either, traverse, plural}

  def toInoxType(expr: Expression): Either[Seq[ErrorLocation], trees.Type] = expr match {

    case Operation(Tuple, irs) if irs.size >= 2 =>
      traverse(irs.map(toInoxType(_))).left.map(_.flatten).right.map(trees.TupleType(_))

    case Operation(Arrow, Seq(Operation(Group, froms), to)) => 
      either(
        traverse(froms.map(toInoxType(_))).left.map(_.flatten),
        toInoxType(to)
      ){
        case (argTpes, retTpe) => trees.FunctionType(argTpes, retTpe)
      }

    case Operation(Arrow, Seq(from, to)) =>
      either(
        toInoxType(from),
        toInoxType(to)
      ){
        case (argTpe, retTpe) => trees.FunctionType(Seq(argTpe), retTpe)
      }

    case Application(l@Literal(value), irs) =>
      either(
        parametric.get(value) match {
          case None => Left(Seq(ErrorLocation("Unknown type constructor: " + value, l.pos)))
          case Some((n, cons)) => if (n == irs.length) {
            Right(cons)
          } else {
            Left(Seq(ErrorLocation("Type constructor " + value + " takes " +
              n + " " + plural(n, "argument", "arguments") + ", " +
              irs.length + " " + plural(irs.length, "was", "were") + " given.", l.pos)))
          }
        },
        traverse(irs.map(toInoxType(_))).left.map(_.flatten)
      ){
        case (cons, tpes) => cons(tpes)
      }
      
    case Literal(EmbeddedType(t)) => Right(t)

    case Literal(Name(BVType(t))) => Right(t)

    case l@Literal(value) => basic.get(value) match {
      case None => Left(Seq(ErrorLocation("Unknown type: " + value, l.pos)))
      case Some(t) => Right(t)
    }

    case _ => Left(Seq(ErrorLocation("Invalid type.", expr.pos)))
  }
}