package io.github.ralfspoeth.xldr.flt.test;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.DataType;
import io.github.ralfspoeth.xldr.spec.FieldSelectorSpec;
import io.github.ralfspoeth.xldr.spec.InputSpec;
import io.github.ralfspoeth.xldr.spec.RecordSelectorSpec;
import io.github.ralfspoeth.xldr.spec.Selector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public class FixedLengthAdapterTest {

    private static final String MIME = "text/plain";

    private static InputSpec spec(FieldSelectorSpec... fields) {
        return new InputSpec(MIME, List.of(new RecordSelectorSpec("rec", "rec", List.of(fields))), List.of(), Map.of());
    }

    /**
     * The adapter's settings are part of the input spec, so they are added to a
     * copy of it rather than set on the factory.
     */
    private static InputAdapter adapter(InputSpec spec, Map<String, String> properties) {
        var configured = new InputSpec(spec.mimeType(),
                spec.recordSelectors(), spec.vars(), properties);
        return ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f.reads(configured))
                .findFirst()
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
        var spec = spec(new FieldSelectorSpec("id", new Selector.Column(1), DataType.TEXT));
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec, Map.of()));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("character range"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("'id'"), thrown.getMessage()));
    }
}
