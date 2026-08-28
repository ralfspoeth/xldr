package io.github.ralfspoeth.xldr.json;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.*;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.query.Selector.all;
import static java.nio.charset.StandardCharsets.UTF_8;

// Greyson exports a Selector of its own, and so does the spec. Only one can wear
// the bare name, and here it should be the one a field selector is written in:
// what is wanted from Greyson's is the single method below.

/**
 * Reads records out of a JSON document, both record and field selectors being
 * {@link Pointer}s in Greyson's {@link Pointer#parse(String) parse} syntax:
 * slash separated steps, where a step is a member name, {@code [n]} for the
 * n-th element of an array ({@code [-1]} counting from the end), or
 * {@code #regex} to match a member by pattern. There is no leading slash, and
 * one is refused rather than ignored, a leading slash being RFC 6901's and that
 * syntax differing from this one exactly where it costs most.
 * <p>
 * The record selector addresses the records: {@code orders}, or
 * {@code data/orders} for a nested document. An empty selector is the document
 * itself. What it addresses is then fanned out with Greyson's {@code all()}
 * selector, so an array yields one record per element, while a single object
 * yields exactly one record.
 * <p>
 * A field selector is applied to the record, so {@code id} reads one of its
 * members, {@code customer/address/city} reaches into a nested object and
 * {@code tags/[0]} into a nested array. A member that is absent, or that holds
 * {@code null}, yields {@code null}. A field may also say {@code nth} instead,
 * which is the n-th element of a record that is an array - and {@code null} for
 * one that is an object, a JSON object having no n-th member to speak of.
 * <p>
 * Numbers keep their exact value: a JSON number is taken as a {@code BigDecimal}
 * and narrowed to the declared type rather than being reparsed from text, so a
 * {@code DECIMAL} is never rounded through a double. Text is converted by the
 * shared {@link Formats}, which applies the feed's date and number patterns.
 */
class JsonInputAdapter implements InputAdapter {

    private final Formats formats;
    private final Map<String, RecordDef> records = new HashMap<>();

    /**
     * One record selector: where its records live, and how to read each field.
     */
    private record RecordDef(Pointer path, Map<String, FieldDef> fields) {}

    private record FieldDef(Pointer path, DataType type) {}

    JsonInputAdapter(Formats formats, InputSpec spec) {
        this.formats = formats;
        for (var rs : spec.recordSelectors()) {
            if (records.putIfAbsent(rs.name(), recordDef(rs)) != null) {
                throw new IllegalArgumentException("duplicate record selector " + rs.name());
            }
        }
    }

    private static RecordDef recordDef(RecordSelectorSpec rs) {
        Map<String, FieldDef> fields = new HashMap<>();
        for (var fs : rs.fieldSelectors()) {
            fields.putIfAbsent(fs.name(), new FieldDef(pointerOf(fs), typeOf(fs)));
        }
        // This adapter is why the three cases are worth being three cases. The
        // other two that point at their records reject anything without a
        // selector outright, so a discriminator could not reach them unnoticed;
        // here saying nothing is an answer rather than an omission - the
        // document itself is the record source, which is what a file that is one
        // top-level array looks like. Two of the three states therefore had to
        // be told apart, and while they were one nullable field a discriminator
        // read as neither and was dropped in silence, the load running over the
        // whole document as though nothing had been asked for.
        return new RecordDef(switch (rs.locator()) {
            case Locator.At(var selector) -> pointer(selector);
            case Locator.Every _ -> Pointer.self();
            case Locator.Where _ -> throw rs.locator().wrongBecause(
                    rs.name(), "a JSON document has records to point at");
        }, fields);
    }

    private static DataType typeOf(FieldSelectorSpec fs) {
        return fs.dataType() == null ? DataType.TEXT : fs.dataType();
    }

    /**
     * The pointer a field selector means.
     * <p>
     * A {@code selector} is one already. An {@code nth} is the n-th component of
     * the record, which for JSON means the n-th element of an array - and this
     * syntax already has {@code [i]} for that, so counting costs one step rather
     * than a second way of reading. The step is 0-based where {@code nth} counts
     * from one, which is the whole of the translation.
     * <p>
     * A record that turns out to be an <em>object</em> yields {@code null} for
     * it, and rightly: a JSON object is unordered by specification, so there is no
     * n-th member to speak of. That is a fact about the data rather than about the
     * spec - the next record may be an array - which is why it is a null here and
     * not a refusal when the adapter is built.
     */
    private static Pointer pointerOf(FieldSelectorSpec fs) {
        return switch (fs.selector()) {
            case Selector.Text(var path) -> pointer(path);
            case Selector.Nth nth -> pointer("[" + nth.index() + "]");
        };
    }

