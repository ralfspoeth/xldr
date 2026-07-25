package io.github.ralfspoeth.xldr.ldr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A compiled {@code ${...}} template: literal text interleaved with holes, each
 * hole a variable reference or a function call. Evaluated against {@link
 * Bindings} that resolve names and invoke functions.
 * <p>
 * A template that is a single hole yields that hole's value with its native type;
 * anything with literal text or several holes yields the pieces concatenated as a
 * string. There are no operators - interpolation and functions only.
 */
final class Expression {

    sealed interface Segment {}

    record Literal(String text) implements Segment {}

    record VarRef(String name) implements Segment {}

    record Call(String function, List<Object> args) implements Segment {}

    /**
     * Resolves the names and functions a template references.
     */
    interface Bindings {
        Object variable(String name);

        Object function(String name, List<Object> args);
    }

    private final List<Segment> segments;

    private Expression(List<Segment> segments) {
        this.segments = segments;
    }

    /**
     * The names this template references, in order of first appearance - used to
     * discover which of them are fields the adapter must resolve.
     */
    Set<String> variableNames() {
        var names = new LinkedHashSet<String>();
        for (var s : segments) {
            if (s instanceof VarRef(String name)) {
                names.add(name);
            }
        }
        return names;
    }

    Object eval(Bindings bindings) {
        if (segments.size() == 1 && !(segments.getFirst() instanceof Literal)) {
            return valueOf(segments.getFirst(), bindings);
        }
        var sb = new StringBuilder();
        for (var s : segments) {
            var v = valueOf(s, bindings);
            sb.append(v == null ? "" : v);
        }
        return sb.toString();
    }

    private static Object valueOf(Segment s, Bindings b) {
        return switch (s) {
            case Literal l -> l.text();
            case VarRef v -> b.variable(v.name());
            case Call c -> b.function(c.function(), c.args());
        };
    }

    static Expression compile(String template) {
        var segments = new ArrayList<Segment>();
        var literal = new StringBuilder();
        int i = 0, n = template.length();
        while (i < n) {
            if (template.charAt(i) == '$' && i + 1 < n && template.charAt(i + 1) == '{') {
                if (!literal.isEmpty()) {
                    segments.add(new Literal(literal.toString()));
                    literal.setLength(0);
                }
                int close = template.indexOf('}', i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException("unterminated ${ in expression: " + template);
                }
                segments.add(hole(template.substring(i + 2, close).strip()));
                i = close + 1;
            } else {
                literal.append(template.charAt(i));
                i++;
            }
        }
        if (!literal.isEmpty()) {
            segments.add(new Literal(literal.toString()));
        }
        if (segments.isEmpty()) {
            segments.add(new Literal(""));
        }
        return new Expression(segments);
    }

    private static Segment hole(String content) {
        int paren = content.indexOf('(');
        if (paren < 0) {
            if (content.isEmpty()) {
                throw new IllegalArgumentException("empty ${} in expression");
            }
            return new VarRef(content);
        }
        if (!content.endsWith(")")) {
            throw new IllegalArgumentException("malformed function call in expression: " + content);
        }
        var function = content.substring(0, paren).strip();
        return new Call(function, args(content.substring(paren + 1, content.length() - 1).strip()));
    }

    private static List<Object> args(String s) {
        if (s.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<Object>();
        for (var raw : s.split(",")) {
            var arg = raw.strip();
            if (arg.length() >= 2 && arg.charAt(0) == '\'' && arg.charAt(arg.length() - 1) == '\'') {
                out.add(arg.substring(1, arg.length() - 1));
            } else {
                try {
                    out.add(Integer.valueOf(arg));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "expression argument must be a quoted string or an integer: " + arg);
                }
            }
        }
        return out;
    }
}
