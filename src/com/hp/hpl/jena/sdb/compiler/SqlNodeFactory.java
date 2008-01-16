/*
 * (c) Copyright 2008 Hewlett-Packard Development Company, LP
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.sdb.compiler;

import static com.hp.hpl.jena.sdb.core.JoinType.INNER;
import static com.hp.hpl.jena.sdb.core.JoinType.LEFT;

import java.util.Collection;
import java.util.Set;

import com.hp.hpl.jena.sparql.core.Var;

import com.hp.hpl.jena.sdb.core.JoinType;
import com.hp.hpl.jena.sdb.core.SDBRequest;
import com.hp.hpl.jena.sdb.core.ScopeEntry;
import com.hp.hpl.jena.sdb.core.sqlexpr.*;
import com.hp.hpl.jena.sdb.core.sqlnode.*;

public class SqlNodeFactory
{
    static public SqlNode distinct(SqlNode sqlNode)
    { return SqlSelectBlock.distinct(sqlNode) ; }

    static public SqlNode slice(SqlNode sqlNode, long start, long length)
    { return SqlSelectBlock.slice(sqlNode, start, length) ; }

    static public SqlNode project(SqlNode sqlNode, Collection<ColAlias> cols)
    { return SqlSelectBlock.project(sqlNode, cols) ; }
    
    static public SqlNode project(SqlNode sqlNode, ColAlias col)
    { return SqlSelectBlock.project(sqlNode, col) ; }

    static public SqlNode view(SqlNode sqlNode)
    { return SqlSelectBlock.view(sqlNode) ; }
    
    // -----  Making join nodes
    
    public static SqlNode innerJoin(SDBRequest request, SqlNode left, SqlNode right)
    {
        if ( left == null )
            return right ; 
        
        // Try to make things a left tree join(join(table, table), table)
        return join(request, INNER, left, right, null) ; 
    }

    public static SqlNode leftJoin(SDBRequest request, SqlNode left, SqlNode right)
    {
        if ( left == null )
            return right ; 
        return join(request, LEFT, left, right, null) ; 
    }

    public static SqlNode leftJoinCoalesce(SDBRequest request, String alias,
                                           SqlNode left, SqlNode right,
                                           Set<Var> coalesceVars)
    {
        SqlJoin sqlJoin = join(request, LEFT, left, right, coalesceVars) ;
        return SqlCoalesce.create(request, alias, sqlJoin, coalesceVars) ;
    }
    
//    private static String sqlNodeName(SqlNode sNode)
//    {
//        if ( sNode == null )            return "<null>" ;
//        if ( sNode.isProject() )        return "Project" ;
//        if ( sNode.isRestrict() )       return "Restrict/"+sqlNodeName(sNode.asRestrict().getSubNode()) ;
//        if ( sNode.isTable() )          return "Table" ;
//        if ( sNode.isInnerJoin() )      return "JoinInner" ;
//        if ( sNode.isLeftJoin() )       return "Joinleft" ;
//        if ( sNode.isCoalesce() )       return "Coalesce" ;
//        return "<unknown>" ;
//    }
    
    // Join/LeftJoin two subexpressions, calculating the join conditions in the process
    // If a coalesce (LeftJoin) then don't equate left and right vars of the same name.
    // A SqlCoalesce is a special case of LeftJoin where ignoreVars!=null
    
    private static SqlJoin join(SDBRequest request, 
                                JoinType joinType, 
                                SqlNode left, SqlNode right,
                                Set<Var> ignoreVars)
    {
        SqlExprList conditions = new SqlExprList() ;

        if ( joinType == INNER )
            // Put any left filter into the join conditions.
            // Does not apply to LEFT because the LHS filter does not apply to the right in the same way. 
            left = removeRestrict(left, conditions) ;

        right = removeRestrict(right, conditions) ;
        
        for ( Var v : left.getIdScope().getVars() )
        {
            if ( right.getIdScope().hasColumnForVar(v) )
            {
                ScopeEntry sLeft = left.getIdScope().findScopeForVar(v) ;
                ScopeEntry sRight = right.getIdScope().findScopeForVar(v) ;
                
                SqlExpr c = joinCondition(joinType, sLeft, sRight) ;
                conditions.add(c) ;
                c.addNote("Join var: "+v) ; 
            }
        }
        
        SqlJoin join = SqlJoin.create(joinType, left, right) ;
        join.addConditions(conditions) ;
        return join ;
    }
    
    private static SqlExpr joinCondition(JoinType joinType, ScopeEntry sLeft, ScopeEntry sRight)
    {
        SqlExpr c = null ;
        SqlColumn leftCol = sLeft.getColumn() ;
        SqlColumn rightCol = sRight.getColumn() ;
        
        // SPARQL join condition is join if "undef or same"
        // Soft null handling : need to insert "IsNull OR"
        // if the column can be a null.
        // The order of the OR conditions matters.
        
        if ( sLeft.isOptional() )
            c = makeOr(c, new S_IsNull(leftCol)) ;
        
        if ( sRight.isOptional() )
            c = makeOr(c, new S_IsNull(rightCol)) ;
        
        c = makeOr(c, new S_Equal(leftCol, rightCol)) ;
        return c ;
    }
   // ---- Expressions
    
    private static SqlExpr makeOr(SqlExpr c, SqlExpr expr)
    {
        if ( c == null )
            return expr ;
       
        return new S_Or(c, expr) ;
    }

    private static SqlExpr makeAnd(SqlExpr c, SqlExpr expr)
    {
        if ( c == null )
            return expr ;
       
        return new S_And(c, expr) ;
    }

    private static SqlNode removeRestrict(SqlNode sqlNode, SqlExprList conditions)
    {
        if ( ! sqlNode.isRestrict() ) 
            return sqlNode ;
        
        SqlRestrict restrict = sqlNode.asRestrict() ;
        SqlNode subNode = restrict.getSubNode() ;
        if ( ! subNode.isTable() && ! subNode.isInnerJoin() )
            return sqlNode ;
        conditions.addAll(restrict.getConditions()) ;
        subNode.addNotes(restrict.getNotes()) ;
        return subNode ;
    }
}

/*
 * (c) Copyright 2008 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */