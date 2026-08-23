package io.github.ralfspoeth.xldr.flt;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public class FixedLengthAdapterTest {

    private static final String MIME = "text/plain";

    /**
     * No selector: a fixed-length file has nowhere to point at, and one written
     * here is refused when the adapter is built. It used to say {@code "rec"},
     * which the adapter ignored - the fixture and the format disagreed and
     * nothing noticed.
     */
    private static InputSpec spec(FieldSelectorSpec... fields) {
        return new InputSpec(MIME, List.of(new RecordSelectorSpec("rec",
                Locator.every(), List.of(fields))), List.of(), Map.of());
    }

    /**
     * The adapter's settings are part of the input spec, so they are added to a
     * copy of it rather than set on the factory.
     * <p>
     * {@link InputAdapterFactory#of} rather than {@link java.util.ServiceLoader}
     * directly: these tests are patched into the module they test, and a module
     * may only load a service it declares {@code uses} for - which {@code flt}
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

    private static InputStream in(String text) {
        return new ByteArrayInputStream(text.getBytes(UTF_8));
    }

    /**
     * Explicit {@code left:right} bounds cut each line into its columns; the
     * bounds are half open, so {@code 0:3} is the first three characters. Values
     * are stripped, and a line that ends early is simply shorter than its
     * bounds rather than an error.
     */
    @Test
    public void cutsColumnsAtExplicitBounds() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("id", "0:3", DataType.TEXT),
                new FieldSelectorSpec("name", "3:8", DataType.TEXT));

        var result = adapter(spec, Map.of()).parse(in("""
                001Alice
                002Bob
                """), "rec", Set.of("id", "name"));

        var rows = result.rows().toList();
        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("001", rows.getFirst().get("id")),
                () -> assertEquals("Alice", rows.getFirst().get("name")),
                () -> assertEquals("002", rows.getLast().get("id")),
                () -> assertEquals("Bob", rows.getLast().get("name"))
        );
    }

    /**
     * Padding is an artifact of the format, not data: a padded field yields the
     * same value as an unpadded one, and a field beyond the end of the line is
     * null rather than an error.
     */
    @Test
    public void stripsPaddingAndToleratesShortLines() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("name", "0:8", DataType.TEXT),
                new FieldSelectorSpec("qty", "8:12", DataType.INTEGRAL),
                new FieldSelectorSpec("tail", "12:20", DataType.TEXT));

        var result = adapter(spec, Map.of())
                .parse(in("Alice     42\n"), "rec", Set.of("name", "qty", "tail"));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("Alice", row.get("name")),
                // right justified and space padded, yet parses as a number
                () -> assertEquals(42L, row.get("qty")),
                // the line ends before this field starts
                () -> assertNull(row.get("tail"))
        );
    }

    /**
     * An omitted left bound continues where the previous field ended, so a
     * layout can be written as a list of end positions.
     */
    @Test
    public void continuesFromThePreviousFieldWhenLeftIsOmitted() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("a", ":3", DataType.TEXT),
                new FieldSelectorSpec("b", ":6", DataType.TEXT),
                new FieldSelectorSpec("c", ":9", DataType.TEXT));

        var result = adapter(spec, Map.of()).parse(in("abcdefghi\n"), "rec", Set.of("a", "b", "c"));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("abc", row.get("a")),
                () -> assertEquals("def", row.get("b")),
                () -> assertEquals("ghi", row.get("c"))
        );
    }

    /**
     * A field's declared type governs the Java type of its value, both in the
     * exposed fields and in the parsed row.
     */
    @Test
    public void convertsAccordingToTheDeclaredType() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("txt", "0:3", DataType.TEXT),
                new FieldSelectorSpec("num", "3:6", DataType.INTEGRAL),
                new FieldSelectorSpec("dec", "6:11", DataType.DECIMAL),
                new FieldSelectorSpec("flt", "11:15", DataType.FP));

        var names = Set.of("txt", "num", "dec", "flt");
        var result = adapter(spec, Map.of()).parse(in("abc04212.500.25\n"), "rec", names);

        assertEquals(
                Map.of("txt", String.class, "num", Long.class,
                        "dec", BigDecimal.class, "flt", Double.class),
                result.fields().stream().collect(Collectors.toMap(Field::name, Field::type)));

        var row = result.rows().toList().getFirst();
        assertAll(
                () -> assertEquals("abc", row.get("txt")),
                () -> assertEquals(42L, row.get("num")),
                () -> assertEquals(new BigDecimal("12.50"), row.get("dec")),
                () -> assertEquals(0.25d, row.get("flt"))
        );
    }

    /**
     * Only the requested field selectors are exposed, even though the spec
     * declares more.
     */
    @Test
    public void exposesOnlyTheRequestedFields() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("id", "0:3", DataType.TEXT),
                new FieldSelectorSpec("name", "3:8", DataType.TEXT));

        var result = adapter(spec, Map.of()).parse(in("001Alice\n"), "rec", Set.of("id"));

        assertEquals(List.of("id"), result.fields().stream().map(Field::name).toList());
    }

    /**
     * With {@code linesPerRecord} greater than one the lines of a record are
     * joined, and the bounds address the joined text - so a field may sit on the
     * second line.
     */
    @Test
    public void joinsSeveralLinesIntoOneRecord() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("id", "0:3", DataType.TEXT),
                new FieldSelectorSpec("city", "3:9", DataType.TEXT));

        var result = adapter(spec, Map.of("linesPerRecord", "2")).parse(in("""
                001
                Berlin
                002
                Bremen
                """), "rec", Set.of("id", "city"));

        var rows = result.rows().toList();
        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("001", rows.getFirst().get("id")),
                () -> assertEquals("Berlin", rows.getFirst().get("city")),
                () -> assertEquals("002", rows.get(1).get("id")),
                () -> assertEquals("Bremen", rows.get(1).get("city"))
        );
    }

    /**
     * The charset is honored when decoding the input.
     */
    @Test
    public void decodesWithTheConfiguredCharset() throws IOException {
        var spec = spec(new FieldSelectorSpec("s", "0:5", DataType.TEXT));
        var latin1 = new ByteArrayInputStream("Grüße".getBytes(ISO_8859_1));

        var result = adapter(spec, Map.of("charset", "ISO-8859-1"))
                .parse(latin1, "rec", Set.of("s"));

        assertEquals("Grüße", result.rows().toList().getFirst().get("s"));
    }

    /**
     * UTF-8 where the spec says nothing, rather than whatever
     * {@code -Dfile.encoding} the JVM happened to start with - so a file loads
     * the same way on the machine that tested the spec and the one that runs it.
     * <p>
     * Fixed-length is where this bites hardest, and the second half of the test
     * is the reason it is worth a default rather than a convention. The bounds
     * are counted in characters: {@code ü} is two bytes, so reading UTF-8 bytes
     * as latin-1 makes it two characters and every field after it has moved.
     * Not a garbled value - a garbled record, and one that still parses.
     */
    @Test
    public void decodesAsUtf8WhenTheSpecSaysNothing() throws IOException {
        var spec = spec(
                new FieldSelectorSpec("city", "0:6", DataType.TEXT),
                new FieldSelectorSpec("id", "6:9", DataType.TEXT));
        var bytes = "Zürich007".getBytes(UTF_8);

        var byDefault = adapter(spec, Map.of())
                .parse(new ByteArrayInputStream(bytes), "rec", Set.of("city", "id"))
                .rows().toList().getFirst();
        assertAll(
                () -> assertEquals("Zürich", byDefault.get("city")),
                () -> assertEquals("007", byDefault.get("id")));

        var asLatin1 = adapter(spec, Map.of("charset", "ISO-8859-1"))
                .parse(new ByteArrayInputStream(bytes), "rec", Set.of("city", "id"))
                .rows().toList().getFirst();
        assertAll(
                () -> assertNotEquals("Zürich", asLatin1.get("city"), "one byte too many, so one character short"),
                () -> assertEquals("h00", asLatin1.get("id"), "and the field after it has slid left by one"));
    }

    /**
     * A selector that is not a {@code left:right} pair names itself in the error.
     */
    @Test
    public void rejectsAmalformedSelector() {
        var spec = spec(new FieldSelectorSpec("id", "0-3", DataType.TEXT));
        assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
    }

    /**
     * A fixed-length record has offsets, not columns, so a spec that counts
     * columns here has confused this format with a separated one. Refused when
     * the adapter is built, and told which of the two it is.
     */
    @Test
    public void rejectsAcolumn() {
        var spec = spec(new FieldSelectorSpec("id", new Selector.Nth(1), DataType.TEXT));
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("character range"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("'id'"), thrown.getMessage()));
    }

    // ---- what this format cannot mean ---------------------------------------

    /**
     * A record selector with a {@code selector} is refused, as it is by the CSV
     * adapter. It was read and discarded here, so a spec transliterated from an
     * XML or JSON one kept an XPath that had stopped meaning anything - and the
     * fixture in this very class carried {@code "rec"} for exactly that reason.
     */
    @Test
    public void rejectsAselectorOnTheRecordSelector() {
        var spec = new InputSpec(MIME, List.of(new RecordSelectorSpec("rec", new Locator.At("//record"),
                List.of(new FieldSelectorSpec("id", "0:3", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no place to point at"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("//record"), thrown.getMessage()));
    }

    /**
     * Two record selectors, each keeping its own lines, as the CSV adapter does.
     * <p>
     * The classic fixed-length layout: a type code in the first columns, and a
     * different arrangement of characters after it per type. Before this the
     * adapter took one record selector and read every line as one kind, so a file
     * like this could not be loaded at all.
     */
    @Test
    public void twoRecordSelectorsPartitionTheFile() throws IOException {
        var spec = new InputSpec(MIME, List.of(
                new RecordSelectorSpec("orders",
                        new Locator.Where(new Discriminator.Equals(new Selector.Text("0:2"), "OR")),
                        List.of(new FieldSelectorSpec("id", "2:6", DataType.TEXT),
                                new FieldSelectorSpec("cust", "6:11", DataType.TEXT))),
                new RecordSelectorSpec("lines",
                        new Locator.Where(new Discriminator.Equals(new Selector.Text("0:2"), "LI")),
                        List.of(new FieldSelectorSpec("order", "2:6", DataType.TEXT),
                                new FieldSelectorSpec("sku", "6:14", DataType.TEXT),
                                new FieldSelectorSpec("qty", "14:17", DataType.INTEGRAL)))
        ), List.of(), Map.of());

        // \s at the end keeps the padding: a text block strips trailing whitespace
        // before it interprets escapes, so \s is still two characters at that point
        // and shields what is left of it. A real fixed-length file is padded, and a
        // fixture that is not would not exercise the stripping.
        var file = """
                OR1001Alice     \s
                LI1001widget  005
                LI1001sprocket002
                OR1002Bob       \s
                LI1002flange  001
                """;

        var orders = adapter(spec, Map.of()).parse(in(file), "orders", Set.of("id", "cust"))
                .rows().toList();
        var lines = adapter(spec, Map.of()).parse(in(file), "lines", Set.of("order", "sku", "qty"))
                .rows().toList();

        assertAll(
                () -> assertEquals(2, orders.size(), "two OR lines"),
                () -> assertEquals("1001", orders.getFirst().get("id")),
                () -> assertEquals("Alice", orders.getFirst().get("cust")),
                () -> assertEquals("Bob", orders.get(1).get("cust"), "padding is stripped from the value"),
                () -> assertEquals(3, lines.size(), "three LI lines"),
                () -> assertEquals("widget", lines.getFirst().get("sku")),
                () -> assertEquals(5L, lines.getFirst().get("qty")),
                () -> assertEquals("flange", lines.get(2).get("sku")));
    }

    /**
     * The regression test for what refusing a second record selector used to hide.
     * <p>
     * A field may omit its left bound and continue where the previous one ended,
     * which makes a layout a running total. When the two record selectors shared
     * one map that total ran <em>across</em> them, so here {@code b}'s {@code ":4"}
     * would continue from {@code a}'s {@code 0:4} and read characters 4 to 4 -
     * nothing - instead of 0 to 4. Both layouts start from zero now, and this test
     * is the only thing that says so.
     */
    @Test
    public void theRunningLeftBoundDoesNotCrossRecordSelectors() throws IOException {
        var spec = new InputSpec(MIME, List.of(
                new RecordSelectorSpec("first",
                        new Locator.Where(new Discriminator.Equals(new Selector.Text("0:1"), "A")),
                        List.of(new FieldSelectorSpec("f", "0:4", DataType.TEXT))),
                new RecordSelectorSpec("second",
                        new Locator.Where(new Discriminator.Equals(new Selector.Text("0:1"), "B")),
                        // omits its left bound, and must begin at 0 rather than at 4
                        List.of(new FieldSelectorSpec("g", ":4", DataType.TEXT)))
        ), List.of(), Map.of());

        var row = adapter(spec, Map.of()).parse(in("Axxx\nByyy\n"), "second", Set.of("g"))
                .rows().toList().getFirst();
        assertEquals("Byyy", row.get("g"), "the second layout starts at 0, not where the first ended");
    }

    /**
     * A record selector with no discriminator still takes every record, which is
     * the single-record-type file every earlier test in this class uses.
     */
    @Test
    public void noDiscriminatorTakesEveryRecord() throws IOException {
        var rows = adapter(spec(new FieldSelectorSpec("id", "0:3", DataType.TEXT)), Map.of())
                .parse(in("001\n002\n003\n"), "rec", Set.of("id"))
                .rows().toList();
        assertEquals(List.of("001", "002", "003"), rows.stream().map(r -> r.get("id")).toList());
    }

    /**
     * A record too short to hold the discriminating range belongs to no record
     * selector, rather than to all of them or to the first.
     * <p>
     * {@code Bounds.of} answers null where the record stops short, and both
     * discriminators refuse a null - a record that could not be asked is not one
     * that answered. That is the only sensible reading, and it had nothing behind
     * it: a truncated line is the commonest thing wrong with a real flat file, and
     * a short line silently joining whichever kind was declared first would put
     * one file's rows in another file's table.
     */
    @Test
    public void arecordTooShortToDiscriminateBelongsToNoKind() throws IOException {
        var spec = new InputSpec(MIME, List.of(
                new RecordSelectorSpec("orders",
                        new Locator.Where(new Discriminator.Equals(new Selector.Text("0:2"), "OR")),
                        List.of(new FieldSelectorSpec("id", "2:6", DataType.TEXT))),
                new RecordSelectorSpec("lines",
                        new Locator.Where(Discriminator.matching(new Selector.Text("0:2"), "LI")),
                        List.of(new FieldSelectorSpec("order", "2:6", DataType.TEXT)))
        ), List.of(), Map.of());

        // the empty second line reaches neither discriminator's range
        var file = "OR1001\n\nLI1001\n";

        var orders = adapter(spec, Map.of()).parse(in(file), "orders", Set.of("id")).rows().toList();
        var lines = adapter(spec, Map.of()).parse(in(file), "lines", Set.of("order")).rows().toList();

        assertAll(
                () -> assertEquals(1, orders.size(), "the short line is not an order"),
                () -> assertEquals(1, lines.size(), "nor a line, though that one matches by pattern"),
                () -> assertEquals("1001", orders.getFirst().get("id")),
                () -> assertEquals("1001", lines.getFirst().get("order")));
    }

    /**
     * A discriminator counts nothing here, for the reason a field selector does
     * not: a fixed-length record has offsets and no components.
     */
    @Test
    public void rejectsAcountingDiscriminator() {
        var spec = new InputSpec(MIME, List.of(new RecordSelectorSpec("rec",
                new Locator.Where(new Discriminator.Equals(new Selector.Nth(1), "A")),
                List.of(new FieldSelectorSpec("id", "0:3", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no components to count"), thrown.getMessage()),
                // and says what to write instead
                () -> assertTrue(thrown.getMessage().contains("0:2"), thrown.getMessage()));
    }

    /**
     * And its range says both bounds. A field may omit the left one and continue
     * from the field before; a discriminator has no field before it.
     */
    @Test
    public void rejectsAdiscriminatorRangeWithoutAleftBound() {
        var spec = new InputSpec(MIME, List.of(new RecordSelectorSpec("rec",
                new Locator.Where(new Discriminator.Equals(new Selector.Text(":2"), "A")),
                List.of(new FieldSelectorSpec("id", "0:3", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertTrue(thrown.getMessage().contains("no previous field"), thrown.getMessage());
    }

    /**
     * Two record selectors of the same name, which a mapping could not choose
     * between.
     */
    @Test
    public void rejectsTwoRecordSelectorsOfOneName() {
        var spec = new InputSpec(MIME, List.of(
                new RecordSelectorSpec("rec",
                        Locator.every(), List.of(new FieldSelectorSpec("a", "0:3", DataType.TEXT))),
                new RecordSelectorSpec("rec",
                        Locator.every(), List.of(new FieldSelectorSpec("b", "3:6", DataType.TEXT)))
        ), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertTrue(thrown.getMessage().contains("could not say which"), thrown.getMessage());
    }

    /**
     * A name the spec does not declare is refused, as it is by every other
     * adapter. This one ignored the argument altogether and read the same fields
     * whatever it was handed, so a mapping naming a record selector that did not
     * exist loaded the whole file as though it did - and nothing else looks: no
     * one checks a mapping's record selector against the input's.
     */
    @Test
    public void rejectsAnUndeclaredRecordSelector() {
        var adapter = adapter(spec(new FieldSelectorSpec("id", "0:3", DataType.TEXT)), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.parse(in("001\n"), "nope", Set.of("id")));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("rec"), thrown.getMessage()));
    }

    /**
     * And a field the record selector does not declare. This used to reach
     * {@code FLRow.get}, where a map lookup returned null and the next line
     * dereferenced it - so the answer to "what is column {@code qty}?" was a
     * NullPointerException from inside a stream, rather than a sentence naming
     * the field.
     */
    @Test
    public void rejectsAnUndeclaredField() {
        var adapter = adapter(spec(new FieldSelectorSpec("id", "0:3", DataType.TEXT)), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.parse(in("001\n"), "rec", Set.of("id", "qty")).rows().toList());
        assertTrue(thrown.getMessage().contains("qty"), thrown.getMessage());
    }
}
