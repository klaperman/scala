package scala.tools.nsc
package typechecker

import symtab.Flags._
import scala.tools.nsc.util._
import scala.tools.nsc.util.ClassPath._
import scala.reflect.runtime.ReflectionUtils
import scala.collection.mutable.ListBuffer
import scala.compat.Platform.EOL
import reflect.internal.util.Statistics
import scala.reflect.macros.util._
import java.lang.{Class => jClass}
import java.lang.reflect.{Array => jArray, Method => jMethod}
import scala.reflect.internal.util.Collections._

/**
 *  Code to deal with macros, namely with:
 *    * Compilation of macro definitions
 *    * Expansion of macro applications
 *
 *  Say we have in a class C:
 *
 *    def foo[T](xs: List[T]): T = macro fooBar
 *
 *  Then fooBar needs to point to a static method of the following form:
 *
 *    def fooBar[T: c.AbsTypeTag] // type tag annotation is optional
 *           (c: scala.reflect.macros.Context)
 *           (xs: c.Expr[List[T]])
 *           : c.Expr[T] = {
 *      ...
 *    }
 *
 *  Then, if foo is called in qual.foo[Int](elems), where qual: D,
 *  the macro application is expanded to a reflective invocation of fooBar with parameters:
 *
 *    (simpleMacroContext{ type PrefixType = D; val prefix = qual })
 *    (Expr(elems))
 *    (TypeTag(Int))
 */
trait Macros extends scala.tools.reflect.FastTrack with Traces {
  self: Analyzer =>

  import global._
  import definitions._
  import treeInfo.{isRepeatedParamType => _, _}
  import MacrosStats._
  def globalSettings = global.settings

  val globalMacroCache = collection.mutable.Map[Any, Any]()
  val perRunMacroCache = perRunCaches.newMap[Symbol, collection.mutable.Map[Any, Any]]

  /** `MacroImplBinding` and its companion module are responsible for
   *  serialization/deserialization of macro def -> impl bindings.
   *
   *  The first officially released version of macros persisted these bindings across compilation runs
   *  using a neat trick. The right-hand side of a macro definition (which contains a reference to a macro impl)
   *  was typechecked and then put verbatim into an annotation on the macro definition.
   *
   *  This solution is very simple, but unfortunately it's also lacking. If we use it, then
   *  signatures of macro defs become transitively dependent on scala-reflect.jar
   *  (because they refer to macro impls, and macro impls refer to scala.reflect.macros.Context defined in scala-reflect.jar).
   *  More details can be found in comments to https://issues.scala-lang.org/browse/SI-5940.
   *
   *  Therefore we have to avoid putting macro impls into binding pickles and come up with our own serialization format.
   *  Situation is further complicated by the fact that it's not enough to just pickle macro impl's class name and method name,
   *  because macro expansion needs some knowledge about the shape of macro impl's signature (which we can't pickle).
   *  Hence we precompute necessary stuff (e.g. the layout of type parameters) when compiling macro defs.
   */

  /** Represents all the information that a macro definition needs to know about its implementation.
   *  Includes a path to load the implementation via Java reflection,
   *  and various accounting information necessary when composing an argument list for the reflective invocation.
   */
  private case class MacroImplBinding(
    // Java class name of the class that contains the macro implementation
    // is used to load the corresponding object with Java reflection
    val className: String,
    // method name of the macro implementation
    // `className` and `methName` are all we need to reflectively invoke a macro implementation
    // because macro implementations cannot be overloaded
    val methName: String,
    // flattens the macro impl's parameter lists having symbols replaced with metadata
    // currently metadata is an index of the type parameter corresponding to that type tag (if applicable)
    // f.ex. for: def impl[T: AbsTypeTag, U: AbsTypeTag, V](c: Context)(x: c.Expr[T]): (U, V) = ???
    // `signature` will be equal to List(-1, -1, 0, 1)
    val signature: List[Int],
    // type arguments part of a macro impl ref (the right-hand side of a macro definition)
    // these trees don't refer to a macro impl, so we can pickle them as is
    val targs: List[Tree])

  /** Macro def -> macro impl bindings are serialized into a `macroImpl` annotation
   *  with synthetic content that carries the payload described in `MacroImplBinding`.
   *
   *  For example, for a pair of macro definition and macro implementation:
   *    def impl(c: scala.reflect.macros.Context): c.Expr[Unit] = c.literalUnit;
   *    def foo: Unit = macro impl
   *
   *  We will have the following annotation added on the macro definition `foo`:
   *
   *    @scala.reflect.macros.internal.macroImpl(
   *      `macro`(
   *        "signature" = List(-1),
   *        "methodName" = "impl",
   *        "versionFormat" = 1,
   *        "className" = "Macros$"))
   */
  private object MacroImplBinding {
    val versionFormat = 1

    def pickleAtom(obj: Any): Tree =
      obj match {
        case list: List[_] => Apply(Ident(ListModule), list map pickleAtom)
        case s: String => Literal(Constant(s))
        case i: Int => Literal(Constant(i))
      }

    def unpickleAtom(tree: Tree): Any =
      tree match {
        case Apply(list @ Ident(_), args) if list.symbol == ListModule => args map unpickleAtom
        case Literal(Constant(s: String)) => s
        case Literal(Constant(i: Int)) => i
      }

