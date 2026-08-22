package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

// Greyson exports a Selector of its own, and so does the spec. A static member
// import takes the one method wanted from Greyson's without importing the type,
// so the bare name still means the spec's wherever it is written.
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
                // through the node, so that "limit": "100" is refused here rather
                // than read as no limit at all - the XML reader always refused it
                node(element).whole("limit").orElse(null)
        );
    }

    private static FieldMappingSpec fieldMappingSpec(JsonValue fm) {
        return new FieldMappingSpec(PTR.member("column").stringOrThrow(fm), valueSource(fm));
    }

    /**
     * A field mapping carries exactly one source: {@code fieldSelector},
     * {@code constant}, {@code var}, {@code expr}, or a {@code lookup} object.
     * Which one, and the refusal when it is not one, is
     * {@link SpecNode#source()}; where the lookup sits is this format's business.
     */
    private static ValueSource valueSource(JsonValue fm) {
        var lookup = PTR.member("lookup").apply(fm).orElse(null);
        if (lookup == null) {
            return node(fm).source();
        }
        if (node(fm).hasSource()) {
            throw new IllegalArgumentException("a lookup mapping must carry no other source: " + fm);
        }
        return new ValueSource.Lookup(
                PTR.member("table").stringOrThrow(lookup),
                PTR.member("column").stringOrThrow(lookup),
                PTR.member("keyColumn").stringOrThrow(lookup),
                node(lookup).source());
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