    /**
     * A selector in Greyson's {@link Pointer#parse(String)} syntax. An empty
     * selector addresses the value itself.
     * <p>
     * A leading slash is refused, being the one unambiguous sign that the author
     * meant <a href="https://datatracker.ietf.org/doc/html/rfc6901">RFC 6901</a>
     * - the syntax of JSON Schema {@code $ref}, JSON Patch and OpenAPI, and the
     * one a reader coming from any of those writes by reflex. The two differ
     * exactly where it costs most: an array step there is a bare number, and a
     * bare number here is a member name.
     * <p>
     * It used to be dropped instead, and that was quiet in both directions.
     * {@code /orders} and {@code orders} do mean the same thing, so the tolerance
     * was harmless for every path without an array step - and wrong for every
     * path with one. {@code /orders/0/id} against
     * {@code {"orders":[{"id":7}]}} parsed, read {@code 0} as a member name,
     * matched nothing, and bound SQL NULL for every row: a spec that validates
     * against the published schema, loads without a word, and fills a column with
     * nothing. Worse, accepting the slash confirmed the belief that produced it,
     * since the author's evidence for "this is being read as a JSON Pointer" was
     * that the pointer had been accepted.
     * <p>
     * The refusal deliberately stops at the slash. A bare numeric step without
     * one - {@code orders/0/id} - is still read as a member name, because
     * {@code {"0": ...}} is a legal object and forbidding it to catch a suspected
     * mistake would refuse a document that has every right to exist. What covers
     * that residue is {@code xldr check --sample}, which prints the value read
     * for each field.
     */
    private static Pointer pointer(String path) {
        // no null branch: the only caller that could pass one was the record
        // selector's absent selector, which is now Locator.Every and answers
        // Pointer.self() where it is read
        if (path.isBlank()) {
            return Pointer.self();
        }
        var trimmed = path.strip();
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("selector '" + path + "' begins with a slash, which is RFC 6901's"
                    + " syntax and not this one. A step here is a member name, [n] for the n-th element of an array"
                    + " (negative counting from the end), or #regex for a member matched by pattern - so an index"
                    + " needs its brackets, and a bare number is read as a member name. Drop the leading slash and"
                    + " bracket the indices.");
        }
        return Pointer.parse(trimmed);
    }

    @Override
    public Result parse(InputStream source, String recordSelector, Set<String> fieldSelectors) throws IOException {
        var record = records.get(recordSelector);
        if (record == null) {
            throw new IllegalArgumentException("no record selector named " + recordSelector
                    + "; the input spec declares " + records.keySet());
        }
        var unknown = fieldSelectors.stream().filter(n -> !record.fields().containsKey(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("record selector " + recordSelector
                    + " declares no field selector(s) " + unknown);
        }
        var selected = fieldSelectors.stream().filter(record.fields()::containsKey).toList();
        var fields = selected.stream()
                .map(name -> new Field(name, record.fields().get(name).type().clazz()))
                .toList();

        // RFC 8259: JSON exchanged between systems is UTF-8
        var document = Greyson.readValue(new InputStreamReader(source, UTF_8))
                .orElseThrow(() -> new IOException("empty JSON document"));

        // the elements of the selected array are the records, in document order
        Stream<Row> rows = record.path()
                .select(all())
                .apply(document)
                .map(element -> row(element, record, selected));
        return new Result(fields, rows);
    }

    private Row row(JsonValue element, RecordDef record, List<String> selected) {
        // only the values that are there: an absent member and one that holds
        // null are the same absent value, and Map::get answers null for both -
        // which keeps the map's values non-null and the Row's contract intact
        Map<String, Object> values = new HashMap<>();
        for (var name : selected) {
            var field = record.fields().get(name);
            field.path()
                    .apply(element)
                    .map(v -> valueOf(v, field.type()))
                    .ifPresent(v -> values.put(name, v));
        }
        return values::get;
    }

    /**
     * A JSON string is converted by the configured formats; a JSON number is
     * narrowed from its exact decimal; a boolean is read as its literal. Anything
     * else - a null, an object, an array - is an absent value.
     */
    private @Nullable Object valueOf(JsonValue value, DataType type) {
        var text = value.string();
        if (text.isPresent()) {
            return formats.parse(type, text.get());
        }
        var number = value.decimal();
        if (number.isPresent()) {
            return number(number.get(), type);
        }
        var bool = value.bool();
        return bool.map(aBoolean -> type == DataType.TEXT ? aBoolean.toString() : formats.parse(type, aBoolean.toString())).orElse(null);
    }

    /**
     * A JSON number literal, as the declared type.
     * <p>
     * No pattern is involved: the document has already said this is a number, so
     * there is nothing to parse and {@code numberFormat} does not apply. The
     * {@code INTEGRAL} rule is still the shared one - a fraction or a magnitude
     * beyond 64 bits is refused rather than dropped, which {@code longValue()}
     * did silently here for as long as this adapter has existed, and without even
     * a {@code numberFormat} needing to be configured for it to happen.
     */
    private @Nullable Object number(BigDecimal value, DataType type) {
        return switch (type) {
            case DECIMAL -> value;
            case INTEGRAL -> Formats.integral(value, value.toPlainString());
            // FP is the type that says it may be approximate, so this one is the
            // contract rather than a loss
            case FP -> value.doubleValue();
            // a number where the spec wants text or a date: let the shared rules decide
            case TEXT, TEMPORAL -> formats.parse(type, value.toPlainString());
        };
    }
}