    def pickle(macroImplRef: Tree): Tree = {
      val macroImpl = macroImplRef.symbol
      val paramss = macroImpl.paramss

      // this logic relies on the assumptions that were valid for the old macro prototype
      // namely that macro implementations can only be defined in top-level classes and modules
      // with the new prototype that materialized in a SIP, macros need to be statically accessible, which is different
      // for example, a macro def could be defined in a trait that is implemented by an object
      // there are some more clever cases when seemingly non-static method ends up being statically accessible
      // however, the code below doesn't account for these guys, because it'd take a look of time to get it right
      // for now I leave it as a todo and move along to more the important stuff
      // todo. refactor when fixing SI-5498
      def className: String = {
        def loop(sym: Symbol): String = sym match {
          case sym if sym.owner.isPackageClass =>
            val suffix = if (sym.isModuleClass) "$" else ""
            sym.fullName + suffix
          case sym =>
            val separator = if (sym.owner.isModuleClass) "" else "$"
            loop(sym.owner) + separator + sym.javaSimpleName.toString
        }

        loop(macroImpl.owner.enclClass)
      }

      def signature: List[Int] = {
        val transformed = transformTypeTagEvidenceParams(paramss, (param, tparam) => tparam)
        transformed.flatten map (p => if (p.isTerm) -1 else p.paramPos)
      }

      val payload = List[(String, Any)](
        "versionFormat" -> versionFormat,
        "className"     -> className,
        "methodName"    -> macroImpl.name.toString,
        "signature"     -> signature
      )

      // the shape of the nucleus is chosen arbitrarily. it doesn't carry any payload.
      // it's only necessary as a stub `fun` for an Apply node that carries metadata in its `args`
      // so don't try to find a program element named "macro" that corresponds to the nucleus
      // I just named it "macro", because it's macro-related, but I could as well name it "foobar"
      val nucleus = Ident(newTermName("macro"))
      val wrapped = Apply(nucleus, payload map { case (k, v) => Assign(pickleAtom(k), pickleAtom(v)) })
      val pickle = gen.mkTypeApply(wrapped, treeInfo.typeArguments(macroImplRef.duplicate))

      // assign NoType to all freshly created AST nodes
      // otherwise pickler will choke on tree.tpe being null
      // there's another gotcha
      // if you don't assign a ConstantType to a constant
      // then pickling will crash
      new Transformer {
        override def transform(tree: Tree) = {
          tree match {
            case Literal(const @ Constant(x)) if tree.tpe == null => tree setType ConstantType(const)
            case _ if tree.tpe == null => tree setType NoType
            case _ => ;
          }
          super.transform(tree)
        }
      }.transform(pickle)
    }

    def unpickle(pickle: Tree): MacroImplBinding = {
      val (wrapped, targs) =
        pickle match {
          case TypeApply(wrapped, targs) => (wrapped, targs)
          case wrapped => (wrapped, Nil)
        }
      val Apply(_, pickledPayload) = wrapped
      val payload = pickledPayload.map{ case Assign(k, v) => (unpickleAtom(k), unpickleAtom(v)) }.toMap

      val pickleVersionFormat = payload("versionFormat").asInstanceOf[Int]
      if (versionFormat != pickleVersionFormat) throw new Error("macro impl binding format mismatch: expected $versionFormat, actual $pickleVersionFormat")

      val className = payload("className").asInstanceOf[String]
      val methodName = payload("methodName").asInstanceOf[String]
      val signature = payload("signature").asInstanceOf[List[Int]]
      MacroImplBinding(className, methodName, signature, targs)
    }
  }

  private def bindMacroImpl(macroDef: Symbol, macroImplRef: Tree): Unit = {
    val pickle = MacroImplBinding.pickle(macroImplRef)
    macroDef withAnnotation AnnotationInfo(MacroImplAnnotation.tpe, List(pickle), Nil)
  }

  private def loadMacroImplBinding(macroDef: Symbol): MacroImplBinding = {
    val Some(AnnotationInfo(_, List(pickle), _)) = macroDef.getAnnotation(MacroImplAnnotation)
    MacroImplBinding.unpickle(pickle)
  }

  /** Transforms parameters lists of a macro impl.
   *  The `transform` function is invoked only for AbsTypeTag evidence parameters.
   *
   *  The transformer takes two arguments: a value parameter from the parameter list
   *  and a type parameter that is witnesses by the value parameter.
   *
   *  If the transformer returns a NoSymbol, the value parameter is not included from the result.
   *  If the transformer returns something else, this something else is included in the result instead of the value parameter.
   *
   *  Despite of being highly esoteric, this function significantly simplifies signature analysis.
   *  For example, it can be used to strip macroImpl.paramss from the evidences (necessary when checking def <-> impl correspondence)
   *  or to streamline creation of the list of macro arguments.
   */
  private def transformTypeTagEvidenceParams(paramss: List[List[Symbol]], transform: (Symbol, Symbol) => Symbol): List[List[Symbol]] = {
    if (paramss.isEmpty || paramss.last.isEmpty) return paramss // no implicit parameters in the signature => nothing to do
    if (paramss.head.isEmpty || !(paramss.head.head.tpe <:< MacroContextClass.tpe)) return paramss // no context parameter in the signature => nothing to do
    def transformTag(param: Symbol): Symbol = param.tpe.dealias match {
      case TypeRef(SingleType(SingleType(NoPrefix, c), universe), AbsTypeTagClass, targ :: Nil)
      if c == paramss.head.head && universe == MacroContextUniverse =>
        transform(param, targ.typeSymbol)
      case _ =>
        param
    }
    val transformed = paramss.last map transformTag filter (_ ne NoSymbol)
    if (transformed.isEmpty) paramss.init else paramss.init :+ transformed
  }

  def computeMacroDefTypeFromMacroImpl(macroDdef: DefDef, macroImpl: Symbol): Type = {
    // Step I. Transform c.Expr[T] to T
    var runtimeType = macroImpl.tpe.finalResultType.dealias match {
      case TypeRef(_, ExprClass, runtimeType :: Nil) => runtimeType
      case _ => AnyTpe // so that macro impls with rhs = ??? don't screw up our inference
    }

    // Step II. Transform type parameters of a macro implementation into type arguments in a macro definition's body
    runtimeType = runtimeType.substituteTypes(macroImpl.typeParams, loadMacroImplBinding(macroDdef.symbol).targs.map(_.tpe))

    // Step III. Transform c.prefix.value.XXX to this.XXX and implParam.value.YYY to defParam.YYY
    def unsigma(tpe: Type): Type =
      transformTypeTagEvidenceParams(macroImpl.paramss, (param, tparam) => NoSymbol) match {
        case (implCtxParam :: Nil) :: implParamss =>
          val implToDef = flatMap2(implParamss, macroDdef.vparamss)(map2(_, _)((_, _))).toMap
          object UnsigmaTypeMap extends TypeMap {
            def apply(tp: Type): Type = tp match {
              case TypeRef(pre, sym, args) =>
                val pre1 = pre match {
                  case SingleType(SingleType(SingleType(NoPrefix, c), prefix), value) if c == implCtxParam && prefix == MacroContextPrefix && value == ExprValue =>
                    ThisType(macroDdef.symbol.owner)
                  case SingleType(SingleType(NoPrefix, implParam), value) if value == ExprValue =>
                    implToDef get implParam map (defParam => SingleType(NoPrefix, defParam.symbol)) getOrElse pre
                  case _ =>
                    pre
                }
                val args1 = args map mapOver
                TypeRef(pre1, sym, args1)
              case _ =>
                mapOver(tp)
            }
          }

          UnsigmaTypeMap(tpe)
        case _ =>
          tpe
      }

    unsigma(runtimeType)
  }

