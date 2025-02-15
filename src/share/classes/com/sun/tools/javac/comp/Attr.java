/*
 * Copyright (c) 1999, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.comp;

import java.util.*;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.*;
import static com.sun.tools.javac.code.Flags.ANNOTATION;
import static com.sun.tools.javac.code.Flags.BLOCK;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;

import com.sun.tools.javac.jvm.Target;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.comp.Check.CheckContext;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.util.SimpleTreeVisitor;

// Panini code
import org.paninij.util.PaniniConstants;
// end Panini code

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Kinds.*;
import static com.sun.tools.javac.code.Kinds.ERRONEOUS;
import static com.sun.tools.javac.code.TypeTags.*;
import static com.sun.tools.javac.code.TypeTags.WILDCARD;
import static com.sun.tools.javac.tree.JCTree.Tag.*;

/** This is the main context-dependent analysis phase in GJC. It
 *  encompasses name resolution, type checking and constant folding as
 *  subtasks. Some subtasks involve auxiliary classes.
 *  @see Check
 *  @see Resolve
 *  @see ConstFold
 *  @see Infer
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Attr extends JCTree.Visitor {
    protected static final Context.Key<Attr> attrKey =
        new Context.Key<Attr>();

    final Names names;
    final Log log;
    final Symtab syms;
    final Resolve rs;
    final Infer infer;
    final Check chk;
    final MemberEnter memberEnter;
    final TreeMaker make;
    final ConstFold cfolder;
    final Enter enter;
    final Target target;
    final Types types;
    final JCDiagnostic.Factory diags;
    final Annotate annotate;
    final DeferredLintHandler deferredLintHandler;
    // Panini code
    public org.paninij.comp.Attr pAttr;
    public boolean doGraphs = false;
    // end Panini code

    public static Attr instance(Context context) {
        Attr instance = context.get(attrKey);
        if (instance == null)
            instance = new Attr(context);
        return instance;
    }

    protected Attr(Context context) {
        context.put(attrKey, this);

        names = Names.instance(context);
        log = Log.instance(context);
        syms = Symtab.instance(context);
        rs = Resolve.instance(context);
        chk = Check.instance(context);
        memberEnter = MemberEnter.instance(context);
        make = TreeMaker.instance(context);
        enter = Enter.instance(context);
        infer = Infer.instance(context);
        cfolder = ConstFold.instance(context);
        target = Target.instance(context);
        types = Types.instance(context);
        diags = JCDiagnostic.Factory.instance(context);
        annotate = Annotate.instance(context);
        deferredLintHandler = DeferredLintHandler.instance(context);

        Options options = Options.instance(context);

        Source source = Source.instance(context);
        allowGenerics = source.allowGenerics();
        allowVarargs = source.allowVarargs();
        allowEnums = source.allowEnums();
        allowBoxing = source.allowBoxing();
        allowCovariantReturns = source.allowCovariantReturns();
        allowAnonOuterThis = source.allowAnonOuterThis();
        allowStringsInSwitch = source.allowStringsInSwitch();
        sourceName = source.name;
        relax = (options.isSet("-retrofit") ||
                 options.isSet("-relax"));
        findDiamonds = options.get("findDiamond") != null &&
                 source.allowDiamond();
        useBeforeDeclarationWarning = options.isSet("useBeforeDeclarationWarning");

        statInfo = new ResultInfo(NIL, Type.noType);
        varInfo = new ResultInfo(VAR, Type.noType);
        unknownExprInfo = new ResultInfo(VAL, Type.noType);
        unknownTypeInfo = new ResultInfo(TYP, Type.noType);

        // Panini code
        pAttr = org.paninij.comp.Attr.instance(context);
        doGraphs = options.isSet("-graphs");
        // end Panini code
    }

    /** Switch: relax some constraints for retrofit mode.
     */
    boolean relax;

    /** Switch: support generics?
     */
    boolean allowGenerics;

    /** Switch: allow variable-arity methods.
     */
    boolean allowVarargs;

    /** Switch: support enums?
     */
    boolean allowEnums;

    /** Switch: support boxing and unboxing?
     */
    boolean allowBoxing;

    /** Switch: support covariant result types?
     */
    boolean allowCovariantReturns;

    /** Switch: allow references to surrounding object from anonymous
     * objects during constructor call?
     */
    boolean allowAnonOuterThis;

    /** Switch: generates a warning if diamond can be safely applied
     *  to a given new expression
     */
    boolean findDiamonds;

    /**
     * Internally enables/disables diamond finder feature
     */
    static final boolean allowDiamondFinder = true;

    /**
     * Switch: warn about use of variable before declaration?
     * RFE: 6425594
     */
    boolean useBeforeDeclarationWarning;

    /**
     * Switch: allow strings in switch?
     */
    boolean allowStringsInSwitch;

    /**
     * Switch: name of source level; used for error reporting.
     */
    String sourceName;

    /** Check kind and type of given tree against protokind and prototype.
     *  If check succeeds, store type in tree and return it.
     *  If check fails, store errType in tree and return it.
     *  No checks are performed if the prototype is a method type.
     *  It is not necessary in this case since we know that kind and type
     *  are correct.
     *
     *  @param tree     The tree whose kind and type is checked
     *  @param owntype  The computed type of the tree
     *  @param ownkind  The computed kind of the tree
     *  @param resultInfo  The expected result of the tree
     */
    Type check(JCTree tree, Type owntype, int ownkind, ResultInfo resultInfo) {
        if (owntype.tag != ERROR && resultInfo.pt.tag != METHOD && resultInfo.pt.tag != FORALL ) {
            if ((ownkind & ~resultInfo.pkind) == 0) {
                owntype = resultInfo.check(tree, owntype);
            } else {
                log.error(tree.pos(), "unexpected.type",
                          kindNames(resultInfo.pkind),
                          kindName(ownkind));
                owntype = types.createErrorType(owntype);
            }
        }
        tree.type = owntype;
        return owntype;
    }

    /** Is given blank final variable assignable, i.e. in a scope where it
     *  may be assigned to even though it is final?
     *  @param v      The blank final variable.
     *  @param env    The current environment.
     */
    boolean isAssignableAsBlankFinal(VarSymbol v, Env<AttrContext> env) {
        Symbol owner = env.info.scope.owner;
           // owner refers to the innermost variable, method or
           // initializer block declaration at this point.
        return
            v.owner == owner
            ||
            ((owner.name == names.init ||    // i.e. we are in a constructor
              owner.kind == VAR ||           // i.e. we are in a variable initializer
              (owner.flags() & BLOCK) != 0)  // i.e. we are in an initializer block
             &&
             v.owner == owner.owner
             &&
             ((v.flags() & STATIC) != 0) == Resolve.isStatic(env));
    }

    /** Check that variable can be assigned to.
     *  @param pos    The current source code position.
     *  @param v      The assigned varaible
     *  @param base   If the variable is referred to in a Select, the part
     *                to the left of the `.', null otherwise.
     *  @param env    The current environment.
     */
    void checkAssignable(DiagnosticPosition pos, VarSymbol v, JCTree base, Env<AttrContext> env) {
        if ((v.flags() & FINAL) != 0 &&
            ((v.flags() & HASINIT) != 0
             ||
             !((base == null ||
               (base.hasTag(IDENT) && TreeInfo.name(base) == names._this)) &&
               isAssignableAsBlankFinal(v, env)))) {
            if (v.isResourceVariable()) { //TWR resource
                log.error(pos, "try.resource.may.not.be.assigned", v);
            } else {
                log.error(pos, "cant.assign.val.to.final.var", v);
            }
        } else if ((v.flags() & EFFECTIVELY_FINAL) != 0) {
            v.flags_field &= ~EFFECTIVELY_FINAL;
        }
    }

    /** Does tree represent a static reference to an identifier?
     *  It is assumed that tree is either a SELECT or an IDENT.
     *  We have to weed out selects from non-type names here.
     *  @param tree    The candidate tree.
     */
    boolean isStaticReference(JCTree tree) {
        if (tree.hasTag(SELECT)) {
            Symbol lsym = TreeInfo.symbol(((JCFieldAccess) tree).selected);
            if (lsym == null || lsym.kind != TYP) {
                return false;
            }
        }
        return true;
    }

    /** Is this symbol a type?
     */
    static boolean isType(Symbol sym) {
        return sym != null && sym.kind == TYP;
    }

    /** The current `this' symbol.
     *  @param env    The current environment.
     */
    Symbol thisSym(DiagnosticPosition pos, Env<AttrContext> env) {
        return rs.resolveSelf(pos, env, env.enclClass.sym, names._this);
    }

    /** Attribute a parsed identifier.
     * @param tree Parsed identifier name
     * @param topLevel The toplevel to use
     */
    public Symbol attribIdent(JCTree tree, JCCompilationUnit topLevel) {
        Env<AttrContext> localEnv = enter.topLevelEnv(topLevel);
        localEnv.enclClass = make.ClassDef(make.Modifiers(0),
                                           syms.errSymbol.name,
                                           null, null, null, null);
        localEnv.enclClass.sym = syms.errSymbol;
        return tree.accept(identAttributer, localEnv);
    }
    // where
        private TreeVisitor<Symbol,Env<AttrContext>> identAttributer = new IdentAttributer();
        private class IdentAttributer extends SimpleTreeVisitor<Symbol,Env<AttrContext>> {
            @Override
            public Symbol visitMemberSelect(MemberSelectTree node, Env<AttrContext> env) {
                Symbol site = visit(node.getExpression(), env);
                if (site.kind == ERR)
                    return site;
                Name name = (Name)node.getIdentifier();
                if (site.kind == PCK) {
                    env.toplevel.packge = (PackageSymbol)site;
                    return rs.findIdentInPackage(env, (TypeSymbol)site, name, TYP | PCK);
                } else {
                    env.enclClass.sym = (ClassSymbol)site;
                    return rs.findMemberType(env, site.asType(), name, (TypeSymbol)site);
                }
            }

            @Override
            public Symbol visitIdentifier(IdentifierTree node, Env<AttrContext> env) {
                return rs.findIdent(env, (Name)node.getName(), TYP | PCK);
            }
        }

    public Type coerce(Type etype, Type ttype) {
        return cfolder.coerce(etype, ttype);
    }

    public Type attribType(JCTree node, TypeSymbol sym) {
        Env<AttrContext> env = enter.typeEnvs.get(sym);
        Env<AttrContext> localEnv = env.dup(node, env.info.dup());
        return attribTree(node, localEnv, unknownTypeInfo);
    }

    public Type attribImportQualifier(JCImport tree, Env<AttrContext> env) {
        // Attribute qualifying package or class.
        JCFieldAccess s = (JCFieldAccess)tree.qualid;
        return attribTree(s.selected,
                       env,
                       new ResultInfo(tree.staticImport ? TYP : (TYP | PCK),
                       Type.noType));
    }

    public Env<AttrContext> attribExprToTree(JCTree expr, Env<AttrContext> env, JCTree tree) {
        breakTree = tree;
        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        try {
            attribExpr(expr, env);
        } catch (BreakAttr b) {
            return b.env;
        } catch (AssertionError ae) {
            if (ae.getCause() instanceof BreakAttr) {
                return ((BreakAttr)(ae.getCause())).env;
            } else {
                throw ae;
            }
        } finally {
            breakTree = null;
            log.useSource(prev);
        }
        return env;
    }

    public Env<AttrContext> attribStatToTree(JCTree stmt, Env<AttrContext> env, JCTree tree) {
        breakTree = tree;
        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        try {
            attribStat(stmt, env);
        } catch (BreakAttr b) {
            return b.env;
        } catch (AssertionError ae) {
            if (ae.getCause() instanceof BreakAttr) {
                return ((BreakAttr)(ae.getCause())).env;
            } else {
                throw ae;
            }
        } finally {
            breakTree = null;
            log.useSource(prev);
        }
        return env;
    }

    private JCTree breakTree = null;

    private static class BreakAttr extends RuntimeException {
        static final long serialVersionUID = -6924771130405446405L;
        private Env<AttrContext> env;
        private BreakAttr(Env<AttrContext> env) {
            this.env = env;
        }
    }

    class ResultInfo {
        int pkind;
        Type pt;
        CheckContext checkContext;

        ResultInfo(int pkind, Type pt) {
            this(pkind, pt, chk.basicHandler);
        }

        protected ResultInfo(int pkind, Type pt, CheckContext checkContext) {
            this.pkind = pkind;
            this.pt = pt;
            this.checkContext = checkContext;
        }

        protected Type check(DiagnosticPosition pos, Type found) {
            return chk.checkType(pos, found, pt, checkContext);
        }
    }

    private final ResultInfo statInfo;
    private final ResultInfo varInfo;
    private final ResultInfo unknownExprInfo;
    private final ResultInfo unknownTypeInfo;

    Type pt() {
        return resultInfo.pt;
    }

    int pkind() {
        return resultInfo.pkind;
    }

