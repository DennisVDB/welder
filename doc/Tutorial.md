Tutorial
========

In this tutorial, we will prove the following formula using Welder:

![∀ n >= 0. 1 + 2 + ... + n = n * (n + 1) / 2](images/tutorial-formula.png)

This tutorial can be followed along in the Scala interpreter.

#### IMPORTANT NOTE ####

This tutorial assumes some level of familiarity with Inox. If you are unfamiliar with it, we strongly advise you to check the [Inox tutorial](https://github.com/epfl-lara/inox/blob/master/doc/tutorial.md) before you dive into this one!

Definition of the sum function
------------------------------

Our first step is to define the sum function, and package it into program. To do so, we use Inox directly. Since you are already familiar with Inox, there's nothing new for you here!

```scala
import inox._
import inox.trees._
import inox.trees.dsl._

// We create an identifier for the function.
val sum = FreshIdentifier("sum")

// We define the sum function.
val sumFunction = mkFunDef(sum)() { case _ =>

  // The function takes only one argument, of type `BigInt`.
  val args: Seq[ValDef] = Seq("n" :: IntegerType)
  
  // It returns a `BigInt`.
  val retType: Type = IntegerType
  
  // Its body is defined as:
  val body: Seq[Variable] => Expr = { case Seq(n) =>
    if_ (n === E(BigInt(0))) {
      // We return `0` if the argument is `0`.
      E(BigInt(0))
    } else_ {
      // We call the function recursively on `n - 1` in other cases.
      val predN = n - E(BigInt(1))     
      E(sum)(predN) + n
    }
  }
    
  (args, retType, body)
}

// Our program simply consists of the `sum` function.
val sumProgram = InoxProgram(Context.empty,
                   NoSymbols.withFunctions(Seq(sumFunction)))

```

The above code snippet simply defines a program which contains the function `sum`. This function performs the sum of all integers from `0` to its argument `n`. Called on `0`, it simply returns `0`. On values of `n` different from `0`, the function recursively calls itself on `n - 1` and adds `n` to the result.

Importing Welder
----------------

Now is time to actually use Welder.
First, we must create a `Theory` over the `sumProgram` that we have just defined. For this, we can use the `theoryOf` function.

```scala
import welder._

val theory = theoryOf(sumProgram)

import theory._
```

The above code snippet will import data types and functions that we can use to
reason about the `sumProgram` we have just defined.

Main concepts of Welder
-----------------------

At this point, we should pause for a moment and introduce some of the concepts that are used in Welder.

### Theorem ###

*The* most important concept of Welder is that of a `Theorem`. A theorem is a simple wrapper around an expression of type `BooleanType`. For instance, the theorem `truth` contains the trivially true expression `true`.

```scala
val myTheorem: Theorem = truth
println(myTheorem)
// Outputs:
// Theorem(true)
```

What is interesting is that, we, as users of Welder, **can not build values of type `Theorem` directly**. Indeed, the constructor of `Theorem` is private and there is no way around that.
The flip side is, when we get a theorem, we are guaranteed that the expression it contains has been proved to hold in the theory!

### Attempt ###

The `Attempt[T]` data type represents either values of type `T`, or the reason of the failure to get such a value. The data type offers a monadic interface (i.e. `map` and `flatMap` functions) and behaves very similarly to `Option[T]`.

Values of type `T` can be implicitly converted to `Attempt[T]`.
The opposite is also true, values of type `Attempt[T]` will be converted to `T` as needed. This conversion will, however, throw an exception on failure cases.

The method `Attempt.abort(message)` can used to abort an attempt.

### Combinators ###

Since, as we have already discussed, we can not use the constructor of `Theorem`, we must rely instead on the various functions provided by Welder to obtain theorems. As there are many such functions, we will not go through all of them now.

However, one of them is so particularly useful that it is worth mentioning it here. Its name is `prove` and it can be used to feed expressions directly to the underlying SMT-solvers used by Inox.

```scala
val expression = (E(BigInt(1)) + E(BigInt(1))) === E(BigInt(2))
prove(expression)
// Returns:
// Success(Theorem(1 + 1 == 2))
```

The method can also be called with more than one argument. All arguments after the first must be values of type `Theorem`. They are passed to the SMT-solver as assumptions that can be used to derive the validity of the expression.

### Scoped theorems ###

Some theorems are only valid for restricted scopes. Such theorems are typically introduced by functions that allow to users to make hypotheses.

One such function is `implI`, which stands for *implication introduction*. It allows to, within some limited scope, accept some expression as true.

```scala
// Here, we assume that `1 == 2`.
implI(E(BigInt(1)) === E(BigInt(2))) { (oneIsTwo: Theorem) =>
  
  // `oneIsTwo` is a scoped theorem.
  // It contains the expression `1 == 2`.
  println(oneIsTwo)
  // Outputs:
  // Theorem*(1 == 2)
  //        ^ Note the star.
  // It indicates that the theorem is not valid in all scopes.

  println(oneIsTwo.isGloballyValid)
  // Outputs:
  // false

  // We can use our `oneIsTwo` theorem as any other.
  // For instance, we can use it to prove that 12 is 17.
  prove(E(BigInt(12)) === E(BigInt(17)), oneIsTwo)
}

// The entire call would return the following (globally true) theorem:
// Success(Theorem((1 == 2) ==> (12 == 17)))
```

If a scoped theorem, or any other theorem derived from it, was to escape its scope through the use of mechanisms such as mutable variables or exceptions, it would *never* be globally valid and would taint any other theorem derived from it.

### Goal and Witness ###

Goals are wrapper around boolean expressions. They are never created by you as a user of the library, but are often passed as arguments to high order functions. They indicate the current expression to be proven.
For instance, the `notI` function, which stands for *negation introduction*, passes a `Goal` to the contradition function supplied.

```scala
def notI(hypothesis: Expr)
        (contradiction: (Theorem, Goal) => Attempt[Witness])
        : Attempt[Theorem]  //    ^^^^  The goal. An expression to be proved.
```

In this case, the goal passed to the contradiction function will contain the boolean literal value `false`. As you may have guessed, the `notI` function allows to make proofs by contradiction.

Witnesses are values that witness the achievement of a `goal`.
They are obtained using the `goal.by(theorem)` method, which takes a `theorem` as argument. If the theorem implies the goal, a witness will be returned. When the goal is trivially true, the method `goal.trivial` can be used as a shortcut.

Even shorter, the object `trivial` can be used in all the following contexts to indicate that the goal is trivially achieved:

- `Witness`
- `Attempt[Witness]`
- `Goal => Witness`
- `(Theorem, Goal) => Witness`

For instance, one could write:

```scala
notI(E(false))(trivial)  // Returns `Success(Theorem(¬false))`
```

Now that we have introduces those fundamental concepts, we can get back on track and try to prove the formula we have introduced (way way) earlier!

Definition of the property
--------------------------

As a reminder, here is the property that we want to prove, but this time expressed as a proper expression in our theory.

```scala
// Contains the expression:
// ∀n: BigInt. ((n >= 0) ==> (sum(n) == (n * (n + 1)) / 2))
val toProve: Expr = forall("n" :: IntegerType) { n => 
    (n >= E(BigInt(0))) ==> {
        E(sum)(n) === (n * (n + E(BigInt(1)))) / E(BigInt(2))
    }
}
```

Invoking Inox's solver
----------------------

The first thing to try is to feed the property directly to Inox.
This can be done very easily using the `prove` function, as we have showcased earlier.
The function takes as argument an expression of type `BooleanType` and returns, if successful, a `Theorem` for the expression.

In our case, we can invoke it like this:

```scala
prove(toProve)  // This will time out. Sad!
```

Unfortunately, Inox is not able to directly prove this property.
The above method fails after a timing out.
We will need to use other methods provided by Welder to progress further.


Performing natural induction
----------------------------

A proof technique that immediately comes to mind when trying to prove properties on natural number is natural induction.

To prove that a property holds on all integers larger or equal to some base value, we can use the function `naturalInduction`. It has the following signature:

```scala
def naturalInduction
      ( property: Expr => Expr
      , base: Expr, 
      , baseCase: Goal => Attempt[Witness]
      )
      ( inductiveCase: (NaturalInductionHypotheses, Goal) => Attempt[Witness])
      : Attempt[Theorem]
``` 

Its arguments are:

- A `property` to be proven.
- The `base` expression. Normally `0` or `1`, but can be arbitrarily specified.
- A proof that the `property` holds on the `base` expression.
- A proof that the `property` holds in the inductive case, given some induction hypotheses. 

The return value of the function will be, if successful, a `Theorem` stating that the `property` holds for all integers greater or equal to the `base`. This is exactly what is needed in our case!

We can thus use the method as follows:

```scala
// The property we want to prove, as a function of `n`.
def property(n: Expr): Expr = {
  E(sum)(n) === ((n * (n + E(BigInt(1)))) / E(BigInt(2)))
}

// Call to natural induction.
// The property we want to prove is defined just above.
// The base expression is `0`.
// The proof for the base case is trivial.
val ourTheorem = naturalInduction(property(_), E(BigInt(0)), trivial) { 
  case (ihs, goal) =>
    // `ihs` contains induction hypotheses
    // and `goal` contains the property that needs to be proven.
  
    // The variable on which we induct is stored in `ihs`.
    // We bound it to `n` for clarity.
    val n = ihs.variable
    
    // The expression for which we try to prove the property.
    // We bound it for clarity as well.
    val nPlus1 = n + E(BigInt(1))
  
    // We then state the following simple lemma:
    // `sum(n + 1) == sum(n) + (n + 1)`
    val lemma = {
      E(sum)(nPlus1) === (E(sum)(n) + nPlus1)
    }

    // `inox` is able to easily prove this property,
    // given that `n` is greater than `0`.
    val provenLemma: Theorem = prove(lemma, ihs.variableGreaterThanBase)

    // We then state that we can prove the goal using the conjunction of
    // our lemma and the induction hypothesis on `n`, i.e. :
    // `sum(n) == (n * (n + 1)) / 2
    goal.by(andI(provenLemma, ihs.propertyForVar))
}
```

At this point, if you inspect `ourTheorem`, you will obtain the following result:

```scala
println(ourTheorem)
// Outputs: 
// Success(Theorem(∀n: BigInt. ((n >= 0) ==> (sum(n) == (n * (n + 1)) / 2))))
```

Congratulations! We have just proven our first non-trivial `Theorem` !

That's it!