  /** A reference macro implementation signature compatible with a given macro definition.
   *
   *  In the example above for the following macro def:
   *    def foo[T](xs: List[T]): T = macro fooBar
   *
   *  This function will return:
   *    (c: scala.reflect.macros.Context)(xs: c.Expr[List[T]]): c.Expr[T]
   *
   *  Note that type tag evidence parameters are not included into the result.
   *  Type tag context bounds for macro impl tparams are optional.
   *  Therefore compatibility checks ignore such parameters, and we don't need to bother about them here.
   *
   *  @param macroDef The macro definition symbol
   *  @param tparams  The type parameters of the macro definition
   *  @param vparamss The value parameters of the macro definition
   *  @param retTpe   The return type of the macro definition
   */
  private def macroImplSig(macroDef: Symbol, tparams: List[TypeDef], vparamss: List[List[ValDef]], retTpe: Type): (List[List[Symbol]], Type) = {
    // had to move method's body to an object because of the recursive dependencies between sigma and param
    object SigGenerator {
      def sigma(tpe: Type): Type = {
        class SigmaTypeMap extends TypeMap {
          def apply(tp: Type): Type = tp match {
            case TypeRef(pre, sym, args) =>
              val pre1 = pre match {
                case ThisType(sym) if sym == macroDef.owner =>
                  SingleType(SingleType(SingleType(NoPrefix, ctxParam), MacroContextPrefix), ExprValue)
                case SingleType(NoPrefix, sym) =>
                  mfind(vparamss)(_.symbol == sym) match {
                    case Some(macroDefParam) => SingleType(SingleType(NoPrefix, param(macroDefParam)), ExprValue)
                    case _ => pre
                  }
                case _ =>
                  pre
              }
              TypeRef(pre1, sym, args map mapOver)
            case _ =>
              mapOver(tp)
          }
        }

        new SigmaTypeMap() apply tpe
      }

      def makeParam(name: Name, pos: Position, tpe: Type, flags: Long = 0L) =
        macroDef.newValueParameter(name, pos, flags) setInfo tpe
      val ctxParam = makeParam(nme.macroContext, macroDef.pos, MacroContextClass.tpe, SYNTHETIC)
      def implType(isType: Boolean, origTpe: Type): Type =
        if (isRepeatedParamType(origTpe))
          appliedType(
            RepeatedParamClass.typeConstructor,
            List(implType(isType, sigma(origTpe.typeArgs.head))))
        else {
          val tsym = getMember(MacroContextClass, if (isType) tpnme.AbsTypeTag else tpnme.Expr)
          typeRef(singleType(NoPrefix, ctxParam), tsym, List(sigma(origTpe)))
        }
      val paramCache = collection.mutable.Map[Symbol, Symbol]()
      def param(tree: Tree): Symbol =
        paramCache.getOrElseUpdate(tree.symbol, {
          val sym = tree.symbol
          val sigParam = makeParam(sym.name, sym.pos, implType(sym.isType, sym.tpe))
          if (sym.isSynthetic) sigParam.flags |= SYNTHETIC
          sigParam
        })

      val paramss = List(ctxParam) :: mmap(vparamss)(param)
      val implRetTpe = typeRef(singleType(NoPrefix, ctxParam), getMember(MacroContextClass, tpnme.Expr), List(sigma(retTpe)))
    }

    import SigGenerator._
    macroTraceVerbose("generating macroImplSigs for: ")(macroDef)
    macroTraceVerbose("tparams are: ")(tparams)
    macroTraceVerbose("vparamss are: ")(vparamss)
    macroTraceVerbose("retTpe is: ")(retTpe)
    macroTraceVerbose("macroImplSig is: ")(paramss, implRetTpe)
  }

  /** Verifies that the body of a macro def typechecks to a reference to a static public non-overloaded method,
   *  and that that method is signature-wise compatible with the given macro definition.
   *
   *  @return Typechecked rhs of the given macro definition if everything is okay.
   *          EmptyTree if an error occurs.
   */
  def typedMacroBody(typer: Typer, macroDdef: DefDef): Tree =
    try new MacroTyper(typer, macroDdef).typed
    catch { case MacroBodyTypecheckException => EmptyTree }

  class MacroTyper(val typer: Typer, val macroDdef: DefDef) extends MacroErrors {
    // Phase I: sanity checks
    val macroDef = macroDdef.symbol
    macroLogVerbose("typechecking macro def %s at %s".format(macroDef, macroDdef.pos))
    assert(macroDef.isTermMacro, macroDdef)
    if (fastTrack contains macroDef) MacroDefIsFastTrack()
    if (!typer.checkFeature(macroDdef.pos, MacrosFeature, immediate = true)) MacroFeatureNotEnabled()

    // we use typed1 instead of typed, because otherwise adapt is going to mess us up
    // if adapt sees <qualifier>.<method>, it will want to perform eta-expansion and will fail
    // unfortunately, this means that we have to manually trigger macro expansion
    // because it's adapt which is responsible for automatic expansion during typechecking
    def typecheckRhs(rhs: Tree): Tree = {
      try {
        // interestingly enough, just checking isErroneous doesn't cut it
        // e.g. a "type arguments [U] do not conform to method foo's type parameter bounds" error
        // doesn't manifest itself as an error in the resulting tree
        val prevNumErrors = reporter.ERROR.count
        var rhs1 = typer.typed1(rhs, EXPRmode, WildcardType)
        def rhsNeedsMacroExpansion = rhs1.symbol != null && rhs1.symbol.isTermMacro && !rhs1.symbol.isErroneous
        while (rhsNeedsMacroExpansion) {
          rhs1 = macroExpand1(typer, rhs1) match {
            case Success(expanded) =>
              try {
                val typechecked = typer.typed1(expanded, EXPRmode, WildcardType)
                macroLogVerbose("typechecked1:%n%s%n%s".format(typechecked, showRaw(typechecked)))
                typechecked
              } finally {
                openMacros = openMacros.tail
              }
            case Fallback(fallback) =>
              typer.typed1(fallback, EXPRmode, WildcardType)
            case Other(result) =>
              result
          }
        }
        val typecheckedWithErrors = (rhs1 exists (_.isErroneous)) || reporter.ERROR.count != prevNumErrors
        if (typecheckedWithErrors) MacroDefUntypeableBodyError()
        rhs1
      } catch {
        case ex: TypeError =>
          typer.reportTypeError(context, rhs.pos, ex)
          MacroDefUntypeableBodyError()
      }
    }

