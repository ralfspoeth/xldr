package io.github.ralfspoeth.xldr.ldr;

import org.jspecify.annotations.Nullable;

import java.util.*;

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

    /**
     * @param function the function name
     * @param args     its arguments: a {@code String} or {@code Integer} for a
     *                 literal, or a {@link Segment} - a name or a nested call -
     *                 to be resolved when the call is evaluated
     */
    record Call(String function, List<Object> args) implements Segment {}

    /**
     * Resolves the names and functions a template references.
     */
    interface Bindings {
        @Nullable Object variable(String name);

        /**
         * @param args the resolved arguments, in the order written; an element is
         *             null where the argument resolved to nothing, because
         *             dropping it would silently change the call's arity
         */
        @Nullable Object function(String name, List<@Nullable Object> args);
    }

    private final List<Segment> segments;

    private Expression(List<Segment> segments) {
        this.segments = segments;
    }

    /**
     * The names this template references, in order of first appearance - used to
     * discover which of them are fields the adapter must resolve. A name inside
     * a function call counts: {@code ${format(birthdate, 'dd.MM.yyyy')}} reads
     * the field {@code birthdate} as surely as {@code ${birthdate}} does.
     */
    Set<String> variableNames() {
        var names = new LinkedHashSet<String>();
        segments.forEach(s -> collectNames(s, names));
        return names;
    }

    private static void collectNames(Object o, Set<String> names) {
        switch (o) {
            case VarRef(String name) -> names.add(name);
            case Call c -> c.args().forEach(a -> collectNames(a, names));
            default -> {
                // a literal, a string or an integer argument names nothing
            }
        }
    }

    @Nullable Object eval(Bindings bindings) {
        if (segments.size() == 1 && !(segments.getFirst() instanceof Literal)) {
            return valueOf(segments.getFirst(), bindings);
        }
        var sb = new StringBuilder();
        for (var s : segments) {
            var v = valueOf(s, bindings);
            sb.append(v);
        }
        return sb.toString();
    }

    private static @Nullable Object valueOf(Segment s, Bindings b) {
        return switch (s) {
            case Literal l -> l.text();
            case VarRef v -> b.variable(v.name());
            // arguments are resolved before the call, innermost first, so a
            // function sees values and never a fragment of the template. A null
            // stays in the list: it is what the argument resolved to, and
            // removing it would renumber the ones behind it. Stream.toList
            // permits nulls, which is why it and not Collectors.toList
            case Call c -> b.function(c.function(), c.args()
                    .stream()
                    .map(a -> a instanceof Segment inner ? valueOf(inner, b) : a)
                    .toList());
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

    /**
     * An argument is a quoted string, an integer, a name, or another call, so
     * that {@code format(now(), 'dd.MM.yyyy')} and
     * {@code format(birthdate, 'yyyy')} both say what they look like.
     */
    private static List<Object> args(String s) {
        if (s.isBlank()) {
            return List.of();
        }
        var out = new ArrayList<>();
        for (var raw : split(s)) {
            var arg = raw.strip();
            if (arg.length() >= 2 && arg.charAt(0) == '\'' && arg.charAt(arg.length() - 1) == '\'') {
                out.add(arg.substring(1, arg.length() - 1));
                continue;
            }
            try {
                out.add(Integer.valueOf(arg));
            } catch (NumberFormatException _) {
                // not a literal, so a name or a nested call, resolved at eval time
                out.add(hole(arg));
            }
        }
        return out;
    }

    /**
     * Splits an argument list at the commas that separate arguments - not at
     * those inside a quoted string, where a date pattern may well have one
     * ({@code 'EEE, dd MMM yyyy'}), nor at those inside a nested call.
     */
    private static List<String> split(String s) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (quoted) {
            throw new IllegalArgumentException("unterminated quote in expression arguments: " + s);
        }
        parts.add(current.toString());
        return parts;
    }
}
