package io.getquill.context

import scala.language.higherKinds
import scala.language.experimental.macros
import java.io.Closeable
import scala.compiletime.summonFrom
import scala.util.Try
import io.getquill.{ ReturnAction }
import io.getquill.generic.EncodingDsl
import io.getquill.Quoted
import io.getquill.QueryMeta
import io.getquill.generic._
import io.getquill.context.mirror.MirrorDecoders
import io.getquill.context.mirror.Row
import io.getquill.generic.GenericDecoder
import io.getquill.Planter
import io.getquill.ast.{ Ast, Ident => AIdent }
import io.getquill.ast.ScalarTag
import io.getquill.idiom.Idiom
import io.getquill.ast.{Transform, QuotationTag}
import io.getquill.QuotationLot
import io.getquill.metaprog.QuotedExpr
import io.getquill.metaprog.PlanterExpr
import io.getquill.idiom.ReifyStatement
import io.getquill.EagerPlanter
import io.getquill.LazyPlanter
import io.getquill.generic.GenericEncoder
import io.getquill.generic.ElaborateStructure
import io.getquill.quat.Quat
import scala.quoted._
import io.getquill._
import io.getquill.quat.QuatMaking
import io.getquill.generic.ElaborateStructure.TaggedLiftedCaseClass
import io.getquill.parser.Lifter
import io.getquill.CaseClassLift

object LiftQueryMacro {
  private[getquill] def newUuid = java.util.UUID.randomUUID().toString

  def apply[T: Type, U[_] <: Iterable[_]: Type, PrepareRow: Type](entity: Expr[U[T]])(using Quotes): Expr[Query[T]] = {
    import quotes.reflect._
    // check if T is a case-class (e.g. mirrored entity) or a leaf, probably best way to do that
    val quat = QuatMaking.ofType[T]
    quat match
      case _: Quat.Product => 
        // Not sure why cast back to iterable is needed here but U param is not needed once it is inside of the planter
        '{ EagerEntitiesPlanter($entity.asInstanceOf[Iterable[T]], ${Expr(newUuid)}).unquote } // [T, PrepareRow] // adding these causes assertion failed: unresolved symbols: value Context_this
      case _ => 
        report.throwError("Scalar liftQuery not implemented yet", entity)
  }
}

object LiftMacro {
  private[getquill] def newUuid = java.util.UUID.randomUUID().toString
  private[getquill] val VIdent = AIdent("v", Quat.Generic)

  def apply[T: Type, PrepareRow: Type](entity: Expr[T])(using Quotes): Expr[T] = {
    import quotes.reflect._

    // check if T is a case-class (e.g. mirrored entity) or a leaf, probably best way to do that
    val quat = QuatMaking.ofType[T]
    quat match
      case _: Quat.Product => 
        '{ ${liftProduct[T, PrepareRow](entity)}.unquote }
      case _ => 
        var liftPlanter = liftValue[T, PrepareRow](entity)
        '{ $liftPlanter.unquote }
  }