    // Phase II: typecheck the right-hand side of the macro def
    val typed = typecheckRhs(macroDdef.rhs)
    typed match {
      case MacroImplReference(owner, meth, targs) =>
        if (!meth.isMethod) MacroDefInvalidBodyError()
        if (!meth.isPublic) MacroImplNotPublicError()
        if (meth.isOverloaded) MacroImplOverloadedError()
        if (!owner.isStaticOwner && !owner.moduleClass.isStaticOwner) MacroImplNotStaticError()
        if (meth.typeParams.length != targs.length) MacroImplWrongNumberOfTypeArgumentsError(typed)
        bindMacroImpl(macroDef, typed)
      case _ =>
        MacroDefInvalidBodyError()
    }

    // Phase III: check compatibility between the macro def and its macro impl
    // this check ignores type tag evidence parameters, because type tag context bounds are optional
    // aXXX (e.g. aparamss) => characteristics of the macro impl ("a" stands for "actual")
    // rXXX (e.g. rparamss) => characteristics of a reference macro impl signature synthesized from the macro def ("r" stands for "reference")
    val macroImpl = typed.symbol
    val aparamss = transformTypeTagEvidenceParams(macroImpl.paramss, (param, tparam) => NoSymbol)
    val aret = macroImpl.tpe.finalResultType
    val macroDefRet =
      if (!macroDdef.tpt.isEmpty) typer.typedType(macroDdef.tpt).tpe
      else computeMacroDefTypeFromMacroImpl(macroDdef, macroImpl)
    val (rparamss, rret) = macroImplSig(macroDef, macroDdef.tparams, macroDdef.vparamss, macroDefRet)

    val implicitParams = aparamss.flatten filter (_.isImplicit)
    if (implicitParams.nonEmpty) MacroImplNonTagImplicitParameters(implicitParams)
    if (aparamss.length != rparamss.length) MacroImplParamssMismatchError()

    val atparams = macroImpl.typeParams
    val atvars = atparams map freshVar
    def atpeToRtpe(atpe: Type) = atpe.substSym(aparamss.flatten, rparamss.flatten).instantiateTypeParams(atparams, atvars)

    try {
      map2(aparamss, rparamss)((aparams, rparams) => {
        if (aparams.length < rparams.length) MacroImplMissingParamsError(aparams, rparams)
        if (rparams.length < aparams.length) MacroImplExtraParamsError(aparams, rparams)
      })

      // cannot fuse these loops because if aparamss.flatten != rparamss.flatten
      // then `atpeToRtpe` is going to fail with an unsound substitution
      map2(aparamss.flatten, rparamss.flatten)((aparam, rparam) => {
        if (aparam.name != rparam.name && !rparam.isSynthetic) MacroImplParamNameMismatchError(aparam, rparam)
        if (isRepeated(aparam) ^ isRepeated(rparam)) MacroImplVarargMismatchError(aparam, rparam)
        checkMacroImplParamTypeMismatch(stripOffPrefixTypeRefinement(atpeToRtpe(aparam.tpe)), rparam)
      })

      checkMacroImplResultTypeMismatch(atpeToRtpe(aret), rret)

      val maxLubDepth = lubDepth(aparamss.flatten map (_.tpe)) max lubDepth(rparamss.flatten map (_.tpe))
      val atargs = solvedTypes(atvars, atparams, atparams map varianceInType(aret), upper = false, depth = maxLubDepth)
      val boundsOk = typer.silent(_.infer.checkBounds(macroDdef, NoPrefix, NoSymbol, atparams, atargs, ""))
      boundsOk match {
        case SilentResultValue(true) => // do nothing, success
        case SilentResultValue(false) | SilentTypeError(_) => MacroImplTargMismatchError(atargs, atparams)
      }
    } catch {
      case ex: NoInstance => MacroImplTparamInstantiationError(atparams, ex)
    }
  }

  /** Macro classloader that is used to resolve and run macro implementations.
   *  Loads classes from from -cp (aka the library classpath).
   *  Is also capable of detecting REPL and reusing its classloader.
   */
  lazy val macroClassloader: ClassLoader = {
    if (global.forMSIL)
      throw new UnsupportedOperationException("Scala reflection not available on this platform")

    val classpath = global.classPath.asURLs
    macroLogVerbose("macro classloader: initializing from -cp: %s".format(classpath))
    val loader = ScalaClassLoader.fromURLs(classpath, self.getClass.getClassLoader)

    // a heuristic to detect the REPL
    if (global.settings.exposeEmptyPackage.value) {
      macroLogVerbose("macro classloader: initializing from a REPL classloader".format(global.classPath.asURLs))
      import scala.tools.nsc.interpreter._
      val virtualDirectory = global.settings.outputDirs.getSingleOutput.get
      new AbstractFileClassLoader(virtualDirectory, loader) {}
    } else {
      loader
    }
  }

