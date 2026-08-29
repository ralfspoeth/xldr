package io.github.ralfspoeth.xldr.csv;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.ia.Row;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

class CsvFileHandlerTest {

    /** comma separated, one record per line */
    private static final Map<String, String> COMMAS =
            Map.of("fieldSeparator", ",");

    /** the same, without a header row */
    private static final Map<String, String> HEADERLESS =
            Map.of("fieldSeparator", ",", "header", "false");

    private static InputSpec spec(Map<String, String> properties, RecordSelectorSpec... recordSelectors) {
        return new InputSpec("text/csv", List.of(recordSelectors), List.of(), properties);
    }

    // input spec mentions only id and name; the file also carries short-name/long-name.
    // no discriminator (selector null): a single-record-type file takes every line.
    private static final InputSpec SPEC = spec(COMMAS,
            new RecordSelectorSpec("people", Locator.every(), List.of(
                    new FieldSelectorSpec("id", "id", DataType.TEXT),
                    new FieldSelectorSpec("name", "name", DataType.TEXT)
            ))
    );

    // the same, plus a free-text column - the one that carries separators,
    // line breaks and quotes in a real feed
    private static final InputSpec SPEC_WITH_NOTE = spec(COMMAS,
            new RecordSelectorSpec("people", Locator.every(), List.of(
                    new FieldSelectorSpec("id", "id", DataType.TEXT),
                    new FieldSelectorSpec("name", "name", DataType.TEXT),
                    new FieldSelectorSpec("note", "note", DataType.TEXT)
            ))
    );

    /** the n-th field of the line, which is the only way to address a headerless file */
    private static FieldSelectorSpec at(int column) {
        return new FieldSelectorSpec(String.valueOf(column), Selector.nth(column), DataType.TEXT);
    }

    /** the first column holds this, which is how the record types tell themselves apart */
    private static Discriminator firstColumnIs(String literal) {
        return new Discriminator.Equals(Selector.nth(1), literal);
    }

    // no header: fields are addressed by column, and named after it here only
    // because a name has to be something
    private static final InputSpec POSITIONAL_SPEC = spec(HEADERLESS,
            new RecordSelectorSpec("people", Locator.every(), List.of(at(1), at(2), at(3)))
    );

    // one headerless file, two interleaved record types keyed by the first
    // column. Positions stay absolute, so column 1 is the discriminator itself
    // and the fields start at 2.
    private static final InputSpec DISCRIMINATED_SPEC = spec(HEADERLESS,
            new RecordSelectorSpec("orders", Locator.where(firstColumnIs("O")), List.of(
                    at(2),   // order id
                    at(3),   // date
                    at(4)    // customer
            )),
            new RecordSelectorSpec("lines", Locator.where(firstColumnIs("L")), List.of(
                    at(2),   // order id
                    at(3),   // product
                    at(4),   // qty
                    at(5)    // price
            ))
    );

    /**
     * {@link InputAdapterFactory#of} rather than {@link java.util.ServiceLoader}
     * directly, which matters now that these tests are patched into the module
     * they test.
     * <p>
     * A module may only load a service it declares {@code uses} for, and
     * {@code csv} does not - nor should it, being a provider rather than a
     * consumer. {@code of} works anyway because the {@code ServiceLoader} call
     * lives in {@code ia}, whose descriptor carries the {@code uses} for exactly
     * this reason. So the test still finds its adapter the way the loader does,
     * from a module that could not have looked for one itself.
     */
    private static InputAdapter adapterFor(InputSpec spec) {
        return InputAdapterFactory.of(spec)
                .orElseThrow(() -> new IllegalStateException("no adapter for " + spec.mimeType()))
                .createInputAdapter(spec);
    }

    private InputAdapter adapter() {
        return adapterFor(SPEC);
    }

    private InputAdapter positionalAdapter() {
        return adapterFor(POSITIONAL_SPEC);
    }

    private InputAdapter discriminatedAdapter() {
        return adapterFor(DISCRIMINATED_SPEC);
    }

