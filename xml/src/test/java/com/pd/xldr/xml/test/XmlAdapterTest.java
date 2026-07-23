package com.pd.xldr.xml.test;

import com.pd.xldr.ia.Field;
import com.pd.xldr.ia.InputAdapter;
import com.pd.xldr.ia.InputAdapterFactory;
import com.pd.xldr.spec.DataType;
import com.pd.xldr.spec.FieldSelectorSpec;
import com.pd.xldr.spec.InputSpec;
import com.pd.xldr.spec.RecordSelectorSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlAdapterTest {

    private static InputAdapterFactory factory(InputSpec spec) {
        return ServiceLoader.load(InputAdapterFactory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(iaf -> iaf.accepts(spec))
                .findFirst()
                .orElseThrow();
    }

    private static InputAdapter adapter(InputSpec spec, String... properties) {
        var factory = factory(spec);
        for (int i = 0; i < properties.length; i += 2) {
            factory.setProperty(properties[i], properties[i + 1]);
        }
        return factory.createInputAdapter(spec);
    }

    /**
     * Field selectors are evaluated against the record node, so a relative
     * expression stays inside the record while an absolute one reaches across
     * the whole document and yields the same value for every row. A literal is
     * a valid selector too, which is how a constant column is written.
     */
    @Test
    public void evaluatesRelativeAbsoluteAndConstantSelectors() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", "/simple1/row", List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.INTEGER),
                        new FieldSelectorSpec("colb", "col[@name='b']", DataType.INTEGER),
                        new FieldSelectorSpec("firstcow", "//cow[1]/sound", DataType.STRING),
                        new FieldSelectorSpec("lastcow", "/simple1/cow[last()]/sound", DataType.STRING),
                        new FieldSelectorSpec("source", "'simple1'", DataType.STRING),
                        new FieldSelectorSpec("missing", "col[@name='zzz']", DataType.STRING)
                ))
        ));
        var wanted = Set.of("cola", "colb", "firstcow", "lastcow", "source", "missing");

        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var result = adapter(spec).parse(in, "row", wanted);

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
     * A record selector that matches nothing yields no rows - not one row of
     * nulls.
     */
    @Test
    public void yieldsNoRowsWhenNothingMatches() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("none", "/simple1/nothing", List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.STRING)
                ))
        ));
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
    public void resolvesNamespacePrefixes() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("fund", "/f:portfolio/f:fund", List.of(
                        new FieldSelectorSpec("id", "@id", DataType.STRING),
                        new FieldSelectorSpec("name", "f:name", DataType.STRING),
                        new FieldSelectorSpec("nav", "f:nav", DataType.DECIMAL),
                        new FieldSelectorSpec("asOf", "f:asOf", DataType.DATE),
                        new FieldSelectorSpec("version", "/f:portfolio/@version", DataType.INTEGER)
                ))
        ));
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
    public void findsNothingWithoutTheNamespaceBinding() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("fund", "/portfolio/fund", List.of(
                        new FieldSelectorSpec("id", "@id", DataType.STRING)
                ))
        ));
        try (var in = getClass().getResourceAsStream("funds.xml")) {
            assertEquals(List.of(), adapter(spec).parse(in, "fund", Set.of("id")).rows().toList());
        }
    }

    /**
     * A broken selector is reported when the adapter is built, not part way
     * through a load.
     */
    @Test
    public void rejectsAmalformedExpressionOnCreation() {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", "/simple1/row[", List.of())
        ));
        var thrown = assertThrows(IllegalArgumentException.class, () -> adapter(spec));
        assertTrue(thrown.getMessage().contains("/simple1/row["), thrown.getMessage());
    }

    /**
     * A mapping referring to a field the input spec does not declare is a broken
     * spec and says so.
     */
    @Test
    public void rejectsAnUndeclaredFieldSelector() throws IOException {
        var spec = new InputSpec("text/xml", List.of(
                new RecordSelectorSpec("row", "/simple1/row", List.of(
                        new FieldSelectorSpec("cola", "col[@name='a']", DataType.STRING)
                ))
        ));
        try (var in = getClass().getResourceAsStream("simple1.xml")) {
            var adapter = adapter(spec);
            assertThrows(IllegalArgumentException.class,
                    () -> adapter.parse(in, "row", Set.of("cola", "colz")));
        }
    }
}
