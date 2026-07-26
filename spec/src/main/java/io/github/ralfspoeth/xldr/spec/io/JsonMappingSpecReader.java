package io.github.ralfspoeth.xldr.spec.io;

import io.github.ralfspoeth.xldr.spec.*;
import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;
import io.github.ralfspoeth.json.query.Selector;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
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
    public MappingSpec readFrom(Reader src) throws IOException {
        return Greyson.readValue(src)
                .map(v -> new MappingSpec(
                        PTR.member("input").apply(v).map(JsonMappingSpecReader::inputSpec).orElseThrow(),
                        PTR.member("mapping").select(Selector.all()).apply(v).map(JsonMappingSpecReader::recordMappingSpec).toList()
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
                        .toList(),
                PTR.member("vars")
                        .select(Selector.all())
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
        return new FieldMappingSpec(valueSource(fm), PTR.member("databaseColumn").stringOrThrow(fm));
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
