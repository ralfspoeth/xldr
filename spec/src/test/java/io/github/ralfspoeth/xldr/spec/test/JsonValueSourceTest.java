package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.FieldMappingSpec;
import io.github.ralfspoeth.xldr.spec.ValueSource;
import io.github.ralfspoeth.xldr.spec.VarSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.github.ralfspoeth.xldr.spec.test.Streams.stream;
import static org.junit.jupiter.api.Assertions.*;

public class JsonValueSourceTest {

    /**
     * A field mapping is one of fieldSelector / constant / var; a JSON constant
     * keeps its literal type (string, number as BigDecimal, boolean); and a
     * record mapping's limit is read.
     */
    @Test
    public void readsSourcesAndLimit() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "limit": 100,
                            "fieldMapping": [
                                { "fieldSelector": "id", "column": "id" },
                                { "constant": "PD",      "column": "src" },
                                { "constant": 42,        "column": "n" },
                                { "constant": true,      "column": "flag" }
                            ]
                        }
                    ]
                }
                """;

        var spec = new JsonMappingSpecReader().read(stream(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();

        assertEquals(100, mapping.limit());
        assertEquals(
                List.of(
                        new FieldMappingSpec("id", new ValueSource.Field("id")),
                        new FieldMappingSpec("src", new ValueSource.Constant("PD")),
                        new FieldMappingSpec("n", new ValueSource.Constant(new BigDecimal("42"))),
                        new FieldMappingSpec("flag", new ValueSource.Constant(Boolean.TRUE))
                ),
                mapping.fieldMappings());
    }

    /**
     * A lookup carries the reference table, the returned column, the key column,
     * and a key that is itself a basic source.
     */
    @Test
    public void readsAlookup() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                {
                                    "lookup": {
                                        "table": "country",
                                        "column": "id",
                                        "keyColumn": "iso",
                                        "fieldSelector": "c"
                                    },
                                    "column": "country_id"
                                }
                            ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        assertEquals(
                List.of(new FieldMappingSpec(
                        "country_id", new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c"))
                )),
                mapping.fieldMappings());
    }

    @Test
    public void defaultsLimitToNull() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));
        assertNull(List.copyOf(spec.recordMappingSpecs()).getFirst().limit());
    }

    @Test
    public void rejectsTwoSourcesInOneMapping() {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                { "fieldSelector": "id", "constant": 1, "column": "id" }
                            ]
                        }
                    ]
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().read(stream(source)));
    }

    /**
     * An {@code expr} source is read as a template, both as a var and as a field
     * mapping.
     */
    @Test
    public void readsAnExpression() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv",
                        "vars": [ { "name": "gid", "expr": "${xldr.filename}-${nextval('b')}" } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t",
                          "fieldMapping": [ { "expr": "${now()}", "column": "loaded_at" } ] }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().read(stream(source));

        assertEquals(
                List.of(new VarSpec("gid", new ValueSource.Expr("${xldr.filename}-${nextval('b')}"))),
                List.copyOf(spec.inputSpec().vars()));

        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        var fm = List.copyOf(mapping.fieldMappings()).getFirst();
        assertEquals(new ValueSource.Expr("${now()}"), fm.source());
    }

    /**
     * Members the reader does not consume - arbitrary annotations and the
     * reserved {@code load} - are ignored at every level, so an annotated spec
     * parses to the same result as a bare one.
     */
    @Test
    public void ignoresUnknownAndReservedMembers() throws IOException {
        var bare = """
                {
                    "input": { "mimeType": "text/csv",
                        "recordSelectors": [ { "name": "r", "selector": "//r",
                            "fieldSelectors": [ { "name": "id", "selector": "@id" } ] } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t",
                          "fieldMapping": [ { "fieldSelector": "id", "column": "id" } ] }
                    ]
                }
                """;
        var annotated = """
                {
                    "comments": "top-level note",
                    "load": { "commitPolicy": "ON_CLOSE" },
                    "input": { "mimeType": "text/csv", "note": "why this feed exists",
                        "recordSelectors": [ { "name": "r", "selector": "//r", "x": 1,
                            "fieldSelectors": [ { "name": "id", "selector": "@id", "unit": "n/a" } ] } ] },
                    "mapping": [
                        { "recordSelector": "r", "table": "t", "todo": "verify",
                          "fieldMapping": [ { "fieldSelector": "id", "column": "id", "comment": "pk" } ] }
                    ]
                }
                """;
        var reader = new JsonMappingSpecReader();
        assertEquals(
                reader.read(stream(bare)),
                reader.read(stream(annotated)));
    }

    /**
     * The settings of the adapter are grouped in {@code properties}, since which
     * of them mean anything depends on the MIME type. A scalar keeps its text,
     * so a number or a boolean may be written as one.
     */
    @Test
    public void readsAdapterSettingsFromTheInput() throws IOException {
        var source = """
                {
                    "input": {
                        "mimeType": "text/csv",
                        "comments": "an annotation, not a setting",
                        "properties": {
                            "fieldSeparator": ";",
                            "header": false,
                            "linesPerRecord": 2,
                            "ns.f": "https://example.com/funds"
                        },
                        "recordSelectors": []
                    },
                    "mapping": []
                }
                """;
        var input = new JsonMappingSpecReader().read(stream(source)).inputSpec();

        assertEquals(
                Map.of("fieldSeparator", ";", "header", "false",
                        "linesPerRecord", "2", "ns.f", "https://example.com/funds"),
                input.properties());
        assertEquals("text/csv", input.mimeType());
    }


    /**
     * A JSON null is a constant like any other and loads a SQL NULL. It has to
     * count as a source, or a field mapping carrying only it would be rejected
     * for having none - which is how a missing member and a null one differ.
     */
    @Test
    public void readsANullConstant() throws IOException {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "table": "t",
                            "fieldMapping": [
                                { "constant": null, "column": "note" },
                                { "constant": "PD", "column": "src" }
                            ]
                        }
                    ]
                }
                """;
        var mapping = List.copyOf(
                new JsonMappingSpecReader().read(stream(source)).recordMappingSpecs()).getFirst();

        assertEquals(
                List.of(
                        new FieldMappingSpec("note", new ValueSource.Constant(null)),
                        new FieldMappingSpec("src", new ValueSource.Constant("PD"))),
                mapping.fieldMappings());
    }

    /**
     * A record selector may omit its selector: a CSV with a header holds one
     * kind of record, so there is nothing to locate, and the CSV adapter reads
     * an absent selector as "every line". An adapter that cannot do without one
     * says so when it is asked for it.
     */
    @Test
    public void readsARecordSelectorWithoutASelector() throws IOException {
        var source = """
                {
                    "input": {
                        "mimeType": "text/csv",
                        "recordSelectors": [
                            { "name": "people", "fieldSelectors": [ {"name": "id", "selector": "id"} ] }
                        ]
                    },
                    "mapping": []
                }
                """;
        var input = new JsonMappingSpecReader().read(stream(source)).inputSpec();
        var recordSelector = List.copyOf(input.recordSelectors()).getFirst();

        assertNull(recordSelector.selector());
        assertThrows(IllegalArgumentException.class, recordSelector::requireSelector);
    }
    }
