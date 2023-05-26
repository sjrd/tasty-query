package tastyquery

import tastyquery.Contexts.*
import tastyquery.Symbols.*
import tastyquery.Types.*
import tastyquery.TypeMaps.*

private[tastyquery] object TypeOps:
  def asSeenFrom(tp: TypeOrMethodic, pre: Prefix, cls: Symbol)(using Context): tp.ThisTypeMappableType =
    pre match
      case NoPrefix | _: PackageRef        => tp
      case pre: ThisType if pre.cls == cls => tp // This is necessary to cut down infinite recursions
      case pre: Type                       => new AsSeenFromMap(pre, cls).apply(tp)
  end asSeenFrom

  /** The TypeMap handling the asSeenFrom */
  private final class AsSeenFromMap(pre: Type, cls: Symbol)(using Context) extends ApproximatingTypeMap {

    /** Set to true when the result of `apply` was approximated to avoid an unstable prefix. */
    var approximated: Boolean = false

    def transform(tp: TypeMappable): TypeMappable = {

      /** Map a `C.this` type to the right prefix. If the prefix is unstable, and
        *  the current variance is <= 0, return a range.
        *  @param  pre     The prefix
        *  @param  cls     The class in which the `C.this` type occurs
        *  @param  thiscls The prefix `C` of the `C.this` type.
        */
      def toPrefix(origTp: ThisType, pre: Prefix, cls: Symbol, thiscls: ClassSymbol): Type =
        pre match
          case NoPrefix | _: PackageRef =>
            origTp
          case pre: SuperType =>
            toPrefix(origTp, pre.thistpe, cls, thiscls)
          case pre: Type =>
            cls match
              case cls: PackageSymbol =>
                origTp
              case cls: ClassSymbol =>
                if (thiscls.isSubclass(cls) && pre.baseType(thiscls).isDefined)
                  /*if (variance <= 0 && !isLegalPrefix(pre)) // isLegalPrefix always true?
                  if (variance < 0) {
                    approximated = true
                    NothingType
                  }
                  else
                    // Don't set the `approximated` flag yet: if this is a prefix
                    // of a path, we might be able to dealias the path instead
                    // (this is handled in `ApproximatingTypeMap`). If dealiasing
                    // is not possible, then `expandBounds` will end up being
                    // called which we override to set the `approximated` flag.
                    range(NothingType, pre)
                else*/ pre
                /*else if (pre.termSymbol.isPackage && !thiscls.isPackage)
                toPrefix(pre.select(nme.PACKAGE), cls, thiscls)*/
                else
                  pre.baseType(cls).flatMap(_.normalizedPrefix) match
                    case Some(normalizedPrefix) => toPrefix(origTp, normalizedPrefix, cls.owner.nn, thiscls)
                    case None                   => origTp
              case _ =>
                throw AssertionError(
                  s"While computing asSeenFrom for $origTp;\n"
                    + s"found unexpected cls = $cls in toPrefix($pre, $cls, $thiscls)"
                )
      end toPrefix

      tp match {
        case tp: ThisType =>
          toPrefix(tp, pre, cls, tp.cls)
        case _ =>
          mapOver(tp)
      }
    }

    override def reapply(tp: Type): Type =
      // derived infos have already been subjected to asSeenFrom, hence no need to apply the map again.
      tp

    override protected def expandBounds(tp: TypeBounds): Type = {
      approximated = true
      super.expandBounds(tp)
    }
  }

  // Tests around `matches`

  /** The implementation for `tp1.matches(tp2)`. */
  final def matchesType(tp1: TypeOrMethodic, tp2: TypeOrMethodic)(using Context): Boolean = tp1.widen match
    case tp1: MethodType =>
      tp2.widen match
        case tp2: MethodType =>
          // implicitness is ignored when matching
          matchingMethodParams(tp1, tp2)
            && matchesType(tp1.resultType, Substituters.substBinders(tp2.resultType, tp2, tp1))
        case tp2 =>
          tp1.paramNames.isEmpty
            && matchesType(tp1.resultType, tp2)

    case tp1: PolyType =>
      tp2.widen match
        case tp2: PolyType =>
          matchingPolyParams(tp1, tp2)
            && matchesType(tp1.resultType, Substituters.substBinders(tp2.resultType, tp2, tp1))
        case _ =>
          false

    case _ =>
      tp2.widen match
        case _: PolyType =>
          false
        case tp2: MethodType =>
          matchesType(tp1, tp2.resultType)
        case tp2 =>
          true
  end matchesType

  /** Do the parameter types of `tp1` and `tp2` match in a way that allows `tp1` to override `tp2`?
    *
    * This is the case if they're pairwise `>:>`.
    */
  def matchingPolyParams(tp1: PolyType, tp2: PolyType)(using Context): Boolean =
    // TODO Actually test `>:>`.
    tp1.paramNames.lengthCompare(tp2.paramNames) == 0
  end matchingPolyParams

  /** Do the parameter types of `tp1` and `tp2` match in a way that allows `tp1` to override `tp2`?
    *
    * This is the case if they're pairwise `=:=`.
    */
  def matchingMethodParams(tp1: MethodType, tp2: MethodType)(using Context): Boolean =
    def loop(formals1: List[Type], formals2: List[Type]): Boolean = formals1 match
      case formal1 :: rest1 =>
        formals2 match
          case formal2 :: rest2 =>
            val formal2a = Substituters.substBinders(formal2, tp2, tp1)
            val paramsMatch = Subtyping.isSameType(formal2a, formal1)
            paramsMatch && loop(rest1, rest2)
          case Nil =>
            false

      case Nil =>
        formals2.isEmpty
    end loop

    loop(tp1.paramTypes, tp2.paramTypes)
  end matchingMethodParams
end TypeOps
