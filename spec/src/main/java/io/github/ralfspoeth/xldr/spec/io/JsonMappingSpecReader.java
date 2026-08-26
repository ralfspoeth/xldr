package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

import static io.github.ralfspoeth.json.query.Selector.all;

// Greyson exports a Selector of its own, and so does the spec. A static member
// import takes the one method wanted from Greyson's without importing the type,
// so the bare name still means the spec's wherever it is written.

/**
 * Reads a JSON mapping specification from an {@code input}, its {@code vars},
 * an array of {@code mapping}s, and an optional {@code transform} array of
 * procedures to call once the input has been loaded.
 * <p>
 * Members beyond those are ignored at every level, so an author may annotate a
 * spec - for instance with a {@code "comments"} member - without breaking it.
 * No member name is reserved. {@code load} was, held since 0.2 against the
 * return of the commit policy it once carried; what a deployment needs to say
 * about where a load goes is now {@code target.properties} beside the spec, and
 * a name kept open for something that is not coming back is only a trap for
 * whoever picks it.
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
                        PTR.member("mapping").select(all()).apply(v).map(JsonMappingSpecReader::recordMappingSpec).toList(),
                        PTR.member("transform").select(all()).apply(v).map(JsonMappingSpecReader::procedureCall).toList()
                ))
                .orElseThrow();
    }

    /**
     * A procedure to call once the input has been loaded: its {@code name} and
     * its {@code args}, each an ordinary value source.
     * <p>
     * No {@code type}, where an {@code fn} needs one - nothing comes back from a
     * procedure, so there is no OUT parameter to declare. That absence is the
     * only difference between the two in this format, and it is the difference
     * that decides which of the two a spec means.
     */
    private static ProcedureCall procedureCall(JsonValue transform) {
        return new ProcedureCall(
                PTR.member("name").stringOrThrow(transform),
                PTR.member("args")
                        .select(all())
                        .apply(transform)
                        .map(JsonMappingSpecReader::valueSource)
                        .toList());
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

    /**
     * A var reads whatever a field mapping reads, minus a field - which
     * {@link VarSpec} refuses itself, at any depth, so there is nothing to check
     * here. It used to be checked here and only at the top level, so a field
     * inside a lookup key slipped through and failed at load instead.
     */
    private static VarSpec varSpec(JsonValue v) {
        return new VarSpec(PTR.member("name").stringOrThrow(v), valueSource(v));
    }

    private static RecordMappingSpec recordMappingSpec(JsonValue element) {
        return new RecordMappingSpec(
                PTR.member("recordSelector").stringOrThrow(element),
                new SqlIdentifier(PTR.member("table").stringOrThrow(element)),
                PTR.member("fieldMapping")
                        .select(all())
                        .apply(element)
                        .map(JsonMappingSpecReader::fieldMappingSpec)
                        .toList(),
                // through the node, so that "limit": "100" is refused here rather
                // than read as no limit at all - the XML reader always refused it
                node(element).whole("limit").orElse(null)
        );
    }

    private static FieldMappingSpec fieldMappingSpec(JsonValue fm) {
        return new FieldMappingSpec(new SqlIdentifier(PTR.member("column").stringOrThrow(fm)), valueSource(fm));
    }

    /**
     * Exactly one source: {@code fieldSelector}, {@code constant}, {@code var} or
     * {@code expr} as a scalar member, or a {@code lookup} or {@code fn} object.
     * Which of the four scalars, and the refusal when it is not one, is
     * {@link SpecNode#source()}; where the two objects sit is this format's
     * business.
     */
    private static ValueSource valueSource(JsonValue fm) {
        var lookup = PTR.member("lookup").apply(fm).orElse(null);
        var fn = PTR.member("fn").apply(fm).orElse(null);
        if (lookup == null && fn == null) {
            return node(fm).source();
        }
        if (lookup != null && fn != null) {
            throw new IllegalArgumentException("a lookup and an fn are two sources, and one is wanted: " + fm);
        }
        if (node(fm).hasSource()) {
            throw new IllegalArgumentException("a " + (fn == null ? "lookup" : "fn")
                    + " and a source member are two sources, and one is wanted: " + fm);
        }
        if (fn != null) {
            return functionCall(fn);
        }
        return new ValueSource.Lookup(
                new SqlIdentifier(PTR.member("table").stringOrThrow(lookup)),
                new SqlIdentifier(PTR.member("column").stringOrThrow(lookup)),
                conditions(lookup));
    }

    /**
     * What a lookup matches on: either a {@code keyColumn} beside its source,
     * which is one condition and the way nearly every lookup is written, or a
     * {@code conditions} array for the composite key.
     * <p>
     * Both spellings, and never both at once. The short one is not deprecated
     * sugar - a lookup on one column is the common case and reads better said
     * once than wrapped in an array of one - but a spec that writes both has
     * said the same thing two ways and is refused rather than picked between.
     */
    private static SequencedMap<SqlIdentifier, ValueSource> conditions(JsonValue lookup) {
        var listed = PTR.member("conditions").apply(lookup).orElse(null);
        var keyColumn = PTR.member("keyColumn").stringValue(lookup).orElse(null);
        if (listed != null && keyColumn != null) {
            throw new IllegalArgumentException(
                    "a lookup says keyColumn and conditions, and one of the two is wanted: " + lookup);
        }
        if (listed == null) {
            if (keyColumn == null) {
                throw new IllegalArgumentException(
                        "a lookup matches on something: give it a keyColumn or conditions: " + lookup);
            }
            var one = new LinkedHashMap<SqlIdentifier, ValueSource>();
            one.put(new SqlIdentifier(keyColumn), conditionValue(lookup));
            return one;
        }
        var many = new LinkedHashMap<SqlIdentifier, ValueSource>();
        PTR.select(all()).apply(listed).forEach(condition -> {
            var column = new SqlIdentifier(PTR.member("column").stringOrThrow(condition));
            if (many.put(column, conditionValue(condition)) != null) {
                // the map keeps the first quietly, and a SqlIdentifier collides
                // with a differently-spelled one for the same column - so this
                // catches ccy beside CCY as well as ccy beside ccy
                throw new IllegalArgumentException(
                        "a lookup matches '" + column + "' twice: " + lookup);
            }
        });
        return many;
    }

    /**
     * What one condition matches against: one of the four scalar sources, or an
     * {@code fn}, and never a nested {@code lookup}.
     * <p>
     * The {@code fn} half is what the schema has claimed since 0.40 and the
     * reader did not do: a var's lookup keyed by a function call validated in an
     * editor and then threw when the spec was read. A call in a *column*
     * lookup's condition is still refused, by {@link
     * io.github.ralfspoeth.xldr.spec.FieldMappingSpec}, which walks a lookup's
     * conditions for exactly that - one call per row is what it exists to
     * prevent.
     * <p>
     * A nested lookup stays out. It would be a join, and a join belongs in a
     * view where the database can plan it, not in a mapping spec.
     */
    private static ValueSource conditionValue(JsonValue condition) {
        var fn = PTR.member("fn").apply(condition).orElse(null);
        if (fn == null) {
            return node(condition).source();
        }
        if (node(condition).hasSource()) {
            throw new IllegalArgumentException(
                    "a condition has an fn and a source member, and one is wanted: " + condition);
        }
        return functionCall(fn);
    }

    /**
     * A call: its {@code name}, the {@code type} it returns, and its {@code args}.
     * <p>
     * {@code type} is required, where a field selector's may be left out and
     * defaults to {@code TEXT}: the loader registers an OUT parameter before the
     * call and has nothing to infer it from.
     * <p>
     * Each argument is a value source of its own, read by the same method that
     * reads a field mapping's - so an argument may be a constant, a var, an
     * expression, a lookup, or another call, and nesting costs nothing. A field
     * among them is refused by {@link VarSpec}, which is where the rule belongs:
     * an argument is evaluated at the same moment as the var it feeds.
     */
    private static ValueSource.FunctionCall functionCall(JsonValue fn) {
        return new ValueSource.FunctionCall(
                PTR.member("name").stringOrThrow(fn),
                PTR.member("type")
                        .stringValue(fn)
                        .map(String::toUpperCase)
                        .map(DataType::valueOf)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "an fn says the type it returns, so that the call can be prepared: " + fn)),
                PTR.member("args")
                        .select(all())
                        .apply(fn)
                        .map(JsonMappingSpecReader::valueSource)
                        .toList());
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
     * A {@code selector} points at records in a tree or a sheet; a
     * {@code discriminator} picks lines out of a flat file; and neither says that
     * the whole input holds one kind of record, which a CSV with a header or a
     * fixed-length file usually does. The three are the cases of
     * {@link io.github.ralfspoeth.xldr.spec.Locator}, and which of them this is
     * gets decided once, in {@link SpecNode#locator}.
     */
    private static RecordSelectorSpec recordSelectorSpec(JsonValue rs) {
        var name = PTR.member("name").stringOrThrow(rs);
        return new RecordSelectorSpec(
                name,
                node(rs).locator("record selector '" + name + "'", discriminator(rs)),
                PTR.member("fieldSelectors")
                        .select(all())
                        .apply(rs)
                        .map(JsonMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    /** A {@code discriminator} member, where there is one. */
    private static @Nullable Discriminator discriminator(JsonValue rs) {
        return PTR.member("discriminator")
                .apply(rs)
                .map(d -> node(d).discriminator())
                .orElse(null);
    }

    private static FieldSelectorSpec fieldSelectorSpec(JsonValue fs) {
        return new FieldSelectorSpec(
                PTR.member("name").stringOrThrow(fs),
                node(fs).selector("a field selector"),
                PTR.member("type")
                        .stringValue(fs)
                        .map(String::toUpperCase)
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    /**
     * This format's answers to the five questions {@link SpecNode} asks, which is
     * all it takes to inherit the rules about what a spec may say.
     * <p>
     * The one that carries weight is {@link #string}: it reads a JSON string and
     * nothing else, so {@code "nth": "1"} does not resolve as a count and
     * {@code "selector": 3} does not resolve as a name. Only {@code equals} takes
     * a scalar of any kind.
     */
    private record Node(JsonValue value) implements SpecNode {

        @Override
        public Optional<String> string(String name) {
            return PTR.member(name).stringValue(value);
        }

        @Override
        public Optional<String> scalar(String name) {
            return PTR.member(name).apply(value).flatMap(JsonMappingSpecReader::text);
        }

        @Override
        public Optional<Integer> whole(String name) {
            return PTR.member(name).apply(value).map(v -> wholeNumber(name, v));
        }

        @Override
        public Optional<ValueSource.Constant> constant() {
            return PTR.member("constant")
                    .apply(value)
                    .map(v -> new ValueSource.Constant(constantValue(v)));
        }

        @Override
        public String shown() {
            return String.valueOf(value);
        }
    }

    private static SpecNode node(JsonValue value) {
        return new Node(value);
    }

    private static int wholeNumber(String name, JsonValue value) {
        var number = value.decimal().orElseThrow(() -> new IllegalArgumentException(
                name + " is a whole number, was " + value));
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is a whole number, was " + number, e);
        }
    }

    private static final Pointer PTR = Pointer.self();
}