  /** Produces a function that can be used to invoke macro implementation for a given macro definition:
   *    1) Looks up macro implementation symbol in this universe.
   *    2) Loads its enclosing class from the macro classloader.
   *    3) Loads the companion of that enclosing class from the macro classloader.
   *    4) Resolves macro implementation within the loaded companion.
   *
   *  @return Requested runtime if macro implementation can be loaded successfully from either of the mirrors,
   *          `null` otherwise.
   */
  type MacroRuntime = List[Any] => Any
  private val macroRuntimesCache = perRunCaches.newWeakMap[Symbol, MacroRuntime]
  private def macroRuntime(macroDef: Symbol): MacroRuntime = {
    macroTraceVerbose("looking for macro implementation: ")(macroDef)
    if (fastTrack contains macroDef) {
      macroLogVerbose("macro expansion is serviced by a fast track")
      fastTrack(macroDef)
    } else {
      macroRuntimesCache.getOrElseUpdate(macroDef, {
        val binding = loadMacroImplBinding(macroDef)
        val className = binding.className
        val methName = binding.methName
        macroLogVerbose(s"resolved implementation as $className.$methName")

        // I don't use Scala reflection here, because it seems to interfere with JIT magic
        // whenever you instantiate a mirror (and not do anything with in, just instantiate), performance drops by 15-20%
        // I'm not sure what's the reason - for me it's pure voodoo
        // upd. my latest experiments show that everything's okay
        // it seems that in 2.10.1 we can easily switch to Scala reflection
        try {
          macroTraceVerbose("loading implementation class: ")(className)
          macroTraceVerbose("classloader is: ")(ReflectionUtils.show(macroClassloader))
          val implObj = ReflectionUtils.staticSingletonInstance(macroClassloader, className)
          // relies on the fact that macro impls cannot be overloaded
          // so every methName can resolve to at maximum one method
          val implMeths = implObj.getClass.getDeclaredMethods.find(_.getName == methName)
          val implMeth = implMeths getOrElse { throw new NoSuchMethodException(s"$className.$methName") }
          macroLogVerbose("successfully loaded macro impl as (%s, %s)".format(implObj, implMeth))
          (args: List[Any]) => implMeth.invoke(implObj, (args map (_.asInstanceOf[AnyRef])): _*)
        } catch {
          case ex: Exception =>
            macroTraceVerbose(s"macro runtime failed to load: ")(ex.toString)
            macroDef setFlag IS_ERROR
            null
        }
      })
    }
  }

  private def macroContext(typer: Typer, prefixTree: Tree, expandeeTree: Tree): MacroContext =
    new {
      val universe: self.global.type = self.global
      val callsiteTyper: universe.analyzer.Typer = typer.asInstanceOf[global.analyzer.Typer]
      val expandee = expandeeTree
    } with UnaffiliatedMacroContext {
      // todo. infer precise typetag for this Expr, namely the PrefixType member of the Context refinement
      val prefix = Expr[Nothing](prefixTree)(TypeTag.Nothing)
      override def toString = "MacroContext(%s@%s +%d)".format(expandee.symbol.name, expandee.pos, enclosingMacros.length - 1 /* exclude myself */)
    }

  /** Calculate the arguments to pass to a macro implementation when expanding the provided tree.
   *
   *  This includes inferring the exact type and instance of the macro context to pass, and also
   *  allowing for missing parameter sections in macro implementation (see `macroImplParamsss` for more info).
   *
   *  @return list of runtime objects to pass to the implementation obtained by `macroRuntime`
   */
  private def macroArgs(typer: Typer, expandee: Tree): Option[List[Any]] = {
    val macroDef   = expandee.symbol
    val prefixTree = expandee.collect{ case Select(qual, name) => qual }.headOption.getOrElse(EmptyTree)
    val context    = expandee.attachments.get[MacroRuntimeAttachment].flatMap(_.macroContext).getOrElse(macroContext(typer, prefixTree, expandee))
    var typeArgs   = List[Tree]()
    val exprArgs   = ListBuffer[List[Expr[_]]]()
    def collectMacroArgs(tree: Tree): Unit = tree match {
      case Apply(fn, args) =>
        // todo. infer precise typetag for this Expr, namely the declared type of the corresponding macro impl argument
        exprArgs.prepend(args map (arg => context.Expr[Nothing](arg)(TypeTag.Nothing)))
        collectMacroArgs(fn)
      case TypeApply(fn, args) =>
        typeArgs = args
        collectMacroArgs(fn)
      case _ =>
    }
    collectMacroArgs(expandee)

    val argcDoesntMatch = macroDef.paramss.length != exprArgs.length
    val nullaryArgsEmptyParams = exprArgs.isEmpty && macroDef.paramss == ListOfNil
    if (argcDoesntMatch && !nullaryArgsEmptyParams) { typer.TyperErrorGen.MacroPartialApplicationError(expandee); return None }

    var argss: List[List[Any]] = List(context) :: exprArgs.toList
    macroTraceVerbose("argss: ")(argss)
    val rawArgss =
      if (fastTrack contains macroDef) {
        if (fastTrack(macroDef) validate argss) argss
        else {
          // if you're getting here, it's not necessarily partial application that is at fault
          // for example, if a signature of a hardwired macro has been changed without updated FastTrack
          // then the corresponding partial function in FastTrack will refuse to process the expandee
          // validation will return false, and control flow will end up here
          // however, for simplicity sake, I didn't introduce the notion of error handling to FastTrack
          // so all kinds of validation errors produce `MacroPartialApplicationError`
          typer.TyperErrorGen.MacroPartialApplicationError(expandee)
          return None
        }
      } else {
        val binding = loadMacroImplBinding(macroDef)
        macroTraceVerbose("binding: ")(binding)

        // if paramss have typetag context bounds, add an arglist to argss if necessary and instantiate the corresponding evidences
        // consider the following example:
        //
        //   class D[T] {
        //     class C[U] {
        //       def foo[V] = macro Impls.foo[T, U, V]
        //     }
        //   }
        //
        //   val outer1 = new D[Int]
        //   val outer2 = new outer1.C[String]
        //   outer2.foo[Boolean]
        //
        // then T and U need to be inferred from the lexical scope of the call using `asSeenFrom`
        // whereas V won't be resolved by asSeenFrom and need to be loaded directly from `expandee` which needs to contain a TypeApply node
        // also, macro implementation reference may contain a regular type as a type argument, then we pass it verbatim
        val tags = binding.signature filter (_ != -1) map (paramPos => {
          val targ = binding.targs(paramPos).tpe.typeSymbol
          val tpe = if (targ.isTypeParameterOrSkolem) {
            if (targ.owner == macroDef) {
              // doesn't work when macro def is compiled separately from its usages
              // then targ is not a skolem and isn't equal to any of macroDef.typeParams
              // val argPos = targ.deSkolemize.paramPos
              val argPos = macroDef.typeParams.indexWhere(_.name == targ.name)
              typeArgs(argPos).tpe
            } else
              targ.tpe.asSeenFrom(
                if (prefixTree == EmptyTree) macroDef.owner.tpe else prefixTree.tpe,
                macroDef.owner)
          } else
            targ.tpe
          if (tpe.isConcrete) context.TypeTag(tpe) else context.AbsTypeTag(tpe)
        })
        val hasImplicitParams = macroDef.paramss.flatten.lastOption exists (_.isImplicit)
        argss = if (hasImplicitParams) argss.dropRight(1) :+ (tags ++ argss.last) else argss :+ tags

        // transforms argss taking into account varargness of paramss
        // not all argument lists in argss map to macroDef.paramss, so we need to apply extra care
        // namely:
        // 1) the first argument list represents (c: Context) in macroImpl, so it doesn't have correspondence in macroDef
        // 2) typetag context bounds are only declared on macroImpls, so this optional arglist also doesn't match macroDef
        // nb! varargs can apply to any parameter section, not necessarily to the last one
        mapWithIndex(argss)((as, i_argss) => {
          val i_paramss = i_argss - 1
          val mapsToParamss = 0 <= i_paramss && i_paramss < macroDef.paramss.length
          if (mapsToParamss) {
            val ps = macroDef.paramss(i_paramss)
            if (isVarArgsList(ps)) as.take(ps.length - 1) :+ as.drop(ps.length - 1)
            else as
          } else as
        })
      }
    val rawArgs = rawArgss.flatten
    macroTraceVerbose("rawArgs: ")(rawArgs)
    Some(rawArgs)
  }