  inline def liftInjectedProductExtern[T, PrepareRow]: List[T => Any] = ${ liftInjectedProduct[T, PrepareRow] }
  /*private[getquill]*/ 
  def liftInjectedProduct[T, PrepareRow](using qctx:Quotes, tpe: Type[T], prepareRowTpe: Type[PrepareRow]): Expr[List[T => Any]] = {
    import qctx.reflect._
    import scala.quoted._

    // Get the elaboration and AST once so that it will not have to be parsed out of the liftedCombo (since they are normally returned by ElaborateStructure.ofProductValue)
    val elaborated = ElaborateStructure.elaborationOfProductValue[T]
    val (_, caseClassAst) = ElaborateStructure.productValueToAst[T](elaborated)
    val caseClass = caseClassAst.asInstanceOf[io.getquill.ast.CaseClass]
    
    val labels = ElaborateStructure.liftsOfProductValue[T](elaborated, '{???.asInstanceOf[T]}).map(_._1)

    // Need to parse lifts out of a lambda method and then isolate the clauses later. Need to do it like this
    // instead of just making a fake-variable because doing the latter would violate phase-consistency (i.e. since we would)
    // be using a variable in a phase that does not actually exists. Instead we create a (singleArg) => { List(lift(singleArg.foo), lift(singleArg.bar), etc...)) }
    // ...
    // and the respectively pull out lift(singleArg.foo), lift(singleArg.bar), etc... from that clause turning it into
    // (singleArg) => lift(singleArg.foo), (singleArg) => lift(singleArg.bar), (singleArg) => etc... so that everything remains phase consistent

    // Note that this can be quite compile-time inefficient for large values since for every element
    // this entire thing needs to be re-computed. The alternative would be to pass the index
    // as a runtime parameter and compute this only once (i.e. ( val liftCombo = (elementIndex: Int) => (entity: T) => ${ ... } ))
    // but that means that the splice itself would have every single element over and over again which would make
    // the runtime code inefficient and could run into java's method limit size
    // A true optimization would be to actually produce the whole method once and then parse the body
    // pulling out all the needed content. This is currently out of scope but may be needed at some point
    def liftCombo =
      '{ (elementIndex: Int) => (entity: T) => ${
        val lifts = ElaborateStructure.liftsOfProductValue[T](elaborated, 'entity)
        val liftsExprs = lifts.map((caseClass, liftValue) => liftValue)
        '{ ${Expr.ofList(liftsExprs)}(elementIndex) }
      } }

    val output =
      labels.zipWithIndex.map((_, index) => Expr.betaReduce('{ $liftCombo(${Expr(index)}) }) )

    Expr.ofList(output)

    // import io.getquill.metaprog.Extractors._
    // val output =
    //   //tmc.UntypeExpr(liftedCombo).asTerm match
    //   liftedCombo.asTerm.underlyingArgument match
    //     case Lambda(params, body) =>
    //       val paramTypes = params.map(_.tpt.tpe)
    //       val paramNames = params.map(_.name)
    //       val mt = MethodType(paramNames)(_ => paramTypes, _ => TypeRepr.of[Any] /*.appliedTo(body.tpe.widen)*/ )
    //       Lambda(Symbol.spliceOwner, mt, (owner,args) => body.changeOwner(owner))
          
        
    //'{ ${output.asExpr} }.asInstanceOf[Expr[T => Any]]
    //'{ (entity: T) => ${output.asExpr} }

    //output.asExprOf[(T => Any)]

    //liftedCombo.asTerm.underlyingArgument.asExprOf[(T => Any)]
    

    
    // val liftPlanters = 
    //   lifts.map(
    //     (liftKey, lift) => 
    //       // since we don't have an implicit Type for every single lift, we need to pull out each of their TypeReprs convert them to Type and manually pass them in
    //       // Also need to widen the type otherwise for some value v=Person(name: String) the type will be TermRef(TermRef(NoPrefix,val v),val name) as oppsoed to 'String'
    //       val liftType = lift.asTerm.tpe.widen.asType
    //       liftType match {
    //         case '[liftT] =>
    //           liftValue[liftT, PrepareRow](lift.asExprOf[liftT], liftKey) // Note: if want to get this to work, try doing 'summon[Type[liftT]]' (using liftType, prepareRowTpe, quotes)
    //       }
    //   )
    
  }

  
  private[getquill] def liftProduct[T, PrepareRow](productEntity: Expr[T])(using qctx:Quotes, tpe: Type[T], prepareRowTpe: Type[PrepareRow]): Expr[CaseClassLift[T]] = {
    import qctx.reflect._
    val TaggedLiftedCaseClass(caseClassAst, lifts) = ElaborateStructure.ofProductValue[T](productEntity).reKeyWithUids()
    val liftPlanters = 
      lifts.map(
        (liftKey, lift) => 
          // since we don't have an implicit Type for every single lift, we need to pull out each of their TypeReprs convert them to Type and manually pass them in
          // Also need to widen the type otherwise for some value v=Person(name: String) the type will be TermRef(TermRef(NoPrefix,val v),val name) as oppsoed to 'String'
          val liftType = lift.asTerm.tpe.widen.asType
          liftType match {
            case '[liftT] =>
              liftValue[liftT, PrepareRow](lift.asExprOf[liftT], liftKey) // Note: if want to get this to work, try doing 'summon[Type[liftT]]' (using liftType, prepareRowTpe, quotes)
          }
      )
    val quotation = '{ Quoted[T](${Lifter(caseClassAst)}, ${Expr.ofList(liftPlanters)}, Nil) }
    '{ CaseClassLift[T]($quotation, ${Expr(java.util.UUID.randomUUID.toString)}) } // NOTE UUID technically not needed here. Can try to remove it later
  }

  private[getquill] def liftValue[T: Type, PrepareRow: Type](valueEntity: Expr[T], uuid: String = newUuid)(using Quotes) /*: Expr[EagerPlanter[T, PrepareRow]]*/ = {
    import quotes.reflect._
    val encoder = 
      Expr.summon[GenericEncoder[T, PrepareRow]] match
        case Some(enc) => enc
        case None => report.throwError(s"Cannot Find a '${Printer.TypeReprCode.show(TypeRepr.of[T])}' Encoder of ${Printer.TreeShortCode.show(valueEntity.asTerm)}", valueEntity)

    '{ EagerPlanter($valueEntity, $encoder, ${Expr(uuid)}) } //[T, PrepareRow] // adding these causes assertion failed: unresolved symbols: value Context_this
  }

  def applyLazy[T, PrepareRow](valueEntity: Expr[T])(using Quotes, Type[T], Type[PrepareRow]): Expr[T] = {
    import quotes.reflect._
    val uuid = java.util.UUID.randomUUID().toString
    '{ LazyPlanter($valueEntity, ${Expr(uuid)}).unquote } //[T, PrepareRow] // adding these causes assertion failed: unresolved symbols: value Context_this
  }
}