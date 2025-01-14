/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.scala.ast;

import scala.meta.Member;

/**
 * The ASTMemberParamClauseGroup node implementation.
 */
public final class ASTMemberParamClauseGroup extends AbstractScalaNode<Member.ParamClauseGroup> {

    ASTMemberParamClauseGroup(Member.ParamClauseGroup scalaNode) {
        super(scalaNode);
    }

    @Override
    protected <P, R> R acceptVisitor(ScalaParserVisitor<? super P, ? extends R> visitor, P data) {
        return visitor.visit(this, data);
    }
}
