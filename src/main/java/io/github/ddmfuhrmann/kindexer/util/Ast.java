package io.github.ddmfuhrmann.kindexer.util;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.Optional;

/** Small, dependency-free helpers for reading annotation members off the JavaParser AST. */
public final class Ast {

    private Ast() {}

    public static boolean has(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotationByName(simpleName).isPresent();
    }

    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotationByName(simpleName);
    }

    /** The implicit single value ({@code @X("v")}) or {@code value=} member as a plain string. */
    public static String stringValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr s) {
            return asString(s.getMemberValue());
        }
        return member(ann, "value");
    }

    /** A named string member ({@code @X(name="v")}); null when absent. */
    public static String member(AnnotationExpr ann, String key) {
        Expression e = memberExpr(ann, key);
        return e == null ? null : asString(e);
    }

    public static Boolean booleanMember(AnnotationExpr ann, String key) {
        Expression e = memberExpr(ann, key);
        if (e instanceof BooleanLiteralExpr b) {
            return b.getValue();
        }
        return null;
    }

    public static Integer intMember(AnnotationExpr ann, String key) {
        Expression e = memberExpr(ann, key);
        if (e instanceof IntegerLiteralExpr i) {
            try {
                return Integer.parseInt(i.getValue());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Raw member expression under {@code key}, or null. */
    public static Expression memberExpr(AnnotationExpr ann, String key) {
        if (ann instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals(key)) {
                    return p.getValue();
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr s && key.equals("value")) {
            return s.getMemberValue();
        }
        return null;
    }

    /**
     * Best-effort string rendering of an annotation member value: string literals unwrapped,
     * {@code X.class} → {@code "X"}, first element of an array, else the source text.
     */
    public static String asString(Expression e) {
        if (e == null) {
            return null;
        }
        if (e instanceof StringLiteralExpr s) {
            return s.getValue();
        }
        if (e instanceof ClassExpr c) {
            return c.getType().asString();
        }
        if (e instanceof ArrayInitializerExpr a) {
            return a.getValues().isEmpty() ? null : asString(a.getValues().get(0));
        }
        return e.toString();
    }

    public static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }
}
