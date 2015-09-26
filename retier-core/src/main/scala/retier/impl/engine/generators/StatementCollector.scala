package retier
package impl
package engine.generators

import engine._
import scala.reflect.macros.blackbox.Context

trait StatementCollector { this: Generation =>
  val c: Context
  import c.universe._

  val collectStatements = AugmentedAggregation[
    InputStatement with PeerDefinition,
    PlacedStatement with NonPlacedStatement] {
      aggregator =>

    echo(verbose = true, " Collecting placed and non-placed statements")

    val peerSymbols = aggregator.all[PeerDefinition] map { _.peerSymbol }

    def extractAndValidateType(tree: Tree, tpe: Type) = {
      val Seq(exprType, peerType) = tpe.typeArgs

      if (!(peerSymbols contains peerType.typeSymbol))
        c.abort(tree.pos,
          "placed abstractions must be placed on a peer " +
          "that is defined in the same scope")

      (peerType, exprType)
    }

    def extractTypeTree(tree: Tree) = {
      val args = tree.typeTree match {
        case AppliedTypeTree(_, args) => args
        case _ => List(EmptyTree)
      }
      args.head.typeTree
    }

    def isPlacedType(tpe: Type) =
      tpe != null && tpe <:< types.localOn && (types.bottom forall { tpe <:!< _ })

    def isPeerDefinition(symbol: Symbol) =
      symbol != null && symbol.isClass && symbol.asClass.toType <:< types.peer

    val stats = aggregator.all[InputStatement] map { _.stat } collect {
      case stat: ValOrDefDef if isPlacedType(stat.tpt.tpe) =>
        val (peerType, exprType) = extractAndValidateType(stat, stat.tpt.tpe)
        val declTypeTree = extractTypeTree(stat.tpt) orElse TypeTree(exprType)
        PlacedStatement(
          stat, peerType.typeSymbol.asType, exprType, Some(declTypeTree), None,
          stat.rhs)

      case stat if isPlacedType(stat.tpe) =>
        val (peerType, exprType) = extractAndValidateType(stat, stat.tpe)
        PlacedStatement(
          stat, peerType.typeSymbol.asType, exprType, None, None, stat)

      case stat
          if !isPeerDefinition(stat.symbol) &&
             !isPeerDefinition(stat.symbol.companion) =>
        NonPlacedStatement(stat)
    }

    val placedStats = stats collect { case stat: PlacedStatement => stat }
    val nonPlacedStats = stats collect { case stat: NonPlacedStatement => stat }

    echo(verbose = true,
      s"  [${placedStats.size} placed statements added]")
    echo(verbose = true,
      s"  [${nonPlacedStats.size} non-placed statements added]")

    aggregator add placedStats add nonPlacedStats
  }
}
