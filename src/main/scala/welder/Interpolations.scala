/* Copyright 2017 EPFL, Lausanne */

package welder

import inox.Identifier
import inox.InoxProgram

import welder.parsing._

import scala.util.parsing.combinator.Parsers

trait Interpolations { self: Theory =>
  
  import program.trees._

  lazy val interpolator = new Interpolator {
    override val program = self.program
  }

  implicit class ExpressionInterpolator(sc: StringContext) {

    import interpolator._

    private val parser = new ExpressionParser()

    object e {
      def apply(args: Any*): Expr = {
        ExprIR.getExpr(ir(args : _*))
      }

      def unapplySeq(expr: Expr): Option[Seq[Any]] = {
        val args = Seq.tabulate(sc.parts.length - 1)(MatchPosition(_))
        val ir = parser.getFromSC(sc, args)(parser.phrase(parser.expression))
        ExprIR.extract(expr, ir) match {
          case Some(mappings) if mappings.size == sc.parts.length - 1 => Some(mappings.toSeq.sortBy(_._1).map(_._2))
          case _ => None
        }
      }
    }

    def ir(args: Any*): ExprIR.Expression = {
      parser.getFromSC(sc, args)(parser.phrase(parser.expression))
    }

    def v(args: Any*): ValDef = {
      parser.getFromSC(sc, args)(parser.phrase(parser.inoxValDef))
    }

    def r(args: Any*): Seq[Lexer.Token] = {
      val reader = Lexer.getReader(sc, args)

      import scala.util.parsing.input.Reader 

      def go[A](r: Reader[A]): Seq[A] = {
        if (r.atEnd) Seq()
        else r.first +: go(r.rest)
      }

      go(reader)
    }

    object t {
      def apply(args: Any*): Type = {
        parser.getFromSC(sc, args)(parser.phrase(parser.inoxType))
      }

      def unapplySeq(tpe: Type): Option[Seq[Any]] = {
        val args = Seq.tabulate(sc.parts.length - 1)(MatchPosition(_))
        val ir = parser.getFromSC(sc, args)(parser.phrase(parser.typeExpression))
        TypeIR.extract(tpe, ir) match {
          case Some(mappings) if mappings.size == sc.parts.length - 1 => Some(mappings.toSeq.sortBy(_._1).map(_._2))
          case _ => None
        }
      }
    }
  }
}
