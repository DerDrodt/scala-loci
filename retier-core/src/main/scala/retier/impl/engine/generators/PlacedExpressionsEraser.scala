package retier
package impl
package engine.generators

import engine._
import scala.reflect.macros.blackbox.Context

trait PlacedExpressionsEraser { this: Generation =>
  val c: Context
  import c.universe._

  val erasePlacedExpressions = UniformAggregation[PlacedStatement] {
      aggregator =>

    def processOverridingDeclaration
        (overridingExpr: Tree, stat: PlacedStatement): Option[TermName] =
      overridingExpr match {
        case expr if expr.symbol == symbols.placedOverriding =>
          val q"$exprBase.$_[..$_].$_[..$_](...$exprss)" = expr
          val identifier = exprss.head.head

          if (stat.declTypeTree.isEmpty)
            c.abort(identifier.pos, "overriding must be part of a declaration")

          identifier match {
            case q"$_.this.$tname" =>
              val Seq(declType, peerType) = identifier.tpe.typeArgs

              if (stat.exprType <:!< declType)
                c.abort(identifier.pos, "overriding value of incompatible type")

              if (stat.peerType <:!< peerType || stat.peerType =:= peerType)
                c.abort(identifier.pos, "overriding value of non-base type")

              Some(tname)

            case _ =>
              c.abort(identifier.pos, "identifier of same scope expected")
              None
          }

        case _ =>
          None
      }

    def processPlacedExpression
        (stat: PlacedStatement): PlacedStatement = {
      val PlacedStatement(
        tree, peerType, exprType, declTypeTree, overridingDecl, expr) = stat

      // process overriding declaration and extract placed expression
      val (processedOverridingDecl, placedExpr) =
        if (symbols.placed contains expr.symbol) {
          val (exprBase, exprss) = expr match {
            case q"$exprBase.$_[..$_].$_[..$_](...$exprss)" =>
              (exprBase, exprss)
            case q"$exprBase.$_[..$_](...$exprss)" =>
              (exprBase, exprss)
          }

          val q"(..$_) => $exprPlaced" = exprss.head.head

          (processOverridingDeclaration(exprBase, stat), exprPlaced)
        }
        else
          (overridingDecl, expr)

        // handle issued types
        val processedPlacedExpr =
          if (exprType <:< types.issuedControlled &&
              exprType <:!< types.issued &&
              (types.functionPlacing exists { placedExpr.tpe <:< _ }))
            q"""_root_.retier.impl.ControlledIssuedValue.create[
                ..${exprType.typeArgs}]($placedExpr)"""
          else if (exprType <:< types.issuedControlled &&
                   (types.issuedPlacing forall { placedExpr.tpe <:!< _ }))
            q"""_root_.retier.impl.IssuedValue.create[
                ..${exprType.typeArgs}]($placedExpr)"""
          else
            placedExpr

        if (expr.symbol == symbols.placedIssuedApply && declTypeTree.isEmpty)
          c.abort(tree.pos, "issuing must be part of a declaration")

        // construct new placed statement
        // with the actual placed expression syntactic construct removed
        PlacedStatement(tree, peerType, exprType, declTypeTree,
          processedOverridingDecl, processedPlacedExpr)
    }

    def dropPrecedingGlobalCasts
        (stat: PlacedStatement): PlacedStatement =
      if (symbols.globalCasts contains stat.expr.symbol) {
        val q"$_(...$exprss)" = stat.expr
        stat.copy(expr = exprss.head.head)
      }
      else
        stat

    val stats =
      aggregator.all[PlacedStatement] map
      dropPrecedingGlobalCasts map
      processPlacedExpression

    echo(
      verbose = true,
      s"Erased placed expressions " +
      s"(${stats.size} placed statements generated, existing replaced)")

    aggregator replace stats
  }
}