package io.github.ralfspoeth.xldr.json.test;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public class JsonAdapterTest {

    private static final String MIME = "application/json";

    private static InputSpec spec(String recordSelector, FieldSelectorSpec... fields) {
        return new InputSpec(MIME, null, null,
                List.of(new RecordSelectorSpec("rec", recordSelector, List.of(fields))),
                List.of(),
                Map.of());
    }

    /**
     * The adapter's settings are part of the input spec, so they are added to a
     * copy of it rather than set on the factory.
     */
    private static InputAdapter adapter(InputSpec spec, Map<String, String> properties) {
        var configured = new InputSpec(spec.mimeType(), spec.sentinel(), spec.accepts(),
                spec.recordSelectors(), spec.vars(), properties);
        return ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.reads(configured))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no adapter for " + configured.mimeType()))
                .createInputAdapter(configured);
    }

    private static InputStream in(String json) {
        return new ByteArrayInputStream(json.getBytes(UTF_8));
    }

    /**
     * The record selector names an array; each element is one record, and a
     * field selector reads a member of it.
     */
    @Test
    public void readsTheElementsOfAnArray() throws IOException {
        var spec = spec("orders",
                new FieldSelectorSpec("id", "id", DataType.STRING),
                new FieldSelectorSpec("qty", "qty", DataType.INTEGER));

        var result = adapter(spec, Map.of()).parse(in("""
                { "orders": [
                    { "id": "A-1", "qty": 3 },
                    { "id": "A-2", "qty": 7 }
                ] }
                """), "rec", Set.of("id", "qty"));

        var rows = result.rows().toList();
        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("A-1", rows.get(0).get("id")),
                () -> assertEquals(3L, rows.get(0).get("qty")),
                () -> assertEquals("A-2", rows.get(1).get("id")),
                () -> assertEquals(7L, rows.get(1).get("qty"))
        );
    }

    /**
     * A nested document is reached by a multi segment path, and so is a nested
     * member of a record.
     */
    @Test
    public void followsNestedPaths() throws IOException {
        var spec = spec("data/orders",
                new FieldSelectorSpec("id", "id", DataType.STRING),
                new FieldSelectorSpec("city", "customer/address/city", DataType.STRING));

        var result = adapter(spec, Map.of()).parse(in("""
                { "data": { "orders": [
                    { "id": "A-1", "customer": { "address": { "city": "Berlin" } } }
                ] } }
                """), "rec", Set.of("id", "city"));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("A-1", row.get("id")),
                () -> assertEquals("Berlin", row.get("city"))
        );
    }

    /**
     * The declared type governs the exposed field type and the value, and a JSON
     * number keeps its exact value rather than going through a double.
     */
    @Test
    public void convertsAccordingToTheDeclaredType() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("txt", "txt", DataType.STRING),
                new FieldSelectorSpec("num", "num", DataType.INTEGER),
                new FieldSelectorSpec("dec", "dec", DataType.DECIMAL),
                new FieldSelectorSpec("flt", "flt", DataType.FLOAT),
                new FieldSelectorSpec("day", "day", DataType.DATE));

        var names = Set.of("txt", "num", "dec", "flt", "day");
        var result = adapter(spec, Map.of()).parse(in("""
                { "rows": [
                    { "txt": "abc", "num": 42, "dec": 100000000000000000.01,
                      "flt": 0.25, "day": "2026-07-22" }
                ] }
                """), "rec", names);

        assertEquals(
                Map.of("txt", String.class, "num", Long.class, "dec", BigDecimal.class,
                        "flt", Double.class, "day", LocalDateTime.class),
                result.fields().stream().collect(Collectors.toMap(Field::name, Field::type)));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("abc", row.get("txt")),
                () -> assertEquals(42L, row.get("num")),
                () -> assertEquals(new BigDecimal("100000000000000000.01"), row.get("dec")),
                () -> assertEquals(0.25d, row.get("flt")),
                () -> assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0), row.get("day"))
        );
    }

    /**
     * A member that is absent, and one that is JSON null, are both absent values.
     */
    @Test
    public void treatsAbsentAndNullMembersAsNull() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("id", "id", DataType.STRING),
                new FieldSelectorSpec("note", "note", DataType.STRING),
                new FieldSelectorSpec("qty", "qty", DataType.INTEGER));

        var result = adapter(spec, Map.of()).parse(in("""
                { "rows": [ { "id": "A-1", "note": null } ] }
                """), "rec", Set.of("id", "note", "qty"));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("A-1", row.get("id")),
                () -> assertNull(row.get("note"), "an explicit JSON null"),
                () -> assertNull(row.get("qty"), "a member that is not there at all")
        );
    }

    /**
     * Values carried as strings still honour the feed's patterns.
     */
    @Test
    public void appliesTheConfiguredFormats() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("day", "day", DataType.DATE),
                new FieldSelectorSpec("amount", "amount", DataType.DECIMAL));

        var result = adapter(spec, Map.of("dateFormat", "dd.MM.yyyy", "numberFormat", "#,##0.00", "locale", "de-DE"))
                .parse(in("""
                        { "rows": [ { "day": "22.07.2026", "amount": "1.234,56" } ] }
                        """), "rec", Set.of("day", "amount"));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0), row.get("day")),
                () -> assertEquals(new BigDecimal("1234.56"), row.get("amount"))
        );
    }

    /**
     * The document itself may be the array of records.
     * <p>
     * Both spellings of "no selector" mean it - blank and absent alike resolve
     * to the document. This adapter is one of those that can do without one, so
     * it reads {@code selector()} rather than {@code requireSelector()}: for
     * JSON an absent selector is an answer, not an omission.
     */
    @Test
    public void readsAtopLevelArray() throws IOException {
        for (var selector : new String[]{"", null}) {
            var spec = spec(selector, new FieldSelectorSpec("id", "id", DataType.STRING));

            var result = adapter(spec, Map.of())
                    .parse(in("""
                            [ { "id": "A-1" }, { "id": "A-2" } ]
                            """), "rec", Set.of("id"));

            assertEquals(List.of("A-1", "A-2"), result.rows().map(r -> r.get("id")).toList(),
                    "selector " + (selector == null ? "absent" : "blank"));
        }
    }

    /**
     * A record selector that addresses a single object, rather than an array,
     * yields exactly one record.
     */
    @Test
    public void readsAsingleObjectAsOneRecord() throws IOException {
        var spec = spec("data/order", new FieldSelectorSpec("id", "id", DataType.STRING));

        var result = adapter(spec, Map.of()).parse(in("""
                { "data": { "order": { "id": "A-1" } } }
                """), "rec", Set.of("id"));

        assertEquals(List.of("A-1"), result.rows().map(r -> r.get("id")).toList());
    }

    /**
     * A step may address an array element by index, counting from the end with a
     * negative one - the pointer syntax of Greyson, in both kinds of selector.
     */
    @Test
    public void addressesArrayElementsByIndex() throws IOException {
        var spec = spec("batches/[-1]/rows",
                new FieldSelectorSpec("id", "id", DataType.STRING),
                new FieldSelectorSpec("first", "tags/[0]", DataType.STRING));

        var result = adapter(spec, Map.of()).parse(in("""
                { "batches": [
                    { "rows": [ { "id": "old", "tags": ["x"] } ] },
                    { "rows": [ { "id": "new", "tags": ["a", "b"] } ] }
                ] }
                """), "rec", Set.of("id", "first"));

        var row = result.rows().toList().getFirst();
        assertAll(
                // the last batch, not the first
                () -> assertEquals("new", row.get("id")),
                () -> assertEquals("a", row.get("first"))
        );
    }

    @Test
    public void rejectsAnUnknownRecordSelector() throws IOException {
        var spec = spec("rows", new FieldSelectorSpec("id", "id", DataType.STRING));
        var adapter = adapter(spec, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parse(in("{\"rows\":[]}"), "nope", Set.of("id")));
    }
}