    @Test
    void parsesSelectedFields() throws IOException {
        try (var in = getClass().getResourceAsStream("simple.csv")) {
            var result = adapter().parse(requireNonNull(in), "people", Set.of("id", "name"));

            // only id and name are exposed as fields
            assertEquals(
                    List.of("id", "name"),
                    result.fields().stream().map(Field::name).toList()
            );

            var rows = result.rows().toList();
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals("1", rows.getFirst().get("id")),
                    () -> assertEquals("Alice", rows.getFirst().get("name")),
                    () -> assertEquals("2", rows.get(1).get("id")),
                    () -> assertEquals("Bob", rows.get(1).get("name"))
            );
        }
    }

    @Test
    void parsesHeaderlessWithRaggedLines() throws IOException {
        try (var in = getClass().getResourceAsStream("positional.csv")) {
            var result = positionalAdapter().parse(requireNonNull(in), "people", Set.of("1", "2", "3"));

            // fields keep the spec order: positions 1, 2, 3
            assertEquals(
                    List.of("1", "2", "3"),
                    result.fields().stream().map(Field::name).toList()
            );

            var rows = result.rows().toList();
            // no header line is consumed -> all 10 lines are records
            assertEquals(10, rows.size());

            assertAll(
                    // fully populated line
                    () -> assertEquals("1", rows.getFirst().get("1")),
                    () -> assertEquals("Alice", rows.getFirst().get("2")),
                    () -> assertEquals("Berlin", rows.getFirst().get("3")),
                    // extra column beyond the spec is simply ignored
                    () -> assertEquals("Bob", rows.get(1).get("2")),
                    () -> assertEquals("Hamburg", rows.get(1).get("3")),
                    // incomplete line: missing column 3 -> null
                    () -> assertEquals("Carol", rows.get(2).get("2")),
                    () -> assertNull(rows.get(2).get("3")),
                    // present but empty column 3 -> null as well: a blank value is
                    // absent, whether the column is missing or merely empty
                    () -> assertNull(rows.get(4).get("3")),
                    // another incomplete line
                    () -> assertNull(rows.get(6).get("3")),
                    // last line, two-digit position value, missing column 3
                    () -> assertEquals("10", rows.get(9).get("1")),
                    () -> assertEquals("Judy", rows.get(9).get("2")),
                    () -> assertNull(rows.get(9).get("3"))
            );
        }
    }

    /**
     * A record is a line however the file terminates its lines, so a file
     * written on one platform reads on another. With a configurable row
     * separator this was the common way to get silently wrong results: splitting
     * a CRLF file on {@code \n} left a stray return on the last column of every
     * line, and in header mode that column then matched no field at all.
     */
    @Test
    void readsEveryLineEndingTheSameWay() throws IOException {
        for (var terminator : List.of("\n", "\r\n", "\r")) {
            var csv = String.join(terminator, "id,name", "1,Alice", "2,Bob") + terminator;
            var result = adapter().parse(
                    new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                    "people", Set.of("id", "name"));

            var rows = result.rows().toList();
            var terminatorName = terminator.replace("\r", "\\r").replace("\n", "\\n");
            assertAll(
                    () -> assertEquals(2, rows.size(), terminatorName),
                    // the last column of the line: where a stray \r would land
                    () -> assertEquals("Alice", rows.getFirst().get("name"), terminatorName),
                    () -> assertEquals("1", rows.getFirst().get("id"), terminatorName)
            );
        }
    }

    /**
     * A field's declared type governs both the exposed {@link Field} type and
     * the value handed to the loader, so a CSV column can arrive as a number
     * rather than as text.
     */
    @Test
    void convertsAccordingToTheDeclaredType() throws IOException {
        var spec = spec(COMMAS,
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.INTEGRAL),
                        new FieldSelectorSpec("name", "name", DataType.TEXT),
                        new FieldSelectorSpec("rate", "rate", DataType.DECIMAL)
                ))
        );
        var adapter = adapterFor(spec);

        var csv = """
                id,name,rate
                1,Alice, 12.50
                2,Bob,
                """;
        var result = adapter.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "people", Set.of("id", "name", "rate"));

        assertEquals(
                List.of(Long.class, String.class, BigDecimal.class),
                result.fields().stream().map(Field::type).toList());

        var rows = result.rows().toList();
        assertAll(
                () -> assertEquals(1L, rows.getFirst().get("id")),
                () -> assertEquals("Alice", rows.getFirst().get("name")),
                // padded in the file, still a number
                () -> assertEquals(new BigDecimal("12.50"), rows.getFirst().get("rate")),
                () -> assertEquals(2L, rows.get(1).get("id")),
                // an empty column is an absent value
                () -> assertNull(rows.get(1).get("rate"))
        );
    }

    @Test
    void selectsOnlyMatchingRecordType() throws IOException {
        try (var in = getClass().getResourceAsStream("discriminated.csv")) {
            var result = discriminatedAdapter().parse(requireNonNull(in), "orders", Set.of("2", "3", "4"));

            var rows = result.rows().toList();
            // three O-lines only; the L-lines are filtered out
            assertEquals(3, rows.size());
            assertAll(
                    () -> assertEquals("1001", rows.getFirst().get("2")),
                    () -> assertEquals("2026-01-05", rows.getFirst().get("3")),
                    () -> assertEquals("ACME", rows.getFirst().get("4")),
                    () -> assertEquals("1002", rows.get(1).get("2")),
                    () -> assertEquals("GLOBEX", rows.get(1).get("4")),
                    () -> assertEquals("1003", rows.get(2).get("2")),
                    () -> assertEquals("INITECH", rows.get(2).get("4"))
            );
        }
    }

    /**
     * A quoted field may carry the separator and a line break, and a doubled
     * quote inside it is one literal quote. The record then spans as many lines
     * as the field needs, which is what a spreadsheet export looks like.
     */
    @Test
    void readsQuotedFields() throws IOException {
        var csv = """
                id,name,note
                1,"Doe, Alice","she said ""no""!"
                2,"Roe, Bob","first line
                second line"
                3,plain,plain
                """;
        var rows = rowsOf(SPEC_WITH_NOTE, csv, "id", "name", "note");

        assertEquals(3, rows.size());
        assertAll(
                () -> assertEquals("Doe, Alice", rows.getFirst().get("name"), "the separator is data here"),
                () -> assertEquals("she said \"no\"!", rows.getFirst().get("note")),
                () -> assertEquals("Roe, Bob", rows.get(1).get("name")),
                () -> assertEquals("first line\nsecond line", rows.get(1).get("note")),
                () -> assertEquals("plain", rows.get(2).get("name"))
        );
    }

    /**
     * A quote is structural only where a field begins. Anywhere else it is an
     * ordinary character, so a file that carries inch marks or an inline
     * quotation reads as it did before quoting was understood at all.
     */
    @Test
    void aQuoteInsideAFieldIsAnOrdinaryCharacter() throws IOException {
        var csv = """
                id,name,note
                1,5" pipe,he said "no" twice
                """;
        var rows = rowsOf(SPEC_WITH_NOTE, csv, "id", "name", "note");

        assertAll(
                () -> assertEquals("5\" pipe", rows.getFirst().get("name")),
                () -> assertEquals("he said \"no\" twice", rows.getFirst().get("note"))
        );
    }

    /**
     * Setting {@code quote} to nothing switches quoting off, for a feed whose
     * values do start with a quote and mean it literally.
     */
    @Test
    void quotingCanBeSwitchedOff() throws IOException {
        var spec = spec(Map.of("fieldSeparator", ",", "quote", ""),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT)
                )));
        var rows = rowsOf(spec, "id,name\n1,\"quoted\"\n", "id", "name");

        assertEquals("\"quoted\"", rows.getFirst().get("name"));
    }

    /**
     * A stray quote at the start of a field would otherwise swallow the rest of
     * the file into one record and report a load of one row. It is called what
     * it is instead, and the message names the line that opened it.
     */
    @Test
    void reportsAnUnterminatedQuote() {
        var csv = """
                id,name,note
                1,"unclosed,note
                2,Bob,fine
                """;
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(SPEC_WITH_NOTE, csv, "id", "name", "note"));
        // line 2 of the file: the header is line 1, and an author counts from there
        assertTrue(thrown.getMessage().contains("line 2"), thrown.getMessage());
    }

    /**
     * {@code present} and {@code absent} say what {@code true} and {@code false}
     * say, in the words the thing itself is spoken of in. A setting that is
     * none of the four is refused rather than read as {@code false}, which would
     * be a headerless read of a file that has a header.
     */
    @Test
    void headerMaybeSaidToBePresentOrAbsent() throws IOException {
        var withHeader = spec(Map.of("fieldSeparator", ",", "header", "present"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT))));
        var withoutHeader = spec(Map.of("fieldSeparator", ",", "header", "absent"),
                new RecordSelectorSpec("people", Locator.every(), List.of(at(1), at(2))));

        var named = rowsOf(withHeader, "id,name\n1,Alice\n", "id", "name");
        assertEquals("Alice", named.getFirst().get("name"));

        var positional = rowsOf(withoutHeader, "1,Alice\n", "1", "2");
        assertEquals("Alice", positional.getFirst().get("2"));

        var nonsense = spec(Map.of("fieldSeparator", ",", "header", "yes"),
                new RecordSelectorSpec("people", Locator.every(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> adapterFor(nonsense));
    }

    /**
     * An empty line is nothing by default, and the end of the data where the
     * feed says so - the shape of a file that carries a trailer after a blank
     * line.
     */
    @Test
    void anEmptyLineIsSkippedOrStopsTheData() throws IOException {
        var csv = """
                id,name,note
                1,Alice,a

                2,Bob,b
                """;
        assertEquals(2, rowsOf(SPEC_WITH_NOTE, csv, "id", "name", "note").size());

        var stopping = spec(Map.of("fieldSeparator", ",", "emptyLine", "stop"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT),
                        new FieldSelectorSpec("note", "note", DataType.TEXT))));
        var rows = rowsOf(stopping, csv, "id", "name", "note");
        assertEquals(1, rows.size(), "everything after the empty line is a trailer");
        assertEquals("Alice", rows.getFirst().get("name"));
    }

    /**
     * A comment runs from the comment character to the end of the record, but
     * only outside a quoted field - inside one it is data. A line that is
     * nothing but a comment is not a record, and a banner of them is looked past
     * to find the header.
     */
    @Test
    void readsComments() throws IOException {
        var commented = spec(Map.of("fieldSeparator", ",", "comment", "#"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT),
                        new FieldSelectorSpec("note", "note", DataType.TEXT))));
        var csv = """
                # produced 2026-07-28 by the nightly job
                id,name,note
                1,Alice,plain # trailing comment
                # a whole line of comment
                2,Bob,"a # inside quotes is data"
                """;
        var rows = rowsOf(commented, csv, "id", "name", "note");

        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("Alice", rows.getFirst().get("name"), "the banner is not the header"),
                () -> assertEquals("plain", rows.getFirst().get("note"), "the comment is cut, the value stripped"),
                () -> assertEquals("a # inside quotes is data", rows.get(1).get("note"))
        );
    }

    /**
     * A comment character is only a comment character where the feed names one;
     * by default it is data, since a value like an order number may well start
     * with a hash.
     */
    @Test
    void withoutTheSettingAcommentCharacterIsData() throws IOException {
        var rows = rowsOf(SPEC_WITH_NOTE, "id,name,note\n1,Alice,#12345\n", "id", "name", "note");
        assertEquals("#12345", rows.getFirst().get("note"));
    }

    /**
     * A field's {@code name} is what a mapping calls it by; its {@code selector}
     * says which column it is. The two are alike in most specs, which is why
     * this went unnoticed: the adapter used to address columns by name and
     * ignore the selector, so a spec that named its fields anything else - as
     * every other adapter allows - read nothing but nulls.
     */
    @Test
    void aFieldsSelectorNamesTheColumn() throws IOException {
        var spec = spec(Map.of("fieldSeparator", ";"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("n1", "Name", DataType.TEXT),
                        new FieldSelectorSpec("n2", "Text", DataType.TEXT),
                        new FieldSelectorSpec("n3", "Id", DataType.INTEGRAL)
                )));
        var rows = rowsOf(spec, """
                Id;leer;Name;Text
                1;;Hello;asdf
                2;;World;asdf
                """, "n1", "n2", "n3");

        assertEquals(2, rows.size());
        assertAll(
                () -> assertEquals("Hello", rows.getFirst().get("n1")),
                () -> assertEquals("asdf", rows.getFirst().get("n2")),
                () -> assertEquals(1L, rows.getFirst().get("n3")),
                () -> assertEquals("World", rows.get(1).get("n1"))
        );
    }

    /**
     * The same, without a header: the field counts its column, and the name
     * stays the mapping's handle. The two were harder to tell apart when a
     * position was written as text - {@code selector="3"} looked like the other
     * kind of selector and was one only because of a property elsewhere.
     */
    @Test
    void aFieldCountsItsComponentWithoutAheader() throws IOException {
        var spec = spec(Map.of("fieldSeparator", ";", "header", "absent"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("name", Selector.nth(3), DataType.TEXT),
                        new FieldSelectorSpec("id", Selector.nth(1), DataType.INTEGRAL)
                )));
        var rows = rowsOf(spec, "1;;Hello;asdf\n", "name", "id");

        assertAll(
                () -> assertEquals("Hello", rows.getFirst().get("name")),
                () -> assertEquals(1L, rows.getFirst().get("id"))
        );
    }

    /**
     * With {@code fieldsFromHeader} a field the spec does not declare is the
     * column of that name, so a feed whose columns are already named as the
     * mapping wants them declares nothing at all. A declared field still wins -
     * that is how a column is renamed, or given a type.
     */
    @Test
    void takesUndeclaredFieldsFromTheHeader() throws IOException {
        var spec = spec(Map.of("fieldSeparator", ";", "fieldsFromHeader", "true"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("who", "Name", DataType.TEXT)
                )));
        var rows = rowsOf(spec, """
                Id;leer;Name;Text
                1;;Hello;asdf
                """, "who", "Id", "Text");

        assertAll(
                () -> assertEquals("Hello", rows.getFirst().get("who"), "the declared one keeps its column"),
                () -> assertEquals("1", rows.getFirst().get("Id"), "and the header supplies the rest"),
                () -> assertEquals("asdf", rows.getFirst().get("Text")),
                // no type is declared for an implicit field, so it arrives as text
                () -> assertEquals(String.class, requireNonNull(rows.getFirst().get("Id")).getClass())
        );
    }

    /**
     * Without the property nothing changes: an undeclared name is a column the
     * record does not have, and so reads as null - the mapping asked for a name
     * the spec never declared, which without this property is a mistake nobody
     * has claimed is deliberate.
     */
    @Test
    void anUndeclaredFieldIsNothingWithoutTheProperty() throws IOException {
        var spec = spec(Map.of("fieldSeparator", ";"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("who", "Name", DataType.TEXT)
                )));
        var rows = rowsOf(spec, "Id;leer;Name;Text\n1;;Hello;asdf\n", "who", "Id");

        assertAll(
                () -> assertEquals("Hello", rows.getFirst().get("who")),
                () -> assertNull(rows.getFirst().get("Id"))
        );
    }

    /**
     * The header is where the names come from, so a headerless feed asking for
     * this is refused rather than left to resolve nothing.
     */
    @Test
    void fieldsFromHeaderNeedsAheader() {
        var spec = spec(Map.of("fieldSeparator", ";", "header", "absent", "fieldsFromHeader", "true"),
                new RecordSelectorSpec("people", Locator.every(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> adapterFor(spec));
    }

    /**
     * A spec that says nothing beyond {@code text/csv} reads the format the MIME
     * type is registered for: commas, a header, and double quotes around a field
     * that carries one of them. Every other test here names its separator, so
     * this is the only one that would notice the default changing.
     */
    @Test
    void theDefaultsAreTheOnesRfc4180Registers() throws IOException {
        var spec = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT))));
        var rows = rowsOf(spec, """
                id,name
                1,Alice
                2,"Bull, John"
                """, "id", "name");

        assertAll(
                () -> assertEquals(2, rows.size(), "the first line was the header"),
                () -> assertEquals("Alice", rows.getFirst().get("name")),
                () -> assertEquals("Bull, John", rows.get(1).get("name"), "quoted, so the comma is data"));
    }

    /**
     * UTF-8 whatever the JVM was started with. {@code Charset.defaultCharset()}
     * would make the same file load differently under a different
     * {@code -Dfile.encoding}, which is a difference between a deployment and the
     * test that passed - so the two encodings are asserted against each other
     * rather than against the platform, which this test cannot change.
     */
    @Test
    void theDefaultCharsetIsUtf8() throws IOException {
        var csv = "id,name\n1,Müller\n";
        var spec = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT))));

        assertEquals("Müller", first(spec, csv.getBytes(StandardCharsets.UTF_8)).get("name"));
        assertNotEquals("Müller", first(spec, csv.getBytes(StandardCharsets.ISO_8859_1)).get("name"),
                "latin-1 bytes are not what this reads");

        var latin1 = spec(Map.of("charset", "ISO-8859-1"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT))));
        assertEquals("Müller", first(latin1, csv.getBytes(StandardCharsets.ISO_8859_1)).get("name"),
                "and a feed on another encoding still says so");
    }

    /**
     * The refusal that makes the separator's default safe to change. A
     * tab-separated file read with commas has one column, called the whole header
     * line, and every selector resolves to nothing - which used to be a table of
     * nulls and a load reporting success.
     */
    @Test
    void aSelectorNamingNoColumnIsRefused() {
        var spec = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT))));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(spec, "id\tname\n1\tAlice\n", "id", "name"));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("id"), thrown.getMessage()),
                // the tab is shown as an escape, or the message would hide the
                // one character that explains it
                () -> assertTrue(thrown.getMessage().contains("\\t"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("fieldSeparator"), thrown.getMessage()));
    }

    /**
     * The other registered type says three things RFC 4180 leaves open, so a TSV
     * spec carries no properties at all: tabs separate the fields, the first line
     * is the names, and there is no quoting - a TSV field cannot contain a tab, so
     * nothing needs escaping and a double quote is an ordinary character.
     */
    @Test
    void tabSeparatedValuesNeedsNoPropertiesAtAll() throws IOException {
        var spec = new InputSpec("text/tab-separated-values",
                List.of(new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("id", "id", DataType.TEXT),
                        new FieldSelectorSpec("name", "name", DataType.TEXT)))),
                List.of(), Map.of());
        var rows = rowsOf(spec, "id\tname\n1\tAlice\n2\t\"Bull, John\"\n", "id", "name");

        assertAll(
                () -> assertEquals(2, rows.size(), "the first line was the names"),
                () -> assertEquals("Alice", rows.getFirst().get("name")),
                () -> assertEquals("\"Bull, John\"", rows.get(1).get("name"),
                        "no quoting, so the quotes are part of the value"));
    }

    /**
     * A spec may repeat what the type already says - a tab separator for a TSV
     * file is redundant, not wrong - but not contradict it. Obeying the
     * contradiction would mean picking one of two descriptions of the file and
     * calling it right; the message points at the type that does allow it.
     */
    @Test
    void tabSeparatedValuesRefusesAspecThatContradictsIt() {
        assertAll(
                () -> assertEquals("\t", tsvSeparatorOf(Map.of("fieldSeparator", "\t")),
                        "saying what the type says is allowed"),
                () -> refuses(Map.of("fieldSeparator", ";"), "fieldSeparator"),
                () -> refuses(Map.of("quote", "\""), "quote"),
                () -> refuses(Map.of("header", "absent"), "header"));
    }

    private static void refuses(Map<String, String> properties, String setting) {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> tsvSeparatorOf(properties));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains(setting), thrown.getMessage()),
                // and says which type would have accepted it
                () -> assertTrue(thrown.getMessage().contains("text/csv"), thrown.getMessage()));
    }

    /**
     * Builds a TSV adapter and reports the separator it reads with, so that the
     * allowed case is asserted on rather than merely not throwing.
     */
    private static String tsvSeparatorOf(Map<String, String> properties) throws IOException {
        var spec = new InputSpec("text/tab-separated-values",
                List.of(new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("a", "a", DataType.TEXT),
                        new FieldSelectorSpec("b", "b", DataType.TEXT)))),
                List.of(), properties);
        try (var in = new ByteArrayInputStream("a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8))) {
            var row = adapterFor(spec).parse(in, "people", Set.of("a", "b")).rows().toList().getFirst();
            return "1".equals(row.get("a")) && "2".equals(row.get("b")) ? "\t" : "something else";
        }
    }

    /**
     * A column counts, so it works whether or not the file names its columns -
     * which is new. A header that names a column {@code 3} is now sayable and
     * distinct from the third column, and this is the file where that used to be
     * impossible.
     */
    @Test
    void countingWorksEvenWhereTheColumnsHaveNames() {
        var counted = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("second", Selector.nth(2), DataType.TEXT))));
        var named = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("second", "3", DataType.TEXT))));
        var csv = """
                1,2,3
                a,b,c
                """;
        assertAll(
                () -> assertEquals("b", rowsOf(counted, csv, "second").getFirst().get("second"),
                        "the second column"),
                () -> assertEquals("c", rowsOf(named, csv, "second").getFirst().get("second"),
                        "the column named 3"));
    }

    /**
     * Names are something only a header has, so a headerless file cannot be
     * selected from by one. This used to be a {@code parseInt} returning -1,
     * which said nothing about what to do instead.
     */
    @Test
    void aNameNeedsAHeaderToNameSomethingIn() {
        var spec = spec(Map.of("header", "absent"),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("who", "name", DataType.TEXT))));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(spec, "1,Alice\n", "who"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no column names"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("\"nth\""), thrown.getMessage()));
    }

    /**
     * With a header there is something to check a count against, so a column
     * past the end of it is a spec that does not fit its file. Without one there
     * is nothing to check against, and a short line is simply short.
     */
    @Test
    void countingPastTheHeaderIsRefused() {
        var spec = spec(Map.of(),
                new RecordSelectorSpec("people", Locator.every(), List.of(
                        new FieldSelectorSpec("far", Selector.nth(9), DataType.TEXT))));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(spec, "id,name\n1,Alice\n", "far"));
        assertTrue(thrown.getMessage().contains("only 2"), thrown.getMessage());
    }

    /**
     * A headed file may perfectly well carry a type column, and then a
     * discriminator is exactly right rather than almost certainly wrong. This is
     * the file that showed the old first-column-only rule was too narrow.
     */
    @Test
    void aDiscriminatorMayNameItsColumnInAHeadedFile() throws IOException {
        var spec = spec(Map.of(),
                new RecordSelectorSpec("ones", Locator.where(new Discriminator.Equals(Selector.text("A"), "1")),
                        List.of(new FieldSelectorSpec("b", "B", DataType.TEXT))));
        var rows = rowsOf(spec, """
                A,B,C
                1,one,One
                1,two,Two
                2,one,Two
                2,two,One
                """, "ones", Set.of("b"));

        assertAll(
                () -> assertEquals(2, rows.size(), "only the rows whose A is 1"),
                () -> assertEquals("one", rows.getFirst().get("b")),
                () -> assertEquals("two", rows.get(1).get("b")));
    }

    /**
     * And it may match rather than equal, which is what a family of record types
     * sharing a prefix needs.
     */
    @Test
    void aDiscriminatorMayMatchAPattern() throws IOException {
        var spec = spec(Map.of("header", "absent"),
                new RecordSelectorSpec("orders",
                        Locator.where(Discriminator.matching(Selector.nth(1), "O[0-9]+")),
                        List.of(at(2))));
        var rows = rowsOf(spec, """
                O1,first
                L1,a line
                O2,second
                OX,not a number
                """, "orders", Set.of("2"));

        assertAll(
                () -> assertEquals(2, rows.size()),
                () -> assertEquals("first", rows.getFirst().get("2")),
                () -> assertEquals("second", rows.get(1).get("2")));
    }

    /**
     * The migration tripwire. A spec written before the two parted says
     * {@code "selector": "O"} where it now means a discriminator; ignoring it
     * would leave every line matching every record selector, and a load reporting
     * a great many rows.
     */
    @Test
    void aFlatRecordSelectorWithASelectorIsRefused() {
        var spec = spec(Map.of("header", "absent"),
                new RecordSelectorSpec("orders", Locator.at("O"), List.of(at(2))));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(spec, "O,first\n", "orders", Set.of("2")));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no place to point at"), thrown.getMessage()),
                // and says what to write instead
                () -> assertTrue(thrown.getMessage().contains("discriminator"), thrown.getMessage()));
    }

    /**
     * A name the spec does not declare is refused here as it is by xlsx, xml and
     * json. It used to answer with no rows, which is indistinguishable from a file
     * that held none - so a mapping with a typo in its {@code recordSelector} was
     * a green load of nothing on a CSV feed and a refusal on any other input. What
     * makes it worth refusing rather than tolerating is that nothing else looks:
     * no one checks a mapping's record selector against the ones the input
     * declares, so this call is where the two names first meet.
     */
    @Test
    void anUndeclaredRecordSelectorIsRefused() {
        var spec = spec(Map.of(), new RecordSelectorSpec("people", Locator.every(),
                List.of(new FieldSelectorSpec("id", "id", DataType.TEXT))));
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> rowsOf(spec, "id\n1\n", "peple", Set.of("id")));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("peple"), thrown.getMessage()),
                // and what the spec does declare, the typo being the likely cause
                () -> assertTrue(thrown.getMessage().contains("people"), thrown.getMessage()));
    }

    private static Row first(InputSpec spec, byte[] csv) throws IOException {
        try (var in = new ByteArrayInputStream(csv)) {
            return adapterFor(spec).parse(in, "people", Set.of("id", "name")).rows().toList().getFirst();
        }
    }

    /** the common case: one record selector, and it is called people */
    private static List<Row> rowsOf(InputSpec spec, String csv, String... fields) throws IOException {
        return rowsOf(spec, csv, "people", Set.of(fields));
    }

    private static List<Row> rowsOf(InputSpec spec, String csv, String recordSelector, Set<String> fields)
            throws IOException {
        try (var in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))) {
            return adapterFor(spec).parse(in, recordSelector, fields).rows().toList();
        }
    }

    @Test
    void sameFileYieldsDifferentRecordType() throws IOException {
        try (var in = getClass().getResourceAsStream("discriminated.csv")) {
            var result = discriminatedAdapter().parse(requireNonNull(in), "lines", Set.of("2", "3", "4", "5"));

            var rows = result.rows().toList();
            // five L-lines only, with their own column layout
            assertEquals(5, rows.size());
            assertAll(
                    () -> assertEquals("widget", rows.getFirst().get("3")),
                    () -> assertEquals("5", rows.getFirst().get("4")),
                    () -> assertEquals("9.99", rows.getFirst().get("5")),
                    () -> assertEquals("sprocket", rows.get(2).get("3")),
                    () -> assertEquals("flange", rows.get(4).get("3")),
                    () -> assertEquals("42.00", rows.get(4).get("5"))
            );
        }
    }
}
