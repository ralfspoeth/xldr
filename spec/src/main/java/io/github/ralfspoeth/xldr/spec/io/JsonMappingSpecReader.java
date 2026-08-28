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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.function.Function;

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
     * The members that hold a source rather than being one, and how each is read.
     * <p>
     * Three of them now, where two could be told apart in a line apiece. Named
     * once, and the same names both checked and dispatched on, so that the list a
     * spec is measured against cannot come to differ from the list the reader can
     * actually read - the {@code fn} the schema offered inside a lookup's
     * condition while the reader threw on it was exactly that divergence, and it
     * stood for three releases because the two lists were never written down
     * beside each other.
     */
    private static final SequencedMap<String, Function<JsonValue, ValueSource>> NESTED = nested();

    private static SequencedMap<String, Function<JsonValue, ValueSource>> nested() {
        var sources = new LinkedHashMap<String, Function<JsonValue, ValueSource>>();
        sources.put("lookup", JsonMappingSpecReader::lookup);
        sources.put("fn", JsonMappingSpecReader::functionCall);
        sources.put("regex", JsonMappingSpecReader::regex);
        return Collections.unmodifiableSequencedMap(sources);
    }

    /**
     * The same, minus the lookup, which is what a lookup's own condition may
     * hold: a condition matching against another lookup is a join, and a join
     * belongs in a view where the database can plan it.
     * <p>
     * A regex here may still read one, its subject being any source at all. That
     * is not the case this excludes - it is two queries one after the other, the
     * pattern applied to what the first returned, which is no more a join than a
     * var reading a lookup is. Where the condition belongs to a *column's*
     * lookup the loader refuses it regardless, a regex there being planned into
     * the same statement and having no value in hand to match against.
     */
    private static final List<String> IN_A_CONDITION = List.of("fn", "regex");

    /**
     * Exactly one source: {@code fieldSelector}, {@code constant}, {@code var} or
     * {@code expr} as a scalar member, or one of the {@link #NESTED} objects.
     * Which of the four scalars, and the refusal when it is not one, is
     * {@link SpecNode#source()}; where the objects sit is this format's business.
     */
    private static ValueSource valueSource(JsonValue fm) {
        return source(fm, NESTED.sequencedKeySet());
    }

    /**
     * One source out of the scalars and whichever objects are allowed here.
     *
     * @param element the object that carries the source
     * @param allowed the object members that may carry a source in this position,
     *                in the order a complaint should list them
     */
    private static ValueSource source(JsonValue element, SequencedCollection<String> allowed) {
        var written = new LinkedHashMap<String, JsonValue>();
        allowed.forEach(name -> PTR.member(name).apply(element).ifPresent(held -> written.put(name, held)));
        if (written.isEmpty()) {
            return node(element).source();
        }
        if (written.size() > 1) {
            throw new IllegalArgumentException(String.join(" and ", written.keySet())
                    + " are sources, and one is wanted: " + element);
        }
        var only = written.firstEntry();
        if (node(element).hasSource()) {
            throw new IllegalArgumentException("a " + only.getKey()
                    + " and a source member are two sources, and one is wanted: " + element);
        }
        return NESTED.get(only.getKey()).apply(only.getValue());
    }

    private static ValueSource.Lookup lookup(JsonValue lookup) {
        return new ValueSource.Lookup(
                new SqlIdentifier(PTR.member("table").stringOrThrow(lookup)),
                new SqlIdentifier(PTR.member("column").stringOrThrow(lookup)),
                conditions(lookup));
    }

    /**
     * A pattern applied to another source: the {@code pattern} it matches with,
     * the capturing {@code group} to take, and the source it reads, written as
     * that source would be written anywhere else.
     *
     * <pre>
     * {"column": "currency",
     *  "regex": {"pattern": ".*_([A-Z]{3})_.*", "group": 1, "expr": "${xldr.filename}"}}
     * </pre>
     * <p>
     * The subject sits among the pattern rather than under a member of its own,
     * which is why this can hand the whole object back to {@link #valueSource}:
     * everything a field mapping may say about where its value comes from, a
     * regex may say about where the text it matches comes from, nesting included.
     * {@code pattern} and {@code group} are not sources and are ignored there.
     * <p>
     * {@code group} defaults to 0, the whole match, so the common case of a
     * pattern written to match exactly what is wanted says nothing. The pattern
     * is compiled here, by {@link ValueSource.Regex#matching}, so that a spec
     * that will not compile is refused when it is read rather than when the first
     * record reaches it - a feed is activated only if its patterns compile.
     */
    private static ValueSource.Regex regex(JsonValue rx) {
        var pattern = PTR.member("pattern")
                .stringValue(rx)
                .orElseThrow(() -> new IllegalArgumentException(
                        "a regex says the pattern it matches with: " + rx));
        var group = node(rx).whole("group").orElse(0);
        return ValueSource.Regex.matching(valueSource(rx), pattern, group);
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
     * What one condition matches against: {@link #IN_A_CONDITION}, which is
     * everything but a nested lookup.
     * <p>
     * The {@code fn} half is what the schema has claimed since 0.40 and the
     * reader did not do: a var's lookup keyed by a function call validated in an
     * editor and then threw when the spec was read. A call in a *column*
     * lookup's condition is still refused, by {@link
     * io.github.ralfspoeth.xldr.spec.FieldMappingSpec}, which walks a lookup's
     * conditions for exactly that - one call per row is what it exists to
     * prevent, and it walks through a regex as well.
     */
    private static ValueSource conditionValue(JsonValue condition) {
        return source(condition, IN_A_CONDITION);
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