  /** Keeps track of macros in-flight.
   *  See more informations in comments to `openMacros` in `scala.reflect.macros.Context`.
   */
  var openMacros = List[MacroContext]()
  def enclosingMacroPosition = openMacros map (_.macroApplication.pos) find (_ ne NoPosition) getOrElse NoPosition

  /** Performs macro expansion:
   *    1) Checks whether the expansion needs to be delayed (see `mustDelayMacroExpansion`)
   *    2) Loads macro implementation using `macroMirror`
   *    3) Synthesizes invocation arguments for the macro implementation
   *    4) Checks that the result is a tree bound to this universe
   *    5) Typechecks the result against the return type of the macro definition
   *
   *  If -Ymacro-debug-lite is enabled, you will get basic notifications about macro expansion
   *  along with macro expansions logged in the form that can be copy/pasted verbatim into REPL.
   *
   *  If -Ymacro-debug-verbose is enabled, you will get detailed log of how exactly this function
   *  performs class loading and method resolution in order to load the macro implementation.
   *  The log will also include other non-trivial steps of macro expansion.
   *
   *  @return
   *    the expansion result                    if the expansion has been successful,
   *    the fallback method invocation          if the expansion has been unsuccessful, but there is a fallback,
   *    the expandee unchanged                  if the expansion has been delayed,
   *    the expandee fully expanded             if the expansion has been delayed before and has been expanded now,
   *    the expandee with an error marker set   if the expansion has been cancelled due malformed arguments or implementation
   *    the expandee with an error marker set   if there has been an error
   */
  def macroExpand(typer: Typer, expandee: Tree, mode: Int = EXPRmode, pt: Type = WildcardType): Tree = {
    def fail(what: String, tree: Tree): Tree = {
      val err = typer.context.errBuffer.head
      this.fail(typer, tree, err.errPos, "failed to %s: %s".format(what, err.errMsg))
      return expandee
    }
    val start = Statistics.startTimer(macroExpandNanos)
    Statistics.incCounter(macroExpandCount)
    try {
      macroExpand1(typer, expandee) match {
        case Success(expanded0) =>
          try {
            val expanded = expanded0 // virtpatmat swallows the local for expandee from the match
                                     // so I added this dummy local for the ease of debugging
            var expectedTpe = expandee.tpe

            val isNullaryInvocation = expandee match {
              case TypeApply(Select(_, _), _) => true
              case TypeApply(Ident(_), _) => true
              case Select(_, _) => true
              case Ident(_) => true
              case _ => false
            }
            if (isNullaryInvocation) expectedTpe match {
              case NullaryMethodType(restpe) =>
                macroTraceVerbose("nullary invocation of a nullary method. unwrapping expectedTpe from " + expectedTpe + " to: ")(restpe)
                expectedTpe = restpe
              case MethodType(Nil, restpe) =>
                macroTraceVerbose("nullary invocation of a method with an empty parameter list. unwrapping expectedTpe from " + expectedTpe + " to: ")(restpe)
                expectedTpe = restpe
              case _ => ;
            }

            macroLogVerbose("typechecking1 against %s: %s".format(expectedTpe, expanded))
            var typechecked = typer.context.withImplicitsEnabled(typer.typed(expanded, EXPRmode, expectedTpe))
            if (typer.context.hasErrors) fail("typecheck against macro def return type", expanded)
            macroLogVerbose("typechecked1:%n%s%n%s".format(typechecked, showRaw(typechecked)))

            macroLogVerbose("typechecking2 against %s: %s".format(pt, expanded))
            typechecked = typer.context.withImplicitsEnabled(typer.typed(typechecked, EXPRmode, pt))
            if (typer.context.hasErrors) fail("typecheck against expected type", expanded)
            macroLogVerbose("typechecked2:%n%s%n%s".format(typechecked, showRaw(typechecked)))

            typechecked addAttachment MacroExpansionAttachment(expandee)
          } finally {
            openMacros = openMacros.tail
          }
        case Fallback(fallback) =>
          typer.context.withImplicitsEnabled(typer.typed(fallback, EXPRmode, pt))
        case Other(result) =>
          result
      }
    } finally {
      Statistics.stopTimer(macroExpandNanos, start)
    }
  }

  private sealed abstract class MacroExpansionResult extends Product with Serializable
  private case class Success(expanded: Tree) extends MacroExpansionResult
  private case class Fallback(fallback: Tree) extends MacroExpansionResult
  private case class Other(result: Tree) extends MacroExpansionResult
  private def Delay(expanded: Tree) = Other(expanded)
  private def Skip(expanded: Tree) = Other(expanded)
  private def Cancel(expandee: Tree) = Other(expandee)
  private def Failure(expandee: Tree) = Other(expandee)
  private def fail(typer: Typer, expandee: Tree, pos: Position = NoPosition, msg: String = null) = {
    def msgForLog = if (msg != null && (msg contains "exception during macro expansion")) msg.split(EOL).drop(1).headOption.getOrElse("?") else msg
    macroLogLite("macro expansion has failed: %s".format(msgForLog))
    val errorPos = if (pos != NoPosition) pos else (if (expandee.pos != NoPosition) expandee.pos else enclosingMacroPosition)
    if (msg != null) typer.context.error(errorPos, msg)
    typer.infer.setError(expandee)
    Failure(expandee)
  }

