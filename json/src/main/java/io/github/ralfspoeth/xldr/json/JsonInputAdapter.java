package io.github.ralfspoeth.xldr.json;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.json.query.Selector;
import io.github.ralfspoeth.xldr.ia.*;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
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

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Reads records out of a JSON document, both record and field selectors being
 * {@link Pointer}s in Greyson's {@link Pointer#parse(String) parse} syntax:
 * slash separated steps, where a step is a member name, {@code [n]} for the
 * n-th element of an array ({@code [-1]} counting from the end), or
 * {@code #regex} to match a member by pattern.
 * <p>
 * The record selector addresses the records: {@code orders}, or
 * {@code data/orders} for a nested document. An empty selector is the document
 * itself. What it addresses is then fanned out with {@link Selector#all()}, so
 * an array yields one record per element, while a single object yields exactly
 * one record.
 * <p>
 * A field selector is applied to the record, so {@code id} reads one of its
 * members, {@code customer/address/city} reaches into a nested object and
 * {@code tags/[0]} into a nested array. A member that is absent, or that holds
 * {@code null}, yields {@code null}.
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
            fields.putIfAbsent(fs.name(), new FieldDef(pointer(fs.selector()), typeOf(fs)));
        }
        // selector(), not requireSelector(): for this adapter an absent one is
        // an answer rather than an omission - the document itself is the record
        // source, which is what a file that is one top-level array looks like
        return new RecordDef(pointer(rs.selector()), fields);
    }

    private static DataType typeOf(FieldSelectorSpec fs) {
        return fs.dataType() == null ? DataType.TEXT : fs.dataType();
    }

    /**
     * A selector in Greyson's {@link Pointer#parse(String)} syntax. An empty
     * selector addresses the value itself; a leading slash is tolerated and
     * dropped, since {@code parse} would otherwise read it as a step to a member
     * named {@code ""}.
     */
    private static Pointer pointer(@Nullable String path) {
        if (path == null || path.isBlank()) {
            return Pointer.self();
        }
        var stripped = path.strip();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        return stripped.isEmpty() ? Pointer.self() : Pointer.parse(stripped);
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
                .select(Selector.all())
                .apply(document)
                .map(element -> row(element, record, selected));
        return new Result(fields, rows);
    }

    private Row row(JsonValue element, RecordDef record, List<String> selected) {
        Map<String, Object> values = new HashMap<>();
        for (var name : selected) {
            var field = record.fields().get(name);
            field.path.apply(element).map(v -> valueOf(v, field.type)).ifPresent(
                    v -> values.put(name, v)
            );
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

    private @Nullable Object number(BigDecimal value, DataType type) {
        return switch (type) {
            case DECIMAL -> value;
            case INTEGRAL -> value.longValue();
            case FP -> value.doubleValue();
            // a number where the spec wants text or a date: let the shared rules decide
            case TEXT, DATE -> formats.parse(type, value.toPlainString());
        };
    }
}
