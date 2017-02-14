package welder

import scala.language.implicitConversions

trait Equational { self: Theory =>

  import program.trees._

  trait AcceptsProof {
    def ==|(theorem: Theorem): Node
    def ==|(proof: Goal => Attempt[Witness]): Node
  }

  trait Node {
    def |(node: Node): Chain = {
      val thiz = this

      new Chain {
        val first = thiz.first
        val next = node.next
        val last = node.first
        def equality = {
          val goal = new Goal(Equals(thiz.first, node.first))
          thiz.next(goal) flatMap { (w: Witness) => 
            if (!goal.accepts(w)) {
              Attempt.incorrectWitness
            }
            else {
              Attempt.success(w.theorem)
            }
          }
        }
      }
    }
    def |(end: Expr): Attempt[Theorem] = {

      val goal = new Goal(Equals(this.first, end))
      this.next(goal) flatMap { (w: Witness) => 
        if (!goal.accepts(w)) {
          Attempt.incorrectWitness
        }
        else {
          Attempt.success(w.theorem)
        }
      }
    }

    val first: Expr
    val next: Goal => Attempt[Witness]
  }

  trait Chain extends Node {
    val last: Expr
    def equality: Attempt[Theorem]

    override def |(node: Node): Chain = {
      val thiz = this

      new Chain {
        val first = thiz.first
        val last = node.first
        val equality = transitivity(thiz.first, thiz.last, node.first)(
          { (goal: Goal) => thiz.equality.flatMap(goal.by(_)) },
          { (goal: Goal) => thiz.next(goal) })
        val next = node.next
      }
    }
    override def |(end: Expr): Attempt[Theorem] = transitivity(this.first, this.last, end)(
      { (goal: Goal) => this.equality.flatMap(goal.by(_)) },
      { (goal: Goal) => this.next(goal) })
  }

  implicit def exprToAcceptsProof(expr: Expr): AcceptsProof = new AcceptsProof {
    
    def ==|(theorem: Theorem): Node = new Node {
      val first = expr
      val next = (goal: Goal) => goal.by(theorem)
    }

    def ==|(proof: Goal => Attempt[Witness]): Node = new Node {
      val first = expr
      val next = proof
    }
  }
}