/* ************************************************************************
 * Visitor methods
 *************************************************************************/

    /** Visitor argument: the current environment.
     */
    Env<AttrContext> env;

    /** Visitor argument: the currently expected attribution result.
     */
    ResultInfo resultInfo;

    /** Visitor result: the computed type.
     */
    Type result;

    /** Visitor method: attribute a tree, catching any completion failure
     *  exceptions. Return the tree's type.
     *
     *  @param tree    The tree to be visited.
     *  @param env     The environment visitor argument.
     *  @param resultInfo   The result info visitor argument.
     */
    private Type attribTree(JCTree tree, Env<AttrContext> env, ResultInfo resultInfo) {
        Env<AttrContext> prevEnv = this.env;
        ResultInfo prevResult = this.resultInfo;
        try {
            this.env = env;
            this.resultInfo = resultInfo;
            tree.accept(this);
            if (tree == breakTree)
                throw new BreakAttr(env);
            return result;
        } catch (CompletionFailure ex) {
            tree.type = syms.errType;
            return chk.completionError(tree.pos(), ex);
        } finally {
            this.env = prevEnv;
            this.resultInfo = prevResult;
        }
    }

    /** Derived visitor method: attribute an expression tree.
     */
    public Type attribExpr(JCTree tree, Env<AttrContext> env, Type pt) {
        return attribTree(tree, env, new ResultInfo(VAL, pt.tag != ERROR ? pt : Type.noType));
    }

    /** Derived visitor method: attribute an expression tree with
     *  no constraints on the computed type.
     */
    Type attribExpr(JCTree tree, Env<AttrContext> env) {
        return attribTree(tree, env, unknownExprInfo);
    }

    /** Derived visitor method: attribute a type tree.
     */
    Type attribType(JCTree tree, Env<AttrContext> env) {
        Type result = attribType(tree, env, Type.noType);
        return result;
    }

    /** Derived visitor method: attribute a type tree.
     */
    Type attribType(JCTree tree, Env<AttrContext> env, Type pt) {
        Type result = attribTree(tree, env, new ResultInfo(TYP, pt));
        return result;
    }

    /** Derived visitor method: attribute a statement or definition tree.
     */
    public Type attribStat(JCTree tree, Env<AttrContext> env) {
        return attribTree(tree, env, statInfo);
    }

    /** Attribute a list of expressions, returning a list of types.
     */
    List<Type> attribExprs(List<JCExpression> trees, Env<AttrContext> env, Type pt) {
        ListBuffer<Type> ts = new ListBuffer<Type>();
        for (List<JCExpression> l = trees; l.nonEmpty(); l = l.tail)
            ts.append(attribExpr(l.head, env, pt));
        return ts.toList();
    }

    /** Attribute a list of statements, returning nothing.
     */
    <T extends JCTree> void attribStats(List<T> trees, Env<AttrContext> env) {
        for (List<T> l = trees; l.nonEmpty(); l = l.tail)
            attribStat(l.head, env);
    }

    /** Attribute the arguments in a method call, returning a list of types.
     */
    List<Type> attribArgs(List<JCExpression> trees, Env<AttrContext> env) {
        ListBuffer<Type> argtypes = new ListBuffer<Type>();
        for (List<JCExpression> l = trees; l.nonEmpty(); l = l.tail)
            argtypes.append(chk.checkNonVoid(
                l.head.pos(), types.upperBound(attribExpr(l.head, env, Infer.anyPoly))));
        return argtypes.toList();
    }

    /** Attribute a type argument list, returning a list of types.
     *  Caller is responsible for calling checkRefTypes.
     */
    List<Type> attribAnyTypes(List<JCExpression> trees, Env<AttrContext> env) {
        ListBuffer<Type> argtypes = new ListBuffer<Type>();
        for (List<JCExpression> l = trees; l.nonEmpty(); l = l.tail)
            argtypes.append(attribType(l.head, env));
        return argtypes.toList();
    }

    /** Attribute a type argument list, returning a list of types.
     *  Check that all the types are references.
     */
    List<Type> attribTypes(List<JCExpression> trees, Env<AttrContext> env) {
        List<Type> types = attribAnyTypes(trees, env);
        return chk.checkRefTypes(trees, types);
    }

    /**
     * Attribute type variables (of generic classes or methods).
     * Compound types are attributed later in attribBounds.
     * @param typarams the type variables to enter
     * @param env      the current environment
     */
    void attribTypeVariables(List<JCTypeParameter> typarams, Env<AttrContext> env) {
        for (JCTypeParameter tvar : typarams) {
            TypeVar a = (TypeVar)tvar.type;
            a.tsym.flags_field |= UNATTRIBUTED;
            a.bound = Type.noType;
            if (!tvar.bounds.isEmpty()) {
                List<Type> bounds = List.of(attribType(tvar.bounds.head, env));
                for (JCExpression bound : tvar.bounds.tail)
                    bounds = bounds.prepend(attribType(bound, env));
                types.setBounds(a, bounds.reverse());
            } else {
                // if no bounds are given, assume a single bound of
                // java.lang.Object.
                types.setBounds(a, List.of(syms.objectType));
            }
            a.tsym.flags_field &= ~UNATTRIBUTED;
        }
        for (JCTypeParameter tvar : typarams)
            chk.checkNonCyclic(tvar.pos(), (TypeVar)tvar.type);
        attribStats(typarams, env);
    }

    void attribBounds(List<JCTypeParameter> typarams) {
        for (JCTypeParameter typaram : typarams) {
            Type bound = typaram.type.getUpperBound();
            if (bound != null && bound.tsym instanceof ClassSymbol) {
                ClassSymbol c = (ClassSymbol)bound.tsym;
                if ((c.flags_field & COMPOUND) != 0) {
                    Assert.check((c.flags_field & UNATTRIBUTED) != 0, c);
                    attribClass(typaram.pos(), c);
                }
            }
        }
    }

    /**
     * Attribute the type references in a list of annotations.
     */
    void attribAnnotationTypes(List<JCAnnotation> annotations,
                               Env<AttrContext> env) {
        for (List<JCAnnotation> al = annotations; al.nonEmpty(); al = al.tail) {
            JCAnnotation a = al.head;
            attribType(a.annotationType, env);
        }
    }

    /**
     * Attribute a "lazy constant value".
     *  @param env         The env for the const value
     *  @param initializer The initializer for the const value
     *  @param type        The expected type, or null
     *  @see VarSymbol#setlazyConstValue
     */
    public Object attribLazyConstantValue(Env<AttrContext> env,
                                      JCTree.JCExpression initializer,
                                      Type type) {

        // in case no lint value has been set up for this env, scan up
        // env stack looking for smallest enclosing env for which it is set.
        Env<AttrContext> lintEnv = env;
        while (lintEnv.info.lint == null)
            lintEnv = lintEnv.next;

        // Having found the enclosing lint value, we can initialize the lint value for this class
        // ... but ...
        // There's a problem with evaluating annotations in the right order, such that
        // env.info.enclVar.attributes_field might not yet have been evaluated, and so might be
        // null. In that case, calling augment will throw an NPE. To avoid this, for now we
        // revert to the jdk 6 behavior and ignore the (unevaluated) attributes.
        if (env.info.enclVar.attributes_field == null)
            env.info.lint = lintEnv.info.lint;
        else
            env.info.lint = lintEnv.info.lint.augment(env.info.enclVar.attributes_field, env.info.enclVar.flags());

        Lint prevLint = chk.setLint(env.info.lint);
        JavaFileObject prevSource = log.useSource(env.toplevel.sourcefile);

        try {
            Type itype = attribExpr(initializer, env, type);
            if (itype.constValue() != null)
                return coerce(itype, type).constValue();
            else
                return null;
        } finally {
            env.info.lint = prevLint;
            log.useSource(prevSource);
        }
    }

    /** Attribute type reference in an `extends' or `implements' clause.
     *  Supertypes of anonymous inner classes are usually already attributed.
     *
     *  @param tree              The tree making up the type reference.
     *  @param env               The environment current at the reference.
     *  @param classExpected     true if only a class is expected here.
     *  @param interfaceExpected true if only an interface is expected here.
     */
    Type attribBase(JCTree tree,
                    Env<AttrContext> env,
                    boolean classExpected,
                    boolean interfaceExpected,
                    boolean checkExtensible) {
        Type t = tree.type != null ?
            tree.type :
            attribType(tree, env);
        return checkBase(t, tree, env, classExpected, interfaceExpected, checkExtensible);
    }
    Type checkBase(Type t,
                   JCTree tree,
                   Env<AttrContext> env,
                   boolean classExpected,
                   boolean interfaceExpected,
                   boolean checkExtensible) {
        if (t.isErroneous())
            return t;
        if (t.tag == TYPEVAR && !classExpected && !interfaceExpected) {
            // check that type variable is already visible
            if (t.getUpperBound() == null) {
                log.error(tree.pos(), "illegal.forward.ref");
                return types.createErrorType(t);
                
                
            }
        } else {
            t = chk.checkClassType(tree.pos(), t, checkExtensible|!allowGenerics);
        }
        if (interfaceExpected && (t.tsym.flags() & INTERFACE) == 0) {
            log.error(tree.pos(), "intf.expected.here");
            // return errType is necessary since otherwise there might
            // be undetected cycles which cause attribution to loop
            return types.createErrorType(t);
        } else if (checkExtensible &&
                   classExpected &&
                   (t.tsym.flags() & INTERFACE) != 0) {
        	// Panini code
			if (t.tsym.isCapsule()) {
				chk.checkNonCyclic(tree.pos(), t);
				return t;
			}
        	// end Panini code
                log.error(tree.pos(), "no.intf.expected.here");
            return types.createErrorType(t);
        }
        if (checkExtensible &&
            ((t.tsym.flags() & FINAL) != 0)) {
            log.error(tree.pos(),
                      "cant.inherit.from.final", t.tsym);
        }
        chk.checkNonCyclic(tree.pos(), t);
        return t;
    }
    
    // Panini code
    /**Check the wiring for a capsule instance.
     * If the check succeeds, store the wiring type in tree and return it.
     * If the check fails, store errType in tree and return it.
     *
     * Based on {@link #check(JCTree, Type, int, ResultInfo)}. This version
     * behaves a little bit differently because it iterates/checks the wiring
     * argument types before assigning the final type to tree. In that respect,
     * this method is more like checking a method invocation, but is much simpler.
     *
     * @param tree capsule wiring/topology tree.
     * @param capsuletype type of the capsule to wire
     * @param argtrees arguments for wiring
     * @param argtypes argument types.
     */
    Type checkWiring(JCTree tree, Type capsuletype, List<JCExpression> argtrees, List<Type> argtypes) {
        Type wiringtype = types.createErrorType(capsuletype);
        if( !capsuletype.tsym.isCapsule() ) {
            log.error(tree.pos(), "only.capsule.types.allowed", capsuletype);
            return wiringtype;
        }
        CapsuleExtras cinfo = ((ClassSymbol)capsuletype.tsym).capsule_info;

        wiringtype = checkWiringArgs(tree, wiringtype, cinfo.wiringSym,
                argtrees, argtypes);

        tree.type = wiringtype;
        return wiringtype;
    }

    /**
     * Check each argument against its prototype from the wiring symbol type.
     * @param tree
     * @param owntype
     * @param ownsym symbol for the type being wired. Must be a ClassSymbol for a capsule.
     * @param argtrees
     * @param argtypes
     * @return
     */
    private Type checkWiringArgs(JCTree tree, Type owntype, Symbol wiringSym, List<JCExpression> argtrees, List<Type> argtypes) {
        

        List<Type> wt = wiringSym.type.getParameterTypes();
        List<JCExpression> as = argtrees;
        List<Type> ats = argtypes;

        if(wt.size() != as.size()) {
            log.error(tree, "wiring.args.count.mismatch", wt.size(), as.size());
        }

        if(wt.size() < 1) { //nothing to do.
            return wiringSym.type;
        }

        boolean wiringOkay = true;
        while(wt.nonEmpty()){
            if( wt.head.tsym.isCapsule() ){//if its a capsule type
                if(as.head.toString().equals("null")){
                    log.error(as.head.pos(), "capsule.null.declare");
                }
            }

            // redo our check here. The stock version assigns
            // the type to the tree when it finishes, which we do not want
            // in this context.
            Type aType = ats.head;
            ResultInfo wireResult = new ResultInfo(VAL, wt.head);

            if (aType.tag != ERROR && wireResult.pt.tag != METHOD && resultInfo.pt.tag != FORALL) {
                Type res = wireResult.check(as.head, aType);
                wiringOkay &= res.tag != ERROR; //check for a mismatch in arg wiring
            } else {
                log.error(as.head.pos(), "unexpected.type",
                          wireResult.pt, aType);
                wiringOkay = false;
            }

            wt = wt.tail;
            as = as.tail;
            ats = ats.tail;
        }

        return wiringOkay ? wiringSym.type : owntype;
    }

    /**
     * Utility method to check a expression is a capsule array
     * @return the Capsule type for the array, or an error type.
     */
    Type checkCapsuleArray(JCExpression tree, Env<AttrContext> env) {
        Type owntype = types.createErrorType(tree.type);
        Type atype   = attribExpr(tree, env);
        if( types.isArray(atype) ) {
            //Will need to handle nested arrays eventually.
            owntype = types.elemtype(atype);
            if(!syms.capsules.containsKey(owntype.tsym.name)) {
                log.error(tree.pos(), "only.capsule.types.allowed");
            }
        } else {
            log.error(tree.pos(), "array.req.but.not.found", atype);
        }
        return owntype;
    }

    public final void visitCapsuleDef(final JCCapsuleDecl tree){
    	pAttr.visitCapsuleDef(tree, this, env, rs);
    }

    @Override
    public void visitCapsuleWiring(JCCapsuleWiring tree) {
        Type owntype = attribExpr(tree.capsule, env);
        List<Type> argtypes = attribArgs(tree.args,  env);
        if( owntype.tag != ERROR) {
            checkWiring(tree, owntype, tree.args, argtypes);
        }
    }

    @Override
    public void visitIndexedCapsuleWiring(JCCapsuleArrayCall tree) {
        attribExpr(tree.index, env, syms.intType);
        Type owntype = checkCapsuleArray(tree.indexed, env);

        List<Type> argtypes = attribArgs(tree.arguments, env);
        checkWiring(tree, owntype, tree.arguments, argtypes);
    }

    @Override
    public void visitCapsuleLambda(JCCapsuleLambda tree){
    	pAttr.visitCapsuleLambda(tree, this, env, rs);
    }
    
    @Override
    public void visitPrimitiveCapsuleLambda(JCPrimitiveCapsuleLambda tree){
    	pAttr.visitPrimitiveCapsuleLambda(tree, this, env, rs);
    }

    @Override
    public void visitCapsuleArray(JCCapsuleArray tree) {
        visitTypeArray(tree);
    }

    /**
     * Adapted from {@link #visitMethodDef(JCMethodDecl)}.
     */
    public final void visitDesignBlock(final JCDesignBlock tree){
        final MethodSymbol m = tree.sym;

        // Create a new environment with local scope
        // for attributing the method.
        Env<AttrContext> localEnv = memberEnter.methodEnv(tree, env);

        Lint lint = env.info.lint.augment(m.attributes_field, m.flags());
        Lint prevLint = chk.setLint(lint);

        try {
            deferredLintHandler.flush(tree.pos());

            // Enter all type parameters into the local method scope.
            //TODO: Do we have type parameters for systems?
            for (List<JCTypeParameter> l = tree.typarams; l.nonEmpty(); l = l.tail)
                localEnv.info.scope.enterIfAbsent(l.head.type.tsym);

            ClassSymbol owner = env.enclClass.sym;
            if ((owner.flags() & ANNOTATION) != 0 &&
                    tree.params.nonEmpty())
                log.error(tree.params.head.pos(),
                        "intf.annotation.members.cant.have.params");


            //SystemDecls may have 0 or exactly 1 argument of type String[]
            if(tree.params.size() <= 1) {
                // Attribute all input parameters.
                for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                    attribStat(l.head, localEnv);
                    check(l.head, l.head.type, l.head.sym.kind, new ResultInfo(
                            VAR,
                            new ArrayType(syms.stringType, syms.arrayClass)));
                }
            } else {
                log.error(tree.params.tail.head.pos, "system.argument.illegal");
            }

            // Check that type parameters are well-formed.
            chk.validate(tree.typarams, localEnv);

            // Attribute the body statements.
            for(List<JCStatement> l = tree.body.stats; l.nonEmpty(); l = l.tail) {
                attribStat(l.head, localEnv);
            }

            
            //we only do translation if we have no errors, this is necessary because
            //we don't want errors to cause incorrect translation or crashes.
            if (log.nerrors == 0) {
                // visit the system def for rewriting and analysis.
                // will re-write the body before doing the 'main' statement
                // attribution.
                pAttr.visitSystemDef(tree, rs, this, localEnv, doGraphs);
            }

            localEnv.info.scope.leave();
            result = tree.type = m.type;
        } finally {
            chk.setLint(prevLint);
        }
    }
    
    public void visitProcDef(JCProcDecl tree){
    	pAttr.visitProcDef(tree);
    }
    

    @Override
    public void visitStateDef(JCStateDecl tree) {
        visitVarDef(tree);
        pAttr.visitStateDef(tree);
    }

    @Override
    public void visitProcApply(JCProcInvocation tree) {
        //TODO: Extend the env?

        //find the capsule instance.
        //attribute wiring, maybe?
        //check the args.
        List<Type> argTypes = attribArgs(tree.args, env);
        //Find the Capsule type for the name of the symbol
        Type mt = newMethTemplate(List.<Type>nil(), argTypes);
        //localEnv.info.varArgs = false;
        tree.type =  attribExpr(tree.meth, env, mt);
        result = tree.type;
    }

    public void visitForeach(JCForeach tree){
    	
    	attribStat(tree.var, env);
    	attribExpr(tree.carr, env);
    	attribExpr(tree.body, env);
    	Type retType = tree.body.meth.type.getReturnType();
    	Type proto = new ArrayType(retType, syms.arrayClass);
    	chk.checkType(tree.pos(), proto, resultInfo.pt);
    	result = proto;
    	tree.endNodes = new java.util.LinkedList<JCTree>();
    	tree.successors = new java.util.LinkedList<JCTree>();
    	tree.predecessors = new java.util.LinkedList<JCTree>();
    	tree.startNodes = new java.util.LinkedList<JCTree>();
    	tree.type = proto;
    }


    @Override
    public void visitWireall(JCWireall tree) {
        arrangeWiringOperatorArgs(tree);
        Type mType = checkCapsuleArray(tree.many, env);
        List<Type> argtypes = attribArgs(tree.args, env);
        checkWiring(tree, mType, tree.args, argtypes);
    }

    @Override
    public void visitRing(JCRing tree) {
        arrangeWiringOperatorArgs(tree);
        attribExpr(tree.capsules, env);
        Type ctype = checkCapsuleArray(tree.capsules, env);
        List<Type> argtypes = attribArgs(tree.args, env);

        //Make up a new list to type check against.
        //The capusules arg is also an argument.
        ListBuffer<JCExpression> was = new ListBuffer<JCExpression>();
        was.add(tree.capsules); was.addAll(tree.args);
        ListBuffer<Type> wts = new ListBuffer<Type>();
        wts.add(ctype); wts.addAll(argtypes);

        checkWiring(tree, ctype, was.toList(), wts.toList());
    }

    @Override
    public void visitStar(JCStar tree) {
        arrangeWiringOperatorArgs(tree);
        Type centerT = attribExpr(tree.center, env);
        checkCapsuleArray(tree.others, env);
        List<Type> argtypes = attribArgs(tree.args, env);

        ListBuffer<JCExpression> was = new ListBuffer<JCExpression>();
        ListBuffer<Type> wts = new ListBuffer<Type>();

        //Center needs be wired to a caparray of othertype, with args.

        was.add(tree.others);
        was.addAll(tree.args);
        wts.add(tree.others.type);
        wts.addAll(argtypes);

        result = tree.type = checkWiring(tree, centerT, was.toList(), wts.toList());
    }

    @Override
    public void visitAssociate(JCAssociate tree) {
        arrangeWiringOperatorArgs(tree);
        //capture types without the arrays.
        Type sType = checkCapsuleArray(tree.src, env);
        Type dType = checkCapsuleArray(tree.dest, env);

        attribExpr(tree.srcPos, env, syms.intType);
        attribExpr(tree.destPos, env, syms.intType);
        attribExpr(tree.len, env, syms.intType);
        List<Type> argtypes = attribArgs(tree.args, env);

        ListBuffer<JCExpression> was = new ListBuffer<JCExpression>();
        was.add(tree.dest);
        was.addAll(tree.args);
        ListBuffer<Type> wts = new ListBuffer<Type>();
        wts.add(dType);
        wts.addAll(argtypes);

        tree.type = checkWiring(tree, sType, was.toList(), wts.toList());
    }

    /**
     * Rearrange the arguments into the more specific fields of
     * the topology. Re-solves parsing abiguity for the topology
     * syntax.
     * @param tree
     */
    void arrangeWiringOperatorArgs(JCTopology tree){
        boolean argSizeError = false;
        switch(tree.getTag()) {
        case TOP_WIREALL:
        case TOP_RING:
            if(tree.args.size() >= 1){
                //first arg is actually the id to be wired
                tree.setMany(tree.args.head);
                //remaining args are the wiring args
                tree.args = tree.args.tail;
            } else {
                argSizeError = true;
            }
            break;
        case TOP_STAR:
            if(tree.args.size() >= 2) {
               tree.setCenter(tree.args.head);
               tree.setOrbiters(tree.args.tail.head);
               tree.args = tree.args.tail.tail;
            } else {
               argSizeError = true;
            }
            break;
        case TOP_ASSOC:
            if(tree.args.size() >= 5) { //walk down the list 5 times.
                tree.setSrc(tree.args.head);     tree.args = tree.args.tail;
                tree.setSrcPos(tree.args.head);  tree.args = tree.args.tail;
                tree.setDest(tree.args.head);    tree.args = tree.args.tail;
                tree.setDestPos(tree.args.head); tree.args = tree.args.tail;
                tree.setLength(tree.args.head);
                tree.args = tree.args.tail;
            } else {
                argSizeError = true;
            }
            break;
        default:
            Assert.error("Unknown topology operator " + tree.desc());
        }

        if(argSizeError){
            log.error(tree, "topology.operator.needs.n.args", tree.desc(), tree.minArgCount());
        }
    }
    // end Panini code

    public void visitClassDef(JCClassDecl tree) {
        // Local classes have not been entered yet, so we need to do it now:
        if ((env.info.scope.owner.kind & (VAR | MTH)) != 0)
            enter.classEnter(tree, env);

        ClassSymbol c = tree.sym;
        if (c == null) {
            // exit in case something drastic went wrong during enter.
            result = null;
        } else {
            // make sure class has been completed:
            c.complete();

            // If this class appears as an anonymous class
            // in a superclass constructor call where
            // no explicit outer instance is given,
            // disable implicit outer instance from being passed.
            // (This would be an illegal access to "this before super").
            if (env.info.isSelfCall &&
                env.tree.hasTag(NEWCLASS) &&
                ((JCNewClass) env.tree).encl == null)
            {
                c.flags_field |= NOOUTERTHIS;
            }
            attribClass(tree.pos(), c);
            result = tree.type = c.type;
        }
    }
   
    public void visitMethodDef(JCMethodDecl tree) {
        MethodSymbol m = tree.sym;
        
        // Panini code
        pAttr.preVisitMethodDef(tree, this);
        // end Panini code

        Lint lint = env.info.lint.augment(m.attributes_field, m.flags());
        Lint prevLint = chk.setLint(lint);
        MethodSymbol prevMethod = chk.setMethod(m);
        try {
            deferredLintHandler.flush(tree.pos());
            chk.checkDeprecatedAnnotation(tree.pos(), m);

            attribBounds(tree.typarams);

            // If we override any other methods, check that we do so properly.
            // JLS ???
            if (m.isStatic()) {
                chk.checkHideClashes(tree.pos(), env.enclClass.type, m);
            } else {
                chk.checkOverrideClashes(tree.pos(), env.enclClass.type, m);
            }
            chk.checkOverride(tree, m);

            // Create a new environment with local scope
            // for attributing the method.
            Env<AttrContext> localEnv = memberEnter.methodEnv(tree, env);

            localEnv.info.lint = lint;

            // Enter all type parameters into the local method scope.
            for (List<JCTypeParameter> l = tree.typarams; l.nonEmpty(); l = l.tail)
                localEnv.info.scope.enterIfAbsent(l.head.type.tsym);

            ClassSymbol owner = env.enclClass.sym;
            if ((owner.flags() & ANNOTATION) != 0 &&
                tree.params.nonEmpty())
                log.error(tree.params.head.pos(),
                          "intf.annotation.members.cant.have.params");

            // Attribute all value parameters.
            for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                attribStat(l.head, localEnv);
            }

            chk.checkVarargsMethodDecl(localEnv, tree);

            // Check that type parameters are well-formed.
            chk.validate(tree.typarams, localEnv);

            // Check that result type is well-formed.
            chk.validate(tree.restype, localEnv);

            // annotation method checks
            if ((owner.flags() & ANNOTATION) != 0) {
                // annotation method cannot have throws clause
                if (tree.thrown.nonEmpty()) {
                    log.error(tree.thrown.head.pos(),
                            "throws.not.allowed.in.intf.annotation");
                }
                // annotation method cannot declare type-parameters
                if (tree.typarams.nonEmpty()) {
                    log.error(tree.typarams.head.pos(),
                            "intf.annotation.members.cant.have.type.params");
                }
                // validate annotation method's return type (could be an annotation type)
                chk.validateAnnotationType(tree.restype);
                // ensure that annotation method does not clash with members of Object/Annotation
                chk.validateAnnotationMethod(tree.pos(), m);

                if (tree.defaultValue != null) {
                    // if default value is an annotation, check it is a well-formed
                    // annotation value (e.g. no duplicate values, no missing values, etc.)
                    chk.validateAnnotationTree(tree.defaultValue);
                }
            }

            for (List<JCExpression> l = tree.thrown; l.nonEmpty(); l = l.tail)
                chk.checkType(l.head.pos(), l.head.type, syms.throwableType);

            if (tree.body == null) {
                // Empty bodies are only allowed for
                // abstract, native, or interface methods, or for methods
                // in a retrofit signature class.
                if ((owner.flags() & INTERFACE) == 0 &&
                    (tree.mods.flags & (ABSTRACT | NATIVE)) == 0 &&
                    !relax)
                    log.error(tree.pos(), "missing.meth.body.or.decl.abstract");
                if (tree.defaultValue != null) {
                    if ((owner.flags() & ANNOTATION) == 0)
                        log.error(tree.pos(),
                                  "default.allowed.in.intf.annotation.member");
                }
            } else if ((owner.flags() & INTERFACE) != 0) {
                log.error(tree.body.pos(), "intf.meth.cant.have.body");
            } else if ((tree.mods.flags & ABSTRACT) != 0) {
                log.error(tree.pos(), "abstract.meth.cant.have.body");
            } else if ((tree.mods.flags & NATIVE) != 0) {
                log.error(tree.pos(), "native.meth.cant.have.body");
            } else {
                // Add an implicit super() call unless an explicit call to
                // super(...) or this(...) is given
                // or we are compiling class java.lang.Object.
                if (tree.name == names.init && owner.type != syms.objectType) {
                    JCBlock body = tree.body;
                    if (body.stats.isEmpty() ||
                        !TreeInfo.isSelfCall(body.stats.head)) {
                        body.stats = body.stats.
                            prepend(memberEnter.SuperCall(make.at(body.pos),
                                                          List.<Type>nil(),
                                                          List.<JCVariableDecl>nil(),
                                                          false));
                    } else if ((env.enclClass.sym.flags() & ENUM) != 0 &&
                               (tree.mods.flags & GENERATEDCONSTR) == 0 &&
                               TreeInfo.isSuperCall(body.stats.head)) {
                        // enum constructors are not allowed to call super
                        // directly, so make sure there aren't any super calls
                        // in enum constructors, except in the compiler
                        // generated one.
                        log.error(tree.body.stats.head.pos(),
                                  "call.to.super.not.allowed.in.enum.ctor",
                                  env.enclClass.sym);
                    }
                }

                // Attribute method body.
                attribStat(tree.body, localEnv);
            }
            localEnv.info.scope.leave();
            result = tree.type = m.type;
            chk.validateAnnotations(tree.mods.annotations, m);
        }
        finally {
            chk.setLint(prevLint);
            chk.setMethod(prevMethod);
        }
        // Panini code
    	pAttr.postVisitMethodDef(tree, env, rs);
        // end Panini code
    }

    public void visitVarDef(JCVariableDecl tree) {
        // Local variables have not been entered yet, so we need to do it now:
        if (env.info.scope.owner.kind == MTH) {
            if (tree.sym != null) {
                // parameters have already been entered
                env.info.scope.enter(tree.sym);
            } else {
                memberEnter.memberEnter(tree, env);
                annotate.flush();
            }
            tree.sym.flags_field |= EFFECTIVELY_FINAL;
        }

        VarSymbol v = tree.sym;
        Lint lint = env.info.lint.augment(v.attributes_field, v.flags());
        Lint prevLint = chk.setLint(lint);

        // Check that the variable's declared type is well-formed.
        chk.validate(tree.vartype, env);
        deferredLintHandler.flush(tree.pos());

        try {
            chk.checkDeprecatedAnnotation(tree.pos(), v);

            if (tree.init != null) {
                if ((v.flags_field & FINAL) != 0 && !tree.init.hasTag(NEWCLASS)) {
                    // In this case, `v' is final.  Ensure that it's initializer is
                    // evaluated.
                    v.getConstValue(); // ensure initializer is evaluated
                } else {
                    // Attribute initializer in a new environment
                    // with the declared variable as owner.
                    // Check that initializer conforms to variable's declared type.
                    Env<AttrContext> initEnv = memberEnter.initEnv(tree, env);
                    initEnv.info.lint = lint;
                    // In order to catch self-references, we set the variable's
                    // declaration position to maximal possible value, effectively
                    // marking the variable as undefined.
                    initEnv.info.enclVar = v;
                    attribExpr(tree.init, initEnv, v.type);
                }
            }
            result = tree.type = v.type;
            chk.validateAnnotations(tree.mods.annotations, v);
        }
        finally {
            chk.setLint(prevLint);
        }
        // Panini code
		if (!tree.name.toString().equals("panini$superCapsule")
				&& (tree.sym.owner.flags_field & SYNTHETIC) == 0 // Ignore
																	// assigns
																	// in
																	// generated
																	// blocks.
				&& env.enclClass.sym.isCapsule() && tree.init != null
				&& tree.init.type != null) {
			if (syms.capsules.containsKey(names.fromString(tree.init.type
					.toString()))) {
				log.error(tree.pos(), "capsule.cannot.be.stored.in.local");
			}
		}
        //end Panini code
    }

    public void visitSkip(JCSkip tree) {
        result = null;
    }

    public void visitBlock(JCBlock tree) {
        if (env.info.scope.owner.kind == TYP) {
            // Block is a static or instance initializer;
            // let the owner of the environment be a freshly
            // created BLOCK-method.
            Env<AttrContext> localEnv =
                env.dup(tree, env.info.dup(env.info.scope.dupUnshared()));
            localEnv.info.scope.owner =
                new MethodSymbol(tree.flags | BLOCK, names.empty, null,
                                 env.info.scope.owner);
            if ((tree.flags & STATIC) != 0) localEnv.info.staticLevel++;
            attribStats(tree.stats, localEnv);
        } else {
            // Create a new local environment with a local scope.
            Env<AttrContext> localEnv =
                env.dup(tree, env.info.dup(env.info.scope.dup()));
            attribStats(tree.stats, localEnv);
            localEnv.info.scope.leave();
        }
        result = null;
    }

    public void visitDoLoop(JCDoWhileLoop tree) {
        attribStat(tree.body, env.dup(tree));
        attribExpr(tree.cond, env, syms.booleanType);
        result = null;
    }

    public void visitWhileLoop(JCWhileLoop tree) {
        attribExpr(tree.cond, env, syms.booleanType);
        attribStat(tree.body, env.dup(tree));
        result = null;
    }

    public void visitForLoop(JCForLoop tree) {
        Env<AttrContext> loopEnv =
            env.dup(env.tree, env.info.dup(env.info.scope.dup()));
        attribStats(tree.init, loopEnv);
        if (tree.cond != null) attribExpr(tree.cond, loopEnv, syms.booleanType);
        loopEnv.tree = tree; // before, we were not in loop!
        attribStats(tree.step, loopEnv);
        attribStat(tree.body, loopEnv);
        loopEnv.info.scope.leave();
        result = null;
    }

    public void visitForeachLoop(JCEnhancedForLoop tree) {
        Env<AttrContext> loopEnv =
            env.dup(env.tree, env.info.dup(env.info.scope.dup()));
        attribStat(tree.var, loopEnv);
        Type exprType = types.upperBound(attribExpr(tree.expr, loopEnv));
        chk.checkNonVoid(tree.pos(), exprType);
        Type elemtype = types.elemtype(exprType); // perhaps expr is an array?
        if (elemtype == null) {
            // or perhaps expr implements Iterable<T>?
            Type base = types.asSuper(exprType, syms.iterableType.tsym);
            if (base == null) {
                log.error(tree.expr.pos(),
                        "foreach.not.applicable.to.type",
                        exprType,
                        diags.fragment("type.req.array.or.iterable"));
                elemtype = types.createErrorType(exprType);
            } else {
                List<Type> iterableParams = base.allparams();
                elemtype = iterableParams.isEmpty()
                    ? syms.objectType
                    : types.upperBound(iterableParams.head);
            }
        }
        chk.checkType(tree.expr.pos(), elemtype, tree.var.sym.type);
        loopEnv.tree = tree; // before, we were not in loop!
        attribStat(tree.body, loopEnv);
        loopEnv.info.scope.leave();
        result = null;
    }

    public void visitLabelled(JCLabeledStatement tree) {
        // Check that label is not used in an enclosing statement
        Env<AttrContext> env1 = env;
        while (env1 != null && !env1.tree.hasTag(CLASSDEF)) {
            if (env1.tree.hasTag(LABELLED) &&
                ((JCLabeledStatement) env1.tree).label == tree.label) {
                log.error(tree.pos(), "label.already.in.use",
                          tree.label);
                break;
            }
            env1 = env1.next;
        }

        attribStat(tree.body, env.dup(tree));
        result = null;
    }

    public void visitSwitch(JCSwitch tree) {
        Type seltype = attribExpr(tree.selector, env);

        Env<AttrContext> switchEnv =
            env.dup(tree, env.info.dup(env.info.scope.dup()));

        boolean enumSwitch =
            allowEnums &&
            (seltype.tsym.flags() & Flags.ENUM) != 0;
        boolean stringSwitch = false;
        if (types.isSameType(seltype, syms.stringType)) {
            if (allowStringsInSwitch) {
                stringSwitch = true;
            } else {
                log.error(tree.selector.pos(), "string.switch.not.supported.in.source", sourceName);
            }
        }
        if (!enumSwitch && !stringSwitch)
            seltype = chk.checkType(tree.selector.pos(), seltype, syms.intType);

        // Attribute all cases and
        // check that there are no duplicate case labels or default clauses.
        Set<Object> labels = new HashSet<Object>(); // The set of case labels.
        boolean hasDefault = false;      // Is there a default label?
        for (List<JCCase> l = tree.cases; l.nonEmpty(); l = l.tail) {
            JCCase c = l.head;
            Env<AttrContext> caseEnv =
                switchEnv.dup(c, env.info.dup(switchEnv.info.scope.dup()));
            if (c.pat != null) {
                if (enumSwitch) {
                    Symbol sym = enumConstant(c.pat, seltype);
                    if (sym == null) {
                        log.error(c.pat.pos(), "enum.label.must.be.unqualified.enum");
                    } else if (!labels.add(sym)) {
                        log.error(c.pos(), "duplicate.case.label");
                    }
                } else {
                    Type pattype = attribExpr(c.pat, switchEnv, seltype);
                    if (pattype.tag != ERROR) {
                        if (pattype.constValue() == null) {
                            log.error(c.pat.pos(),
                                      (stringSwitch ? "string.const.req" : "const.expr.req"));
                        } else if (labels.contains(pattype.constValue())) {
                            log.error(c.pos(), "duplicate.case.label");
                        } else {
                            labels.add(pattype.constValue());
                        }
                    }
                }
            } else if (hasDefault) {
                log.error(c.pos(), "duplicate.default.label");
            } else {
                hasDefault = true;
            }
            attribStats(c.stats, caseEnv);
            caseEnv.info.scope.leave();
            addVars(c.stats, switchEnv.info.scope);
        }

        switchEnv.info.scope.leave();
        result = null;
    }
    // where
        /** Add any variables defined in stats to the switch scope. */
        private static void addVars(List<JCStatement> stats, Scope switchScope) {
            for (;stats.nonEmpty(); stats = stats.tail) {
                JCTree stat = stats.head;
                if (stat.hasTag(VARDEF))
                    switchScope.enter(((JCVariableDecl) stat).sym);
            }
        }
    // where
    /** Return the selected enumeration constant symbol, or null. */
    private Symbol enumConstant(JCTree tree, Type enumType) {
        if (!tree.hasTag(IDENT)) {
            log.error(tree.pos(), "enum.label.must.be.unqualified.enum");
            return syms.errSymbol;
        }
        JCIdent ident = (JCIdent)tree;
        Name name = ident.name;
        for (Scope.Entry e = enumType.tsym.members().lookup(name);
             e.scope != null; e = e.next()) {
            if (e.sym.kind == VAR) {
                Symbol s = ident.sym = e.sym;
                ((VarSymbol)s).getConstValue(); // ensure initializer is evaluated
                ident.type = s.type;
                return ((s.flags_field & Flags.ENUM) == 0)
                    ? null : s;
            }
        }
        return null;
    }

    public void visitSynchronized(JCSynchronized tree) {
        chk.checkRefType(tree.pos(), attribExpr(tree.lock, env));
        attribStat(tree.body, env);
        result = null;
    }

    public void visitTry(JCTry tree) {
        // Create a new local environment with a local
        Env<AttrContext> localEnv = env.dup(tree, env.info.dup(env.info.scope.dup()));
        boolean isTryWithResource = tree.resources.nonEmpty();
        // Create a nested environment for attributing the try block if needed
        Env<AttrContext> tryEnv = isTryWithResource ?
            env.dup(tree, localEnv.info.dup(localEnv.info.scope.dup())) :
            localEnv;
        // Attribute resource declarations
        for (JCTree resource : tree.resources) {
            CheckContext twrContext = new Check.NestedCheckContext(resultInfo.checkContext) {
                @Override
                public void report(DiagnosticPosition pos, Type found, Type req, JCDiagnostic details) {
                    chk.basicHandler.report(pos, found, req, diags.fragment("try.not.applicable.to.type", found));
                }
            };
            ResultInfo twrResult = new ResultInfo(VAL, syms.autoCloseableType, twrContext);
            if (resource.hasTag(VARDEF)) {
                attribStat(resource, tryEnv);
                twrResult.check(resource, resource.type);

                //check that resource type cannot throw InterruptedException
                checkAutoCloseable(resource.pos(), localEnv, resource.type);

                VarSymbol var = (VarSymbol)TreeInfo.symbolFor(resource);
                var.setData(ElementKind.RESOURCE_VARIABLE);
            } else {
                attribTree(resource, tryEnv, twrResult);
            }
        }
        // Attribute body
        attribStat(tree.body, tryEnv);
        if (isTryWithResource)
            tryEnv.info.scope.leave();

        // Attribute catch clauses
        for (List<JCCatch> l = tree.catchers; l.nonEmpty(); l = l.tail) {
            JCCatch c = l.head;
            Env<AttrContext> catchEnv =
                localEnv.dup(c, localEnv.info.dup(localEnv.info.scope.dup()));
            Type ctype = attribStat(c.param, catchEnv);
            if (TreeInfo.isMultiCatch(c)) {
                //multi-catch parameter is implicitly marked as final
                c.param.sym.flags_field |= FINAL | UNION;
            }
            if (c.param.sym.kind == Kinds.VAR) {
                c.param.sym.setData(ElementKind.EXCEPTION_PARAMETER);
            }
            chk.checkType(c.param.vartype.pos(),
                          chk.checkClassType(c.param.vartype.pos(), ctype),
                          syms.throwableType);
            attribStat(c.body, catchEnv);
            catchEnv.info.scope.leave();
        }

        // Attribute finalizer
        if (tree.finalizer != null) attribStat(tree.finalizer, localEnv);

        localEnv.info.scope.leave();
        result = null;
    }

    void checkAutoCloseable(DiagnosticPosition pos, Env<AttrContext> env, Type resource) {
        if (!resource.isErroneous() &&
            types.asSuper(resource, syms.autoCloseableType.tsym) != null &&
            !types.isSameType(resource, syms.autoCloseableType)) { // Don't emit warning for AutoCloseable itself
            Symbol close = syms.noSymbol;
            boolean prevDeferDiags = log.deferDiagnostics;
            Queue<JCDiagnostic> prevDeferredDiags = log.deferredDiagnostics;
            try {
                log.deferDiagnostics = true;
                log.deferredDiagnostics = ListBuffer.lb();
                close = rs.resolveQualifiedMethod(pos,
                        env,
                        resource,
                        names.close,
                        List.<Type>nil(),
                        List.<Type>nil());
            }
            finally {
                log.deferDiagnostics = prevDeferDiags;
                log.deferredDiagnostics = prevDeferredDiags;
            }
            if (close.kind == MTH &&
                    close.overrides(syms.autoCloseableClose, resource.tsym, types, true) &&
                    chk.isHandled(syms.interruptedExceptionType, types.memberType(resource, close).getThrownTypes()) &&
                    env.info.lint.isEnabled(LintCategory.TRY)) {
                log.warning(LintCategory.TRY, pos, "try.resource.throws.interrupted.exc", resource);
            }
        }
    }

    public void visitConditional(JCConditional tree) {
        attribExpr(tree.cond, env, syms.booleanType);
        attribExpr(tree.truepart, env);
        attribExpr(tree.falsepart, env);
        result = check(tree,
                       capture(condType(tree.pos(), tree.cond.type,
                                        tree.truepart.type, tree.falsepart.type)),
                       VAL, resultInfo);
    }
    //where
        /** Compute the type of a conditional expression, after
         *  checking that it exists. See Spec 15.25.
         *
         *  @param pos      The source position to be used for
         *                  error diagnostics.
         *  @param condtype The type of the expression's condition.
         *  @param thentype The type of the expression's then-part.
         *  @param elsetype The type of the expression's else-part.
         */
        private Type condType(DiagnosticPosition pos,
                              Type condtype,
                              Type thentype,
                              Type elsetype) {
            Type ctype = condType1(pos, condtype, thentype, elsetype);

            // If condition and both arms are numeric constants,
            // evaluate at compile-time.
            return ((condtype.constValue() != null) &&
                    (thentype.constValue() != null) &&
                    (elsetype.constValue() != null))
                ? cfolder.coerce(condtype.isTrue()?thentype:elsetype, ctype)
                : ctype;
        }
        /** Compute the type of a conditional expression, after
         *  checking that it exists.  Does not take into
         *  account the special case where condition and both arms
         *  are constants.
         *
         *  @param pos      The source position to be used for error
         *                  diagnostics.
         *  @param condtype The type of the expression's condition.
         *  @param thentype The type of the expression's then-part.
         *  @param elsetype The type of the expression's else-part.
         */
        private Type condType1(DiagnosticPosition pos, Type condtype,
                               Type thentype, Type elsetype) {
            // If same type, that is the result
            if (types.isSameType(thentype, elsetype))
                return thentype.baseType();

            Type thenUnboxed = (!allowBoxing || thentype.isPrimitive())
                ? thentype : types.unboxedType(thentype);
            Type elseUnboxed = (!allowBoxing || elsetype.isPrimitive())
                ? elsetype : types.unboxedType(elsetype);

            // Otherwise, if both arms can be converted to a numeric
            // type, return the least numeric type that fits both arms
            // (i.e. return larger of the two, or return int if one
            // arm is short, the other is char).
            if (thenUnboxed.isPrimitive() && elseUnboxed.isPrimitive()) {
                // If one arm has an integer subrange type (i.e., byte,
                // short, or char), and the other is an integer constant
                // that fits into the subrange, return the subrange type.
                if (thenUnboxed.tag < INT && elseUnboxed.tag == INT &&
                    types.isAssignable(elseUnboxed, thenUnboxed))
                    return thenUnboxed.baseType();
                if (elseUnboxed.tag < INT && thenUnboxed.tag == INT &&
                    types.isAssignable(thenUnboxed, elseUnboxed))
                    return elseUnboxed.baseType();

                for (int i = BYTE; i < VOID; i++) {
                    Type candidate = syms.typeOfTag[i];
                    if (types.isSubtype(thenUnboxed, candidate) &&
                        types.isSubtype(elseUnboxed, candidate))
                        return candidate;
                }
            }

            // Those were all the cases that could result in a primitive
            if (allowBoxing) {
                if (thentype.isPrimitive())
                    thentype = types.boxedClass(thentype).type;
                if (elsetype.isPrimitive())
                    elsetype = types.boxedClass(elsetype).type;
            }

            if (types.isSubtype(thentype, elsetype))
                return elsetype.baseType();
            if (types.isSubtype(elsetype, thentype))
                return thentype.baseType();

            if (!allowBoxing || thentype.tag == VOID || elsetype.tag == VOID) {
                log.error(pos, "neither.conditional.subtype",
                          thentype, elsetype);
                return thentype.baseType();
            }

            // both are known to be reference types.  The result is
            // lub(thentype,elsetype). This cannot fail, as it will
            // always be possible to infer "Object" if nothing better.
            return types.lub(thentype.baseType(), elsetype.baseType());
        }

    public void visitIf(JCIf tree) {
        attribExpr(tree.cond, env, syms.booleanType);
        attribStat(tree.thenpart, env);
        if (tree.elsepart != null)
            attribStat(tree.elsepart, env);
        chk.checkEmptyIf(tree);
        result = null;
    }

    public void visitExec(JCExpressionStatement tree) {
        //a fresh environment is required for 292 inference to work properly ---
        //see Infer.instantiatePolymorphicSignatureInstance()
        Env<AttrContext> localEnv = env.dup(tree);
        attribExpr(tree.expr, localEnv);
        result = null;
    }

    public void visitBreak(JCBreak tree) {
        tree.target = findJumpTarget(tree.pos(), tree.getTag(), tree.label, env);
        result = null;
    }

    public void visitContinue(JCContinue tree) {
        tree.target = findJumpTarget(tree.pos(), tree.getTag(), tree.label, env);
        result = null;
    }
    //where
        /** Return the target of a break or continue statement, if it exists,
         *  report an error if not.
         *  Note: The target of a labelled break or continue is the
         *  (non-labelled) statement tree referred to by the label,
         *  not the tree representing the labelled statement itself.
         *
         *  @param pos     The position to be used for error diagnostics
         *  @param tag     The tag of the jump statement. This is either
         *                 Tree.BREAK or Tree.CONTINUE.
         *  @param label   The label of the jump statement, or null if no
         *                 label is given.
         *  @param env     The environment current at the jump statement.
         */
        private JCTree findJumpTarget(DiagnosticPosition pos,
                                    JCTree.Tag tag,
                                    Name label,
                                    Env<AttrContext> env) {
            // Search environments outwards from the point of jump.
            Env<AttrContext> env1 = env;
            LOOP:
            while (env1 != null) {
                switch (env1.tree.getTag()) {
                case LABELLED:
                    JCLabeledStatement labelled = (JCLabeledStatement)env1.tree;
                    if (label == labelled.label) {
                        // If jump is a continue, check that target is a loop.
                        if (tag == CONTINUE) {
                            if (!labelled.body.hasTag(DOLOOP) &&
                                !labelled.body.hasTag(WHILELOOP) &&
                                !labelled.body.hasTag(FORLOOP) &&
                                !labelled.body.hasTag(FOREACHLOOP))
                                log.error(pos, "not.loop.label", label);
                            // Found labelled statement target, now go inwards
                            // to next non-labelled tree.
                            return TreeInfo.referencedStatement(labelled);
                        } else {
                            return labelled;
                        }
                    }
                    break;
                case DOLOOP:
                case WHILELOOP:
                case FORLOOP:
                case FOREACHLOOP:
                    if (label == null) return env1.tree;
                    break;
                case SWITCH:
                    if (label == null && tag == BREAK) return env1.tree;
                    break;
                case METHODDEF:
                case CLASSDEF:
                    break LOOP;
                default:
                }
                env1 = env1.next;
            }
            if (label != null)
                log.error(pos, "undef.label", label);
            else if (tag == CONTINUE)
                log.error(pos, "cont.outside.loop");
            else
                log.error(pos, "break.outside.switch.loop");
            return null;
        }

    public void visitReturn(JCReturn tree) {
        // Check that there is an enclosing method which is
        // nested within than the enclosing class.
        if (env.enclMethod == null ||
            env.enclMethod.sym.owner != env.enclClass.sym) {
            log.error(tree.pos(), "ret.outside.meth");

        } else {
            // Attribute return expression, if it exists, and check that
            // it conforms to result type of enclosing method.
            Symbol m = env.enclMethod.sym;
            if (m.type.getReturnType().tag == VOID) {
                if (tree.expr != null)
                    log.error(tree.expr.pos(),
                              "cant.ret.val.from.meth.decl.void");
            } else if (tree.expr == null) {
                log.error(tree.pos(), "missing.ret.val");
            } else {
                attribExpr(tree.expr, env, m.type.getReturnType());
            }
        }
        result = null;
    }

    public void visitThrow(JCThrow tree) {
        attribExpr(tree.expr, env, syms.throwableType);
        result = null;
    }

    public void visitAssert(JCAssert tree) {
        attribExpr(tree.cond, env, syms.booleanType);
        if (tree.detail != null) {
            chk.checkNonVoid(tree.detail.pos(), attribExpr(tree.detail, env));
        }
        result = null;
    }

     /** Visitor method for method invocations.
     *  NOTE: The method part of an application will have in its type field
     *        the return type of the method, not the method's type itself!
     */
    public void visitApply(JCMethodInvocation tree) {
        // The local environment of a method application is
        // a new environment nested in the current one.
        Env<AttrContext> localEnv = env.dup(tree, env.info.dup());

        // The types of the actual method arguments.
        List<Type> argtypes;

        // The types of the actual method type arguments.
        List<Type> typeargtypes = null;

        Name methName = TreeInfo.name(tree.meth);
        boolean isConstructorCall =
            methName == names._this || methName == names._super;

        if (isConstructorCall) {
            // We are seeing a ...this(...) or ...super(...) call.
            // Check that this is the first statement in a constructor.
            if (checkFirstConstructorStat(tree, env)) {

                // Record the fact
                // that this is a constructor call (using isSelfCall).
                localEnv.info.isSelfCall = true;

                // Attribute arguments, yielding list of argument types.
                argtypes = attribArgs(tree.args, localEnv);
                typeargtypes = attribTypes(tree.typeargs, localEnv);

                // Variable `site' points to the class in which the called
                // constructor is defined.
                Type site = env.enclClass.sym.type;
                if (methName == names._super) {
                    if (site == syms.objectType) {
                        log.error(tree.meth.pos(), "no.superclass", site);
                        site = types.createErrorType(syms.objectType);
                    } else {
                        site = types.supertype(site);
                    }
                }

                if (site.tag == CLASS) {
                    Type encl = site.getEnclosingType();
                    while (encl != null && encl.tag == TYPEVAR)
                        encl = encl.getUpperBound();
                    if (encl.tag == CLASS) {
                        // we are calling a nested class

                        if (tree.meth.hasTag(SELECT)) {
                            JCTree qualifier = ((JCFieldAccess) tree.meth).selected;

                            // We are seeing a prefixed call, of the form
                            //     <expr>.super(...).
                            // Check that the prefix expression conforms
                            // to the outer instance type of the class.
                            chk.checkRefType(qualifier.pos(),
                                             attribExpr(qualifier, localEnv,
                                                        encl));
                        } else if (methName == names._super) {
                            // qualifier omitted; check for existence
                            // of an appropriate implicit qualifier.
                            rs.resolveImplicitThis(tree.meth.pos(),
                                                   localEnv, site, true);
                        }
                    } else if (tree.meth.hasTag(SELECT)) {
                        log.error(tree.meth.pos(), "illegal.qual.not.icls",
                                  site.tsym);
                    }

                    // if we're calling a java.lang.Enum constructor,
                    // prefix the implicit String and int parameters
                    if (site.tsym == syms.enumSym && allowEnums)
                        argtypes = argtypes.prepend(syms.intType).prepend(syms.stringType);

                    // Resolve the called constructor under the assumption
                    // that we are referring to a superclass instance of the
                    // current instance (JLS ???).
                    boolean selectSuperPrev = localEnv.info.selectSuper;
                    localEnv.info.selectSuper = true;
                    localEnv.info.varArgs = false;
                    Symbol sym = rs.resolveConstructor(
                        tree.meth.pos(), localEnv, site, argtypes, typeargtypes);
                    localEnv.info.selectSuper = selectSuperPrev;

                    // Set method symbol to resolved constructor...
                    TreeInfo.setSymbol(tree.meth, sym);

                    // ...and check that it is legal in the current context.
                    // (this will also set the tree's type)
                    Type mpt = newMethTemplate(argtypes, typeargtypes);
                    checkId(tree.meth, site, sym, localEnv, new ResultInfo(MTH, mpt),
                            tree.varargsElement != null);
                }
                // Otherwise, `site' is an error type and we do nothing
            }
            result = tree.type = syms.voidType;
        }else {
            // Otherwise, we are seeing a regular method call.
            // Attribute the arguments, yielding list of argument types, ...
            argtypes = attribArgs(tree.args, localEnv);
            typeargtypes = attribAnyTypes(tree.typeargs, localEnv);

            // ... and attribute the method using as a prototype a methodtype
            // whose formal argument types is exactly the list of actual
            // arguments (this will also set the method symbol).
            Type mpt = newMethTemplate(argtypes, typeargtypes);
            localEnv.info.varArgs = false;
            Type mtype = attribExpr(tree.meth, localEnv, mpt);
            // System.out.println("METH:" + tree.meth);
            // System.out.println("ISPROCEDURE?:" + ((MethodSymbol) TreeInfo.symbol(tree.meth)).isProcedure);
            
            // Panini Code
			if ((((MethodSymbol) TreeInfo.symbol(tree.meth)).flags() & Flags.PRIVATE) == 0
					&& (((MethodSymbol) TreeInfo.symbol(tree.meth)).flags() & Flags.PROTECTED) == 0) {
				if (env.enclClass.sym.isCapsule()
						&& ((env.enclClass.sym.flags_field & Flags.SERIAL) == 0)
						&& ((env.enclClass.sym.flags_field & Flags.MONITOR) == 0)) {
					if (tree.meth.hasTag(Tag.IDENT)) {
						if (!tree.meth.toString().contains("$")
								&& !tree.meth.toString().equals(
										PaniniConstants.PANINI_YIELD)
								&& !tree.meth.toString().equals(
										PaniniConstants.PANINI_SHUTDOWN)
								&& !tree.meth.toString().equals(
										PaniniConstants.PANINI_EXIT)) {
							tree.meth = make.Ident(names
									.fromString(((JCIdent) tree.meth).name
											.toString().concat("$Original")));
							mtype = attribExpr(tree.meth, localEnv, mpt);
						}
					} else if (tree.meth.hasTag(Tag.SELECT)) {
						JCFieldAccess clazz = (JCFieldAccess) tree.meth;
						if (clazz.selected.hasTag(Tag.IDENT)
								&& ((JCIdent) clazz.selected).name
										.equals(names._this)
								&& clazz.sym.owner == env.enclClass.sym)
							if (!clazz.name.toString().contains("$")
									&& !clazz.name.toString().equals(
											PaniniConstants.PANINI_YIELD)
									&& !clazz.name.toString().equals(
											PaniniConstants.PANINI_SHUTDOWN)
									&& !clazz.name.toString().equals(
											PaniniConstants.PANINI_EXIT)) {
								clazz.name = clazz.name.append(names
										.fromString("$Original"));
								mtype = attribExpr(tree.meth, localEnv, mpt);
							}
					}
				}
			}
            // end Panini code

            // Compute the result type.
            Type restype = mtype.getReturnType();
            if (restype.tag == WILDCARD)
                throw new AssertionError(mtype);

            // as a special case, array.clone() has a result that is
            // the same as static type of the array being cloned
            if (tree.meth.hasTag(SELECT) &&
                allowCovariantReturns &&
                methName == names.clone &&
                types.isArray(((JCFieldAccess) tree.meth).selected.type))
                restype = ((JCFieldAccess) tree.meth).selected.type;

            // as a special case, x.getClass() has type Class<? extends |X|>
            if (allowGenerics &&
                methName == names.getClass && tree.args.isEmpty()) {
                Type qualifier = (tree.meth.hasTag(SELECT))
                    ? ((JCFieldAccess) tree.meth).selected.type
                    : env.enclClass.sym.type;
                restype = new
                    ClassType(restype.getEnclosingType(),
                              List.<Type>of(new WildcardType(types.erasure(qualifier),
                                                               BoundKind.EXTENDS,
                                                               syms.boundClass)),
                              restype.tsym);
            }

            chk.checkRefTypes(tree.typeargs, typeargtypes);

            // Check that value of resulting type is admissible in the
            // current context.  Also, capture the return type
            result = check(tree, capture(restype), VAL, resultInfo);

            if (localEnv.info.varArgs)
                Assert.check(result.isErroneous() || tree.varargsElement != null);
        }
        chk.validate(tree.typeargs, localEnv);
    }
    //where
        /** Check that given application node appears as first statement
         *  in a constructor call.
         *  @param tree   The application node
         *  @param env    The environment current at the application.
         */
        boolean checkFirstConstructorStat(JCMethodInvocation tree, Env<AttrContext> env) {
            JCMethodDecl enclMethod = env.enclMethod;
            if (enclMethod != null && enclMethod.name == names.init) {
                JCBlock body = enclMethod.body;
                if (body.stats.head.hasTag(EXEC) &&
                    ((JCExpressionStatement) body.stats.head).expr == tree)
                    return true;
            }
            log.error(tree.pos(),"call.must.be.first.stmt.in.ctor",
                      TreeInfo.name(tree.meth));
            return false;
        }

        /** Obtain a method type with given argument types.
         */
        Type newMethTemplate(List<Type> argtypes, List<Type> typeargtypes) {
            MethodType mt = new MethodType(argtypes, null, null, syms.methodClass);
            return (typeargtypes == null) ? mt : (Type)new ForAll(typeargtypes, mt);
        }

    public void visitNewClass(JCNewClass tree) {
        Type owntype = types.createErrorType(tree.type);

        // The local environment of a class creation is
        // a new environment nested in the current one.
        Env<AttrContext> localEnv = env.dup(tree, env.info.dup());

        // The anonymous inner class definition of the new expression,
        // if one is defined by it.
        JCClassDecl cdef = tree.def;

        // If enclosing class is given, attribute it, and
        // complete class name to be fully qualified
        JCExpression clazz = tree.clazz; // Class field following new
        JCExpression clazzid =          // Identifier in class field
            (clazz.hasTag(TYPEAPPLY))
            ? ((JCTypeApply) clazz).clazz
            : clazz;

        JCExpression clazzid1 = clazzid; // The same in fully qualified form

        if (tree.encl != null) {
            // We are seeing a qualified new, of the form
            //    <expr>.new C <...> (...) ...
            // In this case, we let clazz stand for the name of the
            // allocated class C prefixed with the type of the qualifier
            // expression, so that we can
            // resolve it with standard techniques later. I.e., if
            // <expr> has type T, then <expr>.new C <...> (...)
            // yields a clazz T.C.
            Type encltype = chk.checkRefType(tree.encl.pos(),
                                             attribExpr(tree.encl, env));
            clazzid1 = make.at(clazz.pos).Select(make.Type(encltype),
                                                 ((JCIdent) clazzid).name);
            if (clazz.hasTag(TYPEAPPLY))
                clazz = make.at(tree.pos).
                    TypeApply(clazzid1,
                              ((JCTypeApply) clazz).arguments);
            else
                clazz = clazzid1;
        }

        // Attribute clazz expression and store
        // symbol + type back into the attributed tree.
        Type clazztype = attribType(clazz, env);
        clazztype = chk.checkDiamond(tree, clazztype);
        chk.validate(clazz, localEnv);
        if (tree.encl != null) {
            // We have to work in this case to store
            // symbol + type back into the attributed tree.
            tree.clazz.type = clazztype;
            TreeInfo.setSymbol(clazzid, TreeInfo.symbol(clazzid1));
            clazzid.type = ((JCIdent) clazzid).sym.type;
            if (!clazztype.isErroneous()) {
                if (cdef != null && clazztype.tsym.isInterface()) {
                    log.error(tree.encl.pos(), "anon.class.impl.intf.no.qual.for.new");
                } else if (clazztype.tsym.isStatic()) {
                    log.error(tree.encl.pos(), "qualified.new.of.static.class", clazztype.tsym);
                }
            }
        } else if (!clazztype.tsym.isInterface() &&
                   clazztype.getEnclosingType().tag == CLASS) {
            // Check for the existence of an apropos outer instance
            rs.resolveImplicitThis(tree.pos(), env, clazztype);
        }

        // Attribute constructor arguments.
        List<Type> argtypes = attribArgs(tree.args, localEnv);
        List<Type> typeargtypes = attribTypes(tree.typeargs, localEnv);

        if (TreeInfo.isDiamond(tree) && !clazztype.isErroneous()) {
            clazztype = attribDiamond(localEnv, tree, clazztype, argtypes, typeargtypes);
            clazz.type = clazztype;
        } else if (allowDiamondFinder &&
                tree.def == null &&
                !clazztype.isErroneous() &&
                clazztype.getTypeArguments().nonEmpty() &&
                findDiamonds) {
            boolean prevDeferDiags = log.deferDiagnostics;
            Queue<JCDiagnostic> prevDeferredDiags = log.deferredDiagnostics;
            Type inferred = null;
            try {
                //disable diamond-related diagnostics
                log.deferDiagnostics = true;
                log.deferredDiagnostics = ListBuffer.lb();
                inferred = attribDiamond(localEnv,
                        tree,
                        clazztype,
                        argtypes,
                        typeargtypes);
            }
            finally {
                log.deferDiagnostics = prevDeferDiags;
                log.deferredDiagnostics = prevDeferredDiags;
            }
            if (inferred != null &&
                    !inferred.isErroneous() &&
                    inferred.tag == CLASS &&
                    types.isAssignable(inferred, pt().tag == NONE ? clazztype : pt(), Warner.noWarnings)) {
                String key = types.isSameType(clazztype, inferred) ?
                    "diamond.redundant.args" :
                    "diamond.redundant.args.1";
                log.warning(tree.clazz.pos(), key, clazztype, inferred);
            }
        }

        // If we have made no mistakes in the class type...
        if (clazztype.tag == CLASS) {
            // Enums may not be instantiated except implicitly
            if (allowEnums &&
                (clazztype.tsym.flags_field&Flags.ENUM) != 0 &&
                (!env.tree.hasTag(VARDEF) ||
                 (((JCVariableDecl) env.tree).mods.flags&Flags.ENUM) == 0 ||
                 ((JCVariableDecl) env.tree).init != tree))
                log.error(tree.pos(), "enum.cant.be.instantiated");
            // Check that class is not abstract
            if (cdef == null &&
                (clazztype.tsym.flags() & (ABSTRACT | INTERFACE)) != 0) {
                log.error(tree.pos(), "abstract.cant.be.instantiated",
                          clazztype.tsym);
            } else if (cdef != null && clazztype.tsym.isInterface()) {
                // Check that no constructor arguments are given to
                // anonymous classes implementing an interface
                if (!argtypes.isEmpty())
                    log.error(tree.args.head.pos(), "anon.class.impl.intf.no.args");

                if (!typeargtypes.isEmpty())
                    log.error(tree.typeargs.head.pos(), "anon.class.impl.intf.no.typeargs");

                // Error recovery: pretend no arguments were supplied.
                argtypes = List.nil();
                typeargtypes = List.nil();
            }

            // Resolve the called constructor under the assumption
            // that we are referring to a superclass instance of the
            // current instance (JLS ???).
            else {
                //the following code alters some of the fields in the current
                //AttrContext - hence, the current context must be dup'ed in
                //order to avoid downstream failures
                Env<AttrContext> rsEnv = localEnv.dup(tree);
                rsEnv.info.selectSuper = cdef != null;
                rsEnv.info.varArgs = false;
                tree.constructor = rs.resolveConstructor(
                    tree.pos(), rsEnv, clazztype, argtypes, typeargtypes);
                tree.constructorType = tree.constructor.type.isErroneous() ?
                    syms.errType :
                    checkConstructor(clazztype,
                        tree.constructor,
                        rsEnv,
                        tree.args,
                        argtypes,
                        typeargtypes,
                        rsEnv.info.varArgs);
                if (rsEnv.info.varArgs)
                    Assert.check(tree.constructorType.isErroneous() || tree.varargsElement != null);
            }

            if (cdef != null) {
                // We are seeing an anonymous class instance creation.
                // In this case, the class instance creation
                // expression
                //
                //    E.new <typeargs1>C<typargs2>(args) { ... }
                //
                // is represented internally as
                //
                //    E . new <typeargs1>C<typargs2>(args) ( class <empty-name> { ... } )  .
                //
                // This expression is then *transformed* as follows:
                //
                // (1) add a STATIC flag to the class definition
                //     if the current environment is static
                // (2) add an extends or implements clause
                // (3) add a constructor.
                //
                // For instance, if C is a class, and ET is the type of E,
                // the expression
                //
                //    E.new <typeargs1>C<typargs2>(args) { ... }
                //
                // is translated to (where X is a fresh name and typarams is the
                // parameter list of the super constructor):
                //
                //   new <typeargs1>X(<*nullchk*>E, args) where
                //     X extends C<typargs2> {
                //       <typarams> X(ET e, args) {
                //         e.<typeargs1>super(args)
                //       }
                //       ...
                //     }
                if (Resolve.isStatic(env)) cdef.mods.flags |= STATIC;

                if (clazztype.tsym.isInterface()) {
                    cdef.implementing = List.of(clazz);
                } else {
                    cdef.extending = clazz;
                }

                attribStat(cdef, localEnv);

                // If an outer instance is given,
                // prefix it to the constructor arguments
                // and delete it from the new expression
                if (tree.encl != null && !clazztype.tsym.isInterface()) {
                    tree.args = tree.args.prepend(makeNullCheck(tree.encl));
                    argtypes = argtypes.prepend(tree.encl.type);
                    tree.encl = null;
                }

                // Reassign clazztype and recompute constructor.
                clazztype = cdef.sym.type;
                boolean useVarargs = tree.varargsElement != null;
                Symbol sym = rs.resolveConstructor(
                    tree.pos(), localEnv, clazztype, argtypes,
                    typeargtypes, true, useVarargs);
                Assert.check(sym.kind < AMBIGUOUS || tree.constructor.type.isErroneous());
                tree.constructor = sym;
                if (tree.constructor.kind > ERRONEOUS) {
                    tree.constructorType =  syms.errType;
                }
                else {
                    tree.constructorType = checkConstructor(clazztype,
                            tree.constructor,
                            localEnv,
                            tree.args,
                            argtypes,
                            typeargtypes,
                            useVarargs);
                }
            }

            if (tree.constructor != null && tree.constructor.kind == MTH)
                owntype = clazztype;
        }
        result = check(tree, owntype, VAL, resultInfo);
        chk.validate(tree.typeargs, localEnv);
    }

    Type attribDiamond(Env<AttrContext> env,
                        final JCNewClass tree,
                        Type clazztype,
                        List<Type> argtypes,
                        List<Type> typeargtypes) {
        if (clazztype.isErroneous() ||
                clazztype.isInterface()) {
            //if the type of the instance creation expression is erroneous,
            //or if it's an interface, or if something prevented us to form a valid
            //mapping, return the (possibly erroneous) type unchanged
            return clazztype;
        }

        //dup attribution environment and augment the set of inference variables
        Env<AttrContext> localEnv = env.dup(tree);

        ClassType site = new ClassType(clazztype.getEnclosingType(),
                    clazztype.tsym.type.getTypeArguments(),
                    clazztype.tsym);

        //if the type of the instance creation expression is a class type
        //apply method resolution inference (JLS 15.12.2.7). The return type
        //of the resolved constructor will be a partially instantiated type
        Symbol constructor = rs.resolveDiamond(tree.pos(),
                    localEnv,
                    site,
                    argtypes,
                    typeargtypes);

        if (constructor.kind == MTH) {
            clazztype = checkMethod(site,
                    constructor,
                    localEnv,
                    tree.args,
                    argtypes,
                    typeargtypes,
                    localEnv.info.varArgs).getReturnType();
        } else {
            clazztype = syms.errType;
        }

        if (clazztype.tag == FORALL && !resultInfo.pt.isErroneous()) {
            try {
                clazztype = resultInfo.checkContext.rawInstantiatePoly((ForAll)clazztype, pt(), Warner.noWarnings);
            } catch (Infer.InferenceException ex) {
                //an error occurred while inferring uninstantiated type-variables
                resultInfo.checkContext.report(tree.clazz.pos(), clazztype, resultInfo.pt,
                        diags.fragment("cant.apply.diamond.1", diags.fragment("diamond", clazztype.tsym), ex.diagnostic));
            }
        }

        return chk.checkClassType(tree.clazz.pos(), clazztype, true);
    }

    /** Make an attributed null check tree.
     */
    public JCExpression makeNullCheck(JCExpression arg) {
        // optimization: X.this is never null; skip null check
        Name name = TreeInfo.name(arg);
        if (name == names._this || name == names._super) return arg;

        JCTree.Tag optag = NULLCHK;
        JCUnary tree = make.at(arg.pos).Unary(optag, arg);
        tree.operator = syms.nullcheck;
        tree.type = arg.type;
        return tree;
    }

    public void visitNewArray(JCNewArray tree) {
        Type owntype = types.createErrorType(tree.type);
        Type elemtype;
        if (tree.elemtype != null) {
            elemtype = attribType(tree.elemtype, env);
            chk.validate(tree.elemtype, env);
            owntype = elemtype;
            for (List<JCExpression> l = tree.dims; l.nonEmpty(); l = l.tail) {
                attribExpr(l.head, env, syms.intType);
                owntype = new ArrayType(owntype, syms.arrayClass);
            }
        } else {
            // we are seeing an untyped aggregate { ... }
            // this is allowed only if the prototype is an array
            if (pt().tag == ARRAY) {
                elemtype = types.elemtype(pt());
            } else {
                if (pt().tag != ERROR) {
                    log.error(tree.pos(), "illegal.initializer.for.type",
                              pt());
                }
                elemtype = types.createErrorType(pt());
            }
        }
        if (tree.elems != null) {
            attribExprs(tree.elems, env, elemtype);
            owntype = new ArrayType(elemtype, syms.arrayClass);
        }
        if (!types.isReifiable(elemtype))
            log.error(tree.pos(), "generic.array.creation");
        result = check(tree, owntype, VAL, resultInfo);
    }

    @Override
    public void visitLambda(JCLambda that) {
        throw new UnsupportedOperationException("Lambda expression not supported yet");
    }

    @Override
    public void visitReference(JCMemberReference that) {
        throw new UnsupportedOperationException("Member references not supported yet");
    }

    public void visitParens(JCParens tree) {
        Type owntype = attribTree(tree.expr, env, resultInfo);
        result = check(tree, owntype, pkind(), resultInfo);
        Symbol sym = TreeInfo.symbol(tree);
        if (sym != null && (sym.kind&(TYP|PCK)) != 0)
            log.error(tree.pos(), "illegal.start.of.type");
    }

    public void visitAssign(JCAssign tree) {
        Type owntype = attribTree(tree.lhs, env.dup(tree), varInfo);
        Type capturedType = capture(owntype);
        Type rhsType = attribExpr(tree.rhs, env, owntype);
        result = check(tree, capturedType, VAL, resultInfo);
        // Panini code
        // Assigning the a capsule type is not allowed
        // in CapsuleDecls, unless the assign is in a wiring method.
        if( env.enclClass.sym.isCapsule()
           && rhsType.tsym.isCapsule()
           && env.enclMethod != null
           && env.enclMethod.sym.name != names.panini.InternalCapsuleWiring) {
        		log.error(tree.pos(), "capsule.cannot.be.stored.in.local");
        }
        // end Panini code
    }

    public void visitAssignop(JCAssignOp tree) {
        // Attribute arguments.
        Type owntype = attribTree(tree.lhs, env, varInfo);
        Type operand = attribExpr(tree.rhs, env);
        // Find operator.
        Symbol operator = tree.operator = rs.resolveBinaryOperator(
            tree.pos(), tree.getTag().noAssignOp(), env,
            owntype, operand);

        if (operator.kind == MTH &&
                !owntype.isErroneous() &&
                !operand.isErroneous()) {
            chk.checkOperator(tree.pos(),
                              (OperatorSymbol)operator,
                              tree.getTag().noAssignOp(),
                              owntype,
                              operand);
            chk.checkDivZero(tree.rhs.pos(), operator, operand);
            chk.checkCastable(tree.rhs.pos(),
                              operator.type.getReturnType(),
                              owntype);
        }
        result = check(tree, owntype, VAL, resultInfo);
    }

    public void visitUnary(JCUnary tree) {
        // Attribute arguments.
        Type argtype = (tree.getTag().isIncOrDecUnaryOp())
            ? attribTree(tree.arg, env, varInfo)
            : chk.checkNonVoid(tree.arg.pos(), attribExpr(tree.arg, env));

        // Find operator.
        Symbol operator = tree.operator =
            rs.resolveUnaryOperator(tree.pos(), tree.getTag(), env, argtype);

        Type owntype = types.createErrorType(tree.type);
        if (operator.kind == MTH &&
                !argtype.isErroneous()) {
            owntype = (tree.getTag().isIncOrDecUnaryOp())
                ? tree.arg.type
                : operator.type.getReturnType();
            int opc = ((OperatorSymbol)operator).opcode;

            // If the argument is constant, fold it.
            if (argtype.constValue() != null) {
                Type ctype = cfolder.fold1(opc, argtype);
                if (ctype != null) {
                    owntype = cfolder.coerce(ctype, owntype);

                    // Remove constant types from arguments to
                    // conserve space. The parser will fold concatenations
                    // of string literals; the code here also
                    // gets rid of intermediate results when some of the
                    // operands are constant identifiers.
                    if (tree.arg.type.tsym == syms.stringType.tsym) {
                        tree.arg.type = syms.stringType;
                    }
                }
            }
        }
        result = check(tree, owntype, VAL, resultInfo);
    }

    public void visitBinary(JCBinary tree) {
        // Attribute arguments.
        Type left = chk.checkNonVoid(tree.lhs.pos(), attribExpr(tree.lhs, env));
        Type right = chk.checkNonVoid(tree.lhs.pos(), attribExpr(tree.rhs, env));

        // Find operator.
        Symbol operator = tree.operator =
            rs.resolveBinaryOperator(tree.pos(), tree.getTag(), env, left, right);

        Type owntype = types.createErrorType(tree.type);
        if (operator.kind == MTH &&
                !left.isErroneous() &&
                !right.isErroneous()) {
            owntype = operator.type.getReturnType();
            int opc = chk.checkOperator(tree.lhs.pos(),
                                        (OperatorSymbol)operator,
                                        tree.getTag(),
                                        left,
                                        right);

            // If both arguments are constants, fold them.
            if (left.constValue() != null && right.constValue() != null) {
                Type ctype = cfolder.fold2(opc, left, right);
                if (ctype != null) {
                    owntype = cfolder.coerce(ctype, owntype);

                    // Remove constant types from arguments to
                    // conserve space. The parser will fold concatenations
                    // of string literals; the code here also
                    // gets rid of intermediate results when some of the
                    // operands are constant identifiers.
                    if (tree.lhs.type.tsym == syms.stringType.tsym) {
                        tree.lhs.type = syms.stringType;
                    }
                    if (tree.rhs.type.tsym == syms.stringType.tsym) {
                        tree.rhs.type = syms.stringType;
                    }
                }
            }

            // Check that argument types of a reference ==, != are
            // castable to each other, (JLS???).
            if ((opc == ByteCodes.if_acmpeq || opc == ByteCodes.if_acmpne)) {
                if (!types.isCastable(left, right, new Warner(tree.pos()))) {
                    log.error(tree.pos(), "incomparable.types", left, right);
                }
            }

            chk.checkDivZero(tree.rhs.pos(), operator, right);
        }
        result = check(tree, owntype, VAL, resultInfo);
    }

    public void visitTypeCast(JCTypeCast tree) {
        Type clazztype = attribType(tree.clazz, env);
        chk.validate(tree.clazz, env, false);
        //a fresh environment is required for 292 inference to work properly ---
        //see Infer.instantiatePolymorphicSignatureInstance()
        Env<AttrContext> localEnv = env.dup(tree);
        Type exprtype = attribExpr(tree.expr, localEnv, Infer.anyPoly);
        Type owntype = chk.checkCastable(tree.expr.pos(), exprtype, clazztype);
        if (exprtype.constValue() != null)
            owntype = cfolder.coerce(exprtype, owntype);
        result = check(tree, capture(owntype), VAL, resultInfo);
        chk.checkRedundantCast(localEnv, tree);
    }

    public void visitTypeTest(JCInstanceOf tree) {
        Type exprtype = chk.checkNullOrRefType(
            tree.expr.pos(), attribExpr(tree.expr, env));
        Type clazztype = chk.checkReifiableReferenceType(
            tree.clazz.pos(), attribType(tree.clazz, env));
        chk.validate(tree.clazz, env, false);
        chk.checkCastable(tree.expr.pos(), exprtype, clazztype);
        result = check(tree, syms.booleanType, VAL, resultInfo);
    }

    public void visitIndexed(JCArrayAccess tree) {
        Type owntype = types.createErrorType(tree.type);
        Type atype = attribExpr(tree.indexed, env);
        attribExpr(tree.index, env, syms.intType);
        if (types.isArray(atype))
            owntype = types.elemtype(atype);
        else if (atype.tag != ERROR)
            log.error(tree.pos(), "array.req.but.found", atype);
        if ((pkind() & VAR) == 0) owntype = capture(owntype);
        result = check(tree, owntype, VAR, resultInfo);
    }

    public void visitIdent(JCIdent tree) {
        Symbol sym;
        boolean varArgs = false;

        int pttag = pt().tag;

        // Find symbol
        if (pt().tag == METHOD || pt().tag == FORALL) {
            // If we are looking for a method, the prototype `pt' will be a
            // method type with the type of the call's arguments as parameters.
            env.info.varArgs = false;
            sym = rs.resolveMethod(tree.pos(), env, tree.name, pt().getParameterTypes(), pt().getTypeArguments());
            varArgs = env.info.varArgs;
        } else if (tree.sym != null && tree.sym.kind != VAR) {
            sym = tree.sym;
        } else {
            sym = rs.resolveIdent(tree.pos(), env, tree.name, pkind());
        }
        tree.sym = sym;

        // (1) Also find the environment current for the class where
        //     sym is defined (`symEnv').
        // Only for pre-tiger versions (1.4 and earlier):
        // (2) Also determine whether we access symbol out of an anonymous
        //     class in a this or super call.  This is illegal for instance
        //     members since such classes don't carry a this$n link.
        //     (`noOuterThisPath').
        Env<AttrContext> symEnv = env;
        boolean noOuterThisPath = false;
        if (env.enclClass.sym.owner.kind != PCK && // we are in an inner class
            (sym.kind & (VAR | MTH | TYP)) != 0 &&
            sym.owner.kind == TYP &&
            tree.name != names._this && tree.name != names._super) {

            // Find environment in which identifier is defined.
            while (symEnv.outer != null &&
                   !sym.isMemberOf(symEnv.enclClass.sym, types)) {
                if ((symEnv.enclClass.sym.flags() & NOOUTERTHIS) != 0)
                    noOuterThisPath = !allowAnonOuterThis;
                symEnv = symEnv.outer;
            }
        }

        // If symbol is a variable, ...
        if (sym.kind == VAR) {
            VarSymbol v = (VarSymbol)sym;

            // ..., evaluate its initializer, if it has one, and check for
            // illegal forward reference.
            checkInit(tree, env, v, false);

            // If symbol is a local variable accessed from an embedded
            // inner class check that it is final.
            if (v.owner.kind == MTH &&
                v.owner != env.info.scope.owner &&
                (v.flags_field & FINAL) == 0) {
                log.error(tree.pos(),
                          "local.var.accessed.from.icls.needs.final",
                          v);
            }

            // If we are expecting a variable (as opposed to a value), check
            // that the variable is assignable in the current environment.
            if (pkind() == VAR)
                checkAssignable(tree.pos(), v, null, env);
        }

        // In a constructor body,
        // if symbol is a field or instance method, check that it is
        // not accessed before the supertype constructor is called.
        if ((symEnv.info.isSelfCall || noOuterThisPath) &&
            (sym.kind & (VAR | MTH)) != 0 &&
            sym.owner.kind == TYP &&
            (sym.flags() & STATIC) == 0) {
            chk.earlyRefError(tree.pos(), sym.kind == VAR ? sym : thisSym(tree.pos(), env));
        }
        Env<AttrContext> env1 = env;
        if (sym.kind != ERR && sym.kind != TYP && sym.owner != null && sym.owner != env1.enclClass.sym) {
            // If the found symbol is inaccessible, then it is
            // accessed through an enclosing instance.  Locate this
            // enclosing instance:
            while (env1.outer != null && !rs.isAccessible(env, env1.enclClass.sym.type, sym))
                env1 = env1.outer;
        }
        result = checkId(tree, env1.enclClass.sym.type, sym, env, resultInfo, varArgs);
    }

    public void visitSelect(JCFieldAccess tree) {
        // Determine the expected kind of the qualifier expression.
        int skind = 0;
        if (tree.name == names._this || tree.name == names._super ||
            tree.name == names._class)
        {
            skind = TYP;
        } else {
            if ((pkind() & PCK) != 0) skind = skind | PCK;
            if ((pkind() & TYP) != 0) skind = skind | TYP | PCK;
            if ((pkind() & (VAL | MTH)) != 0) skind = skind | VAL | TYP;
        }

        // Attribute the qualifier expression, and determine its symbol (if any).
        Type site = attribTree(tree.selected, env, new ResultInfo(skind, Infer.anyPoly));
        if ((pkind() & (PCK | TYP)) == 0)
            site = capture(site); // Capture field access

        // don't allow T.class T[].class, etc
        if (skind == TYP) {
            Type elt = site;
            while (elt.tag == ARRAY)
                elt = ((ArrayType)elt).elemtype;
            if (elt.tag == TYPEVAR) {
                log.error(tree.pos(), "type.var.cant.be.deref");
                result = types.createErrorType(tree.type);
                return;
            }
        }

        // If qualifier symbol is a type or `super', assert `selectSuper'
        // for the selection. This is relevant for determining whether
        // protected symbols are accessible.
        Symbol sitesym = TreeInfo.symbol(tree.selected);
        boolean selectSuperPrev = env.info.selectSuper;
        env.info.selectSuper =
            sitesym != null &&
            sitesym.name == names._super;

        // If selected expression is polymorphic, strip
        // type parameters and remember in env.info.tvars, so that
        // they can be added later (in Attr.checkId and Infer.instantiateMethod).
        if (tree.selected.type.tag == FORALL) {
            ForAll pstype = (ForAll)tree.selected.type;
            env.info.tvars = pstype.tvars;
            site = tree.selected.type = pstype.qtype;
        }

        // Determine the symbol represented by the selection.
        env.info.varArgs = false;
        Symbol sym = selectSym(tree, sitesym, site, env, resultInfo);
        if (sym.exists() && !isType(sym) && (pkind() & (PCK | TYP)) != 0) {
            site = capture(site);
            sym = selectSym(tree, sitesym, site, env, resultInfo);
        }
        boolean varArgs = env.info.varArgs;
        tree.sym = sym;

        if (site.tag == TYPEVAR && !isType(sym) && sym.kind != ERR) {
            while (site.tag == TYPEVAR) site = site.getUpperBound();
            site = capture(site);
        }

        // If that symbol is a variable, ...
        if (sym.kind == VAR) {
            VarSymbol v = (VarSymbol)sym;

            // ..., evaluate its initializer, if it has one, and check for
            // illegal forward reference.
            checkInit(tree, env, v, true);

            // If we are expecting a variable (as opposed to a value), check
            // that the variable is assignable in the current environment.
            if (pkind() == VAR)
                checkAssignable(tree.pos(), v, tree.selected, env);
        }

        if (sitesym != null &&
                sitesym.kind == VAR &&
                ((VarSymbol)sitesym).isResourceVariable() &&
                sym.kind == MTH &&
                sym.name.equals(names.close) &&
                sym.overrides(syms.autoCloseableClose, sitesym.type.tsym, types, true) &&
                env.info.lint.isEnabled(LintCategory.TRY)) {
            log.warning(LintCategory.TRY, tree, "try.explicit.close.call");
        }

        // Disallow selecting a type from an expression
        if (isType(sym) && (sitesym==null || (sitesym.kind&(TYP|PCK)) == 0)) {
            tree.type = check(tree.selected, pt(),
                              sitesym == null ? VAL : sitesym.kind, new ResultInfo(TYP|PCK, pt()));
        }

        if (isType(sitesym)) {
            if (sym.name == names._this) {
                // If `C' is the currently compiled class, check that
                // C.this' does not appear in a call to a super(...)
                if (env.info.isSelfCall &&
                    site.tsym == env.enclClass.sym) {
                    chk.earlyRefError(tree.pos(), sym);
                }
            } else {
                // Check if type-qualified fields or methods are static (JLS)
                if ((sym.flags() & STATIC) == 0 &&
                    sym.name != names._super &&
                    (sym.kind == VAR || sym.kind == MTH)) {
                    rs.access(rs.new StaticError(sym),
                              tree.pos(), site, sym.name, true);
                }
            }
        } else if (sym.kind != ERR && (sym.flags() & STATIC) != 0 && sym.name != names._class) {
            // If the qualified item is not a type and the selected item is static, report
            // a warning. Make allowance for the class of an array type e.g. Object[].class)
            chk.warnStatic(tree, "static.not.qualified.by.type", Kinds.kindName(sym.kind), sym.owner);
        }

        // If we are selecting an instance member via a `super', ...
        if (env.info.selectSuper && (sym.flags() & STATIC) == 0) {

            // Check that super-qualified symbols are not abstract (JLS)
            rs.checkNonAbstract(tree.pos(), sym);

            if (site.isRaw()) {
                // Determine argument types for site.
                Type site1 = types.asSuper(env.enclClass.sym.type, site.tsym);
                if (site1 != null) site = site1;
            }
        }

        env.info.selectSuper = selectSuperPrev;
        result = checkId(tree, site, sym, env, resultInfo, varArgs);
        env.info.tvars = List.nil();
        
        // Panini code
        if ( pAttr.checkCapStateAcc
             && tree.selected.type.tsym.isCapsule() &&!tree.type.getKind().toString().equals("EXECUTABLE")
        		&&env.enclClass.sym.isCapsule() &&!tree.selected.toString().equals("this")){
			log.error(tree.pos, "invalid.access.of.capsules.states");
        }
        // end Panini code
    }
    //where
        /** Determine symbol referenced by a Select expression,
         *
         *  @param tree   The select tree.
         *  @param site   The type of the selected expression,
         *  @param env    The current environment.
         *  @param resultInfo The current result.
         */
        private Symbol selectSym(JCFieldAccess tree,
                                 Symbol location,
                                 Type site,
                                 Env<AttrContext> env,
                                 ResultInfo resultInfo) {
            DiagnosticPosition pos = tree.pos();
            Name name = tree.name;
            switch (site.tag) {
            case PACKAGE:
                return rs.access(
                    rs.findIdentInPackage(env, site.tsym, name, resultInfo.pkind),
                    pos, location, site, name, true);
            case ARRAY:
            case CLASS:
                if (resultInfo.pt.tag == METHOD || resultInfo.pt.tag == FORALL) {
                    return rs.resolveQualifiedMethod(
                        pos, env, location, site, name, resultInfo.pt.getParameterTypes(), resultInfo.pt.getTypeArguments());
                } else if (name == names._this || name == names._super) {
                    return rs.resolveSelf(pos, env, site.tsym, name);
                } else if (name == names._class) {
                    // In this case, we have already made sure in
                    // visitSelect that qualifier expression is a type.
                    Type t = syms.classType;
                    List<Type> typeargs = allowGenerics
                        ? List.of(types.erasure(site))
                        : List.<Type>nil();
                    t = new ClassType(t.getEnclosingType(), typeargs, t.tsym);
                    return new VarSymbol(
                        STATIC | PUBLIC | FINAL, names._class, t, site.tsym);
                } else {
                    // We are seeing a plain identifier as selector.
                    Symbol sym = rs.findIdentInType(env, site, name, resultInfo.pkind);
                    if ((resultInfo.pkind & ERRONEOUS) == 0)
                        sym = rs.access(sym, pos, location, site, name, true);
                    return sym;
                }
            case WILDCARD:
                throw new AssertionError(tree);
            case TYPEVAR:
                // Normally, site.getUpperBound() shouldn't be null.
                // It should only happen during memberEnter/attribBase
                // when determining the super type which *must* beac
                // done before attributing the type variables.  In
                // other words, we are seeing this illegal program:
                // class B<T> extends A<T.foo> {}
                Symbol sym = (site.getUpperBound() != null)
                    ? selectSym(tree, location, capture(site.getUpperBound()), env, resultInfo)
                    : null;
                if (sym == null) {
                    log.error(pos, "type.var.cant.be.deref");
                    return syms.errSymbol;
                } else {
                    Symbol sym2 = (sym.flags() & Flags.PRIVATE) != 0 ?
                        rs.new AccessError(env, site, sym) :
                                sym;
                    rs.access(sym2, pos, location, site, name, true);
                    return sym;
                }
            case ERROR:
                // preserve identifier names through errors
                return types.createErrorType(name, site.tsym, site).tsym;
            default:
                // The qualifier expression is of a primitive type -- only
                // .class is allowed for these.
                if (name == names._class) {
                    // In this case, we have already made sure in Select that
                    // qualifier expression is a type.
                    Type t = syms.classType;
                    Type arg = types.boxedClass(site).type;
                    t = new ClassType(t.getEnclosingType(), List.of(arg), t.tsym);
                    return new VarSymbol(
                        STATIC | PUBLIC | FINAL, names._class, t, site.tsym);
                } else {
                    log.error(pos, "cant.deref", site);
                    return syms.errSymbol;
                }
            }
        }

        /** Determine type of identifier or select expression and check that
         *  (1) the referenced symbol is not deprecated
         *  (2) the symbol's type is safe (@see checkSafe)
         *  (3) if symbol is a variable, check that its type and kind are
         *      compatible with the prototype and protokind.
         *  (4) if symbol is an instance field of a raw type,
         *      which is being assigned to, issue an unchecked warning if its
         *      type changes under erasure.
         *  (5) if symbol is an instance method of a raw type, issue an
         *      unchecked warning if its argument types change under erasure.
         *  If checks succeed:
         *    If symbol is a constant, return its constant type
         *    else if symbol is a method, return its result type
         *    otherwise return its type.
         *  Otherwise return errType.
         *
         *  @param tree       The syntax tree representing the identifier
         *  @param site       If this is a select, the type of the selected
         *                    expression, otherwise the type of the current class.
         *  @param sym        The symbol representing the identifier.
         *  @param env        The current environment.
         *  @param resultInfo    The expected result
         */
        Type checkId(JCTree tree,
                     Type site,
                     Symbol sym,
                     Env<AttrContext> env,
                     ResultInfo resultInfo,
                     boolean useVarargs) {
            if (resultInfo.pt.isErroneous()) return types.createErrorType(site);
            Type owntype; // The computed type of this identifier occurrence.
            switch (sym.kind) {
            case TYP:
                // For types, the computed type equals the symbol's type,
                // except for two situations:
                owntype = sym.type;
                if (owntype.tag == CLASS) {
                    Type ownOuter = owntype.getEnclosingType();

                    // (a) If the symbol's type is parameterized, erase it
                    // because no type parameters were given.
                    // We recover generic outer type later in visitTypeApply.
                    if (owntype.tsym.type.getTypeArguments().nonEmpty()) {
                        owntype = types.erasure(owntype);
                    }

                    // (b) If the symbol's type is an inner class, then
                    // we have to interpret its outer type as a superclass
                    // of the site type. Example:
                    //
                    // class Tree<A> { class Visitor { ... } }
                    // class PointTree extends Tree<Point> { ... }
                    // ...PointTree.Visitor...
                    //
                    // Then the type of the last expression above is
                    // Tree<Point>.Visitor.
                    else if (ownOuter.tag == CLASS && site != ownOuter) {
                        Type normOuter = site;
                        if (normOuter.tag == CLASS)
                            normOuter = types.asEnclosingSuper(site, ownOuter.tsym);
                        if (normOuter == null) // perhaps from an import
                            normOuter = types.erasure(ownOuter);
                        if (normOuter != ownOuter)
                            owntype = new ClassType(
                                normOuter, List.<Type>nil(), owntype.tsym);
                    }
                }
                break;
            case VAR:
                VarSymbol v = (VarSymbol)sym;
                // Test (4): if symbol is an instance field of a raw type,
                // which is being assigned to, issue an unchecked warning if
                // its type changes under erasure.
                if (allowGenerics &&
                    resultInfo.pkind == VAR &&
                    v.owner.kind == TYP &&
                    (v.flags() & STATIC) == 0 &&
                    (site.tag == CLASS || site.tag == TYPEVAR)) {
                    Type s = types.asOuterSuper(site, v.owner);
                    if (s != null &&
                        s.isRaw() &&
                        !types.isSameType(v.type, v.erasure(types))) {
                        chk.warnUnchecked(tree.pos(),
                                          "unchecked.assign.to.var",
                                          v, s);
                    }
                }
                // The computed type of a variable is the type of the
                // variable symbol, taken as a member of the site type.
                owntype = (sym.owner.kind == TYP &&
                           sym.name != names._this && sym.name != names._super)
                    ? types.memberType(site, sym)
                    : sym.type;

                if (env.info.tvars.nonEmpty()) {
                    Type owntype1 = new ForAll(env.info.tvars, owntype);
                    for (List<Type> l = env.info.tvars; l.nonEmpty(); l = l.tail)
                        if (!owntype.contains(l.head)) {
                            log.error(tree.pos(), "undetermined.type", owntype1);
                            owntype1 = types.createErrorType(owntype1);
                        }
                    owntype = owntype1;
                }

                // If the variable is a constant, record constant value in
                // computed type.
                if (v.getConstValue() != null && isStaticReference(tree))
                    owntype = owntype.constType(v.getConstValue());

                if (resultInfo.pkind == VAL) {
                    owntype = capture(owntype); // capture "names as expressions"
                }
                break;
            case MTH: {
                JCMethodInvocation app = (JCMethodInvocation)env.tree;
                owntype = checkMethod(site, sym, env, app.args,
                                      resultInfo.pt.getParameterTypes(), resultInfo.pt.getTypeArguments(),
                                      env.info.varArgs);
                break;
            }
            case PCK: case ERR:
                owntype = sym.type;
                break;
            default:
                throw new AssertionError("unexpected kind: " + sym.kind +
                                         " in tree " + tree);
            }

            // Test (1): emit a `deprecation' warning if symbol is deprecated.
            // (for constructors, the error was given when the constructor was
            // resolved)

            if (sym.name != names.init) {
                chk.checkDeprecated(tree.pos(), env.info.scope.owner, sym);
                chk.checkSunAPI(tree.pos(), sym);
            }

            // Test (3): if symbol is a variable, check that its type and
            // kind are compatible with the prototype and protokind.
            return check(tree, owntype, sym.kind, resultInfo);
        }

        /** Check that variable is initialized and evaluate the variable's
         *  initializer, if not yet done. Also check that variable is not
         *  referenced before it is defined.
         *  @param tree    The tree making up the variable reference.
         *  @param env     The current environment.
         *  @param v       The variable's symbol.
         */
        private void checkInit(JCTree tree,
                               Env<AttrContext> env,
                               VarSymbol v,
                               boolean onlyWarning) {
//          System.err.println(v + " " + ((v.flags() & STATIC) != 0) + " " +
//                             tree.pos + " " + v.pos + " " +
//                             Resolve.isStatic(env));//DEBUG

            // A forward reference is diagnosed if the declaration position
            // of the variable is greater than the current tree position
            // and the tree and variable definition occur in the same class
            // definition.  Note that writes don't count as references.
            // This check applies only to class and instance
            // variables.  Local variables follow different scope rules,
            // and are subject to definite assignment checking.
            if ((env.info.enclVar == v || v.pos > tree.pos) &&
                v.owner.kind == TYP &&
                canOwnInitializer(env.info.scope.owner) &&
                v.owner == env.info.scope.owner.enclClass() &&
                ((v.flags() & STATIC) != 0) == Resolve.isStatic(env) &&
                (!env.tree.hasTag(ASSIGN) ||
                 TreeInfo.skipParens(((JCAssign) env.tree).lhs) != tree)) {
                String suffix = (env.info.enclVar == v) ?
                                "self.ref" : "forward.ref";
                if (!onlyWarning || isStaticEnumField(v)) {
                    log.error(tree.pos(), "illegal." + suffix);
                } else if (useBeforeDeclarationWarning) {
                    log.warning(tree.pos(), suffix, v);
                }
            }

            v.getConstValue(); // ensure initializer is evaluated

            checkEnumInitializer(tree, env, v);
        }

        /**
         * Check for illegal references to static members of enum.  In
         * an enum type, constructors and initializers may not
         * reference its static members unless they are constant.
         *
         * @param tree    The tree making up the variable reference.
         * @param env     The current environment.
         * @param v       The variable's symbol.
         * @jls  section 8.9 Enums
         */
        private void checkEnumInitializer(JCTree tree, Env<AttrContext> env, VarSymbol v) {
            // JLS:
            //
            // "It is a compile-time error to reference a static field
            // of an enum type that is not a compile-time constant
            // (15.28) from constructors, instance initializer blocks,
            // or instance variable initializer expressions of that
            // type. It is a compile-time error for the constructors,
            // instance initializer blocks, or instance variable
            // initializer expressions of an enum constant e to refer
            // to itself or to an enum constant of the same type that
            // is declared to the right of e."
            if (isStaticEnumField(v)) {
                ClassSymbol enclClass = env.info.scope.owner.enclClass();

                if (enclClass == null || enclClass.owner == null)
                    return;

                // See if the enclosing class is the enum (or a
                // subclass thereof) declaring v.  If not, this
                // reference is OK.
                if (v.owner != enclClass && !types.isSubtype(enclClass.type, v.owner.type))
                    return;

                // If the reference isn't from an initializer, then
                // the reference is OK.
                if (!Resolve.isInitializer(env))
                    return;

                log.error(tree.pos(), "illegal.enum.static.ref");
            }
        }

        /** Is the given symbol a static, non-constant field of an Enum?
         *  Note: enum literals should not be regarded as such
         */
        private boolean isStaticEnumField(VarSymbol v) {
            return Flags.isEnum(v.owner) &&
                   Flags.isStatic(v) &&
                   !Flags.isConstant(v) &&
                   v.name != names._class;
        }

        /** Can the given symbol be the owner of code which forms part
         *  if class initialization? This is the case if the symbol is
         *  a type or field, or if the symbol is the synthetic method.
         *  owning a block.
         */
        private boolean canOwnInitializer(Symbol sym) {
            return
                (sym.kind & (VAR | TYP)) != 0 ||
                (sym.kind == MTH && (sym.flags() & BLOCK) != 0);
        }

    Warner noteWarner = new Warner();

    /**
     * Check that method arguments conform to its instantiation.
     **/
    public Type checkMethod(Type site,
                            Symbol sym,
                            Env<AttrContext> env,
                            final List<JCExpression> argtrees,
                            List<Type> argtypes,
                            List<Type> typeargtypes,
                            boolean useVarargs) {
        // Test (5): if symbol is an instance method of a raw type, issue
        // an unchecked warning if its argument types change under erasure.
        if (allowGenerics &&
            (sym.flags() & STATIC) == 0 &&
            (site.tag == CLASS || site.tag == TYPEVAR)) {
            Type s = types.asOuterSuper(site, sym.owner);
            if (s != null && s.isRaw() &&
                !types.isSameTypes(sym.type.getParameterTypes(),
                                   sym.erasure(types).getParameterTypes())) {
                chk.warnUnchecked(env.tree.pos(),
                                  "unchecked.call.mbr.of.raw.type",
                                  sym, s);
            }
        }

        // Compute the identifier's instantiated type.
        // For methods, we need to compute the instance type by
        // Resolve.instantiate from the symbol's type as well as
        // any type arguments and value arguments.
        noteWarner.clear();
        Type owntype = rs.instantiate(env,
                                      site,
                                      sym,
                                      argtypes,
                                      typeargtypes,
                                      true,
                                      useVarargs,
                                      noteWarner);

        boolean unchecked = noteWarner.hasNonSilentLint(LintCategory.UNCHECKED);

        // If this fails, something went wrong; we should not have
        // found the identifier in the first place.
        if (owntype == null) {
            if (!pt().isErroneous())
                log.error(env.tree.pos(),
                           "internal.error.cant.instantiate",
                           sym, site,
                          Type.toString(pt().getParameterTypes()));
            owntype = types.createErrorType(site);
            return types.createErrorType(site);
        } else if (owntype.getReturnType().tag == FORALL && !unchecked) {
            return owntype;
        } else {
            return chk.checkMethod(owntype, sym, env, argtrees, argtypes, useVarargs, unchecked);
        }
    }

    /**
     * Check that constructor arguments conform to its instantiation.
     **/
    public Type checkConstructor(Type site,
                            Symbol sym,
                            Env<AttrContext> env,
                            final List<JCExpression> argtrees,
                            List<Type> argtypes,
                            List<Type> typeargtypes,
                            boolean useVarargs) {
        Type owntype = checkMethod(site, sym, env, argtrees, argtypes, typeargtypes, useVarargs);
        chk.checkType(env.tree.pos(), owntype.getReturnType(), syms.voidType);
        return owntype;
    }

    public void visitLiteral(JCLiteral tree) {
        result = check(
            tree, litType(tree.typetag).constType(tree.value), VAL, resultInfo);
    }
    //where
    /** Return the type of a literal with given type tag.
     */
    Type litType(int tag) {
        return (tag == TypeTags.CLASS) ? syms.stringType : syms.typeOfTag[tag];
    }

    public void visitTypeIdent(JCPrimitiveTypeTree tree) {
        result = check(tree, syms.typeOfTag[tree.typetag], TYP, resultInfo);
    }

    public void visitTypeArray(JCArrayTypeTree tree) {
        Type etype = attribType(tree.elemtype, env);
        Type type = new ArrayType(etype, syms.arrayClass);
        result = check(tree, type, TYP, resultInfo);
    }

    /** Visitor method for parameterized types.
     *  Bound checking is left until later, since types are attributed
     *  before supertype structure is completely known
     */
    public void visitTypeApply(JCTypeApply tree) {
        Type owntype = types.createErrorType(tree.type);

        // Attribute functor part of application and make sure it's a class.
        Type clazztype = chk.checkClassType(tree.clazz.pos(), attribType(tree.clazz, env));

        // Attribute type parameters
        List<Type> actuals = attribTypes(tree.arguments, env);

        if (clazztype.tag == CLASS) {
            List<Type> formals = clazztype.tsym.type.getTypeArguments();
            if (actuals.isEmpty()) //diamond
                actuals = formals;

            if (actuals.length() == formals.length()) {
                List<Type> a = actuals;
                List<Type> f = formals;
                while (a.nonEmpty()) {
                    a.head = a.head.withTypeVar(f.head);
                    a = a.tail;
                    f = f.tail;
                }
                // Compute the proper generic outer
                Type clazzOuter = clazztype.getEnclosingType();
                if (clazzOuter.tag == CLASS) {
                    Type site;
                    JCExpression clazz = TreeInfo.typeIn(tree.clazz);
                    if (clazz.hasTag(IDENT)) {
                        site = env.enclClass.sym.type;
                    } else if (clazz.hasTag(SELECT)) {
                        site = ((JCFieldAccess) clazz).selected.type;
                    } else throw new AssertionError(""+tree);
                    if (clazzOuter.tag == CLASS && site != clazzOuter) {
                        if (site.tag == CLASS)
                            site = types.asOuterSuper(site, clazzOuter.tsym);
                        if (site == null)
                            site = types.erasure(clazzOuter);
                        clazzOuter = site;
                    }
                }
                owntype = new ClassType(clazzOuter, actuals, clazztype.tsym);
            } else {
                if (formals.length() != 0) {
                    log.error(tree.pos(), "wrong.number.type.args",
                              Integer.toString(formals.length()));
                } else {
                    log.error(tree.pos(), "type.doesnt.take.params", clazztype.tsym);
                }
                owntype = types.createErrorType(tree.type);
            }
        }
        result = check(tree, owntype, TYP, resultInfo);
    }

    public void visitTypeUnion(JCTypeUnion tree) {
        ListBuffer<Type> multicatchTypes = ListBuffer.lb();
        ListBuffer<Type> all_multicatchTypes = null; // lazy, only if needed
        for (JCExpression typeTree : tree.alternatives) {
            Type ctype = attribType(typeTree, env);
            ctype = chk.checkType(typeTree.pos(),
                          chk.checkClassType(typeTree.pos(), ctype),
                          syms.throwableType);
            if (!ctype.isErroneous()) {
                //check that alternatives of a union type are pairwise
                //unrelated w.r.t. subtyping
                if (chk.intersects(ctype,  multicatchTypes.toList())) {
                    for (Type t : multicatchTypes) {
                        boolean sub = types.isSubtype(ctype, t);
                        boolean sup = types.isSubtype(t, ctype);
                        if (sub || sup) {
                            //assume 'a' <: 'b'
                            Type a = sub ? ctype : t;
                            Type b = sub ? t : ctype;
                            log.error(typeTree.pos(), "multicatch.types.must.be.disjoint", a, b);
                        }
                    }
                }
                multicatchTypes.append(ctype);
                if (all_multicatchTypes != null)
                    all_multicatchTypes.append(ctype);
            } else {
                if (all_multicatchTypes == null) {
                    all_multicatchTypes = ListBuffer.lb();
                    all_multicatchTypes.appendList(multicatchTypes);
                }
                all_multicatchTypes.append(ctype);
            }
        }
        Type t = check(tree, types.lub(multicatchTypes.toList()), TYP, resultInfo);
        if (t.tag == CLASS) {
            List<Type> alternatives =
                ((all_multicatchTypes == null) ? multicatchTypes : all_multicatchTypes).toList();
            t = new UnionClassType((ClassType) t, alternatives);
        }
        tree.type = result = t;
    }

    public void visitTypeParameter(JCTypeParameter tree) {
        TypeVar a = (TypeVar)tree.type;
        Set<Type> boundSet = new HashSet<Type>();
        if (a.bound.isErroneous())
            return;
        List<Type> bs = types.getBounds(a);
        if (tree.bounds.nonEmpty()) {
            // accept class or interface or typevar as first bound.
            Type b = checkBase(bs.head, tree.bounds.head, env, false, false, false);
            boundSet.add(types.erasure(b));
            if (b.isErroneous()) {
                a.bound = b;
            }
            else if (b.tag == TYPEVAR) {
                // if first bound was a typevar, do not accept further bounds.
                if (tree.bounds.tail.nonEmpty()) {
                    log.error(tree.bounds.tail.head.pos(),
                              "type.var.may.not.be.followed.by.other.bounds");
                    tree.bounds = List.of(tree.bounds.head);
                    a.bound = bs.head;
                }
            } else {
                // if first bound was a class or interface, accept only interfaces
                // as further bounds.
                for (JCExpression bound : tree.bounds.tail) {
                    bs = bs.tail;
                    Type i = checkBase(bs.head, bound, env, false, true, false);
                    if (i.isErroneous())
                        a.bound = i;
                    else if (i.tag == CLASS)
                        chk.checkNotRepeated(bound.pos(), types.erasure(i), boundSet);
                }
            }
        }
        bs = types.getBounds(a);

        // in case of multiple bounds ...
        if (bs.length() > 1) {
            // ... the variable's bound is a class type flagged COMPOUND
            // (see comment for TypeVar.bound).
            // In this case, generate a class tree that represents the
            // bound class, ...
            JCExpression extending;
            List<JCExpression> implementing;
            if ((bs.head.tsym.flags() & INTERFACE) == 0) {
                extending = tree.bounds.head;
                implementing = tree.bounds.tail;
            } else {
                extending = null;
                implementing = tree.bounds;
            }
            JCClassDecl cd = make.at(tree.pos).ClassDef(
                make.Modifiers(PUBLIC | ABSTRACT),
                tree.name, List.<JCTypeParameter>nil(),
                extending, implementing, List.<JCTree>nil());

            ClassSymbol c = (ClassSymbol)a.getUpperBound().tsym;
            Assert.check((c.flags() & COMPOUND) != 0);
            cd.sym = c;
            c.sourcefile = env.toplevel.sourcefile;

            // ... and attribute the bound class
            c.flags_field |= UNATTRIBUTED;
            Env<AttrContext> cenv = enter.classEnv(cd, env);
            enter.typeEnvs.put(c, cenv);
        }
    }


    public void visitWildcard(JCWildcard tree) {
        //- System.err.println("visitWildcard("+tree+");");//DEBUG
        Type type = (tree.kind.kind == BoundKind.UNBOUND)
            ? syms.objectType
            : attribType(tree.inner, env);
        result = check(tree, new WildcardType(chk.checkRefType(tree.pos(), type),
                                              tree.kind.kind,
                                              syms.boundClass),
                       TYP, resultInfo);
    }

    public void visitAnnotation(JCAnnotation tree) {
        log.error(tree.pos(), "annotation.not.valid.for.type", pt());
        result = tree.type = syms.errType;
    }

    public void visitErroneous(JCErroneous tree) {
        if (tree.errs != null)
            for (JCTree err : tree.errs)
                attribTree(err, env, new ResultInfo(ERR, pt()));
        result = tree.type = syms.errType;
    }

    /** Default visitor method for all other trees.
     */
    public void visitTree(JCTree tree) {
        throw new AssertionError();
    }

    /**
     * Attribute an env for either a top level tree or class declaration.
     */
    public void attrib(Env<AttrContext> env) {
        if (env.tree.hasTag(TOPLEVEL))
            attribTopLevel(env);
        else
            attribClass(env.tree.pos(), env.enclClass.sym);
    }

    /**
     * Attribute a top level tree. These trees are encountered when the
     * package declaration has annotations.
     */
    public void attribTopLevel(Env<AttrContext> env) {
        JCCompilationUnit toplevel = env.toplevel;
        try {
            annotate.flush();
            chk.validateAnnotations(toplevel.packageAnnotations, toplevel.packge);
        } catch (CompletionFailure ex) {
            chk.completionError(toplevel.pos(), ex);
        }
    }

    /** Main method: attribute class definition associated with given class symbol.
     *  reporting completion failures at the given position.
     *  @param pos The source position at which completion errors are to be
     *             reported.
     *  @param c   The class symbol whose definition will be attributed.
     */
    public void attribClass(DiagnosticPosition pos, ClassSymbol c) {
        try {
            annotate.flush();
            attribClass(c);
        } catch (CompletionFailure ex) {
            chk.completionError(pos, ex);
        }
    }

    /** Attribute class definition associated with given class symbol.
     *  @param c   The class symbol whose definition will be attributed.
     */
    void attribClass(ClassSymbol c) throws CompletionFailure {
        // Check for cycles in the inheritance graph, which can arise from
        // ill-formed class files.
        chk.checkNonCyclic(null, c.type);

        Type st = types.supertype(c.type);
        if ((c.flags_field & Flags.COMPOUND) == 0) {
            // First, attribute superclass.
            if (st.tag == CLASS)
                attribClass((ClassSymbol)st.tsym);

            // Next attribute owner, if it is a class.
            if (c.owner.kind == TYP && c.owner.type.tag == CLASS)
                attribClass((ClassSymbol)c.owner);
        }

        // The previous operations might have attributed the current class
        // if there was a cycle. So we test first whether the class is still
        // UNATTRIBUTED.
        if ((c.flags_field & UNATTRIBUTED) != 0) {
            c.flags_field &= ~UNATTRIBUTED;

            // Get environment current at the point of class definition.
            Env<AttrContext> env = enter.typeEnvs.get(c);
            // The info.lint field in the envs stored in enter.typeEnvs is deliberately uninitialized,
            // because the annotations were not available at the time the env was created. Therefore,
            // we look up the environment chain for the first enclosing environment for which the
            // lint value is set. Typically, this is the parent env, but might be further if there
            // are any envs created as a result of TypeParameter nodes.
            Env<AttrContext> lintEnv = env;
            while (lintEnv.info.lint == null)
                lintEnv = lintEnv.next;

            // Having found the enclosing lint value, we can initialize the lint value for this class
            env.info.lint = lintEnv.info.lint.augment(c.attributes_field, c.flags());

            Lint prevLint = chk.setLint(env.info.lint);
            JavaFileObject prev = log.useSource(c.sourcefile);

            try {
                // java.lang.Enum may not be subclassed by a non-enum
                if (st.tsym == syms.enumSym &&
                    ((c.flags_field & (Flags.ENUM|Flags.COMPOUND)) == 0))
                    log.error(env.tree.pos(), "enum.no.subclassing");

                // Enums may not be extended by source-level classes
                if (st.tsym != null &&
                    ((st.tsym.flags_field & Flags.ENUM) != 0) &&
                    ((c.flags_field & (Flags.ENUM | Flags.COMPOUND)) == 0) &&
                    !target.compilerBootstrap(c)) {
                    log.error(env.tree.pos(), "enum.types.not.extensible");
                }
                // Panini code
                //Do not attrib classBody for capsules.
                //Visiting a capsule def will cause the class body to be attributed.
                if(c.isCapsule()){
                	Env<AttrContext> oldEnv = this.env;
                	this.env = env;
                	((JCCapsuleDecl)env.tree).switchToCapsule();
                	env.tree.accept(this);
                	((JCCapsuleDecl)env.tree).switchToClass();
                	this.env = oldEnv;
                } else {
                // end Panini code
                attribClassBody(env, c);
                }
                chk.checkDeprecatedAnnotation(env.tree.pos(), c);
            } finally {
                log.useSource(prev);
                chk.setLint(prevLint);
            }

        }
    }

    public void visitImport(JCImport tree) {
        // nothing to do
    }

    /** Finish the attribution of a class. */
    public void attribClassBody(Env<AttrContext> env, ClassSymbol c) {
        JCClassDecl tree = (JCClassDecl)env.tree;
        Assert.check(c == tree.sym);

        // Validate annotations
        chk.validateAnnotations(tree.mods.annotations, c);

        // Validate type parameters, supertype and interfaces.
        attribBounds(tree.typarams);
        if (!c.isAnonymous()) {
            //already checked if anonymous
            chk.validate(tree.typarams, env);
            chk.validate(tree.extending, env);
            chk.validate(tree.implementing, env);
        }

        // If this is a non-abstract class, check that it has no abstract
        // methods or unimplemented methods of an implemented interface.
        if ((c.flags() & (ABSTRACT | INTERFACE)) == 0) {
            if (!relax)
                chk.checkAllDefined(tree.pos(), c);
        }

        if ((c.flags() & ANNOTATION) != 0) {
            if (tree.implementing.nonEmpty())
                log.error(tree.implementing.head.pos(),
                          "cant.extend.intf.annotation");
            if (tree.typarams.nonEmpty())
                log.error(tree.typarams.head.pos(),
                          "intf.annotation.cant.have.type.params");
        } else {
            // Check that all extended classes and interfaces
            // are compatible (i.e. no two define methods with same arguments
            // yet different return types).  (JLS 8.4.6.3)
            chk.checkCompatibleSupertypes(tree.pos(), c.type);
        }

        // Check that class does not import the same parameterized interface
        // with two different argument lists.
        chk.checkClassBounds(tree.pos(), c.type);

        tree.type = c.type;

        for (List<JCTypeParameter> l = tree.typarams;
             l.nonEmpty(); l = l.tail) {
             Assert.checkNonNull(env.info.scope.lookup(l.head.name).scope);
        }

        // Check that a generic class doesn't extend Throwable
        if (!c.type.allparams().isEmpty() && types.isSubtype(c.type, syms.throwableType))
            log.error(tree.extending.pos(), "generic.throwable");

        // Check that all methods which implement some
        // method conform to the method they implement.
        chk.checkImplementations(tree);

        //check that a resource implementing AutoCloseable cannot throw InterruptedException
        checkAutoCloseable(tree.pos(), env, c.type);

        for (List<JCTree> l = tree.defs; l.nonEmpty(); l = l.tail) {
            // Attribute declaration
            attribStat(l.head, env);
            // Check that declarations in inner classes are not static (JLS 8.1.2)
            // Make an exception for static constants.
            if (c.owner.kind != PCK &&
                ((c.flags() & STATIC) == 0 || c.name == names.empty) &&
                (TreeInfo.flags(l.head) & (STATIC | INTERFACE)) != 0) {
                Symbol sym = null;
                if (l.head.hasTag(VARDEF)) sym = ((JCVariableDecl) l.head).sym;
                if (sym == null ||
                    sym.kind != VAR ||
                    ((VarSymbol) sym).getConstValue() == null)
                    log.error(l.head.pos(), "icls.cant.have.static.decl", c);
            }
        }

        // Check for cycles among non-initial constructors.
        chk.checkCyclicConstructors(tree);

        // Check for cycles among annotation elements.
        chk.checkNonCyclicElements(tree);

        // Check for proper use of serialVersionUID
        if (env.info.lint.isEnabled(LintCategory.SERIAL) &&
            isSerializable(c) &&
            (c.flags() & Flags.ENUM) == 0 &&
            (c.flags() & ABSTRACT) == 0) {
            checkSerialVersionUID(tree, c);
        }
    }
        // where
        /** check if a class is a subtype of Serializable, if that is available. */
        private boolean isSerializable(ClassSymbol c) {
            try {
                syms.serializableType.complete();
            }
            catch (CompletionFailure e) {
                return false;
            }
            return types.isSubtype(c.type, syms.serializableType);
        }

        /** Check that an appropriate serialVersionUID member is defined. */
        private void checkSerialVersionUID(JCClassDecl tree, ClassSymbol c) {

            // check for presence of serialVersionUID
            Scope.Entry e = c.members().lookup(names.serialVersionUID);
            while (e.scope != null && e.sym.kind != VAR) e = e.next();
            if (e.scope == null) {
                log.warning(LintCategory.SERIAL,
                        tree.pos(), "missing.SVUID", c);
                return;
            }

            // check that it is static final
            VarSymbol svuid = (VarSymbol)e.sym;
            if ((svuid.flags() & (STATIC | FINAL)) !=
                (STATIC | FINAL))
                log.warning(LintCategory.SERIAL,
                        TreeInfo.diagnosticPositionFor(svuid, tree), "improper.SVUID", c);

            // check that it is long
            else if (svuid.type.tag != TypeTags.LONG)
                log.warning(LintCategory.SERIAL,
                        TreeInfo.diagnosticPositionFor(svuid, tree), "long.SVUID", c);

            // check constant
            else if (svuid.getConstValue() == null)
                log.warning(LintCategory.SERIAL,
                        TreeInfo.diagnosticPositionFor(svuid, tree), "constant.SVUID", c);
        }

    private Type capture(Type type) {
        return types.capture(type);
    }

    // <editor-fold desc="post-attribution visitor">

    /**
     * Handle missing types/symbols in an AST. This routine is useful when
     * the compiler has encountered some errors (which might have ended up
     * terminating attribution abruptly); if the compiler is used in fail-over
     * mode (e.g. by an IDE) and the AST contains semantic errors, this routine
     * prevents NPE to be progagated during subsequent compilation steps.
     */
    public void postAttr(Env<AttrContext> env) {
        new PostAttrAnalyzer().scan(env.tree);
    }

    class PostAttrAnalyzer extends TreeScanner {

        private void initTypeIfNeeded(JCTree that) {
            if (that.type == null) {
                that.type = syms.unknownType;
            }
        }

        @Override
        public void scan(JCTree tree) {
            if (tree == null) return;
            if (tree instanceof JCExpression) {
                initTypeIfNeeded(tree);
            }
            super.scan(tree);
        }

        @Override
        public void visitIdent(JCIdent that) {
            if (that.sym == null) {
                that.sym = syms.unknownSymbol;
            }
        }

        @Override
        public void visitSelect(JCFieldAccess that) {
            if (that.sym == null) {
                that.sym = syms.unknownSymbol;
            }
            super.visitSelect(that);
        }

        @Override
        public void visitClassDef(JCClassDecl that) {
            initTypeIfNeeded(that);
            if (that.sym == null) {
                that.sym = new ClassSymbol(0, that.name, that.type, syms.noSymbol);
            }
            super.visitClassDef(that);
        }

        @Override
        public void visitMethodDef(JCMethodDecl that) {
            initTypeIfNeeded(that);
            if (that.sym == null) {
                that.sym = new MethodSymbol(0, that.name, that.type, syms.noSymbol);
            }
            super.visitMethodDef(that);
        }

        @Override
        public void visitVarDef(JCVariableDecl that) {
            initTypeIfNeeded(that);
            if (that.sym == null) {
                that.sym = new VarSymbol(0, that.name, that.type, syms.noSymbol);
                that.sym.adr = 0;
            }
            super.visitVarDef(that);
        }

        @Override
        public void visitNewClass(JCNewClass that) {
            if (that.constructor == null) {
                that.constructor = new MethodSymbol(0, names.init, syms.unknownType, syms.noSymbol);
            }
            if (that.constructorType == null) {
                that.constructorType = syms.unknownType;
            }
            super.visitNewClass(that);
        }

        @Override
        public void visitAssignop(JCAssignOp that) {
            if (that.operator == null)
                that.operator = new OperatorSymbol(names.empty, syms.unknownType, -1, syms.noSymbol);
            super.visitAssignop(that);
        }

        @Override
        public void visitBinary(JCBinary that) {
            if (that.operator == null)
                that.operator = new OperatorSymbol(names.empty, syms.unknownType, -1, syms.noSymbol);
            super.visitBinary(that);
        }

        @Override
        public void visitUnary(JCUnary that) {
            if (that.operator == null)
                that.operator = new OperatorSymbol(names.empty, syms.unknownType, -1, syms.noSymbol);
            super.visitUnary(that);
        }
    }
    // </editor-fold>
}