  /** Does the same as `macroExpand`, but without typechecking the expansion
   *  Meant for internal use within the macro infrastructure, don't use it elsewhere.
   */
  private def macroExpand1(typer: Typer, expandee: Tree): MacroExpansionResult =
    // InfoLevel.Verbose examines and prints out infos of symbols
    // by the means of this'es these symbols can climb up the lexical scope
    // when these symbols will be examined by a node printer
    // they will enumerate and analyze their children (ask for infos and tpes)
    // if one of those children involves macro expansion, things might get nasty
    // that's why I'm temporarily turning this behavior off
    withInfoLevel(nodePrinters.InfoLevel.Quiet) {
      // if a macro implementation is incompatible or any of the arguments are erroneous
      // there is no sense to expand the macro itself => it will only make matters worse
      if (expandee.symbol.isErroneous || (expandee exists (_.isErroneous))) {
        val reason = if (expandee.symbol.isErroneous) "not found or incompatible macro implementation" else "erroneous arguments"
        macroTraceVerbose("cancelled macro expansion because of %s: ".format(reason))(expandee)
        return Cancel(typer.infer.setError(expandee))
      }

      val runtime = macroRuntime(expandee.symbol)
      if (runtime != null) macroExpandWithRuntime(typer, expandee, runtime)
      else macroExpandWithoutRuntime(typer, expandee)
    }

  /** Expands a macro when a runtime (i.e. the macro implementation) can be successfully loaded
   *  Meant for internal use within the macro infrastructure, don't use it elsewhere.
   */
  private def macroExpandWithRuntime(typer: Typer, expandee: Tree, runtime: MacroRuntime): MacroExpansionResult = {
    def issueFreeError(sym: FreeSymbol) = {
      val template = (
          "Macro expansion contains free @kind@ variable %s. Have you forgotten to use %s? "
        + "If you have troubles tracking free @kind@ variables, consider using -Xlog-free-@kind@s"
      )
      val forgotten = (
        if (sym.isTerm) "splice when splicing this variable into a reifee"
        else "c.AbsTypeTag annotation for this type parameter"
      )
      typer.context.error(expandee.pos,
        template.replaceAllLiterally("@kind@", sym.name.nameKind).format(
          sym.name + " " + sym.origin, forgotten)
      )
    }
    def macroExpandInternal = {
      val wasDelayed  = isDelayed(expandee)
      val undetparams = calculateUndetparams(expandee)
      val nowDelayed  = !typer.context.macrosEnabled || undetparams.nonEmpty

      def failExpansion(msg: String = null) = fail(typer, expandee, msg = msg)
      def performExpansion(args: List[Any]): MacroExpansionResult = {
        val numErrors    = reporter.ERROR.count
        def hasNewErrors = reporter.ERROR.count > numErrors

        val expanded = runtime(args)

        if (hasNewErrors)
          failExpansion() // errors have been reported by the macro itself
        else expanded match {
          case expanded: Expr[_] =>
            macroLogVerbose("original:")
            macroLogLite("" + expanded.tree + "\n" + showRaw(expanded.tree))

            expanded.tree.freeTerms foreach issueFreeError
            expanded.tree.freeTypes foreach issueFreeError
            if (hasNewErrors) failExpansion()

            // inherit the position from the first position-ful expandee in macro callstack
            // this is essential for sane error messages
            // now macro expansion gets typechecked against the macro definition return type
            // however, this happens in macroExpand, not here in macroExpand1
            else Success(atPos(enclosingMacroPosition.focus)(expanded.tree))
          case _ =>
            failExpansion(
              "macro must return a compiler-specific expr; returned value is " + (
                if (expanded.isInstanceOf[Expr[_]]) " Expr, but it doesn't belong to this compiler's universe"
                else " of " + expanded.getClass
              )
            )
        }
      }

      if (wasDelayed) {
        if (nowDelayed) Delay(expandee)
        else Skip(macroExpandAll(typer, expandee))
      }
      else {
        macroLogLite("typechecking macro expansion %s at %s".format(expandee, expandee.pos))
        macroArgs(typer, expandee).fold(failExpansion(): MacroExpansionResult) {
          args => (args: @unchecked) match {
            // crashes virtpatmat:
            // case args @ ((context: MacroContext) :: _) =>
            // todo. extract a minimized test case
            case args @ (context0 :: _) =>
              val context = context0.asInstanceOf[MacroContext]
              if (nowDelayed) {
                macroLogLite("macro expansion is delayed: %s".format(expandee))
                delayed += expandee -> undetparams
                // need to save typer context for `macroExpandAll`
                // need to save macro context to preserve enclosures
                expandee addAttachment MacroRuntimeAttachment(delayed = true, typerContext = typer.context, macroContext = Some(context.asInstanceOf[MacroContext]))
                Delay(expandee)
              }
              else {
                // adding stuff to openMacros is easy, but removing it is a nightmare
                // it needs to be sprinkled over several different code locations
                // why? https://github.com/scala/scala/commit/bd3eacbae21f39b1ac7fe8ade4ed71fa98e1a28d#L2R1137
                // todo. will be improved
                openMacros ::= context
                var isSuccess = false
                try performExpansion(args) match {
                  case x: Success => isSuccess = true ; x
                  case x          => x
                }
                finally {
                  expandee.removeAttachment[MacroRuntimeAttachment]
                  if (!isSuccess) openMacros = openMacros.tail
                }
              }
          }
        }
      }
    }

    try macroExpandInternal
    catch { case ex: Throwable => handleMacroExpansionException(typer, expandee, ex) }
  }

  private def macroExpandWithoutRuntime(typer: Typer, expandee: Tree): MacroExpansionResult = {
    val macroDef = expandee.symbol
    def notFound() = {
      typer.context.error(expandee.pos, "macro implementation not found: " + macroDef.name + " " +
        "(the most common reason for that is that you cannot use macro implementations in the same compilation run that defines them)")
      None
    }
    def fallBackToOverridden(tree: Tree): Option[Tree] = {
      tree match {
        case Select(qual, name) if (macroDef.isTermMacro) =>
          macroDef.allOverriddenSymbols match {
            case first :: _ =>
              Some(Select(qual, name) setPos tree.pos setSymbol first)
            case _ =>
              macroTraceVerbose("macro is not overridden: ")(tree)
              notFound()
          }
        case Apply(fn, args) =>
          fallBackToOverridden(fn) match {
            case Some(fn1) => Some(Apply(fn1, args) setPos tree.pos)
            case _         => None
          }
        case TypeApply(fn, args) =>
          fallBackToOverridden(fn) match {
            case Some(fn1) => Some(TypeApply(fn1, args) setPos tree.pos)
            case _         => None
          }
        case _ =>
          macroTraceVerbose("unexpected tree in fallback: ")(tree)
          notFound()
      }
    }
    fallBackToOverridden(expandee) match {
      case Some(tree1) =>
        macroTraceLite("falling back to: ")(tree1)
        currentRun.macroExpansionFailed = true
        Fallback(tree1)
      case None =>
        fail(typer, expandee)
    }
  }

