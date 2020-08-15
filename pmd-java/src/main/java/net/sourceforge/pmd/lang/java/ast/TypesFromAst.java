/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.ast;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeParameterSymbol;
import net.sourceforge.pmd.lang.java.symbols.table.internal.JavaResolvers;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import net.sourceforge.pmd.util.CollectionUtil;

/**
 * Builds type mirrors from AST nodes.
 */
final class TypesFromAst {

    private TypesFromAst() {
        // utility class
    }

    public static List<JTypeMirror> fromAst(TypeSystem ts, Substitution subst, List<ASTType> reflected) {
        return CollectionUtil.map(reflected, it -> fromAst(ts, subst, it));
    }

    /**
     * Builds a type from an AST node.
     *
     * @param lexicalSubst A substitution to apply to type variables
     * @param node         An ast node
     */
    public static JTypeMirror fromAst(TypeSystem ts, Substitution lexicalSubst, ASTType node) {
        if (node == null) {
            return null;
        }

        return fromAstImpl(ts, lexicalSubst, node);
    }

    public static JTypeMirror fromAstImpl(TypeSystem ts, Substitution lexicalSubst, ASTType node) {

        if (node instanceof ASTClassOrInterfaceType) {

            return makeFromClassType(ts, (ASTClassOrInterfaceType) node, lexicalSubst);

        } else if (node instanceof ASTWildcardType) {


            ASTWildcardType wild = (ASTWildcardType) node;
            @Nullable JTypeMirror bound = fromAst(ts, lexicalSubst, wild.getTypeBoundNode());
            return bound == null
                   ? ts.UNBOUNDED_WILD
                   : ts.wildcard(wild.hasUpperBound(), bound);


        } else if (node instanceof ASTIntersectionType) {

            List<JTypeMirror> components = new ArrayList<>();
            for (ASTType t : (ASTIntersectionType) node) {
                components.add(fromAst(ts, lexicalSubst, t));
            }

            return ts.intersect(components);

        } else if (node instanceof ASTArrayType) {

            JTypeMirror eltType = fromAst(ts, lexicalSubst, ((ASTArrayType) node).getElementType());

            return ts.arrayType(eltType, node.getArrayDepth());

        } else if (node instanceof ASTPrimitiveType) {

            return ts.getPrimitive(((ASTPrimitiveType) node).getKind());

        } else if (node instanceof ASTAmbiguousName) {

            return ts.UNRESOLVED_TYPE;

        } else if (node instanceof ASTUnionType) {

            return ts.lub(CollectionUtil.map(((ASTUnionType) node).getComponents(), TypeNode::getTypeMirror));

        }

        throw new IllegalStateException("Illegal type " + node.getClass() + " " + node);
    }

    private static JTypeMirror makeFromClassType(TypeSystem ts, ASTClassOrInterfaceType node, Substitution subst) {

        if (node == null) {
            return null;
        }

        // TODO error handling, what if we're saying List<String, Int> in source: should be caught before

        ASTClassOrInterfaceType lhsType = node.getQualifier();

        @Nullable JTypeMirror enclosing = makeFromClassType(ts, lhsType, subst);

        JTypeDeclSymbol reference = getReferenceEnsureResolved(node);

        if (reference instanceof JTypeParameterSymbol) {
            return subst.apply(((JTypeParameterSymbol) reference).getTypeMirror());
        }

        if (enclosing != null && !shouldEnclose(enclosing, reference)) {
            // It's possible to write Map.Entry<A,B> but Entry is a static type,
            // so we should ignore the "enclosing" Map
            enclosing = null;
        } else if (enclosing == null && needsEnclosing(reference)) {
            // class Foo<T> {
            //      class Inner {}
            //      void bar(Inner k) {}
            //               ^^^^^
            //               This is shorthand for Foo<T>.Inner (because of regular scoping rules)
            // }
            enclosing = node.getImplicitEnclosing();
            assert enclosing != null : "Implicit enclosing type should have been set by disambiguation, for " + node;
        }

        ASTTypeArguments typeArguments = node.getTypeArguments();

        if (typeArguments != null) {
            if (typeArguments.isDiamond()) {
                // todo should be set to the inferred type! later
                return ts.declaration((JClassSymbol) reference);
            } else {
                final List<JTypeMirror> boundGenerics = new ArrayList<>(typeArguments.getNumChildren());
                for (ASTType t : typeArguments) {
                    boundGenerics.add(fromAst(ts, subst, t));
                }

                if (enclosing != null) {
                    return ((JClassType) enclosing).selectInner((JClassSymbol) reference, boundGenerics);
                } else {
                    return ts.parameterise((JClassSymbol) reference, boundGenerics);
                }
            }
        }

        if (enclosing != null) {
            return ((JClassType) enclosing).selectInner((JClassSymbol) reference, Collections.emptyList());
        } else {
            return ts.rawType(reference);
        }
    }

    // Whether the reference needs an enclosing type if it is unqualified (non-static inner type)
    private static boolean needsEnclosing(JTypeDeclSymbol reference) {
        return reference instanceof JClassSymbol
            && reference.getEnclosingClass() != null
            && !Modifier.isStatic(reference.getModifiers());
    }

    private static @NonNull JTypeDeclSymbol getReferenceEnsureResolved(ASTClassOrInterfaceType node) {
        if (node.getReferencedSym() != null) {
            return node.getReferencedSym();
        } else if (node.getParent() instanceof ASTConstructorCall) {
            ASTExpression qualifier = ((ASTConstructorCall) node.getParent()).getQualifier();
            if (qualifier != null) {
                assert node.getImplicitEnclosing() == null
                    : "Qualified ctor calls should be handled lazily";
                JTypeMirror qualifierType = qualifier.getTypeMirror();
                if (qualifierType instanceof JClassType) {
                    JClassType enclosing = (JClassType) qualifierType;
                    JClassType resolved = JavaResolvers.getMemberClassResolver(enclosing, node.getRoot().getPackageName(), node.getEnclosingType().getSymbol(), node.getSimpleName())
                                                       .resolveFirst(node.getSimpleName());
                    JClassSymbol symbol;
                    if (resolved == null) {
                        // compile-time error
                        symbol = (JClassSymbol) node.getTypeSystem().UNRESOLVED_TYPE.getSymbol();
                    } else {
                        symbol = resolved.getSymbol();
                        JClassType actualEnclosing = enclosing.getAsSuper(symbol.getEnclosingClass());
                        assert actualEnclosing != null : "We got this symbol by looking into enclosing";
                        node.setImplicitEnclosing(actualEnclosing);
                    }
                    node.setSymbol(symbol);
                    return symbol;
                }
            } // else fallthrough
        }
        throw new IllegalStateException("Disambiguation pass should resolve everything except qualified ctor calls");
    }

    // Whether the reference is a non-static inner type of the enclosing type
    // Note most checks have already been done in the disambiguation pass (including reporting)
    private static boolean shouldEnclose(@NonNull JTypeMirror enclosing, JTypeDeclSymbol reference) {
        return enclosing instanceof JClassType && !Modifier.isStatic(reference.getModifiers());
    }
}
