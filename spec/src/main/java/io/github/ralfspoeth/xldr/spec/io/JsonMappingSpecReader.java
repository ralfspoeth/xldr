package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.json.query.Selector;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads a JSON mapping specification; the spec by example is given below.
 *
 *
 */
public class JsonMappingSpecReader implements MappingSpecReader {

    @Override
    public MappingSpec readFrom(Reader src) throws IOException {
        return Greyson.readValue(src)
                .map(v -> new MappingSpec(
                        PTR.member("input").apply(v).map(JsonMappingSpecReader::inputSpec).orElseThrow(),
                        PTR.member("mapping").select(Selector.all()).apply(v).map(JsonMappingSpecReader::recordMappingSpec).toList(),
                        PTR.member("load").apply(v).map(JsonMappingSpecReader::loadSpec).orElseGet(LoadSpec::new)
                ))
                .orElseThrow();
    }

    private static InputSpec inputSpec(JsonValue is) {
        return new InputSpec(
                PTR.member("mimeType").stringOrThrow(is),
                PTR.member("sentinel").stringValue(is).orElse(null),
                PTR.member("accepts").stringValue(is).orElse(null),
                PTR.member("recordSelectors")
                        .select(Selector.all())
                        .apply(is)
                        .map(JsonMappingSpecReader::recordSelectorSpec)
                        .toList()
        );
    }

    private static LoadSpec loadSpec(JsonValue ls) {
        return new LoadSpec(
                PTR.member("commitPolicy")
                        .stringValue(ls)
                        .map(String::toUpperCase)
                        .map(CommitPolicy::valueOf)
                        .orElse(null)
        );
    }

    private static RecordMappingSpec recordMappingSpec(JsonValue element) {
        return new RecordMappingSpec(
                PTR.member("recordSelector").stringOrThrow(element),
                PTR.member("databaseTable").stringOrThrow(element),
                PTR.member("fieldMapping")
                        .select(Selector.all())
                        .apply(element)
                        .map(JsonMappingSpecReader::fieldMappingSpec)
                        .toList(),
                PTR.member("limit").intValue(element).stream().boxed().findFirst().orElse(null)
        );
    }

    private static FieldMappingSpec fieldMappingSpec(JsonValue fm) {
        return new FieldMappingSpec(columnSource(fm), PTR.member("databaseColumn").stringOrThrow(fm));
    }

    /**
     * A field mapping carries exactly one source: {@code fieldSelector},
     * {@code constant}, {@code function}, or a {@code lookup} object.
     */
    private static ColumnSource columnSource(JsonValue fm) {
        var lookup = PTR.member("lookup").apply(fm);
        if (lookup.isPresent()) {
            if (hasBasicSource(fm)) {
                throw new IllegalArgumentException("a lookup mapping must carry no other source: " + fm);
            }
            return new ColumnSource.Lookup(
                    PTR.member("table").stringOrThrow(lookup.get()),
                    PTR.member("column").stringOrThrow(lookup.get()),
                    PTR.member("keyColumn").stringOrThrow(lookup.get()),
                    basicSource(lookup.get()));
        }
        return basicSource(fm);
    }

    private static boolean hasBasicSource(JsonValue v) {
        return PTR.member("fieldSelector").stringValue(v).isPresent()
                || PTR.member("function").stringValue(v).isPresent()
                || PTR.member("constant").apply(v).isPresent();
    }

    /**
     * Exactly one of {@code fieldSelector}, {@code constant} or {@code function}.
     */
    private static ColumnSource basicSource(JsonValue v) {
        var field = PTR.member("fieldSelector").stringValue(v);
        var function = PTR.member("function").stringValue(v);
        var constant = PTR.member("constant").apply(v);

        var present = (field.isPresent() ? 1 : 0) + (function.isPresent() ? 1 : 0) + (constant.isPresent() ? 1 : 0);
        if (present != 1) {
            throw new IllegalArgumentException(
                    "needs exactly one of fieldSelector, constant, function: " + v);
        }
        if (field.isPresent()) {
            return new ColumnSource.Field(field.get());
        } else if (function.isPresent()) {
            return new ColumnSource.Function(function.get());
        } else {
            return new ColumnSource.Constant(constantValue(constant.get()));
        }
    }

    /**
     * The constant's Java type follows the JSON literal: string, number (as
     * {@link java.math.BigDecimal}, exact) or boolean.
     */
    private static Object constantValue(JsonValue value) {
        return value.string().map(Object.class::cast)
                .or(() -> value.bool().map(Object.class::cast))
                .or(() -> value.decimal().map(Object.class::cast))
                .orElseThrow(() -> new IllegalArgumentException(
                        "constant must be a string, number or boolean: " + value));
    }

    private static RecordSelectorSpec recordSelectorSpec(JsonValue rs) {
        return new RecordSelectorSpec(
                PTR.member("name").stringOrThrow(rs),
                PTR.member("selector").stringOrThrow(rs),
                PTR.member("fieldSelectors")
                        .select(Selector.all())
                        .apply(rs)
                        .map(JsonMappingSpecReader::fieldSelectorSpec)
                        .toList()
        );
    }

    private static FieldSelectorSpec fieldSelectorSpec(JsonValue fs) {
        return new FieldSelectorSpec(
                PTR.member("name").stringOrThrow(fs),
                PTR.member("selector").stringOrThrow(fs),
                PTR.member("type")
                        .stringValue(fs)
                        .map(String::toUpperCase)
                        .map(DataType::valueOf)
                        .orElse(null)
        );
    }

    private static final Pointer PTR = Pointer.self();
}