  private def handleMacroExpansionException(typer: Typer, expandee: Tree, ex: Throwable): MacroExpansionResult = {
    val realex = ReflectionUtils.unwrapThrowable(ex)
    realex match {
      case realex: reflect.macros.runtime.AbortMacroException =>
        macroLogVerbose("macro expansion has failed: %s".format(realex.msg))
        fail(typer, expandee) // error has been reported by abort
      case err: TypeError =>
        macroLogLite("macro expansion has failed: %s at %s".format(err.msg, err.pos))
        throw err // error should be propagated, don't report
      case _ =>
        val message = {
          try {
            // [Eugene] is there a better way?
            // [Paul] See Exceptional.scala and Origins.scala.
            val relevancyThreshold = realex.getStackTrace().indexWhere(_.getMethodName endsWith "macroExpand1")
            if (relevancyThreshold == -1) None
            else {
              var relevantElements = realex.getStackTrace().take(relevancyThreshold + 1)
              def isMacroInvoker(este: StackTraceElement) = este.isNativeMethod || (este.getClassName != null && (este.getClassName contains "fastTrack"))
              var threshold = relevantElements.reverse.indexWhere(isMacroInvoker) + 1
              while (threshold != relevantElements.length && isMacroInvoker(relevantElements(relevantElements.length - threshold - 1))) threshold += 1
              relevantElements = relevantElements dropRight threshold

              realex.setStackTrace(relevantElements)
              val message = new java.io.StringWriter()
              realex.printStackTrace(new java.io.PrintWriter(message))
              Some(EOL + message)
            }
          } catch {
            // if the magic above goes boom, just fall back to uninformative, but better than nothing, getMessage
            case ex: Throwable =>
              None
          }
        } getOrElse {
          val msg = realex.getMessage
          if (msg != null) msg else realex.getClass.getName
        }
        fail(typer, expandee, msg = "exception during macro expansion: " + message)
    }
  }

  /** Without any restrictions on macro expansion, macro applications will expand at will,
   *  and when type inference is involved, expansions will end up using yet uninferred type params.
   *
   *  For some macros this might be ok (thanks to TreeTypeSubstituter that replaces
   *  the occurrences of undetparams with their inferred values), but in general case this won't work.
   *  E.g. for reification simple substitution is not enough - we actually need to re-reify inferred types.
   *
   *  Luckily, there exists a very simple way to fix the problem: delay macro expansion until everything is inferred.
   *  Here are the exact rules. Macro application gets delayed if any of its subtrees contain:
   *    1) type vars (tpe.isInstanceOf[TypeVar]) // [Eugene] this check is disabled right now, because TypeVars seem to be created from undetparams anyways
   *    2) undetparams (sym.isTypeParameter && !sym.isSkolem)
   */
  var hasPendingMacroExpansions = false
  private val delayed = perRunCaches.newWeakMap[Tree, collection.mutable.Set[Int]]
  private def isDelayed(expandee: Tree) = delayed contains expandee
  private def calculateUndetparams(expandee: Tree): collection.mutable.Set[Int] =
    delayed.get(expandee).getOrElse {
      val calculated = collection.mutable.Set[Symbol]()
      expandee foreach (sub => {
        def traverse(sym: Symbol) = if (sym != null && (undetparams contains sym.id)) calculated += sym
        if (sub.symbol != null) traverse(sub.symbol)
        if (sub.tpe != null) sub.tpe foreach (sub => traverse(sub.typeSymbol))
      })
      macroLogVerbose("calculateUndetparams: %s".format(calculated))
      calculated map (_.id)
    }
  private val undetparams = perRunCaches.newSet[Int]
  def notifyUndetparamsAdded(newUndets: List[Symbol]): Unit = {
    undetparams ++= newUndets map (_.id)
    if (macroDebugVerbose) newUndets foreach (sym => println("undetParam added: %s".format(sym)))
  }
  def notifyUndetparamsInferred(undetNoMore: List[Symbol], inferreds: List[Type]): Unit = {
    undetparams --= undetNoMore map (_.id)
    if (macroDebugVerbose) (undetNoMore zip inferreds) foreach { case (sym, tpe) => println("undetParam inferred: %s as %s".format(sym, tpe))}
    if (!delayed.isEmpty)
      delayed.toList foreach {
        case (expandee, undetparams) if !undetparams.isEmpty =>
          undetparams --= undetNoMore map (_.id)
          if (undetparams.isEmpty) {
            hasPendingMacroExpansions = true
            macroTraceVerbose("macro expansion is pending: ")(expandee)
          }
        case _ =>
          // do nothing
      }
  }

  /** Performs macro expansion on all subtrees of a given tree.
   *  Innermost macros are expanded first, outermost macros are expanded last.
   *  See the documentation for `macroExpand` for more information.
   */
  def macroExpandAll(typer: Typer, expandee: Tree): Tree =
    new Transformer {
      override def transform(tree: Tree) = super.transform(tree match {
        // todo. expansion should work from the inside out
        case wannabe if (delayed contains wannabe) && calculateUndetparams(wannabe).isEmpty =>
          val context = wannabe.attachments.get[MacroRuntimeAttachment].get.typerContext
          delayed -= wannabe
          context.implicitsEnabled = typer.context.implicitsEnabled
          context.enrichmentEnabled = typer.context.enrichmentEnabled
          context.macrosEnabled = typer.context.macrosEnabled
          macroExpand(newTyper(context), wannabe, EXPRmode, WildcardType)
        case _ =>
          tree
      })
    }.transform(expandee)
}

object MacrosStats {
  import reflect.internal.TypesStats.typerNanos
  val macroExpandCount    = Statistics.newCounter ("#macro expansions", "typer")
  val macroExpandNanos    = Statistics.newSubTimer("time spent in macroExpand", typerNanos)
}
