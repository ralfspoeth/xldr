package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.ValueSource;
import io.github.ralfspoeth.xldr.spec.FieldMappingSpec;
import io.github.ralfspoeth.xldr.spec.VarSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                            "databaseTable": "t",
                            "limit": 100,
                            "fieldMapping": [
                                { "fieldSelector": "id", "databaseColumn": "id" },
                                { "constant": "PD",      "databaseColumn": "src" },
                                { "constant": 42,        "databaseColumn": "n" },
                                { "constant": true,      "databaseColumn": "flag" }
                            ]
                        }
                    ]
                }
                """;

        var spec = new JsonMappingSpecReader().readFrom(new StringReader(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();

        assertEquals(100, mapping.limit());
        assertEquals(
                List.of(
                        new FieldMappingSpec(new ValueSource.Field("id"), "id"),
                        new FieldMappingSpec(new ValueSource.Constant("PD"), "src"),
                        new FieldMappingSpec(new ValueSource.Constant(new BigDecimal("42")), "n"),
                        new FieldMappingSpec(new ValueSource.Constant(Boolean.TRUE), "flag")
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
                            "databaseTable": "t",
                            "fieldMapping": [
                                {
                                    "lookup": {
                                        "table": "country",
                                        "column": "id",
                                        "keyColumn": "iso",
                                        "fieldSelector": "c"
                                    },
                                    "databaseColumn": "country_id"
                                }
                            ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().readFrom(new StringReader(source));
        var mapping = List.copyOf(spec.recordMappingSpecs()).getFirst();
        assertEquals(
                List.of(new FieldMappingSpec(
                        new ValueSource.Lookup("country", "id", "iso", new ValueSource.Field("c")),
                        "country_id")),
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
                            "databaseTable": "t",
                            "fieldMapping": [ { "fieldSelector": "id", "databaseColumn": "id" } ]
                        }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().readFrom(new StringReader(source));
        assertEquals(null, List.copyOf(spec.recordMappingSpecs()).getFirst().limit());
    }

    @Test
    public void rejectsTwoSourcesInOneMapping() {
        var source = """
                {
                    "input": { "mimeType": "text/csv" },
                    "mapping": [
                        {
                            "recordSelector": "r",
                            "databaseTable": "t",
                            "fieldMapping": [
                                { "fieldSelector": "id", "constant": 1, "databaseColumn": "id" }
                            ]
                        }
                    ]
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> new JsonMappingSpecReader().readFrom(new StringReader(source)));
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
                        { "recordSelector": "r", "databaseTable": "t",
                          "fieldMapping": [ { "expr": "${now()}", "databaseColumn": "loaded_at" } ] }
                    ]
                }
                """;
        var spec = new JsonMappingSpecReader().readFrom(new StringReader(source));

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
                        { "recordSelector": "r", "databaseTable": "t",
                          "fieldMapping": [ { "fieldSelector": "id", "databaseColumn": "id" } ] }
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
                        { "recordSelector": "r", "databaseTable": "t", "todo": "verify",
                          "fieldMapping": [ { "fieldSelector": "id", "databaseColumn": "id", "comment": "pk" } ] }
                    ]
                }
                """;
        var reader = new JsonMappingSpecReader();
        assertEquals(
                reader.readFrom(new StringReader(bare)),
                reader.readFrom(new StringReader(annotated)));
    }
}
