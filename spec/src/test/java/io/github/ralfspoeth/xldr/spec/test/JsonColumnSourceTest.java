package io.github.ralfspoeth.xldr.spec.test;

import io.github.ralfspoeth.xldr.spec.ColumnSource;
import io.github.ralfspoeth.xldr.spec.FieldMappingSpec;
import io.github.ralfspoeth.xldr.spec.io.JsonMappingSpecReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JsonColumnSourceTest {

    /**
     * A field mapping is one of fieldSelector / constant / function; a JSON
     * constant keeps its literal type (string, number as BigDecimal, boolean);
     * and a record mapping's limit is read.
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
                                { "constant": true,      "databaseColumn": "flag" },
                                { "function": "sysdate", "databaseColumn": "loaded_at" }
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
                        new FieldMappingSpec(new ColumnSource.Field("id"), "id"),
                        new FieldMappingSpec(new ColumnSource.Constant("PD"), "src"),
                        new FieldMappingSpec(new ColumnSource.Constant(new BigDecimal("42")), "n"),
                        new FieldMappingSpec(new ColumnSource.Constant(Boolean.TRUE), "flag"),
                        new FieldMappingSpec(new ColumnSource.Function("sysdate"), "loaded_at")
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
                        new ColumnSource.Lookup("country", "id", "iso", new ColumnSource.Field("c")),
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
}
