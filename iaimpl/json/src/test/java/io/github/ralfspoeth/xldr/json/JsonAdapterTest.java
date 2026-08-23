package io.github.ralfspoeth.xldr.json;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class JsonAdapterTest {

    private static final String MIME = "application/json";

    private static InputSpec spec(String recordSelector, FieldSelectorSpec... fields) {
        return spec(new Locator.At(recordSelector), fields);
    }

    private static InputSpec spec(Locator locator, FieldSelectorSpec... fields) {
        return new InputSpec(MIME,
                List.of(new RecordSelectorSpec("rec", locator, List.of(fields))),
                List.of(),
                Map.of());
    }

    /**
     * The adapter's settings are part of the input spec, so they are added to a
     * copy of it rather than set on the factory.
     * <p>
     * {@link InputAdapterFactory#of} rather than {@link java.util.ServiceLoader}
     * directly: these tests are patched into the module they test, and a module
     * may only load a service it declares {@code uses} for - which {@code json}
     * does not, being a provider. {@code of} works anyway, its lookup running in
     * {@code ia}, whose descriptor carries the {@code uses}.
     */
    private static InputAdapter adapter(InputSpec spec, Map<String, String> properties) {
        var configured = new InputSpec(spec.mimeType(),
                spec.recordSelectors(), spec.vars(), properties);
        return InputAdapterFactory.of(configured)
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
    void readsTheElementsOfAnArray() throws IOException {
        var spec = spec("orders",
                new FieldSelectorSpec("id", "id", DataType.TEXT),
                new FieldSelectorSpec("qty", "qty", DataType.INTEGRAL));

        var result = adapter(spec, Map.of()).parse(in("""
                { "orders": [
                    { "id": "A-1", "qty": 3 },
                    { "id": "A-2", "qty": 7 }
                ] }
                """), "rec", Set.of("id", "qty"));

        var rows = result.rows().toList();
        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("A-1", rows.getFirst().get("id")),
                () -> assertEquals(3L, rows.getFirst().get("qty")),
                () -> assertEquals("A-2", rows.getLast().get("id")),
                () -> assertEquals(7L, rows.getLast().get("qty"))
        );
    }

    /**
     * A nested document is reached by a multi segment path, and so is a nested
     * member of a record.
     */
    @Test
    void followsNestedPaths() throws IOException {
        var spec = spec("data/orders",
                new FieldSelectorSpec("id", "id", DataType.TEXT),
                new FieldSelectorSpec("city", "customer/address/city", DataType.TEXT));

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
    void convertsAccordingToTheDeclaredType() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("txt", "txt", DataType.TEXT),
                new FieldSelectorSpec("num", "num", DataType.INTEGRAL),
                new FieldSelectorSpec("dec", "dec", DataType.DECIMAL),
                new FieldSelectorSpec("flt", "flt", DataType.FP),
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
    void treatsAbsentAndNullMembersAsNull() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("id", "id", DataType.TEXT),
                new FieldSelectorSpec("note", "note", DataType.TEXT),
                new FieldSelectorSpec("qty", "qty", DataType.INTEGRAL));

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
     * A JSON number declared {@code INTEGRAL} has to be one.
     * <p>
     * This adapter does not go through a pattern for a number literal - the
     * document has already said it is a number - so it reached
     * {@code BigDecimal.longValue()} directly, which drops a fraction and wraps
     * an overflow. No {@code numberFormat} had to be configured for it to
     * happen, which made this the quieter of the two places the same mistake
     * lived.
     */
    @Test
    void refusesAnumberThatIsNotAwholeIntegral() throws IOException {
        var spec = spec("rows", new FieldSelectorSpec("qty", "qty", DataType.INTEGRAL));

        var fraction = assertThrows(RuntimeException.class,
                () -> adapter(spec, Map.of())
                        .parse(in("""
                                { "rows": [ { "qty": 1.5 } ] }
                                """), "rec", Set.of("qty"))
                        .rows().toList());
        var tooBig = assertThrows(RuntimeException.class,
                () -> adapter(spec, Map.of())
                        .parse(in("""
                                { "rows": [ { "qty": 9999999999999999999999999 } ] }
                                """), "rec", Set.of("qty"))
                        .rows().toList());

        assertAll(
                () -> assertTrue(fraction.getMessage().contains("1.5"), fraction.getMessage()),
                () -> assertTrue(tooBig.getMessage().contains("INTEGRAL"), tooBig.getMessage()));
    }

    /**
     * And a number that is whole still loads however it was written - a JSON
     * literal may carry a decimal point and still be an integer.
     */
    @Test
    void awholeNumberLoadsHoweverItIsWritten() throws IOException {
        var spec = spec("rows", new FieldSelectorSpec("qty", "qty", DataType.INTEGRAL));

        var rows = adapter(spec, Map.of()).parse(in("""
                { "rows": [ { "qty": 3 }, { "qty": 3.0 }, { "qty": 3.00 }, { "qty": 3e2 } ] }
                """), "rec", Set.of("qty")).rows().toList();

        assertEquals(List.of(3L, 3L, 3L, 300L), rows.stream().map(r -> r.get("qty")).toList());
    }

    /**
     * Values carried as strings still honor the feed's patterns.
     */
    @Test
    void appliesTheConfiguredFormats() throws IOException {
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
     * {@link Locator.Every} is a case this adapter honours rather than one it
     * refuses: for JSON, saying nothing about where the records are is an answer
     * and not an omission, which is what a file that is one top-level array
     * looks like.
     */
    @Test
    void readsAtopLevelArray() throws IOException {
        var spec = spec(Locator.every(), new FieldSelectorSpec("id", "id", DataType.TEXT));

        var result = adapter(spec, Map.of())
                .parse(in("""
                        [ { "id": "A-1" }, { "id": "A-2" } ]
                        """), "rec", Set.of("id"));

        assertEquals(List.of("A-1", "A-2"), result.rows().map(r -> r.get("id")).toList());
    }

    /**
     * A blank selector is not the same thing, and no longer reads as though it
     * were.
     * <p>
     * It used to: this adapter resolved {@code ""} to the document while XML and
     * Excel refused it, so one spelling meant two things depending on which
     * adapter read it. {@link Locator.At} refuses a blank selector for everyone,
     * which leaves exactly one way to say "every record" - saying nothing.
     */
    @Test
    void refusesAblankSelector() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> spec("", new FieldSelectorSpec("id", "id", DataType.TEXT)));
        assertTrue(thrown.getMessage().contains("blank"), thrown.getMessage());
    }

    /**
     * A record selector that addresses a single object, rather than an array,
     * yields exactly one record.
     */
    @Test
    void readsASingleObjectAsOneRecord() throws IOException {
        var spec = spec("data/order", new FieldSelectorSpec("id", "id", DataType.TEXT));

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
    void addressesArrayElementsByIndex() throws IOException {
        var spec = spec("batches/[-1]/rows",
                new FieldSelectorSpec("id", "id", DataType.TEXT),
                new FieldSelectorSpec("first", "tags/[0]", DataType.TEXT));

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

    /**
     * A field may count instead of naming, and for JSON the n-th component of a
     * record is the n-th element of an array. This syntax already had {@code [i]}
     * for that, so counting is a step rather than a second way of reading - the
     * only translation being that a step is 0-based where {@code nth} counts from
     * one.
     */
    @Test
    void countsTheElementsOfAnArrayRecord() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("id", new Selector.Nth(1), DataType.TEXT),
                new FieldSelectorSpec("name", new Selector.Nth(2), DataType.TEXT),
                // past the end of this record's array
                new FieldSelectorSpec("spare", new Selector.Nth(9), DataType.TEXT));

        var rows = adapter(spec, Map.of()).parse(in("""
                { "rows": [ ["1", "Alice"], ["2", "Bob"] ] }
                """), "rec", Set.of("id", "name", "spare")).rows().toList();

        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("1", rows.getFirst().get("id")),
                () -> assertEquals("Alice", rows.getFirst().get("name")),
                () -> assertEquals("2", rows.get(1).get("id")),
                () -> assertEquals("Bob", rows.get(1).get("name")),
                () -> assertNull(rows.getFirst().get("spare")));
    }

    /**
     * And a record that turns out to be an <em>object</em> has no n-th member to
     * speak of, a JSON object being unordered by specification - so counting
     * yields null rather than whichever member the parser happened to keep first.
     * <p>
     * A null and not a refusal, because this is a fact about the data: the same
     * spec against the next document may meet arrays throughout, and only the
     * document can say.
     */
    @Test
    void countingAnObjectRecordYieldsNull() throws IOException {
        var spec = spec("rows",
                new FieldSelectorSpec("counted", new Selector.Nth(1), DataType.TEXT),
                new FieldSelectorSpec("named", "id", DataType.TEXT));

        var row = adapter(spec, Map.of()).parse(in("""
                { "rows": [ { "id": "1", "name": "Alice" } ] }
                """), "rec", Set.of("counted", "named")).rows().toList().getFirst();

        assertAll(
                () -> assertNull(row.get("counted"), "an object has no first element"),
                () -> assertEquals("1", row.get("named"), "and naming still works"));
    }

    /**
     * A discriminator is refused, and this adapter is the reason the refusal
     * exists.
     * <p>
     * The other two that point at their records require a selector, so one could
     * not slip past them unnoticed. Here an absent selector legitimately means
     * the whole document, so a record selector carrying only a discriminator used
     * to be read as "the document, unfiltered" - the load ran over everything and
     * reported success, with the filter the author wrote silently dropped.
     */
    @Test
    void rejectsAdiscriminator() {
        var spec = new InputSpec(MIME, List.of(new RecordSelectorSpec("rec",
                new Locator.Where(new Discriminator.Equals(new Selector.Nth(1), "O")),
                List.of(new FieldSelectorSpec("id", "id", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("rec"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("records to point at"), thrown.getMessage()));
    }

    /**
     * A leading slash is refused, in either kind of selector.
     * <p>
     * It used to be stripped, which was right for the path it was tested on and
     * wrong for the one that matters: {@code /orders} and {@code orders} do mean
     * the same thing, but {@code /orders/0/id} - RFC 6901, and what anyone
     * arriving from JSON Schema or JSON Patch writes - read {@code 0} as a member
     * name, matched nothing and bound NULL for every row, on a spec the published
     * schema accepts. The refusal is at construction, so it lands before a file
     * is opened rather than as an empty column afterwards.
     */
    @Test
    void refusesAnRfc6901Selector() {
        var record = assertThrows(IllegalArgumentException.class,
                () -> adapter(spec("/orders", new FieldSelectorSpec("id", "id", DataType.TEXT)), Map.of()));
        var field = assertThrows(IllegalArgumentException.class,
                () -> adapter(spec("orders", new FieldSelectorSpec("id", "/id", DataType.TEXT)), Map.of()));
        assertAll(
                () -> assertTrue(record.getMessage().contains("/orders"), record.getMessage()),
                () -> assertTrue(record.getMessage().contains("6901"), record.getMessage()),
                () -> assertTrue(field.getMessage().contains("/id"), field.getMessage()),
                () -> assertTrue(field.getMessage().contains("6901"), field.getMessage()));
    }

    /**
     * The refusal stops at the slash. {@code {"0": ...}} is a legal object, so a
     * bare numeric step keeps meaning what it says - a member of that name - and
     * is not second-guessed into an index.
     */
    @Test
    void abareNumberIsStillAmemberName() throws IOException {
        var spec = spec("rows/0", new FieldSelectorSpec("id", "id", DataType.TEXT));

        var result = adapter(spec, Map.of()).parse(in("""
                { "rows": { "0": { "id": "A-1" } } }
                """), "rec", Set.of("id"));

        assertEquals(List.of("A-1"), result.rows().map(r -> r.get("id")).toList());
    }

    @Test
    void rejectsAnUnknownRecordSelector() {
        var spec = spec("rows", new FieldSelectorSpec("id", "id", DataType.TEXT));
        var adapter = adapter(spec, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parse(in("{\"rows\":[]}"), "nope", Set.of("id")));
    }
}
