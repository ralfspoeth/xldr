package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

// Greyson exports a Selector of its own, and so does the spec now. Only one of
// the two can wear the bare name, and it should be the one this file is building:
// what is wanted from Greyson's is the single method below.
import static io.github.ralfspoeth.json.query.Selector.all;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a JSON mapping specification from an {@code input}, its {@code vars},
 * and an array of {@code mapping}s.
 * <p>
 * Members beyond those are ignored at every level, so an author may annotate a
 * spec - for instance with a {@code "comments"} member - without breaking it.
 * The name {@code load} is reserved (it carried the commit policy once and may
 * return) and must not be repurposed.
 */
public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public boolean accepts(Path path) {
        return path.getFileSystem()
                .getPathMatcher("glob:*.json")
                .matches(path.getFileName());
    }

    @Override
    public MappingSpec read(InputStream src) throws IOException {
        return Greyson.readValue(new InputStreamReader(src))
                .map(v -> new MappingSpec(
                        PTR.member("input").apply(v).map(JsonMappingSpecReader::inputSpec).orElseThrow(),
                        PTR.member("mapping").select(all()).apply(v).map(JsonMappingSpecReader::recordMappingSpec).toList()
                ))
                .orElseThrow();
    }

    private static InputSpec inputSpec(JsonValue is) {
        return new InputSpec(
                PTR.member("mimeType").stringOrThrow(is),
                PTR.member("recordSelectors")
                        .select(all())
                        .apply(is)
                        .map(JsonMappingSpecReader::recordSelectorSpec)
                        .toList(),
                PTR.member("vars")
                        .select(all())
                        .apply(is)
                        .map(JsonMappingSpecReader::varSpec)
                        .toList(),
                properties(is)
        );
    }

    /**
     * The settings of the adapter the input selects - {@code fieldSeparator},
     * {@code dateFormat}, {@code ns.f}, whatever that adapter understands. They
     * are grouped in one object because which of them mean anything depends on
     * the {@code mimeType}. A scalar is taken as its text; an object or an array
     * is not a setting and is ignored.
     */
    private static Map<String, String> properties(JsonValue is) {
        if (!(PTR.member("properties").apply(is).orElse(null) instanceof JsonObject(var members))) {
            return Map.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        members.forEach((name, value) -> text(value).ifPresent(v -> properties.put(name, v)));
        return properties;
    }

    private static Optional<String> text(JsonValue value) {
        return value.string()
                .or(() -> value.decimal().map(BigDecimal::toPlainString))
                .or(() -> value.bool().map(String::valueOf));
    }

    private static VarSpec varSpec(JsonValue v) {
        var source = valueSource(v);
        if (source instanceof ValueSource.Field) {
            throw new IllegalArgumentException("a var must be row-independent, not a fieldSelector: " + v);
        }
        return new VarSpec(PTR.member("name").stringOrThrow(v), source);
    }

    private static RecordMappingSpec recordMappingSpec(JsonValue element) {
        return new RecordMappingSpec(
                PTR.member("recordSelector").stringOrThrow(element),
                PTR.member("table").stringOrThrow(element),
                PTR.member("fieldMapping")
                        .select(all())
                        .apply(element)
                        .map(JsonMappingSpecReader::fieldMappingSpec)
                        .toList(),
                PTR.member("limit").intValue(element).stream().boxed().findFirst().orElse(null)
        );
    }

    private static FieldMappingSpec fieldMappingSpec(JsonValue fm) {
        return new FieldMappingSpec(PTR.member("column").stringOrThrow(fm), valueSource(fm));
    }

    /**
     * A field mapping carries exactly one source: {@code fieldSelector},
     * {@code constant}, {@code var}, {@code expr}, or a {@code lookup} object.
     */
    private static ValueSource valueSource(JsonValue fm) {
        var lookup = PTR.member("lookup").apply(fm);
        if (lookup.isPresent()) {
            if (hasBasicSource(fm)) {
                throw new IllegalArgumentException("a lookup mapping must carry no other source: " + fm);
            }
            return new ValueSource.Lookup(
                    PTR.member("table").stringOrThrow(lookup.get()),
                    PTR.member("column").stringOrThrow(lookup.get()),
                    PTR.member("keyColumn").stringOrThrow(lookup.get()),
                    basicSource(lookup.get()));
        }
        return basicSource(fm);
    }

    private static boolean hasBasicSource(JsonValue v) {
        return PTR.member("fieldSelector").stringValue(v).isPresent()
                || PTR.member("constant").apply(v).isPresent()
                || PTR.member("var").stringValue(v).isPresent()
                || PTR.member("expr").stringValue(v).isPresent();
    }

    /**
     * Exactly one of {@code fieldSelector}, {@code constant}, {@code var} or
     * {@code expr}.
     */
    private static ValueSource basicSource(JsonValue v) {
        var field = PTR.member("fieldSelector").stringValue(v);
        var constant = PTR.member("constant").apply(v);
        var varRef = PTR.member("var").stringValue(v);
        var expr = PTR.member("expr").stringValue(v);

        var present = (field.isPresent() ? 1 : 0) + (constant.isPresent() ? 1 : 0)
                + (varRef.isPresent() ? 1 : 0) + (expr.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException(
                    "needs exactly one of fieldSelector, constant, var, expr: " + v);
        }
        if (field.isPresent()) {
            return new ValueSource.Field(field.get());
        } else if (varRef.isPresent()) {
            return new ValueSource.Var(varRef.get());
        } else if (expr.isPresent()) {
            return new ValueSource.Expr(expr.get());
        } else {
            return new ValueSource.Constant(constantValue(constant.get()));
        }
    }

    /**
     * The constant's Java type follows the JSON literal: string, number (as
     * {@link java.math.BigDecimal}, exact), boolean, or {@code null} for the JSON
     * null literal, which loads a SQL NULL into the column.
     */
    private static @Nullable Object constantValue(JsonValue value) {
        if (value instanceof JsonNull) {
            return null;
        }
        return value.string().map(Object.class::cast)
                .or(() -> value.bool().map(Object.class::cast))
                .or(() -> value.decimal().map(Object.class::cast))
                .orElseThrow(() -> new IllegalArgumentException(
                        "constant must be a string, number, boolean or null: " + value));
    }

    /**
     * Both ways of selecting records are optional, and no record selector has
     * both. A {@code selector} points at records in a tree or a sheet; a
     * {@code discriminator} picks lines out of a flat file; and neither says that
     * the whole input holds one kind of record, which a CSV with a header or a
     * fixed-length file usually does.
     */
    private static RecordSelectorSpec recordSelectorSpec(JsonValue rs) {
        return new RecordSelectorSpec(
                PTR.member("name").stringOrThrow(rs),
                PTR.member("selector").stringValue(rs).orElse(null),
                discriminator(rs),
                PTR.member("fieldSelectors")
                        .select(all())
                        .apply(rs)
                        .map(JsonMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    /**
     * A discriminator says where to look and what for: exactly one of
     * {@code nth} and {@code selector}, and exactly one of {@code equals} and
     * {@code matches}.
     */
    private static @Nullable Discriminator discriminator(JsonValue rs) {
        var d = PTR.member("discriminator").apply(rs).orElse(null);
        if (d == null) {
            return null;
        }
        var where = selector(d, "a discriminator");
        // any scalar, so that a record type written 1 rather than "1" is a
        // discriminator rather than a puzzle
        var literal = PTR.member("equals").apply(d).flatMap(JsonMappingSpecReader::text);
        var regex = PTR.member("matches").stringValue(d);
        if (literal.isPresent() && regex.isPresent()) {
            throw new IllegalArgumentException(
                    "a discriminator tests equals or matches, not both: " + d);
        }
        if (literal.isPresent()) {
            return new Discriminator.Equals(where, literal.get());
        }
        if (regex.isPresent()) {
            return Discriminator.matching(where, regex.get());
        }
        throw new IllegalArgumentException("a discriminator needs equals or matches; "
                + where + " on its own says where to look and not what for: " + d);
    }

    private static FieldSelectorSpec fieldSelectorSpec(JsonValue fs) {
        return new FieldSelectorSpec(
                PTR.member("name").stringOrThrow(fs),
                selector(fs, "a field selector"),
                PTR.member("type")
                        .stringValue(fs)
                        .map(String::toUpperCase)
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    /**
     * Exactly one of {@code selector} - the adapter's own syntax - and
     * {@code nth}, a component counted from one.
     * <p>
     * Two names rather than one of two JSON types, because the XML format cannot
     * tell {@code selector="3"} from a number and would have had to guess. An
     * {@code nth} that is not a number is refused here rather than coerced, for
     * the same reason: {@code "nth": "1"} is a spec that means the other thing.
     */
    private static Selector selector(JsonValue v, String what) {
        var text = PTR.member("selector").stringValue(v);
        var nth = PTR.member("nth").apply(v);
        if (text.isPresent() && nth.isPresent()) {
            throw new IllegalArgumentException(what + " has both a selector and an nth,"
                    + " which are two answers to one question: " + v);
        }
        if (text.isPresent()) {
            return new Selector.Text(text.get());
        }
        if (nth.isPresent()) {
            return new Selector.Nth(wholeNumber(nth.get(), what));
        }
        throw new IllegalArgumentException(what + " needs a selector or an nth: " + v);
    }

    private static int wholeNumber(JsonValue value, String what) {
        var number = value.decimal().orElseThrow(() -> new IllegalArgumentException(
                what + ": nth is a whole number counted from one, was " + value));
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    what + ": nth is a whole number counted from one, was " + number, e);
        }
    }

    private static final Pointer PTR = Pointer.self();
}
