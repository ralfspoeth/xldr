package io.github.ralfspoeth.xldr.xml;

import io.github.ralfspoeth.xldr.ia.Field;
import io.github.ralfspoeth.xldr.ia.InputAdapter;
import io.github.ralfspoeth.xldr.ia.InputAdapterFactory;
import io.github.ralfspoeth.xldr.spec.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class XmlAdapterTest {

    /**
     * {@link InputAdapterFactory#of} rather than {@link java.util.ServiceLoader}
     * directly. These tests are patched into the module they test, and a module
     * may only load a service it declares {@code uses} for - which {@code xml}
     * does not, being a provider. {@code of} works anyway: its lookup runs in
     * {@code ia}, whose descriptor carries the {@code uses}.
     */
    private static InputAdapterFactory factory(InputSpec spec) {
        return InputAdapterFactory.of(spec)
                .orElseThrow(() -> new IllegalStateException("no adapter for " + spec.mimeType()));
    }

    /**
     * The adapter's settings are part of the input spec, so they are added to a
     * copy of it rather than set on the factory.
     * A discriminator is refused by name, where before it was refused as a
     * missing selector - the author told about what they left out rather than
     * what they wrote.
     */
    @Test
    void rejectsAdiscriminator() {
        var spec = new InputSpec("text/xml", List.of(new RecordSelectorSpec("rec",
                Locator.where(new Discriminator.Equals(Selector.nth(1), "O")),
                List.of(new FieldSelectorSpec("id", "@id", DataType.TEXT)))), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("rec"), thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("records to point at"), thrown.getMessage()));
    }

    private static InputAdapter adapter(InputSpec spec, String... properties) {
        Map<String, String> props = new LinkedHashMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            props.put(properties[i], properties[i + 1]);
        }
        var configured = new InputSpec(spec.mimeType(),
                spec.recordSelectors(), spec.vars(), props);
        return factory(configured).createInputAdapter(configured);
    }

    /**
     * Field selectors are evaluated against the record node, so a relative
     * expression stays inside the record while an absolute one reaches across
     * the whole document and yields the same value for every row. A literal is
     * a valid selector too, which is how a constant column is written.
     */
    @Test
    void evaluatesRelativeAbsoluteAndConstantSelectors() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", Locator.at("/simple1/row"), List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.INTEGRAL),
                        new FieldSelectorSpec("colb", "col[@name='b']", DataType.INTEGRAL),
                        new FieldSelectorSpec("firstcow", "//cow[1]/sound", DataType.TEXT),
                        new FieldSelectorSpec("lastcow", "/simple1/cow[last()]/sound", DataType.TEXT),
                        new FieldSelectorSpec("source", "'simple1'", DataType.TEXT),
                        new FieldSelectorSpec("missing", "col[@name='zzz']", DataType.TEXT)
                ))),
                List.of(),
                Map.of()
        );
        var wanted = Set.of("cola", "colb", "firstcow", "lastcow", "source", "missing");

        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var result = adapter(spec).parse(Objects.requireNonNull(in), "row", wanted);

            // fields keep the order of the spec, not of the requested set
            assertEquals(
                    List.of("cola", "colb", "firstcow", "lastcow", "source", "missing"),
                    result.fields().stream().map(Field::name).toList());
            assertEquals(Long.class, result.fields().getFirst().type());

            var rows = result.rows().toList();
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals(25L, rows.get(0).get("cola")),
                    () -> assertEquals(33L, rows.get(0).get("colb")),
                    () -> assertEquals(181L, rows.get(1).get("cola")),
                    () -> assertEquals(77L, rows.get(1).get("colb")),
                    // absolute: same for every record
                    () -> assertEquals("Mooo", rows.get(0).get("firstcow")),
                    () -> assertEquals("Mooo", rows.get(1).get("firstcow")),
                    () -> assertEquals("Meow", rows.get(0).get("lastcow")),
                    () -> assertEquals("simple1", rows.get(0).get("source")),
                    // a string field cannot tell "absent" from "empty"
                    () -> assertEquals("", rows.get(0).get("missing")),
                    // a name with no selector at all
                    () -> assertNull(rows.get(0).get("nosuchfield"))
            );
        }
    }

    /**
     * A field may count instead of naming, and for an element the n-th component
     * is its n-th child element. XPath spells that {@code *[n]} and its predicate
     * counts from one exactly as {@code nth} does, so the translation is the
     * identity and the two agree by construction rather than by arithmetic.
     * <p>
     * Nobody with an XPath to hand would write this; it exists because
     * {@code nth} means the same thing in every format, and an exception here
     * would be one more thing to remember.
     */
    @Test
    void countsChildElementsWhenAFieldSaysNth() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", Locator.at("/simple1/row"), List.of(
                        new FieldSelectorSpec("first", Selector.nth(1), DataType.INTEGRAL),
                        new FieldSelectorSpec("second", Selector.nth(2), DataType.INTEGRAL),
                        // past the end of the children: absent, as a short line is
                        new FieldSelectorSpec("third", Selector.nth(3), DataType.TEXT)
                ))),
                List.of(), Map.of());

        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var rows = adapter(spec).parse(in, "row", Set.of("first", "second", "third"))
                    .rows().toList();

            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals(25L, rows.getFirst().get("first")),
                    () -> assertEquals(33L, rows.getFirst().get("second")),
                    () -> assertEquals(181L, rows.get(1).get("first")),
                    () -> assertEquals(77L, rows.get(1).get("second")),
                    // a row has two children, so the third is nothing at all
                    () -> assertEquals("", rows.getFirst().get("third")));
        }
    }

    /**
     * A record selector that matches nothing yields no rows - not one row of
     * nulls.
     */
    @Test
    void yieldsNoRowsWhenNothingMatches() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("none", Locator.at("/simple1/nothing"), List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.TEXT)
                ))
        ), List.of(), Map.of());
        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var result = adapter(spec).parse(in, "none", Set.of("cola"));
            assertEquals(List.of(), result.rows().toList());
        }
    }

    /**
     * XPath 1.0 has no default namespace, so a document that declares one can
     * only be addressed through a bound prefix.
     */
    @Test
    void resolvesNamespacePrefixes() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("fund", Locator.at("/f:portfolio/f:fund"), List.of(
                        new FieldSelectorSpec("id", "@id", DataType.TEXT),
                        new FieldSelectorSpec("name", "f:name", DataType.TEXT),
                        new FieldSelectorSpec("nav", "f:nav", DataType.DECIMAL),
                        new FieldSelectorSpec("asOf", "f:asOf", DataType.TEMPORAL),
                        new FieldSelectorSpec("version", "/f:portfolio/@version", DataType.INTEGRAL)
                ))
        ), List.of(), Map.of());
        var wanted = Set.of("id", "name", "nav", "asOf", "version");

        try (var in = getClass().getResourceAsStream("funds.xml")) {
            var result = adapter(spec, "ns.f", "http://example.com/funds").parse(in, "fund", wanted);
            var rows = result.rows().toList();
            assertEquals(2, rows.size());
            assertAll(
                    () -> assertEquals("F-1", rows.get(0).get("id")),
                    () -> assertEquals("Alpha", rows.get(0).get("name")),
                    // exact, not a binary approximation of 1234.56
                    () -> assertEquals(new BigDecimal("1234.56"), rows.get(0).get("nav")),
                    // a plain date is accepted as well as a full timestamp
                    () -> assertEquals(LocalDateTime.parse("2026-07-22T00:00"), rows.get(0).get("asOf")),
                    () -> assertEquals(LocalDateTime.parse("2026-07-21T14:30"), rows.get(1).get("asOf")),
                    () -> assertEquals(2L, rows.get(0).get("version"))
            );
        }
    }

    /**
     * Without the binding the same selectors match nothing at all - the failure
     * mode worth knowing about when a spec "silently returns no rows".
     */
    @Test
    void findsNothingWithoutTheNamespaceBinding() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("fund", Locator.at("/portfolio/fund"), List.of(
                        new FieldSelectorSpec("id", "@id", DataType.TEXT)
                ))
        ), List.of(), Map.of());
        try (var in = getClass().getResourceAsStream("funds.xml")) {
            assertEquals(List.of(), adapter(spec).parse(in, "fund", Set.of("id")).rows().toList());
        }
    }

    /**
     * A broken selector is reported when the adapter is built, not part way
     * through a load.
     */
    @Test
    void rejectsAmalformedExpressionOnCreation() {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", Locator.at("/simple1/row["), List.of())
        ), List.of(), Map.of());
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec));
        assertTrue(thrown.getMessage().contains("/simple1/row["), thrown.getMessage());
    }

    /**
     * A mapping referring to a field the input spec does not declare is a broken
     * spec and says so.
     */
    @Test
    void rejectsAnUndeclaredFieldSelector() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", Locator.at("/simple1/row"), List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.TEXT)
                ))
        ), List.of(), Map.of());
        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var adapter = adapter(spec);
            assertThrows(IllegalArgumentException.class,
                    () -> adapter.parse(in, "row", Set.of("cola", "colz")));
        }
    }
}
