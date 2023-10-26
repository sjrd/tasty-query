package tastyquery.reader.classfiles

import scala.collection.mutable

import tastyquery.Annotations.Annotation as TQAnnotation
import tastyquery.Classpaths.*
import tastyquery.Contexts.*
import tastyquery.Exceptions.*
import tastyquery.Flags
import tastyquery.Flags.*
import tastyquery.Names.*
import tastyquery.SourceLanguage
import tastyquery.Symbols.*
import tastyquery.Types.*

import tastyquery.reader.ReaderContext
import tastyquery.reader.ReaderContext.rctx
import tastyquery.reader.pickles.{Unpickler, PickleReader}

import ClassfileReader.*
import ClassfileReader.Access.AccessFlags
import Constants.*

private[reader] object ClassfileParser {
  private val javaLangObjectBinaryName = termName("java/lang/Object")

  inline def innerClasses(using innerClasses: InnerClasses): innerClasses.type = innerClasses
  inline def resolver(using resolver: Resolver): resolver.type = resolver

  enum ClassKind:
    case Scala2(structure: Structure, runtimeAnnotStart: Forked[DataStream])
    case Java(structure: Structure, classSig: SigOrSupers, inners: Option[Forked[DataStream]])
    case TASTy
    case Artifact

  case class InnerClassRef(name: SimpleName, outer: SimpleName, isStatic: Boolean)

  case class InnerClassDecl(classData: ClassData, name: SimpleName, owner: DeclaringSymbol)

  class Resolver:
    private val refs = mutable.HashMap.empty[SimpleName, TypeRef]
    private val staticrefs = mutable.HashMap.empty[SimpleName, TermRef]

    private def computeRef(binaryName: SimpleName, isStatic: Boolean)(using ReaderContext, InnerClasses): NamedType =
      innerClasses.get(binaryName) match
        case Some(InnerClassRef(name, outer, isStaticInner)) =>
          val pre = lookup(outer, isStaticInner)
          NamedType(pre, if isStatic then name else name.toTypeName)
        case None if !isStatic && binaryName == javaLangObjectBinaryName =>
          rctx.FromJavaObjectType
        case None =>
          val (pkgRef, cls) = binaryName.name.lastIndexOf('/') match
            case -1 => (rctx.RootPackage.packageRef, binaryName)
            case i  => (computePkg(binaryName.name.take(i)), termName(binaryName.name.drop(i + 1)))
          NamedType(pkgRef, if isStatic then cls else cls.toTypeName)
    end computeRef

    private def computePkg(packageName: String)(using ReaderContext): TermReferenceType =
      val parts = packageName.split('/').map(termName).toList
      rctx.createPackageSelection(parts)

    private def lookup(binaryName: SimpleName, isStatic: Boolean)(using ReaderContext, InnerClasses): NamedType =
      if isStatic then staticrefs.getOrElseUpdate(binaryName, computeRef(binaryName, isStatic = true).asTermRef)
      else refs.getOrElseUpdate(binaryName, computeRef(binaryName, isStatic = false).asTypeRef)

    def resolve(binaryName: SimpleName)(using ReaderContext, InnerClasses): TypeRef =
      lookup(binaryName, isStatic = false).asTypeRef

  end Resolver

  /** The inner classes local to a class file */
  class InnerClasses private (refs: Map[SimpleName, InnerClassRef], decls: List[InnerClassDecl]):
    def get(binaryName: SimpleName): Option[InnerClassRef] =
      refs.get(binaryName)

    /** The inner class declarations of the associated classfile */
    def declarations: List[InnerClassDecl] =
      decls

  object InnerClasses:
    def parse(
      cls: ClassSymbol,
      moduleClass: ClassSymbol,
      structure: Structure,
      lookup: Map[SimpleName, ClassData],
      innerClasses: Forked[DataStream]
    ): InnerClasses =
      import structure.reader

      def missingClass(binaryName: SimpleName) =
        ClassfileFormatException(s"Inner class $binaryName not found, keys: ${lookup.keys.toList}")

      def lookupDeclaration(isStatic: Boolean, name: SimpleName, binaryName: SimpleName): InnerClassDecl =
        val data = lookup.getOrElse(binaryName, throw missingClass(binaryName))
        InnerClassDecl(data, name, if isStatic then moduleClass else cls)

      val refsBuf = Map.newBuilder[SimpleName, InnerClassRef]
      val declsBuf = List.newBuilder[InnerClassDecl]
      innerClasses.use {
        reader.readInnerClasses { (name, innerBinaryName, outerBinaryName, flags) =>
          val isStatic = flags.is(Flags.Static)
          refsBuf += innerBinaryName -> InnerClassRef(name, outerBinaryName, isStatic)
          if outerBinaryName == structure.binaryName then declsBuf += lookupDeclaration(isStatic, name, innerBinaryName)
        }
      }
      InnerClasses(refsBuf.result(), declsBuf.result())
    end parse

    val Empty = InnerClasses(Map.empty, Nil)
  end InnerClasses

  class Structure(
    val access: AccessFlags,
    val binaryName: SimpleName,
    val reader: ClassfileReader,
    val supers: Forked[DataStream],
    val fields: Forked[DataStream],
    val methods: Forked[DataStream],
    val attributes: Forked[DataStream]
  )(using val pool: reader.ConstantPool)

  def loadScala2Class(structure: Structure, runtimeAnnotStart: Forked[DataStream])(using ReaderContext): Unit = {
    import structure.{reader, given}

    val Some(Annotation(tpe, args)) = runtimeAnnotStart.use {
      reader.readAnnotation(Set(annot.ScalaLongSignature, annot.ScalaSignature))
    }: @unchecked

    val sigBytes = tpe match {
      case annot.ScalaSignature =>
        val bytesArg = args.head.asInstanceOf[AnnotationValue.Const[pool.type]]
        pool.sigbytes(bytesArg.valueIdx)
      case annot.ScalaLongSignature =>
        val bytesArrArg = args.head.asInstanceOf[AnnotationValue.Arr[pool.type]]
        val idxs = bytesArrArg.values.map(_.asInstanceOf[AnnotationValue.Const[pool.type]].valueIdx)
        pool.sigbytes(idxs)
    }
    Unpickler.loadInfo(sigBytes)

  }

  def loadJavaClass(
    classOwner: DeclaringSymbol,
    name: SimpleName,
    structure: Structure,
    classSig: SigOrSupers,
    innerLookup: Map[SimpleName, ClassData],
    optInnerClasses: Option[Forked[DataStream]]
  )(using ReaderContext, Resolver): List[InnerClassDecl] = {
    import structure.{reader, given}

    val allRegisteredSymbols = mutable.ListBuffer.empty[Symbol]

    val cls = ClassSymbol.create(name.toTypeName, classOwner)
    allRegisteredSymbols += cls

    def privateWithin(access: AccessFlags): Option[PackageSymbol] =
      def enclosingPackage(sym: Symbol): PackageSymbol = sym match
        case sym: PackageSymbol    => sym
        case sym: TermOrTypeSymbol => enclosingPackage(sym.owner)
      if access.isPackagePrivate then Some(enclosingPackage(classOwner)) else None

    val clsFlags = structure.access.toFlags | JavaDefined
    val clsPrivateWithin = privateWithin(structure.access)

    val moduleClass = ClassSymbol
      .create(name.withObjectSuffix.toTypeName, classOwner)
      .withTypeParams(Nil)
      .withFlags(clsFlags | Flags.ModuleClassCreationFlags, clsPrivateWithin)
      .setAnnotations(Nil)
      .withParentsDirect(rctx.ObjectType :: Nil)
      .withGivenSelfType(None)
    allRegisteredSymbols += moduleClass

    val module = TermSymbol
      .create(name, classOwner)
      .withDeclaredType(moduleClass.localRef)
      .withFlags(clsFlags | Flags.ModuleValCreationFlags, clsPrivateWithin)
      .setAnnotations(Nil)
    allRegisteredSymbols += module

    def readInnerClasses(innerClasses: Forked[DataStream]): InnerClasses =
      InnerClasses.parse(cls, moduleClass, structure, innerLookup, innerClasses)

    val innerClassesStrict = optInnerClasses.map(readInnerClasses).getOrElse(InnerClasses.Empty)
    given InnerClasses = innerClassesStrict

    /* Does this class contain signature-polymorphic methods?
     *
     * See https://scala-lang.org/files/archive/spec/3.4/06-expressions.html#signature-polymorphic-methods
     */
    val clsContainsSigPoly: Boolean =
      classOwner == rctx.javaLangInvokePackage
        && (cls.name == tpnme.MethodHandle || cls.name == tpnme.VarHandle)

    /* Is the member with the given baseFlags and access flags signature-polymorphic?
     *
     * We cheat a little bit here compared to the spec: we do not test whether
     * the method as *only* a varargs parameter. This is fine because
     * MethodHandle and VarHandle do not contain any native method with varargs
     * and also other arguments. We check that after the fact (see
     * `if sym.isSignaturePolymorphicMethod` down below).
     */
    def isSignaturePolymorphic(baseFlags: FlagSet, access: AccessFlags): Boolean =
      clsContainsSigPoly && baseFlags.is(Method) && access.isNativeVarargsIfMethod

    def createMember(name: SimpleName, baseFlags: FlagSet, access: AccessFlags): TermSymbol =
      val flags0 = baseFlags | access.toFlags | JavaDefined
      val flags =
        if isSignaturePolymorphic(baseFlags, access) then flags0 | SignaturePolymorphic
        else flags0
      val owner = if flags.is(Flags.Static) then moduleClass else cls
      val sym = TermSymbol.create(name, owner).withFlags(flags, privateWithin(access))
      sym.setAnnotations(Nil) // TODO Read Java annotations on fields and methods
      allRegisteredSymbols += sym
      sym

    def loadMembers(): IArray[(TermSymbol, AccessFlags, SigOrDesc)] =
      val buf = IArray.newBuilder[(TermSymbol, AccessFlags, SigOrDesc)]
      structure.fields.use {
        reader.readFields { (name, sigOrDesc, access) =>
          buf += ((createMember(name, EmptyFlagSet, access), access, sigOrDesc))
        }
      }
      structure.methods.use {
        reader.readMethods { (name, sigOrDesc, access) =>
          buf += ((createMember(name, Method, access), access, sigOrDesc))
        }
      }
      buf.result()
    end loadMembers

    def initParents(): Unit =
      def binaryName(cls: ConstantInfo.Class[pool.type]) =
        pool.utf8(cls.nameIdx)
      val parents = classSig match
        case SigOrSupers.Sig(sig) =>
          JavaSignatures.parseSignature(cls, sig, allRegisteredSymbols).requireType match
            case mix: AndType => mix.parts
            case sup          => sup :: Nil
        case SigOrSupers.Supers =>
          structure.supers.use {
            val superClass = reader.readSuperClass().map(binaryName)
            val interfaces = reader.readInterfaces().map(binaryName)
            Descriptors.parseSupers(cls, superClass, interfaces)
          }
      end parents
      val parents1 =
        if parents.head eq rctx.FromJavaObjectType then rctx.ObjectType :: parents.tail
        else parents
      cls.withParentsDirect(parents1)
    end initParents

    cls.withGivenSelfType(None)
    cls.withFlags(clsFlags, clsPrivateWithin)
    cls.setAnnotations(Nil) // TODO Read Java annotations on classes
    initParents()

    // Intercept special classes to create their magic methods
    if cls.isAnySpecialClass then
      if cls.isObject then rctx.createObjectMagicMethods(cls)
      else if cls.isString then rctx.createStringMagicMethods(cls)
      else if cls.isJavaEnum then rctx.createEnumMagicMethods(cls)

    for (sym, javaFlags, sigOrDesc) <- loadMembers() do
      val parsedType = sigOrDesc match
        case SigOrDesc.Desc(desc) => Descriptors.parseDescriptor(sym, desc)
        case SigOrDesc.Sig(sig)   => JavaSignatures.parseSignature(sym, sig, allRegisteredSymbols)
      val adaptedType =
        if sym.isMethod && sym.name == nme.Constructor then cls.makePolyConstructorType(parsedType)
        else if sym.isMethod && javaFlags.isVarargsIfMethod then patchForVarargs(sym, parsedType)
        else parsedType
      sym.withDeclaredType(adaptedType)

      // Verify after the fact that we don't mark signature-polymorphic methods that should not be
      if sym.isSignaturePolymorphicMethod then
        adaptedType match
          case adaptedType: MethodType if adaptedType.paramNames.sizeIs == 1 =>
            () // OK
          case _ =>
            throw AssertionError(
              s"Found a method that would be signature-polymorphic but it has more than one argument: " +
                s"${cls.name}.${sym.name}: ${adaptedType.showBasic}"
            )
    end for

    for sym <- allRegisteredSymbols do
      sym.checkCompleted()
      assert(
        !sym.isPackage && sym.asInstanceOf[TermOrTypeSymbol].sourceLanguage == SourceLanguage.Java,
        s"$sym of ${sym.getClass()}"
      )

    innerClasses.declarations
  }

  private def patchForVarargs(sym: TermSymbol, tpe: TypeOrMethodic)(using ReaderContext): MethodicType =
    tpe match
      case tpe: MethodType if tpe.paramNames.sizeIs >= 1 =>
        val patchedLast = tpe.paramTypes.last match
          case ArrayTypeExtractor(lastElemType) =>
            RepeatedType(lastElemType.requireType)
          case _ =>
            throw ClassfileFormatException(s"Found ACC_VARARGS on $sym but its last param type was not an array: $tpe")
        tpe.derivedLambdaType(tpe.paramNames, tpe.paramTypes.init :+ patchedLast, tpe.resultType)
      case tpe: PolyType =>
        tpe.derivedLambdaType(tpe.paramNames, tpe.paramTypeBounds, patchForVarargs(sym, tpe.resultType))
      case _ =>
        throw ClassfileFormatException(s"Found ACC_VARARGS on $sym but its type was not a MethodType: $tpe")
  end patchForVarargs

  /** Extracts `elemType` from `AppliedType(scala.Array, List(elemType))`.
    *
    * This works for array types created by `defn.ArrayTypeOf(elemType)`, but
    * is not otherwise guaranteed to work in all situations.
    */
  private object ArrayTypeExtractor:
    def unapply(tpe: AppliedType)(using ReaderContext): Option[TypeOrWildcard] =
      tpe.tycon match
        case tycon: TypeRef if tycon.name == tpnme.Array && tpe.args.sizeIs == 1 =>
          tycon.prefix match
            case prefix: PackageRef if prefix.symbol.isScalaPackage =>
              Some(tpe.args.head)
            case _ =>
              None
        case _ =>
          None
  end ArrayTypeExtractor

  private def parse(classRoot: ClassData, structure: Structure): ClassKind = {
    import structure.{reader, given}

    def process(attributesStream: Forked[DataStream]): ClassKind =
      var runtimeAnnotStart: Forked[DataStream] | Null = null
      var innerClassesStart: Option[Forked[DataStream]] = None
      var sigOrNull: String | Null = null
      var isScala = false
      var isTASTY = false
      var isScalaRaw = false
      attributesStream.use {
        reader.scanAttributes {
          case attr.ScalaSig =>
            isScala = true
            runtimeAnnotStart != null
          case attr.Scala =>
            isScalaRaw = true
            true
          case attr.TASTY =>
            isTASTY = true
            true
          case attr.RuntimeVisibleAnnotations =>
            runtimeAnnotStart = data.fork
            isScala
          case attr.Signature =>
            if !(isScala || isScalaRaw || isTASTY) then sigOrNull = data.fork.use(reader.readSignature)
            false
          case attr.InnerClasses =>
            if !(isScala || isScalaRaw || isTASTY) then innerClassesStart = Some(data.fork)
            false
          case _ =>
            false
        }
        isScalaRaw &= !isTASTY
      }
      if isScala then
        val annots = runtimeAnnotStart
        if annots != null then ClassKind.Scala2(structure, annots)
        else
          throw Scala2PickleFormatException(
            s"class file for ${classRoot.binaryName} is a scala 2 class, but has no annotations"
          )
      else if isTASTY then ClassKind.TASTy
      else if isScalaRaw then ClassKind.Artifact
      else
        val sig = sigOrNull
        val classSig = if sig != null then SigOrSupers.Sig(sig) else SigOrSupers.Supers
        ClassKind.Java(structure, classSig, innerClassesStart)
    end process

    process(structure.attributes)
  }

  private def structure(reader: ClassfileReader)(using pool: reader.ConstantPool)(using DataStream): Structure = {
    val access = reader.readAccessFlags()
    val thisClass = reader.readThisClass()
    val supers = data.forkAndSkip {
      data.skip(2) // superclass
      data.skip(2 * data.readU2()) // interfaces
    }
    Structure(
      access = access,
      binaryName = pool.utf8(thisClass.nameIdx),
      reader = reader,
      supers = supers,
      fields = reader.skipFields(),
      methods = reader.skipMethods(),
      attributes = reader.skipAttributes()
    )
  }

  private def toplevel(classOwner: DeclaringSymbol, classRoot: ClassData): Structure = {
    def headerAndStructure(reader: ClassfileReader)(using DataStream) = {
      reader.acceptHeader(classOwner, classRoot)
      structure(reader)(using reader.readConstantPool())
    }

    ClassfileReader.unpickle(classRoot)(headerAndStructure)
  }

  def readKind(classOwner: DeclaringSymbol, classRoot: ClassData): ClassKind =
    parse(classRoot, toplevel(classOwner, classRoot))

}
